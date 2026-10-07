import type { BookRecord, ParsedBook } from '../domain/book';
import { sentenceSegments } from './segments';

export async function parseTxt(book: BookRecord): Promise<ParsedBook> {
  const text = await book.file.text();
  const paragraphs = text.replace(/\r\n?/g, '\n').split(/\n\s*\n/g);
  const segments = paragraphs.flatMap((paragraph, index) =>
    sentenceSegments(paragraph, 'txt-' + index, 'Paragraph ' + (index + 1)),
  );
  if (!segments.length) throw new Error('This text file is empty.');
  return { title: book.title, format: 'txt', segments };
}
