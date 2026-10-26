import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type { ReadableSegment } from '../domain/book';
import type { PlaybackSettings, PlaybackUpdate, BookIdentity } from './playback-controller';

type NativeState = { bookId: string; index: number; state: 'playing' | 'loading' | 'paused' | 'stopped' | 'ended' | 'error'; alive: boolean; savedAt: number; error?: string };
const native = registerPlugin<{
  start(options: { bookId: string; title: string; segments: { text: string }[]; index: number; speed: number }): Promise<void>;
  getState(): Promise<NativeState>;
  getProgress(options: { bookId: string }): Promise<{ index?: number; savedAt?: number }>;
  pause(): Promise<NativeState>; resume(): Promise<NativeState>; seek(options: { index: number }): Promise<NativeState>;
  stop(): Promise<NativeState>; setSpeed(options: { speed: number }): Promise<void>;
  addListener(name: 'state', listener: (state: NativeState) => void): Promise<PluginListenerHandle>;
}>('ReaderPlayback');

export function hasNativePiper(): boolean { return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('ReaderPlayback'); }
export function androidReader(): boolean { return Capacitor.getPlatform() === 'android'; }
export function usesNativePiper(settings: PlaybackSettings): boolean { return hasNativePiper() && settings.model === 'piper' && settings.voice === 'en_US-lessac-medium'; }

export class NativePiper {
  private identity?: BookIdentity;
  private segments: ReadableSegment[] = [];
  private ready: Promise<PluginListenerHandle>;
  private operation = 0;
  private index = 0;
  constructor(private update: PlaybackUpdate, private error: (error: unknown) => void) {
    this.ready = native.addListener('state', state => this.receive(state));
  }
  private receive(state: NativeState): void {
    if (!this.identity || state.bookId !== this.identity.id) return;
    const index = Math.max(0, Math.min(state.index, Math.max(0, this.segments.length - 1)));
    this.index = index;
    this.update(index, state.state === 'error' ? 'stopped' : state.state);
    if (state.state === 'error') this.error(new Error(state.error || 'Piper stopped. Press Play to retry this passage.'));
  }
  async load(segments: ReadableSegment[], index: number, identity?: BookIdentity): Promise<number> {
    this.segments = segments; this.identity = identity; this.index = index;
    if (!identity) return index;
    const saved = await native.getProgress({ bookId: identity.id });
    if (Number.isInteger(saved.index) && (saved.savedAt ?? 0) > (identity.progressSavedAt ?? 0)) {
      this.index = Math.max(0, Math.min(saved.index!, Math.max(0, segments.length - 1)));
      return this.index;
    }
    return index;
  }
  async play(index: number, settings: PlaybackSettings, seek = false): Promise<void> {
    const operation = ++this.operation;
    if (!this.identity || !this.segments.length) return;
    await this.ready;
    const state = await native.getState();
    if (operation !== this.operation) return;
    if (state.alive && state.bookId === this.identity.id && ['playing', 'loading', 'paused'].includes(state.state)) {
      if (seek) this.receive(await native.seek({ index }));
      else this.receive(await native.resume());
      return;
    }
    this.index = index; this.update(index, 'loading');
    try { await native.start({ bookId: this.identity.id, title: this.identity.title, segments: this.segments.map(segment => ({ text: segment.text })), index, speed: settings.speed }); }
    catch (error) { if (operation !== this.operation) return; throw error; }
    if (operation !== this.operation) await native.pause();
  }
  async pause(): Promise<void> {
    this.operation++;
    const state = await native.pause(); this.receive(state);
    if (!state.alive || state.bookId !== this.identity?.id) this.update(this.index, 'paused');
  }
  async stop(): Promise<void> { this.operation++; const state = await native.stop(); this.receive({ ...state, state: 'stopped', error: undefined }); }
  async setSpeed(speed: number): Promise<void> { await native.setSpeed({ speed }); }
  async dispose(): Promise<void> { await this.stop(); await (await this.ready).remove(); }
}
