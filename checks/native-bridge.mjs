import assert from 'node:assert/strict';
import { createServer } from 'vite';
const server = await createServer({ configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, watch: null }, appType: 'custom' });
const { NativePiper } = await server.ssrLoadModule('/src/reader/native-piper.ts');
const stopped = { bookId: '', index: 0, state: 'stopped', savedAt: 0, alive: false };
const gate = () => { let release; const promise = new Promise(resolve => { release = resolve; }); return { promise, release }; };
function fake() {
  const calls = [];
  return {
    calls, addListener: async () => ({ remove: async () => {} }), getState: async () => stopped,
    getProgress: async () => ({}), beginPlan: async options => { calls.push(['begin', options]); return { uploadId: 'synthetic-plan' }; },
    appendPlan: async options => { calls.push(['append', options]); }, finishPlan: async options => { calls.push(['finish', options]); },
    abortPlan: async options => { calls.push(['abort', options]); }, pause: async () => stopped, stop: async () => stopped,
    resume: async () => stopped, seek: async () => stopped, setSpeed: async () => {},
  };
}
const segments = Array.from({ length: 3000 }, (_, index) => ({ id: String(index), text: 'Synthetic chapter ' + (Math.floor(index / 3) + 1) + ' passage ' + index, locationLabel: 'Chapter ' + (Math.floor(index / 3) + 1) }));
const settings = { model: 'piper', voice: 'en_US-lessac-medium', speed: 1 };
try {
  {
    const bridge = fake(); const player = new NativePiper(() => {}, assert.fail, bridge);
    await player.load(segments, 0, { id: 'synthetic', title: 'Synthetic book' }); await player.play(0, settings);
    const batches = bridge.calls.filter(([name]) => name === 'append').map(([, options]) => options.segments);
    assert.ok(batches.every(batch => batch.length <= 128));
    assert.deepEqual(batches.flat(), segments.map(segment => segment.text));
    assert.equal(bridge.calls.filter(([name]) => name === 'finish').length, 1);
    assert.equal(bridge.calls[0][1].count, 3000);
    assert.ok(!('segments' in bridge.calls[0][1]));
    console.log('PASS all 1,000 chapters transfer in bounded, ordered batches');
    await player.dispose();
  }
  {
    const bridge = fake(), entered = gate(), resume = gate(); const updates = [];
    bridge.appendPlan = async () => { entered.release(); await resume.promise; };
    const player = new NativePiper((index, state) => updates.push(state), assert.fail, bridge);
    await player.load(segments, 0, { id: 'synthetic', title: 'Synthetic book' });
    const work = player.play(0, settings); await entered.promise; await player.pause(); resume.release(); await work;
    assert.ok(!bridge.calls.some(([name]) => name === 'finish'));
    assert.ok(bridge.calls.some(([name]) => name === 'abort'));
    assert.equal(updates.at(-1), 'paused'); console.log('PASS pause during transfer cancels without starting narration');
    await player.dispose();
  }
  {
    const bridge = fake(); const updates = [];
    bridge.appendPlan = async () => { throw new Error('Synthetic disk full'); };
    const player = new NativePiper((index, state) => updates.push(state), assert.fail, bridge);
    await player.load(segments, 0, { id: 'synthetic', title: 'Synthetic book' });
    await assert.rejects(player.play(0, settings), /Synthetic disk full/);
    assert.equal(updates.at(-1), 'stopped');
    assert.ok(!bridge.calls.some(([name]) => name === 'finish'));
    assert.ok(bridge.calls.some(([name]) => name === 'abort'));
    console.log('PASS storage failure aborts the partial plan and exits loading'); await player.dispose();
  }
  {
    const bridge = fake(), entered = gate(), finish = gate(); let finishes = 0, pauses = 0;
    bridge.finishPlan = async () => { if (++finishes === 1) { entered.release(); await finish.promise; } };
    bridge.pause = async () => { pauses++; return stopped; };
    const player = new NativePiper(() => {}, assert.fail, bridge);
    await player.load(segments, 0, { id: 'synthetic', title: 'Synthetic book' });
    const first = player.play(0, settings); await entered.promise; await player.pause();
    await player.play(0, settings); finish.release(); await first;
    assert.equal(pauses, 1, 'A superseded completion must not pause the newer narration request');
    console.log('PASS late completion cannot pause a newer playback request'); await player.dispose();
  }

} finally { await server.close(); }
