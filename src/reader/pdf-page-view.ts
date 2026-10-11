import * as pdfjs from 'pdfjs-dist';
import type { ReadableSegment } from '../domain/book';

export class PdfPageView {
  private document?: pdfjs.PDFDocumentProxy;
  private loading?: pdfjs.PDFDocumentLoadingTask;
  private renderTask?: pdfjs.RenderTask;
  private page = 0;
  private zoom = 1;
  private version = 0;
  private work: Promise<void> = Promise.resolve();
  private position = -1;
  private stage: HTMLDivElement;
  private label: HTMLInputElement;
  private total: HTMLSpanElement;
  private status: HTMLParagraphElement;

  constructor(private target: HTMLElement, private segments: ReadableSegment[],
    private read: (index: number) => void, private error: (error: unknown) => void) {
    target.innerHTML = '<nav class="pdf-toolbar" aria-label="PDF pages"><button type="button" data-page="previous" aria-label="Previous PDF page">‹</button><label>Page <input class="pdf-page-number" type="number" min="1" aria-label="PDF page"></label><span class="pdf-total"></span><button type="button" data-page="next" aria-label="Next PDF page">›</button><select class="pdf-zoom" aria-label="Page zoom"><option value="1">Fit page width</option><option value="1.25">125%</option><option value="1.5">150%</option></select></nav><div class="pdf-scroll"><div class="pdf-stage"></div></div><p class="pdf-page-note"></p>';
    this.stage = target.querySelector('.pdf-stage')!;
    this.label = target.querySelector('.pdf-page-number')!;
    this.total = target.querySelector('.pdf-total')!;
    this.status = target.querySelector('.pdf-page-note')!;
    target.querySelector('[data-page="previous"]')!.addEventListener('click', () => this.navigate(this.page - 1));
    target.querySelector('[data-page="next"]')!.addEventListener('click', () => this.navigate(this.page + 1));
    this.label.addEventListener('change', () => this.navigate(Number(this.label.value)));
    target.querySelector('.pdf-zoom')!.addEventListener('change', event => {
      this.zoom = Number((event.target as HTMLSelectElement).value);
      this.navigate(this.page, true);
    });
  }

  async open(file: Blob, position: number, hasProgress: boolean): Promise<void> {
    this.loading = pdfjs.getDocument({ data: await file.arrayBuffer(), useSystemFonts: false,
      standardFontDataUrl: '/pdf-assets/standard_fonts/', cMapUrl: '/pdf-assets/cmaps/',
      cMapPacked: true, wasmUrl: '/pdf-assets/wasm/' });
    this.document = await this.loading.promise;
    this.total.textContent = 'of ' + this.document.numPages;
    this.label.max = String(this.document.numPages);
    this.position = position;
    await this.showPage(hasProgress ? this.segments[position]?.pdf?.page ?? 1 : 1);
  }

  follow(position: number, speaking: boolean): void {
    this.position = position;
    const page = this.segments[position]?.pdf?.page;
    if (speaking && page && page !== this.page) this.navigate(page);
    else this.highlight(speaking);
  }

  private navigate(page: number, force = false): void {
    void this.showPage(page, force).catch(this.error);
  }

  private async showPage(page: number, force = false): Promise<void> {
    if (!this.document) return;
    page = Math.max(1, Math.min(Math.round(page) || 1, this.document.numPages));
    if (page === this.page && !force) { this.highlight(false); return; }
    const version = ++this.version;
    this.renderTask?.cancel();
    this.page = page;
    this.label.value = String(page);
    this.status.textContent = 'Opening page ' + page + '…';
    this.work = this.work.catch(() => {}).then(async () => {
      if (version !== this.version || !this.document) return;
      const pdfPage = await this.document.getPage(page);
      if (version !== this.version) return;
      const original = pdfPage.getViewport({ scale: 1 });
      const available = Math.max(260, this.target.clientWidth - 4);
      const viewport = pdfPage.getViewport({ scale: available / original.width * this.zoom });
      const canvas = document.createElement('canvas');
      canvas.setAttribute('role', 'img');
      canvas.setAttribute('aria-label', 'Original PDF page ' + page);
      const density = Math.min(window.devicePixelRatio || 1, 1.5);
      canvas.width = Math.ceil(viewport.width * density);
      canvas.height = Math.ceil(viewport.height * density);
      canvas.style.width = viewport.width + 'px';
      canvas.style.height = viewport.height + 'px';
      this.stage.style.width = viewport.width + 'px';
      this.stage.style.height = viewport.height + 'px';
      this.stage.replaceChildren(canvas);
      this.renderTask = pdfPage.render({ canvas, viewport, transform: [density, 0, 0, density, 0, 0] });
      try { await this.renderTask.promise; }
      catch (error) { if ((error as Error).name === 'RenderingCancelledException') return; throw error; }
      if (version !== this.version) return;
      let count = 0;
      const lines = new Map<string, HTMLButtonElement>();
      this.segments.forEach((segment, index) => {
        if (segment.pdf?.page !== page) return;
        const key = segment.pdf.rect.join(',');
        const existing = lines.get(key);
        if (existing) {
          existing.dataset.indices += ' ' + index;
          existing.setAttribute('aria-label', existing.getAttribute('aria-label') + ' ' + segment.text);
          return;
        }
        const rect = [
          ...viewport.convertToViewportPoint(segment.pdf.rect[0], segment.pdf.rect[1]),
          ...viewport.convertToViewportPoint(segment.pdf.rect[2], segment.pdf.rect[3]),
        ];
        const line = document.createElement('button');
        line.type = 'button'; line.className = 'pdf-line'; line.dataset.index = String(index);
        line.dataset.indices = String(index);
        line.setAttribute('aria-label', 'Read: ' + segment.text);
        line.style.left = Math.min(rect[0], rect[2]) + 'px';
        line.style.top = Math.min(rect[1], rect[3]) + 'px';
        line.style.width = Math.max(4, Math.abs(rect[2] - rect[0])) + 'px';
        line.style.height = Math.max(6, Math.abs(rect[3] - rect[1])) + 'px';
        line.addEventListener('click', () => this.read(index));
        this.stage.append(line); lines.set(key, line); count++;
      });
      this.status.textContent = count ? 'Original page · tap a line to listen' : 'Original page · no selectable text on this page';
      this.highlight(false);
      pdfPage.cleanup();
    });
    await this.work;
  }

  private highlight(scroll: boolean): void {
    this.stage.querySelector('.pdf-line.current')?.classList.remove('current');
    const current = Array.from(this.stage.querySelectorAll<HTMLElement>('.pdf-line'))
      .find(line => line.dataset.indices?.split(' ').includes(String(this.position)));
    current?.classList.add('current');
    if (scroll && current) current.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  async destroy(): Promise<void> {
    this.version++;
    this.renderTask?.cancel();
    await this.work.catch(() => {});
    await this.loading?.destroy();
    this.document = undefined;
    this.target.replaceChildren();
  }
}
