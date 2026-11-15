import { Capacitor } from '@capacitor/core';
import { TextToSpeech } from '@capacitor-community/text-to-speech';
import { registerPlugin } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';

const BackgroundGeolocation = registerPlugin('BackgroundGeolocation');
const MfitMotion = registerPlugin('MfitMotion');
const MfitLiveUpdate = registerPlugin('MfitLiveUpdate');
const MfitShare = registerPlugin('MfitShare');
const isNative = Capacitor.isNativePlatform();

async function getPersianTtsInfo() {
  const normalize = value => String(value || '').trim().toLowerCase().replace('_', '-');
  let lastInfo = { available: false, language: null, voiceName: null, languages: [], voices: [] };

  // Android's TextToSpeech service can take a moment to initialize after app launch.
  // Retry empty results before telling the user that Persian is not installed.
  for (let attempt = 0; attempt < 4; attempt++) {
    let languages = [];
    let voices = [];
    try {
      const result = await TextToSpeech.getSupportedLanguages();
      languages = Array.isArray(result?.languages) ? result.languages : [];
    } catch (error) { /* Some engines expose voices but not a language list. */ }
    try {
      const result = await TextToSpeech.getSupportedVoices();
      voices = Array.isArray(result?.voices) ? result.voices : [];
    } catch (error) { /* Fall back to the engine language list. */ }

    const persianVoice = voices.find(voice => normalize(voice.lang) === 'fa-ir')
      || voices.find(voice => normalize(voice.lang).startsWith('fa-'))
      || voices.find(voice => normalize(voice.lang) === 'fa');
    const persianLanguage = languages.find(language => normalize(language) === 'fa-ir')
      || languages.find(language => normalize(language).startsWith('fa-'))
      || languages.find(language => normalize(language) === 'fa');
    const language = persianVoice?.lang || persianLanguage || null;
    lastInfo = {
      available: Boolean(language),
      language,
      voiceName: persianVoice?.name || null,
      languages,
      voices: voices.filter(voice => normalize(voice.lang).startsWith('fa'))
    };
    if (lastInfo.available) return lastInfo;
    if (attempt < 3) await new Promise(resolve => setTimeout(resolve, 500));
  }
  return lastInfo;
}

window.MfitNativeTTS = {
  isNative,
  async speak(text) {
    const info = await getPersianTtsInfo();
    if (!info.available) {
      const error = new Error('PERSIAN_VOICE_NOT_INSTALLED');
      error.code = 'PERSIAN_VOICE_NOT_INSTALLED';
      throw error;
    }
    return TextToSpeech.speak({
      text,
      lang: info.language,
      rate: 0.94,
      pitch: 1,
      volume: Math.max(0, Math.min(1, Number(window.MfitVoiceVolume ?? 0.75))),
      queueStrategy: 0
    });
  },
  async stop() {
    return TextToSpeech.stop();
  },
  async getPersianVoiceInfo() {
    return getPersianTtsInfo();
  },
  async isPersianAvailable() {
    const info = await getPersianTtsInfo();
    return info.available;
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

window.MfitNativeGPS = {
  isNative,
  startGps: (...args) => window.MfitNativeTTS.startGps(...args),
  stopGps: (id) => window.MfitNativeTTS.stopGps(id)
};

window.MfitNativeMotion = {
  isNative,
  listener: null,
  async start(onStep) {
    if (!isNative) return { available: false };
    if (!this.listener) this.listener = await MfitMotion.addListener('step', event => {
      if (onStep) onStep(Math.max(1, Number(event?.count) || 1));
    });
    return MfitMotion.start();
  },
  async stop() {
    try { await MfitMotion.stop(); } catch (error) { /* Best-effort stop. */ }
    if (this.listener) { await this.listener.remove(); this.listener = null; }
  }
};


window.MfitLiveUpdate = {
  isNative,
  async canPromote(){ if(!isNative)return {api36:false,notificationsEnabled:false}; return MfitLiveUpdate.canPromote(); },
  async start(data){ if(!isNative)return {shown:false}; return MfitLiveUpdate.start(data); },
  async update(data){ if(isNative)return MfitLiveUpdate.update(data); },
  async stop(){ if(isNative)return MfitLiveUpdate.stop(); }
};


window.MfitNativeShare = {
  isNative,
  async saveImage(data, fileName = 'mfit-route.png') {
    if (!isNative) throw new Error('Native Android storage is unavailable');
    return MfitShare.saveImage({ data, fileName });
  },
  async shareImage(data, fileName = 'mfit-route.png') {
    if (!isNative) throw new Error('Native Android sharing is unavailable');
    return MfitShare.shareImage({ data, fileName });
  }
};

window.MfitNativeBackup = {
  isNative,
  async saveBackup(data, fileName = 'M-FIT-backup.json') {
    if (!isNative) throw new Error('Native Android storage is unavailable');
    return MfitShare.saveBackup({ data, fileName });
  }
};
