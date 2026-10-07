declare module 'piper-tts-web' {
  export class OnnxWebRuntime {
    constructor(options?: { basePath?: string; numThreads?: number });
  }

  export class PhonemizeWebRuntime {
    constructor(options?: { basePath?: string; provider?: { fetch(path: string): Promise<string>; destroy(): void } });
    loadModule(wasmUrl?: string | null, dataUrl?: string | null): Promise<unknown>;
    phonemize(text: string, voiceData: unknown): Promise<{ phonemes?: string[]; phoneme_ids?: number[] }>;
    destroy(): void;
  }

  export class PiperWebEngine {
    constructor(options?: {
      onnxRuntime?: OnnxWebRuntime;
      phonemizeRuntime?: PhonemizeWebRuntime;
      voiceProvider?: { fetch(voice: string): Promise<unknown>; destroy?: () => void };
    });
    generate(text: string, voice: string, speaker?: number): Promise<{ file: Blob }>;
    destroy(): void;
  }
}
