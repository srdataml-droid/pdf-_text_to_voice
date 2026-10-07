import * as ort from 'onnxruntime-web/wasm';
import type { PhonemizeWebRuntime } from 'piper-tts-web';
import { getPiperPack } from '../storage/voice-packs';
import { createLocalPhonemizer } from './local-phonemizer';
import { localBytes, localJson, wavBlob } from './local-assets';
import type { SpeechEngine } from './speech-engine';
import type { ModelId } from './catalog';

type PiperConfig = {
  espeak: { voice: string };
  phoneme_id_map: Record<string, number[]>;
  audio: { sample_rate: number };
  inference: { noise_scale: number; length_scale: number; noise_w: number };
  speaker_id_map?: Record<string, number>;
};

export class PiperEngine implements SpeechEngine {
  readonly model: ModelId = 'piper';
  private session?: ort.InferenceSession;
  private phonemizer?: PhonemizeWebRuntime;
  private config?: PiperConfig;

  async initialize(voice: string): Promise<void> {
    await this.dispose();
    let model: Uint8Array;
    let config: PiperConfig;
    if (voice.startsWith('local:')) {
      const pack = await getPiperPack(voice);
      if (!pack) throw new Error('That local Piper voice is no longer installed.');
      model = new Uint8Array(await pack.model.arrayBuffer());
      config = pack.config as PiperConfig;
    } else {
      if (voice !== 'en_US-lessac-medium') throw new Error('That Piper voice is not installed.');
      [model, config] = await Promise.all([
        localBytes('/piper/' + voice + '.onnx'),
        localJson<PiperConfig>('/piper/' + voice + '.onnx.json'),
      ]);
    }
    this.config = config;
    ort.env.wasm.numThreads = 1;
    ort.env.wasm.wasmPaths = {
      mjs: '/voice-packs/onnx/ort-wasm-simd-threaded.mjs',
      wasm: '/voice-packs/onnx/ort-wasm-simd-threaded.wasm',
    };
    this.phonemizer = await createLocalPhonemizer();
    this.session = await ort.InferenceSession.create(model, { executionProviders: ['wasm'] });
  }

  async synthesize(text: string, speed: number): Promise<Blob> {
    if (!this.session || !this.config || !this.phonemizer) throw new Error('Choose Piper and prepare the voice first.');
    const config = this.config;
    const data = await this.phonemizer.phonemize(text, [config, '']);
    const ids = data.phoneme_ids;
    if (!ids?.length) throw new Error('The local voice could not read this passage.');
    const feeds: Record<string, ort.Tensor> = {
      input: new ort.Tensor('int64', BigInt64Array.from(ids.map(BigInt)), [1, ids.length]),
      input_lengths: new ort.Tensor('int64', BigInt64Array.of(BigInt(ids.length)), [1]),
      scales: new ort.Tensor('float32', Float32Array.of(
        config.inference.noise_scale,
        config.inference.length_scale / Math.max(0.5, Math.min(2, speed)),
        config.inference.noise_w,
      ), [3]),
    };
    if (this.session.inputNames.includes('sid')) feeds.sid = new ort.Tensor('int64', BigInt64Array.of(0n), [1]);
    const output = await this.session.run(feeds);
    try { return wavBlob(output.output.data as Float32Array, config.audio.sample_rate); }
    finally {
      for (const tensor of Object.values(feeds)) tensor.dispose();
      for (const tensor of Object.values(output)) tensor.dispose();
    }
  }

  async dispose(): Promise<void> {
    await this.session?.release();
    this.session = undefined;
    this.phonemizer?.destroy();
    this.phonemizer = undefined;
    this.config = undefined;
  }
}
