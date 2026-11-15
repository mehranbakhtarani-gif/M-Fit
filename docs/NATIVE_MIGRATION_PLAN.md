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
- [x] Add isolated native Android Gradle/Compose app shell and a dedicated CI build workflow (initial scaffold only; build result not yet verified).
- [ ] Add Room, Navigation, repository, and test foundations after the legacy data contract is specified.
- [ ] Implement domain models and unit tests first.
- [x] Implement initial GPS tracking state machine + foreground service + Room persistence (device validation and recovery hardening remain open).
- [ ] Implement remaining feature modules in parity order.
- [ ] Add UI/integration/device tests and verify on physical Android device.
- [ ] Set up signed release build and regression checklist.
- [ ] Decide separately whether PWA remains a maintained client and how cross-client sync should work.

## Code-level findings from first pass
- GPS point acceptance currently rejects fixes with accuracy worse than 25 m and ignores movements below a dynamic 2.0–4.5 m threshold. Native implementation should retain raw fixes, apply documented filtering, and test stationary drift / urban canyon cases rather than silently discarding all lower-quality data.
- Current route sampling and distance calculation are embedded in the main HTML script. The activity state machine, route filter, distance calculation, speed smoothing, calorie estimator, and coach thresholds need to be extracted and covered by deterministic unit tests before porting.
- Step counting is implemented with `DeviceMotionEvent` and browser-side peak detection. This is not a dependable background step-counting solution; native implementation should evaluate Android `Sensor.TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` with permission and device capability fallbacks.
- Session draft uses localStorage key `mfit-active-session-v1`, stores at most the last 5,000 coordinates, and expires after 7 days. Recovery is user-confirmed and resumes paused. Preserve this behavior and define what happens when the coordinate cap is reached.
- Activity history is dual-written to localStorage and IndexedDB. The IndexedDB initialization merges by ID and sorts newest first. Migration must deduplicate legacy records and preserve IDs/timestamps/coordinates; test interrupted writes and IndexedDB-unavailable fallback.
- The food-photo feature currently points to `https://m-fit-food-ai.YOUR-SUBDOMAIN.workers.dev` in `index.html`. This is a placeholder, so the deployed AI endpoint must be configured/verified before treating the feature as production-ready. Worker expects an allowed Origin and an OpenAI API key in server environment.
- Route image generation draws Leaflet map tiles into a canvas and explicitly can fail when the tile provider blocks canvas export (CORS). Native route sharing should render a map snapshot using a supported native map SDK or an approved static-map/image pipeline.
- Current CI creates a fresh Capacitor Android project during each run and only assembles `assembleDebug`. No persistent Android manifest/plugin configuration or release/signing pipeline is checked into the repository.

## Audit conclusion
The safest native path is a separate Kotlin Android app alongside the existing PWA. Do not wrap the existing HTML again and do not delete Capacitor until feature parity and local-data migration have been verified. The first high-risk feature to port and test is activity tracking (GPS, background service, route filtering, session recovery, history persistence); step counting follows as a separate native sensor module.

## Current blockers / unknowns
- Generated Capacitor Android project is not committed, so its exact manifest, SDK levels, plugin registration, and Gradle configuration are not available for audit.
- The food AI worker contains a placeholder deployment origin in the client code; production endpoint/configuration must be verified before moving this feature.
- Existing user data lives on devices, not in this repository. A native migration must not assume GitHub contains those records.
- No successful native Gradle build or physical-device test has been run as part of this audit.

## First implementation gate
Do not begin porting screens until the code-level audit and legacy-data contract are complete. First native milestone should be a clean Kotlin/Compose app shell with Room and automated tests, built on a separate branch, while the current PWA/Capacitor app remains intact.

## Implemented increment — activity domain core

The first Native activity-domain increment is now present under
`native-android/app/src/main/java/com/mfit/nativeapp/core/activity/`:

- `ActivityModels.kt`: activity types, session states, GPS fixes, route points and snapshots.
- `GeoMath.kt`: Haversine distance and a conservative route-point filter. Invalid coordinates, invalid/poor accuracy, out-of-order timestamps, sub-2m jitter and implausible jumps are rejected. Rejected points do not increase route distance.
- `ActivitySessionEngine.kt`: start/pause/resume/finish transitions, elapsed-time accounting that excludes paused intervals, accepted route accumulation, smoothed recent speed and a provisional MET calorie estimate (currently assumes 70 kg; must be connected to profile weight before UI release).
- `app/src/test/.../ActivitySessionEngineTest.kt`: JUnit coverage for distance, poor accuracy, jitter, out-of-order fixes, implausible jumps, pause/resume timing, distance accumulation and invalid transitions.
- Added JUnit 4.13.2 to the app module test dependencies.

Important limitations:
- These are pure domain classes, not yet connected to Android location APIs, a foreground service, Room persistence or Compose screens.
- The 25m accuracy / 2m displacement / 35m/s plausibility defaults are initial conservative parameters and must be validated against real walking, running and cycling sessions on the target phone.
- Calorie output is provisional until profile body mass and activity-specific calibration are wired in.
- CI workflow exists, but no workflow run/build result has been observed yet; do not treat tests as passed until GitHub Actions reports success.

## Implemented increment — Android GPS foreground service and Room persistence

The Native activity path now includes an initial Android integration:

- Added Google Play Services Location dependency and a high-accuracy Fused Location Provider request (2s target interval, 1s minimum interval).
- Added `ActivityLocationService`, declared as a non-exported location foreground service. It creates an ongoing notification, handles start/pause/resume/finish commands, feeds fixes into the domain engine, and stores accepted route points.
- Added runtime requests for precise/coarse location and Android 13+ notification permission. Precise location is required by the current start flow.
- Added Android foreground-service location permissions. No background-location permission is requested: tracking is started from the visible activity and continues as a location foreground service.
- Added Room database `mfit-activities.db` with activity-session and route-point tables, DAO queries, duplicate-safe point insertion, and session history retrieval.
- Added a startup safeguard: if a previous process died with a RUNNING/PAUSED session still in the database, the next launch marks it INTERRUPTED and tells the user that already-written GPS points were preserved. This intentionally avoids fabricating elapsed time across an unknown process-death interval.

Current limitations and release gates:
- Process-death handling preserves recorded points and marks the session interrupted; it does not yet offer seamless continuation of the same live session.
- The Compose Sport screen currently issues service commands and shows local UI state; it is not yet observing the service/database as a reactive source of truth, so UI state can reset after activity recreation.
- Location collection and Room writes have not been verified on a physical device, screen-off, battery saver, permission-denial, approximate-location, or OEM background-management scenarios.
- The service currently uses a provisional 2-second high-accuracy request. Battery use and GPS thresholds need field testing on the target phone.
- The calorie estimate still uses a temporary 70kg assumption; connect profile body mass before showing it as a user-specific result.
- No CI result is visible for the latest commit yet. The existence of source files is not proof of a successful Android build. Do not distribute an APK until GitHub Actions or a local Gradle build succeeds and physical-device tests pass.


## Implemented increment — native activity history and ordered persistence

- Added an activity-history section to the Native Sport screen, backed by the Room session table. It lists saved sessions with localized activity/status labels, date, distance, elapsed time and calorie estimate.
- Selecting a history row loads its persisted GPS points and shows the point count plus first/last coordinates. This is a data inspection step; map rendering and visual route replay are still pending.
- On app recreation, the Sport UI now restores the active/paused display state from the persisted unfinished session when the foreground location service is still active. If the service is no longer active, the session is marked interrupted and retained in history.
- Room writes from the location service now use a single limited-parallelism dispatcher. The service waits for the session row to be inserted before starting location callbacks, preventing route-point inserts from racing ahead of session creation. Point inserts and session status/finish updates share the same serialized write queue.
- History refresh is available manually; the screen reloads its list when opened and after a finish command.

Remaining limitations:
- The history detail currently exposes saved coordinates but does not yet draw or replay the route on a map.
- The UI still uses local Compose state for live pause/resume controls; a fully reactive service-state stream is a separate follow-up.
- Finish writes are asynchronous; if the history list is refreshed before the service finishes its Room update, the user may need to tap refresh again.
- No successful Gradle build, CI run, emulator test, or physical-device test has yet been observed for these latest changes.


## Implemented increment — native route map and replay

- Added osmdroid as a native Android map renderer, using the OpenStreetMap Mapnik tile source; no Google Maps API key is required.
- Added Android INTERNET permission for downloading map tiles. The map credits OpenStreetMap contributors in the UI.
- Activity details now render the stored GPS coordinates as a polyline, with start and current/end markers.
- Added a replay scrubber that progressively reveals the saved route from its first point to its last point. The camera initially fits the route bounds; the user can then pan and zoom normally.
- MapView is connected to Compose lifecycle resume/pause and detached when its composable leaves composition.

Notes and remaining work:
- Map tiles require an internet connection; saved route coordinates remain in Room and are not lost when offline, but an offline tile cache strategy is not implemented.
- OpenStreetMap tile service usage must follow its tile usage policy; do not bulk-prefetch or use it as an unrestricted tile CDN.
- The route scrubber is a manual replay control, not yet a timed animation with playback speed controls.
- Device verification is still required for map gestures, tile loading, attribution visibility, and memory/lifecycle behavior on the target Android devices.
- This implementation does not alter the PWA or existing activity data schema.


## Live activity state synchronization

- The foreground GPS service publishes an in-process `StateFlow<ActivitySnapshot?>` as its live state stream.
- Compose observes the stream with lifecycle-aware collection; elapsed time refreshes every second and accepted GPS fixes refresh distance, current/average speed, calorie estimate, and point count.
- Pause, resume, and finish publish explicit snapshots. The finished snapshot remains available after the service stops so the UI can render the final metrics.
- If the service is unexpectedly destroyed while a session is still running or paused, the stream is cleared rather than leaving stale live metrics on screen.
- Room remains the durable source for session history and track points; the StateFlow is only a live, process-local presentation stream.
- Validation still required: run the newest CI build/tests, then test on a physical Android device for permission denial, pause/resume, screen-off tracking, service termination, and final history refresh.


## Profile body mass and activity calories

- Body mass is stored locally in Android SharedPreferences under the profile namespace; it is not sent to a server.
- Profile accepts 20–250 kg and validates input before saving.
- A newly started activity reads the saved body mass and passes it to the MET-based calorie estimator.
- The estimator uses `MET × body mass (kg) × elapsed hours`; this is an approximation, not a measured energy expenditure.
- When no valid body mass exists, calories remain unavailable (zero internally) and the UI explicitly asks the user to enter weight instead of silently assuming 70 kg.
- Changing profile weight affects the next activity; an activity already in progress retains the weight loaded at its start.


## Native health dashboard — first implementation slice

- Replaced the Native Health placeholder with a local daily dashboard.
- Step count uses Android's hardware `TYPE_STEP_COUNTER` sensor when available and requests `ACTIVITY_RECOGNITION` on Android 10+.
- The daily counter persists accumulated steps and the last hardware counter reading, handling day rollover and sensor-counter reset without discarding already accumulated steps.
- Hydration supports adding/removing one glass, with a daily target display of eight glasses.
- Food log supports manual food name and calorie entry, per-item deletion, and daily calorie totals.
- Daily steps, hydration, and food entries are stored locally in app-private SharedPreferences. No existing PWA localStorage or IndexedDB data is read, changed, or deleted.
- Remaining Health parity work: fasting timer, AI food scan (requires a real secure endpoint), weekly insights, motion fallback for devices without a step-counter sensor, and import/export/migration from PWA data.


### Native fasting timer and completion reminder

- Added persistent fasting sessions with 14, 16, and 18-hour presets.
- The countdown survives app restarts by storing absolute start and target-end timestamps locally.
- An Android AlarmManager reminder is scheduled for the target end time; notification permission can be requested from the Health screen on Android 13+.
- Ending a session cancels its pending reminder.
- The reminder uses an inexact idle-compatible alarm so it does not require the special exact-alarm permission. Delivery can be delayed by Android battery policies.


## Native health reporting and profile history — implementation increment

- Added a seven-day health report aggregating locally stored steps, hydration, food calories, and completed Room activity sessions.
- Daily step values are retained under date-specific keys so opening the dashboard on a later day does not erase previously recorded daily totals.
- Added a current-calendar-month report with daily step progress and monthly totals for hydration, logged food calories, completed activities, and route distance.
- Added local body-weight measurement history. Saving a profile weight records a timestamped measurement; the profile displays the latest 12 entries as a simple trend.
- Historical values that were never recorded by the Native app are not fabricated. PWA history is not yet imported by this increment.

## Native backup and restore — versioned, merge-only

- Added a versioned JSON backup format containing allowlisted Native SharedPreferences stores, Room activity sessions, and route points.
- Added profile actions to export a backup using Android's document picker and restore a previously exported JSON file.
- Restore validates the format/version, validates GPS coordinate ranges, inserts sessions and points without deleting existing rows, and skips preference keys already present on the device.
- In-progress activity sessions in a backup are restored as INTERRUPTED so an imported backup cannot silently restart GPS tracking.
- Backup/restore currently covers Native app data only. A dedicated, verified PWA export/import adapter remains a release gate; do not imply that this file contains legacy PWA localStorage or IndexedDB data.
- Physical-device testing is still required for Android document-provider behavior, backup round-trip, large route histories, and restore interruption.


## Release signing pipeline

- The native Gradle module now supports a release signing configuration supplied only through environment variables; no keystore or passwords are committed to the repository.
- CI always builds and uploads the debug APK. It builds and uploads a release APK when the existing repository Actions secret `MFIT_DEBUG_KEYSTORE_BASE64` is available.
- The persistent keystore uses alias `androiddebugkey` and the standard Android debug-keystore passwords. Only the Base64 keystore is stored as a GitHub secret; no keystore file is committed to the repository.
- The secret should be configured under GitHub repository Settings → Secrets and variables → Actions → New repository secret. The user previously reported adding it, but registration was not independently verified; the next CI run checks whether it is available by whether the signed-release steps execute.
- The release pipeline does not silently fall back to debug signing. If the secret is missing, only the debug APK is produced.
- Upgrade compatibility with any previously installed APK remains unverified until the same original signing certificate is confirmed. A different key cannot update an existing installation with the same application ID.


## Existing APK signing compatibility finding

- Compared the APK signing certificates from the two latest legacy Android APK workflow artifacts (workflow runs 43720393468 and 43720411414). Their SHA-256 certificate fingerprints differ.
- This confirms the legacy debug workflow did not use one persistent signing key. The private key corresponding to the already-installed APK cannot be reconstructed from its public certificate.
- To preserve the user's existing installation and its local data, the Native app now uses a separate package ID, `com.mfit.nativeapp`, while the legacy PWA APK retains `com.mfit.wellness`. They can be installed side-by-side; no uninstall or overwrite is required for migration.
- Future Native releases must use the same persistent release keystore via the four Actions secrets described above. Without those secrets, CI produces only a debug APK and future update compatibility is not guaranteed.
