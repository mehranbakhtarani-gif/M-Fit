/* Offline Persian Piper TTS for M-Fit.
 * Sherpa-ONNX WebAssembly runtime and the Persian model package are bundled
 * into the Android APK by the Android build workflow.
 */
(function () {
  'use strict';
  let engine = null;
  let initPromise = null;
  let audioContext = null;
  let activeSource = null;

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.async = false;
      script.onload = resolve;
      script.onerror = () => reject(new Error('Could not load Persian TTS runtime: ' + src));
      document.head.appendChild(script);
    });
  }

  function ready() {
    if (engine) return Promise.resolve(engine);
    if (initPromise) return initPromise;
    initPromise = new Promise(async (resolve, reject) => {
      let settled = false;
      const fail = error => {
        if (settled) return;
        settled = true;
        reject(error instanceof Error ? error : new Error(String(error || 'Persian TTS initialization failed')));
      };
      window.Module = {
        locateFile: path => 'assets/tts/' + path,
        onRuntimeInitialized: () => {
          try {
            engine = window.initSherpaOnnxOfflineTts();
            if (!engine || !engine.sampleRate) throw new Error('Persian voice model did not initialize');
            settled = true;
            resolve(engine);
          } catch (error) { fail(error); }
        },
        onAbort: reason => fail(new Error('Persian TTS runtime aborted: ' + reason))
      };
      try {
        await loadScript('assets/tts/sherpa-onnx.js');
        await loadScript('assets/tts/sherpa-onnx-wasm-main.js');
      } catch (error) { fail(error); }
    });
    return initPromise;
  }

  async function speak(text) {
    const tts = await ready();
    const clean = String(text || '').trim().replace(/\u200c/g, ' ');
    if (!clean) return;
    if (!audioContext) audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: tts.sampleRate });
    await audioContext.resume();
    if (activeSource) {
      try { activeSource.stop(); } catch (_) {}
      activeSource = null;
    }
    const audio = tts.generate({ text: clean, sid: 0, speed: 1 });
    const buffer = audioContext.createBuffer(1, audio.samples.length, audio.sampleRate || tts.sampleRate);
    buffer.copyToChannel(audio.samples, 0);
    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(audioContext.destination);
    activeSource = source;
    return new Promise(resolve => {
      source.onended = () => {
        if (activeSource === source) activeSource = null;
        resolve();
      };
      source.start();
    });
  }

  function stop() {
    if (activeSource) {
      try { activeSource.stop(); } catch (_) {}
      activeSource = null;
    }
  }

  window.MfitWasmTTS = { ready, speak, stop };
})();
