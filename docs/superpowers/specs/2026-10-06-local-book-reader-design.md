# Local-first book reader design

## Purpose

Build a fully local book reader for the laptop and Android phone. It supports PDF, EPUB, and plain-text books; reads aloud with a user-selected neural voice; and highlights the passage being spoken. At runtime, it must not need an Internet connection, cloud account, remote speech service, or remote model catalog.

## User flow

1. Start the reader locally on the laptop, or install the Android package copied from the laptop.
2. Import a PDF, EPUB, or TXT book from the device.
3. Choose a speech model: Piper, Kokoro, or Kitten Nano.
4. Choose one of that model's voices. The voice list changes with the model.
5. Choose from model and voice files already stored on that device. Show which voices are present. Transfer app updates or additional model packs by local file transfer.
6. Read visually or play, pause, resume, stop, move through the book, and adjust speech speed. Save the reading position locally.

The selector always shows the three models first, then the selected model's available local voices. Piper voices are separate model files; include the Lessac voice already downloaded and allow additional Piper voice packs to be imported from local files. Kokoro exposes its bundled voice choices. Kitten Nano exposes its eight voice choices. Do not fetch a catalog or download model files from the Internet at runtime.

## Recommended architecture

- A TypeScript and Vite front end, built once as local web assets.
- Bundle the JavaScript libraries, PDF/EPUB parsing tools, phonemizers, WebAssembly files, models, and voice data with the app or local voice packs. Never load code, runtime files, fonts, models, or catalogs from a CDN or model hub.
- On the laptop, a launcher serves those assets on loopback only (127.0.0.1); no network-facing server is required.
- On Android, package the same web assets in a Capacitor Android app. The app bundle contains the available model weights and voice data and requests no Internet permission.
- Store imported books, selected model and voice, and reading position in device-local storage. Provide an export/import library backup so the user can move books and progress between devices without cloud sync. Handle quota and missing-file errors.
- Model-specific speech adapters isolate Piper, Kokoro, and Kitten runtime differences behind one interface. Each adapter synthesizes text in short ordered segments and returns audio for local playback.
- Disable model-generation telemetry and all analytics. The reader makes no outbound network requests; books, voice generation, models, and saved progress stay on the device.
- Use PDF.js to extract selectable PDF text and EPUB.js to render EPUB content. TXT books use a lightweight local parser.

## Read-along behavior

- For PDFs, extract and group positioned text into readable lines; highlight the line currently being spoken.
- For EPUB and TXT, highlight the current sentence or paragraph because line wrapping changes with screen size.
- Persist progress at the nearest stable location: PDF page and line, EPUB chapter and text location, or TXT character offset.
- Initial scope is segment highlighting, not word-by-word karaoke timing.

## Supported and excluded content

- Included: selectable-text PDFs, DRM-free EPUBs, and TXT files.
- Excluded from the first version: scanned PDFs that need OCR, DRM-protected books, automatic cloud sync, user accounts, and non-book office formats.
- Each device keeps its own library and voices unless the user exports and transfers a backup. There is no automatic syncing.
- The desktop browser opens the local web app through the loopback launcher. The phone runs the packaged Android app. Transfer the package and any voice packs with USB or another local file-transfer method; no hosted site is required.

## Alternatives considered

1. **Recommended: shared local web app plus offline Android wrapper.** One front end handles both devices; the laptop uses a loopback launcher and the phone gets a local Android package. Both can run in airplane mode. Capacitor is designed to package a modern web app for Android.
2. **PWA hosted over HTTPS.** Less Android packaging work, but its first install normally depends on a hosted origin, so it does not match the no-Internet requirement as directly.
3. **Separate native Flutter applications.** Strong device integration, but duplicates more of the client work and moves away from the web-first reader.

## Acceptance criteria

- Users can import, open, remove, and reopen PDF, EPUB, and TXT books without uploading their contents.
- The model selector offers Piper, Kokoro, and Kitten Nano; changing models updates the voice list.
- A voice already included in or imported into the app can be selected and remains available offline; changing voices does not replace the book library.
- Speech is generated locally, and the current PDF line or EPUB/TXT sentence or paragraph is visibly highlighted.
- Playback controls and saved reading position work after closing and reopening the installed app.
- The Android package and laptop build both open and read in airplane mode, with no outbound network request.
- Model, voice, book, and progress data remain local; export/import works through a user-chosen local file.
- Kitten analytics and all other speech telemetry are disabled.

## Risks and mitigations

- **Browser runtimes may differ.** Keep model implementations behind adapters and make Android WebView compatibility an early validation milestone.
- **APK and model packs take storage.** Show bundled voice sizes and keep the three model/voice adapters replaceable.
- **PDF extraction may not match reading order.** Group text by page coordinates and report when a document has no selectable text; OCR is out of scope.
- **Speech generation may take longer than playback begins.** Generate one or a few upcoming segments in a queue, play them in order, and keep the active segment highlight synchronized.
- **Third-party browser runtimes have different maturity.** Pin versions, package all runtime assets locally, keep adapters replaceable, and make the no-network runtime requirement an acceptance check.

## References

- Piper browser runtime: https://github.com/Poket-Jony/piper-tts-web
- Kokoro browser inference: https://www.npmjs.com/package/kokoro-js
- Kitten browser SDK: https://github.com/KittenML/KittenTTS-web
- Capacitor Android packaging: https://capacitorjs.com/docs
- PDF.js: https://mozilla.github.io/pdf.js/getting_started/
- epub.js: https://github.com/futurepress/epub.js
- PWA offline behavior: https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Offline_and_background_operation
- IndexedDB: https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API
