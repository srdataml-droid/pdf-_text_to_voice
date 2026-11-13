import type { PhonemizeWebRuntime } from 'piper-tts-web';
import { KittenTTS } from './vendor/kitten-web/index';
import { createLocalPhonemizer } from './local-phonemizer';
import { localBytes, localJson } from './local-assets';
import type { ModelId } from './catalog';
import type { SpeechEngine } from './speech-engine';

type PiperConfig = {
  espeak: { voice: string };
  phoneme_id_map: Record<string, number[]>;
};

export class KittenEngine implements SpeechEngine {
  readonly model: ModelId = 'kitten-nano';
  private engine?: KittenTTS;
  private phonemizer?: PhonemizeWebRuntime;
  private piperVoice?: [PiperConfig, string];
  private piperModelUrl?: string;
  private voice = 'bella';

  async initialize(voice: string): Promise<void> {
    await this.dispose();
    this.voice = voice;
    const [model, voices, piperConfig] = await Promise.all([
      localBytes('/kitten/kitten_tts_nano_v0_8.onnx'),
      localBytes('/kitten/voices.npz'),
      localJson<PiperConfig>('/piper/en_US-lessac-medium.onnx.json'),
    ]);

    this.piperVoice = [piperConfig, ''];
    this.phonemizer = await createLocalPhonemizer();

    const phonemizer = {
      phonemize: async (text: string) => {
        const data = await this.phonemizer?.phonemize(text, this.piperVoice);
        if (!data?.phonemes?.length) throw new Error('The local phonemizer could not read this passage.');
        return data.phonemes.join('');
      },
    };

    this.engine = await KittenTTS.create({
      model: 'nano-int8',
      defaultVoice: voice as never,
      modelFiles: { onnxData: model, voicesData: voices },
      phonemizer,
      ortNumThreads: 1,
      analytics: false,
      ortWasmPath: {
        mjs: '/voice-packs/onnx/ort-wasm-simd-threaded.mjs',
        wasm: '/voice-packs/onnx/ort-wasm-simd-threaded.wasm',
      },
    });
  }

  async synthesize(text: string, speed: number): Promise<Blob> {
    if (!this.engine) throw new Error('Choose Kitten Nano and prepare the voice first.');
    const result = await this.engine.generate(text, { voice: this.voice as never, speed });
    return new Blob([toArrayBuffer(result.wavData())], { type: 'audio/wav' });
  }

  async dispose(): Promise<void> {
    await this.engine?.dispose();
    this.engine = undefined;
    this.phonemizer?.destroy();
    this.phonemizer = undefined;
    if (this.piperModelUrl) URL.revokeObjectURL(this.piperModelUrl);
    this.piperModelUrl = undefined;
    this.piperVoice = undefined;
  }
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
