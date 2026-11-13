#!/usr/bin/env bash
set -euo pipefail
# This host-side helper downloads one official model. The Android app has no network access.
case "${1:-granite}" in
  granite)
    file=granite-4.1-3b-Q4_K_M.gguf
    url=https://huggingface.co/ibm-granite/granite-4.1-3b-GGUF/resolve/ab4701481089b58a082ef63cc1cee738887293ff/$file
    sha=662b0626cd58f443baea23559b469df6576a81d349649c59413b36a9fb32eb29
    ;;
  small)
    file=qwen2.5-1.5b-instruct-q4_k_m.gguf
    url=https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/$file
    sha=6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e
    ;;
  *) echo 'Usage: download-model.sh [granite|small] [output-directory]' >&2; exit 2 ;;
esac
out=${2:-models}
mkdir -p "$out"
curl --fail --location --retry 3 --continue-at - --output "$out/$file.part" "$url"
printf '%s  %s\n' "$sha" "$out/$file.part" | sha256sum --check
mv "$out/$file.part" "$out/$file"
printf 'Verified model: %s\n' "$out/$file"
