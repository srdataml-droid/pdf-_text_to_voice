import { spawn } from 'node:child_process';
import { existsSync, openSync, closeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.PORT ?? 4173);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Choose a valid local reader port.');
const url = 'http://127.0.0.1:' + port;
const log = resolve(tmpdir(), 'novaxis-reader-' + port + '.log');

async function healthy() {
  try {
    const response = await fetch(url + '/_reader/health', { signal: AbortSignal.timeout(500) });
    const status = await response.json();
    return status.app === 'novaxis-reader';
  } catch { return false; }
}

if (!existsSync(resolve(root, 'dist/index.html'))) throw new Error('Build the reader first with npm run build.');
if (!(await healthy())) {
  const output = openSync(log, 'a');
  const child = spawn(process.execPath, [resolve(root, 'scripts/serve-local.mjs')], {
    cwd: root,
    detached: true,
    stdio: ['ignore', output, output],
    windowsHide: true,
    env: { ...process.env, PORT: String(port) },
  });
  child.on('error', error => { console.error(error.message); process.exitCode = 1; });
  child.unref();
  closeSync(output);
  let ready = false;
  for (let attempt = 0; attempt < 30; attempt++) {
    if (await healthy()) { ready = true; break; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  if (!ready) throw new Error('The local reader could not start. Check whether port ' + port + ' is already in use. Details: ' + log);
}

console.log('Novaxis Reader is running at ' + url);
console.log('You can close this launcher. The reader stays running locally.');
