import type { BookRecord, ReadingProgress } from '../domain/book';
import { openDatabase, requestResult } from './database';

export type BookMetadata = Pick<BookRecord,
  'title' | 'author' | 'cover' | 'segmentCount' | 'lastOpenedAt' | 'lastLocation' | 'lastPage' | 'pageCount'>;

export async function saveBook(book: BookRecord): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction('books', 'readwrite');
  tx.objectStore('books').put(book);
  await transactionDone(tx);
}

export async function listBooks(): Promise<BookRecord[]> {
  const db = await openDatabase();
  const books = await requestResult<BookRecord[]>(db.transaction('books').objectStore('books').getAll());
  return books.map(validCover).sort((a, b) => b.addedAt - a.addedAt);
}

export async function getBook(id: string): Promise<BookRecord | undefined> {
  const db = await openDatabase();
  const book = await requestResult<BookRecord | undefined>(db.transaction('books').objectStore('books').get(id));
  return book ? validCover(book) : undefined;
}

export async function updateBookMetadata(id: string, metadata: Partial<BookMetadata>, migratedIndex?: number): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction(migratedIndex === undefined ? ['books'] : ['books', 'progress'], 'readwrite');
  const done = transactionDone(tx);
  const store = tx.objectStore('books');
  const request = store.get(id);
  request.onsuccess = () => {
    const book = request.result as BookRecord | undefined;
    if (!book) return;
    const updated = { ...book };
    // Metadata updates cannot replace the original file, ID, or import date.
    for (const key of ['title', 'author', 'cover', 'segmentCount', 'lastOpenedAt', 'lastLocation', 'lastPage', 'pageCount'] as const) {
      if (Object.prototype.hasOwnProperty.call(metadata, key)) {
        Object.assign(updated, { [key]: metadata[key] });
      }
    }
    store.put(validCover(updated));
    if (migratedIndex !== undefined) {
      tx.objectStore('progress').put({ bookId: id, segmentIndex: migratedIndex, savedAt: Date.now() } satisfies ReadingProgress);
    }
  };
  await done;
}

export async function deleteBook(id: string): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction(['books', 'progress'], 'readwrite');
  tx.objectStore('books').delete(id);
  tx.objectStore('progress').delete(id);
  await transactionDone(tx);
}

export async function getProgress(bookId: string): Promise<ReadingProgress | undefined> {
  const db = await openDatabase();
  return requestResult<ReadingProgress | undefined>(
    db.transaction('progress').objectStore('progress').get(bookId),
  );
}

export async function saveProgress(bookId: string, segmentIndex: number): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction('progress', 'readwrite');
  tx.objectStore('progress').put({ bookId, segmentIndex, savedAt: Date.now() } satisfies ReadingProgress);
  await transactionDone(tx);
}

export async function listReadingProgress(): Promise<ReadingProgress[]> {
  const db = await openDatabase();
  return requestResult<ReadingProgress[]>(db.transaction('progress').objectStore('progress').getAll());
}

function validCover(book: BookRecord): BookRecord {
  if (book.cover === undefined || (book.cover instanceof Blob && book.cover.size <= 1024 * 1024)) return book;
  const { cover: _cover, ...record } = book;
  return record;
}

export function transactionDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('Could not save the change.'));
    tx.onabort = () => reject(tx.error ?? new Error('The save was cancelled.'));
  });
}
