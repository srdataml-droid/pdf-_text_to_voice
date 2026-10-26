import assert from 'node:assert/strict';
import { createServer } from 'vite';
const server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null }, appType: 'custom' });
const { PlaybackController } = await server.ssrLoadModule('/src/reader/playback-controller.ts');
const { sentenceSegments } = await server.ssrLoadModule('/src/formats/segments.ts');
const turn = () => new Promise(resolve => setImmediate(resolve));
const gate = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; };
const segment = text => ({ id: text, text, locationLabel: text });
const wav = text => new Blob([text], { type: 'audio/wav' });
class Clock {
  static latest;
  currentTime = 0; state = 'suspended'; destination = {}; queue = []; played = []; peak = 0;
  constructor() { Clock.latest = this; }
  async resume() { this.state = 'running'; }
  async suspend() { this.state = 'suspended'; }
  async close() { this.state = 'closed'; }
  async decodeAudioData(bytes) { return { duration: .1, label: new TextDecoder().decode(bytes) }; }
  createBufferSource() {
    const clock = this;
    return {
      buffer: undefined, onended: null, startAt: 0,
      connect() {}, disconnect() {},
      start(at) { this.startAt = at; clock.queue.push(this); clock.peak = Math.max(clock.peak, clock.queue.length); },
      stop() { clock.queue = clock.queue.filter(source => source !== this); },
      end() {
        assert.equal(clock.state, 'running'); clock.currentTime = this.startAt + this.buffer.duration;
        clock.played.push(this.buffer.label); this.stop(); this.onended?.();
      },
    };
  }
}
globalThis.AudioContext = Clock;
const engine = synthesize => ({ initialize: async () => {}, synthesize: synthesize ?? (async text => wav(text)), dispose: async () => {} });
let count = 0;
async function check(name, work) { await work(); count++; console.log('PASS ' + name); }
try {
  await check('1,000 chapter transitions preserve order with at most three scheduled clips', async () => {
    const spoken = [], updates = [];
    const input = Array.from({ length: 3000 }, (_, i) => ({ ...segment('passage ' + i), chapterTitle: 'Chapter ' + (Math.floor(i / 3) + 1) }));
    const player = new PlaybackController({}, (index, state) => updates.push([index, state]), async () => engine(async text => { spoken.push(text); return wav(text); }));
    try {
      await player.play(input); const clock = Clock.latest;
      for (let i = 0; i < input.length; i++) { assert.ok(clock.queue.length); clock.queue[0].end(); await turn(); }
      assert.deepEqual(clock.played, input.map(item => item.text)); assert.deepEqual(spoken, clock.played);
      assert.equal(clock.peak, 3); assert.equal(clock.queue.length, 0); assert.deepEqual(updates.at(-1), [2999, 'ended']);
      assert.equal(updates.filter(([, state]) => state === 'ended').length, 1);
    } finally { await player.dispose(); }
  });
  await check('pause suspends the audio clock and resume keeps prepared speech', async () => {
    let generated = 0; const input = [segment('first'), segment('second')];
    const player = new PlaybackController({}, () => {}, async () => engine(async text => { generated++; return wav(text); }));
    try {
      await player.play(input); const clock = Clock.latest, first = clock.queue[0];
      await player.pause(); assert.equal(clock.state, 'suspended'); await player.play(input, 0);
      assert.equal(clock.state, 'running'); assert.equal(clock.queue[0], first); assert.equal(generated, 2);
    } finally { await player.dispose(); }
  });
  await check('speed changes apply to future generation and preserve queued speech', async () => {
    const calls = [], input = Array.from({ length: 5 }, (_, i) => segment(String(i)));
    const player = new PlaybackController({}, () => {}, async () => engine(async (text, speed) => { calls.push([text, speed]); return wav(text); }));
    try {
      await player.play(input); player.setSettings({ model: 'piper', voice: 'en_US-lessac-medium', speed: 1.5 });
      Clock.latest.queue[0].end(); await turn();
      assert.deepEqual(calls, [['0', 1], ['1', 1], ['2', 1], ['3', 1.5]]);
    } finally { await player.dispose(); }
  });
  await check('rapid seeks during voice initialization use the newest passage', async () => {
    const entered = gate(), ready = gate(), spoken = [];
    const voice = { ...engine(async text => { spoken.push(text); return wav(text); }), initialize: async () => { entered.release(); await ready.promise; } };
    const player = new PlaybackController({}, () => {}, async () => voice); const input = [segment('old'), segment('new')];
    try {
      await player.load(input); const work = player.play(input); await entered.promise;
      const seek = player.jump(1); ready.release(); await Promise.all([work, seek]); assert.deepEqual(spoken, ['new']);
    } finally { ready.release(); await player.dispose(); }
  });
  await check('book replacement waits for inference and discards its obsolete audio', async () => {
    const entered = gate(), ready = gate(); let busy = false;
    const voice = { ...engine(async text => { if (text === 'old') { busy = true; entered.release(); await ready.promise; busy = false; } return wav(text); }), dispose: async () => assert.equal(busy, false) };
    const player = new PlaybackController({}, () => {}, async () => voice);
    try {
      const work = player.play([segment('old')]); await entered.promise;
      const replacement = player.load([segment('new')]); ready.release(); await Promise.all([work, replacement]);
      await player.play([segment('new')]); assert.deepEqual(Clock.latest.queue.map(source => source.buffer.label), ['new']);
    } finally { ready.release(); await player.dispose(); }
  });
  await check('model changes wait for old inference before releasing its engine', async () => {
    const entered = gate(), ready = gate(); let busy = false, disposed = false;
    const voice = { ...engine(async text => { busy = true; entered.release(); await ready.promise; busy = false; return wav(text); }), dispose: async () => { assert.equal(busy, false); disposed = true; } };
    const player = new PlaybackController({}, () => {}, async () => voice);
    try {
      const work = player.play([segment('old voice')]); await entered.promise;
      player.setSettings({ model: 'kokoro', voice: 'af_heart', speed: 1 }); ready.release(); await work; await player.stop();
      assert.equal(Clock.latest.queue.length, 0); assert.ok(disposed);
    } finally { ready.release(); await player.dispose(); }
  });
  await check('failed initialization releases its engine and allows a manual retry', async () => {
    let attempts = 0, disposed = 0;
    const player = new PlaybackController({}, () => {}, async () => ({ ...engine(), initialize: async () => { if (++attempts === 1) throw new Error('synthetic missing asset'); }, dispose: async () => { disposed++; } }));
    const input = [segment('retry')];
    try { await assert.rejects(player.play(input), /synthetic missing asset/); await player.play(input); assert.equal(attempts, 2); assert.equal(disposed, 1); assert.equal(Clock.latest.queue.length, 1); }
    finally { await player.dispose(); }
  });
  await check('long paragraphs retain every word in bounded passages', async () => {
    const text = Array.from({ length: 240 }, (_, i) => 'word' + i).join(' '), parts = sentenceSegments(text, 'long', 'Chapter 1');
    assert.ok(parts.every(part => part.text.length <= 350)); assert.equal(parts.map(part => part.text).join(' '), text);
    assert.equal(new Set(parts.map(part => part.id)).size, parts.length);
  });
  await check('speech normalizes extraction artifacts without changing displayed text', async () => {
    const input = [segment('ﬁrst\u200b word .')], spoken = [];
    const player = new PlaybackController({}, () => {}, async () => engine(async text => { spoken.push(text); return wav(text); }));
    try { await player.play(input); assert.deepEqual(spoken, ['first word.']); assert.equal(input[0].text, 'ﬁrst\u200b word .'); }
    finally { await player.dispose(); }
  });
  console.log(count + ' Web Audio reading/playback checks passed (simulated clock; not real sound).');
} finally { await server.close(); delete globalThis.AudioContext; }
