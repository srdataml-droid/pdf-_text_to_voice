import { type KittenTTSVoiceId } from './KittenVoice.js';
import { uint8ArrayToBase64 } from './audio/Base64.js';
import { MP3Encoder, type MP3EncodeOptions } from './audio/MP3Encoder.js';
import { WAVEncoder } from './audio/WAVEncoder.js';
import type { KittenWordTiming } from './KittenWordTiming.js';

/**
 * The result of a KittenTTS speech-synthesis call.
 *
 * Contains raw PCM audio samples and metadata. Use {@link wavData} or
 * {@link mp3Data} to encode file formats, or access {@link samples} for raw
 * Float32 PCM.
 */
export class KittenTTSResult {
  /** Raw Float32 PCM samples at {@link sampleRate} Hz, mono channel. */
  readonly samples: Float32Array;

  /** Sample rate of the audio. Always 24,000 Hz. */
  readonly sampleRate: number;

  /** The voice used to generate this audio. */
  readonly voice: KittenTTSVoiceId;

  /** The effective speed multiplier that was applied. */
  readonly effectiveSpeed: number;

  /** The original input text that was synthesised. */
  readonly inputText: string;

  /** Word-level timestamps in seconds. Empty when unavailable. */
  readonly wordTimings: KittenWordTiming[];

  constructor(
    samples: Float32Array,
    sampleRate: number,
    voice: KittenTTSVoiceId,
    effectiveSpeed: number,
    inputText: string,
    wordTimings: KittenWordTiming[] = [],
  ) {
    this.samples = samples;
    this.sampleRate = sampleRate;
    this.voice = voice;
    this.effectiveSpeed = effectiveSpeed;
    this.inputText = inputText;
    this.wordTimings = wordTimings;
  }

  /** Duration of the audio in seconds. */
  get duration(): number {
    return this.samples.length / this.sampleRate;
  }

  /**
   * Encode the audio as a standard 16-bit PCM RIFF WAV file.
   * @returns A Uint8Array containing the complete WAV file.
   */
  wavData(): Uint8Array {
    return WAVEncoder.encode(this.samples, this.sampleRate);
  }

  /**
   * Get the WAV data as a base64-encoded string.
   * Useful for writing to disk, uploading, or passing to a custom audio player.
   */
  wavBase64(): string {
    return uint8ArrayToBase64(this.wavData());
  }

  /**
   * Encode the audio as a mono MP3 file.
   * @param options - Optional bitrate configuration. Defaults to 128 kbps.
   * @returns A promise that resolves with the complete MP3 file.
   */
  mp3Data(options: MP3EncodeOptions = {}): Promise<Uint8Array> {
    return MP3Encoder.encode(this.samples, this.sampleRate, options);
  }

  /**
   * Get the MP3 data as a base64-encoded string.
   */
  async mp3Base64(options: MP3EncodeOptions = {}): Promise<string> {
    return uint8ArrayToBase64(await this.mp3Data(options));
  }
}
