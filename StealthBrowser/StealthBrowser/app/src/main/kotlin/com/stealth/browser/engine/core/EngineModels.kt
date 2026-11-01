package com.stealth.browser.engine.core

/**
 * Honest capability matrix of the current engine backing.
 *
 * PRD rule: every advertised status must represent actual engine functionality.
 * The UI MUST read these flags before displaying any privacy status.
 */
enum class EngineCapability {
    /** Page navigation, history, back/forward. */
    NAVIGATION,

    /** Multiple tabs backed by engine instances. */
    MULTI_TAB,

    /** Per-profile isolated storage directory (cookies/cache/site storage). */
    PROFILE_STORAGE_ISOLATION,

    /** Temporary-session storage wipe on close. */
    SESSION_CLEANUP,

    /** JS-origin fingerprint API patching (canvas / webgl / audio / hardware). */
    FINGERPRINT_SCRIPTING,

    /** Engine-level proxy routing (no silent direct fallback). */
    PROXY,

    /** Deny-at-permission-gate capture prevention (WebRTC exposure control). */
    WEBRTC_CAPTURE_PREVENTION,

    /** Secure DNS override inside the engine. */
    DOH_IN_ENGINE,

    /** Connection-level network kill switch inside the engine. */
    ENGINE_KILL_SWITCH,

    /** Chromium-style extension runtime (MV2/MV3, background workers, content scripts). */
    EXTENSION_RUNTIME,

    /** Download interception and progress tracking. */
    DOWNLOAD_MANAGEMENT,

    /** Request-level tracker blocking. */
    REQUEST_FILTERING,
}

data class EnginePeek(
    val engineName: String,
    val version: String,
    val capabilities: Set<EngineCapability>,
) {
    fun supports(capability: EngineCapability): Boolean = capability in capabilities
}

enum class ProxyType { NONE, HTTP, HTTPS, SOCKS5 }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    /** Kept as CharArray for credential hygiene; wiped by callers after use. */
    val password: CharArray? = null,
) {
    init {
        require(host.isNotBlank()) { "Proxy host must not be blank" }
        require(port in 1..65535) { "Proxy port out of range: $port" }
    }
}

/**
 * Result of resolving which proxy applies to a WebView request.
 * On the WebView backing, HTTP and HTTPS proxies are supported through
 * [androidx.webkit][ProxyController]; SOCKS5 is reported unsupported.
 */
data class NetworkConfig(
    val proxy: ProxyConfig? = null,
    val dohEnabled: Boolean = false,
    val dohProvider: String? = null,
    val killSwitchEnabled: Boolean = false,
) {
    companion object {
        val DIRECT = NetworkConfig()
    }
}

/** Fingerprint privacy flags. Values map 1:1 to real engine behavior. */
data class PrivacyConfig(
    val canvasProtection: Boolean = false,
    val webglPrivacy: Boolean = false,
    val audioContextPrivacy: Boolean = false,
    val webgpuPrivacy: Boolean = false,
    val fontEnumerationPrivacy: Boolean = false,
    val screenInfoPrivacy: Boolean = false,
    val hardwareInfoPrivacy: Boolean = false,
    val userAgentOverride: String? = null,
    val clientHintsPrivacy: Boolean = false,
    val timezonePrivacy: Boolean = false,
    val localePrivacy: Boolean = false,
) {
    companion object {
        val OFF = PrivacyConfig()
    }
}

sealed interface EngineEvent {
    data class PageStarted(val url: String, val isRedirect: Boolean) : EngineEvent
    data class PageFinished(val url: String) : EngineEvent
    data class Progress(val url: String, val progress: Int) : EngineEvent
    data class TitleChanged(val url: String, val title: String) : EngineEvent
    data class TargetUrlChanged(val url: String?) : EngineEvent
    data class DownloadRequested(val url: String, val suggestedName: String?) : EngineEvent
    data class SitePermissionRequested(
        val url: String,
        /** WebView PermissionRequest resource types. */
        val resources: Array<String>,
        val grant: (Array<String>) -> Unit,
        val deny: () -> Unit,
    ) : EngineEvent

    /** A proxy failure surfaced by the engine — navigation is halted, not silently retried direct. */
    data class ProxyFailure(val message: String) : EngineEvent
    data class RenderFailed(val description: String) : EngineEvent
}
