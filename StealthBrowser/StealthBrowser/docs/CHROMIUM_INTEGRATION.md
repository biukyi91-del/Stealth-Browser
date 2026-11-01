# Chromium integration plan

The `EngineController` abstraction (`engine/core/EngineController.kt`) is the seam for
replacing the WebView backing with a custom Chromium (Content API) build. All UI and
session code targets the interface; ONLY `engine/webview/` is WebView-specific.

## Why a custom Chromium build (PRD §1)

A genuine Chromium Android browser gives: real DoH (`net::URLRequest` with
`NetworkChangeNotifier`-managed resolver), true SOCKS5, engine-level WebRTC HMAC/ICE
hardening (`webrtc::PeerConnection::kDisabledIceRestartPolicy` etc.), process wide
kill switch (`NetworkService` offline crash), and the extension runtime (MV3 service
workers, declarativeNetRequest). This plan maps each PRD gap to a Chromium subsystem.

## Build pipeline

1. `fetch --nohooks android` (Chromium depot ~100 GB), then
   `gclient runhooks`.
2. Target: `content_shell` (Content API embedding) — closer to a full embedder than
   WebView and the correct substrate for extension support.
3. GN config: `target_os = "android"`, `target_cpu = "arm64"`,
   `is_component_build = false`, `use_java` minimal, enable
   `enable_extensions = true` extended to the Android build (extensions subsystem is
   currently desktop-only in upstream — the ports require porting //extensions loops).
4. Build: `ninja -C out/Release content_shell`. CI: 8–12 h per full build, use
   component_cache (sccache) to keep incremental edits near 20–40 min.
5. Ship `libcontent_shell.so` + Java glue into an SDK `engine-android` module;
   Kotlin side consumes the existing `EngineController` interface.

## Feature mapping

| PRD item | Chromium implementation point |
|---|---|
| Per-profile isolation | Multiple `ProfileImpl`s→ `off-the-record` contexts + per-profile `BrowserContext`s with distinct `UserDataDir`. |
| Temp sessions | Blink `SessionStorage`, `NetworkContext` with dead-cookie jars; `ProfileAttributesEntry::Ephemeral` approach; wipe via `NetworkContext::Cleanup`. |
| Fingerprint scripting | `content/public/renderer` Blink `V8 injection` — inject at document-start similarly to the Web layer, extending the equivalent of `BlinkExecutionContext` protection. |
| Proxy + no fallback | `net::ProxyResolutionService` with a `ProxyConfigService` and `ProxyDelegate` that clamps `fallbacks` (PRD §9). |
| DoH | `net::DnsClient` DoH providers (Cloudflare/Google + custom DoH template) surfaced in Settings. |
| WebRTC | `blink::WebRTC` gating policies: `webrtc::PeerConnection::kDefaultIceConfig` with `ice_transports_type = kRelay` and mDNS obfuscation (`kMdnsObfuscation`) for local exposure (PRD §11). |
| Tracker protection | `DeclarativeNetRequest` (DV monitoring), two thirds-party-cookie restriction registry (PRD §12). |
| Extensions | `//extensions` runtime: `Manifest v3` service workers, `extensions/browser/extension_registry`, `content::WebUI extension_install flow`; port CWS install verification (PRD §13.1–13.9). |

## Timeline estimate (2 senior Android/Chromium engineers)

- Weeks 1–6: depot setup, content_shell glue + EngineController, basic navigation.
- Weeks 7–12: profiles + temp sessions + storage isolation (Chromium profile plumbing).
- Weeks 13–18: network engine (proxy/service, DoH, kill switch).
- Weeks 19–26: fingerprint script hardening at renderer layer; WebRTC policy wiring.
- Weeks 27–40: extension runtime porting + validation reuse from this repo's Kotlin
  manifest/package systems + extension manager UI bindings.
- Weeks 41–48: rewrite privacy dashboards to read native engine probes; testing.

The repository scaffold in this folder remains the product/surface layer unchanged
throughout — builds switch `WebViewEngineProvider` → `ChromiumEngineProvider`.
