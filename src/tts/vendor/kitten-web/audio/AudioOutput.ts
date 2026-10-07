import {
  KittenTTSError,
  errorMessage,
  isKittenTTSError,
} from '../KittenTTSError.js';
import { WAVEncoder } from './WAVEncoder.js';

export interface AudioPlayOptions {
  /** Called after the configured player has started playback. */
  onPlaybackStart?: () => void;
}

/** Audio player interface that users can provide. */
export interface AudioPlayer {
  /** Play generated PCM samples. Resolves when playback finishes. */
  play(
    samples: Float32Array,
    sampleRate: number,
    options?: AudioPlayOptions,
  ): Promise<void>;
  /** Stop current playback. */
  stop(): Promise<void>;
  /** Pause current playback when supported. */
  pause?(): Promise<void>;
  /** Resume current playback when supported. */
  resume?(): Promise<void>;
  /** Return current playback state when supported. */
  isPlaying?(): boolean;
}

export class AudioOutput {
  private player: AudioPlayer | null;
  private playing = false;

  constructor(player?: AudioPlayer) {
    this.player = player ?? null;
  }

  async play(
    samples: Float32Array,
    sampleRate: number,
    options: AudioPlayOptions = {},
  ): Promise<void> {
    if (!this.player) {
      throw KittenTTSError.playbackFailed(
        'No audio player configured. Pass an AudioPlayer to KittenTTS.create(), ' +
        'or use createBrowserAudioPlayer() in browser apps.',
      );
    }

    await this.stop();
    this.playing = true;
    try {
      await this.player.play(samples, sampleRate, options);
    } catch (error) {
      if (isKittenTTSError(error)) throw error;
      throw KittenTTSError.playbackFailed(errorMessage(error), error);
    } finally {
      this.playing = false;
    }
  }

  async stop(): Promise<void> {
    if (this.player && this.playing) {
      try {
        await this.player.stop();
      } catch (error) {
        throw KittenTTSError.playbackFailed(errorMessage(error), error);
      }
    }
    this.playing = false;
  }

  async pause(): Promise<void> {
    if (!this.player?.pause || !this.playing) return;
    try {
      await this.player.pause();
      this.playing = false;
    } catch (error) {
      throw KittenTTSError.playbackFailed(errorMessage(error), error);
    }
  }

  async resume(): Promise<void> {
    if (!this.player?.resume) return;
    try {
      await this.player.resume();
      this.playing = true;
    } catch (error) {
      throw KittenTTSError.playbackFailed(errorMessage(error), error);
    }
  }

  isPlaying(): boolean {
    return this.player?.isPlaying?.() ?? this.playing;
  }
}

export function createBrowserAudioPlayer(): AudioPlayer {
  let current: HTMLAudioElement | null = null;
  let currentUrl: string | null = null;
  let settleCurrent: (() => void) | null = null;

  const cleanup = () => {
    if (currentUrl) URL.revokeObjectURL(currentUrl);
    currentUrl = null;
    current = null;
    settleCurrent = null;
  };

  return {
    async play(
      samples: Float32Array,
      sampleRate: number,
      options: AudioPlayOptions = {},
    ): Promise<void> {
      await this.stop();
      const wav = WAVEncoder.encode(samples, sampleRate);
      const blob = new Blob([toArrayBuffer(wav)], { type: 'audio/wav' });
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      current = audio;
      currentUrl = url;

      return new Promise<void>((resolve, reject) => {
        let started = false;
        let settled = false;
        const finish = (error?: unknown) => {
          if (settled) return;
          settled = true;
          cleanup();
          if (error) {
            reject(error);
          } else {
            resolve();
          }
        };
        settleCurrent = () => finish();
        audio.onplaying = () => {
          if (started) return;
          started = true;
          options.onPlaybackStart?.();
        };
        audio.onended = () => {
          finish();
        };
        audio.onerror = () => {
          finish(new Error('Browser audio playback failed.'));
        };
        audio.play().catch((error) => {
          finish(error);
        });
      });
    },

    async stop(): Promise<void> {
      const audio = current;
      if (audio) {
        audio.pause();
        audio.currentTime = 0;
      }
      settleCurrent?.();
      cleanup();
    },

    async pause(): Promise<void> {
      current?.pause();
    },

    async resume(): Promise<void> {
      await current?.play();
    },

    isPlaying(): boolean {
      return Boolean(current && !current.paused && !current.ended);
    },
  };
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
}
