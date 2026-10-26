# Android native Piper — 1.4.2

Bundled Lessac narration runs in `ReaderPlaybackService`, independently of the Activity/WebView. One native engine configured with two CPU worker threads feeds a three-clip PCM16 queue; one streaming AudioTrack consumes it. Passage markers follow the audio playback head rather than generation completion. Pause keeps the in-session track position. A CPU wake lock runs only during preparation/playback, and a MediaSession exposes Play/Pause/Stop plus transport callbacks. Calls and headphone removal pause playback. Saved positions remain passage based.

The bridge sends at most 128 passages per call. The service reads a temporary private binary text plan with an on-disk offset index, one passage at a time. The plan remains in private app files during playback/pause and is scheduled for deletion after stop, completion, failure or service destruction. Cleanup runs after pending native work releases its reader. Interrupted plans older than 24 hours are cleaned on a later app launch; active plans are excluded. The files are outside cache to avoid cache eviction during reading. Capacitor bridge argument logging is disabled so the narration plan is not printed in debug logs. Native progress is saved separately in app preferences; opening the book restores whichever saved position is newer. Stop cancels synthesis/output and releases audio, focus and wake resources. Engine destruction is serialized after pending native work. Native completion is book-wide; page/chapter boundaries are not stop points.

## JNI callback compatibility

sherpa-onnx 1.13.8 Android JNI resolves callback method `invoke([F)Ljava/lang/Integer;`. A Java lambda implementing generic Kotlin `Function1` exposes only the erased `invoke(Object): Object` method. In 1.4.1 that mismatch caused `NoSuchMethodError`, followed by a fatal CheckJNI abort; normal exception retries cannot intercept that process abort. `PiperSynthesisCallback` now declares `Integer invoke(float[])` explicitly, and the ProGuard keep rule preserves the class/method for future shrinking. A JVM reflection regression and a real-device instrumentation smoke test cover this boundary.

The phone-side smoke test uses temporary bundled voice assets and three fixed synthetic passages, verifies nonempty finite speech at 22,050 Hz and actual callback execution, then releases the native engine and deletes its temporary assets. It does not open the library, read user books or play sound. Screen-off AudioTrack/service continuity remains a separate listening check.

## Recovery and bounds

`NarrationPlan` validates counts, frame sizes, offsets and complete transfer before accepting a plan. It supports up to one million passages, 256 MiB of encoded text frames and an additional 8-byte-per-passage disk index. Bridge transfers and native text access stay bounded. PCM look-ahead remains bounded to three queued clips plus the in-flight generator/writer and AudioTrack buffer. Speech results longer than 90 seconds or with unsupported sample rates are rejected.

`SpeechRecovery` makes at most two attempts for a normal generation exception, releasing/recreating the engine before the second. Cancellation, linkage and VM errors are not blindly retried. Prepared speech drains before a persistent generation error is surfaced. The user can retry from saved passage progress; unread content is not silently skipped. The monotonic watchdog reports an error after 120 seconds without generated audio or forward audio-head progress. Pause resets that budget. Session cancellation releases audio focus/wake resources; a hung native call that does not return/call back cannot be forcibly restarted safely inside this process.

`src/formats/epub-content.ts` unloads each chapter DOM after extraction. The WebView still retains the full extracted passage list and the source archive during opening. This is not complete lazy document parsing or a guarantee that every 1,000-chapter book fits a phone.

## Rebuild and dependencies

`npm run android:prepare` invokes the standard-library Python script `scripts/prepare-native-piper.py`. The script pins SHA-256 checksums for the official sherpa-onnx 1.13.8 Android AAR and its Lessac medium pack. The ignored cache lives under `../build-tools/native-tts`. Copy this cache, the prepared web packs and ordinary Gradle/npm dependencies to rebuild without downloading. The installed app never downloads these assets.

- Runtime/source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- Android binary: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
- Voice conversion/model distribution: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- Original voice/model card: https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/lessac/medium
- eSpeak NG source: https://github.com/espeak-ng/espeak-ng

Sherpa-onnx has an Apache-2.0 licence. The native eSpeak NG component has its own GPL licence; the Lessac voice has a separate linked dataset licence. Runtime and model permissions are separate. Licence texts are bundled under `public/notices`, with the unmodified voice model card. This personal debug preview is not a cleared commercial or Play Store distribution. Retain these sources and notices for the native dependency, and complete a dependency/source-distribution review before a public release.

## Scope and remaining device checks

Native playback covers bundled Piper Lessac only. Imported Piper packs, Kokoro and Kitten retain foreground Web Audio playback. The native model is also copied into private files at first use (roughly another 65 MB); this preparation can take time once. No Internet permission is added.

Synthetic reliability checks cover the disk plan under a 32 MB JVM heap, 1,000 chapter boundaries, one retry, permanent failure, cancellation, timeout accounting and chapter-document retention. Audio timing checks use a simulated clock. Production compilation and APK assembly are build evidence, not evidence of sustained phone playback. The user-reported chapter 27 stop has no confirmed crash log or reproducible passage yet. Screen-off continuity, notification controls, interruptions, long-book progress and that chapter still require the user’s device check. Force-stop, reboot, process death or restrictive manufacturer battery settings can interrupt playback. No automatic recovery after those events is promised.
