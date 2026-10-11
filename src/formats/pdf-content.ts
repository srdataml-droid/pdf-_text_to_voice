import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { BookRecord, ParsedBook, ReadableSegment } from '../domain/book';
import { sentenceSegments } from './segments';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

type PositionedItem = { str: string; x: number; y: number; width: number; height: number };

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

    for (let pageNo = 1; pageNo <= document.numPages; pageNo++) {
      const page = await document.getPage(pageNo);
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
      const lines = groupLines(items);
      lines.forEach((line, lineNo) => {
        segments.push(
          ...sentenceSegments(line.text, 'pdf-' + pageNo + '-' + lineNo, 'Page ' + pageNo)
            .map(segment => ({ ...segment, pdf: { page: pageNo, rect: line.rect } })),
        );
      });


    }

    if (!segments.length) throw new Error('No selectable text was found in this PDF.');
    return { title: book.title, format: 'pdf', segments };
  } finally { await loadingTask.destroy(); }
}

function groupLines(items: PositionedItem[]): { text: string; rect: [number, number, number, number] }[] {
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

  return lines
    .sort((a, b) => b.y - a.y)
    .map((line) => ({
      text: line.values
        .sort((a, b) => a.x - b.x)
        .map((item) => item.str)
        .join(' ')
        .replace(/\s+/g, ' ')
        .trim(),
      rect: [
        Math.min(...line.values.map(item => item.x)),
        Math.min(...line.values.map(item => item.y - item.height * .2)),
        Math.max(...line.values.map(item => item.x + item.width)),
        Math.max(...line.values.map(item => item.y + item.height)),
      ] as [number, number, number, number],
    }))
    .filter(line => Boolean(line.text));
}
