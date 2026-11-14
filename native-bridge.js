import { Capacitor } from '@capacitor/core';
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import { registerPlugin } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';

const BackgroundGeolocation = registerPlugin('BackgroundGeolocation');

const isNative = Capacitor.isNativePlatform();
window.MfitNativeTTS = {
  isNative,
  async speak(text) {
    return TextToSpeech.speak({
      text,
      lang: 'fa-IR',
      rate: 0.94,
      pitch: 1,
      volume: 1,
      queueStrategy: 0
    });
  },
  async stop() {
    return TextToSpeech.stop();
  },
  async isPersianAvailable() {
    const result = await TextToSpeech.isLanguageSupported({ lang: 'fa-IR' });
    return Boolean(result && result.supported);
  },
  async openPersianInstall() {
    return TextToSpeech.openInstall();
  },
  async startGps(onLocation, onError) {
    try { await LocalNotifications.requestPermissions(); } catch (error) { /* Android may already have notifications disabled; still attempt tracking. */ }
    return BackgroundGeolocation.addWatcher({
      backgroundMessage: 'Mfit is recording your activity. Tap to return to the app.',
      backgroundTitle: 'Mfit activity tracking',
      requestPermissions: true,
      stale: false,
      distanceFilter: 0
    }, (location, error) => {
      if (error) { if (onError) onError(error); return; }
      if (!location || !onLocation) return;
      onLocation({
        coords: {
          latitude: location.latitude,
          longitude: location.longitude,
          accuracy: location.accuracy,
          speed: location.speed
        },
        timestamp: location.time
      });
    });
  },
  async stopGps(id) {
    return BackgroundGeolocation.removeWatcher({ id });
  }
};
