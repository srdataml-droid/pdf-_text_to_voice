import * as pdfjs from 'pdfjs-dist';
import type { ReadableSegment } from '../domain/book';

export class PdfPageView {
  private document?: pdfjs.PDFDocumentProxy;
  private loading?: pdfjs.PDFDocumentLoadingTask;
  private renderTask?: pdfjs.RenderTask;
  private page = 0;
  private zoom: 'width' | 'page' | number = 'width';
  private rotation = 0;
  private version = 0;
  private work: Promise<void> = Promise.resolve();
  private position = -1;
  private following = false;
  private stage: HTMLDivElement;
  private label: HTMLInputElement;
  private total: HTMLSpanElement;
  private status: HTMLParagraphElement;
  private scroll: HTMLDivElement;

  constructor(private target: HTMLElement, private segments: ReadableSegment[],
    private read: (index: number) => void, private error: (error: unknown) => void,
    private pageChanged?: (page: number) => void) {
    target.innerHTML = '<nav class="pdf-toolbar" aria-label="PDF pages"><div class="pdf-page-controls"><button type="button" data-page="previous" aria-label="Previous PDF page">‹</button><label><span class="visually-hidden">Page</span><input class="pdf-page-number" type="number" min="1" aria-label="PDF page"></label><span class="pdf-total"></span><button type="button" data-page="next" aria-label="Next PDF page">›</button></div><div class="pdf-view-controls"><select class="pdf-zoom" aria-label="Page zoom"><option value="width">Fit width</option><option value="page">Fit page</option><option value="0.75">75%</option><option value="1">100%</option><option value="1.25">125%</option><option value="1.5">150%</option><option value="2">200%</option><option value="3">300%</option></select><button type="button" data-page="rotate" aria-label="Rotate page clockwise" title="Rotate page">↻</button></div></nav><div class="pdf-scroll" tabindex="0" role="region" aria-label="PDF page — scroll to read"><div class="pdf-stage"></div></div><p class="pdf-page-note"></p>';
    this.stage = target.querySelector('.pdf-stage')!;
    this.label = target.querySelector('.pdf-page-number')!;
    this.total = target.querySelector('.pdf-total')!;
    this.status = target.querySelector('.pdf-page-note')!;
    this.scroll = target.querySelector('.pdf-scroll')!;
    target.querySelector('[data-page="previous"]')!.addEventListener('click', () => this.navigate(this.page - 1));
    target.querySelector('[data-page="next"]')!.addEventListener('click', () => this.navigate(this.page + 1));
    this.label.addEventListener('change', () => this.navigate(Number(this.label.value)));
    target.querySelector('.pdf-zoom')!.addEventListener('change', event => {
      const value = (event.target as HTMLSelectElement).value;
      this.zoom = value === 'width' || value === 'page' ? value : Number(value);
      this.navigate(this.page, true);
    });
    target.querySelector('[data-page="rotate"]')!.addEventListener('click', () => {
      this.rotation = (this.rotation + 90) % 360;
      this.navigate(this.page, true);
    });
  }

  async open(file: Blob, position: number, hasProgress: boolean, savedPage?: number): Promise<void> {
    this.loading = pdfjs.getDocument({ data: await file.arrayBuffer(), useSystemFonts: false,
      standardFontDataUrl: '/pdf-assets/standard_fonts/', cMapUrl: '/pdf-assets/cmaps/',
      cMapPacked: true, wasmUrl: '/pdf-assets/wasm/' });
    this.document = await this.loading.promise;
    this.total.textContent = 'of ' + this.document.numPages;
    this.label.max = String(this.document.numPages);
    this.position = position;
    await this.showPage(savedPage ?? (hasProgress ? this.segments[position]?.pdf?.page ?? 1 : 1));
  }

  follow(position: number, speaking: boolean, autoFollow = true): void {
    this.position = position;
    this.following = speaking && autoFollow;
    const page = this.segments[position]?.pdf?.page;
    if (speaking && autoFollow && page && page !== this.page) this.navigate(page);
    else this.highlight(speaking && autoFollow);
  }

  refresh(): void { if (this.document && this.target.clientWidth) this.navigate(this.page, true); }

  private navigate(page: number, force = false): void {
    void this.showPage(page, force).catch(this.error);
  }

  private async showPage(page: number, force = false): Promise<void> {
    if (!this.document) return;
    page = Math.max(1, Math.min(Math.round(page) || 1, this.document.numPages));
    if (page === this.page && !force) { this.highlight(false); return; }
    const version = ++this.version;
    const samePage = page === this.page;
    const anchorX = this.scroll.scrollLeft / Math.max(1, this.scroll.scrollWidth);
    const anchorY = this.scroll.scrollTop / Math.max(1, this.scroll.scrollHeight);
    this.renderTask?.cancel();
    this.page = page;
    this.label.value = String(page);
    this.target.querySelector<HTMLButtonElement>('[data-page="previous"]')!.disabled = page === 1;
    this.target.querySelector<HTMLButtonElement>('[data-page="next"]')!.disabled = page === this.document.numPages;
    this.status.textContent = 'Opening page ' + page + '…';
    this.work = this.work.catch(() => {}).then(async () => {
      if (version !== this.version || !this.document) return;
      const pdfPage = await this.document.getPage(page);
      if (version !== this.version) return;
      const rotation = (pdfPage.rotate + this.rotation) % 360;
      const original = pdfPage.getViewport({ scale: 1, rotation });
      const playerHeight = document.querySelector('#player')?.getBoundingClientRect().height ?? 0;
      const navigationHeight = document.querySelector('.mobile-navigation')?.getBoundingClientRect().height ?? 0;
      this.scroll.style.height = Math.max(200, window.innerHeight - this.scroll.getBoundingClientRect().top - playerHeight - navigationHeight - 24) + 'px';
      const padding = getComputedStyle(this.scroll);
      const available = Math.max(220, this.scroll.clientWidth - parseFloat(padding.paddingLeft) - parseFloat(padding.paddingRight));
      const height = Math.max(200, this.scroll.clientHeight - parseFloat(padding.paddingTop) - parseFloat(padding.paddingBottom));
      const scale = this.zoom === 'width' ? available / original.width : this.zoom === 'page'
        ? Math.min(available / original.width, height / original.height) : this.zoom * 96 / 72;
      const viewport = pdfPage.getViewport({ scale, rotation });
      const canvas = document.createElement('canvas');
      canvas.setAttribute('role', 'img');
      canvas.setAttribute('aria-label', 'Original PDF page ' + page);
      // Match dense phone screens without allocating unbounded canvases at high zoom.
      const density = Math.min(window.devicePixelRatio || 1, 3,
        Math.sqrt(6_000_000 / (viewport.width * viewport.height)),
        4096 / Math.max(viewport.width, viewport.height));
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
      this.scroll.scrollLeft = samePage ? anchorX * this.scroll.scrollWidth : 0;
      this.scroll.scrollTop = samePage ? anchorY * this.scroll.scrollHeight : 0;
      let count = 0;
      const lines = new Map<string, HTMLButtonElement>();
      this.segments.forEach((segment, index) => {
        if (segment.pdf?.page !== page) return;
        for (const region of segment.pdf.rects ?? [segment.pdf.rect]) {
        const key = region.join(',');
        const existing = lines.get(key);
        if (existing) {
          existing.dataset.indices += ' ' + index;
          existing.setAttribute('aria-label', existing.getAttribute('aria-label') + ' ' + segment.text);
          continue;
        }
        const rect = [
          ...viewport.convertToViewportPoint(region[0], region[1]),
          ...viewport.convertToViewportPoint(region[2], region[3]),
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
        }
      });
      this.status.textContent = count ? 'Original page · tap a line to listen' : 'Original page · no selectable text on this page';
      this.highlight(this.following);
      this.pageChanged?.(page);
      pdfPage.cleanup();
    });
    await this.work;
  }

  private highlight(scroll: boolean): void {
    this.stage.querySelectorAll('.pdf-line.current').forEach(line => line.classList.remove('current'));
    const current = Array.from(this.stage.querySelectorAll<HTMLElement>('.pdf-line'))
      .filter(line => line.dataset.indices?.split(' ').includes(String(this.position)));
    current.forEach(line => line.classList.add('current'));
    if (scroll && current[0]) {
      const rect = current[0].getBoundingClientRect();
      const area = this.scroll.getBoundingClientRect();
      const outsideY = rect.top < area.top + 20 || rect.bottom > area.bottom - 20;
      const outsideX = rect.left < area.left + 12 || rect.right > area.right - 12;
      if (outsideY || outsideX) this.scroll.scrollTo({
        top: outsideY ? this.scroll.scrollTop + rect.top - area.top - this.scroll.clientHeight * .35 : this.scroll.scrollTop,
        left: outsideX ? this.scroll.scrollLeft + rect.left - area.left - 20 : this.scroll.scrollLeft,
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      });
    }
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
