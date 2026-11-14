import { mkdir } from 'node:fs/promises';
import sharp from 'sharp';

const source = 'icon.svg';
const root = 'android/app/src/main/res';
const densities = [
  ['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]
];
for (const [density, size] of densities) {
  const dir = root + '/mipmap-' + density;
  await mkdir(dir, { recursive: true });
  await sharp(source).resize(size, size).png().toFile(dir + '/ic_launcher.png');
  await sharp(source).resize(size, size).png().toFile(dir + '/ic_launcher_round.png');
}
const adaptive = root + '/mipmap-anydpi-v26';
await mkdir(adaptive, { recursive: true });
await sharp(source).resize(432, 432).png().toFile(root + '/mipmap-xxxhdpi/ic_launcher_foreground.png');
await import('node:fs/promises').then(({writeFile}) => writeFile(adaptive + '/ic_launcher.xml',
  '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/ic_launcher_background"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/></adaptive-icon>'));
const colors = root + '/values';
await mkdir(colors, { recursive: true });
await import('node:fs/promises').then(({writeFile}) => writeFile(colors + '/ic_launcher_background.xml',
  '<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_background">#0B1D2B</color></resources>'));
console.log('Generated Mfit launcher icon resources.');
