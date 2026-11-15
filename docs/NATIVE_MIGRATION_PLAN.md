# M-Fit Native Migration — Audit and Plan

## Decision
Target: fully native Android application using Kotlin + Jetpack Compose.
Current strategy: incremental migration, not a rewrite-in-place. Preserve the current web/PWA implementation and all user data while the native app is built in a separate Android source tree.

## Baseline audit (GitHub main, 2026-11-15)
- Repository root is web-first: `index.html` (~156 KB), `native-bridge.js`, `package.json`, `capacitor.config.json`, `manifest.webmanifest`, `sw.js`, assets, scripts, and a food AI worker.
- `package.json` uses Capacitor 7.4.3, Capacitor Android, text-to-speech, background-geolocation, and local notifications.
- `capacitor.config.json` points at `www`; it configures a Capacitor wrapper, not a native Compose UI.
- There is no committed `android/` project in the repository root. `.github/workflows/android-apk.yml` generates it during CI with `npx cap add android`, then builds a debug APK only.
- Main UI and application logic are embedded in the large `index.html`; Leaflet is used for maps.
- `native-bridge.js` exposes native TTS and background GPS to JavaScript. The rest of the UI still runs in the WebView.
- Daily/profile data uses localStorage key `mfit-v1`; activity history uses IndexedDB database `mfit-activity-history`, object store `activities`. Activity drafts are checkpointed to localStorage and restored after reload.
- PWA has a service worker and web manifest. Current APK and PWA are therefore two delivery shells for substantially the same web app.

## Feature inventory to preserve
1. Three primary tabs: Health, Sport, Profile.
2. Activity tracking: Walk, Run, Ride; start/pause/resume/finish; elapsed time; distance; speed/pace; calories; GPS route points; active-session draft recovery.
3. Map: Leaflet, street/satellite layers, current-position centering, route polyline, current marker, route export/share image.
4. Motion step counter using DeviceMotionEvent, step and calorie totals, daily and weekly summaries.
5. Health dashboard: hydration counter/reset, food diary/manual entry, food-photo AI analysis.
6. Fasting timer: configurable target, start-time picker, elapsed/remaining time, completion notification.
7. Profile: display name/photo, body measurements and trend chart, activity totals and weekly report.
8. Voice coach / Persian text-to-speech, haptic feedback, local notifications.
9. Persistence and recovery: daily records, activity history, active activity draft, profile, measurements, settings.
10. PWA offline shell and web deployment must remain supported unless explicitly retired later.

## Migration architecture
- :app — Compose screens, navigation, theme, Android entry points.
- :core:model — typed domain models and validation.
- :core:database — Room entities, DAOs, migrations.
- :core:data — repositories, import/export, data migration.
- :core:location — fused location provider, foreground service, permission handling, route sampling.
- :core:activity — tracking state machine, distance/speed/pace/calorie calculations.
- :core:sensors — step counter / motion sensor abstraction.
- :core:notifications — notifications and fasting reminders.
- :core:voice — Android TextToSpeech and coach.
- :feature:health — dashboard, hydration, food diary, fasting.
- :feature:sport — live tracking, map, history, route sharing.
- :feature:profile — profile, body measurements, trends, reports.
- Keep PWA as a separate web client. Share API contracts/domain rules where practical; do not attempt to share Compose UI with PWA.

## Non-negotiable safety rules
- Do not delete, overwrite, or reset existing localStorage/IndexedDB data.
- Do not change production main branch while building the native foundation.
- Before migrating data, define and test an explicit export/import format and idempotent migration.
- Do not claim GPS background reliability until tested on real devices with screen off, battery optimization, permission denial/regrant, app process death, and Android 13+ notification permission.
- Do not ship release APK until signing, minification rules, permissions, privacy disclosures, and backup/restore have been verified.

## Work sequence
- [x] Create isolated branch for native migration planning.
- [x] Record baseline architecture and feature inventory.
- [ ] Complete code-level audit of tracking, calculations, persistence, food AI, fasting, and profile flows.
- [ ] Specify data schema and legacy data export/import contract.
- [ ] Add native Android Gradle project (Kotlin, Compose, Room, Navigation, test setup) without touching current web app.
- [ ] Implement domain models and unit tests first.
- [ ] Implement GPS tracking state machine + foreground service + Room persistence.
- [ ] Implement remaining feature modules in parity order.
- [ ] Add UI/integration/device tests and verify on physical Android device.
- [ ] Set up signed release build and regression checklist.
- [ ] Decide separately whether PWA remains a maintained client and how cross-client sync should work.

## Current blockers / unknowns
- Generated Capacitor Android project is not committed, so its exact manifest, SDK levels, plugin registration, and Gradle configuration are not available for audit.
- The food AI worker contains a placeholder deployment origin in the client code; production endpoint/configuration must be verified before moving this feature.
- Existing user data lives on devices, not in this repository. A native migration must not assume GitHub contains those records.
- No successful native Gradle build or physical-device test has been run as part of this audit.

## First implementation gate
Do not begin porting screens until the code-level audit and legacy-data contract are complete. First native milestone should be a clean Kotlin/Compose app shell with Room and automated tests, built on a separate branch, while the current PWA/Capacitor app remains intact.
