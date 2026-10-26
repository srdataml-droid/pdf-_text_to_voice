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
    detail: 'A compact starting point. Lessac US English is included, with Android background reading. Imported voices need the reader open.',
    voices: [{ id: 'en_US-lessac-medium', label: 'Lessac · US English', model: 'piper' }],
  },
  {
    id: 'kokoro',
    label: 'Kokoro',
    detail: '28 included English voices. Compare a few to find your preferred reading sound.',
    voices: kokoroIds.map((id) => ({ id, label: title(id.split('_')[1]) + ' · ' + (id.startsWith('a') ? 'US English' : 'British English'), model: 'kokoro' })),
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

export const featuredVoices: { model: ModelId; id: string; name: string; accent: string }[] = [
  { model: 'piper', id: 'en_US-lessac-medium', name: 'Lessac', accent: 'US English' },
  { model: 'kokoro', id: 'af_heart', name: 'Heart', accent: 'US English' },
  { model: 'kokoro', id: 'bm_george', name: 'George', accent: 'British English' },
  { model: 'kitten-nano', id: 'luna', name: 'Luna', accent: 'English' },
  { model: 'kitten-nano', id: 'jasper', name: 'Jasper', accent: 'English' },
];
export function voiceName(model: ModelId, id: string): string {
  return featuredVoices.find(voice => voice.model === model && voice.id === id)?.name ?? modelById(model).voices.find(voice => voice.id === id)?.label.split(' · ')[0] ?? 'Local voice';
}
