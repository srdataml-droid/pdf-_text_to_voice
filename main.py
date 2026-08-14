"""
Kokoro Reader — local read-along PDF player.

Why not Gradio: a Gradio streaming Audio output is a live stream, not a media
file, so the browser has nothing to seek within — pause/resume/scrub can't work
reliably. Here each sentence is its own tiny WAV served over HTTP, so the native
<audio> element gives real transport controls for free.

Run:
    pip install -r requirements.txt
    uvicorn main:app --host 127.0.0.1 --port 7860
"""

from __future__ import annotations

import ctypes
import hashlib
import io
import json
import os
import queue
import re
import threading
import time
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as rt
import soundfile as sf
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse, Response
from kokoro_onnx import Kokoro
from pypdf import PdfReader

# --------------------------------------------------------------------- storage

APP_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("KOKORO_DIR", Path.home() / ".local/share/kokoro-reader"))
DOCS_DIR = DATA_DIR / "docs"
CACHE_DIR = DATA_DIR / "audio"
for d in (DATA_DIR, DOCS_DIR, CACHE_DIR):
    d.mkdir(parents=True, exist_ok=True)

RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"

# fp16 (~170MB). int8 looks like the obvious pick on a CPU-only laptop, but this
# Ryzen is Zen 3 and has no AVX512-VNNI, so its int8 matmuls fall back to a slow
# kernel: measured 2.31x slower than real time, which means playback outruns
# synthesis and the reader stalls a few sentences in. fp16 renders the same
# sentence at 0.49x real time. Override with KOKORO_MODEL — "kokoro-v1.0.onnx"
# is 310MB f32 at 0.57x, "kokoro-v1.0.int8.onnx" is the small slow one.
MODEL_FILE = os.environ.get("KOKORO_MODEL", "kokoro-v1.0.fp16.onnx")
MODEL_PATH = DATA_DIR / MODEL_FILE
VOICES_PATH = DATA_DIR / "voices-v1.0.bin"

# A sentence of speech costs ~230 KB of PCM16, so a full book runs to about a
# gigabyte per voice and the cache grows without limit. Keep a budget and evict
# the least recently played WAVs; an evicted file is only a cache miss.
CACHE_BUDGET = int(os.environ.get("KOKORO_CACHE_MB", "512")) << 20

# ONNX Runtime defaults to one intra-op thread per core plus an arena allocator
# that never hands memory back, which is half of why a long reading session ends
# up sitting on a gigabyte. Extra threads buy nothing here because SYNTH_LOCK
# serialises inference anyway. See release_heap() for the other half, and the
# measured before/after.
SYNTH_THREADS = int(os.environ.get("KOKORO_THREADS", "4"))

FALLBACK_VOICES = ["af_bella", "af_nicole", "af_sarah", "am_adam", "am_michael",
                   "bf_emma", "bf_isabella", "bm_george", "bm_lewis"]


def _download(url: str, dest: Path) -> None:
    """Download to a .part file and rename on success, so a killed download
    never leaves a truncated model behind."""
    tmp = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "kokoro-reader/1.0"})
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {dest.name}  {done / total:6.1%}", end="", flush=True)
    print(f"\r  {dest.name}  done{' ' * 12}")
    tmp.replace(dest)


def load_engine() -> Kokoro:
    if not MODEL_PATH.exists():
        print(f"Fetching {MODEL_FILE} (one time)...")
        _download(f"{RELEASE}/{MODEL_FILE}", MODEL_PATH)
    if not VOICES_PATH.exists():
        print("Fetching voices-v1.0.bin (one time)...")
        _download(f"{RELEASE}/voices-v1.0.bin", VOICES_PATH)

    opts = rt.SessionOptions()
    opts.enable_cpu_mem_arena = False       # arena growth is what pins ~400 MB
    opts.intra_op_num_threads = SYNTH_THREADS
    opts.inter_op_num_threads = 1
    opts.execution_mode = rt.ExecutionMode.ORT_SEQUENTIAL
    session = rt.InferenceSession(
        str(MODEL_PATH), sess_options=opts, providers=["CPUExecutionProvider"]
    )
    return Kokoro.from_session(session, str(VOICES_PATH))


KOKORO = load_engine()
SYNTH_LOCK = threading.Lock()   # ONNX inference is serialised; one CPU, one job


# Synthesis allocates and frees tens of MB per sentence, and glibc parks the
# freed pages in a per-thread arena rather than returning them; uvicorn runs the
# endpoints across a threadpool, so the arenas multiply. malloc_trim walks them
# all and gives the free tops back to the kernel, for a few ms against the
# seconds a sentence takes to render.
#
# Together with the session options above, measured on this 8-core/13 GB laptop
# by synthesising one 49-sentence document through the running server:
# 998 MB resident before, 584 MB after, with wall time unchanged (89s vs 93s).
try:
    _libc = ctypes.CDLL("libc.so.6")
    _libc.malloc_trim.argtypes = [ctypes.c_size_t]
except OSError:                 # not glibc; nothing to trim
    _libc = None


def release_heap() -> None:
    if _libc is not None:
        _libc.malloc_trim(0)

try:
    VOICES = sorted(KOKORO.get_voices())
except Exception:
    VOICES = FALLBACK_VOICES

# ------------------------------------------------------------ text preparation

_ABBREV = ["Mr", "Mrs", "Ms", "Dr", "Prof", "Sr", "Jr", "St", "No", "Vol", "Fig",
           "Eq", "Ref", "vs", "cf", "al", "Inc", "Ltd", "Co", "e.g", "i.e",
           "approx", "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sept",
           "Oct", "Nov", "Dec"]

# Split after . ! ? — but not after a known abbreviation, and only when the next
# thing looks like the start of a sentence.
_NOT_ABBREV = "".join(rf"(?<!{re.escape(a)}\.)" for a in _ABBREV)
SENT_END = re.compile(rf"{_NOT_ABBREV}(?<=[.!?])[\"')\]]*\s+(?=[\"'(\[]*[A-Z0-9])")

MIN_CHARS = 24
MAX_CHARS = 320   # Kokoro's context is 510 tokens; stay well clear of it

BULLETS = "\u2022\u25aa\u25e6\u2023\u00b7\u2219\u25c6\u25c7\u25cf\u25cb\u25a0\u25a1\u27a4\u27a2*"
_LIST_MARK = re.compile(rf"^[{re.escape(BULLETS)}]+[ \t]*", re.M)

# Kokoro phonemises to nothing when a chunk holds no letters or digits, and an
# empty phoneme list crashes the ONNX call. A lone "\u2022" off a bulleted list is
# the usual culprit, so such chunks never become sentences.
_HAS_SPEECH = re.compile(r"[A-Za-z0-9]")


def is_speakable(s: str) -> bool:
    return bool(_HAS_SPEECH.search(s))


def clean_text(raw: str) -> str:
    t = raw.replace("\r", "").replace("\u00ad", "")
    t = re.sub(r"(\w)-\n(\w)", r"\1\2", t)              # rejoin hyphenated words
    t = re.sub(r"\n\s*(\d{1,4}|[ivxlcIVXLC]{1,7})\s*\n", "\n", t)  # bare page numbers
    t = _LIST_MARK.sub("", t)                           # drop bullet glyphs, keep the item
    t = re.sub(r"[ \t]*\n(?=[a-z,;:)\-])", " ", t)      # unwrap soft line breaks
    t = re.sub(r"[ \t]{2,}", " ", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    return t.strip()


def _hard_wrap(s: str) -> list[str]:
    """Break an over-long sentence at the latest clause boundary that fits."""
    if len(s) <= MAX_CHARS:
        return [s]
    for sep in ("; ", ": ", " — ", ", "):
        cut = s.rfind(sep, MIN_CHARS, MAX_CHARS)
        if cut > 0:
            return [s[:cut + 1].strip()] + _hard_wrap(s[cut + len(sep):].strip())
    cut = s.rfind(" ", MIN_CHARS, MAX_CHARS)
    if cut <= 0:
        cut = MAX_CHARS
    return [s[:cut].strip()] + _hard_wrap(s[cut:].strip())


def _merge_runts(items: list[str]) -> list[str]:
    """Glue stray fragments onto their neighbour so playback doesn't stutter."""
    out: list[str] = []
    for s in items:
        short = len(s) < MIN_CHARS or (out and len(out[-1]) < MIN_CHARS)
        if out and short and len(out[-1]) + len(s) + 1 <= MAX_CHARS:
            out[-1] = f"{out[-1]} {s}"
        else:
            out.append(s)
    return out


def build_sentences(pages: list[str]) -> list[dict]:
    """-> [{i, text, page, para}] with paragraph grouping preserved."""
    records: list[dict] = []
    para_no = 0
    for page_no, raw in enumerate(pages, start=1):
        text = clean_text(raw or "")
        if not text:
            continue
        for para in re.split(r"\n\s*\n|\n", text):
            para = para.strip()
            if not para:
                continue
            parts: list[str] = []
            for chunk in SENT_END.split(para):
                chunk = chunk.strip()
                if chunk:
                    parts.extend(_hard_wrap(chunk))
            parts = _merge_runts([p for p in parts if is_speakable(p)])
            if not parts:
                continue
            for s in parts:
                records.append({"i": len(records), "text": s,
                                "page": page_no, "para": para_no})
            para_no += 1
    return records


# ------------------------------------------------------------------- doc store

def doc_file(doc_id: str) -> Path:
    return DOCS_DIR / f"{doc_id}.json"


def load_doc(doc_id: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{16}", doc_id) or not doc_file(doc_id).exists():
        raise HTTPException(404, "That document isn't loaded. Open the PDF again.")
    return json.loads(doc_file(doc_id).read_text())


# -------------------------------------------------------------------- synthesis

def wav_path(doc_id: str, voice: str, idx: int) -> Path:
    # Keyed by model too: switching KOKORO_MODEL must not replay audio that the
    # previous model rendered.
    return CACHE_DIR / doc_id / MODEL_PATH.stem / voice / f"{idx:05d}.wav"


# ------------------------------------------------------------- cache eviction

_TOUCH_AFTER = 3600         # don't rewrite mtime more than hourly per file
_SWEEP_EVERY = 32 << 20     # bytes written between budget checks
_sweep_lock = threading.Lock()
_unswept = 0


def _mark_used(path: Path) -> None:
    """Refresh mtime so a sentence you actually played survives the next sweep.
    Rate-limited because this runs on every cache hit."""
    try:
        if time.time() - path.stat().st_mtime > _TOUCH_AFTER:
            os.utime(path, None)
    except OSError:
        pass


def cache_size() -> tuple[int, int]:
    """-> (bytes, file count) currently held in the audio cache."""
    total = count = 0
    for p in CACHE_DIR.rglob("*.wav"):
        try:
            total += p.stat().st_size
        except OSError:
            continue
        count += 1
    return total, count


def sweep_cache(budget: int = CACHE_BUDGET) -> int:
    """Evict least-recently-played WAVs until the cache fits the budget.
    Returns bytes freed. Safe at any time: a missing WAV is re-synthesised."""
    entries: list[tuple[float, int, Path]] = []
    total = 0
    for p in CACHE_DIR.rglob("*.wav"):
        try:
            st = p.stat()
        except OSError:
            continue
        entries.append((st.st_mtime, st.st_size, p))
        total += st.st_size
    if total <= budget:
        return 0

    entries.sort()                              # oldest first
    freed = 0
    for _, size, p in entries:
        if total - freed <= budget:
            break
        try:
            p.unlink()
        except OSError:
            continue
        freed += size

    for d in sorted(CACHE_DIR.rglob("*"), key=lambda q: len(q.parts), reverse=True):
        if d.is_dir():
            try:
                d.rmdir()                       # only succeeds when empty
            except OSError:
                pass
    return freed


def _note_write(n: int) -> None:
    """Check the budget once per _SWEEP_EVERY bytes rather than every write."""
    global _unswept
    with _sweep_lock:
        _unswept += n
        if _unswept < _SWEEP_EVERY:
            return
        _unswept = 0
    freed = sweep_cache()
    if freed:
        print(f"cache over {CACHE_BUDGET >> 20} MB budget; freed {freed >> 20} MB")


def _read_cached(path: Path) -> bytes | None:
    """-> the cached WAV, or None to mean "render it". Reads without testing
    exists() first: a sweep can unlink the file between the test and the open,
    and a cache miss must never surface as a 500."""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    _mark_used(path)
    return data


def synth(doc_id: str, voice: str, idx: int) -> bytes:
    """Render one sentence to WAV. Always at speed 1.0 — playback speed is a
    client-side playbackRate, so changing it never invalidates this cache."""
    path = wav_path(doc_id, voice, idx)
    hit = _read_cached(path)
    if hit is not None:
        return hit

    text = load_doc(doc_id)["sentences"][idx]["text"]
    with SYNTH_LOCK:
        hit = _read_cached(path)                # another request beat us to it
        if hit is not None:
            return hit
        try:
            samples, rate = KOKORO.create(text, voice=voice, speed=1.0, lang="en-us")
        except ValueError:                      # no phonemes came out of the text
            samples, rate = None, 24000
        if samples is None or len(samples) == 0:
            # A sentence that renders to nothing must still answer with a WAV.
            # The client only advances on the <audio> `ended` event, so a 500
            # here would stall the read at this sentence forever.
            print(f"empty synthesis for {doc_id}/{voice}/{idx}: {text[:40]!r}")
            samples = np.zeros(rate // 8, dtype=np.float32)
        buf = io.BytesIO()
        sf.write(buf, samples, rate, format="WAV", subtype="PCM_16")
        data = buf.getvalue()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(path)
    release_heap()              # outside the lock; both of these are slow-ish
    _note_write(len(data))      # and must not stall the next synthesis
    return data


PREFETCH_Q: "queue.Queue[tuple[str, str, int]]" = queue.Queue()
_queued: set[tuple[str, str, int]] = set()
_queued_lock = threading.Lock()


def enqueue(job: tuple[str, str, int]) -> None:
    if wav_path(*job).exists():
        return
    with _queued_lock:
        if job in _queued:
            return
        _queued.add(job)
    PREFETCH_Q.put(job)


def _prefetch_worker() -> None:
    while True:
        job = PREFETCH_Q.get()
        try:
            synth(*job)
        except Exception as exc:                # a bad sentence shouldn't kill the worker
            print(f"prefetch {job} failed: {exc}")
        finally:
            with _queued_lock:
                _queued.discard(job)
            PREFETCH_Q.task_done()


threading.Thread(target=_prefetch_worker, daemon=True).start()


def _startup_sweep() -> None:
    used, files = cache_size()
    print(f"audio cache: {used >> 20} MB in {files} files "
          f"(budget {CACHE_BUDGET >> 20} MB, set KOKORO_CACHE_MB to change)")
    freed = sweep_cache()
    if freed:
        print(f"  evicted {freed >> 20} MB of least recently played audio")


threading.Thread(target=_startup_sweep, daemon=True).start()

# ------------------------------------------------------------------------- api

app = FastAPI(title="Kokoro Reader")


@app.get("/")
def index() -> FileResponse:
    return FileResponse(APP_DIR / "index.html")


@app.get("/api/voices")
def get_voices() -> dict:
    return {"voices": VOICES, "default": "af_bella" if "af_bella" in VOICES else VOICES[0]}


@app.get("/api/cache")
def get_cache() -> dict:
    used, files = cache_size()
    return {"bytes": used, "files": files, "budget": CACHE_BUDGET}


@app.delete("/api/cache")
def clear_cache() -> dict:
    """Drop every rendered WAV. Costs re-synthesis, never the reading position —
    that lives in localStorage, and the parsed documents stay on disk."""
    freed = sweep_cache(budget=0)
    used, files = cache_size()
    return {"freed": freed, "bytes": used, "files": files, "budget": CACHE_BUDGET}


@app.post("/api/doc")
async def create_doc(file: UploadFile = File(...)) -> dict:
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "That file was empty.")
    doc_id = hashlib.sha256(raw).hexdigest()[:16]

    if doc_file(doc_id).exists():               # same PDF as before — reuse everything
        return load_doc(doc_id)

    try:
        reader = PdfReader(io.BytesIO(raw))
        pages = [p.extract_text() or "" for p in reader.pages]
    except Exception as exc:
        raise HTTPException(422, f"Couldn't open that PDF: {exc}")

    sentences = build_sentences(pages)
    if not sentences:
        raise HTTPException(
            422,
            "No text layer in that PDF. It's probably a scan — run it through OCR "
            "(ocrmypdf in.pdf out.pdf) and try again.",
        )

    doc = {
        "doc_id": doc_id,
        "title": (file.filename or "Untitled").rsplit(".", 1)[0],
        "page_count": len(pages),
        "sentences": sentences,
    }
    doc_file(doc_id).write_text(json.dumps(doc))
    return doc


@app.get("/api/doc/{doc_id}")
def get_doc(doc_id: str) -> dict:
    return load_doc(doc_id)


@app.get("/api/doc/{doc_id}/audio/{idx}")
def get_audio(doc_id: str, idx: int, voice: str = "af_bella") -> Response:
    doc = load_doc(doc_id)
    total = len(doc["sentences"])
    if not 0 <= idx < total:
        raise HTTPException(404, "No sentence at that position.")
    if voice not in VOICES:
        raise HTTPException(400, f"Unknown voice. Available: {', '.join(VOICES)}")

    data = synth(doc_id, voice, idx)
    for j in range(idx + 1, min(idx + 4, total)):   # keep three sentences ahead warm
        enqueue((doc_id, voice, j))

    return Response(
        content=data,
        media_type="audio/wav",
        headers={"Cache-Control": "public, max-age=604800",
                 "Content-Length": str(len(data))},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=7860)
