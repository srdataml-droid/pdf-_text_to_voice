import JSZip from 'jszip';
import { createWriteStream } from 'node:fs';
import { readFile, readdir, mkdir } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { pipeline } from 'node:stream/promises';

const zip = new JSZip();
const root = resolve('.');
const prefix = 'novaxis-reader/';
async function addFolder(folder) {
  for (const item of await readdir(folder, { withFileTypes: true })) {
    const path = resolve(folder, item.name);
    if (item.isDirectory()) await addFolder(path);
    else zip.file(prefix + relative(root, path).replaceAll('\\', '/'), await readFile(path));
  }
}
await addFolder(resolve('dist'));
zip.file(prefix + 'scripts/serve-local.mjs', await readFile('scripts/serve-local.mjs'));
zip.file(prefix + 'start-reader.sh', '#!/bin/sh\ncd "$(dirname "$0")"\nnode scripts/serve-local.mjs\n', { unixPermissions: 0o755 });
zip.file(prefix + 'start-reader.cmd', '@echo off\ncd /d "%~dp0"\nnode scripts\\serve-local.mjs\npause\n');
zip.file(prefix + 'START-HERE.txt', 'Novaxis Reader local preview\n\nInstall Node.js 22 or newer once if needed.\nWindows: open start-reader.cmd.\nLinux/macOS: run sh start-reader.sh.\nOpen http://127.0.0.1:4173 in your browser.\n\nThe voice models and app assets are included. You can use the reader without an internet connection. Keep this folder to move the same reader to another computer. Books and reading progress stay in your browser; use Back up library to transfer them. Extra Piper packs are separate.\n\nThis is a personal preview. Before distributing voices commercially, review their terms in README.md.\n');
zip.file(prefix + 'README.md', await readFile('README.md'));
await mkdir('artifacts', { recursive: true });
await pipeline(zip.generateNodeStream({ type: 'nodebuffer', streamFiles: true, platform: 'UNIX', compression: 'DEFLATE', compressionOptions: { level: 1 } }), createWriteStream('artifacts/novaxis-reader-portable.zip'));
console.log('Created artifacts/novaxis-reader-portable.zip with the local app and voices.');
