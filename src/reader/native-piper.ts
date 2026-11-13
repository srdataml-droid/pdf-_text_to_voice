import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import { keepReadingScreenAwake } from '../storage/device-storage';
import type { ReadableSegment } from '../domain/book';
import type { PlaybackSettings, PlaybackUpdate, BookIdentity } from './playback-controller';

type NativeState = { planId?: string; bookId: string; index: number; state: 'playing' | 'loading' | 'paused' | 'stopped' | 'ended' | 'error'; alive: boolean; savedAt: number; error?: string };
export type NativePlaybackBridge = {
  beginPlan(options: { bookId: string; title: string; count: number; index: number; speed: number }): Promise<{ uploadId: string }>;
  appendPlan(options: { uploadId: string; segments: string[] }): Promise<void>;
  finishPlan(options: { uploadId: string }): Promise<void>;
  abortPlan(options: { uploadId: string }): Promise<void>;
  getState(): Promise<NativeState>;
  getProgress(options: { bookId: string }): Promise<{ index?: number; savedAt?: number }>;
  pause(): Promise<NativeState>; resume(): Promise<NativeState>; seek(options: { index: number }): Promise<NativeState>;
  stop(): Promise<NativeState>; setSpeed(options: { speed: number }): Promise<void>;
  addListener(name: 'state', listener: (state: NativeState) => void): Promise<PluginListenerHandle>;
};
const native = registerPlugin<NativePlaybackBridge>('ReaderPlayback');

export function hasNativePiper(): boolean { return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('ReaderPlayback'); }
export function androidReader(): boolean { return Capacitor.getPlatform() === 'android'; }
export function usesNativePiper(settings: PlaybackSettings): boolean { return hasNativePiper() && settings.model === 'piper' && settings.voice === 'en_US-lessac-medium'; }

export class NativePiper {
  private identity?: BookIdentity;
  private segments: ReadableSegment[] = [];
  private ready: Promise<PluginListenerHandle>;
  private operation = 0;
  private index = 0;
  private preparing?: number;
  private planId?: string;
  constructor(private update: PlaybackUpdate, private error: (error: unknown) => void, private bridge: NativePlaybackBridge = native) {
    this.ready = this.bridge.addListener('state', state => this.receive(state));
  }
  private receive(state: NativeState): void {
    if (this.preparing !== undefined || !this.identity || state.bookId !== this.identity.id || (this.planId && this.planId !== state.planId)) return;
    const index = Math.max(0, Math.min(state.index, Math.max(0, this.segments.length - 1)));
    this.index = index;
    this.update(index, state.state === 'error' ? 'stopped' : state.state);
    if (state.state === 'error') this.error(new Error(state.error || 'Piper stopped. Press Play to retry this passage.'));
  }
  async load(segments: ReadableSegment[], index: number, identity?: BookIdentity): Promise<number> {
    this.segments = segments; this.identity = identity; this.index = index; this.planId = undefined;
    if (!identity) return index;
    const saved = await this.bridge.getProgress({ bookId: identity.id });
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
    const state = await this.bridge.getState();
    if (operation !== this.operation) return;
    if (state.alive && state.bookId === this.identity.id && ['playing', 'loading', 'paused'].includes(state.state)) {
      this.planId = state.planId;
      if (seek) this.receive(await this.bridge.seek({ index }));
      else this.receive(await this.bridge.resume());
      return;
    }
    this.index = index; this.update(index, 'loading');
    this.preparing = operation; keepReadingScreenAwake(true);
    let uploadId: string | undefined;
    try {
      ({ uploadId } = await this.bridge.beginPlan({ bookId: this.identity.id, title: this.identity.title, count: this.segments.length, index, speed: settings.speed }));
      if (operation === this.operation) this.planId = uploadId;
      for (let start = 0; start < this.segments.length; start += 128) {
        if (operation !== this.operation) { await this.bridge.abortPlan({ uploadId }); return; }
        await this.bridge.appendPlan({ uploadId, segments: this.segments.slice(start, start + 128).map(segment => segment.text) });
      }
      if (operation !== this.operation) { await this.bridge.abortPlan({ uploadId }); return; }
      await this.bridge.finishPlan({ uploadId });
    } catch (error) {
      if (uploadId) await this.bridge.abortPlan({ uploadId }).catch(() => {});
      if (operation !== this.operation) return;
      this.update(this.index, 'stopped'); throw error;
    } finally {
      if (this.preparing === operation) { this.preparing = undefined; keepReadingScreenAwake(false); }
    }
    if (operation === this.operation) this.receive(await this.bridge.getState());
  }
  async pause(): Promise<void> {
    this.operation++; this.preparing = undefined; keepReadingScreenAwake(false);
    const state = await this.bridge.pause(); this.receive(state);
    if (!state.alive || state.bookId !== this.identity?.id || (this.planId && state.planId !== this.planId)) this.update(this.index, 'paused');
  }
  async stop(): Promise<void> { this.operation++; this.preparing = undefined; keepReadingScreenAwake(false); const state = await this.bridge.stop(); this.receive({ ...state, state: 'stopped', error: undefined }); }
  async setSpeed(speed: number): Promise<void> { await this.bridge.setSpeed({ speed }); }
  async dispose(): Promise<void> { await this.stop(); await (await this.ready).remove(); }
}
