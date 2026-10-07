# Novaxis Reader

This repository is the local-first book reader for PDF, EPUB, and TXT on desktop browsers and Android. The approved architecture and requirements are in `docs/superpowers/specs/2026-10-06-local-book-reader-design.md` and the implementation plan is in `docs/superpowers/plans/2026-10-06-local-book-reader.md`.

## Product requirements

- Offline during use: do not add a CDN, online speech API, remote model fallback, telemetry, or account requirement.
- Keep the laptop launcher bound to 127.0.0.1 and the Android package free of Internet permission.
- Speech models and voices are local assets under `public/voice-packs`; they are intentionally gitignored.
- Let the user select a model first, then a voice belonging to that model.
- Store books and progress locally. Library backups must not include model weights.
- Support selectable-text PDFs, non-DRM EPUBs, and UTF-8 TXT. Do not imply OCR or DRM support.

## Development

- `npm run build` validates TypeScript and creates the Vite production build; model assets must already be prepared.
- `npm run models:prepare` prepares local model and runtime files. Network use is limited to initial setup when supported model assets are missing.
- `npm start` starts a detached loopback reader that survives launcher exit.
- `npm run serve` runs the loopback reader in the current terminal.
- `npm run android:apk` packages the app and its prepared local assets as a debug APK.

Avoid adding online dependencies to the app's runtime. Keep local data, model licenses, and backup behavior documented in the README.
