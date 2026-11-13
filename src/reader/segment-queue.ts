import type { ReadableSegment } from '../domain/book';

export class SegmentQueue {
  constructor(readonly segments: ReadableSegment[]) {}
  get length(): number { return this.segments.length; }
  at(index: number): ReadableSegment | undefined { return this.segments[index]; }
}
