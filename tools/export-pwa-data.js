(async () => {
  "use strict";
  const APP_KEY = "mfit-v1";
  const DRAFT_KEY = "mfit-active-session-v1";
  const DB_NAME = "mfit-activity-history";
  const STORE_NAME = "activities";

  const parseStoredJson = (key) => {
    const raw = localStorage.getItem(key);
    if (raw == null) return null;
    try { return JSON.parse(raw); }
    catch { throw new Error("Stored M-Fit data is not valid JSON: " + key); }
  };

  const appData = parseStoredJson(APP_KEY);
  if (!appData) throw new Error("M-Fit data was not found in this browser origin.");

  const activeSession = parseStoredJson(DRAFT_KEY);
  const activities = await new Promise((resolve, reject) => {
    if (!("indexedDB" in window)) return reject(new Error("IndexedDB is unavailable."));
    const request = indexedDB.open(DB_NAME);
    request.onerror = () => reject(request.error || new Error("Could not open M-Fit activity history."));
    request.onupgradeneeded = () => {
      request.transaction.abort();
      reject(new Error("M-Fit activity database does not exist on this origin; no data was changed."));
    };
    request.onsuccess = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.close();
        return reject(new Error("M-Fit activity store was not found."));
      }
      const tx = db.transaction(STORE_NAME, "readonly");
      const get = tx.objectStore(STORE_NAME).getAll();
      get.onsuccess = () => resolve(Array.isArray(get.result) ? get.result : []);
      get.onerror = () => reject(get.error || new Error("Could not read activity history."));
      tx.oncomplete = () => db.close();
      tx.onerror = () => { db.close(); reject(tx.error || new Error("Activity history read failed.")); };
    };
  });

  const backup = {
    format: "mfit-pwa-backup",
    version: 1,
    exportedAtMillis: Date.now(),
    appData,
    activeSession,
    activities
  };
  const blob = new Blob([JSON.stringify(backup, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "mfit-pwa-backup-" + new Date().toISOString().replace(/[:.]/g, "-") + ".json";
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  console.info("M-Fit backup exported. Source data was read only and was not modified.", {
    days: Object.keys(appData.days || {}).length,
    activities: activities.length,
    hasActiveSession: !!activeSession
  });
})().catch(error => {
  console.error("M-Fit export failed:", error);
  alert("M-Fit export failed: " + error.message);
});
