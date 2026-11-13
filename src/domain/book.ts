export type BookFormat = 'pdf' | 'epub' | 'txt';

export type BookRecord = {
  id: string;
  title: string;
  format: BookFormat;
  size: number;
  addedAt: number;
  file: Blob;
  author?: string;
  cover?: Blob;
  segmentCount?: number;
  lastOpenedAt?: number;
  lastLocation?: string;
  lastPage?: number;
  pageCount?: number;
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
  chapterTitle?: string;
  kind?: 'heading' | 'paragraph';
  blockId?: string;
  pdf?: {
    page: number;
    rect: [number, number, number, number];
    rects?: [number, number, number, number][];
  };
};

export type ParsedBook = {
  title: string;
  format: BookFormat;
  segments: ReadableSegment[];
  author?: string;
  cover?: Blob;
  chapters?: { title: string; index: number }[];
  pageCount?: number;
  legacyIndexMap?: number[];
};
