import type { BookFormat, BookRecord, ParsedBook, ReadableSegment } from './domain/book';
import { parseEpub } from './formats/epub-content';
import { parsePdf } from './formats/pdf-content';
import { parseTxt } from './formats/txt-content';
import { deleteBook, getBook, getProgress, listBooks, saveBook, saveProgress } from './storage/book-store';
import { modelById, modelCatalog, type ModelId } from './tts/catalog';
import { createSpeechEngine } from './tts/engine-factory';
import { PlaybackController } from './reader/playback-controller';
import { bindPlaybackControls } from './reader/playback-controls';
import { listPiperPacks, importPiperPack, deletePiperPack } from './storage/voice-packs';
import { downloadBackup, restoreBackup } from './settings/backup-view';

type Settings = { model: ModelId; voices: Record<string, string>; speed: number };
const SETTINGS_KEY = 'novaxis-reader-settings-v1';
const app = document.querySelector<HTMLDivElement>('#app');
if (!app) throw new Error('Reader app root is missing.');

let settings = readSettings();
let book: BookRecord | undefined;
let content: ParsedBook | undefined;
let index = 0;
let player: PlaybackController;
let audio: HTMLAudioElement;
let toastTimer = 0;
let previewVersion = 0;
let previewAudio: HTMLAudioElement | undefined;
let previewTask: Promise<void> | undefined;

export async function startApp(): Promise<void> {
  renderShell();
  audio = el<HTMLAudioElement>('#audio');
  player = new PlaybackController(audio, (position, state) => {
    index = position;
    paintPosition(position, state);
    if (book) void saveProgress(book.id, position).catch(showError);
  }, undefined, showError);
  player.setSettings(currentSettings());
  bindPlaybackControls({
    toggle: () => void togglePlayback(),
    previous: () => void player.jump(index - 1).catch(showError),
    next: () => void player.jump(index + 1).catch(showError),
    speed: (value) => {
      settings.speed = value;
      persistSettings();
      text('#speed-value', value.toFixed(2) + '×');
      player.setSettings(currentSettings());
    },
  });
  on('#remove-piper-voice', 'click', () => void removePiperVoice());
  on('#add-piper-voice', 'click', () => el<HTMLInputElement>('#voice-pack-picker').click());
  on('#voice-pack-picker', 'change', (event) => {
    const input = event.currentTarget as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (files.length) void addPiperVoice(files);
    input.value = '';
  });
  on('#add-book', 'click', chooseBook);
  on('#welcome-add', 'click', chooseBook);
  on('#import-card', 'click', chooseBook);
  on('#close-book', 'click', () => void closeBook());
  on('#try-voice', 'click', () => { if (!previewTask) previewTask = tryVoice().finally(() => { previewTask = undefined; }); });
  on('#export-library', 'click', () => void backupLibrary());
  on('#model', 'change', (event) => changeModel((event.currentTarget as HTMLSelectElement).value as ModelId));
  on('#voice', 'change', (event) => changeVoice((event.currentTarget as HTMLSelectElement).value));
  on('#file-picker', 'change', (event) => {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (file) void importFile(file);
    input.value = '';
  });
  on('#import-backup', 'change', (event) => {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (file) void restoreFromFile(file);
    input.value = '';
  });
  on('#scrub', 'input', (event) => void player.jump(Number((event.currentTarget as HTMLInputElement).value)).catch(showError));
  window.addEventListener('beforeunload', () => void player.dispose());
  setupDrop();
  await refreshPiperVoices();
  renderVoiceSettings();
  await refreshLibrary();
}

function renderShell(): void {
  app!.innerHTML = '<header class="topbar"><a class="brand" href="#"><span class="brand-mark">N</span><span>Novaxis <em>Reader</em></span></a><div class="top-actions"><button class="quiet-button" id="export-library" type="button">Back up library</button><label class="quiet-button import-label" for="import-backup">Restore</label><input id="import-backup" type="file" accept=".zip,application/zip" hidden><button class="add-button" id="add-book" type="button">Add a book</button><input id="file-picker" type="file" accept=".pdf,.epub,.txt,application/pdf,application/epub+zip,text/plain" hidden></div></header><main class="layout"><aside class="library"><div class="section-heading"><span>Your library</span><span class="library-count" id="book-count">0</span></div><div id="library-list" class="library-list"></div><button class="import-card" id="import-card" type="button"><span class="plus-icon">+</span><span><strong>Bring in a book</strong><small>PDF, EPUB, or plain text</small></span></button><section class="voice-panel"><div class="section-heading"><span>Reading voice</span><span class="offline-tag"><i></i> local</span></div><label class="field-label" for="model">VOICE ENGINE</label><select id="model" class="field-select"></select><label class="field-label" for="voice">VOICE</label><select id="voice" class="field-select"></select><button id="add-piper-voice" class="quiet-button" type="button" hidden>Add a Piper voice</button><button id="remove-piper-voice" class="quiet-button" type="button" hidden>Remove this voice</button><input id="voice-pack-picker" type="file" accept=".onnx,.json" multiple hidden><button id="try-voice" class="try-button" type="button">▶ Try this voice</button><p class="local-note">Voice runs on this device. No account or connection needed.</p></section><p class="storage-note">● Your books stay on this device.</p></aside><section class="reader-column"><div id="welcome" class="welcome-card"><div class="eyebrow"><span></span> YOUR OWN PRIVATE READER</div><h1>Make time for<br><i>the words that matter.</i></h1><p>Bring a book from your device. Read quietly, or listen with a voice that stays yours.</p><button class="add-button large" id="welcome-add" type="button">Choose a book →</button><div class="supported-formats"><b>PDF</b><b>EPUB</b><b>TXT</b><span>Works offline</span></div></div><div id="reader" class="reader" hidden><div class="book-heading"><div><div class="eyebrow" id="book-format">BOOK</div><h1 id="book-title"></h1></div><button class="quiet-button" id="close-book" type="button">Library</button></div><div id="reader-status" class="reader-status" aria-live="polite"></div><article id="book-text" class="book-text"></article></div></section></main><footer id="player" class="player" hidden><button class="transport" id="previous" type="button" aria-label="Previous passage">‹‹</button><button class="play-button" id="play" type="button" aria-label="Play or pause">▶</button><button class="transport" id="next" type="button" aria-label="Next passage">››</button><div class="progress-area"><div class="progress-meta"><span id="location">Ready</span><span id="segment-count">—</span></div><input id="scrub" class="scrub" type="range" min="0" max="0" value="0" aria-label="Reading position"></div><label class="speed-control"><span>SPEED</span><input id="speed" type="range" min="0.7" max="1.5" step="0.05"><b id="speed-value"></b></label><audio id="audio" preload="auto"></audio></footer><div id="toast" class="toast" role="status" aria-live="polite"></div>';
}

function renderVoiceSettings(): void {
  const select = el<HTMLSelectElement>('#model');
  select.replaceChildren(...modelCatalog.map((item) => new Option(item.label, item.id)));
  select.value = settings.model;
  fillVoices();
  el<HTMLInputElement>('#speed').value = String(settings.speed);
  text('#speed-value', settings.speed.toFixed(2) + '×');
}

function fillVoices(): void {
  el<HTMLElement>('#add-piper-voice').hidden = settings.model !== 'piper';
  const voices = modelById(settings.model).voices;
  if (!voices.some((voice) => voice.id === settings.voices[settings.model])) {
    settings.voices[settings.model] = voices[0].id;
  }
  const select = el<HTMLSelectElement>('#voice');
  select.replaceChildren(...voices.map((voice) => new Option(voice.label, voice.id)));
  select.value = settings.voices[settings.model];
  el<HTMLElement>('#remove-piper-voice').hidden = settings.model !== 'piper' || !select.value.startsWith('local:');
}

async function refreshPiperVoices(): Promise<void> {
  modelById('piper').voices = [
    { id: 'en_US-lessac-medium', label: 'Lessac · US English', model: 'piper' },
    ...(await listPiperPacks()).map(pack => ({ id: pack.id, label: pack.label, model: 'piper' as const })),
  ];
}

async function addPiperVoice(files: File[]): Promise<void> {
  try {
    cancelPreview();
    await previewTask;
    await player.stop();
    const pack = await importPiperPack(files);
    await refreshPiperVoices();
    settings.model = 'piper';
    settings.voices.piper = pack.id;
    renderVoiceSettings();
    persistSettings();
    player.setSettings(currentSettings());
    notify('Piper voice stored on this device.');
  } catch (error) { showError(error); }
}

async function removePiperVoice(): Promise<void> {
  try {
    const id = settings.voices.piper;
    if (!id?.startsWith('local:')) return;
    cancelPreview();
    await previewTask;
    await player.stop();
    await deletePiperPack(id);
    settings.voices.piper = 'en_US-lessac-medium';
    await refreshPiperVoices();
    renderVoiceSettings();
    persistSettings();
    player.setSettings(currentSettings());
    notify('Extra voice removed from this device.');
  } catch (error) { showError(error); }
}

async function importFile(file: File): Promise<void> {
  const format = getFormat(file);
  if (!format) return notify('Choose a PDF, EPUB, or plain text file.');
  const record: BookRecord = {
    id: crypto.randomUUID(),
    title: file.name.replace(/\.(pdf|epub|txt)$/i, ''),
    format,
    size: file.size,
    addedAt: Date.now(),
    file,
  };
  try {
    await saveBook(record);
    await refreshLibrary();
    await openBook(record.id);
  } catch (error) { showError(error); }
}

async function openBook(id: string): Promise<void> {
  cancelPreview();
  await previewTask;
  const record = await getBook(id);
  if (!record) return notify('That book is no longer in the library.');
  await player.stop();
  book = record;
  content = undefined;
  el<HTMLElement>('#welcome').hidden = true;
  el<HTMLElement>('#reader').hidden = false;
  el<HTMLElement>('#book-text').replaceChildren();
  text('#book-title', record.title);
  text('#book-format', record.format.toUpperCase() + ' · ' + formatBytes(record.size));
  text('#reader-status', 'Opening book on this device…');
  try {
    if (record.format === 'pdf') content = await parsePdf(record);
    else if (record.format === 'epub') content = await parseEpub(record);
    else content = await parseTxt(record);
    const progress = await getProgress(record.id);
    index = Math.min(progress?.segmentIndex ?? 0, content.segments.length - 1);
    await player.load(content.segments, index);
    renderSegments(content.segments);
    el<HTMLElement>('#player').hidden = false;
    text('#reader-status', content.segments.length + ' passages · stored on this device');
    paintPosition(index, 'stopped');
  } catch (error) {
    text('#reader-status', error instanceof Error ? error.message : 'Could not open this book.');
  }
}

function renderSegments(segments: ReadableSegment[]): void {
  const target = el<HTMLElement>('#book-text');
  const fragment = document.createDocumentFragment();
  segments.forEach((segment, position) => {
    const paragraph = document.createElement('p');
    paragraph.className = 'reading-segment';
    paragraph.dataset.index = String(position);
    const label = document.createElement('span');
    label.className = 'segment-location';
    label.textContent = segment.locationLabel;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'segment-text';
    button.textContent = segment.text;
    button.addEventListener('click', () => void player.play(segments, position).catch(showError));
    paragraph.append(label, button);
    fragment.append(paragraph);
  });
  target.replaceChildren(fragment);
  const scrub = el<HTMLInputElement>('#scrub');
  scrub.max = String(Math.max(0, segments.length - 1));
  text('#segment-count', segments.length + ' passages');
}

async function togglePlayback(): Promise<void> {
  if (!content) return notify('Choose a book first.');
  cancelPreview();
  await previewTask;
  try {
    const state = el<HTMLButtonElement>('#play').dataset.state;
    if (state === 'playing' || state === 'loading') await player.pause();
    else await player.play(content.segments, index);
  } catch (error) { showError(error); }
}

async function tryVoice(): Promise<void> {
  const operation = ++previewVersion;
  const selection = currentSettings();
  const button = el<HTMLButtonElement>('#try-voice');
  button.disabled = true;
  let engine: Awaited<ReturnType<typeof createSpeechEngine>> | undefined;
  let url = '';
  try {
    await player.stop();
    notify('Preparing the local voice…');
    engine = await createSpeechEngine(selection.model);
    await engine.initialize(selection.voice);
    if (operation !== previewVersion) return;
    const clip = await engine.synthesize('A quiet voice can make reading feel easier.', selection.speed);
    if (operation !== previewVersion) return;
    url = URL.createObjectURL(clip);
    const preview = new Audio(url);
    previewAudio = preview;
    await new Promise<void>((resolve, reject) => {
      preview.onended = () => resolve();
      preview.onerror = () => reject(new Error('This device could not play the generated audio.'));
      preview.play().then(() => notify('Playing a local voice sample.')).catch(reject);
    });
  } catch (error) { if (operation === previewVersion) showError(error); }
  finally {
    if (url) URL.revokeObjectURL(url);
    previewAudio = undefined;
    await engine?.dispose();
    button.disabled = false;
  }
}

function cancelPreview(): void {
  previewVersion++;
  if (previewAudio) {
    previewAudio.pause();
    previewAudio.dispatchEvent(new Event('ended'));
  }
}

function paintPosition(position: number, state: string): void {
  document.querySelector('.reading-segment.current')?.classList.remove('current');
  const current = document.querySelector<HTMLElement>('.reading-segment[data-index="' + position + '"]');
  current?.classList.add('current');
  if (current && (state === 'playing' || state === 'loading')) current.scrollIntoView({ behavior: 'smooth', block: 'center' });
  const button = el<HTMLButtonElement>('#play');
  button.dataset.state = state;
  button.textContent = state === 'playing' ? 'Ⅱ' : '▶';
  text('#location', (state === 'loading' ? 'Preparing · ' : '') + (current?.querySelector('.segment-location')?.textContent ?? 'Ready'));
  const scrub = document.querySelector<HTMLInputElement>('#scrub');
  if (scrub) scrub.value = String(position);
  if (state === 'ended') notify('You have reached the end of this book.');
}

function changeModel(model: ModelId): void {
  cancelPreview();
  settings.model = model;
  fillVoices();
  persistSettings();
  player.setSettings(currentSettings());
}

function changeVoice(voice: string): void {
  cancelPreview();
  settings.voices[settings.model] = voice;
  fillVoices();
  persistSettings();
  player.setSettings(currentSettings());
}

async function refreshLibrary(): Promise<void> {
  const books = await listBooks();
  const list = el<HTMLDivElement>('#library-list');
  list.replaceChildren();
  text('#book-count', String(books.length));
  for (const item of books) {
    const row = document.createElement('div');
    row.className = 'book-row';
    const open = document.createElement('button');
    open.className = 'book-open';
    open.type = 'button';
    const badge = document.createElement('span');
    badge.className = 'format-badge ' + item.format;
    badge.textContent = item.format.toUpperCase();
    const meta = document.createElement('span');
    meta.className = 'book-meta';
    const title = document.createElement('span');
    title.className = 'book-row-title';
    title.textContent = item.title;
    const size = document.createElement('small');
    size.textContent = formatBytes(item.size);
    meta.append(title, size);
    open.append(badge, meta);
    open.addEventListener('click', () => void openBook(item.id));
    const remove = document.createElement('button');
    remove.className = 'remove-book';
    remove.type = 'button';
    remove.textContent = '×';
    remove.setAttribute('aria-label', 'Remove ' + item.title);
    remove.addEventListener('click', async () => {
      if (book?.id === item.id) await closeBook();
      await deleteBook(item.id);
      await refreshLibrary();
      notify('Book removed from this device.');
    });
    row.append(open, remove);
    list.append(row);
  }
}

async function closeBook(): Promise<void> {
  await player.stop();
  book = undefined;
  content = undefined;
  el<HTMLElement>('#reader').hidden = true;
  el<HTMLElement>('#welcome').hidden = false;
  el<HTMLElement>('#player').hidden = true;
}

async function backupLibrary(): Promise<void> {
  try {
    await downloadBackup();
    notify('Library backup saved to this device.');
  } catch (error) { showError(error); }
}

async function restoreFromFile(file: File): Promise<void> {
  try {
    await closeBook();
    const count = await restoreBackup(file);
    await refreshLibrary();
    notify(count + ' book' + (count === 1 ? '' : 's') + ' restored.');
  } catch (error) { showError(error); }
}

function setupDrop(): void {
  const target = el<HTMLElement>('.reader-column');
  target.addEventListener('dragover', (event) => { event.preventDefault(); target.classList.add('drop-active'); });
  target.addEventListener('dragleave', () => target.classList.remove('drop-active'));
  target.addEventListener('drop', (event) => {
    event.preventDefault();
    target.classList.remove('drop-active');
    const file = (event as DragEvent).dataTransfer?.files[0];
    if (file) void importFile(file);
  });
}

function readSettings(): Settings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? 'null') as Settings | null;
    if (saved && saved.voices && typeof saved.voices === 'object' && modelCatalog.some((item) => item.id === saved.model)) {
      saved.speed = Math.max(0.7, Math.min(1.5, Number(saved.speed) || 1));
      return saved;
    }
  } catch { /* Use the default voice if stored preferences are unreadable. */ }
  return { model: 'piper', voices: { piper: 'en_US-lessac-medium' }, speed: 1 };
}

function persistSettings(): void { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); }
function currentSettings() { return { model: settings.model, voice: settings.voices[settings.model], speed: settings.speed }; }
function chooseBook(): void { el<HTMLInputElement>('#file-picker').click(); }
function getFormat(file: File): BookFormat | undefined {
  const name = file.name.toLowerCase();
  if (name.endsWith('.pdf') || file.type === 'application/pdf') return 'pdf';
  if (name.endsWith('.epub') || file.type === 'application/epub+zip') return 'epub';
  if (name.endsWith('.txt') || file.type === 'text/plain') return 'txt';
  return undefined;
}
function formatBytes(size: number): string { return size < 1048576 ? Math.max(1, Math.round(size / 1024)) + ' KB' : (size / 1048576).toFixed(1) + ' MB'; }
function el<T extends HTMLElement>(selector: string): T { return document.querySelector<T>(selector)!; }
function text(selector: string, value: string): void { const target = document.querySelector<HTMLElement>(selector); if (target) target.textContent = value; }
function on(selector: string, event: string, listener: EventListener): void { document.querySelector(selector)?.addEventListener(event, listener); }
function notify(message: string): void {
  const toast = el<HTMLElement>('#toast');
  toast.textContent = message;
  toast.classList.add('visible');
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove('visible'), 3400);
}
function showError(error: unknown): void { notify(error instanceof DOMException && error.name === 'QuotaExceededError' ? 'This device has no room for that book. Back up your library and remove books to free space.' : error instanceof Error ? error.message : 'Something went wrong while reading.'); }
