import JSZip from 'jszip';
import type { BookRecord, ReadingProgress } from '../domain/book';
import { getProgress, listBooks, saveBook, saveProgress } from './book-store';

type BackupBook = Omit<BookRecord, 'file' | 'cover'> & { entry: string; coverEntry?: string; coverType?: string; progress?: ReadingProgress };
type BackupManifest = { version: 1; createdAt: number; books: BackupBook[] };
const coverTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/avif'];

export async function createLibraryBackup(): Promise<Blob> {
  const zip = new JSZip();
  const books = await listBooks();
  const manifestBooks: BackupBook[] = [];
  for (const [index, book] of books.entries()) {
    const entry = 'books/' + index + '.' + book.format;
    zip.file(entry, book.file);
    const { file: _file, cover, ...record } = book;
    const item: BackupBook = { ...record, entry, progress: await getProgress(book.id) };
    if (cover instanceof Blob && coverTypes.includes(cover.type) && cover.size <= 1024 * 1024) {
      item.coverEntry = 'covers/' + index; item.coverType = cover.type; zip.file(item.coverEntry, cover);
    }
    manifestBooks.push(item);
  }
  zip.file('library.json', JSON.stringify({ version: 1, createdAt: Date.now(), books: manifestBooks }));
  return zip.generateAsync({ type: 'blob', mimeType: 'application/vnd.novaxis.reader-backup+zip', compression: 'DEFLATE', compressionOptions: { level: 1 } });
}

export async function restoreLibraryBackup(file: Blob): Promise<number> {
  const zip = await JSZip.loadAsync(file, { checkCRC32: true });
  const manifestFile = zip.file('library.json');
  if (!manifestFile) throw new Error('This backup is missing its library details.');
  let manifest: BackupManifest;
  try { manifest = JSON.parse(await manifestFile.async('text')) as BackupManifest; }
  catch { throw new Error('This backup file could not be read.'); }
  if (!manifest || manifest.version !== 1 || !Array.isArray(manifest.books)) throw new Error('This backup version is not supported.');
  // Validate the complete manifest before changing any stored book. Older version-one backups remain supported.
  if (manifest.books.some(item => !isValidBook(item) || !zip.file(item.entry))) throw new Error('This backup contains a missing or invalid book.');
  let restored = 0;
  for (const item of manifest.books) {
    const blob = await zip.file(item.entry)!.async('blob');
    const book: BookRecord = { id: item.id, title: item.title, format: item.format, size: blob.size, addedAt: item.addedAt, file: blob };
    if (typeof item.author === 'string') book.author = item.author;
    if (typeof item.lastLocation === 'string') book.lastLocation = item.lastLocation;
    if (Number.isFinite(item.lastOpenedAt) && item.lastOpenedAt! >= 0) book.lastOpenedAt = item.lastOpenedAt;
    if (Number.isInteger(item.segmentCount) && item.segmentCount! >= 0) book.segmentCount = item.segmentCount;
    if (Number.isInteger(item.pageCount) && item.pageCount! > 0) book.pageCount = item.pageCount;
    if (Number.isInteger(item.lastPage) && item.lastPage! > 0) book.lastPage = item.lastPage;
    if (item.coverEntry && /^covers\/[0-9]+$/.test(item.coverEntry) && coverTypes.includes(item.coverType ?? '')) {
      const entry = zip.file(item.coverEntry);
      // Check declared decompressed size before reading optional images.
      const length = (entry as unknown as { _data?: { uncompressedSize?: number } })?._data?.uncompressedSize;
      if (entry && typeof length === 'number' && length <= 1024 * 1024) {
        const bytes = await entry.async('uint8array');
        if (bytes.byteLength <= 1024 * 1024) book.cover = new Blob([bytes as Uint8Array<ArrayBuffer>], { type: item.coverType });
      }
    }
    await saveBook(book);
    const position = item.progress?.segmentIndex;
    if (Number.isInteger(position) && position! >= 0) await saveProgress(book.id, Math.min(position!, book.segmentCount ? book.segmentCount - 1 : position!));
    restored++;
  }
  return restored;
}

function isValidBook(book: BackupBook): boolean {
  return book !== null && typeof book === 'object' &&
    typeof book.addedAt === 'number' && Number.isFinite(book.addedAt) &&
    typeof book.size === 'number' && Number.isFinite(book.size) && book.size >= 0 &&
    typeof book.id === 'string' && book.id.length > 0 && book.id.length < 512 &&
    typeof book.title === 'string' &&
    (book.format === 'pdf' || book.format === 'epub' || book.format === 'txt') &&
    typeof book.entry === 'string' && new RegExp('^books/[0-9]+\\.' + book.format + '$').test(book.entry);
}
