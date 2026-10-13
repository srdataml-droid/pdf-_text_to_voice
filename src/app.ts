import type { BookFormat, BookRecord, ParsedBook, ReadingProgress } from './domain/book';
import { parseEpub } from './formats/epub-content';
import { parsePdf } from './formats/pdf-content';
import { parseTxt } from './formats/txt-content';
import { deleteBook, getBook, getProgress, listBooks, listReadingProgress, saveBook, saveProgress, updateBookMetadata } from './storage/book-store';
import { modelById, modelCatalog, type ModelId } from './tts/catalog';
import { createSpeechEngine } from './tts/engine-factory';
import { PlaybackController } from './reader/playback-controller';
import { PdfPageView } from './reader/pdf-page-view';
import { bindPlaybackControls } from './reader/playback-controls';
import { listPiperPacks, importPiperPack, deletePiperPack } from './storage/voice-packs';
import { downloadBackup, restoreBackup } from './settings/backup-view';
import { renderShell } from './ui/shell';
import { icon } from './ui/icons';

type View = 'library' | 'reader' | 'models' | 'storage';
type Settings = { model: ModelId; voices: Record<string, string>; speed: number; theme: 'paper' | 'dark'; fontSize: number; spacing: number; width: 'narrow' | 'wide'; follow: boolean };
type PackManifest = { models: { id: ModelId; bytes: number; files: number }[]; runtimeBytes: number; totalBytes: number };
const SETTINGS_KEY = 'novaxis-reader-settings-v1';
const app = document.querySelector<HTMLDivElement>('#app');
if (!app) throw new Error('Reader app root is missing.');

let settings = readSettings();
let draftModel = settings.model;
let draftVoices = { ...settings.voices };
let view: View = 'library';
let book: BookRecord | undefined;
let content: ParsedBook | undefined;
let index = 0;
let playbackState = 'stopped';
let player: PlaybackController;
let pdfView: PdfPageView | undefined;
let bookVersion = 0;
let openingBook = false;
let openingWork: Promise<void> = Promise.resolve();
let positionWork: Promise<void> = Promise.resolve();
let books: BookRecord[] = [];
let progress = new Map<string, ReadingProgress>();
let coverUrls: string[] = [];
let libraryVersion = 0;
let windowStart = -1;
let toastTimer = 0;
let previewVersion = 0;
let previewAudio: HTMLAudioElement | undefined;
let previewTask: Promise<void> | undefined;
let packManifest: PackManifest | undefined;
let importing = false;

export async function startApp(): Promise<void> {
  app!.innerHTML = renderShell();
  player = new PlaybackController(el<HTMLAudioElement>('#audio'), (position, state) => {
    index = position; playbackState = state; paintPosition();
    if (book && content && !openingBook && ['playing', 'paused', 'ended'].includes(state)) {
      const id = book.id;
      const location = content.segments[position]?.locationLabel;
      progress.set(id, { bookId: id, segmentIndex: position, savedAt: Date.now() });
      book.lastLocation = location;
      positionWork = positionWork.catch(() => {}).then(async () => {
        await saveProgress(id, position);
        await updateBookMetadata(id, { lastLocation: location });
      }).catch(showError);
    }
  }, undefined, showError);
  player.setSettings(currentSettings());
  bindPlaybackControls({
    toggle: () => void togglePlayback(),
    previous: () => void jumpTo(index - 1),
    next: () => void jumpTo(index + 1),
    speed: value => { settings.speed = value; persistSettings(); text('#speed-value', value.toFixed(2) + '×'); player.setSettings(currentSettings()); },
  });
  app!.addEventListener('click', event => {
    const target = (event.target as Element).closest<HTMLElement>('[data-view],[data-action],[data-theme]');
    if (!target) return;
    if (target.dataset.view) { event.preventDefault(); setView(target.dataset.view as View); }
    if (target.dataset.action === 'add-book') chooseBook();
    if (target.dataset.action === 'close-dialog') target.closest<HTMLDialogElement>('dialog')?.close();
    if (target.dataset.theme) { settings.theme = target.dataset.theme as Settings['theme']; applyAppearance(); persistSettings(); }
  });
  on('#follow-voice', 'click', () => { settings.follow = !settings.follow; applyAppearance(); persistSettings(); if (settings.follow) revealPosition(); });
  on('#chapters-toggle', 'click', () => { renderChapters(); showDialog('#chapters'); });
  on('#preferences-toggle', 'click', () => showDialog('#preferences'));
  on('#reading-font', 'input', event => { settings.fontSize = Number((event.currentTarget as HTMLInputElement).value); applyAppearance(); persistSettings(); });
  on('#reading-spacing', 'change', event => { settings.spacing = Number((event.currentTarget as HTMLSelectElement).value); applyAppearance(); persistSettings(); });
  on('#reading-width', 'change', event => { settings.width = (event.currentTarget as HTMLSelectElement).value as Settings['width']; applyAppearance(); persistSettings(); });
  on('#player-expand', 'click', () => {
    const expanded = el('#player').classList.toggle('expanded');
    el('#player-expand').setAttribute('aria-expanded', String(expanded));
    document.body.dataset.playerExpanded = String(expanded);
  });
  on('#add-piper-voice', 'click', () => el<HTMLInputElement>('#voice-pack-picker').click());
  on('#voice-pack-picker', 'change', event => { const input = event.currentTarget as HTMLInputElement; const files = Array.from(input.files ?? []); if (files.length) void addPiperVoice(files); input.value = ''; });
  on('#try-voice', 'click', () => { if (!previewTask) previewTask = tryVoice().finally(() => { previewTask = undefined; }); });
  on('#use-voice', 'click', () => void useVoice());
  on('#voices-back', 'click', () => { cancelPreview(); el('#voice-stage').hidden = true; el('#model-stage').hidden = false; });
  on('#model', 'change', event => selectModel((event.currentTarget as HTMLSelectElement).value as ModelId));
  on('#voice', 'change', event => selectVoice((event.currentTarget as HTMLSelectElement).value));
  on('#export-library', 'click', () => void backupLibrary());
  on('#import-backup-label', 'keydown', event => { if (['Enter', ' '].includes((event as KeyboardEvent).key)) { event.preventDefault(); el<HTMLInputElement>('#import-backup').click(); } });
  on('#file-picker', 'change', event => { const input = event.currentTarget as HTMLInputElement; if (input.files?.[0]) void importFile(input.files[0]); input.value = ''; });
  on('#import-backup', 'change', event => { const input = event.currentTarget as HTMLInputElement; if (input.files?.[0]) void restoreFromFile(input.files[0]); input.value = ''; });
  on('#scrub', 'change', event => void jumpTo(Number((event.currentTarget as HTMLInputElement).value)));
  for (const selector of ['#library-search', '#library-sort', '#library-filter']) on(selector, selector === '#library-search' ? 'input' : 'change', paintLibrary);
  window.addEventListener('keydown', event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setView('library'); el<HTMLInputElement>('#library-search').focus(); } });
  let resizeTimer = 0;
  window.addEventListener('resize', () => { window.clearTimeout(resizeTimer); resizeTimer = window.setTimeout(() => { if (view === 'reader') pdfView?.refresh(); }, 200); });
  window.addEventListener('beforeunload', () => { coverUrls.forEach(url => URL.revokeObjectURL(url)); cancelPreview(); void player.dispose(); });
  setupDrop(); applyAppearance();
  try {
    await refreshPiperVoices(); renderVoiceSettings(); await refreshLibrary();
    // A bundled same-device manifest, never a remote lookup.
    const response = await fetch('/voice-packs/manifest.json');
    if (response.ok) packManifest = await response.json() as PackManifest;
    renderModels();
  } catch (error) { showError(error); }
  setView('library');
}

function setView(next: View): void {
  if (!['library', 'reader', 'models', 'storage'].includes(next)) return;
  if (next === 'reader' && !book) return notify('Choose a book from your library first.');
  if (next !== 'models') cancelPreview();
  view = next; document.body.dataset.view = view;
  for (const name of ['library', 'reader', 'models', 'storage']) el('#view-' + name).hidden = name !== view;
  document.querySelectorAll<HTMLElement>('.nav-button[data-view]').forEach(button => {
    if (button.dataset.view === view) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  });
  if (view === 'library') void refreshLibrary().catch(showError);
  if (view === 'storage') void refreshStorage().catch(showError);
  if (view === 'reader') {
    if (pdfView) { pdfView.refresh(); if (settings.follow && ['playing', 'loading'].includes(playbackState)) pdfView.follow(index, true, true); }
    else if (content) { renderPassageWindow(index, true); paintPosition(); }
  }
  // View navigation preserves the permanent audio element and playback controller.
  window.scrollTo({ top: 0, behavior: 'instant' });
}

function applyAppearance(): void {
  document.body.dataset.theme = settings.theme; document.body.dataset.readingWidth = settings.width;
  document.documentElement.style.setProperty('--reading-size', settings.fontSize + 'px');
  document.documentElement.style.setProperty('--reading-spacing', String(settings.spacing));
  el<HTMLInputElement>('#reading-font').value = String(settings.fontSize);
  el<HTMLSelectElement>('#reading-spacing').value = String(settings.spacing);
  el<HTMLSelectElement>('#reading-width').value = settings.width;
  document.querySelectorAll<HTMLElement>('[data-theme]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.theme === settings.theme)));
  el('#follow-voice').setAttribute('aria-pressed', String(settings.follow));
  el<HTMLInputElement>('#speed').value = String(settings.speed); text('#speed-value', settings.speed.toFixed(2) + '×');
}

async function refreshLibrary(): Promise<void> {
  const version = ++libraryVersion;
  await positionWork;
  const [storedBooks, positions] = await Promise.all([listBooks(), listReadingProgress()]);
  if (version !== libraryVersion) return;
  books = storedBooks; progress = new Map(positions.map(item => [item.bookId, item])); paintLibrary();
}

function paintLibrary(): void {
  coverUrls.forEach(url => URL.revokeObjectURL(url)); coverUrls = [];
  const search = el<HTMLInputElement>('#library-search').value.trim().toLocaleLowerCase();
  const filter = el<HTMLSelectElement>('#library-filter').value; const sort = el<HTMLSelectElement>('#library-sort').value;
  const visible = books.filter(item => (filter === 'all' || item.format === filter) && (item.title + ' ' + (item.author ?? '')).toLocaleLowerCase().includes(search));
  visible.sort((a, b) => sort === 'title' ? a.title.localeCompare(b.title) : sort === 'added' ? b.addedAt - a.addedAt : lastRead(b) - lastRead(a));
  text('#book-count', visible.length + ' of ' + books.length + ' books');
  const list = el('#library-list'); list.replaceChildren();
  for (const item of visible) {
    const card = node('article', 'book-card'); const open = action('', 'book-open', () => void openBook(item.id));
    open.setAttribute('aria-label', 'Open ' + item.title);
    const well = node('div', 'cover-well'); well.append(makeCover(item));
    const meta = node('span', 'book-meta'); meta.append(node('strong', '', item.title), node('small', '', item.author || 'From your device'));
    const status = node('span', 'book-status'); status.append(node('span', 'format-badge ' + item.format, item.format.toUpperCase()), node('small', '', readingStatus(item))); meta.append(status); open.append(well, meta);
    const remove = action('×', 'remove-book', () => askRemoveBook(item)); remove.setAttribute('aria-label', 'Remove ' + item.title);
    card.append(open, remove); list.append(card);
  }
  const empty = el('#library-empty'); empty.hidden = visible.length > 0;
  if (!visible.length) {
    empty.querySelector('h2')!.textContent = books.length ? 'No books match that search.' : 'Your next chapter starts here.';
    empty.querySelector('p')!.textContent = books.length ? 'Try another title, author, or format.' : 'Choose a PDF, EPUB, or text book from your device.';
  }
  const resume = [...books].filter(item => item.lastOpenedAt || progress.has(item.id)).sort((a, b) => lastRead(b) - lastRead(a))[0];
  const container = el('#continue-reading'); container.hidden = !resume; container.replaceChildren();
  if (resume) {
    const card = node('div', 'continue-card'); const cover = node('div', 'continue-cover'); cover.append(makeCover(resume));
    const copy = node('div', 'continue-copy'); copy.append(node('div', 'eyebrow', 'CONTINUE READING'), node('h2', '', resume.title), node('p', '', resume.author || 'Your saved place, on this device'), node('small', '', resume.lastPage ? 'Page ' + resume.lastPage : resume.lastLocation || readingStatus(resume)));
    const fraction = completion(resume);
    if (fraction !== undefined) { const track = node('div', 'progress-track'); const fill = node('span'); fill.style.width = fraction + '%'; track.append(fill); copy.append(track); }
    const actions = node('div', 'continue-actions');
    const listen = action('Listen from here', 'button button-secondary', () => void openBook(resume.id).then(() => { if (book?.id === resume.id && !['playing', 'loading'].includes(playbackState)) void togglePlayback(); }));
    listen.disabled = resume.segmentCount === 0;
    actions.append(action('Resume reading', 'button button-primary', () => void openBook(resume.id)), listen);
    card.append(cover, copy, actions); container.append(card);
  }
}

function makeCover(item: BookRecord): HTMLElement {
  const cover = node('span', 'book-cover');
  if (item.cover instanceof Blob) {
    const image = document.createElement('img'); const url = URL.createObjectURL(item.cover); coverUrls.push(url);
    image.src = url; image.alt = ''; image.loading = 'lazy'; cover.append(image);
  } else {
    let hash = 0; for (const letter of item.title) hash = (hash * 31 + letter.charCodeAt(0)) >>> 0;
    cover.dataset.tone = String(hash % 4);
    const ornament = node('span'); ornament.innerHTML = icon('sprout', 30);
    cover.append(node('small', '', item.format.toUpperCase()), ornament, node('span', 'book-cover-title', item.title), node('span', 'book-cover-author', item.author || 'Your library'));
  }
  return cover;
}
function lastRead(item: BookRecord): number { return Math.max(item.lastOpenedAt ?? 0, progress.get(item.id)?.savedAt ?? 0) || item.addedAt; }
function completion(item: BookRecord): number | undefined { const saved = progress.get(item.id); if (!saved || !item.segmentCount) return undefined; return Math.min(100, Math.round(saved.segmentIndex / Math.max(1, item.segmentCount - 1) * 100)); }
function readingStatus(item: BookRecord): string { const value = completion(item); return value === undefined ? (item.lastOpenedAt ? 'Opened' : 'Ready to read') : value + '% through'; }

async function importFile(file: File): Promise<void> {
  if (importing) return notify('Please wait for the current import to finish.');
  const format = getFormat(file); if (!format) return notify('Choose a PDF, EPUB, or plain text book.');
  importing = true;
  const ready = el<HTMLButtonElement>('#ready-open'); ready.disabled = true;
  text('#ready-title', 'Bringing in your book…'); text('#ready-detail', 'Copying ' + file.name + ' to this device.'); showDialog('#book-ready');
  try {
    const record: BookRecord = { id: crypto.randomUUID(), title: file.name.replace(/\.(pdf|epub|txt)$/i, ''), format, size: file.size, addedAt: Date.now(), file };
    await saveBook(record); void navigator.storage?.persist?.().catch(() => false); await refreshLibrary();
    text('#ready-title', 'Your book is ready.'); text('#ready-detail', record.title + ' · ' + format.toUpperCase() + ' · ' + formatBytes(file.size) + '. A copy is stored on this device.');
    ready.onclick = () => { el<HTMLDialogElement>('#book-ready').close(); void openBook(record.id); };
  } catch (error) { el<HTMLDialogElement>('#book-ready').close(); showError(error, () => void importFile(file)); }
  finally { ready.disabled = false; importing = false; }
}

function openBook(id: string): Promise<void> {
  if (book?.id === id && content && !openingBook) { setView('reader'); return Promise.resolve(); }
  const version = ++bookVersion;
  openingWork = openingWork.catch(() => {}).then(() => performOpen(id, version));
  return openingWork;
}
async function performOpen(id: string, version: number): Promise<void> {
  if (version !== bookVersion) return;
  openingBook = true;
  try {
    cancelPreview(); await previewTask; const record = await getBook(id);
    if (version !== bookVersion) return;
    if (!record) return notify('That book is no longer in the library.');
    await player.stop(); await positionWork; await pdfView?.destroy(); pdfView = undefined;
    if (version !== bookVersion) return;
    book = record; content = undefined; index = 0; windowStart = -1;
    document.body.dataset.book = 'true'; el('#player').hidden = true; el('#book-text').replaceChildren();
    text('#book-title', record.title); text('#player-title', record.title); text('#book-format', record.format.toUpperCase() + ' · ' + formatBytes(record.size));
    text('#reader-status', 'Opening your book on this device…'); setView('reader');
    const parsed = record.format === 'pdf' ? await parsePdf(record) : record.format === 'epub' ? await parseEpub(record) : await parseTxt(record);
    if (version !== bookVersion) return;
    const saved = await getProgress(record.id);
    if (version !== bookVersion) return;
    content = parsed;
    const restoredIndex = parsed.legacyIndexMap?.[saved?.segmentIndex ?? 0] ?? saved?.segmentIndex ?? 0;
    index = Math.max(0, Math.min(restoredIndex, parsed.segments.length - 1));
    await player.load(parsed.segments, index);
    if (version !== bookVersion) return;
    const metadata = { title: parsed.title || record.title, author: parsed.author ?? record.author, cover: parsed.cover ?? record.cover, segmentCount: parsed.segments.length, pageCount: parsed.pageCount, lastOpenedAt: Date.now(), lastLocation: parsed.segments[index]?.locationLabel };
    Object.assign(book, metadata);
    // Commit the new segment count and migrated index together, including when storage is full.
    await updateBookMetadata(id, metadata, saved && parsed.segments.length ? index : undefined);
    if (version !== bookVersion) return;
    text('#book-title', book.title); text('#player-title', book.title);
    el<HTMLInputElement>('#scrub').max = String(Math.max(0, parsed.segments.length - 1));
    if (record.format === 'pdf') {
      pdfView = new PdfPageView(el('#book-text'), parsed.segments, position => void jumpTo(position), showError, page => {
        if (version !== bookVersion) return;
        record.lastPage = page;
        positionWork = positionWork.catch(() => {}).then(() => updateBookMetadata(id, { lastPage: page })).catch(showError);
      });
      await pdfView.open(record.file, index, Boolean(saved), record.lastPage);
    }
    else renderPassageWindow(index, true);
    if (version !== bookVersion) return;
    el('#player').hidden = !parsed.segments.length;
    text('#reader-status', parsed.segments.length ? (record.format === 'pdf' ? 'Original PDF pages · ' : 'Tap a passage to listen · ') + parsed.segments.length + ' passages' : 'This PDF has no selectable text. You can view its pages; narration needs a text layer.');
    el<HTMLButtonElement>('#chapters-toggle').disabled = !parsed.segments.length;
    paintPosition(); await refreshLibrary();
    if (!parsed.segments.length) showRecovery('This book can be viewed.', 'The PDF contains images without selectable text. Its original pages are available here. To listen, import an EPUB, text book, or a PDF with a text layer.', [{ label: 'View the pages', run: () => setView('reader') }, { label: 'Choose another book', run: chooseBook }]);
  } catch (error) {
    if (version === bookVersion) { content = undefined; text('#reader-status', 'This book could not be opened. Your original file remains in the library.'); showError(error, () => void openBook(id)); }
  } finally { if (version === bookVersion) openingBook = false; }
}

function renderPassageWindow(position: number, force = false): void {
  if (!content || content.format === 'pdf') return;
  const segments = content.segments; const start = Math.max(0, Math.floor(position / 80) * 80);
  if (!force && start === windowStart) return;
  windowStart = start;
  const target = el('#book-text'); const fragment = document.createDocumentFragment();
  const controls = () => {
    const row = node('nav', 'passage-window-controls'); row.setAttribute('aria-label', 'Book passages');
    const previous = action('← Earlier passages', 'button button-quiet', () => void browseWindow(Math.max(0, start - 80))); previous.disabled = start === 0;
    const next = action('Later passages →', 'button button-quiet', () => void browseWindow(start + 80)); next.disabled = start + 80 >= segments.length;
    row.append(previous, node('small', '', (start + 1) + '–' + Math.min(start + 80, segments.length) + ' of ' + segments.length), next); return row;
  };
  fragment.append(controls());
  let block: HTMLElement | undefined;
  let blockId: string | undefined;
  for (let position = start; position < Math.min(start + 80, segments.length); position++) {
    const segment = segments[position];
    if (!block || !segment.blockId || blockId !== segment.blockId) {
      block = node(segment.kind === 'heading' ? 'h2' : 'p', 'reading-block' + (segment.kind === 'heading' ? ' heading' : ''));
      blockId = segment.blockId; fragment.append(block);
    }
    const passage = action(segment.text, 'segment-text reading-segment', () => void jumpTo(position));
    passage.dataset.index = String(position); block.append(passage, document.createTextNode(' '));
  }
  fragment.append(controls()); target.replaceChildren(fragment);
}

function renderChapters(): void {
  const list = el('#chapter-list'); list.replaceChildren();
  let chapters = content?.chapters;
  if (content?.format === 'pdf') {
    const pages = new Map<number, number>(); content.segments.forEach((segment, position) => { if (segment.pdf && !pages.has(segment.pdf.page)) pages.set(segment.pdf.page, position); });
    chapters = [...pages].map(([page, index]) => ({ title: 'Page ' + page, index }));
  }
  if (!chapters?.length && content?.segments.length) chapters = [{ title: 'Beginning', index: 0 }];
  for (const chapter of chapters ?? []) {
    const button = action(chapter.title, 'chapter-item', () => { el<HTMLDialogElement>('#chapters').close(); void seekWithoutPlaying(chapter.index).catch(showError); });
    const next = chapters?.find(item => item.index > chapter.index)?.index ?? content!.segments.length;
    if (index >= chapter.index && index < next) button.setAttribute('aria-current', 'location');
    list.append(button);
  }
}
function seekWithoutPlaying(position: number): Promise<void> {
  if (!book || !content?.segments.length || openingBook) return Promise.resolve();
  const version = bookVersion, bookId = book.id, parsed = content;
  const targetIndex = Math.max(0, Math.min(position, parsed.segments.length - 1));
  const ownsBook = () => version === bookVersion && book?.id === bookId && content === parsed;
  // Serialize seeks with book replacement, so a delayed seek cannot load another book's queue.
  openingWork = openingWork.catch(() => {}).then(async () => {
    if (!ownsBook()) return;
    await player.pause(); await positionWork;
    if (!ownsBook()) return;
    await player.load(parsed.segments, targetIndex);
    if (!ownsBook()) return;
    index = targetIndex;
    const location = parsed.segments[targetIndex].locationLabel;
    positionWork = positionWork.catch(() => {}).then(async () => { await saveProgress(bookId, targetIndex); await updateBookMetadata(bookId, { lastLocation: location }); });
    await positionWork;
    if (!ownsBook()) return;
    renderPassageWindow(index, true); paintPosition(); revealPosition();
  });
  return openingWork;
}
async function browseWindow(position: number): Promise<void> {
  try {
    if (['playing', 'loading'].includes(playbackState)) {
      settings.follow = false; applyAppearance(); persistSettings(); renderPassageWindow(position, true); paintPosition();
    } else await seekWithoutPlaying(position);
    window.scrollTo({ top: 0, behavior: 'instant' });
  } catch (error) { showError(error); }
}
async function jumpTo(position: number): Promise<void> {
  if (!content?.segments.length || openingBook) return;
  cancelPreview(); await previewTask;
  try { await player.jump(position); } catch (error) { showError(error, () => void jumpTo(position)); }
}
async function togglePlayback(): Promise<void> {
  if (!content?.segments.length || openingBook) return notify('Choose a readable book first.');
  cancelPreview(); await previewTask;
  try { if (playbackState === 'playing' || playbackState === 'loading') await player.pause(); else await player.play(content.segments, playbackState === 'ended' ? 0 : index); }
  catch (error) { showError(error, () => void togglePlayback()); }
}
function paintPosition(): void {
  if (content && view === 'reader') {
    const speaking = playbackState === 'playing' || playbackState === 'loading';
    pdfView?.follow(index, speaking, settings.follow);
    if (settings.follow) renderPassageWindow(index);
    document.querySelectorAll('.reading-segment.current').forEach(item => item.classList.remove('current'));
    const current = document.querySelector<HTMLElement>('.reading-segment[data-index="' + index + '"]'); current?.classList.add('current');
    if (current && speaking && settings.follow) scrollToPassage(current);
  }
  const button = el<HTMLButtonElement>('#play'); button.dataset.state = playbackState;
  button.textContent = playbackState === 'loading' ? '…' : playbackState === 'playing' ? 'Ⅱ' : '▶';
  button.setAttribute('aria-label', playbackState === 'loading' ? 'Cancel preparing audio' : playbackState === 'playing' ? 'Pause reading' : 'Play reading');
  el('#player').dataset.state = playbackState;
  text('#location', (playbackState === 'loading' ? 'Preparing audio · ' : playbackState === 'paused' ? 'Paused · ' : '') + (content?.segments[index]?.locationLabel ?? 'Ready'));
  text('#segment-count', content?.segments.length ? (index + 1) + ' / ' + content.segments.length + ' passages' : 'Ready');
  el<HTMLInputElement>('#scrub').value = String(index);
  if (playbackState === 'ended') notify('You have reached the end of this book.');
}
function scrollToPassage(target: HTMLElement): void {
  const rect = target.getBoundingClientRect(); const bottom = el('#player').getBoundingClientRect().top - 30;
  if (rect.top < 120 || rect.bottom > bottom) target.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' });
}
function revealPosition(): void {
  if (view !== 'reader') return;
  if (pdfView) pdfView.follow(index, true, true);
  else { renderPassageWindow(index, true); paintPosition(); document.querySelector<HTMLElement>('.reading-segment.current')?.scrollIntoView({ block: 'center' }); }
}

function renderVoiceSettings(): void {
  el<HTMLSelectElement>('#model').replaceChildren(...modelCatalog.map(item => new Option(item.label, item.id))); el<HTMLSelectElement>('#model').value = draftModel; fillVoices(); renderModels();
}
function renderModels(): void {
  const list = el('#model-cards'); list.replaceChildren();
  for (const model of modelCatalog) {
    const button = action('', 'model-card', () => selectModel(model.id)); button.setAttribute('aria-label', 'Choose ' + model.label); button.setAttribute('aria-pressed', String(model.id === settings.model));
    const top = node('div', 'model-card-top'); const mark = node('span', 'model-mark', model.label[0]); mark.dataset.model = model.id;
    const meta = node('div', 'model-meta'); meta.append(node('h3', '', model.label), node('p', '', model.detail)); top.append(mark, meta);
    const bytes = packManifest?.models.find(item => item.id === model.id)?.bytes;
    const foot = node('div', 'model-foot'); foot.append(node('span', '', model.voices.length + ' voices'), node('span', '', bytes === undefined ? 'Included locally' : formatBytes(bytes) + ' on disk'), node('span', 'local-badge', model.id === settings.model ? 'In use' : 'Choose voices →'));
    button.append(top, foot); list.append(button);
  }
}
function fillVoices(): void {
  const voices = modelById(draftModel).voices;
  if (!voices.some(voice => voice.id === draftVoices[draftModel])) draftVoices[draftModel] = voices[0].id;
  const select = el<HTMLSelectElement>('#voice'); select.replaceChildren(...voices.map(voice => new Option(voice.label, voice.id))); select.value = draftVoices[draftModel];
  text('#voice-title', modelById(draftModel).label + ' · choose a voice');
  const list = el('#voice-list'); list.replaceChildren();
  for (const voice of voices) {
    const button = action('', 'voice-option', () => selectVoice(voice.id)); button.setAttribute('aria-pressed', String(voice.id === draftVoices[draftModel]));
    button.append(node('strong', '', voice.label), node('small', '', voice.id.startsWith('local:') ? 'Imported on this device' : 'Included on this device')); list.append(button);
  }
}
function selectModel(model: ModelId): void { cancelPreview(); draftModel = model; el<HTMLSelectElement>('#model').value = model; el('#model-stage').hidden = true; el('#voice-stage').hidden = false; fillVoices(); }
function selectVoice(voice: string): void { cancelPreview(); draftVoices[draftModel] = voice; fillVoices(); }
async function useVoice(): Promise<void> {
  cancelPreview(); await previewTask;
  settings.model = draftModel; settings.voices = { ...draftVoices }; persistSettings(); player.setSettings(currentSettings()); renderModels();
  notify((modelById(settings.model).voices.find(voice => voice.id === settings.voices[settings.model])?.label ?? 'Voice') + ' selected.'); setView(book ? 'reader' : 'library');
}
async function tryVoice(): Promise<void> {
  const version = ++previewVersion; const selection = { model: draftModel, voice: draftVoices[draftModel], speed: settings.speed };
  const button = el<HTMLButtonElement>('#try-voice'); button.disabled = true; button.textContent = 'Preparing voice…';
  let engine: Awaited<ReturnType<typeof createSpeechEngine>> | undefined; let url = '';
  try {
    // Release narration before auditioning another engine to limit memory use.
    await player.stop(); if (version !== previewVersion) return;
    engine = await createSpeechEngine(selection.model); await engine.initialize(selection.voice);
    if (version !== previewVersion) return;
    const clip = await engine.synthesize('A quiet voice can make reading feel easier. Your books, your voice, and your own space to think.', selection.speed);
    if (version !== previewVersion) return;
    url = URL.createObjectURL(clip); const preview = new Audio(url); previewAudio = preview;
    await new Promise<void>((resolve, reject) => { preview.onended = () => resolve(); preview.onerror = () => reject(new Error('This device could not play the generated audio.')); preview.play().then(() => { button.textContent = 'Playing preview…'; }).catch(reject); });
  } catch (error) { if (version === previewVersion) showError(error, () => el<HTMLButtonElement>('#try-voice').click()); }
  finally {
    if (url) URL.revokeObjectURL(url); previewAudio = undefined;
    try { await engine?.dispose(); } catch (error) { if (version === previewVersion) showError(error); }
    button.disabled = false; button.innerHTML = icon('headphones') + 'Preview voice';
  }
}
function cancelPreview(): void { previewVersion++; if (previewAudio) { previewAudio.pause(); previewAudio.dispatchEvent(new Event('ended')); } }

async function refreshPiperVoices(): Promise<void> {
  modelById('piper').voices = [{ id: 'en_US-lessac-medium', label: 'Lessac · US English', model: 'piper' }, ...(await listPiperPacks()).map(pack => ({ id: pack.id, label: pack.label, model: 'piper' as const }))];
  if (!modelById('piper').voices.some(voice => voice.id === settings.voices.piper)) settings.voices.piper = 'en_US-lessac-medium'; draftVoices = { ...settings.voices };
}
async function addPiperVoice(files: File[]): Promise<void> {
  try { cancelPreview(); await previewTask; const pack = await importPiperPack(files); await refreshPiperVoices(); draftModel = 'piper'; draftVoices.piper = pack.id; renderVoiceSettings(); selectModel('piper'); setView('models'); notify('Piper voice stored. Preview it, then choose Use this voice.'); }
  catch (error) { showError(error, () => void addPiperVoice(files)); }
}
async function removePiperVoice(id: string): Promise<void> {
  try {
    cancelPreview(); await previewTask;
    if (settings.voices.piper === id) { await player.stop(); settings.voices.piper = 'en_US-lessac-medium'; player.setSettings(currentSettings()); persistSettings(); }
    await deletePiperPack(id); await refreshPiperVoices(); renderVoiceSettings(); await refreshStorage(); notify('Imported voice removed from this device.');
  } catch (error) { showError(error); }
}
async function refreshStorage(): Promise<void> {
  const [storedBooks, packs, estimate] = await Promise.all([listBooks(), listPiperPacks(), navigator.storage?.estimate?.().catch(() => undefined)]);
  const bookBytes = storedBooks.reduce((sum, item) => sum + item.size + (item.cover?.size ?? 0), 0); const voiceBytes = packs.reduce((sum, item) => sum + item.model.size, 0);
  const summary = el('#storage-summary'); summary.replaceChildren();
  for (const [label, value, detail] of [
    ['Books & covers', formatBytes(bookBytes), storedBooks.length + ' books stored here'],
    ['Bundled speech files', packManifest ? formatBytes(packManifest.totalBytes) : 'Included', 'Models, voices and speech runtimes'],
    ['Imported voice files', formatBytes(voiceBytes), packs.length + ' additional Piper voices'],
  ]) { const card = node('div', 'storage-stat'); card.append(node('small', '', label), node('strong', '', value), node('span', '', detail)); summary.append(card); }
  if (estimate?.quota !== undefined && estimate.usage !== undefined) summary.append(node('p', 'storage-estimate', 'This browser reports ' + formatBytes(estimate.usage) + ' used of a ' + formatBytes(estimate.quota) + ' allowance. Bundled app files are shown separately.'));
  const bookList = el('#stored-books'); bookList.replaceChildren();
  for (const item of storedBooks) {
    const row = node('div', 'storage-row'); const main = node('div', 'storage-row-main'); main.append(node('strong', '', item.title), node('small', '', item.format.toUpperCase() + ' · ' + formatBytes(item.size)));
    const actions = node('div', 'storage-row-actions'); actions.append(action('Open', 'button button-quiet', () => void openBook(item.id)), action('Remove', 'button button-quiet', () => askRemoveBook(item))); row.append(main, actions); bookList.append(row);
  }
  if (!storedBooks.length) bookList.append(node('p', 'quiet-note', 'No books stored yet.'));
  const voiceList = el('#stored-voices'); voiceList.replaceChildren();
  for (const model of modelCatalog) {
    const bytes = packManifest?.models.find(item => item.id === model.id)?.bytes; const row = node('div', 'storage-row'); const main = node('div', 'storage-row-main');
    main.append(node('strong', '', model.label), node('small', '', model.voices.filter(voice => !voice.id.startsWith('local:')).length + ' included voices' + (bytes === undefined ? '' : ' · ' + formatBytes(bytes))));
    row.append(main, action('Choose voice', 'button button-quiet', () => { setView('models'); selectModel(model.id); })); voiceList.append(row);
  }
  for (const pack of packs) {
    const row = node('div', 'storage-row'); const main = node('div', 'storage-row-main'); main.append(node('strong', '', pack.label), node('small', '', formatBytes(pack.model.size) + ' · imported Piper voice'));
    row.append(main, action('Remove', 'button button-quiet', () => showRecovery('Remove this voice?', 'Keep its original files if you want to add it again.', [{ label: 'Keep voice', run: () => {} }, { label: 'Remove voice', run: () => void removePiperVoice(pack.id) }]))); voiceList.append(row);
  }
}
function askRemoveBook(item: BookRecord): void {
  showRecovery('Remove this book?', item.title + ' and its saved position will be removed from this device. Your original file stays where you imported it from.', [{ label: 'Keep book', run: () => {} }, { label: 'Remove book', run: () => void removeBook(item.id) }]);
}
async function removeBook(id: string): Promise<void> {
  try { if (book?.id === id) await unloadBook(); await positionWork; await deleteBook(id); await refreshLibrary(); if (view === 'storage') await refreshStorage(); notify('Book removed from this device.'); } catch (error) { showError(error); }
}
async function unloadBook(): Promise<void> {
  bookVersion++; await openingWork; openingBook = true;
  await player.stop(); await positionWork; await pdfView?.destroy(); pdfView = undefined; book = undefined; content = undefined; openingBook = false;
  document.body.dataset.book = 'false'; el('#player').hidden = true; setView('library');
}
async function backupLibrary(): Promise<void> {
  const button = el<HTMLButtonElement>('#export-library'); button.disabled = true;
  try { await positionWork; await downloadBackup(); notify('Library backup saved. Keep it in your own storage.'); } catch (error) { showError(error, () => void backupLibrary()); } finally { button.disabled = false; }
}
async function restoreFromFile(file: File): Promise<void> {
  if (importing) return notify('Please wait for the current import to finish.'); importing = true;
  try { cancelPreview(); await previewTask; await unloadBook(); notify('Restoring books on this device…'); const count = await restoreBackup(file); await refreshLibrary(); notify(count + ' book' + (count === 1 ? '' : 's') + ' restored.'); }
  catch (error) { await refreshLibrary().catch(() => {}); showError(error, () => void restoreFromFile(file)); } finally { importing = false; }
}

function setupDrop(): void {
  const target = el('#main-content');
  target.addEventListener('dragover', event => { event.preventDefault(); target.classList.add('drop-active'); }); target.addEventListener('dragleave', () => target.classList.remove('drop-active'));
  target.addEventListener('drop', event => { event.preventDefault(); target.classList.remove('drop-active'); const file = event.dataTransfer?.files[0]; if (file) void importFile(file); });
}
function showDialog(selector: string): void { const dialog = el<HTMLDialogElement>(selector); if (!dialog.open) dialog.showModal(); }
function showRecovery(title: string, message: string, actions: { label: string; run: () => void }[]): void {
  text('#recovery-title', title); text('#recovery-message', message); const target = el('#recovery-actions'); target.replaceChildren();
  actions.forEach((item, index) => target.append(action(item.label, 'button ' + (index === 0 ? 'button-primary' : 'button-secondary'), () => { el<HTMLDialogElement>('#recovery').close(); item.run(); }))); showDialog('#recovery');
}
function showError(error: unknown, retry?: () => void): void {
  const message = error instanceof Error ? error.message : 'Something went wrong while reading.';
  const quota = error instanceof DOMException && error.name === 'QuotaExceededError'; const missing = /fetch|missing|not found|load.*model|voice.*file/i.test(message);
  showRecovery(quota ? 'This device needs more space.' : missing ? 'The local voice could not load.' : 'Let’s get you reading.',
    quota ? 'Your saved books and position are still here. Back up your library, then remove books or imported voices to make room.' : missing ? 'A local file may be missing or this device could not load it. Choose another bundled voice, or restore the complete local app package. Nothing is downloaded automatically.' : message,
    [...(retry ? [{ label: 'Try again', run: retry }] : []), { label: quota ? 'Manage storage' : missing ? 'Choose another voice' : 'Close', run: () => { if (quota) setView('storage'); else if (missing) setView('models'); } }]);
}
function readSettings(): Settings {
  const defaults: Settings = { model: 'piper', voices: { piper: 'en_US-lessac-medium' }, speed: 1, theme: 'paper', fontSize: 22, spacing: 1.75, width: 'narrow', follow: true };
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? 'null'); if (!saved || !modelCatalog.some(item => item.id === saved.model)) return defaults;
    return { ...defaults, model: saved.model, voices: saved.voices && typeof saved.voices === 'object' && !Array.isArray(saved.voices) ? saved.voices : defaults.voices,
      speed: Math.max(.7, Math.min(2, Number(saved.speed) || 1)), theme: saved.theme === 'dark' ? 'dark' : 'paper', fontSize: Math.max(16, Math.min(32, Number(saved.fontSize) || 22)),
      spacing: [1.5, 1.75, 2].includes(saved.spacing) ? saved.spacing : 1.75, width: saved.width === 'wide' ? 'wide' : 'narrow', follow: saved.follow !== false };
  } catch { return defaults; }
}
function persistSettings(): void { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); } catch { notify('This browser could not save appearance and voice preferences.'); } }
function currentSettings() { return { model: settings.model, voice: settings.voices[settings.model] || modelById(settings.model).voices[0].id, speed: settings.speed }; }
function chooseBook(): void { el<HTMLInputElement>('#file-picker').click(); }
function getFormat(file: File): BookFormat | undefined { const name = file.name.toLowerCase(); if (name.endsWith('.pdf') || file.type === 'application/pdf') return 'pdf'; if (name.endsWith('.epub') || file.type === 'application/epub+zip') return 'epub'; if (name.endsWith('.txt') || file.type === 'text/plain') return 'txt'; }
function formatBytes(size: number): string { if (!size) return '0 KB'; if (size >= 1073741824) return (size / 1073741824).toFixed(1) + ' GB'; return size < 1048576 ? Math.max(1, Math.round(size / 1024)) + ' KB' : (size / 1048576).toFixed(1) + ' MB'; }
function el<T extends HTMLElement = HTMLElement>(selector: string): T { return document.querySelector<T>(selector)!; }
function node<K extends keyof HTMLElementTagNameMap>(tag: K, className = '', value?: string): HTMLElementTagNameMap[K] { const target = document.createElement(tag); target.className = className; if (value !== undefined) target.textContent = value; return target; }
function action(label: string, className: string, run: () => void): HTMLButtonElement { const button = node('button', className, label); button.type = 'button'; button.addEventListener('click', run); return button; }
function text(selector: string, value: string): void { const target = document.querySelector<HTMLElement>(selector); if (target) target.textContent = value; }
function on(selector: string, event: string, listener: EventListener): void { document.querySelector(selector)?.addEventListener(event, listener); }
function notify(message: string): void { const toast = el('#toast'); toast.textContent = message; toast.classList.add('visible'); window.clearTimeout(toastTimer); toastTimer = window.setTimeout(() => toast.classList.remove('visible'), 4400); }
