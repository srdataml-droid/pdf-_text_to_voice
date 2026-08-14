# Kokoro Reader

Local, offline PDF read-along player. FastAPI backend + one HTML file. No build step,
no framework, no database. Runs on a CPU-only Fedora laptop.

```
main.py       PDF -> sentences -> per-sentence WAV, cached on disk
index.html    reader UI, transport, sync highlight (vanilla JS, no deps)
```

## Invariants — do not break these without asking

1. **One sentence = one WAV file = one HTTP request.** This is what makes pause,
   resume, and seek work. Do not replace it with a stream, a websocket, or a
   single concatenated file.
2. **Synthesis is always `speed=1.0`.** Playback speed is `audio.playbackRate` on
   the client. Baking speed into the audio would invalidate the whole cache on
   every slider move.
3. **Two `<audio>` elements, ping-ponged.** One plays while the other preloads the
   next sentence. That's what removes the gap between sentences.
4. **`SYNTH_LOCK` stays.** One ONNX inference at a time. This machine has no GPU
   and 13.4 GB of RAM; parallel synthesis makes everything slower, not faster.
5. **No `localStorage` key format changes** without a migration — it holds the
   user's saved reading position.
6. **Cache writes go to a `.tmp` file then `.replace()`.** Never write a cache
   entry in place; a crash mid-write must not poison the cache.
7. **The audio cache is disposable, and bounded.** Any WAV may be evicted at any
   time; a missing one is just a cache miss and gets re-synthesised. Nothing may
   ever assume a cached sentence is still there. Reading position lives in
   `localStorage` and parsed documents live in `docs/` — neither is cache.

## Disk and memory

A sentence of speech is ~230 KB of PCM16, so one book is roughly a gigabyte per
voice and the cache grows without limit if you let it. Three knobs, all env vars:

| Variable | Default | What it does |
| --- | --- | --- |
| `KOKORO_CACHE_MB` | `512` | Cache budget. Least recently played WAVs are evicted past it, on startup and every 32 MB written. |
| `KOKORO_THREADS` | `4` | ONNX intra-op threads. More is not faster; `SYNTH_LOCK` serialises inference anyway. |
| `KOKORO_MODEL` | `kokoro-v1.0.fp16.onnx` | See the note above `MODEL_FILE` before changing this. |

Two things kept resident memory near a gigabyte and both are now handled in
`main.py` — the ONNX CPU arena (disabled in `load_engine`) and glibc's per-thread
malloc arenas (`release_heap`, called after each synthesis). Measured over one
49-sentence document through the running server: 998 MB before, 584 MB after,
wall time unchanged. If you touch either, re-measure; don't assume.

The server holds the model resident for as long as it runs. Stop it when you
aren't reading — there is no idle unload.

## Style

- Standard library and the deps already in `requirements.txt`. Adding a dependency
  needs a reason stated in the commit message.
- Backend: type hints on public functions, no classes where a function does.
- Frontend: no framework, no bundler, no CSS-in-JS. Colours come from the CSS
  custom properties at the top of `index.html` — never hardcode a hex value below
  that block.
- Keep `index.html` a single file.

## Testing

There is no test suite yet. Before saying a change works, actually run it:

```bash
uvicorn main:app --port 7860 &
curl -sf localhost:7860/api/voices | head -c 200
```

Then load a real PDF in the browser and confirm audio plays, pause holds position,
and clicking a sentence jumps to it. "It should work" is not a result.

## Known rough edges

- Scanned PDFs with no text layer are rejected. OCR is out of scope for now.
- The sentence splitter is regex-based; it will occasionally break on citation-heavy
  academic text. Fix by extending `_ABBREV`, not by adding an NLP dependency.
- First run downloads ~88 MB of model files to `~/.local/share/kokoro-reader`.
