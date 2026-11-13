import { KokoroTTS } from 'kokoro-js';
import { env } from '@huggingface/transformers';
import type { SpeechEngine } from './speech-engine';
import type { ModelId } from './catalog';

export class KokoroEngine implements SpeechEngine {
  readonly model: ModelId = 'kokoro';
  private engine?: KokoroTTS;
  private voice = 'af_heart';

  async initialize(voice: string): Promise<void> {
    this.voice = voice;
    env.allowLocalModels = true;
    env.allowRemoteModels = false;
    env.localModelPath = '/voice-packs/';
    env.useBrowserCache = false;
    const onnxEnvironment = env.backends.onnx as unknown as {
      wasm?: { wasmPaths: { mjs: string; wasm: string } };
    };
    onnxEnvironment.wasm = {
      wasmPaths: {
      mjs: '/voice-packs/kokoro-runtime/ort-wasm-simd-threaded.mjs',
      wasm: '/voice-packs/kokoro-runtime/ort-wasm-simd-threaded.wasm',
      },
    };
    this.engine = await KokoroTTS.from_pretrained('kokoro', {
      dtype: 'q8',
      device: 'wasm',
    });
  }

  async synthesize(text: string, speed: number): Promise<Blob> {
    if (!this.engine) throw new Error('Choose Kokoro and prepare the voice first.');
    return (await this.engine.generate(text, { voice: this.voice as never, speed })).toBlob();
  }

  async dispose(): Promise<void> {
    await this.engine?.model.dispose();
    this.engine = undefined;
  }
}
