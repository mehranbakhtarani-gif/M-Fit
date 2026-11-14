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

/* Generate a punch-hole-aware, tap-to-expand workout island. */
await writeFile(pluginDir + '/MfitIslandPlugin.java', [
'package com.mfit.wellness.plugins;',
'import android.content.Context;',
'import android.content.Intent;',
'import android.graphics.Canvas;',
'import android.graphics.Color;',
'import android.graphics.Paint;',
'import android.graphics.PixelFormat;',
'import android.graphics.PorterDuff;',
'import android.graphics.PorterDuffXfermode;',
'import android.graphics.Typeface;',
'import android.graphics.drawable.GradientDrawable;',
'import android.net.Uri;',
'import android.os.Build;',
'import android.provider.Settings;',
'import android.view.Gravity;',
'import android.view.View;',
'import android.view.WindowManager;',
'import android.widget.LinearLayout;',
'import android.widget.TextView;',
'import com.getcapacitor.JSObject;',
'import com.getcapacitor.Plugin;',
'import com.getcapacitor.PluginCall;',
'import com.getcapacitor.PluginMethod;',
'import com.getcapacitor.annotation.CapacitorPlugin;',
'@CapacitorPlugin(name = "MfitIsland")',
'public class MfitIslandPlugin extends Plugin {',
'  private WindowManager wm; private LinearLayout root,details; private TextView distanceView,timeView,modeView,stateView,pauseView; private PillView pill; private boolean attached=false,expanded=false,paused=false;',
'  private String mode="Walk",distance="0.00",duration="00:00:00";',
'  private int dp(float v){return (int)(v*getContext().getResources().getDisplayMetrics().density+0.5f);}',
'  @PluginMethod public void hasPermission(PluginCall call){JSObject r=new JSObject();r.put("granted",Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(getContext()));call.resolve(r);}',
'  @PluginMethod public void requestPermission(PluginCall call){if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(getContext())){Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getContext().getPackageName()));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(i);}JSObject r=new JSObject();r.put("granted",Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(getContext()));call.resolve(r);}',
'  private GradientDrawable cardBg(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xF51B252B,0xF510171C});d.setCornerRadius(dp(25));d.setStroke(dp(1),0x66FFFFFF);return d;}',
'  private TextView label(String s,int size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setGravity(Gravity.CENTER);return t;}',
'  private void emit(String action){JSObject e=new JSObject();e.put("action",action);notifyListeners("control",e);}',
'  private void build(){if(root!=null)return;wm=(WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);root=new LinearLayout(getContext());root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);root.setClipChildren(false);root.setClipToPadding(false);',
'    pill=new PillView(getContext());pill.setOnClickListener(v->setExpanded(!expanded));root.addView(pill,new LinearLayout.LayoutParams(dp(224),dp(46)));',
'    details=new LinearLayout(getContext());details.setOrientation(LinearLayout.VERTICAL);details.setPadding(dp(16),dp(10),dp(16),dp(12));details.setBackground(cardBg());details.setVisibility(View.GONE);LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(dp(304),-2);cardLp.topMargin=dp(9);root.addView(details,cardLp);',
'    LinearLayout heading=new LinearLayout(getContext());heading.setGravity(Gravity.CENTER_VERTICAL);modeView=label("M-FIT · WALK",12,Color.WHITE);modeView.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);stateView=label("LIVE",10,0xFF73E3A5);heading.addView(modeView,new LinearLayout.LayoutParams(0,dp(26),1));heading.addView(stateView);details.addView(heading);',
'    LinearLayout metrics=new LinearLayout(getContext());metrics.setGravity(Gravity.CENTER);distanceView=label("0.00 km",22,Color.WHITE);timeView=label("00:00:00",22,Color.WHITE);metrics.addView(distanceView,new LinearLayout.LayoutParams(0,dp(48),1));metrics.addView(timeView,new LinearLayout.LayoutParams(0,dp(48),1));details.addView(metrics);',
'    LinearLayout controls=new LinearLayout(getContext());controls.setGravity(Gravity.CENTER);pauseView=label("Ⅱ  PAUSE",11,Color.WHITE);pauseView.setPadding(dp(16),dp(8),dp(16),dp(8));pauseView.setBackground(roundBg(0xFF34434A));pauseView.setOnClickListener(v->{emit("pause");});TextView stop=label("■  STOP",11,0xFFFF9292);stop.setPadding(dp(16),dp(8),dp(16),dp(8));stop.setBackground(roundBg(0xFF3A292B));stop.setOnClickListener(v->emit("stop"));LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-2,-2);c.setMargins(dp(5),dp(8),dp(5),0);controls.addView(pauseView,c);controls.addView(stop,c);details.addView(controls);',
'  }',
'  private GradientDrawable roundBg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}',
'  private void setExpanded(boolean value){expanded=value;if(details!=null)details.setVisibility(value?View.VISIBLE:View.GONE);if(attached&&wm!=null&&root!=null){try{WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();p.width=dp(value?324:240);p.height=-2;wm.updateViewLayout(root,p);}catch(Exception ignored){}}}',
'  private void updateValues(String m,String d,String t,boolean p){mode=m;distance=d;duration=t;paused=p;if(pill!=null)pill.invalidate();if(modeView!=null){modeView.setText("M-FIT · "+mode.toUpperCase());distanceView.setText(distance+" km");timeView.setText(duration);stateView.setText(paused?"PAUSED":"LIVE");stateView.setTextColor(paused?0xFFFFC66D:0xFF73E3A5);pauseView.setText(paused?"▶  RESUME":"Ⅱ  PAUSE");}}',
'  @PluginMethod public void start(PluginCall call){if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(getContext())){JSObject r=new JSObject();r.put("permission",false);call.resolve(r);return;}try{build();updateValues(call.getString("mode","Walk"),call.getString("distance","0.00"),call.getString("duration","00:00:00"),call.getBoolean("paused",false));if(!attached){WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(240),-2,Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=0;if(Build.VERSION.SDK_INT>=28)p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;wm.addView(root,p);attached=true;}JSObject r=new JSObject();r.put("permission",true);r.put("shown",true);call.resolve(r);}catch(Exception ex){JSObject r=new JSObject();r.put("permission",true);r.put("shown",false);r.put("error",ex.getMessage());call.resolve(r);}}',
'  @PluginMethod public void update(PluginCall call){updateValues(call.getString("mode","Walk"),call.getString("distance","0.00"),call.getString("duration","00:00:00"),call.getBoolean("paused",false));call.resolve();}',
'  @PluginMethod public void stop(PluginCall call){try{if(attached&&wm!=null&&root!=null)wm.removeView(root);}catch(Exception ignored){}attached=false;expanded=false;root=null;pill=null;details=null;call.resolve();}',
'  @Override protected void handleOnDestroy(){try{if(attached&&wm!=null&&root!=null)wm.removeView(root);}catch(Exception ignored){}attached=false;root=null;super.handleOnDestroy();}',
'  private class PillView extends View {private final Paint paint=new Paint(3);private final Paint clear=new Paint(3);private final Paint txt=new Paint(3);PillView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));txt.setColor(Color.WHITE);txt.setTextSize(dp(11));txt.setTypeface(Typeface.DEFAULT_BOLD);txt.setTextAlign(Paint.Align.CENTER);}',
'    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setColor(0xF20B1115);c.drawRoundRect(dp(4),dp(3),w-dp(4),h-dp(3),h/2,h/2,paint);c.drawCircle(w/2,h/2,dp(11),clear);txt.setTextSize(dp(10));c.drawText(distance+" km",w*.25f,h*.58f,txt);c.drawText(duration,w*.75f,h*.58f,txt);paint.setColor(paused?0xFFFFC66D:0xFF65DDA7);c.drawCircle(dp(15),h/2,dp(3),paint);}',
'  }',
'}'
].join('\n'));
const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let manifest = await readFile(manifestPath, 'utf8');
if (!manifest.includes('android.permission.SYSTEM_ALERT_WINDOW')) manifest = manifest.replace(/(<manifest\b[^>]*>)/, '$1\n    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />');
await writeFile(manifestPath, manifest);
if (!mainSource.includes('MfitIslandPlugin')) {
  mainSource = mainSource.replace('import com.mfit.wellness.plugins.MfitMotionPlugin;', 'import com.mfit.wellness.plugins.MfitMotionPlugin;\nimport com.mfit.wellness.plugins.MfitIslandPlugin;');
  mainSource = mainSource.replace('registerPlugin(MfitMotionPlugin.class);', 'registerPlugin(MfitMotionPlugin.class);\n        registerPlugin(MfitIslandPlugin.class);');
  await writeFile(mainPath, mainSource);
}
console.log('Generated native floating workout island plugin.');
