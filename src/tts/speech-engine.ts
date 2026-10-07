import type { ModelId } from './catalog';

export interface SpeechEngine {
  readonly model: ModelId;
  initialize(voice: string): Promise<void>;
  synthesize(text: string, speed: number): Promise<Blob>;
  dispose(): Promise<void>;
}
