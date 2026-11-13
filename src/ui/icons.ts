const iconNames = new Set([
  'sprout', 'plus', 'headphones', 'library', 'audio-lines', 'hard-drive',
  'shield-check', 'book-open', 'search', 'arrow-down-wide-narrow', 'chevron-down',
]);

/** The wrapper sets the slot size; each Figma SVG keeps its intrinsic dimensions. */
export function icon(name: string, size = 20): string {
  if (!iconNames.has(name)) return '';
  const slot = Number.isFinite(size) ? Math.max(16, Math.min(48, size)) : 20;
  return `<span class="ui-icon ui-icon--${name}" style="--icon-slot:${slot}px" aria-hidden="true"><img src="/design/icons/${name}.svg" alt=""></span>`;
}
