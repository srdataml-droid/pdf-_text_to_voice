export type ModelId = 'piper' | 'kokoro' | 'kitten-nano';
export type VoiceOption = { id: string; label: string; model: ModelId };
export type ModelOption = { id: ModelId; label: string; detail: string; voices: VoiceOption[] };

const kokoroIds = [
  'af_heart', 'af_alloy', 'af_aoede', 'af_bella', 'af_jessica', 'af_kore', 'af_nicole',
  'af_nova', 'af_river', 'af_sarah', 'af_sky', 'am_adam', 'am_echo', 'am_eric',
  'am_fenrir', 'am_liam', 'am_michael', 'am_onyx', 'am_puck', 'am_santa', 'bf_alice',
  'bf_emma', 'bf_isabella', 'bf_lily', 'bm_daniel', 'bm_fable', 'bm_george', 'bm_lewis',

];

function title(id: string): string {
  return id.replaceAll('_', ' ').replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
}

export const modelCatalog: ModelOption[] = [
  {
    id: 'piper',
    label: 'Piper',
    detail: 'Compact and quick. Lessac voice pack included.',
    voices: [{ id: 'en_US-lessac-medium', label: 'Lessac · US English', model: 'piper' }],
  },
  {
    id: 'kokoro',
    label: 'Kokoro',
    detail: 'Natural sounding. 28 bundled English voice choices.',
    voices: kokoroIds.map((id) => ({ id, label: title(id), model: 'kokoro' })),
  },
  {
    id: 'kitten-nano',
    label: 'Kitten Nano',
    detail: 'Small model. Eight bundled voices.',
    voices: ['bella', 'jasper', 'luna', 'bruno', 'rosie', 'hugo', 'kiki', 'leo'].map((id) => ({
      id,
      label: title(id),
      model: 'kitten-nano',
    })),
  },
];

export function modelById(id: string): ModelOption {
  const model = modelCatalog.find((item) => item.id === id);
  return model ?? modelCatalog[0];
}
