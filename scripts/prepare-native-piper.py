"""Prepare pinned, checksum-verified Android-only offline speech assets."""
from pathlib import Path, PurePosixPath
import hashlib, shutil, tarfile, urllib.request
ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT.parent / 'build-tools' / 'native-tts'
CACHE.mkdir(parents=True, exist_ok=True)
PACKS = [
 ('sherpa-onnx-1.13.8.aar', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar', '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'),
 ('piper-lessac-medium.tar.bz2', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2', '9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e'),
]
for name, url, digest in PACKS:
    file = CACHE / name
    if not file.exists():
        pending = file.with_suffix(file.suffix + '.part')
        urllib.request.urlretrieve(url, pending)
        pending.replace(file)
    if hashlib.sha256(file.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Checksum mismatch: ' + name)
libs = ROOT / 'android/app/libs'
libs.mkdir(parents=True, exist_ok=True)
shutil.copy2(CACHE / PACKS[0][0], libs / PACKS[0][0])
destination = ROOT / 'android/app/src/main/assets/native-piper'
destination.mkdir(parents=True, exist_ok=True)
with tarfile.open(CACHE / PACKS[1][0]) as archive:
    prefix = PurePosixPath('vits-piper-en_US-lessac-medium')
    for member in archive:
        relative = PurePosixPath(member.name).relative_to(prefix)
        if '..' in relative.parts or member.issym() or member.islnk():
            raise RuntimeError('Unsafe archive entry')
        if not member.isfile(): continue
        target = destination.joinpath(*relative.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open('wb') as output:
            shutil.copyfileobj(source, output)
(destination / 'pack-version.txt').write_text(PACKS[1][2])
print('Prepared native Piper and sherpa-onnx 1.13.8 for Android.')
