const ASSETS = '/voice-packs';

export async function localBytes(path: string): Promise<Uint8Array> {
  const response = await fetch(ASSETS + path, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error('A local voice file is missing: ' + path + '. Rebuild the app with its voice pack.');
  }
  return new Uint8Array(await response.arrayBuffer());
}

export async function localJson<T>(path: string): Promise<T> {
  const response = await fetch(ASSETS + path, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error('A local voice file is missing: ' + path + '. Rebuild the app with its voice pack.');
  }
  return response.json() as Promise<T>;
}

export function localUrl(path: string): string {
  return new URL(ASSETS + path, window.location.href).toString();
}

export function wavBlob(samples: Float32Array, sampleRate: number): Blob {
  const dataLength = samples.length * 2;
  const buffer = new ArrayBuffer(44 + dataLength);
  const view = new DataView(buffer);
  writeText(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataLength, true);
  writeText(view, 8, 'WAVE');
  writeText(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeText(view, 36, 'data');
  view.setUint32(40, dataLength, true);
  let offset = 44;
  for (const sample of samples) {
    const value = Math.max(-1, Math.min(1, sample));
    view.setInt16(offset, value < 0 ? value * 0x8000 : value * 0x7fff, true);
    offset += 2;
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

function writeText(view: DataView, offset: number, value: string): void {
  for (let index = 0; index < value.length; index++) {
    view.setUint8(offset + index, value.charCodeAt(index));
  }
}
