import { Capacitor } from '@capacitor/core';
import { TextToSpeech } from '@capacitor-community/text-to-speech';

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
  }
};
