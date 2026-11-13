import type {
  KittenTTS,
  KittenTTSGenerateOptions,
  KittenTTSSpeakOptions,
} from '../KittenTTS.js';
import { KittenTTSError } from '../KittenTTSError.js';
import type { KittenTTSResult } from '../KittenTTSResult.js';
import type { AudioPlayOptions } from './AudioOutput.js';

type QueueRuntime = Pick<KittenTTS, 'generate' | 'play' | 'stopSpeaking'>;

interface PlaybackQueueTask {
  run: () => Promise<unknown>;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
}

/**
 * FIFO playback queue for generated KittenTTS audio.
 *
 * Each queued item waits for the previous item to finish before playback starts.
 * The `AudioPlayer.play()` implementation must resolve when playback has
 * finished for strict sequencing.
 */
export class PlaybackQueue {
  private readonly tasks: PlaybackQueueTask[] = [];
  private draining = false;
  private playing = false;

  constructor(private readonly tts: QueueRuntime) {}

  /** Number of queued items, including the item currently playing. */
  get length(): number {
    return this.tasks.length + (this.playing ? 1 : 0);
  }

  /** Number of queued items that have not started playback yet. */
  get pendingCount(): number {
    return this.tasks.length;
  }

  /** Whether the queue is currently playing an item. */
  get isPlaying(): boolean {
    return this.playing;
  }

  /** Queue an already generated result for playback. */
  enqueue(
    result: KittenTTSResult,
    options: AudioPlayOptions = {},
  ): Promise<void> {
    return this.add(() => this.tts.play(result, options));
  }

  /**
   * Queue text for generation and playback.
   *
   * Generation starts only when this item reaches the front of the queue, which
   * keeps output order stable even when inputs have different generation costs.
   */
  enqueueText(
    text: string,
    options: KittenTTSSpeakOptions = {},
  ): Promise<KittenTTSResult> {
    const { voice, speed, ...playOptions } = options;
    const generateOptions: KittenTTSGenerateOptions = { voice, speed };
    return this.add(async () => {
      const result = await this.tts.generate(text, generateOptions);
      await this.tts.play(result, playOptions);
      return result;
    });
  }

  /** Reject queued items that have not started yet. */
  clear(): void {
    const error = KittenTTSError.playbackFailed('Playback queue was cleared.');
    while (this.tasks.length > 0) {
      this.tasks.shift()?.reject(error);
    }
  }

  /** Clear pending items and stop the active player. */
  async stop(): Promise<void> {
    this.clear();
    await this.tts.stopSpeaking();
  }

  private add<T>(run: () => Promise<T>): Promise<T> {
    const promise = new Promise<T>((resolve, reject) => {
      this.tasks.push({
        run,
        resolve: (value) => resolve(value as T),
        reject,
      });
    });
    void this.drain();
    return promise;
  }

  private async drain(): Promise<void> {
    if (this.draining) return;
    this.draining = true;
    try {
      while (this.tasks.length > 0) {
        const task = this.tasks.shift();
        if (!task) continue;
        this.playing = true;
        try {
          task.resolve(await task.run());
        } catch (error) {
          task.reject(error);
        } finally {
          this.playing = false;
        }
      }
    } finally {
      this.draining = false;
      if (this.tasks.length > 0) void this.drain();
    }
  }
}
