import { createMp3Encoder } from 'wasm-media-encoders';

export interface MP3EncodeOptions {
  /** Target MP3 bitrate in kbps. */
  bitRate?: number;
}

type SupportedBitRate = 8 | 16 | 24 | 32 | 40 | 48 | 64 | 80 | 96 | 112 | 128 | 160 | 192 | 224 | 256 | 320;

const SUPPORTED_BIT_RATES: SupportedBitRate[] = [
  8,
  16,
  24,
  32,
  40,
  48,
  64,
  80,
  96,
  112,
  128,
  160,
  192,
  224,
  256,
  320,
];

/**
 * Encodes raw Float32 PCM samples into a mono MP3 file.
 */
export class MP3Encoder {
  static async encode(
    samples: Float32Array,
    sampleRate: number,
    options: MP3EncodeOptions = {},
  ): Promise<Uint8Array> {
    const encoder = await createMp3Encoder();
    encoder.configure({
      channels: 1,
      sampleRate,
      bitrate: nearestSupportedBitRate(options.bitRate ?? 128),
    });

    const chunks = [
      copyChunk(encoder.encode([samples])),
      copyChunk(encoder.finalize()),
    ].filter((chunk) => chunk.length > 0);

    return concat(chunks);
  }
}

function nearestSupportedBitRate(bitRate: number): SupportedBitRate {
  const normalized = Math.max(8, Math.min(320, Math.floor(bitRate)));
  return SUPPORTED_BIT_RATES.reduce((nearest, candidate) => (
    Math.abs(candidate - normalized) < Math.abs(nearest - normalized)
      ? candidate
      : nearest
  ));
}

function copyChunk(chunk: Uint8Array): Uint8Array {
  return new Uint8Array(chunk);
}

function concat(chunks: Uint8Array[]): Uint8Array {
  const length = chunks.reduce((total, chunk) => total + chunk.byteLength, 0);
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}
