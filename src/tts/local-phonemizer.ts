import { PhonemizeWebRuntime } from 'piper-tts-web';
import { localBytes } from './local-assets';

class LocalRuntimeFiles {
  private readonly urls = new Map<string, string>();
  async fetch(path: string): Promise<string> {
    const previous = this.urls.get(path);
    if (previous) return previous;
    const bytes = await localBytes(path.replace(/^\/voice-packs/, ''));
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const url = URL.createObjectURL(new Blob([buffer], { type: path.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' }));
    this.urls.set(path, url);
    return url;
  }
  destroy(): void {
    for (const url of this.urls.values()) URL.revokeObjectURL(url);
    this.urls.clear();
  }
}

export async function createLocalPhonemizer(): Promise<PhonemizeWebRuntime> {
  const runtime = new PhonemizeWebRuntime({
    provider: new LocalRuntimeFiles(),
    basePath: '/voice-packs/piper-runtime/',
  });
  try { await runtime.loadModule(); }
  catch (error) { runtime.destroy(); throw error; }
  return runtime;
}
