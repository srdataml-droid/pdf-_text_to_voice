# Verification — 7 October 2026

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
