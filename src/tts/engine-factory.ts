import type { ModelId } from './catalog';
import type { SpeechEngine } from './speech-engine';

export async function createSpeechEngine(model: ModelId): Promise<SpeechEngine> {
  if (model === 'piper') return new (await import('./piper-engine')).PiperEngine();
  if (model === 'kokoro') return new (await import('./kokoro-engine')).KokoroEngine();
  return new (await import('./kitten-engine')).KittenEngine();
}
