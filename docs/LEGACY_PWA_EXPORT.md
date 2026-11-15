# Exporting data from the legacy M-Fit PWA

The native app uses a separate database and cannot directly read browser storage. This utility creates a portable JSON backup from the **same browser origin** where the existing M-Fit PWA is installed.

## Before exporting

1. Open the existing M-Fit PWA in the browser profile that contains your history.
2. If possible, use desktop Chrome or Edge so the browser developer console is available.
3. Open Developer Tools → Console.
4. Copy the complete contents of `tools/export-pwa-data.js` from this repository and paste it into the console.
5. Run it and approve the browser's paste warning only if you have inspected the script.
6. Save the downloaded `mfit-pwa-backup-*.json` file somewhere safe.

## What it exports

- The `mfit-v1` localStorage application data, including daily logs and legacy history.
- The `mfit-active-session-v1` draft, if present.
- The `mfit-fast-timer-v2` fasting timer state, if present.
- All records in IndexedDB database `mfit-activity-history`, object store `activities`.

The script opens IndexedDB in read-only mode, does not clear or rewrite localStorage or IndexedDB, and downloads one JSON file. It does not upload data to a server.

## Important

- Run the script on the exact origin where the PWA data is stored. Running it on a GitHub repository page or a different browser profile will not access the PWA's storage.
- Keep the exported JSON private; it may contain location tracks and personal activity history.
- Do not uninstall, clear browser storage, or reset the PWA until the native import has been implemented and verified.
- The native app's Profile → Restore Data action accepts this PWA backup format. Import is additive: existing native preferences and records are preserved, duplicate sessions/route points are ignored, and unfinished drafts are restored as interrupted activities. Daily steps, water, food logs, fasting timer state, and weight history are migrated. Original height/waist measurement records are also retained in a local legacy archive for future support. Verify the imported history before deleting or resetting the old PWA.
