import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { promisify } from 'node:util';

// A foreground-only launcher fails this check: its parent never finishes.
const probe = createServer();
await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
const port = probe.address().port;
await new Promise(resolve => probe.close(resolve));
const pkg = JSON.parse(await readFile('package.json', 'utf8'));
const script = (pkg.scripts.start ?? pkg.scripts.serve).replace(/^node /, '');
const run = promisify(execFile);
let pid;
try {
  let failure;
  try { await run(process.execPath, [script], { env: { ...process.env, PORT: String(port) }, timeout: 4000 }); }
  catch (error) { failure = error; }
  assert.equal(failure, undefined, 'The launcher must finish while leaving the reader running.');
  const url = 'http://127.0.0.1:' + port;
  const health = await (await fetch(url + '/_reader/health')).json();
  pid = health.pid;
  assert.equal(health.app, 'novaxis-reader');
  assert.ok(Number.isInteger(pid));
  assert.equal((await fetch(url + '/')).status, 200);
  await run(process.execPath, [script], { env: { ...process.env, PORT: String(port) }, timeout: 4000 });
  const reused = await (await fetch(url + '/_reader/health')).json();
  assert.equal(reused.pid, pid, 'Starting again must reuse the same reader.');
  console.log('PASS Local reader survives launcher exit and repeated starts reuse it.');
} finally {
  if (pid) process.kill(pid, 'SIGTERM');
}
