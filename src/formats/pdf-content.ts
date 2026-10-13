import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { BookRecord, ParsedBook, ReadableSegment } from '../domain/book';
import { sentenceSegments } from './segments';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

type PositionedItem = { str: string; x: number; y: number; width: number; height: number };
type Rect = [number, number, number, number];
type PdfLine = { text: string; rect: Rect; height: number; lineNo: number };
type PdfPage = { pageNo: number; lines: PdfLine[]; view: number[] };

export async function parsePdf(book: BookRecord): Promise<ParsedBook> {
  const loadingTask = pdfjs.getDocument({
    data: await book.file.arrayBuffer(),
    useSystemFonts: false,
    standardFontDataUrl: '/pdf-assets/standard_fonts/',
    cMapUrl: '/pdf-assets/cmaps/', cMapPacked: true,
    wasmUrl: '/pdf-assets/wasm/',
  });
  try {
    const document = await loadingTask.promise;
    const segments: ReadableSegment[] = [];
    const pages: PdfPage[] = [];
    const legacySegments: ReadableSegment[] | undefined = book.segmentCount === undefined ? [] : undefined;
    let title = book.title;
    let author: string | undefined;
    try {
      const metadata = await document.getMetadata();
      const info = metadata.info as { Title?: unknown; Author?: unknown };
      title = metadataText(info?.Title) || metadataText(metadata.metadata?.get('dc:title')) || book.title;
      author = metadataText(info?.Author) || metadataText(metadata.metadata?.get('dc:creator'));
    } catch { /* A damaged metadata dictionary need not prevent reading. */ }

    for (let pageNo = 1; pageNo <= document.numPages; pageNo++) {
      const page = await document.getPage(pageNo);
      try {
        const content = await page.getTextContent();
        const items = content.items
          .filter((item): item is typeof item & { str: string; transform: number[] } => 'str' in item)
          .map((item) => ({
            str: item.str,
            x: item.transform[4],
            y: item.transform[5],
            width: (item as { width: number }).width,
            height: (item as { height: number }).height || Math.hypot(item.transform[2], item.transform[3]),
          }))
          .filter((item) => item.str.trim());
        // Old progress refers to the previous parser's line-sized passages.
        // Reconstruct that ordering once for books imported before metadata.
        if (legacySegments) legacyLines(items).forEach((line, lineNo) => {
          legacySegments.push(...sentenceSegments(line.text, 'pdf-' + pageNo + '-' + lineNo, 'Page ' + pageNo)
            .map(segment => ({ ...segment, pdf: { page: pageNo, rect: line.rect } })));
        });
        pages.push({ pageNo, lines: columnOrder(groupLines(items), page.view), view: page.view });
      } finally { page.cleanup(); }
    }

    const repeatedMargins = recurringMargins(pages);
    for (const page of pages) {
      const bodyHeight = typicalHeight(page.lines);
      const lines = page.lines.filter(line => !skipMargin(line, page, repeatedMargins, bodyHeight));
      for (const paragraph of groupParagraphs(lines)) {
        const joined = joinParagraph(paragraph);
        let cursor = 0;
        for (const segment of sentenceSegments(joined.text, 'pdf-' + page.pageNo + '-' + paragraph[0].lineNo,
          'Page ' + page.pageNo)) {
          const start = joined.text.indexOf(segment.text, cursor);
          const end = (start < 0 ? cursor : start) + segment.text.length;
          const rects = joined.ranges.filter(range => range.end > (start < 0 ? cursor : start) && range.start < end)
            .map(range => range.rect);
          cursor = end;
          segments.push({ ...segment, kind: headingLine(paragraph[0], bodyHeight) ? 'heading' : 'paragraph',
            blockId: 'pdf-' + page.pageNo + '-line-' + paragraph[0].lineNo,
            pdf: { page: page.pageNo, rect: rects[0] || paragraph[0].rect, rects } });
        }
      }
    }
    // Image-only PDFs still have a useful original-page view; speech stays off.
    return { title, author, format: 'pdf', segments, pageCount: document.numPages,
      legacyIndexMap: legacySegments ? mapLegacyIndices(legacySegments, segments) : undefined };
  } finally { await loadingTask.destroy(); }
}

// Keep this grouping equivalent to the pre-redesign parser. In particular, it
// joins every run on one baseline with spaces and does not split column gutters.
function legacyLines(items: PositionedItem[]): { text: string; rect: Rect }[] {
  const ordered = [...items].sort((a, b) => b.y - a.y || a.x - b.x);
  const lines: { y: number; values: PositionedItem[] }[] = [];
  for (const item of ordered) {
    let line = lines.find(candidate => Math.abs(candidate.y - item.y) < 2.2);
    if (!line) { line = { y: item.y, values: [] }; lines.push(line); }
    line.values.push(item);
  }
  return lines.sort((a, b) => b.y - a.y).map(line => ({
    text: line.values.sort((a, b) => a.x - b.x).map(item => item.str).join(' ').replace(/\s+/g, ' ').trim(),
    rect: [Math.min(...line.values.map(item => item.x)),
      Math.min(...line.values.map(item => item.y - item.height * .2)),
      Math.max(...line.values.map(item => item.x + item.width)),
      Math.max(...line.values.map(item => item.y + item.height))] as Rect,
  })).filter(line => Boolean(line.text));
}

function mapLegacyIndices(legacy: ReadableSegment[], current: ReadableSegment[]): number[] {
  if (!current.length) return [];
  type Candidate = { segment: ReadableSegment; index: number; text: string };
  const pageCandidates = new Map<number, Candidate[]>();
  current.forEach((segment, index) => {
    if (!segment.pdf) return;
    const candidates = pageCandidates.get(segment.pdf.page) || [];
    candidates.push({ segment, index, text: comparisonText(segment.text) });
    pageCandidates.set(segment.pdf.page, candidates);
  });
  const availablePages = [...pageCandidates.keys()].sort((a, b) => a - b);
  return legacy.map(previous => {
    if (!previous.pdf) return 0;
    const page = previous.pdf.page;
    let candidates = pageCandidates.get(page);
    if (!candidates) {
      const nearest = availablePages.reduce((best, candidate) => Math.abs(candidate - page) < Math.abs(best - page) ? candidate : best,
        availablePages[0]);
      return pageCandidates.get(nearest)?.[0]?.index ?? 0;
    }
    const text = comparisonText(previous.text);
    let best = candidates[0].index;
    let bestScore = -Infinity;
    for (const candidate of candidates) {
      const rects = candidate.segment.pdf!.rects || [candidate.segment.pdf!.rect];
      const overlap = Math.max(...rects.map(rect => rectangleOverlap(previous.pdf!.rect, rect)));
      const distance = Math.min(...rects.map(rect => Math.abs(previous.pdf!.rect[1] - rect[1])));
      // The old line can be a fragment of a new sentence. A text match breaks
      // ties when several new sentences share the same display rectangle.
      const textMatch = text.length >= 3 && (candidate.text.includes(text) || text.includes(candidate.text));
      const score = overlap > 0 ? 10 + overlap + (textMatch ? 2 : 0) : -distance;
      if (score > bestScore) { bestScore = score; best = candidate.index; }
    }
    return best;
  });
}

function comparisonText(text: string): string {
  return text.replace(/\u00ad/g, '').replace(/-\s*$/, '').replace(/\s+/g, '').toLocaleLowerCase();
}

function rectangleOverlap(a: Rect, b: Rect): number {
  const width = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
  const height = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
  const smaller = Math.min(Math.max(1, (a[2] - a[0]) * (a[3] - a[1])), Math.max(1, (b[2] - b[0]) * (b[3] - b[1])));
  return width * height / smaller;
}

function groupLines(items: PositionedItem[]): PdfLine[] {
  const ordered = [...items].sort((a, b) => b.y - a.y || a.x - b.x);
  const lines: { y: number; values: PositionedItem[] }[] = [];
  for (const item of ordered) {
    let line = lines.find((candidate) => Math.abs(candidate.y - item.y) < 2.2);
    if (!line) {
      line = { y: item.y, values: [] };
      lines.push(line);
    }
    line.values.push(item);
  }

  const result: PdfLine[] = [];
  for (const row of lines.sort((a, b) => b.y - a.y)) {
    const groups: PositionedItem[][] = [];
    for (const item of row.values.sort((a, b) => a.x - b.x)) {
      const group = groups.at(-1);
      const previous = group?.at(-1);
      // A large horizontal gutter is a separate block/column, not a word gap.
      if (!group || (previous && item.x - previous.x - previous.width > Math.max(item.height, previous.height) * 3.5)) {
        groups.push([item]);
      } else group.push(item);
    }
    for (const values of groups) {
      const text = lineText(values);
      if (!text) continue;
      result.push({ text, lineNo: result.length, height: median(values.map(item => item.height)), rect: [
        Math.min(...values.map(item => item.x)),
        Math.min(...values.map(item => item.y - item.height * .2)),
        Math.max(...values.map(item => item.x + item.width)),
        Math.max(...values.map(item => item.y + item.height)),
      ] });
    }
  }
  return result;
}

function lineText(items: PositionedItem[]): string {
  let text = '';
  items.forEach((item, index) => {
    const previous = items[index - 1];
    const gap = previous ? item.x - previous.x - previous.width : 0;
    const needsSpace = previous && gap > Math.max(0.5, Math.min(item.height, previous.height) * .08) &&
      !/\s$/.test(text) && !/^\s|^[,.;:!?\])}]/.test(item.str);
    text += (needsSpace ? ' ' : '') + item.str;
  });
  return text.replace(/\u00ad/g, '').replace(/\s+/g, ' ').trim();
}

function columnOrder(lines: PdfLine[], view: number[]): PdfLine[] {
  const middle = (view[0] + view[2]) / 2;
  const left = lines.filter(line => line.rect[2] < middle);
  const right = lines.filter(line => line.rect[0] > middle);
  if (left.length < 4 || right.length < 4) return lines;
  const spanning = lines.filter(line => !left.includes(line) && !right.includes(line));
  const top = Math.max(...left.concat(right).map(line => line.rect[3]));
  const bottom = Math.min(...left.concat(right).map(line => line.rect[1]));
  // Only reorder an unambiguous two-column page. A full-width block between
  // columns needs a richer layout analysis, so leave its original order intact.
  if (spanning.some(line => line.rect[1] < top && line.rect[3] > bottom)) return lines;
  return [...spanning.filter(line => line.rect[1] >= top), ...left, ...right,
    ...spanning.filter(line => line.rect[3] <= bottom)];
}

function metadataText(value: unknown): string | undefined {
  if (Array.isArray(value)) return value.map(metadataText).filter(Boolean).join(', ') || undefined;
  if (typeof value !== 'string') return undefined;
  return value.replace(/\s+/g, ' ').trim() || undefined;
}

function median(values: number[]): number {
  if (!values.length) return 12;
  const sorted = [...values].filter(value => value > 0 && Number.isFinite(value)).sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)] || 12;
}

function typicalHeight(lines: PdfLine[]): number { return median(lines.map(line => line.height)); }

function margin(line: PdfLine, page: PdfPage): 'top' | 'bottom' | undefined {
  const height = page.view[3] - page.view[1];
  if (line.rect[1] > page.view[3] - height * .085) return 'top';
  if (line.rect[3] < page.view[1] + height * .085) return 'bottom';
  return undefined;
}

function recurringMargins(pages: PdfPage[]): Set<string> {
  const occurrences = new Map<string, Set<number>>();
  for (const page of pages) for (const line of page.lines) {
    const edge = margin(line, page);
    if (!edge || line.text.length > 160) continue;
    const key = edge + ':' + line.text.toLocaleLowerCase();
    const found = occurrences.get(key) || new Set<number>();
    found.add(page.pageNo); occurrences.set(key, found);
  }
  return new Set([...occurrences].filter(([, found]) => {
    if (found.size >= Math.max(3, Math.ceil(pages.length * .6))) return true;
    // Many books alternate the author/title header on odd and even pages.
    return [0, 1].some(parity => [...found].filter(page => page % 2 === parity).length >=
      Math.max(3, Math.ceil(pages.filter(page => page.pageNo % 2 === parity).length * .8)));
  })
    .map(([key]) => key));
}

function skipMargin(line: PdfLine, page: PdfPage, repeated: Set<string>, bodyHeight: number): boolean {
  const edge = margin(line, page);
  if (!edge) return false;
  if (repeated.has(edge + ':' + line.text.toLocaleLowerCase())) return true;
  return edge === 'bottom' && /^(?:\d{1,5}|[ivxlcdm]{1,8})$/i.test(line.text) &&
    line.height <= bodyHeight * 1.15;
}

function headingLine(line: PdfLine, bodyHeight: number): boolean {
  return line.text.length <= 160 && line.height > bodyHeight * 1.18;
}

function groupParagraphs(lines: PdfLine[]): PdfLine[][] {
  const paragraphs: PdfLine[][] = [];
  const height = typicalHeight(lines);
  const verticalGaps = lines.slice(1).map((line, index) => lines[index].rect[1] - line.rect[1]).filter(gap => gap > height * .5 && gap < height * 2.5);
  const lineGap = median(verticalGaps);
  for (const line of lines) {
    const paragraph = paragraphs.at(-1);
    const previous = paragraph?.at(-1);
    const gap = previous ? previous.rect[1] - line.rect[1] : 0;
    const newBlock = !paragraph || !previous || headingLine(previous, height) || headingLine(line, height) ||
      gap <= 0 || gap > Math.max(lineGap * 1.4, height * 1.8) ||
      (line.rect[0] - previous.rect[0] > height * 1.2 && /[.!?:]["'”’)\]]?$/.test(previous.text)) ||
      Math.abs(line.rect[0] - paragraph[0].rect[0]) > Math.max(height * 3, 40);
    if (newBlock) paragraphs.push([line]);
    else paragraph!.push(line);
  }
  return paragraphs;
}

function joinParagraph(lines: PdfLine[]): { text: string; ranges: { start: number; end: number; rect: Rect }[] } {
  let text = '';
  const ranges: { start: number; end: number; rect: Rect }[] = [];
  for (const line of lines) {
    if (/[\p{L}]-$/u.test(text) && /^\p{Ll}/u.test(line.text)) {
      text = text.slice(0, -1);
      if (ranges.length) ranges[ranges.length - 1].end--;
    } else if (text) text += ' ';
    const start = text.length;
    text += line.text;
    ranges.push({ start, end: text.length, rect: line.rect });
  }
  return { text, ranges };
}
