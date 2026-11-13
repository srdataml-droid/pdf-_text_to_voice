# Anchor model validation — 28 October 2026

All three requested models exist and have official GGUF artifacts. The current pinned llama.cpp source contains implementations for `lfm3`, `granite`, and `lfm2`. This establishes a plausible CPU inference route, not a successful Android load or a performance winner. The current check found no connected phone, so no new phone speed, first-token time, or memory result is claimed here.

| Candidate | Official Q4_K_M size | Architecture | Current qualification |
|---|---:|---|---|
| Liquid AI LFM3-1.7B | 1,099,627,808 bytes; 1.024 GiB | New convolution/attention hybrid | Metadata inspected; app plain-text nonthinking formatter verified on the host; Android execution pending |
| IBM Granite 4.1 3B | 2,099,501,664 bytes; 1.955 GiB | Dense transformer | Existing full local file verified by SHA-256; Android execution pending |
| Liquid AI LFM2.5-1.2B-Instruct | 730,895,168 bytes; 0.681 GiB | Convolution/attention hybrid | Metadata inspected; Android execution pending |

The exact immutable revisions, filenames, SHA-256 values, licenses, and download URLs are in [candidates.json](candidates.json). Both Liquid models use the LFM Open License v1.0; Granite uses Apache 2.0. File size is not total working memory. Quantized weights, caches/state, computation buffers, app memory, and Android's available memory must all be measured together.

Start testing LFM2.5 as the smallest file, then LFM3, then Granite. This is an engineering test order, not a measured ranking. Keep one model loaded at a time. The app currently uses CPU, two threads, 1024-token context, 128-token prompt batches, 64-token microbatches, and at most 192 output tokens. Larger advertised model context limits do not establish usable phone settings.

## What is known about this handset

The previous session recorded HONOR ANY-NX1, Android 13/API 33, arm64, `MemTotal=5,619,092 kB` (about 5.36 GiB), and CPU FP16/dot-product features. These are previous observations, not measurements repeated during this validation. That session's test log reports 14 instrumentation tests passing; those tests cover native error paths and planner behavior, not successful model generation.

HONOR's official specification describes a 6 GB physical-memory configuration plus 2 GB RAM Turbo taken from storage. Calling that 8 GB of physical RAM would be wrong. Exact available memory varies with the device state and must be sampled again before loading. [HONOR specification](https://www.honor.com/za/phones/honor-magic4-lite-5g/)

The current `adb devices -l` check returned no attached devices. The command automatically started a previously absent local adb server; it did not operate on a phone. The included runner checks for an existing server before making adb calls and requires an explicit serial.

## The LFM3 app compatibility correction

Architecture support alone was insufficient. The app's small native renderer previously recognized LFM3's template as generic ChatML and would omit its explicit thinking control. Inspection of the actual downloaded GGUF header also showed `add_bos_token=false`, although the official template emits a beginning-of-text token.

The native adapter now handles the vendor's plain-text system/user/assistant subset explicitly. It emits the vocabulary BOS when automatic BOS is disabled, normalizes prior assistant reasoning like the official template, and appends `<think></think>` to request nonthinking output within the small phone output budget. It rejects unsupported message roles. It does not implement Liquid's tool-call or multimodal template paths. The app's deterministic local actions remain application code.

Eight host cases match the pinned vendor Jinja output, including BOS, Unicode, multi-turn chat, empty/no system messages, and prior reasoning. Four invalid input cases are rejected. The updated arm64 JNI library builds successfully against the existing pinned runtime. These results do not establish that any model loads, generates correct answers, cancels promptly, or survives Android memory pressure.

Reproduce the formatting check with Python, Jinja2, and a host C++17 compiler:

```sh
python3 verify-lfm3-template.py --source /absolute/path/to/anchor-android/app/src/main/cpp/anchor_llama.cpp
```

The actual template embedded in the LFM3 GGUF header matches the pinned source template used by this test. The partial-header evidence predates completion of the full LFM downloads and must not be treated as full-file integrity verification. See `evidence/lfm3-partial-header.json`, `evidence/lfm3-template-parity.json`, and the final full-file verification records when available.

## Reproducible benchmark tools

The primary three-way comparison uses each publisher's Q4_K_M artifact. Do not silently substitute the similarly named QAD Q4_0 variants. Run QAD as a separately labeled experiment if desired. Equal quantization labels do not guarantee equal quality loss.

`candidate-tool.py` lists candidates, verifies a local file, and downloads only when its `download` action is explicitly selected. It never downloads anything during a dry run:

```sh
python3 candidate-tool.py list
python3 candidate-tool.py verify granite-4.1-3b-q4_k_m --file /path/to/granite-4.1-3b-Q4_K_M.gguf
python3 candidate-tool.py download lfm3-1.7b-q4_k_m --directory /path/to/model-storage
```

`inspect-gguf.py` reads header metadata without mapping or running tensors. It is useful for checking architecture, special-token policy, and template presence, and is not a model integrity or execution test.

`bin/llama-bench-android-arm64` is a stripped, CPU-only Android API 26 arm64 executable built from llama.cpp commit `d05706b4f32a545814856e98ca518292585443b9` with NDK r28c. It uses `armv8.2-a+dotprod`, matching features recorded on this target in the previous session. Do not run this optimized variant on an unverified arm64 device. Its checksum and configuration are beside it. It depends only on Android system libc/libm/libdl; the C++ and llama.cpp libraries are statically linked.

Rebuild without fetching dependencies:

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28c
export ANCHOR_LLAMA_SOURCE=/absolute/path/to/pinned/llama.cpp
export ANCHOR_ARM_ARCH=armv8.2-a+dotprod
bash build-android-bench.sh
```

Unset `ANCHOR_ARM_ARCH` for a portable build and label any comparison using a different binary accordingly. Building and ELF inspection passed on the host. The binary has not been executed on Android in this validation.

Preview a benchmark plan without a phone, binary execution, or weight download:

```sh
python3 run-android-bench.py --candidate lfm3-1.7b-q4_k_m --model /path/to/LFM3-1.7B-Q4_K_M.gguf
```

To execute, supply an explicitly selected, authorized handset and add `--run --serial DEVICE_SERIAL`. This stages the verified model and binary into a new `/data/local/tmp/anchor-model-bench-*` folder, executes the benchmark, and saves raw output and memory/battery/thermal/airplane-mode observations under `runs/`. It does not change phone settings or delete app data. The staging directory is retained for inspection. An interrupted or failed run must be labeled as such.

The synthetic sweep uses prompt lengths 256 and 1024, 128 generated tokens, two and four threads, three repetitions, CPU-only, mmap, batch 128 and microbatch 64. OS caches remain uncontrolled and upstream warmup remains enabled. `llama-bench` sizes its context from its tests; this is not the app's fixed 1024-token context. It excludes tokenization and sampling, so its tokens/second must not be presented as app end-to-end latency. Host-driven memory sampling adds overhead and can miss brief peaks. A shell process has different lifecycle and memory behavior from an installed Android app. [Upstream benchmark semantics](https://github.com/ggml-org/llama.cpp/blob/d05706b4f32a545814856e98ca518292585443b9/tools/llama-bench/README.md)

For the user-facing result, import each verified file into Anchor and use the in-app benchmark as well. Preserve each report before importing the next model; the app retains only its latest benchmark. Compare the same short prompt, actual input/output token counts, nonthinking settings, first-token time, generation speed, peak sampled process PSS, minimum available memory, thermal state, and errors. Do not combine throughput from the CLI with latency from the app into one undifferentiated score.

## Phone qualification still required

1. Record device model/OS, physical accessible and available memory, low-memory threshold, storage, battery/charging state, thermal status, model hash, runtime binary hash, settings, and CPU features.
2. Run each model in the app for three complete short-prompt tests. Keep cold/reload time separate; OS cache is uncontrolled. Compare two and four threads in the CLI sweep, but label the app's two-thread measurements separately.
3. Check cancellation, unload/reload, model switching, rotation/relaunch, and low-memory behavior. A CLI load is not proof that the app survives.
4. Repeat realistic tasks for ten minutes. Preserve failures, empty responses, OS kills, and severe thermal states. A short benchmark is not a sustained-use qualification.
5. With the model installed, verify airplane-mode relaunch, chat, local memory retrieval, and planner actions. The absence of app network permission supports the local-only design; a switch-state snapshot alone does not prove a successful offline workflow.
6. Score useful answers separately from speed: factual correctness, concise instructions, supported local commands, persistence, memory retrieval, and rejection of unsupported actions. No quality score is claimed from the synthetic benchmark.

An optional product target is at least 5 generated tokens/second and short-prompt warm first-token time at most 8 seconds, with no low-memory or severe thermal signal. These are chosen usability targets, not published device benchmarks. The final choice should reflect measured speed, memory stability, and task quality.

## Primary sources

- [Liquid AI LFM3-1.7B model card](https://huggingface.co/LiquidAI/LFM3-1.7B), [official GGUF repository](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF), [pinned chat template](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/ac2586a34f1a1c03329dbdcdefabd8b6478ddaa7/chat_template.jinja).
- [Liquid AI LFM2.5-1.2B-Instruct model card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct), [official GGUF repository](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF).
- [IBM Granite 4.1 3B model card](https://huggingface.co/ibm-granite/granite-4.1-3b), [official GGUF repository](https://huggingface.co/ibm-granite/granite-4.1-3b-GGUF).
- [Pinned llama.cpp architecture registry](https://github.com/ggml-org/llama.cpp/blob/d05706b4f32a545814856e98ca518292585443b9/src/llama-arch.cpp), [LFM3 implementation](https://github.com/ggml-org/llama.cpp/blob/d05706b4f32a545814856e98ca518292585443b9/src/models/lfm3.cpp), [Android build documentation](https://github.com/ggml-org/llama.cpp/blob/d05706b4f32a545814856e98ca518292585443b9/docs/android.md).
- [Android memory fields](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo), [process PSS](https://developer.android.com/reference/android/os/Debug.MemoryInfo), [thermal status](https://developer.android.com/reference/android/os/PowerManager).

Liquid's published LFM3 edge examples use different hardware and GPU offload; LFM2.5's published mobile results include much newer Snapdragon hardware and different NPU/runtime paths. They are not measurements of Snapdragon 695 running this CPU app. They must not be copied into the HONOR result table.
