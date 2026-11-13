import { createReadStream, createWriteStream } from 'node:fs';
import { access, copyFile, mkdir, readdir, rename, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(process.env.VOICE_MODEL_SOURCE ?? '../../outputs/voice-models');
const target = resolve(root, 'public/voice-packs');
const kokoroBase = 'https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main';

await mkdir(resolve(target, 'piper'), { recursive: true });
await mkdir(resolve(target, 'kitten'), { recursive: true });
await mkdir(resolve(target, 'kokoro/onnx'), { recursive: true });
await mkdir(resolve(target, 'kokoro/voices'), { recursive: true });
await mkdir(resolve(target, 'onnx'), { recursive: true });

const piperBase = 'https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium';
const kittenBase = 'https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8/resolve/main';
await prepareWeight('piper/en_US-lessac-medium.onnx', piperBase + '/en_US-lessac-medium.onnx');
await prepareWeight('piper/en_US-lessac-medium.onnx.json', piperBase + '/en_US-lessac-medium.onnx.json');
await prepareWeight('kitten/kitten_tts_nano_v0_8.onnx', kittenBase + '/kitten_tts_nano_v0_8.onnx');
await prepareWeight('kitten/voices.npz', kittenBase + '/voices.npz');

await fetchModel('onnx/model_quantized.onnx');
await fetchModel('config.json');
await fetchModel('tokenizer.json');
await fetchModel('tokenizer_config.json');

const kokoroVoices = resolve(root, 'node_modules/kokoro-js/voices');
for (const name of await readdir(kokoroVoices)) {
  if (/^[ab][fm]_.*\.bin$/.test(name)) {
    await copyFile(resolve(kokoroVoices, name), resolve(target, 'kokoro/voices', name));
  }
}

for (const name of await readdir(resolve(target, 'kokoro/voices'))) {
  if (name.endsWith('.bin') && !/^[ab][fm]_/.test(name)) await rm(resolve(target, 'kokoro/voices', name));
}

const ortDist = resolve(root, 'node_modules/onnxruntime-web/dist');
await copyRequired(
  resolve(ortDist, 'ort-wasm-simd-threaded.mjs'),
  resolve(target, 'onnx/ort-wasm-simd-threaded.mjs'),
);
await copyRequired(
  resolve(ortDist, 'ort-wasm-simd-threaded.wasm'),
  resolve(target, 'onnx/ort-wasm-simd-threaded.wasm'),
);

const kokoroOrtDist = resolve(root, 'node_modules/@huggingface/transformers/node_modules/onnxruntime-web/dist');
for (const name of ['ort-wasm-simd-threaded.mjs', 'ort-wasm-simd-threaded.wasm']) {
  await copyRequired(resolve(kokoroOrtDist, name), resolve(target, 'kokoro-runtime', name));
}

const piperDist = resolve(root, 'node_modules/piper-tts-web/dist');
for (const name of ['piper_phonemize.wasm', 'piper_phonemize.data']) {
  await copyRequired(resolve(piperDist, 'piper', name), resolve(target, 'piper-runtime', name));
}

const hashes = {
  'piper/en_US-lessac-medium.onnx': '5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f',
  'kitten/kitten_tts_nano_v0_8.onnx': 'f7b0afcbee92870b32b8e0276d855b954dc25470c9f051b376ac7eee537c76fc',
  'kitten/voices.npz': '8aa7cee235abb0739cb51e6559685f65a4dacd95568833d05699b1633f519b3f',
  'kokoro/onnx/model_quantized.onnx': 'fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478',
};
for (const [name, expected] of Object.entries(hashes)) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(resolve(target, name))) hash.update(chunk);
  if (hash.digest('hex') !== expected) throw new Error('The installed model differs from the supported pack: ' + name);
}

console.log('Local speech assets are ready. The app does not download models when it runs.');

async function fetchModel(name) {
  const destination = resolve(target, 'kokoro', name);
  if (await exists(destination)) return;

  await download(kokoroBase + '/' + name, destination);
}

async function prepareWeight(name, url) {
  const destination = resolve(target, name);
  if (await exists(destination)) return;
  const existing = resolve(source, name);
  if (await exists(existing)) await copyRequired(existing, destination);
  else await download(url, destination);
}

async function download(url, destination) {
  console.log('Preparing local asset: ' + destination);
  const response = await fetch(url);
  if (!response.ok || !response.body) throw new Error('Could not prepare local asset (HTTP ' + response.status + '): ' + url);
  await mkdir(dirname(destination), { recursive: true });
  const partial = destination + '.partial';
  await pipeline(response.body, createWriteStream(partial));
  await rename(partial, destination);
}

async function copyRequired(from, to) {
  if (!(await exists(from))) {
    throw new Error('Missing local model/runtime asset: ' + from);
  }
  await mkdir(dirname(to), { recursive: true });
  await rm(to, { force: true });
  await copyFile(from, to);
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}
