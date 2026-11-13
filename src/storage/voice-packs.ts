import { openDatabase, requestResult } from './database';
import { transactionDone } from './book-store';

export type LocalPiperPack = { id: string; label: string; config: unknown; model: Blob };
export async function listPiperPacks(): Promise<LocalPiperPack[]> {
  const db = await openDatabase();
  return requestResult(db.transaction('voicePacks').objectStore('voicePacks').getAll());
}
export async function getPiperPack(id: string): Promise<LocalPiperPack | undefined> {
  const db = await openDatabase();
  return requestResult(db.transaction('voicePacks').objectStore('voicePacks').get(id));
}
export async function importPiperPack(files: File[]): Promise<LocalPiperPack> {
  const model = files.find(file => file.name.endsWith('.onnx'));
  const json = files.find(file => file.name.endsWith('.onnx.json'));
  if (!model || !json || json.name !== model.name + '.json') {
    throw new Error('Select the matching Piper .onnx and .onnx.json files together.');
  }
  const config = JSON.parse(await json.text());
  if (!config || typeof config.espeak?.voice !== 'string' || !config.phoneme_id_map ||
      !Number.isFinite(config.audio?.sample_rate) || config.audio.sample_rate <= 0 ||
      !['noise_scale', 'length_scale', 'noise_w'].every(key => Number.isFinite(config.inference?.[key]))) {
    throw new Error('This file is not a supported Piper voice configuration.');
  }
  const id = 'local:' + model.name.slice(0, -5);
  const pack = { id, label: model.name.slice(0, -5).replaceAll('_', ' ') + ' · local', config, model };
  const db = await openDatabase();
  const tx = db.transaction('voicePacks', 'readwrite');
  tx.objectStore('voicePacks').put(pack);
  await transactionDone(tx);
  return pack;
}

export async function deletePiperPack(id: string): Promise<void> {
  const db = await openDatabase();
  const tx = db.transaction('voicePacks', 'readwrite');
  tx.objectStore('voicePacks').delete(id);
  await transactionDone(tx);
}
