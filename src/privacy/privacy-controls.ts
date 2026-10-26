export const POLICY_VERSION = '2026-10-26.2';
export const PUBLISHER = 'Irenikase Samuel Temitope';
const CONSENT_KEY = 'novaxis-reader-agreement';
const DISMISS_KEY = 'novaxis-reader-policy-dismissed';
const ACCESS_KEY = 'novaxis-reader-file-explanations';
type Consent = { version: string; acceptedAt: string; privacyAcknowledged: true; termsAccepted: true };
export type FileAccess = 'books' | 'voices' | 'restore' | 'backup';
let consent = readConsent();
let sessionOnly = false;
let pendingUsage: (() => void) | undefined;
let pendingFile: (() => void) | undefined;
let pendingKind: FileAccess | undefined;
let withdrawn: () => Promise<void>;

export function privacyMarkup(): string {
  return `<dialog id="privacy-dialog" class="reader-dialog policy-dialog" aria-labelledby="privacy-heading" aria-describedby="policy-intro">
    <div class="dialog-heading"><h2 id="privacy-heading">Privacy &amp; agreement</h2><button id="policy-close" class="icon-button" type="button" aria-label="Close privacy and agreement">×</button></div>
    <p id="policy-intro" class="dialog-description">Read how this offline reader uses your files, then choose whether to agree.</p>
    <div class="policy-publisher"><strong>Publisher: ${PUBLISHER}</strong><span>Local personal preview · Updated 26 October 2026</span></div>
    <div class="policy-documents" tabindex="0" aria-label="Policy documents">
      <details open><summary>Privacy notice</summary><div class="policy-copy">
        <h3>What stays on this device</h3><p>The reader stores copies of the books you import, their titles, author information and cover images, reading positions, appearance and voice settings, imported voice packs, and your agreement and file-explanation choices. It uses these to display and read your books and remember your place.</p>
        <h3>Processing and sharing</h3><p>Document parsing and Piper, Kokoro and Kitten Nano speech generation run locally. This version has no account, advertising, analytics, remote speech service, automatic crash reporting or automatic book upload. The Android package does not request Internet access. The laptop reader serves its bundled files on this computer only.</p>
        <h3>Audio and access</h3><p>Speech is generated from document text. The app does not record your microphone. Short playback buffers are held temporarily in memory; this version does not save an audio library. File access is limited to files you choose or drop into the reader. For offline use, choose files already downloaded onto your device. If you select a cloud file provider in the system picker, that provider may use its own network connection and policies.</p>
        <h3>Background reading data</h3><p>Android bundled Piper stores a temporary text-and-position plan in private app files while reading, and saves passage positions in private app settings. The plan is read in small pieces and scheduled for deletion when narration stops or finishes. If the process closes unexpectedly, an interrupted plan may remain until a later app launch cleans up plans older than a day, or app data is cleared. The native Piper voice is copied into private app files at first use. These files stay on your device. Removing a book removes its library copy; a small saved native position can remain in settings until app data is cleared. Paused background narration retains its temporary plan and short audio buffers until stopped or the process closes. Large-book preparation uses extra local storage and can take time; keep the app open until narration starts. The app keeps the display awake during this initial preparation, then lets it turn off once native playback starts.</p>
        <h3>Storage and retention</h3><p>Imported books, voices and settings remain in this app or browser storage until you remove them, clear its data or uninstall. Android automatic app backup is disabled. The app can display storage totals and estimate its own private data size; those figures are not sent elsewhere. Browser or operating-system storage cleanup can remove local data, so keep independent backups.</p>
        <h3>Your controls and backups</h3><p>Remove a book or imported voice in Local storage or the library. Removing an imported copy does not delete your original file. Backups contain books and reading positions, can include book metadata and covers, and are not encrypted by the reader. They exclude voice model packs and agreement records. Anyone you give a backup to may be able to read its contents. You choose where to save and whether to share it; exported copies stay outside the app until you delete them yourself.</p>
        <h3>Questions and changes</h3><p>For help, contact ${PUBLISHER} through the channel used to share this local preview. No support email has been configured. Updated policies are delivered with app updates. A new agreement version asks for a fresh choice; installing an update never counts as acceptance.</p>
      </div></details>
      <details><summary>Personal-use agreement</summary><div class="policy-copy">
        <h3>Using this preview</h3><p>This agreement covers the local Novaxis Reader preview provided by ${PUBLISHER} for personal reading and voice playback. This version has no subscription or in-app payment. Future online or subscription services would need their own terms and a new choice from you.</p>
        <h3>Your books and rights</h3><p>You keep the rights you already hold in your documents. Import only content you own or are authorized to use. This reader does not supply a licensed book catalogue, grant rights to copyrighted books, or remove DRM. Book authors and other rights holders retain their rights.</p>
        <h3>Voice and software licences</h3><p>Third-party software, model and voice licences remain applicable. This agreement does not grant extra rights to redistribute or commercially use those components. The bundled preview is intended for personal use; commercial redistribution needs a separate licence review.</p>
        <h3>Reading limits and your copies</h3><p>Text extraction, reading order, pronunciation and highlighting may contain errors. Image-only PDFs can be viewed but need an existing text layer for narration. Keep your original documents and independent backups; reading positions and app data can be lost through deletion, storage cleanup or uninstalling.</p>
        <h3>Your choice</h3><p>Accepting is optional. Importing books or voices, restoring a backup, and starting narration require acceptance of this version. Without accepting, you can still browse existing books, remove your imported copies and export your library. You may withdraw your agreement in this screen. Withdrawal pauses narration and stops future imports and playback until you agree again; it does not delete your library. This agreement does not take away rights that applicable law gives you.</p>
      </div></details>
      <details><summary>Permissions &amp; file access</summary><div class="policy-copy">
        <h3>You choose each file</h3><p>Add books, Import a Piper voice and Restore backup open a system file chooser after a short explanation. Selecting a file gives the app access to that selection. Cancelling leaves the library unchanged. The reader does not request access to every file, camera, microphone, contacts or location. Android bundled Piper narration uses a media playback notification with Play, Pause and Stop controls; it does not request general notification permission.</p>
        <h3>Saving and reading</h3><p>Back up library asks where to save on Android, or uses your browser's download behaviour on a laptop. Speech plays through the device's normal audio output and volume controls. On Android, the bundled Piper Lessac voice uses an on-device media playback service and a CPU wake lock while preparing or playing, so the display can turn off. The CPU wake lock is released when paused or stopped. Playback controls can appear on the lock screen; Android controls their visibility, and the notification contains the book title, never passage text. Other voices use the foreground reader and keep its display awake while preparing or playing. These behaviours use normal Android permissions, without a special permission prompt. You can stop playback from the reader or its media controls.</p>
        <h3>Explanations are not permissions</h3><p>Remembering a file explanation only saves your preference to skip that explanation next time. It never lets the app select new files for you. Reset explanations here to see them again. To remove access to an imported copy, delete it from the library or Local storage; clearing app data removes all local copies and settings. Keep a backup first if needed.</p>
      </div></details>
    </div>
    <p id="agreement-status" class="policy-status" role="status"></p>
    <div id="agreement-form" class="agreement-form">
      <label><input id="privacy-read" type="checkbox"><span>I have read the privacy notice.</span></label>
      <label><input id="terms-agree" type="checkbox"><span>I agree to the personal-use agreement.</span></label>
      <button id="accept-agreement" class="button button-primary" type="button" disabled>Accept and continue</button>
    </div>
    <div class="policy-actions"><button id="policy-later" class="button button-secondary" type="button">Not now</button><button id="withdraw-agreement" class="button button-quiet" type="button" hidden>Withdraw agreement</button><button id="reset-explanations" class="button button-quiet" type="button">Reset file explanations</button></div>
    <small class="policy-version">Policy version ${POLICY_VERSION}. Your choice is kept on this device.</small>
  </dialog>
  <dialog id="file-access-dialog" class="reader-dialog" aria-labelledby="file-access-heading" aria-describedby="file-access-description">
    <div class="dialog-heading"><h2 id="file-access-heading">Choose files</h2></div><p id="file-access-description" class="dialog-description"></p>
    <p class="access-note">The system chooser controls the selection. You can cancel without changing your library.</p>
    <label class="remember-explanation"><input id="remember-file-explanation" type="checkbox"><span>Don't show this explanation again on this device.</span></label>
    <div class="dialog-actions"><button id="file-access-cancel" class="button button-secondary" type="button">Cancel</button><button id="file-access-continue" class="button button-primary" type="button">Choose files</button></div>
  </dialog>`;
}

export function bindPrivacyControls(onWithdraw: () => Promise<void>): void {
  withdrawn = onWithdraw;
  document.querySelectorAll('[data-action="privacy"]').forEach(button => button.addEventListener('click', () => openPrivacy()));
  for (const id of ['privacy-read', 'terms-agree']) get<HTMLInputElement>(id).addEventListener('change', () => {
    get<HTMLButtonElement>('accept-agreement').disabled = !get<HTMLInputElement>('privacy-read').checked || !get<HTMLInputElement>('terms-agree').checked;
  });
  for (const id of ['policy-close', 'policy-later']) get(id).addEventListener('click', dismissPolicy);
  get<HTMLDialogElement>('privacy-dialog').addEventListener('cancel', () => { pendingUsage = undefined; rememberDismissal(); });
  get<HTMLDialogElement>('privacy-dialog').addEventListener('close', () => { pendingUsage = undefined; });
  get('accept-agreement').addEventListener('click', () => {
    if (!get<HTMLInputElement>('privacy-read').checked || !get<HTMLInputElement>('terms-agree').checked) return;
    consent = { version: POLICY_VERSION, acceptedAt: new Date().toISOString(), privacyAcknowledged: true, termsAccepted: true };
    sessionOnly = !store(CONSENT_KEY, JSON.stringify(consent));
    const action = pendingUsage;
    pendingUsage = undefined;
    get<HTMLDialogElement>('privacy-dialog').close();
    refreshPrivacyStatus();
    action?.();
  });
  get('withdraw-agreement').addEventListener('click', () => {
    consent = undefined;
    sessionOnly = !store(CONSENT_KEY, JSON.stringify({ version: POLICY_VERSION, withdrawnAt: new Date().toISOString() }));
    rememberDismissal();
    refreshPrivacyStatus();
    void withdrawn().catch(() => { get('agreement-status').textContent = 'Agreement withdrawn. Close and reopen the app if audio did not pause.'; });
  });
  get('reset-explanations').addEventListener('click', () => {
    const saved = store(ACCESS_KEY, '{}');
    remembered = {};
    get('agreement-status').textContent = 'File explanations reset' + (saved ? '.' : ' for this session; storage is unavailable.') + ' The system chooser still asks you to choose each file.';
  });
  get('file-access-cancel').addEventListener('click', () => get<HTMLDialogElement>('file-access-dialog').close());
  for (const event of ['close', 'cancel']) get('file-access-dialog').addEventListener(event, () => {
    if (event === 'close' && get<HTMLDialogElement>('file-access-dialog').open) return;
    pendingFile = undefined; pendingKind = undefined;
  });
  get('file-access-continue').addEventListener('click', () => {
    const action = pendingFile;
    if (get<HTMLInputElement>('remember-file-explanation').checked && pendingKind) {
      remembered[pendingKind] = POLICY_VERSION;
      store(ACCESS_KEY, JSON.stringify(remembered));
    }
    pendingFile = undefined;
    pendingKind = undefined;
    get<HTMLDialogElement>('file-access-dialog').close();
    // Keep this synchronous: browser file selection needs the user's click gesture.
    action?.();
  });
  window.addEventListener('storage', event => {
    if (event.key === ACCESS_KEY || event.key === null) remembered = readExplanations();
    if (event.key !== CONSENT_KEY && event.key !== null) return;
    consent = readConsent(); sessionOnly = false; refreshPrivacyStatus();
    if (!consent) void withdrawn().catch(() => {});
  });
  refreshPrivacyStatus();
}

export function openPrivacy(action?: () => void): void {
  pendingUsage = action;
  refreshPrivacyStatus();
  const dialog = get<HTMLDialogElement>('privacy-dialog');
  if (!dialog.open) dialog.showModal();
}

export function showPrivacyWelcome(): void {
  if (!consent && read(DISMISS_KEY) !== POLICY_VERSION && !document.querySelector('dialog[open]')) openPrivacy();
}

export function agreementRequired(action: () => void): boolean {
  if (consent) return false;
  openPrivacy(action);
  return true;
}

export function requestFileAccess(kind: FileAccess, action: () => void): void {
  if (kind !== 'backup' && agreementRequired(() => requestFileAccess(kind, action))) return;
  if (remembered[kind] === POLICY_VERSION) { action(); return; }
  const notices: Record<FileAccess, [string, string, string]> = {
    books: ['Add books from your device', 'Choose PDF, EPUB or TXT files. The reader stores a copy of each selected book in its local library. Your original files stay where they are. No access to all files is requested.', 'Choose books'],
    voices: ['Import a local Piper voice', 'Choose a matching .onnx model and .onnx.json configuration together. They are stored on this device for local narration. The bundled voices already work without importing anything.', 'Choose voice files'],
    restore: ['Restore a library backup', 'Choose a Novaxis Reader ZIP backup. Its books and saved positions are imported on this device. A backup can replace books and positions with matching IDs. No file is uploaded.', 'Choose backup'],
    backup: ['Save a copy of your library', 'The backup contains your books and reading positions and is not encrypted. It does not contain voice models or your agreement record. On Android you choose where to save; on a laptop your browser handles the download.', 'Save backup'],
  };
  const [heading, description, label] = notices[kind];
  get('file-access-heading').textContent = heading;
  get('file-access-description').textContent = description;
  get('file-access-continue').textContent = label;
  get<HTMLInputElement>('remember-file-explanation').checked = false;
  pendingFile = action;
  pendingKind = kind;
  get<HTMLDialogElement>('file-access-dialog').showModal();
}

function refreshPrivacyStatus(): void {
  get('agreement-form').hidden = !!consent;
  get('withdraw-agreement').hidden = !consent;
  get('policy-later').textContent = consent ? 'Done' : 'Not now';
  get<HTMLInputElement>('privacy-read').checked = false;
  get<HTMLInputElement>('terms-agree').checked = false;
  get<HTMLButtonElement>('accept-agreement').disabled = true;
  const status = consent ? 'Agreed on ' + new Date(consent.acceptedAt).toLocaleString() + (sessionOnly ? ' for this session; storage is unavailable.' : '. Saved on this device.') : 'Not accepted. Your existing books and backups remain yours.' + (sessionOnly ? ' Withdrawal is for this session only because storage is unavailable.' : '');
  get('agreement-status').textContent = status;
  const summary = document.querySelector('#privacy-summary');
  if (summary) summary.textContent = status;
}
function dismissPolicy(): void { pendingUsage = undefined; rememberDismissal(); get<HTMLDialogElement>('privacy-dialog').close(); }
function rememberDismissal(): void { store(DISMISS_KEY, POLICY_VERSION); }
function get<T extends HTMLElement = HTMLElement>(id: string): T { return document.getElementById(id) as T; }
function read(key: string): string | null { try { return localStorage.getItem(key); } catch { return null; } }
function store(key: string, value: string): boolean { try { localStorage.setItem(key, value); return true; } catch { return false; } }
function readConsent(): Consent | undefined {
  try {
    const value = JSON.parse(read(CONSENT_KEY) ?? 'null');
    if (value?.version === POLICY_VERSION && value.privacyAcknowledged === true && value.termsAccepted === true && typeof value.acceptedAt === 'string' && Number.isFinite(Date.parse(value.acceptedAt))) return value as Consent;
  } catch { /* Invalid or unavailable storage does not count as acceptance. */ }
  return undefined;
}
function readExplanations(): Partial<Record<FileAccess, string>> {
  try { const value = JSON.parse(read(ACCESS_KEY) ?? '{}'); return value && typeof value === 'object' && !Array.isArray(value) ? value : {}; } catch { return {}; }
}
let remembered = readExplanations();
