#!/usr/bin/env bash
set -euo pipefail

ANCHOR_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_COMMIT=d05706b4f32a545814856e98ca518292585443b9
LLAMA_SOURCE_DIR="${ANCHOR_LLAMA_SOURCE:-$ANCHOR_ROOT/app/src/main/cpp/llama.cpp}"
NATIVE_BUILD_DIR="${ANCHOR_NATIVE_BUILD:-$ANCHOR_ROOT/.build/native-arm64}"
NDK_DIRECTORY="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [[ -z "$NDK_DIRECTORY" || ! -f "$NDK_DIRECTORY/build/cmake/android.toolchain.cmake" ]]; then
  printf '%s\n' 'Set ANDROID_NDK_HOME to Android NDK 28.2.13676358 (r28c) or a compatible NDK.' >&2
  exit 1
fi
if [[ ! -f "$LLAMA_SOURCE_DIR/CMakeLists.txt" ]]; then
  mkdir -p "$LLAMA_SOURCE_DIR"
  git -C "$LLAMA_SOURCE_DIR" init
  git -C "$LLAMA_SOURCE_DIR" remote add origin https://github.com/ggml-org/llama.cpp.git
  git -C "$LLAMA_SOURCE_DIR" fetch --depth 1 origin "$LLAMA_COMMIT"
  git -C "$LLAMA_SOURCE_DIR" checkout --detach FETCH_HEAD
fi
ACTUAL_COMMIT="$(git -C "$LLAMA_SOURCE_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$LLAMA_COMMIT" ]]; then
  printf 'llama.cpp must be at %s; found %s. Use a separate source directory.\n' "$LLAMA_COMMIT" "$ACTUAL_COMMIT" >&2
  exit 1
fi
"${CMAKE_BIN:-cmake}" -S "$ANCHOR_ROOT/app/src/main/cpp" -B "$NATIVE_BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_DIRECTORY/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DANCHOR_LLAMA_DIR="$LLAMA_SOURCE_DIR" \
  -DGGML_CPU_ARM_ARCH="${ANCHOR_ARM_ARCH:-}" \
  -DCMAKE_MAKE_PROGRAM="${NINJA_BIN:-ninja}"
"${CMAKE_BIN:-cmake}" --build "$NATIVE_BUILD_DIR" --target anchor_llama --parallel "${ANCHOR_BUILD_JOBS:-2}"
mkdir -p "$ANCHOR_ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$NATIVE_BUILD_DIR/libanchor_llama.so" "$ANCHOR_ROOT/app/src/main/jniLibs/arm64-v8a/libanchor_llama.so"
NDK_STRIP=""
for STRIP_CANDIDATE in "$NDK_DIRECTORY"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
  if [[ -x "$STRIP_CANDIDATE" ]]; then NDK_STRIP="$STRIP_CANDIDATE"; break; fi
done
if [[ -z "$NDK_STRIP" ]]; then printf '%s\n' 'NDK llvm-strip was not found.' >&2; exit 1; fi
"$NDK_STRIP" --strip-unneeded "$ANCHOR_ROOT/app/src/main/jniLibs/arm64-v8a/libanchor_llama.so"
cp "$LLAMA_SOURCE_DIR/LICENSE" "$ANCHOR_ROOT/docs/llama.cpp-LICENSE.txt"
printf '%s\n' "Native CPU library staged. Runtime commit: $LLAMA_COMMIT"
