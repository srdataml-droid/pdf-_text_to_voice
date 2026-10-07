import JSZip from 'jszip';
import type { BookFormat, BookRecord, ReadingProgress } from '../domain/book';
import { getProgress, listBooks, saveBook, saveProgress } from './book-store';

type BackupBook = Omit<BookRecord, 'file'> & { entry: string; progress?: ReadingProgress };
type BackupManifest = { version: 1; createdAt: number; books: BackupBook[] };

export async function createLibraryBackup(): Promise<Blob> {
  const zip = new JSZip();
  const books = await listBooks();
  const manifestBooks: BackupBook[] = [];

  for (const [index, book] of books.entries()) {
    const entry = 'books/' + index + '.' + book.format;
    zip.file(entry, book.file);
    const { file: _file, ...record } = book;
    manifestBooks.push({ ...record, entry, progress: await getProgress(book.id) });
  }

  zip.file('library.json', JSON.stringify({ version: 1, createdAt: Date.now(), books: manifestBooks }));
  return zip.generateAsync({
    type: 'blob',
    mimeType: 'application/vnd.novaxis.reader-backup+zip',
    compression: 'DEFLATE',
    compressionOptions: { level: 1 },
  });
}

export async function restoreLibraryBackup(file: Blob): Promise<number> {
  const zip = await JSZip.loadAsync(file, { checkCRC32: true });
  const manifestFile = zip.file('library.json');
  if (!manifestFile) throw new Error('This backup is missing its library details.');

  let manifest: BackupManifest;
  try {
    manifest = JSON.parse(await manifestFile.async('text')) as BackupManifest;
  } catch {
    throw new Error('This backup file could not be read.');
  }
  if (manifest.version !== 1 || !Array.isArray(manifest.books)) {
    throw new Error('This backup version is not supported.');
  }

  let restored = 0;
  for (const item of manifest.books) {
    if (!isValidBook(item)) continue;
    const fileEntry = zip.file(item.entry);
    if (!fileEntry) continue;
    const { entry, progress, ...record } = item;
    const book: BookRecord = {
      ...record,
      file: await fileEntry.async('blob'),
    };
    await saveBook(book);
    if (progress && Number.isInteger(progress.segmentIndex) && progress.segmentIndex >= 0) await saveProgress(book.id, progress.segmentIndex);
    restored++;
  }
  return restored;
}

function isValidBook(book: BackupBook): boolean {
  return book !== null && typeof book === 'object' &&
    typeof book.addedAt === 'number' && Number.isFinite(book.addedAt) &&
    typeof book.size === 'number' && Number.isFinite(book.size) && book.size >= 0 &&
    typeof book.id === 'string' && book.id.length > 0 &&
    typeof book.title === 'string' &&
    (book.format === 'pdf' || book.format === 'epub' || book.format === 'txt') &&
    typeof book.entry === 'string' &&
    /^books\/[0-9]+\.(pdf|epub|txt)$/.test(book.entry);
}
