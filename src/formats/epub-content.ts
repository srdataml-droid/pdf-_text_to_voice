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
  packaging?: { metadata?: { title?: string } };
  load: (url: string, type?: string) => Promise<unknown>;
  destroy: () => void;
};

export async function parseEpub(book: BookRecord): Promise<ParsedBook> {
  const epubBook = ePub(await book.file.arrayBuffer()) as unknown as EpubBook;
  try {
    await epubBook.opened;
    const segments: ReadableSegment[] = [];

    for (const [chapterIndex, section] of epubBook.spine.spineItems.entries()) {
      const document = await section.load(epubBook.load.bind(epubBook)) as Element;
      document.querySelectorAll('script, style, noscript, svg, iframe, object, embed').forEach((node) => node.remove());
      const blocks = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre')].filter((node) => !node.parentElement?.closest('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre'));
      const chapterText = blocks.length
        ? blocks.map((node) => node.textContent ?? '').join('\n\n')
        : document.querySelector('body')?.textContent ?? document.textContent ?? '';
      const chapterSegments = sentenceSegments(
        chapterText,
        'epub-' + chapterIndex,
        'Chapter ' + (chapterIndex + 1),
      );
      segments.push(...chapterSegments);
    }

    const title = epubBook.packaging?.metadata?.title?.trim() || book.title;
    if (!segments.length) throw new Error('No readable chapter text was found in this EPUB.');
    return { title, format: 'epub', segments };
  } finally { epubBook.destroy(); }
}
