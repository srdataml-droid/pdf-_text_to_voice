# Native local AI build

The APK embeds CPU-only llama.cpp through `libanchor_llama.so`. The app has no internet permission and imports model files through Android's file picker. No model weights are bundled.

Runtime is pinned to llama.cpp commit `d05706b4f32a545814856e98ca518292585443b9`. Native source is fetched at build time by `scripts/build-native.sh`; it is not needed to use the supplied APK. The first build needs internet access to GitHub. To build without network, provide an existing checkout at the exact commit through `ANCHOR_LLAMA_SOURCE`.

Prerequisites: Git, CMake 3.22 or newer, Ninja, Android NDK r28c (`28.2.13676358` tested), Java 17+, Android SDK and Gradle as described in the main instructions.

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358
bash scripts/build-native.sh
gradle :app:assembleDebug
```

`ANCHOR_NATIVE_BUILD` optionally selects an intermediate build directory; `ANCHOR_BUILD_JOBS` defaults to 2. `CMAKE_BIN` and `NINJA_BIN` can select installed executables. The result is staged at `app/src/main/jniLibs/arm64-v8a/libanchor_llama.so` for Android's normal packaging. C++ runtime and llama.cpp dependencies are linked statically. The output supports 16 KB page alignment and targets Android API 26 or later on arm64.

The default is a portable arm64 CPU build. After checking the actual target's `/proc/cpuinfo` for `asimddp` and `asimdhp`, set `ANCHOR_ARM_ARCH=armv8.2-a+dotprod` for an optimized build. This raises the required CPU instruction set; do not distribute that variant to older arm64 phones without those features. The user's connected HONOR reports both features. Benchmark reports include the runtime's CPU feature information.

Initial settings are CPU-only, 2 threads, mmap without mlock, 1024 context, 128-token prompt batches, 64-token microbatches and at most 192 generated tokens. These are conservative starting values, not established optimal settings for the HONOR phone. Imported files are copied to private storage, have a SHA-256 recorded, and are validated by the native loader. The official Granite Q4_K_M checksum is recognized; arbitrary GGUF files are never labeled official.

Send loads an imported model when requested. Stop uses an atomic cancellation flag read by model loading, prefill and generation. Unload queues native release after current work. No model is loaded at app startup. Conversations are formatted with the model's own supported chat template; old turns are dropped to fit the small context, preserving the current request. Model-generated text cannot execute planner changes.

Benchmark explicitly reloads the model and records real device observations for three short-prompt runs, including token counts, first-token timing, generation speed, sampled process PSS, available RAM, thermal state and airplane mode before and after. OS file cache is not cleared. These measurements do not establish sustained thermal behavior or task accuracy. Reports stay private unless the user exports them through Android's file picker.

For an actual release, repeat the tests on the target handset. A successful desktop build is not a measurement of the HONOR's model speed or memory survival.
