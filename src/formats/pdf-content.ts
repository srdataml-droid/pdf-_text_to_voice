import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { BookRecord, ParsedBook, ReadableSegment } from '../domain/book';
import { sentenceSegments } from './segments';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

type PositionedItem = { str: string; x: number; y: number; end: boolean };

export async function parsePdf(book: BookRecord): Promise<ParsedBook> {
  const loadingTask = pdfjs.getDocument({
    data: await book.file.arrayBuffer(),
    useSystemFonts: false,
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
          end: Boolean((item as { hasEOL?: boolean }).hasEOL),
        }))
        .filter((item) => item.str.trim());
      const lines = groupLines(items);
      lines.forEach((line, lineNo) => {
        segments.push(
          ...sentenceSegments(line, 'pdf-' + pageNo + '-' + lineNo, 'Page ' + pageNo),
        );
      });


    }

    if (!segments.length) throw new Error('No selectable text was found in this PDF.');
    return { title: book.title, format: 'pdf', segments };
  } finally { await loadingTask.destroy(); }
}

function groupLines(items: PositionedItem[]): string[] {
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
    .map((line) =>
      line.values
        .sort((a, b) => a.x - b.x)
        .map((item) => item.str)
        .join(' ')
        .replace(/\s+/g, ' ')
        .trim(),
    )
    .filter(Boolean);
}
