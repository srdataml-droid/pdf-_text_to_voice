import ePub from 'epubjs';
import type { BookRecord, ParsedBook, ReadableSegment } from '../domain/book';
import { sentenceSegments } from './segments';

type EpubSection = {
  href: string;
  load: (request: (url: string, type?: string) => Promise<unknown>) => Promise<Element>;
};

type EpubBook = {
  opened: Promise<unknown>;
  spine: { spineItems: EpubSection[] };
  packaging?: { metadata?: { title?: string; creator?: string }; coverPath?: string; navPath?: string; ncxPath?: string };
  archive?: { zip: { files: Record<string, { _data?: { uncompressedSize?: number } }> }; getBlob: (path: string) => Promise<Blob> | undefined };
  resolve: (path: string, absolute?: boolean) => string;
  load: (url: string, type?: string) => Promise<unknown>;
  destroy: () => void;
};

const BLOCKS = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,figcaption,dt,dd';
const LEGACY_BLOCKS = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre';

export async function parseEpub(book: BookRecord): Promise<ParsedBook> {
  // Text extraction never mounts EPUB HTML or loads its styles/media. Avoid
  // expanding every embedded image/font into a blob just to read a chapter.
  const epubBook = ePub(await book.file.arrayBuffer(), { replacements: 'none' }) as unknown as EpubBook;
  try {
    await epubBook.opened;
    const segments: ReadableSegment[] = [];
    const chapters: { title: string; index: number }[] = [];
    const legacyIndexMap: number[] | undefined = book.segmentCount === undefined ? [] : undefined;
    const navigation = await localNavigation(epubBook);
    const localLoad = (path: string) => {
      if (remotePath(path)) return Promise.reject(new Error('This EPUB chapter is not stored in the book.'));
      return epubBook.load(path);
    };

    for (const [chapterIndex, section] of epubBook.spine.spineItems.entries()) {
      if (remotePath(section.href)) continue;
      const document = await section.load(localLoad) as Element;
      document.querySelectorAll('script,style,noscript,svg,iframe,object,embed').forEach(node => node.remove());
      let legacy: ReadableSegment[] | undefined;
      if (legacyIndexMap) {
        const oldBlocks = [...document.querySelectorAll(LEGACY_BLOCKS)]
          .filter(node => !node.parentElement?.closest(LEGACY_BLOCKS));
        const text = oldBlocks.length ? oldBlocks.map(node => node.textContent ?? '').join('\n\n') :
          document.querySelector('body')?.textContent ?? document.textContent ?? '';
        legacy = sentenceSegments(text, 'epub-' + chapterIndex, 'Chapter ' + (chapterIndex + 1));
      }
      document.querySelectorAll('script,style,noscript,svg,iframe,object,embed,img,picture,audio,video,form,[hidden],[aria-hidden="true"]').forEach((node) => node.remove());
      const blocks = [...document.querySelectorAll(BLOCKS)].filter((node) => !node.parentElement?.closest(BLOCKS));
      const sectionPath = pathKey(epubBook.resolve(section.href));
      const chapterTitle = navigation.find(item => pathKey(item.path) === sectionPath)?.title ||
        cleanText(document.querySelector('h1,h2')?.textContent ?? '') || undefined;
      const locationLabel = chapterTitle || 'Chapter ' + (chapterIndex + 1);
      const start = segments.length;
      let passageIndex = 0;
      const addBlock = (text: string, heading: boolean, blockIndex: number) => {
        for (const segment of sentenceSegments(cleanText(text), 'epub-' + chapterIndex, locationLabel)) {
          segments.push({ ...segment, id: 'epub-' + chapterIndex + '-' + passageIndex++,
            chapterTitle, kind: heading ? 'heading' : 'paragraph', blockId: 'epub-' + chapterIndex + '-block-' + blockIndex });
        }
      };
      if (blocks.length) blocks.forEach((node, blockIndex) => addBlock(node.textContent ?? '', /^h[1-6]$/i.test(node.tagName), blockIndex));
      else addBlock(document.querySelector('body')?.textContent ?? document.textContent ?? '', false, 0);
      if (chapterTitle && segments.length > start) chapters.push({ title: chapterTitle, index: start });
      if (legacy && legacyIndexMap) legacyIndexMap.push(...mapLegacyChapter(legacy, segments.slice(start), start));
    }

    const title = epubBook.packaging?.metadata?.title?.trim() || book.title;
    const author = epubBook.packaging?.metadata?.creator?.trim() || undefined;
    const cover = await localCover(epubBook);
    if (!segments.length) throw new Error('No readable chapter text was found in this EPUB.');
    return { title, format: 'epub', segments, author, cover, chapters, legacyIndexMap };
  } finally { epubBook.destroy(); }
}

function mapLegacyChapter(previous: ReadableSegment[], current: ReadableSegment[], start: number): number[] {
  if (!current.length) return previous.map(() => Math.max(0, start - 1));
  const texts = current.map(segment => comparisonText(segment.text));
  const positions = new Map<string, number[]>();
  texts.forEach((text, index) => { const indices = positions.get(text) || []; indices.push(index); positions.set(text, indices); });
  let last = 0;
  return previous.map((segment, index) => {
    const target = index / Math.max(1, previous.length - 1) * (current.length - 1);
    const text = comparisonText(segment.text);
    const exact = positions.get(text);
    if (exact?.length) last = Math.max(last, nearestIndex(exact, target));
    else {
      // Bound fuzzy matching to nearby positions instead of comparing every
      // sentence against a whole chapter. Exact matches use the index above.
      const candidates = new Set<number>();
      for (const center of [Math.round(target), last]) for (let offset = -12; offset <= 12; offset++) {
        const candidate = center + offset;
        if (candidate >= last && candidate < current.length) candidates.add(candidate);
      }
      let best = Math.max(last, Math.round(target));
      let score = -Infinity;
      for (const candidate of candidates) {
        const value = texts[candidate];
        const match = text.length >= 3 && value.length >= 3 ?
          (value.includes(text) ? 3 : text.startsWith(value) ? 2.5 : text.includes(value) ? 2 : 0) : 0;
        const candidateScore = match - Math.abs(candidate - target) / Math.max(1, current.length) * .2;
        if (candidateScore > score) { score = candidateScore; best = candidate; }
      }
      last = best;
    }
    return start + last;
  });
}

function comparisonText(text: string): string {
  return text.normalize('NFKC').replace(/\u00ad/g, '').replace(/[-\u2010\u2011]\s*(?=\p{L})/gu, '')
    .replace(/\s+/g, '').toLocaleLowerCase();
}

function nearestIndex(indices: number[], target: number): number {
  let low = 0, high = indices.length;
  while (low < high) { const middle = (low + high) >>> 1; if (indices[middle] < target) low = middle + 1; else high = middle; }
  const before = indices[Math.max(0, low - 1)];
  const after = indices[Math.min(indices.length - 1, low)];
  return Math.abs(before - target) <= Math.abs(after - target) ? before : after;
}

function cleanText(text: string): string {
  return text.replace(/([\p{L}])-\s*\n\s*(?=\p{Ll})/gu, '$1')
    .replace(/\u00ad/g, '').replace(/\s+/g, ' ').trim();
}

function remotePath(path: string): boolean {
  return /^\s*(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(path);
}

function pathKey(path: string): string {
  const value = path.split('#')[0].replace(/^\//, '');
  try { return decodeURIComponent(value); } catch { return value; }
}

async function localNavigation(book: EpubBook): Promise<{ path: string; title: string }[]> {
  const path = book.packaging?.navPath || book.packaging?.ncxPath;
  if (!path || remotePath(path)) return [];
  try {
    const document = await book.load(book.resolve(path)) as Document;
    const navigation: { path: string; title: string }[] = [];
    const base = new URL(book.resolve(path), 'https://epub.invalid');
    const add = (href: string | null, text: string) => {
      if (!href || remotePath(href)) return;
      const title = cleanText(text);
      if (title) navigation.push({ path: new URL(href, base).pathname, title });
    };
    if (book.packaging?.navPath) {
      const navs = [...document.querySelectorAll('nav')];
      const toc = navs.find(node => /\btoc\b/.test(node.getAttribute('epub:type') || node.getAttribute('type') || '')) || navs[0];
      toc?.querySelectorAll('a[href]').forEach(node => add(node.getAttribute('href'), node.textContent ?? ''));
    } else {
      document.querySelectorAll('navPoint').forEach(node => add(node.querySelector('content')?.getAttribute('src') ?? null,
        node.querySelector('navLabel')?.textContent ?? ''));
    }
    return navigation;
  } catch { return []; }
}

async function localCover(book: EpubBook): Promise<Blob | undefined> {
  const coverPath = book.packaging?.coverPath;
  if (!coverPath || remotePath(coverPath) || !book.archive) return undefined;
  try {
    const path = book.resolve(coverPath);
    const entry = book.archive.zip.files[pathKey(path)];
    // Check the ZIP entry before decompression as well as the resulting blob.
    const size = entry?._data?.uncompressedSize;
    if (size === undefined || size <= 0 || size > 1024 * 1024) return undefined;
    const cover = await book.archive.getBlob(path);
    if (cover && cover.size <= 1024 * 1024 && /^image\/(?:png|jpeg|webp|gif)$/i.test(cover.type)) return cover;
  } catch { /* Missing or unsupported covers never prevent reading. */ }
  return undefined;
}
