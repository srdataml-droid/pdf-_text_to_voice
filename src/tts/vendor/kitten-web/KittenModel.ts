import { type KittenTTSVoiceId } from './KittenVoice.js';

/**
 * Public model IDs accepted by KittenTTS.
 *
 * Repository names and filenames stay internal to the SDK.
 */
export type KittenTTSModelId = 'nano' | 'nano-int8' | 'micro' | 'mini';

/** Lightweight constants for autocomplete without enum-style names. */
export const model = {
  nano: 'nano',
  nanoInt8: 'nano-int8',
  micro: 'micro',
  mini: 'mini',
} as const;

/** Internal Hugging Face repository slug for the selected model. */
export function modelRepoId(model: KittenTTSModelId): string {
  switch (model) {
    case 'nano':
      return 'kitten-tts-nano-0.8';
    case 'nano-int8':
      return 'kitten-tts-nano-0.8-int8';
    case 'micro':
      return 'kitten-tts-micro-0.8';
    case 'mini':
      return 'kitten-tts-mini-0.8';
  }
}

/** Hugging Face repository ID for the given model. */
export function huggingFaceRepo(model: KittenTTSModelId): string {
  return `KittenML/${modelRepoId(model)}`;
}

/** Base URL for direct file downloads from Hugging Face. */
export function huggingFaceBaseURL(model: KittenTTSModelId): string {
  return `https://huggingface.co/${huggingFaceRepo(model)}/resolve/main`;
}

/** ONNX model filename within the Hugging Face repository. */
export function onnxFileName(model: KittenTTSModelId): string {
  switch (model) {
    case 'nano':
    case 'nano-int8':
      return 'kitten_tts_nano_v0_8.onnx';
    case 'micro':
      return 'kitten_tts_micro_v0_8.onnx';
    case 'mini':
      return 'kitten_tts_mini_v0_8.onnx';
  }
}

/** Voice embeddings archive filename. */
export function voicesFileName(_model: KittenTTSModelId): string {
  return 'voices.npz';
}

/** Approximate total download size in bytes. */
export function approximateDownloadBytes(model: KittenTTSModelId): number {
  switch (model) {
    case 'nano':
      return 56_000_000;
    case 'nano-int8':
      return 25_000_000;
    case 'micro':
      return 41_000_000;
    case 'mini':
      return 80_000_000;
  }
}

/** Hardcoded per-voice speed multiplier matching the upstream model settings. */
export function speedPrior(model: KittenTTSModelId, voice: KittenTTSVoiceId): number {
  switch (model) {
    case 'nano':
    case 'nano-int8':
      return voice === 'hugo' ? 0.9 : 0.8;
    case 'micro':
    case 'mini':
      return 1.0;
  }
}

/** Human-readable display name. */
export function modelDisplayName(model: KittenTTSModelId): string {
  switch (model) {
    case 'nano':
      return 'Nano (fp32)';
    case 'nano-int8':
      return 'Nano (int8)';
    case 'micro':
      return 'Micro';
    case 'mini':
      return 'Mini';
  }
}
