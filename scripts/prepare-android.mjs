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

/* Generate the Android floating Dynamic-Island-style workout capsule. */
await writeFile(pluginDir + '/MfitIslandPlugin.java', "package com.mfit.wellness.plugins;\nimport android.content.Context;\nimport android.content.Intent;\nimport android.graphics.Color;\nimport android.graphics.PixelFormat;\nimport android.graphics.Typeface;\nimport android.graphics.drawable.GradientDrawable;\nimport android.net.Uri;\nimport android.os.Build;\nimport android.provider.Settings;\nimport android.view.Gravity;\nimport android.view.WindowManager;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;\nimport com.getcapacitor.JSObject;\nimport com.getcapacitor.Plugin;\nimport com.getcapacitor.PluginCall;\nimport com.getcapacitor.PluginMethod;\nimport com.getcapacitor.annotation.CapacitorPlugin;\n@CapacitorPlugin(name = \"MfitIsland\")\npublic class MfitIslandPlugin extends Plugin {\n  private WindowManager wm; private LinearLayout panel; private TextView distanceView, timeView, modeView, stateView; private boolean attached=false;\n  private int dp(float v){return (int)(v*getContext().getResources().getDisplayMetrics().density+0.5f);}\n  @PluginMethod public void hasPermission(PluginCall call){ JSObject r=new JSObject(); r.put(\"granted\",Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(getContext())); call.resolve(r); }\n  @PluginMethod public void requestPermission(PluginCall call){\n    if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(getContext())){Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse(\"package:\"+getContext().getPackageName())); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i);}\n    JSObject r=new JSObject(); r.put(\"granted\",Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(getContext())); call.resolve(r);\n  }\n  private GradientDrawable bg(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xE91D252B,0xE912171C});d.setCornerRadius(dp(28));d.setStroke(dp(1),0x88FFFFFF);return d;}\n  private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.CENTER);return t;}\n  private void build(){\n    if(panel!=null)return; wm=(WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);\n    panel=new LinearLayout(getContext());panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(9),dp(14),dp(9));panel.setBackground(bg());\n    LinearLayout top=new LinearLayout(getContext());top.setGravity(Gravity.CENTER_VERTICAL);\n    modeView=text(\"M-FIT  ·  WALK\",11,Color.WHITE); modeView.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);\n    stateView=text(\"LIVE\",9,0xFF73E3A5); stateView.setPadding(dp(8),0,0,0);top.addView(modeView,new LinearLayout.LayoutParams(0,dp(22),1));top.addView(stateView);panel.addView(top);\n    LinearLayout metrics=new LinearLayout(getContext());metrics.setGravity(Gravity.CENTER_VERTICAL);\n    distanceView=text(\"0.00 km\",19,Color.WHITE);timeView=text(\"00:00:00\",19,Color.WHITE);\n    metrics.addView(distanceView,new LinearLayout.LayoutParams(0,dp(30),1));metrics.addView(timeView,new LinearLayout.LayoutParams(0,dp(30),1));panel.addView(metrics);\n    LinearLayout controls=new LinearLayout(getContext());controls.setGravity(Gravity.CENTER);\n    TextView pause=text(\"Ⅱ  PAUSE\",10,0xFFFFFFFF);pause.setPadding(dp(12),dp(7),dp(12),dp(7));pause.setOnClickListener(v->{JSObject e=new JSObject();e.put(\"action\",\"pause\");notifyListeners(\"control\",e);});\n    TextView stop=text(\"■  STOP\",10,0xFFFF8585);stop.setPadding(dp(12),dp(7),dp(12),dp(7));stop.setOnClickListener(v->{JSObject e=new JSObject();e.put(\"action\",\"stop\");notifyListeners(\"control\",e);});\n    controls.addView(pause);controls.addView(stop);panel.addView(controls);\n  }\n  @PluginMethod public void start(PluginCall call){\n    if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(getContext())){JSObject r=new JSObject();r.put(\"permission\",false);call.resolve(r);return;}\n    try{build(); updateValues(call.getString(\"mode\",\"Walk\"),call.getString(\"distance\",\"0.00\"),call.getString(\"duration\",\"00:00:00\"),call.getBoolean(\"paused\",false));\n      if(!attached){WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(30);wm.addView(panel,p);attached=true;}\n      JSObject r=new JSObject();r.put(\"permission\",true);r.put(\"shown\",true);call.resolve(r);\n    }catch(Exception ex){JSObject r=new JSObject();r.put(\"permission\",true);r.put(\"shown\",false);r.put(\"error\",ex.getMessage());call.resolve(r);}\n  }\n  private void updateValues(String mode,String distance,String duration,boolean paused){if(panel==null)return;modeView.setText(\"M-FIT  ·  \"+mode.toUpperCase());distanceView.setText(distance+\" km\");timeView.setText(duration);stateView.setText(paused?\"PAUSED\":\"LIVE\");stateView.setTextColor(paused?0xFFFFC66D:0xFF73E3A5);}\n  @PluginMethod public void update(PluginCall call){updateValues(call.getString(\"mode\",\"Walk\"),call.getString(\"distance\",\"0.00\"),call.getString(\"duration\",\"00:00:00\"),call.getBoolean(\"paused\",false));call.resolve();}\n  @PluginMethod public void stop(PluginCall call){try{if(attached&&wm!=null&&panel!=null)wm.removeView(panel);}catch(Exception ignored){}attached=false;panel=null;call.resolve();}\n  @Override protected void handleOnDestroy(){try{if(attached&&wm!=null&&panel!=null)wm.removeView(panel);}catch(Exception ignored){}attached=false;panel=null;super.handleOnDestroy();}\n}");
const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let manifest = await readFile(manifestPath, 'utf8');
if (!manifest.includes('android.permission.SYSTEM_ALERT_WINDOW')) manifest = manifest.replace(/(<manifest\b[^>]*>)/, '$1\n    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />');
await writeFile(manifestPath, manifest);
if (!mainSource.includes('MfitIslandPlugin')) {
  mainSource = mainSource.replace('import com.mfit.wellness.plugins.MfitMotionPlugin;', 'import com.mfit.wellness.plugins.MfitMotionPlugin;\\nimport com.mfit.wellness.plugins.MfitIslandPlugin;');
  mainSource = mainSource.replace('registerPlugin(MfitMotionPlugin.class);', 'registerPlugin(MfitMotionPlugin.class);\\n        registerPlugin(MfitIslandPlugin.class);');
  await writeFile(mainPath, mainSource);
}
console.log('Generated native floating workout island plugin.');
