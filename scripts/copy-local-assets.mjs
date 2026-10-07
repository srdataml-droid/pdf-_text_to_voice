import { access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const paths = [
  'public/voice-packs/piper/en_US-lessac-medium.onnx',
  'public/voice-packs/piper/en_US-lessac-medium.onnx.json',
  'public/voice-packs/kitten/kitten_tts_nano_v0_8.onnx',
  'public/voice-packs/kitten/voices.npz',
  'public/voice-packs/kokoro/onnx/model_quantized.onnx',
  'public/voice-packs/onnx/ort-wasm-simd-threaded.wasm',
  'public/voice-packs/onnx/ort-wasm-simd-threaded.mjs',
  'public/voice-packs/kokoro-runtime/ort-wasm-simd-threaded.wasm',
  'public/voice-packs/kokoro-runtime/ort-wasm-simd-threaded.mjs',
  'public/voice-packs/kokoro/config.json',
  'public/voice-packs/kokoro/tokenizer.json',
  'public/voice-packs/kokoro/tokenizer_config.json',
  'public/voice-packs/piper-runtime/piper_phonemize.wasm',
  'public/voice-packs/piper-runtime/piper_phonemize.data',
  ...['af_heart','af_alloy','af_aoede','af_bella','af_jessica','af_kore','af_nicole','af_nova','af_river','af_sarah','af_sky','am_adam','am_echo','am_eric','am_fenrir','am_liam','am_michael','am_onyx','am_puck','am_santa','bf_alice','bf_emma','bf_isabella','bf_lily','bm_daniel','bm_fable','bm_george','bm_lewis'].map(id => 'public/voice-packs/kokoro/voices/' + id + '.bin'),
];

const missing = [];
for (const item of paths) {
  try {
    await access(resolve(root, item));
  } catch {
    missing.push(item);
  }
}

if (missing.length) {
  console.error(
    'Local voice files are incomplete:\n' +
      missing.map((item) => '  - ' + item).join('\n') +
      '\n\nRun npm run models:prepare while online once, then build the reader. Model files stay local and are not committed.',
  );
  process.exit(1);
}
