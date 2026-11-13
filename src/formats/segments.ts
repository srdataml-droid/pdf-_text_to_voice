import type { ReadableSegment } from '../domain/book';

export function sentenceSegments(text: string, prefix: string, label: string): ReadableSegment[] {
  const clean = text.replace(/\s+/g, ' ').trim();
  if (!clean) return [];

  const sentences = clean.match(/[^.!?]+[.!?]+(?:["'”’)\]]+)?|[^.!?]+$/g) ?? [clean];
  return sentences
    .map((sentence) => sentence.trim())
    .filter(Boolean)
    .flatMap(shortPassages)
    .map((sentence, index) => ({
      id: prefix + '-' + index,
      text: sentence,
      locationLabel: label,
    }));
}

// Keep local inference small, including books with long unpunctuated paragraphs.
function shortPassages(sentence: string): string[] {
  const passages: string[] = [];
  let remaining = sentence;
  while (remaining.length > 350) {
    const space = remaining.lastIndexOf(' ', 350);
    const cut = space > 0 ? space : 350;
    passages.push(remaining.slice(0, cut));
    remaining = remaining.slice(cut).trimStart();
  }
  if (remaining) passages.push(remaining);
  return passages;
}
