import { mkdir, copyFile, cp } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { build } from 'esbuild';

await mkdir('www', { recursive: true });
await copyFile('index.html', 'www/index.html');
for (const file of ['icon.svg', 'icon-192.svg', 'icon-512.svg', 'manifest.webmanifest', 'sw.js']) {
  if (existsSync(file)) await copyFile(file, 'www/' + file);
}
if (existsSync('assets')) await cp('assets', 'www/assets', { recursive: true });
await build({ entryPoints: ['native-bridge.js'], bundle: true, platform: 'browser', format: 'iife', target: ['es2020'], outfile: 'www/native-bridge.js' });
console.log('Prepared Mfit web assets and native Android bridge.');
