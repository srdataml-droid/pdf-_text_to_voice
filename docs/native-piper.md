# Android native Piper — 1.4

Bundled Lessac narration runs in `ReaderPlaybackService`, independently of the Activity/WebView. One native engine configured with two CPU worker threads feeds a three-clip PCM16 queue; one streaming AudioTrack consumes it. Passage markers follow the audio playback head rather than generation completion. Pause keeps the in-session track position. A CPU wake lock runs only during preparation/playback, and a MediaSession exposes Play/Pause/Stop plus transport callbacks. Calls and headphone removal pause playback. Saved positions remain passage based.

The service reads a temporary private JSON plan and deletes it after loading. Capacitor bridge argument logging is disabled so the narration plan is not printed in debug logs. Native progress is saved separately in app preferences; opening the book restores whichever saved position is newer. Stop cancels synthesis/output and releases audio, focus and wake resources. Engine destruction is serialized after pending native work. Native completion is book-wide; page/chapter boundaries are not stop points.

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

Production compilation and APK assembly are build evidence, not evidence of sustained phone playback. The user-reported chapter 27 stop has no confirmed crash log or reproducible passage yet. Screen-off continuity, notification controls, interruptions, long-book progress and that chapter still require the user’s device check. Force-stop, reboot, process death or restrictive manufacturer battery settings can interrupt playback. No automatic recovery after those events is promised.
