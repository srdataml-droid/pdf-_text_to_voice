import type { BookFormat, BookRecord } from '../domain/book';
import { saveBook } from '../storage/book-store';

export type BookImportResult = {
  file: File;
  status: 'added' | 'failed' | 'unsupported' | 'skipped';
  message: string;
  book?: BookRecord;
};

export type BookImportBatch = { results: BookImportResult[]; quotaReached: boolean; stopped: boolean };

export async function importBookFiles(files: readonly File[], callbacks: {
  progress: (position: number, total: number, file: File) => void;
  result: (result: BookImportResult) => void;
  cancelled: () => boolean;
}): Promise<BookImportBatch> {
  const results: BookImportResult[] = [];
  let quotaReached = false, stopped = false;
  for (const [position, file] of files.entries()) {
    let result: BookImportResult;
    if (callbacks.cancelled() || quotaReached) {
      stopped ||= callbacks.cancelled();
      result = { file, status: 'skipped', message: quotaReached ? 'Not added because local storage filled up.' : 'Stopped before adding this book.' };
    } else {
      callbacks.progress(position + 1, files.length, file);
      const format = bookFormat(file);
      if (!format) result = { file, status: 'unsupported', message: 'Choose PDF, EPUB, or TXT books.' };
      else {
        const book: BookRecord = { id: crypto.randomUUID(), title: file.name.replace(/\.(pdf|epub|txt)$/i, ''), format, size: file.size, addedAt: Date.now(), file };
        try {
          // Copy one file at a time; do not load or synthesize all books together.
          await saveBook(book);
          result = { file, book, status: 'added', message: 'Stored on this device.' };
        } catch (error) {
          quotaReached = error instanceof DOMException && error.name === 'QuotaExceededError';
          result = { file, status: 'failed', message: quotaReached ? 'Not enough local storage for this book.' : error instanceof Error ? error.message : 'This book could not be stored.' };
        }
      }
    }
    results.push(result); callbacks.result(result);
  }
  return { results, quotaReached, stopped };
}

function bookFormat(file: File): BookFormat | undefined {
  const name = file.name.toLowerCase();
  if (name.endsWith('.pdf') || file.type === 'application/pdf') return 'pdf';
  if (name.endsWith('.epub') || file.type === 'application/epub+zip') return 'epub';
  if (name.endsWith('.txt') || file.type === 'text/plain') return 'txt';
}
