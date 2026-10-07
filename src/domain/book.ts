export type BookFormat = 'pdf' | 'epub' | 'txt';

export type BookRecord = {
  id: string;
  title: string;
  format: BookFormat;
  size: number;
  addedAt: number;
  file: Blob;
};

export type ReadingProgress = {
  bookId: string;
  segmentIndex: number;
  savedAt: number;
};

export type ReadableSegment = {
  id: string;
  text: string;
  locationLabel: string;
};

export type ParsedBook = {
  title: string;
  format: BookFormat;
  segments: ReadableSegment[];
};
