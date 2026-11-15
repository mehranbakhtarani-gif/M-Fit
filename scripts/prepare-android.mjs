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
/* Native Android MediaStore save + ACTION_SEND share integration for route PNGs. */
const sharePlugin = String.raw`package com.mfit.wellness.plugins;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ClipData;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;

@CapacitorPlugin(name = "MfitShare")
public class MfitSharePlugin extends Plugin {
  private Uri writeImage(PluginCall call) throws Exception {
    if (Build.VERSION.SDK_INT < 29) throw new Exception("Android 10 or newer is required to save route images.");
    String encoded = call.getString("data");
    if (encoded == null || encoded.isEmpty()) throw new Exception("Route image data is empty.");
    byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
    String name = call.getString("fileName", "mfit-route.png").replaceAll("[^A-Za-z0-9._-]", "_");
    if (!name.toLowerCase().endsWith(".png")) name += ".png";
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/M-FIT");
    values.put(MediaStore.Images.Media.IS_PENDING, 1);
    Uri uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new Exception("Android could not create the image file.");
    try (OutputStream stream = getContext().getContentResolver().openOutputStream(uri)) {
      if (stream == null) throw new Exception("Could not open the image file.");
      stream.write(bytes);
      stream.flush();
    } catch (Exception error) {
      getContext().getContentResolver().delete(uri, null, null);
      throw error;
    }
    ContentValues ready = new ContentValues();
    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
    getContext().getContentResolver().update(uri, ready, null, null);
    return uri;
  }
  @PluginMethod public void saveImage(PluginCall call) {
    try {
      Uri uri = writeImage(call);
      JSObject result = new JSObject();
      result.put("saved", true);
      result.put("uri", uri.toString());
      call.resolve(result);
    } catch (Exception error) { call.reject("Could not save route image: " + error.getMessage(), error); }
  }
  @PluginMethod public void shareImage(PluginCall call) {
    try {
      Uri uri = writeImage(call);
      Intent send = new Intent(Intent.ACTION_SEND);
      send.setType("image/png");
      send.putExtra(Intent.EXTRA_STREAM, uri);
      send.setClipData(ClipData.newUri(getContext().getContentResolver(), "M-FIT route", uri));
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      getActivity().startActivity(Intent.createChooser(send, "Share M-FIT route"));
      JSObject result = new JSObject();
      result.put("shared", true);
      call.resolve(result);
    } catch (Exception error) { call.reject("Could not open Android sharing: " + error.getMessage(), error); }
  }
}`;
await writeFile(pluginDir + '/MfitSharePlugin.java', sharePlugin);
const { readFile, writeFile } = await import('node:fs/promises');
const javaSource = [
'package com.mfit.wellness.plugins;',
'import android.content.Context;',
'import android.Manifest;',
'import android.os.Build;',
'import android.hardware.Sensor;',
'import android.hardware.SensorEvent;',
'import android.hardware.SensorEventListener;',
'import android.hardware.SensorManager;',
'import com.getcapacitor.JSObject;',
'import com.getcapacitor.Plugin;',
'import com.getcapacitor.PluginCall;',
'import com.getcapacitor.PluginMethod;',
'import com.getcapacitor.PermissionState;',
'import com.getcapacitor.annotation.CapacitorPlugin;',
'import com.getcapacitor.annotation.Permission;',
'import com.getcapacitor.annotation.PermissionCallback;',
'@CapacitorPlugin(name = "MfitMotion", permissions = { @Permission(alias = "activityRecognition", strings = { Manifest.permission.ACTIVITY_RECOGNITION }) })',
'public class MfitMotionPlugin extends Plugin implements SensorEventListener {',
'  private SensorManager manager;',
'  private Sensor stepDetector;',
'  private boolean listening = false;',
'  @PluginMethod public void start(PluginCall call) {',
'    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && getPermissionState("activityRecognition") != PermissionState.GRANTED) { requestPermissionForAlias("activityRecognition", call, "activityRecognitionPermsCallback"); return; }',
'    startSensor(call);',
'  }',
'  @PermissionCallback private void activityRecognitionPermsCallback(PluginCall call) {',
'    if (getPermissionState("activityRecognition") == PermissionState.GRANTED) startSensor(call); else call.reject("Activity recognition permission is required for automatic step counting.");',
'  }',
'  private void startSensor(PluginCall call) {',
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


/* Generate Android 16 Live Update support for workout sessions. */
const livePlugin = String.raw`package com.mfit.wellness.plugins;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "MfitLiveUpdate")
public class MfitLiveUpdatePlugin extends Plugin {
  private static final String CHANNEL = "mfit_live_activity";
  private static final int ID = 2601;
  private NotificationManager manager;
  private void ensureChannel() {
    manager = (NotificationManager)getContext().getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
      NotificationChannel channel = new NotificationChannel(CHANNEL, "Live workout", NotificationManager.IMPORTANCE_DEFAULT);
      channel.setDescription("Live status for an active M-FIT workout");
      channel.setShowBadge(false);
      manager.createNotificationChannel(channel);
    }
  }
  private Notification makeNotification(String mode, String distance, String duration, int seconds, boolean paused) {
    Context context = getContext();
    Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    PendingIntent content = launch == null ? null : PendingIntent.getActivity(context, ID, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String title = "M-FIT · " + mode;
    String status = (paused ? "PAUSED" : "IN PROGRESS") + " · " + distance + " km";
    Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
    builder.setSmallIcon(context.getApplicationInfo().icon)
      .setContentTitle(title)
      .setContentText(status + " · " + duration)
      .setStyle(new Notification.BigTextStyle().bigText(status + " · " + duration))
      .setCategory("workout")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(true)
      .setWhen(System.currentTimeMillis() - Math.max(0, seconds) * 1000L)
      .setUsesChronometer(!paused)
      .setContentIntent(content)
      .setAutoCancel(false);
    if (Build.VERSION.SDK_INT >= 36) {
      // Android 16 QPR APIs may not exist in the compile SDK; use reflection
      // so the APK remains buildable on stable SDK 36 while using them when present.
      try {
        builder.getClass().getMethod("setRequestPromotedOngoing", boolean.class).invoke(builder, true);
        builder.getClass().getMethod("setShortCriticalText", String.class).invoke(builder, paused ? "PAUSED" : "M-FIT");
      } catch (Exception ignored) { }
    }
    return builder.build();
  }
  @PluginMethod public void start(PluginCall call) { update(call); }
  @PluginMethod public void update(PluginCall call) {
    try {
      ensureChannel();
      String mode = call.getString("mode", "Walk");
      String distance = call.getString("distance", "0.00");
      String duration = call.getString("duration", "00:00:00");
      int seconds = call.getInt("seconds", 0);
      boolean paused = call.getBoolean("paused", false);
      if (Build.VERSION.SDK_INT >= 33 && !manager.areNotificationsEnabled()) {
        JSObject r = new JSObject(); r.put("shown", false); r.put("reason", "notifications_disabled"); call.resolve(r); return;
      }
      manager.notify(ID, makeNotification(mode, distance, duration, seconds, paused));
      JSObject r = new JSObject(); r.put("shown", true); r.put("promotedEligible", Build.VERSION.SDK_INT >= 36); call.resolve(r);
    } catch (Exception error) {
      JSObject r = new JSObject(); r.put("shown", false); r.put("error", error.getMessage()); call.resolve(r);
    }
  }
  @PluginMethod public void stop(PluginCall call) {
    try { ensureChannel(); manager.cancel(ID); } catch (Exception ignored) {}
    call.resolve();
  }
  @PluginMethod public void canPromote(PluginCall call) {
    JSObject r = new JSObject();
    r.put("api36", Build.VERSION.SDK_INT >= 36);
    NotificationManager nm = (NotificationManager)getContext().getSystemService(Context.NOTIFICATION_SERVICE);
    r.put("notificationsEnabled", Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled());
    if (Build.VERSION.SDK_INT >= 36) r.put("canPostPromoted", nm.canPostPromotedNotifications());
    call.resolve(r);
  }
}`;
await writeFile(pluginDir + '/MfitLiveUpdatePlugin.java', livePlugin);
const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let manifest = await readFile(manifestPath, 'utf8');
for (const permission of ['android.permission.ACTIVITY_RECOGNITION', 'android.permission.POST_NOTIFICATIONS', 'android.permission.POST_PROMOTED_NOTIFICATIONS']) {
  if (!manifest.includes(permission)) manifest = manifest.replace(/(<manifest\b[^>]*>)/, '$1\n    <uses-permission android:name="' + permission + '" />');
}
await writeFile(manifestPath, manifest);
if (!mainSource.includes('MfitLiveUpdatePlugin')) {
  mainSource = mainSource.replace('import com.mfit.wellness.plugins.MfitMotionPlugin;', 'import com.mfit.wellness.plugins.MfitMotionPlugin;\nimport com.mfit.wellness.plugins.MfitLiveUpdatePlugin;\nimport com.mfit.wellness.plugins.MfitSharePlugin;');
  mainSource = mainSource.replace('registerPlugin(MfitMotionPlugin.class);', 'registerPlugin(MfitMotionPlugin.class);\n        registerPlugin(MfitLiveUpdatePlugin.class);\n        registerPlugin(MfitSharePlugin.class);');
}
await writeFile(mainPath, mainSource);
console.log('Generated native Live Update integration and removed overlay island.');
