import type { BookRecord, ParsedBook, ReadableSegment } from '../domain/book';
import { sentenceSegments } from './segments';

export async function parseTxt(book: BookRecord): Promise<ParsedBook> {
  const text = await book.file.text();
  const paragraphs = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split(/\n\s*\n/g);
  const segments: ReadableSegment[] = [];
  const chapters: { title: string; index: number }[] = [];
  const legacyIndexMap: number[] | undefined = book.segmentCount === undefined ? [] : undefined;
  let chapterTitle: string | undefined;
  paragraphs.forEach((paragraph, index) => {
    const start = segments.length;
    // Explicit chapter/Markdown headings can also occur before a paragraph
    // without a blank line. Keep ordinary wrapped lines together for speech.
    const blocks = paragraph.split(/\n(?=#{1,6}\s|(?:chapter|part|book)\s+(?:[\dIVXLCDM]+|one|two|three|four|five|six|seven|eight|nine|ten)\b)/i);
    let passageIndex = 0;
    for (const [blockIndex, block] of blocks.entries()) {
      const lines = block.trim().split('\n');
      const first = lines[0]?.trim() ?? '';
      const heading = isHeading(first);
      if (heading) {
        chapterTitle = first.replace(/^#{1,6}\s+/, '').trim();
        chapters.push({ title: chapterTitle, index: segments.length });
        segments.push({ id: 'txt-' + index + '-' + passageIndex++, text: chapterTitle,
          locationLabel: chapterTitle, chapterTitle, kind: 'heading', blockId: 'txt-' + index + '-' + blockIndex + '-heading' });
        lines.shift();
      }
      const paragraphText = lines.join('\n').replace(/([\p{L}])-\n\s*(?=\p{Ll})/gu, '$1');
      for (const segment of sentenceSegments(paragraphText, 'txt-' + index, 'Paragraph ' + (index + 1))) {
        segments.push({ ...segment, id: 'txt-' + index + '-' + passageIndex++,
          chapterTitle, kind: 'paragraph', blockId: 'txt-' + index + '-' + blockIndex });
      }
    }
    if (legacyIndexMap) {
      const legacy = sentenceSegments(paragraph, 'txt-' + index, 'Paragraph ' + (index + 1));
      legacyIndexMap.push(...mapLegacyParagraph(legacy, segments.slice(start), start));
    }
  });
  if (!segments.length) throw new Error('This text file is empty.');
  return { title: book.title, format: 'txt', segments, chapters, legacyIndexMap };
}

function mapLegacyParagraph(previous: ReadableSegment[], current: ReadableSegment[], start: number): number[] {
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

function isHeading(text: string): boolean {
  return text.length > 0 && text.length <= 140 && (
    /^#{1,6}\s+\S/.test(text) ||
    /^(?:chapter|part|book)\s+(?:[\dIVXLCDM]+|one|two|three|four|five|six|seven|eight|nine|ten)\b(?:\s*[:.\-–—]?\s*.*)?$/i.test(text) ||
    /^(?:prologue|epilogue|preface|foreword|introduction|contents)$/i.test(text)
  );
}
