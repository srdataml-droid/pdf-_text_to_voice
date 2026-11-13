import assert from 'node:assert/strict';
import { createServer } from 'vite';
let live = 0, peak = 0;
const sections = Array.from({ length: 1000 }, (_, i) => {
  let loaded = false;
  return {
    href: 'chapter-' + i + '.xhtml',
    async load() {
      loaded = true; live++; peak = Math.max(peak, live);
      return {
        querySelectorAll: () => [],
        querySelector: selector => selector === 'body' ? { textContent: 'First sentence. Second sentence.' } : selector === 'h1,h2' ? { textContent: 'Chapter ' + (i + 1) } : null,
      };
    },
    unload() { if (loaded) { loaded = false; live--; } },
  };
});
globalThis.__syntheticEpub = {
  opened: Promise.resolve(), spine: { spineItems: sections }, packaging: { metadata: {} },
  resolve: path => path, load: async () => undefined,
  destroy: () => sections.forEach(section => section.unload()),
};
const server = await createServer({ configFile: false, ssr: { noExternal: ['epubjs'] }, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null }, appType: 'custom', plugins: [{
  name: 'synthetic-epub-input', enforce: 'pre',
  resolveId(id) { if (id === 'epubjs') return '\0synthetic-epub'; },
  load(id) { if (id === '\0synthetic-epub') return 'export default () => globalThis.__syntheticEpub'; },
}] });
try {
  const { parseEpub } = await server.ssrLoadModule('/src/formats/epub-content.ts');
  const result = await parseEpub({ id: 'synthetic', title: 'Generated book', format: 'epub', size: 9, addedAt: 0, segmentCount: 2000, file: new Blob(['synthetic']) });
  assert.equal(result.segments.length, 2000); assert.equal(result.chapters.length, 1000);
  assert.equal(result.segments.at(-1).locationLabel, 'Chapter 1000');
  assert.equal(peak, 1, 'Finished chapter documents must be released before loading the next');
  assert.equal(live, 0);
  console.log('PASS 1,000 extracted chapters retain at most one loaded chapter document');
} finally { await server.close(); delete globalThis.__syntheticEpub; }
