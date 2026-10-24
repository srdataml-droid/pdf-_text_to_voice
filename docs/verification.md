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
