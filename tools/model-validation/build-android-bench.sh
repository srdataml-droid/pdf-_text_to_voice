#!/usr/bin/env bash
set -euo pipefail
# Builds only from an already-present, pinned checkout; never fetches source.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="${ANCHOR_LLAMA_SOURCE:?Set ANCHOR_LLAMA_SOURCE to the pinned llama.cpp checkout}"
NDK_DIRECTORY="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28c}"
BUILD="${ANCHOR_BENCH_BUILD:-$ROOT/../../work/model-validation/android-build}"
COMMIT=d05706b4f32a545814856e98ca518292585443b9
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$COMMIT" ]] || { printf '%s\n' 'Wrong llama.cpp commit.' >&2; exit 1; }
[[ -z "$(git -C "$SOURCE" status --porcelain --untracked-files=no)" ]] || { printf '%s\n' 'Tracked source modifications must be reviewed before building.' >&2; exit 1; }
cmake -S "$SOURCE" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_DIRECTORY/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_COMMON=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_OPENSSL=OFF \
  -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=OFF -DGGML_LLAMAFILE=OFF \
  -DGGML_CPU_ARM_ARCH="${ANCHOR_ARM_ARCH:-}" \
  -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
cmake --build "$BUILD" --target llama-bench --parallel "${ANCHOR_BUILD_JOBS:-2}"
mkdir -p "$ROOT/bin"
cp "$BUILD/bin/llama-bench" "$ROOT/bin/llama-bench-android-arm64"
STRIP_BINARY=""
for CANDIDATE in "$NDK_DIRECTORY"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
  if [[ -x "$CANDIDATE" ]]; then STRIP_BINARY="$CANDIDATE"; break; fi
done
[[ -n "$STRIP_BINARY" ]] || { printf '%s\n' 'NDK llvm-strip missing.' >&2; exit 1; }
"$STRIP_BINARY" --strip-unneeded "$ROOT/bin/llama-bench-android-arm64"
sha256sum "$ROOT/bin/llama-bench-android-arm64" > "$ROOT/bin/SHA256SUMS"
printf 'runtime_commit=%s\nandroid_api=26\nabi=arm64-v8a\narm_arch=%s\nbackend=CPU\nopenmp=OFF\n' \
  "$COMMIT" "${ANCHOR_ARM_ARCH:-portable}" > "$ROOT/bin/build-info.txt"
printf 'Built Android CPU benchmark from %s\n' "$COMMIT"
