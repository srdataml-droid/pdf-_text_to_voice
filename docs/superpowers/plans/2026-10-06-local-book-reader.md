# Local Book Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

## Goal

Build a book reader that the user owns and can run fully offline on a laptop and Android phone. It imports PDF, EPUB, and TXT books; reads them with locally bundled speech models; highlights the current reading segment; and stores books and progress on the device.

## Architecture

- One TypeScript web application provides the reader, library, speech controls, and settings.
- On the laptop, a small local launcher serves the built app at 127.0.0.1 only.
- On Android, Capacitor packages the same built web assets into an installable APK. It does not request Internet access.
- Book parsers, speech runtimes, model weights, voices, and phonemizer assets are bundled locally. Runtime code never downloads models or contacts a service.
- IndexedDB stores imported books and reading progress. A user-created backup file can move library data between devices; model files are installed separately.
- Speech engines implement one replaceable interface, with separate adapters for Piper, Kokoro, and Kitten Nano.

## Tech Stack

- TypeScript, Vite, and plain browser UI.
- Capacitor for the Android package.
- PDF.js for selectable-text PDFs, epub.js for non-DRM EPUB files, and a local TXT parser.
- Local ONNX/browser speech runtimes and bundled model assets.
- IndexedDB for books and progress.

## Spec

Follow ../specs/2026-10-06-local-book-reader-design.md.

## Global Constraints

- No Internet connection is required for use, reading, or speech generation.
- No CDN, hosted model, remote voice catalog, account, analytics, or cloud service.
- The laptop launcher binds to loopback only.
- The Android app must not request Android Internet permission.
- Do not bundle sample audio, virtual environments, caches, or duplicate model files in release packages.
- Store books and progress on the device. A user may export/import a backup file to transfer them.
- Support selectable-text PDFs, non-DRM EPUB, and TXT. OCR and DRM removal are out of scope.
- Expose model selection first, then show only voices available for that model.
- Include Piper Lessac, bundled Kokoro voices, and Kitten Nano's eight voices.
- Highlight the current PDF line or EPUB/TXT paragraph or sentence while its audio plays.

## Review Focus

- Ensure runtime assets resolve only from packaged local files, with no network fallback.
- Ensure Android packaging has no Internet permission and the laptop launcher listens only on 127.0.0.1.
- Ensure PDF extraction keeps page and line ordering and explains scanned-page limitations.
- Ensure EPUB scripts and external resources do not execute or load.
- Ensure model assets are included once and excluded from library backups.

---

## Implementation Tasks

### Task 1: Build shell and local launcher

Create package.json, package-lock.json, vite.config.ts, tsconfig.json, capacitor.config.ts, index.html, src/main.ts, src/app.ts, src/styles.css, scripts/serve-local.mjs, scripts/copy-local-assets.mjs, and README.md.

Use Vite with pinned dependencies and plain TypeScript UI. Implement the local server on 127.0.0.1 only. Copy selected model assets from the task's local outputs/voice-models into public/voice-packs; exclude samples, Python environments, and duplicate caches.

### Task 2: Store and manage books locally

Create src/domain/book.ts, src/storage/database.ts, src/storage/book-store.ts, src/storage/progress-store.ts, src/library/import-book.ts, and src/library/library-view.ts.

Store imported files as Blobs in IndexedDB with metadata. Keep stable per-format reading positions and provide usable quota/import error messages.

### Task 3: Read PDF, EPUB, and TXT

Create src/formats/book-content.ts, src/formats/pdf-content.ts, src/formats/epub-content.ts, src/formats/txt-content.ts, and src/reader/book-view.ts.

Bundle PDF.js and its worker and epub.js locally. Normalize parser output into ordered segments with page/chapter/paragraph locations. Support selectable PDFs, non-DRM EPUB, and UTF-8 text; reject or explain pages without selectable text. Disable EPUB scripts and prevent external resource loading.

### Task 4: Model and voice selection

Create src/tts/catalog.ts, src/settings/model-settings.ts, and src/settings/voice-settings-view.ts.

Expose model choices Piper, Kokoro, and Kitten Nano first, then voices belonging to the selected model. Only show voice choices for packaged assets. Include local Piper voice-pack import when supported. Preserve per-model selections.

### Task 5: Local speech adapters

Create src/tts/speech-engine.ts, src/tts/engine-factory.ts, src/tts/piper-engine.ts, src/tts/kokoro-engine.ts, src/tts/kitten-engine.ts, and src/tts/local-assets.ts.

Use initialize(voice), synthesize(text, speed), and dispose() as the shared engine contract. Pin compatible runtime versions. Disable all analytics. Configure local model, WASM, and phonemizer paths explicitly. Fail clearly when any local asset is absent; never download or fall back to remote URLs.

### Task 6: Playback and synchronized reading

Create src/reader/segment-queue.ts, src/reader/playback-controller.ts, and src/reader/playback-controls.ts; update src/reader/book-view.ts.

Synthesize one segment at a time. Add play, pause, resume, stop, next, previous, and speed controls. Highlight the spoken segment and persist progress at segment boundaries.

### Task 7: Backup and restore

Create src/storage/library-backup.ts and src/settings/backup-view.ts; update src/app.ts.

Export books and reading progress as a versioned local file. Import with validation and duplicate handling. Exclude speech models and runtime caches.

### Task 8: Android package

Create the Capacitor Android project and scripts/build-android.mjs. Ensure the generated Android manifest has no Internet permission. Package the same static app and model assets. Use local file selection for books and voice packs; keep library data app-private and support user-selected export/import.

### Task 9: Documentation

Complete README.md and create docs/supported-formats.md. Explain local laptop launch, Android packaging/install, model and voice selection, storage, backup transfer, supported formats, and scanned-PDF/DRM limits. State that the runtime works offline.

## Completion Checklist

- [x] Laptop app imports and reads PDF, EPUB, and TXT with locally bundled voices.
- [x] Android app uses the same reader and local speech assets.
- [x] Model selection leads to the matching local voice list.
- [x] Playback controls and active-segment highlighting stay synchronized.
- [x] Books and reading progress persist locally and move via user-managed backup.
- [x] No runtime feature depends on Internet, CDN, analytics, or hosted models.

Implementation and verification notes: see ../../verification.md. The Android package was built, but has not been run on a physical phone. Several planned helper files were consolidated into app.ts, book-store.ts, and the Android build script in package.json.
