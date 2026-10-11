import type { ReadableSegment } from '../domain/book';
import type { ModelId } from '../tts/catalog';
import { createSpeechEngine } from '../tts/engine-factory';
import type { SpeechEngine } from '../tts/speech-engine';
import { SegmentQueue } from './segment-queue';

export type PlaybackSettings = { model: ModelId; voice: string; speed: number };
export type PlaybackUpdate = (index: number, state: 'loading' | 'playing' | 'paused' | 'stopped' | 'ended') => void;
type EngineFactory = (model: ModelId) => Promise<SpeechEngine>;

export class PlaybackController {
  private queue = new SegmentQueue([]);
  private segments: ReadableSegment[] = [];
  private index = 0;
  private settings: PlaybackSettings = { model: 'piper', voice: 'en_US-lessac-medium', speed: 1 };
  private engine?: SpeechEngine;
  private engineKey = '';
  private engineWork: Promise<unknown> = Promise.resolve();
  private objectUrl?: string;
  private generation = 0;
  private paused = false;
  private playing = false;
  private aheadVersion = 0;
  private ahead = new Map<number, { key: string; clip: Promise<Blob | undefined> }>();
  private readonly onEnded = () => { void this.advance().catch(this.onError); };

  constructor(
    private readonly audio: HTMLAudioElement,
    private readonly update: PlaybackUpdate,
    private readonly factory: EngineFactory = createSpeechEngine,
    private readonly onError: (error: unknown) => void = console.error,
  ) {
    this.audio.addEventListener('ended', this.onEnded);
  }

  setSettings(settings: PlaybackSettings): void {
    const changed = this.settings.model !== settings.model || this.settings.voice !== settings.voice;
    const speedChanged = this.settings.speed !== settings.speed;
    this.settings = { ...settings };
    if (changed) void this.stop().catch(this.onError);
    else if (speedChanged) { this.clearAhead(); if (this.playing) this.prepareAhead(); }
  }

  async load(segments: ReadableSegment[], startIndex = 0): Promise<void> {
    await this.stop();
    this.segments = segments;
    this.queue = new SegmentQueue(segments);
    this.index = Math.max(0, Math.min(startIndex, Math.max(0, segments.length - 1)));
    this.update(this.index, 'stopped');
  }

  async play(segments: ReadableSegment[], startIndex = this.index): Promise<void> {
    if (!segments.length) return;
    if (segments !== this.segments) await this.load(segments, startIndex);
    if (this.paused && this.objectUrl && startIndex === this.index) {
      this.paused = false;
      const operation = this.generation;
      await this.audio.play();
      if (operation === this.generation && !this.paused) {
        this.playing = true;
        this.update(this.index, 'playing');
        this.prepareAhead();
      }
      return;
    }
    await this.playAt(Math.max(0, Math.min(startIndex, this.queue.length - 1)));
  }

  async pause(): Promise<void> {
    this.generation++;
    this.playing = false;
    this.clearAhead();
    this.audio.pause();
    this.paused = true;
    this.update(this.index, 'paused');
  }

  async stop(): Promise<void> {
    this.generation++;
    this.playing = false;
    this.clearAhead();
    this.paused = false;
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.audio.load();
    this.releaseUrl();
    this.update(this.index, 'stopped');
    await this.serial(async () => this.disposeEngine());
  }

  async jump(index: number): Promise<void> {
    if (!this.queue.length) return;
    await this.playAt(Math.max(0, Math.min(index, this.queue.length - 1)));
  }

  async dispose(): Promise<void> {
    await this.stop();
    this.audio.removeEventListener('ended', this.onEnded);
  }

  private async playAt(index: number, continuing = false): Promise<void> {
    const segment = this.queue.at(index);
    if (!segment) return;
    const operation = ++this.generation;
    const settings = { ...this.settings };
    if (!continuing) this.clearAhead();
    const buffered = this.ahead.get(index);
    this.ahead.delete(index);
    this.index = index;
    this.paused = false;
    this.playing = false;
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.audio.load();
    this.releaseUrl();
    this.update(index, 'loading');
    try {
      const cached = buffered?.key === this.settingsKey(settings) ? await buffered.clip : undefined;
      const result = cached ?? await this.serial(async () => {
        if (operation !== this.generation) return;
        const engine = await this.getEngine(settings);
        if (operation !== this.generation) return;
        return engine.synthesize(segment.text, settings.speed);
      });
      if (!result || operation !== this.generation || this.paused) return;
      this.objectUrl = URL.createObjectURL(result);
      this.audio.src = this.objectUrl;
      await this.audio.play();
      if (operation === this.generation && !this.paused) {
        this.playing = true;
        this.update(index, 'playing');
        this.prepareAhead();
      }
    } catch (error) {
      if (operation === this.generation) {
        this.update(index, 'stopped');
        throw error;
      }
    }
  }

  private async advance(): Promise<void> {
    if (!this.playing || this.paused) return;
    this.playing = false;
    this.releaseUrl();
    if (this.index + 1 >= this.queue.length) {
      this.audio.removeAttribute('src');
      this.clearAhead();
      this.update(this.index, 'ended');
      return;
    }
    await this.playAt(this.index + 1, true);
  }

  private settingsKey(settings: PlaybackSettings): string {
    return settings.model + ':' + settings.voice + ':' + settings.speed;
  }

  private clearAhead(): void { this.aheadVersion++; this.ahead.clear(); }

  private prepareAhead(): void {
    const version = this.aheadVersion;
    const settings = { ...this.settings };
    const key = this.settingsKey(settings);
    // Only two short clips are retained; inference remains serialized.
    for (let next = this.index + 1; next <= this.index + 2; next++) {
      const segment = this.queue.at(next);
      if (!segment || this.ahead.has(next)) continue;
      const clip = this.serial(async () => {
        if (version !== this.aheadVersion) return;
        const engine = await this.getEngine(settings);
        if (version !== this.aheadVersion) return;
        const result = await engine.synthesize(segment.text, settings.speed);
        return version === this.aheadVersion ? result : undefined;
      }).catch(() => undefined); // A failed preparation is retried when that passage is requested.
      this.ahead.set(next, { key, clip });
    }
  }

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

  private releaseUrl(): void {
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
    this.objectUrl = undefined;
  }
}
