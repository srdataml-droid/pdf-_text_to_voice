import assert from 'node:assert/strict';
import { createServer } from 'vite';

const server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null }, appType: 'custom' });
const { PlaybackController } = await server.ssrLoadModule('/src/reader/playback-controller.ts');
const { sentenceSegments } = await server.ssrLoadModule('/src/formats/segments.ts');
const segment = (text) => ({ id: text, text, locationLabel: text });
const gate = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; };
class Audio extends EventTarget {
  src = ''; plays = 0;
  async play() { this.plays++; }
  pause() {}
  load() {}
  removeAttribute() { this.src = ''; }
}
const wav = () => new Blob(['wave'], { type: 'audio/wav' });
let count = 0;
async function check(name, work) { await work(); count++; console.log('PASS ' + name); }

try {
  await check('Long paragraphs use bounded speech passages without losing words', async () => {
    const text = Array.from({ length: 240 }, (_, i) => 'word' + i).join(' ');
    const passages = sentenceSegments(text, 'long', 'Chapter 1');
    assert.ok(passages.length > 1);
    assert.ok(passages.every(p => p.text.length <= 350));
    assert.equal(passages.map(p => p.text).join(' '), text);
    assert.equal(new Set(passages.map(p => p.id)).size, passages.length);
    assert.ok(passages.every(p => p.locationLabel === 'Chapter 1'));
    assert.deepEqual(sentenceSegments('First. Second!', 'short', 'Page 1').map(p => p.text), ['First.', 'Second!']);
  });
  await check('Pause cancels speech still preparing', async () => {
    const audio = new Audio(); const entered = gate(); const ready = gate();
    const engine = { initialize: async () => { entered.release(); await ready.promise; }, synthesize: async () => wav(), dispose: async () => {} };
    const player = new PlaybackController(audio, () => {}, async () => engine);
    const segments = [segment('first')]; await player.load(segments);
    const pending = player.play(segments); await entered.promise; await player.pause(); ready.release(); await pending;
    assert.equal(audio.plays, 0); await player.dispose();
  });
  await check('Rapid passage clicks wait for initialization and speak the newest passage', async () => {
    const audio = new Audio(); const entered = gate(); const ready = gate(); let initialized = false; const spoken = [];
    const engine = { initialize: async () => { entered.release(); await ready.promise; initialized = true; }, synthesize: async (text) => { assert.ok(initialized); spoken.push(text); return wav(); }, dispose: async () => {} };
    const player = new PlaybackController(audio, () => {}, async () => engine);
    const segments = [segment('first'), segment('second')]; await player.load(segments);
    const first = player.play(segments); await entered.promise; const second = player.jump(1); ready.release(); await Promise.all([first, second]);
    assert.deepEqual(spoken, ['second']); assert.equal(audio.plays, 1); await player.dispose();
  });
  await check('Opening a book replaces the seek queue', async () => {
    const spoken = []; const audio = new Audio();
    const factory = async () => ({ initialize: async () => {}, synthesize: async (text) => { spoken.push(text); return wav(); }, dispose: async () => {} });
    const player = new PlaybackController(audio, () => {}, factory);
    await player.play([segment('old')]); await player.load([segment('new first'), segment('new second')]); await player.jump(1);
    assert.deepEqual(spoken, ['old', 'new second']); await player.dispose();
  });
  await check('Changing model cancels old speech and disposes only after inference', async () => {
    const entered = gate(); const ready = gate(); const audio = new Audio(); let busy = false; let disposed = false;
    const engine = { initialize: async () => {}, synthesize: async () => { busy = true; entered.release(); await ready.promise; busy = false; return wav(); }, dispose: async () => { assert.equal(busy, false); disposed = true; } };
    const player = new PlaybackController(audio, () => {}, async () => engine);
    const segments = [segment('old voice')]; await player.load(segments); const pending = player.play(segments); await entered.promise;
    player.setSettings({ model: 'kokoro', voice: 'af_heart', speed: 1 }); ready.release(); await pending; await player.stop();
    assert.equal(audio.plays, 0); assert.ok(disposed); await player.dispose();
  });
  await check('Resume reuses the paused clip', async () => {
    const audio = new Audio(); let generated = 0;
    const engine = { initialize: async () => {}, synthesize: async () => { generated++; return wav(); }, dispose: async () => {} };
    const player = new PlaybackController(audio, () => {}, async () => engine); const segments = [segment('resume')];
    await player.play(segments); await player.pause(); await player.play(segments, 0);
    assert.equal(generated, 1); assert.equal(audio.plays, 2); await player.dispose();
  });
  await check('A failed initialization can be retried', async () => {
    const audio = new Audio(); let tries = 0; let disposed = 0;
    const factory = async () => ({ initialize: async () => { if (++tries === 1) throw new Error('missing asset'); }, synthesize: async () => wav(), dispose: async () => { disposed++; } });
    const player = new PlaybackController(audio, () => {}, factory); const segments = [segment('retry')];
    await assert.rejects(player.play(segments), /missing asset/); await player.play(segments);
    assert.equal(audio.plays, 1); assert.equal(tries, 2); assert.equal(disposed, 1); await player.dispose();
  });
  console.log(count + ' reading and playback checks passed.');
} finally { await server.close(); }
