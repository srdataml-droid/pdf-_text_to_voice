import type { ReadableSegment } from '../domain/book';
import type { ModelId } from '../tts/catalog';
import { createSpeechEngine } from '../tts/engine-factory';
import type { SpeechEngine } from '../tts/speech-engine';

export type PlaybackSettings = { model: ModelId; voice: string; speed: number };
export type PlaybackUpdate = (index: number, state: 'loading' | 'playing' | 'paused' | 'stopped' | 'ended') => void;
type EngineFactory = (model: ModelId) => Promise<SpeechEngine>;
type Clip = { index: number; source: AudioBufferSourceNode; end: number };

export class PlaybackController {
  private segments: ReadableSegment[] = [];
  private index = 0;
  private settings: PlaybackSettings = { model: 'piper', voice: 'en_US-lessac-medium', speed: 1 };
  private engine?: SpeechEngine;
  private engineKey = '';
  private engineWork: Promise<unknown> = Promise.resolve();
  private context?: AudioContext;
  private clips: Clip[] = [];
  private generation = 0;
  private command = 0;
  private next = 0;
  private paused = false;
  private active = false;
  private pumping?: { generation: number; work: Promise<void> };

  constructor(
    _audio: HTMLAudioElement,
    private readonly update: PlaybackUpdate,
    private readonly factory: EngineFactory = createSpeechEngine,
    private readonly onError: (error: unknown) => void = console.error,
  ) {}

  setSettings(settings: PlaybackSettings): void {
    const voiceChanged = this.settings.model !== settings.model || this.settings.voice !== settings.voice;
    this.settings = { ...settings };
    if (voiceChanged) void this.stop().catch(this.onError);
    // Already scheduled speech finishes at its original speed; following clips use the new speed.
  }

  async load(segments: ReadableSegment[], startIndex = 0): Promise<void> {
    const command = this.command + 1;
    await this.stop();
    if (command !== this.command) return;
    this.segments = segments;
    this.index = this.bound(startIndex);
    this.update(this.index, 'stopped');
  }

  async play(segments: ReadableSegment[], startIndex = this.index): Promise<void> {
    if (!segments.length) return;
    // Unlock the audio clock while still handling the user's Play gesture.
    const command = ++this.command;
    this.context ??= new AudioContext();
    const resume = this.context.resume();
    if (segments !== this.segments) {
      this.generation++;
      this.clearClips();
      this.segments = segments;
      this.index = this.bound(startIndex);
      this.active = false;
      this.paused = false;
    }
    await resume;
    if (command !== this.command) return;
    if (this.active && this.paused && startIndex === this.index) {
      this.paused = false;
      this.update(this.index, this.clips.length ? 'playing' : 'loading');
      await this.pump();
      return;
    }
    await this.playAt(this.bound(startIndex));
  }

  async pause(): Promise<void> {
    const command = ++this.command;
    this.paused = true;
    await this.context?.suspend();
    if (command === this.command) this.update(this.index, 'paused');
  }

  async stop(): Promise<void> {
    this.command++;
    this.generation++;
    this.active = false;
    this.paused = false;
    this.clearClips();
    this.update(this.index, 'stopped');
    await this.serial(() => this.disposeEngine());
  }

  async jump(index: number): Promise<void> {
    if (!this.segments.length) return;
    const command = ++this.command;
    this.context ??= new AudioContext();
    await this.context.resume();
    if (command !== this.command) return;
    await this.playAt(this.bound(index));
  }

  async dispose(): Promise<void> {
    await this.stop();
    await this.context?.close();
    this.context = undefined;
  }

  private async playAt(index: number): Promise<void> {
    this.generation++;
    this.clearClips();
    this.index = index;
    this.next = index;
    this.paused = false;
    this.active = true;
    this.update(index, 'loading');
    await this.pump();
  }

  private pump(): Promise<void> {
    const generation = this.generation;
    if (this.pumping?.generation === generation) return this.pumping.work;
    const work = this.fill(generation).catch(error => {
      if (generation !== this.generation) return;
      this.active = false;
      this.clearClips();
      this.update(this.index, 'stopped');
      throw error;
    }).finally(() => {
      if (this.pumping?.generation === generation) this.pumping = undefined;
    });
    this.pumping = { generation, work };
    return work;
  }

  private async fill(generation: number): Promise<void> {
    // Three short decoded passages at most. Page boundaries never stop this queue.
    while (this.active && generation === this.generation && this.clips.length < 3 && this.next < this.segments.length) {
      const position = this.next++;
      const settings = { ...this.settings };
      const blob = await this.serial(async () => {
        if (generation !== this.generation) return;
        const engine = await this.getEngine(settings);
        if (generation !== this.generation) return;
        return engine.synthesize(speechText(this.segments[position].text), settings.speed);
      });
      if (!blob || generation !== this.generation || !this.active) return;
      const context = this.context!;
      const buffer = await context.decodeAudioData(await blob.arrayBuffer());
      if (generation !== this.generation || !this.active) return;
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      const start = Math.max(context.currentTime + .015, this.clips.at(-1)?.end ?? 0);
      const clip: Clip = { index: position, source, end: start + buffer.duration };
      const first = !this.clips.length;
      this.clips.push(clip);
      source.onended = () => {
        source.disconnect();
        if (generation !== this.generation || !this.active) return;
        this.clips = this.clips.filter(item => item !== clip);
        if (this.clips.length) {
          this.index = this.clips[0].index;
          if (!this.paused) this.update(this.index, 'playing');
        } else if (this.next >= this.segments.length && !this.pumping) {
          this.active = false;
          this.update(this.index, 'ended');
        } else if (!this.paused) this.update(this.index, 'loading');
        // A pending fill owns generation of the next passage, including at book end.
        if (this.active) void this.pump().catch(this.onError);
      };
      source.start(start);
      if (first) {
        this.index = position;
        if (!this.paused) this.update(position, 'playing');
      }
    }
    if (generation === this.generation && this.active && !this.clips.length && this.next >= this.segments.length) {
      this.active = false;
      this.update(this.index, 'ended');
    }
  }

  private clearClips(): void {
    for (const { source } of this.clips) {
      source.onended = null;
      source.stop();
      source.disconnect();
    }
    this.clips = [];
  }

  private bound(index: number): number { return Math.max(0, Math.min(index, Math.max(0, this.segments.length - 1))); }

  private async getEngine(settings: PlaybackSettings): Promise<SpeechEngine> {
    const key = settings.model + ':' + settings.voice;
    if (this.engine && this.engineKey === key) return this.engine;
    await this.disposeEngine();
    const engine = await this.factory(settings.model);
    try { await engine.initialize(settings.voice); }
    catch (error) { await engine.dispose(); throw error; }
    this.engine = engine;
    this.engineKey = key;
    return engine;
  }

  private serial<T>(work: () => Promise<T>): Promise<T> {
    const next = this.engineWork.then(work, work);
    this.engineWork = next.catch(() => undefined);
    return next;
  }

  private async disposeEngine(): Promise<void> {
    const engine = this.engine;
    this.engine = undefined;
    this.engineKey = '';
    if (engine) await engine.dispose();
  }
}

// Clean extraction artifacts for speech without altering displayed text or saved positions.
function speechText(text: string): string {
  return text.normalize('NFKC').replace(/[\u00ad\u200b\ufeff]/g, '')
    .replace(/\s+([,.;:!?])/g, '$1').replace(/\s+/g, ' ').trim();
}
