#!/usr/bin/env python3
"""Inspect pinned candidates, verify a local file, or explicitly download one.

Uses only the Python standard library. A download never runs by default.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent


def checksum(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(candidate, path):
    size = path.stat().st_size
    sha = checksum(path)
    result = {"candidate": candidate["id"], "file": str(path.resolve()), "bytes": size,
              "sha256": sha, "matches_pinned_artifact": size == candidate["bytes"] and sha == candidate["sha256"]}
    print(json.dumps(result, indent=2))
    return result["matches_pinned_artifact"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["list", "verify", "download"])
    parser.add_argument("candidate", nargs="?")
    parser.add_argument("--file", type=Path, help="Local file to verify")
    parser.add_argument("--directory", type=Path, help="Download destination; required for download")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "candidates.json").read_text())
    if args.action == "list":
        print(json.dumps(manifest, indent=2))
        return
    matches = [c for c in manifest["candidates"] if c["id"] == args.candidate]
    if len(matches) != 1:
        parser.error("Choose an exact candidate id from the list action.")
    candidate = matches[0]
    if args.action == "verify":
        if args.file is None:
            parser.error("verify requires --file")
        raise SystemExit(0 if verify(candidate, args.file) else 1)
    if args.directory is None:
        parser.error("download requires --directory (downloads up to 2.1 GB per candidate)")
    args.directory.mkdir(parents=True, exist_ok=True)
    target = args.directory / candidate["filename"]
    if target.exists():
        raise SystemExit(0 if verify(candidate, target) else 1)
    temp = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(candidate["download_url"], timeout=90) as response, temp.open("wb") as dest:
            total = 0
            while block := response.read(4 * 1024 * 1024):
                total += len(block)
                if total > candidate["bytes"]:
                    raise RuntimeError("Server response exceeds pinned artifact size")
                dest.write(block)
            dest.flush()
            os.fsync(dest.fileno())
        if not verify(candidate, temp):
            raise RuntimeError("Downloaded artifact failed its pinned size or checksum")
        temp.replace(target)
        print("Verified artifact saved to " + str(target.resolve()))
    except BaseException:
        temp.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    main()
