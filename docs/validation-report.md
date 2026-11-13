# Anchor 0.2 validation — 29 October 2026

The Android APK is built and installed on the HONOR Magic4 Lite 5G (reported model ANY-NX1), Android 13 / API 33 / arm64. It includes the local calendar, daily tasks, fixed/flexible scheduling, local notifications and optional ringing/snooze, durable change reports/Undo, explicit local tools, private memory, GGUF chat and offline Android voice integration.

## Verified app behavior

- 42 JVM tests passed; 37 distinct real-device regression tests passed across the baseline and follow-up runs. Device tests include SQLite v1→v2 migration without losing entries, restart persistence, task completion/history, stale reminder rejection, clock-adjusted persistent snooze, fixed/active task protection, allocations/overload, report deduplication, atomic Undo and observer serialization.
- One end-to-end hardware alarm was scheduled through AlarmManager, delivered to the foreground ringing service, then snoozed and dismissed. Its temporary task was deleted and the original ringing preference restored. The test checks native service/state and notification access; it does not independently measure loudspeaker sound pressure.
- Five new handset schema tests cover valid unsaved drafts, non-action conversation, invalid types/fields/dates, fenced output/protected events, and trailing-output rejection. A model draft opens the existing editable task form and writes nothing until Save. The real LFM3 request produced the correct tomorrow date (2026-10-30),09:00 time,30-minute duration and flexible flag; the test draft was cancelled without saving.
- APK, test APK and lint build pass. Lint has warnings (synchronous persistence deliberately used for alarm state, API26 font fallbacks, English literals, dependency updates, ARM64-only ABI and framework deprecations), not a clean-warning claim.
- Final merged APK has no INTERNET, calendar-provider, contacts, location or storage permission. WorkManager adds ACCESS_NETWORK_STATE; this grants network-state observation, not Internet sockets. Android backup and device transfer are disabled.
- Independent review found an Undo/automatic-check race and voice continuing after leaving the activity. Both were fixed. Undo/settings changes now use the observation gate, with a handset concurrency regression. Activity stop cancels voice and invalidates queued transcripts independently of model import. Live background-microphone behavior was not recorded.

## Model comparison on this phone

Official SHA-256 and size checked on host and after staging. Same packaged JNI runtime (llama.cpp d05706b4f32a545814856e98ca518292585443b9), CPU, 2 threads, 1024-token context, 3 runs capped at 128 output tokens, then one planning question. LFM3 uses vendor-compatible plain-text nonthinking formatting with its explicit BOS; 8 host parity cases and 4 invalid-history rejections passed. Both Liquid runs confirmed the airplane-mode flag before and after. Wi-Fi was still enabled at the final check, so this was not a fully isolated-device network test. The app had no INTERNET permission and generation ran through its packaged CPU JNI runtime; this establishes app-local inference. OS file cache was uncontrolled; these are short, warm-cache-influenced measurements, not cold-boot or sustained battery tests.

| Model | Load | Median first token, excludes load | Median tokens/s | Sampled peak process PSS |
|---|---:|---:|---:|---:|
| LFM2.5 1.2B Q4_K_M | 5.97s | 1.06s | 12.49 | 943 MiB |
| LFM3 1.7B Q4_K_M | 8.34s | 2.19s | 8.36 | 1255 MiB |
| Granite 4.1 3B Q4_K_M | Incomplete | Unavailable | Unavailable | Unavailable |

Android terminated the Granite run for LOW_MEMORY (ApplicationExitInfo reason 3). The instrumentation process died before it could write complete measurements; no speed or peak-memory number is inferred. Earlier evidence is not counted as a successful run. A conservative guard now rejects large files when currently available memory is insufficient; it is not a guarantee against future memory pressure.

Android reports 5.36 GiB system-accessible physical RAM. RAM Turbo is storage expansion. Minimum available RAM sampled was about 608 MiB for LFM2.5 and 521 MiB for LFM3; neither completed Liquid run reported Android's low-memory signal, and maximum OS thermal status was 0. Process PSS includes test/runtime overhead and differs from a full UI/voice workload.

LFM3 is the provisional practical default and was imported through the Android file picker with its official checksum verified. In the single planning question, it proposed09:30–10:00 for a30-minute flexible reading task before a protected10:00–11:00 meeting. LFM2.5 proposed09:30–09:45, violating the duration. This demonstrates one constraint-following difference; it does not prove broad intelligence parity or a general accuracy ranking. All scheduling writes are governed by tested deterministic tools, not model prose.

Raw JSON, model hashes, original responses and failure evidence are in model-validation/app-runs/. Normal Chat also generated “Ready offline.” with the verified LFM3 file imported through the Android picker. The source includes CandidateBenchmarkDeviceTest; run-app-bench.py reproduces the packaged JNI comparison, while run-android-bench.py is a separate synthetic kernel benchmark. No synthetic throughput is presented as app latency.

## Voice and background limits

The phone reports on-device recognition available and 18 installed offline English TTS voices. The capability check did not open a microphone or play TTS. Actual spoken transcription accuracy, audible read-aloud and accents remain untested. The app uses createOnDeviceSpeechRecognizer only and rejects network-only TTS voices; there is no cloud fallback. Sherpa-onnx is not bundled.

Checks run on planner events/app open/clock or restart broadcasts and through WorkManager at the minimum 15-minute interval when Android allows. They do not run an always-on LLM. Actual reboot recovery, prolonged Doze, overnight use, denied permissions on every Android version, OEM battery-management changes, long model sessions, battery drain and heat over time remain unverified. Force stop prevents delivery until the app is reopened.

## Stack and data decisions

The recovered working project was Java/framework views/SQLiteOpenHelper. This implementation preserves it instead of rewriting in Kotlin/Compose/Room/DataStore during reliability work. Those preferred technologies remain possible future migrations. Existing entries default to fixed/protected after migration. Flexible percentage caps flexible minutes within the same-day window; it does not manufacture fixed appointments. Missed challenge days do not reset its start. Undo refuses to overwrite newer calendar changes and pauses automatic planning after success.

The final app is left on Today with LFM3 imported but unloaded to release RAM. Airplane mode remains in the state selected by the user; it can be turned off after testing.

No account, Google Calendar, server, analytics, continuous microphone or LLM background loop. Storage uses Android sandbox/device encryption without a separate database password. Memories are explicit, editable/deletable, and their inclusion in chat can be switched off. Development signing is used; the APK is a personal-test release, not a Play publication.

Dependency risk review: WorkManager 2.11.2 compatibility was checked against official Android release notes; live Endor vulnerability/policy evidence was unavailable. Risk posture remains UNKNOWN, not verified clean. See the source docs/dependency-review.md for the exact gap and follow-up.

Primary references: [Android alarms](https://developer.android.com/develop/background-work/services/alarms), [periodic work](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest), [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work), [official LFM3](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF), [official LFM2.5](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF), [official Granite](https://huggingface.co/ibm-granite/granite-4.1-3b-GGUF).
