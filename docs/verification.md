## Reader space and named voices — 26 October 2026 (v1.5)

User confirmed the 1.4.2 reader works “even at 1123”; the number was not identified as a page or chapter, and this was not a timed endurance check. Requested more document space, compact reader controls, quarter-step speed buttons, and voice names with short introductions.

Implemented a slim top reader bar, overflow reading menu, hidden app navigation while reading, full-width PDF space, optional spoken-text caption and a single bottom player. Speed buttons use 0.25 steps over 0.75–2.0 with bounded buttons and matching saved-setting normalization. A five-voice shortlist offers Lessac (Piper), Heart/George (Kokoro), Luna/Jasper (Kitten), while technical details and all voices remain in an expandable section. Tapping a featured voice queues a local “Hi, I'm [name].” preview; superseded preview requests are ignored and engines are released serially. Native notification text uses Lessac's name.

TypeScript/Vite production compilation succeeded. The actual voices screen was visually reviewed in the local browser. A static phone-size layout preview rendered the real reader shell/CSS with synthetic book-page content at 390×844; it is a layout illustration, not a screenshot of the Android app or a PDF functional test. No agreement was accepted by the agent. No new automated tests were added or run for this design pass. The final Capacitor sync and app-scoped Android debug build succeeded. Version 1.5 (code 10) installed successfully as an in-place update on the connected phone. Artifact inspection confirmed the new named-voice UI and exclusion of the temporary layout-preview page; no Internet permission was added. New voice previews, speed taps and real-phone layout still need the user’s review. Native background playback remains limited to bundled Lessac; other voice choices explicitly say to keep the reader open.

## Confirmed native Piper crash repair — 26 October 2026 (v1.4.2)

The user reported being kicked out of v1.4.1. Connected-device crash logs confirmed two fatal SIGABRT events in `OfflineTts.generateWithCallbackImpl`: `JNI DETECTED ERROR IN APPLICATION` with pending `NoSuchMethodError` for callback `invoke([F)Ljava/lang/Integer;`. This identified a callback ABI mismatch in the new native integration. The generic Java lambda did not expose the specialized float-array/Integer method that this sherpa-onnx JNI version looks up. The prior 22 synthetic checks did not exercise that native boundary.

A small JVM reproduction using the old lambda failed the same specialized method lookup. Replaced it with concrete `PiperSynthesisCallback`, declaring `public Integer invoke(float[])` and preserving it with a ProGuard keep rule. Added reflection/cancellation regression coverage and a real Android instrumentation smoke test. Kotlin dependency versions for the app and app tests are aligned with the existing 2.1.20 runtime.

All 23 local reliability checks passed. App-scoped debug and Android-test builds succeeded. Version 1.4.2 (code 9) was installed as an update. On the connected physical phone, `PiperNativeSmokeTest.typedCallbackGeneratesRealSpeechWithoutAborting` passed in 5.233 seconds: the actual bundled native Piper runtime generated three fixed synthetic passages, returned nonempty finite 22,050 Hz audio and invoked the callback at least three times. The smoke test does not play audio or open user documents. Temporary test assets are cleaned by the test. No book contents were read for crash diagnosis or testing.

This confirms repair of the observed callback crash and successful native speech generation on this phone. Sustained locked-screen reading, playback controls, audio interruptions and chapter 27 remain pending the user's listening check. The new smoke test does not establish those broader playback properties.

## Long-book reliability pass — 26 October 2026 (v1.4.1)

Confirmed source-level risks: v1.4 transferred all book text in one bridge message and reconstructed it as one native JSON object; EPUB sections retained their loaded documents until book destruction; native generation errors immediately cleared already queued speech; and an unresponsive player had no progress timeout. The existing web playback checks still simulated the old HTMLAudioElement player and failed with `AudioContext is not defined`, so they were updated to exercise the current Web Audio scheduling path with a controlled clock.

Replaced the native JSON plan with ordered batches (maximum 128 passages), a private binary text file and a fixed-width disk index. Android reads one passage at a time and validates complete transfers. EPUB extraction now unloads each chapter document after extracting its text. Native generation gets one engine-reset retry for a normal exception, cancellation remains cancellable, prepared audio drains on persistent generation failure, and a 120-second no-progress watchdog saves a visible error. Private plans are removed after use; interrupted plans older than a day are cleaned on later launches. Native plan identifiers prevent stale state from taking over a newer request. Bridge argument logging remains disabled and Internet permission remains absent.

TypeScript/Vite compilation, Capacitor sync and Android debug APK assembly succeeded. Packaged assets contain the new bridge and policy version 2026-10-26.2; bridge logging is disabled and the merged manifest omits Internet permission. Version 1.4.1 (code 8) was installed successfully as an in-place update over 1.2.1 on the connected phone, and a copy was transferred to Downloads/Novaxis-Reader-1.4.1.apk. The app had not yet been opened for a listening check at this point.

All 22 targeted automated checks passed: 8 plain-Java storage/retry/watchdog checks, 4 bridge/transfer/cancellation checks, 1 EPUB retention check, and 9 simulated Web Audio playback checks. The synthetic disk plan contains 201,000 passages across 1,000 chapters, exceeds 32 MiB of text, and is written/read with a 32 MB Java heap. This is a native-plan memory check, not a measurement of the full Android app or real speech. The EPUB retention check failed with 1,000 simultaneously retained section documents before the fix and passed with a maximum of one afterward. A regression mutation confirmed a superseded completion incorrectly paused a newer request; the corrected bridge passed. No user book contents were read for these checks.

Remaining limits: the WebView still parses/retains the complete extracted passage list during book opening; Android narration safety ceilings are one million passages and 256 MiB of encoded text plus its disk index; sufficient device space is required. A watchdog cannot forcibly kill a native JNI call that never yields. OS process death, force-stop, restart and restrictive battery settings can still interrupt reading. Sustained screen-off playback, interruptions and the user-reported chapter 27 passage require a physical-device listening check. Background speech still covers bundled Piper Lessac only.

## Piper background repair — 26 October 2026 (v1.4)

User reported Piper cutting off, slowing with the screen off, and stopping after chapter 27. Read-only device diagnostics confirmed the installed version was 1.2.1. Available crash logs did not identify the chapter failure. Source inspection found no native background player: all generation/playback depended on the embedded web runtime. This is an architectural gap, not proof of the exact cause of every reported stop.

Implemented a native Android Piper service, bounded PCM look-ahead, audio focus and headphone-disconnect handling, media controls, CPU wake lock, native passage persistence and private temporary plan loading. Included pending-start cancellation, saved progress restoration, and cleanup of web Piper inputs even when inference fails. Background support is limited to the bundled Piper Lessac voice; other voices keep their foreground path. Existing document parsing, book database and package identity are retained. Policy version is updated, with a fresh agreement choice required for narration.

TypeScript/Vite production build, Capacitor sync and Android debug assembly succeeded. Source review covered cancellation, audio-head passage timing, end-of-book drain and bounded queue allocation. The APK packages ARM64/ARMv7 native libraries and Piper assets; the merged manifest includes foreground-media/wake permissions and omits Internet permission. No automated functional tests were added or run. These build observations do not establish sustained screen-off playback or resolve the chapter-specific report.

The phone disconnected before installation. Pending device checks: accept the updated notice if desired, select bundled Piper Lessac, listen with display locked, use notification Pause/Resume, and revisit chapter 27. The user should open their own book; book files and contents are not collected for diagnostics. The new native path has not yet been exercised on the physical phone.

# Verification — 7 October 2026

## Privacy and agreement update — 24 October 2026

Version 1.3 (code 6) bundles an offline privacy notice, personal-use agreement and permissions guide naming Irenikase Samuel Temitope as publisher. No email address was supplied or invented. Consent requires two unchecked acknowledgements followed by an explicit button action. A versioned local record includes the device timestamp; invalid, absent, withdrawn or outdated records do not count as acceptance. Storage failures are labelled as session-only choices. Withdrawal pauses narration and guards subsequent imports, restores and playback, while manual library access, deletion and backup export remain available. The record is a local preference, with no remote identity or signature verification.

Contextual file explanations are cancellable and preserve synchronous user-gesture handoff to hidden file inputs. Remembered explanations do not select files or grant system permissions. Restore uses a real button rather than a label default action, so it follows the same explanation path. Cancel, Escape and declined agreement clear pending actions. Drag-and-drop and central import/restore/playback entry points are guarded as well. Same-origin browser tabs respond to agreement storage changes. The policy describes local processing, private storage, original-file preservation, unencrypted backup copies, optional cloud file-provider behaviour, data removal, voice licences and the absence of telemetry and online services.

Android's official document-access and runtime-permission guidance was consulted. No new dangerous or Internet permission is needed or added. Source review covered acknowledgement state, withdrawal, file chooser activation, backup access after declining, policy-version handling and unchanged book storage. No automated tests or user acceptance interactions were performed. Build/package results are recorded by the delivery session; Android UI and first-use flows still need a person to review. The agent does not accept the agreement on the user's behalf.

## PDF presentation update — 24 October 2026

Version 1.2.1 (code 5) refines the pages-while-reading view. Original PDF pages retain their document fonts, images and layout through the existing PDF.js renderer. Rendering now uses the device pixel ratio up to 3× with a six-million-pixel budget and 4096-pixel maximum edge, replacing the unconditional 1.5× cap. Fit-page, fit-width, true 75–300% scale (96 CSS pixels per 72 PDF points), rotation, bounded viewer scrolling and a neutral paper surround are included. Follow scrolling is confined to the viewer rather than moving the whole app. Narration text is collapsed until Show text is selected; Focus removes navigation without stopping audio.

Source review covered rotated highlight coordinates, pixel allocation, fit sizing above the audio player, previous/next limits and preserving relative scroll position on zoom. The production build and APK packaging are recorded by the delivery session. No automated tests or phone visual checks were run. The user identified reading pages as the problem; no screenshot was supplied. Figma inspection was unavailable due to a connector transport error. This is a source-led presentation pass, not a claim of matching Chrome's rendering engine, selection, OCR or complete PDF feature set.

## Continuous reading update — 24 October 2026

Version 1.2 (code 4) replaces per-passage HTML media reloads with a bounded Web Audio queue. Three short decoded passages are retained at most, with one serialized speech engine. Audio start times use the audio clock across page/chapter boundaries. Pause suspends that clock; stop and seek invalidate pending generation and disconnect scheduled sources. Voice changes release the old engine. Speed changes affect newly generated passages. Text normalization is applied only to speech input; displayed text, segment indices and saved progress stay unchanged.

The update applies Piper Lessac once to existing settings, then preserves subsequent choices. Read-along graphics reuse the existing paper, amber, sage, Inter and Lora design. A native screen-awake flag is active only while foreground narration is loading or playing; this adds no permission. A contents selection continues narration when already playing.

Production TypeScript/Vite compilation completed successfully. Source review covered queue ownership, cancellation, bounded decoded audio, paused-clock resume, settings migration and chapter navigation. No automated tests or device listening checks were run for this update. Actual voice throughput, audible joins, visual rendering and Android pause/resume remain to be confirmed on the phone. Background/locked-screen playback is not implemented, and a model that generates slower than real time can still cause waits.

Android debug packaging also completed successfully. The delivered APK's entry script matches the final web build, all 43 non-hidden voice/runtime files match the build assets, and the packaged permission list has no Internet permission. The update keeps the existing application ID and database. Installation was pending a reconnected, authorized device at the time this delivery note was written.

## Picker repair — 13 October 2026

The user reported Add books could not select a file. Browser diagnostics confirmed that the file input's closest delegated action target was BODY outside #app, because BODY has data-view and data-theme layout flags. The delegated click listener cancelled that input's default action; no filechooser event opened even though the input was enabled and supported multiple files. The listener now ignores targets outside #app, addressing the same cancellation path for backup/voice inputs and unmarked controls. Android version 1.1.1 includes this repair.

Delivery follow-up — 23 October 2026: the production web build and Android debug packaging completed in the earlier repair session. APK metadata records version 1.1.1 (code 3). The local launcher was restarted after it was found stopped. Post-change picker operation remains unconfirmed: the earlier browser connection timed out, and today's attempt to inspect the tab was blocked by browser security policy. No physical Android check or new automated tests were run. Refresh the app and confirm Add books opens the device file picker before treating this repair as functionally verified.

## Phone library update — 13 October 2026

This update adds sequential multi-book import, one-file import through the same picker, per-file outcomes, stop/retry, filtered selection, atomic bulk book/progress removal, and native Android storage information. The existing Capacitor chooser was inspected and already supports multiple ClipData files. No new permissions, packages or automated tests were added.

The desktop production build compiled successfully. Android version 1.1 uses the same package ID and database as the previous build. Private-data estimates exclude caches and APK assets, use a bounded background scan, and explicitly label incomplete scans. Import/delete and phone storage behavior still need a physical-device trial; compilation and source review do not establish those outcomes.

Read-only review found a delayed playback action could outlive deletion while waiting for a voice preview. Playback actions now capture and recheck book ownership after that wait; unloading a book also clears the retained playback queue and reading DOM. No Android device is connected to this workspace for installation.

The Android 1.1 debug APK compiled successfully with the updated native storage bridge and prepared local voices. Its merged manifest has no Internet permission. The updated browser library was opened for a visual inspection of the new controls; no book files were imported or deleted as part of automated testing.

## Redesign delivery — 13 October 2026

TypeScript and Vite production compilation succeeded for the Figma-inspired redesign. A read-only source review identified and prompted fixes for EPUB/TXT position migration, PDF visual-page persistence, asynchronous chapter seeking, manual passage browsing, and hidden keyboard-focusable selectors. The local library was opened for visual review; local SVG serving was corrected by adding image/font MIME types to the loopback server. No new test suite or automated functional checks were run for this redesign. Earlier results below describe earlier versions, not a complete exercise of the new interface.

Desktop library tokens/assets come from detailed Figma context. Reader, voice, storage and recovery layouts adapt the same design system; further detailed exports were blocked by Figma's tool quota. All fonts/icons are packaged locally. Narration retains one engine and a two-clip look-ahead buffer; EPUB/TXT render at most 80 passages in the current window.

The redesigned portable ZIP and Android debug APK were generated successfully. The Android source manifest continues to remove Internet permission. Local speech-pack sizes are generated from the actual packaged files at build time.

Physical Android behavior, 4 GB Windows performance, background mobile audio, and the new import/backup/recovery journey still require device trials. No voice model was fine tuned. Highlighting follows a spoken passage, not word-level timing.

## Completed

- Production build: TypeScript and Vite succeeded with the prepared local models.
- Seven automated reading/playback checks passed: bounded long paragraphs, cancellation during voice preparation, rapid passage changes, book queue replacement, changing models during inference, resume without regeneration, and retry after failed initialization.
- Local browser audition: Piper Lessac, Kokoro Af Heart, and Kitten Bella all generated and played speech.
- TXT: imported and read three passages through to the end with progress and highlighting.
- PDF: imported a document with a blank cover and selectable text on page 2; text from page 2 appeared in reading order.
- EPUB: imported ordered chapters, including container-only text and a nested quotation; neither text duplication nor script execution occurred.
- Library export: saved a ZIP with books and progress, without speech models. Restoring it reported three books restored without duplicating their IDs.
- Piper pack import: selected matching ONNX and JSON files, stored the voice locally, and played it. Removing the temporary imported pack returned to the bundled voice.
- Android debug APK: built successfully with all three ONNX models included. ZIP integrity passed, and the merged manifest has no Internet permission.
- Portable laptop archive: generated with the app, models, and launchers. ZIP integrity passed.

## Limits of these checks

The Android APK has not been installed or exercised on a physical phone. Android document selection, native backup saving, sustained mobile speech, and device storage behavior still need a phone trial. A 4 GB Windows computer has not been measured. Browser auditions establish that the local voice adapters work on the current laptop; they are not a promise of performance on every device.

Highlighting follows the current reading passage, not individual words. Scanned PDFs require a text layer; OCR and DRM removal are not included. The Android package is a debug build for personal testing, not a signed Play Store release.

The repository contains source, dependency pins, and model preparation instructions. Large generated model packs, user books, backups, and build artifacts are excluded from Git.

## Local launcher repair

A user encountered failed PDF-worker and voice-engine imports after the temporary preview process stopped. The requested files existed in the build; nothing was listening on port 4173. A detached launcher now starts the local process, waits until it is ready, and reuses it on subsequent starts. A regression check exercises actual launcher exit, successful HTTP loading afterwards, and repeated-start reuse. The portable package uses this launcher. It must be started again after a computer restart.

## Reader changes — 11 October 2026

Eleven automated reading/playback checks pass, including local preparation of upcoming audio while the current clip plays, reuse of prepared audio, speed-change invalidation, and cancellation during preparation. The detached launcher check also passes after rebuilding the interrupted production build.

PDF pages now render from the original document into a single-page canvas, with local fonts, character maps and decoding assets. Per-line reading overlays use PDF coordinates, and page selection and zoom are available. Only the displayed page is rendered; text extraction for speech remains separate. EPUB and TXT retain the passage view.

The prepared laptop archive passes ZIP integrity verification and includes the PDF assets. Initial speech still needs to load the selected voice; look-ahead reduces recurring waits but does not guarantee uninterrupted playback on every device.

The updated Android debug APK builds successfully and passes ZIP integrity verification, includes three ONNX models and the local PDF rendering assets, and still has no Internet permission. It has not been exercised on a physical phone. The final live browser check on 11 October remains pending because the existing tab is on a connection-error page that browser control cannot access; the user was asked to refresh it.
