import type { BookRecord, ReadingProgress } from '../domain/book';
import { openDatabase, requestResult } from './database';

export async function saveBook(book: BookRecord): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction('books', 'readwrite');
  tx.objectStore('books').put(book);
  await transactionDone(tx);
}

export async function listBooks(): Promise<BookRecord[]> {
  const db = await openDatabase();
  const books = await requestResult<BookRecord[]>(db.transaction('books').objectStore('books').getAll());
  return books.sort((a, b) => b.addedAt - a.addedAt);
}

export async function getBook(id: string): Promise<BookRecord | undefined> {
  const db = await openDatabase();
  return requestResult<BookRecord | undefined>(db.transaction('books').objectStore('books').get(id));
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

export function transactionDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error ?? new Error('Could not save the change.'));
    tx.onabort = () => reject(tx.error ?? new Error('The save was cancelled.'));
  });
}
