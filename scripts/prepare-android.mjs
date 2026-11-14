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


/* Register the native Android step detector plugin in the generated Capacitor project. */
const javaDir = 'android/app/src/main/java/com/mfit/wellness';
await mkdir(javaDir, { recursive: true });
const pluginDir = javaDir + '/plugins';
await mkdir(pluginDir, { recursive: true });
const { readFile, writeFile } = await import('node:fs/promises');
const javaSource = [
'package com.mfit.wellness.plugins;',
'import android.content.Context;',
'import android.hardware.Sensor;',
'import android.hardware.SensorEvent;',
'import android.hardware.SensorEventListener;',
'import android.hardware.SensorManager;',
'import com.getcapacitor.JSObject;',
'import com.getcapacitor.Plugin;',
'import com.getcapacitor.PluginCall;',
'import com.getcapacitor.PluginMethod;',
'import com.getcapacitor.annotation.CapacitorPlugin;',
'@CapacitorPlugin(name = "MfitMotion")',
'public class MfitMotionPlugin extends Plugin implements SensorEventListener {',
'  private SensorManager manager;',
'  private Sensor stepDetector;',
'  private boolean listening = false;',
'  @PluginMethod public void start(PluginCall call) {',
'    if (listening) { JSObject r = new JSObject(); r.put("available", true); call.resolve(r); return; }',
'    manager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);',
'    stepDetector = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);',
'    if (stepDetector == null) { JSObject r = new JSObject(); r.put("available", false); call.resolve(r); return; }',
'    listening = manager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);',
'    JSObject r = new JSObject(); r.put("available", listening); call.resolve(r);',
'  }',
'  @PluginMethod public void stop(PluginCall call) { if (manager != null && listening) manager.unregisterListener(this); listening = false; call.resolve(); }',
'  @Override public void onSensorChanged(SensorEvent event) {',
'    if (event.sensor.getType() != Sensor.TYPE_STEP_DETECTOR) return;',
'    JSObject data = new JSObject(); data.put("count", Math.max(1, Math.round(event.values[0]))); notifyListeners("step", data);',
'  }',
'  @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }',
'  @Override protected void handleOnPause() { if (manager != null && listening) manager.unregisterListener(this); listening = false; }',
'  @Override protected void handleOnResume() { if (manager != null && stepDetector != null && !listening) listening = manager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL); }',
'}'
].join('\n');
await writeFile(pluginDir + '/MfitMotionPlugin.java', javaSource);
const mainPath = javaDir + '/MainActivity.java';
let mainSource = await readFile(mainPath, 'utf8');
if (!mainSource.includes('MfitMotionPlugin')) {
  mainSource = mainSource.replace('import com.getcapacitor.BridgeActivity;', 'import com.getcapacitor.BridgeActivity;\nimport com.mfit.wellness.plugins.MfitMotionPlugin;');
  mainSource = mainSource.replace('public class MainActivity extends BridgeActivity {', 'public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(android.os.Bundle savedInstanceState) {\n        registerPlugin(MfitMotionPlugin.class);\n        super.onCreate(savedInstanceState);\n    }');
  await writeFile(mainPath, mainSource);
}
console.log('Generated native Android step detector plugin.');
