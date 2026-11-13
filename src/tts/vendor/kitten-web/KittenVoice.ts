/**
 * Public voice IDs accepted by KittenTTS.
 *
 * The SDK keeps internal embedding keys hidden; app code should use these
 * stable IDs only.
 */
export type KittenTTSVoiceId =
  | 'bella'
  | 'jasper'
  | 'luna'
  | 'bruno'
  | 'rosie'
  | 'hugo'
  | 'kiki'
  | 'leo';

/** Lightweight constants for autocomplete without enum-style names. */
export const voice = {
  bella: 'bella',
  jasper: 'jasper',
  luna: 'luna',
  bruno: 'bruno',
  rosie: 'rosie',
  hugo: 'hugo',
  kiki: 'kiki',
  leo: 'leo',
} as const satisfies Record<KittenTTSVoiceId, KittenTTSVoiceId>;

/** All available public voice IDs. */
export const ALL_VOICE_IDS: KittenTTSVoiceId[] = [
  'bella',
  'jasper',
  'luna',
  'bruno',
  'rosie',
  'hugo',
  'kiki',
  'leo',
];

/** Internal voice embedding key used by the model's voices.npz file. */
export function voiceEmbeddingKey(voice: KittenTTSVoiceId): string {
  switch (voice) {
    case 'bella':
      return 'expr-voice-2-f';
    case 'jasper':
      return 'expr-voice-2-m';
    case 'luna':
      return 'expr-voice-3-f';
    case 'bruno':
      return 'expr-voice-3-m';
    case 'rosie':
      return 'expr-voice-4-f';
    case 'hugo':
      return 'expr-voice-4-m';
    case 'kiki':
      return 'expr-voice-5-f';
    case 'leo':
      return 'expr-voice-5-m';
  }
}

/** Human-readable display name for a voice. */
export function voiceDisplayName(voice: KittenTTSVoiceId): string {
  switch (voice) {
    case 'bella':
      return 'Bella';
    case 'jasper':
      return 'Jasper';
    case 'luna':
      return 'Luna';
    case 'bruno':
      return 'Bruno';
    case 'rosie':
      return 'Rosie';
    case 'hugo':
      return 'Hugo';
    case 'kiki':
      return 'Kiki';
    case 'leo':
      return 'Leo';
  }
}

/** Whether the voice is female. */
export function isFemaleVoice(voice: KittenTTSVoiceId): boolean {
  return voice === 'bella' || voice === 'luna' || voice === 'rosie' || voice === 'kiki';
}
