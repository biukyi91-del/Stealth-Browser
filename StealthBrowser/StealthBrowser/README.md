# Stealth Browser (Android)

Kotlin + Jetpack Compose privacy browser, Unity of features per PRD v3.0. This repository
contains the **complete application layer**: session/profile engine, fingerprint privacy
engine, network proxy engine with kill switch, tracker filtering, extension package
validation & management, and full Compose UI.

## Honest status of the engine backing

Per the PRD's own acceptance rules (§25: "Privacy dashboard reports actual protection"),
every advertised capability is derived from `WebViewEngineProvider.peek` — the capability
matrix in `docs/ENGINE_CAPABILITIES.md`. **Nothing in the UI claims a protection the
engine does not apply.**

## Build

Requirements: JDK 17, Android Studio Ladybug+ (AGP 8.7), Android SDK 35.

```bash
gradle wrapper --gradle-version 8.9   # wrapper jar is not committed
./gradlew :app:assembleDebug
```

ABI filtering: `arm64-v8a` only (PRD §2). minSdk 29 (Android 10) → targetSdk 35.

## Module map

```
app/src/main/kotlin/com/stealth/browser/
├── engine/
│   ├── core/          EngineController abstraction, capability matrix, configs, events
│   ├── webview/       WebView-backed controller, per-owner data-dir isolation, provider
│   └── privacy/       FingerprintScripts (document-start JS canvas/webgl/audio/webgpu/…)
├── session/           ProfileManager, SessionManager (temp lifecycle), EnginePool,
│                      SitePermissionGate, EngineEventCoordinator, PrivacyCodec
├── network/           ProxyStore (encrypted credentials), NetworkTester, NetworkManager
├── trackers/          TrackerEngine (request filter + event stats), Blocklist
├── extensions/
│   ├── manifest/      ManifestParser (strict), CompatibilityChecker
│   ├── packageinstall/ Zip/CRX3 structural validation (no execution), sha256 audit
│   ├── model/         Manifest models, capability sets, statuses, site access modes
│   └── ExtensionManager.kt   per-profile enable/disable, uninstall, reports
├── downloads/         DownloadCoordinator (platform DownloadManager, temp marker purge)
├── data/
│   ├── db/            Room: profiles, tabs, bookmarks, history, extensions, proxy configs,
│   │                  site permissions, downloads, tracker stats
│   ├── prefs/         SettingsStore (DataStore)
│   └── secure/        SecureCredentialStore (Android Keystore-backed)
└── ui/                Compose screens: home, browser, tabs, profiles, extensions,
                       privacy dashboard, settings, onboarding; Material3 theme
```

## PRD phase implementation status

| Phase | Status in this scaffold |
|-------|------------------------|
| 1 Foundation (Compose, DI, dashboard) | ✅ Implemented |
| 2 Browser engine (navigation/tabs/bookmarks/history/downloads DB) | ✅ Implemented on WebView backing —bookmarks/history/downloads screens live, history auto-recorded per owner, downloads poll platform progress |
| 3 Session engine (temp sessions, profiles, isolation, cleanup) | ✅ Implemented — see honest notes in ENGINE_CAPABILITIES |
| 4 Privacy engine (fingerprint JS) | ✅ Implemented (canvas/webgl/audio/webgpu/fonts/screen/hardware/UA/client-hints/timezone/locale) |
| 5 Network engine (proxy, kill switch, DNS/WebRTC honest reporting) | ✅ Proxy CRUD UI + per-profile binding + connection test + kill switch; authenticated-proxy auth hooks & DoH-in-engine pending Chromium (documented) |
| 6 Extension engine | Validation, install pipeline, manager, permission review, per-extension detail + compatibility report pages, profile-scoped toggles, per-session approval picker (PRD §6 A/B) — ✅; live extension *execution* is a Chromium-runtime item |
| 7 Tracker protection | ✅ Request filter + dashboard statistics |
| 8 UI polish | Browser toolbar: bookmark star, share, desktop-mode toggle, find-on-page, voice search, **live search suggestions** (real engine endpoints; engines without one return none — honest); site-permission dialogs; per-profile fingerprint config screen; tab screen with session-end rows and `requireSessionConfirm` dialog (PRD §17); profile deletion runs a full data cascade behind a confirm dialog; extension store discovery (§13.3) with CWS links; Home secondary actions (bookmarks/history/downloads/network) |
| 9 Testing / 10 Release | 5 JVM unit-test suites (manifest, compat, blocklist, fingerprint invariants, backup codec); CI workflow (`.github/workflows/ci.yml`) runs unit tests + `assembleDebug` and uploads the debug APK; instrumented tests + release signing still need a real build environment |

## Tests

`./gradlew :app:testDebugUnitTest` — JVM tests for manifest parser, compatibility
checker, blocklist matching, fingerprint payload/script invariants, and the privacy
settings codec (round-trip/failure tolerance).

## Repository docs

- `docs/ENGINE_CAPABILITIES.md` — what is real per backing, capability-by-capability
- `docs/CHROMIUM_INTEGRATION.md` — how to swap the WebView backing for a Chromium (Content-API) backing behind the same `EngineController` interface
