# Engine capability matrix (WebView backing)

Source of truth: `WebViewEngineProvider.peek` (`EngineCapability` set). The UI and the
privacy dashboard read ONLY from this matrix — Sourced from actual behavior, not intent.

| PRD § | Capability | Status | Detail |
|-------|-----------|--------|--------|
| §8 | Fingerprint scripting (canvas, WebGL, audio, WebGPU, fonts, screen, hardware, UA, client hints, timezone, locale) | ✅ REAL | `WebViewCompat.addDocumentStartJavaScript` injects before page scripts; per-session coherent noise (no randomization churn). WebGPU masked by rejecting `requestAdapter` while the API is exposed — never faked when absent. |
| §3/§7 | Profile storage isolation | ✅ REAL (measured) | `WebView.setDataDirectorySuffix(ownerId)` before creation — distinct cookies/cache/site-storage per profile and per temporary session. |
| §7 | Temporary session cleanup | ✅ REAL (documented consequence) | Deletes the session data dir + purges DB remnants; process-global managers (CookieManager/WebStorage/WebViewDatabase) are wiped conservatively — privacy-first direction, honestly reported, and noted as a Chromium integration point for per-suffix targeting. |
| §9 | Proxy | ✅ REAL | androidx `ProxyController`; implicit bypass rules removed. A dead proxy halts navigation and raises `ProxyFailure` → kill switch. **No silent direct fallback is possible** (there is no direct route). SOCKS5: unsupported — reported as such, never half-implemented. |
| §9 | Engine kill switch | ✅ REAL at event level | Proxy failure → NetworkManager engages kill switch; BrowserScreen blocks address entry with failure banner and retry. OS-level socket kill is not possible above the engine. |
| §10 | DoH in engine | ❌ NOT AVAILABLE | Cannot set secure DNS on the WebView backing. Testers probe provider reachability; UI labels DoH as unsupported instead of pretending. |
| §11 | WebRTC | ⚠️ PARTIAL (real) | `getUserMedia` runs through the permission gate (default deny). Software-camera API exposure of WebRTC statistics is inaccessible; capture prevention is enforced, local enum exposure limited. Maintained compat per PRD §11. |
| §12 | Tracker filtering | ✅ REAL | `shouldInterceptRequest` host-suffix + path rules, per-list counters on the dashboard. |
| §13 | Extension runtime (background workers, content scripts, webRequest, tabs API execution) | ❌ PENDING | Requires the Chromium extension runtime. What IS real today: package validation (zip-slip-safe, CRX3 parse), strict manifest parsing, compatibility analysis (§13.4/13.10), permission summary + review flow, per-profile enable/disable persistence, uninstall, storage isolation directory, and NO execution at install time. |
| §8 | Honest reporting guarantee | ✅ enforced | Any status that cannot be enforced is rendered "(unsupported by current engine)". |

## Adding a Chromium backing later

Implement `EngineController` + extend `WebViewEngineProvider`'s capability set (see
`docs/CHROMIUM_INTEGRATION.md`). UI code reads capabilities — feature reveals itself
automatically once a capability is declared.
