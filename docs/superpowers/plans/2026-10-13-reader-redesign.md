# Novaxis Reader redesign implementation

## Scope and decisions

Build the approved local reader journey from the Figma handoff. Keep the existing plain TypeScript, CSS, Vite and local voice engines. Books, covers, progress and voice inference stay on the device. No runtime CDN, online TTS, account or telemetry.

The desktop library is backed by detailed Figma context and its local assets. Other layouts use that visual language and the approved screen inventory because Figma's tool quota prevented further detailed exports. This is an adapted functional implementation, not a claim of exact fidelity to all 24 frames.

Keep image-only PDFs viewable; narration needs selectable text. OCR and voice fine tuning are future work. No new dependencies or test suites are needed for this redesign. Production compilation and packaging are delivery steps.

## Work ledger

- [x] Inspect source, handoff, design context and existing offline restrictions.
- [x] Split work: reader shell/assets, book metadata/parsers, controller/integration, final review.
- [x] Implement navigation, real library, resume and permanent player.
- [x] Connect chapters, reading appearance, voice picker, storage and recovery.
- [x] Preserve PDF pages with multi-line passage highlights; bound reflow DOM size.
- [x] Preserve cover images in compatible library backups; report measured pack sizes.
- [x] Compile, review source, repair material issues and package desktop/Android artifacts.
- [ ] Push authorized branch to GitHub and open the updated app.

## Source

Figma: https://www.figma.com/design/1yP2eUx6GfbevVCTuwHHJU/Untitled?node-id=4-45939

Existing handoff: outputs/novaxis-figma-handoff.md in the parent workspace.

## Limits to report

No physical Android or 4 GB Windows performance measurements are available in this workspace. Browser inference and installed Android assets remain local, with one active speech engine. Bundled pack removal is not offered: only separately imported Piper voices can be removed. Library backups transfer books and positions; they do not provide automatic sync or include weights.

## Review resolutions

- Added one-time legacy index mapping for PDF, EPUB and TXT, scoped to records without the new segment count.
- Saved PDF visual page independently from the speech position and included it in backups.
- Serialized chapter seeks with book replacement and captured book ownership before asynchronous work.
- Manual window browsing updates a paused reading position; during narration it switches Follow off without stopping audio.
- Removed hidden compatibility model/voice selectors from keyboard and accessibility focus.
- Local server now serves SVGs and font files with explicit MIME types and returns a real 404 for missing assets.
- The migrated position and new segment count are committed in the same storage transaction.
