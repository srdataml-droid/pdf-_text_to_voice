import { requestFileAccess } from '../privacy/privacy-controls';

const PRESETS = [
  ['none', 'Plain'], ['dunes', 'Dunes'], ['forest', 'Forest mist'], ['lake', 'Moonlit lake'], ['linen', 'Linen'], ['custom', 'Your image'],
] as const;
type Choice = typeof PRESETS[number][0];
const CHOICE_KEY = 'novaxis-reader-wallpaper-v1';
type SavedImage = { id: 'custom'; name: string; image: Blob };

export function wallpaperMarkup(): string {
  return `<fieldset class="preference-group wallpaper-preferences"><legend>Reading background</legend><p class="quiet-note">A little atmosphere, all on your device. PDF artwork stays on solid paper.</p><div class="wallpaper-grid">${PRESETS.map(([id, label]) => `<button class="wallpaper-choice" type="button" data-wallpaper="${id}" aria-pressed="${id === 'none'}" ${id === 'custom' ? 'disabled' : ''}><span class="wallpaper-preview wallpaper-preview-${id}" aria-hidden="true"></span><span>${label}</span></button>`).join('')}</div><div class="wallpaper-actions"><button id="wallpaper-add" class="button button-secondary" type="button">Add your image</button><button id="wallpaper-remove" class="button button-quiet" type="button" hidden>Remove local image</button></div><p id="wallpaper-status" class="quiet-note" role="status">Choose a background, or keep plain paper.</p><p class="quiet-note">JPG, PNG or WebP · up to 12 MB. Only a smaller copy is saved here; it is not uploaded or included in book backups.</p><input id="wallpaper-picker" type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" hidden></fieldset>`;
}

function openImages(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('novaxis-reader-wallpapers', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('images', { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Could not open background storage.'));
    request.onblocked = () => reject(new Error('Close another reader tab to update background storage.'));
  });
}
async function readImage(): Promise<SavedImage | undefined> {
  const db = await openImages();
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction('images').objectStore('images').get('custom');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally { db.close(); }
}
async function storeImage(value?: SavedImage): Promise<void> {
  const db = await openImages();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('images', 'readwrite');
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () => reject(tx.error ?? new Error('Could not save this background.'));
      if (value) tx.objectStore('images').put(value);
      else tx.objectStore('images').delete('custom');
    });
  } finally { db.close(); }
}

/** One bounded local image; book and voice stores are never migrated or modified. */
export class ReaderWallpaper {
  private choice: Choice = 'none';
  private imageUrl?: string;
  private imageName = '';
  private busy = false;
  private changedDuringLoad = false;
  private imageRevision = 0;

  constructor(private root: HTMLElement, private error: (error: unknown) => void,
    private confirmRemoval: (remove: () => void) => void, private resized: () => void) {
    try {
      const choice = localStorage.getItem(CHOICE_KEY);
      if (PRESETS.some(([id]) => id === choice)) this.choice = choice as Choice;
    } catch { /* Session-only appearance remains usable. */ }
    for (const button of root.querySelectorAll<HTMLButtonElement>('[data-wallpaper]')) button.addEventListener('click', () => {
      this.changedDuringLoad = true; this.select(button.dataset.wallpaper as Choice);
    });
    root.querySelector('#wallpaper-add')!.addEventListener('click', () => requestFileAccess('wallpaper', () => {
      root.querySelector<HTMLInputElement>('#wallpaper-picker')!.click();
    }));
    root.querySelector('#wallpaper-picker')!.addEventListener('change', event => {
      const picker = event.currentTarget as HTMLInputElement, file = picker.files?.[0]; picker.value = '';
      if (file && !this.busy) void this.import(file).catch(error => this.error(error));
    });
    root.querySelector('#wallpaper-remove')!.addEventListener('click', () => this.confirmRemoval(() => {
      if (!this.busy) void this.remove().catch(error => this.error(error));
    }));
    this.paint();
  }
  async load(): Promise<void> {
    const selectedAtStart = this.choice;
    const revision = this.imageRevision;
    try {
      const saved = await readImage();
      if (revision !== this.imageRevision) return;
      if (saved?.image instanceof Blob && saved.image.size <= 2 * 1024 * 1024 && ['image/png', 'image/jpeg', 'image/webp'].includes(saved.image.type)) {
        this.setImage(saved);
      } else if (!this.changedDuringLoad && selectedAtStart === 'custom') this.choice = 'none';
      this.paint();
    } catch {
      if (revision !== this.imageRevision) return;
      if (this.choice === 'custom') this.choice = 'none';
      this.paint(); this.status('Background storage is unavailable. Built-in backgrounds still work.');
    }
  }
  private select(choice: Choice): void {
    if (!PRESETS.some(([id]) => id === choice) || (choice === 'custom' && !this.imageUrl) || this.busy) return;
    this.choice = choice; this.paint();
    try { localStorage.setItem(CHOICE_KEY, choice); }
    catch { this.status('Background selected for this session; this device could not save the choice.'); }
  }
  private paint(): void {
    const visibleChoice = this.choice === 'custom' && !this.imageUrl ? 'none' : this.choice;
    const changed = document.body.dataset.wallpaper !== visibleChoice;
    document.body.dataset.wallpaper = visibleChoice;
    const image = this.choice === 'custom' ? this.imageUrl : '/design/wallpapers/' + this.choice + '.svg';
    document.body.style.setProperty('--reader-wallpaper', visibleChoice === 'none' ? 'none' : 'url("' + image + '")');
    for (const button of this.root.querySelectorAll<HTMLButtonElement>('[data-wallpaper]')) {
      button.setAttribute('aria-pressed', String(button.dataset.wallpaper === this.choice));
      button.disabled = this.busy || (button.dataset.wallpaper === 'custom' && !this.imageUrl);
    }
    const preview = this.root.querySelector<HTMLElement>('.wallpaper-preview-custom')!;
    preview.style.backgroundImage = this.imageUrl ? 'url("' + this.imageUrl + '")' : '';
    this.root.querySelector<HTMLElement>('#wallpaper-remove')!.hidden = !this.imageUrl;
    this.root.querySelector<HTMLButtonElement>('#wallpaper-add')!.disabled = this.busy;
    this.root.querySelector<HTMLButtonElement>('#wallpaper-remove')!.disabled = this.busy;
    this.status(this.choice === 'custom' ? 'Using ' + this.imageName + ' · saved on this device.' :
      this.choice === 'none' ? 'Plain paper. Your saved image stays available until you remove it.' :
      PRESETS.find(([id]) => id === this.choice)![1] + ' · included offline.');
    if (changed) this.resized();
  }
  private setImage(saved: SavedImage): void {
    const previous = this.imageUrl;
    this.imageUrl = URL.createObjectURL(saved.image); this.imageName = saved.name;
    if (previous) URL.revokeObjectURL(previous);
  }
  private status(value: string): void { this.root.querySelector('#wallpaper-status')!.textContent = value; }
  private async import(file: File): Promise<void> {
    if (file.size > 12 * 1024 * 1024 || !/\.(?:jpe?g|png|webp)$/i.test(file.name) ||
      (file.type && !['image/png', 'image/jpeg', 'image/webp', 'application/octet-stream'].includes(file.type))) throw new Error('Choose a JPG, PNG or WebP image up to 12 MB.');
    this.imageRevision++;
    this.changedDuringLoad = true; this.busy = true; this.paint(); this.status('Preparing your image on this device…');
    let bitmap: ImageBitmap | undefined;
    try {
      bitmap = await createImageBitmap(file);
      if (bitmap.width * bitmap.height > 32_000_000) throw new Error('Choose an image smaller than 32 megapixels.');
      const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const context = canvas.getContext('2d'); if (!context) throw new Error('This device could not prepare the image.');
      context.fillStyle = '#f7f4ed'; context.fillRect(0, 0, canvas.width, canvas.height); context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      const image = await new Promise<Blob>((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Could not prepare the image.')), 'image/webp', .82));
      if (image.size > 2 * 1024 * 1024) throw new Error('This image needs more space than allowed. Choose a smaller image.');
      const saved: SavedImage = { id: 'custom', name: file.name, image };
      await storeImage(saved); this.setImage(saved); this.busy = false; this.select('custom');
    } finally { bitmap?.close(); this.busy = false; this.paint(); }
  }
  private async remove(): Promise<void> {
    this.imageRevision++;
    this.changedDuringLoad = true; this.busy = true;
    try {
      await storeImage();
      if (this.imageUrl) URL.revokeObjectURL(this.imageUrl);
      this.imageUrl = undefined; this.imageName = ''; this.busy = false;
      this.select(this.choice === 'custom' ? 'none' : this.choice);
    } finally { this.busy = false; this.paint(); }
  }
  dispose(): void { if (this.imageUrl) URL.revokeObjectURL(this.imageUrl); }
}
