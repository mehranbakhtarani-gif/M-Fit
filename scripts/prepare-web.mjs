import { mkdir, copyFile, cp } from 'node:fs/promises';
import { existsSync } from 'node:fs';

await mkdir('www', { recursive: true });
await copyFile('index.html', 'www/index.html');
for (const file of ['icon.svg', 'icon-192.svg', 'icon-512.svg', 'manifest.webmanifest', 'sw.js']) {
  if (existsSync(file)) await copyFile(file, 'www/' + file);
}
if (existsSync('assets')) await cp('assets', 'www/assets', { recursive: true });
console.log('Prepared Mfit web assets.');
