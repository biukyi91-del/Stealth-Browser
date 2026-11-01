package com.stealth.browser.engine.webview

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import com.stealth.browser.engine.core.EngineCapability
import com.stealth.browser.engine.core.EnginePeek
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.PrivacyConfig
import com.stealth.browser.engine.privacy.FingerprintScripts
import com.stealth.browser.trackers.TrackerEngine
import java.util.TimeZone
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for WebView-backed engine controllers. Owns the honest capability matrix
 * for this backing and guarantees per-profile data suffixes are active BEFORE a
 * controller's WebView is created (ordering is load-bearing for isolation).
 */
@Singleton
class WebViewEngineProvider @Inject constructor(
    private val dataDirManager: WebViewDataDirManager,
    private val trackerEngine: TrackerEngine,
    private val permissionGate: com.stealth.browser.session.SitePermissionGate,
    private val context: Context,
) {
    val peek: EnginePeek = EnginePeek(
        engineName = "Android WebView (stealth profiled)",
        version = runCatching { WebView.getCurrentWebViewPackage()?.versionName ?: "bundled" }
            .getOrDefault("bundled"),
        capabilities = buildSet {
            add(EngineCapability.NAVIGATION)
            add(EngineCapability.MULTI_TAB)
            add(EngineCapability.PROFILE_STORAGE_ISOLATION)
            add(EngineCapability.SESSION_CLEANUP)
            add(EngineCapability.FINGERPRINT_SCRIPTING)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                add(EngineCapability.PROXY)
            }
            add(EngineCapability.WEBRTC_CAPTURE_PREVENTION)
            add(EngineCapability.DOWNLOAD_MANAGEMENT)
            add(EngineCapability.REQUEST_FILTERING)
            // Not available on this backing — UI must reflect reality:
            // DOH_IN_ENGINE, ENGINE_KILL_SWITCH, EXTENSION_RUNTIME
        },
    )

    /**
     * Creates a controller whose WebView is bound to [ownerId]'s isolated data
     * directory. MUST run on the main thread.
     */
    fun create(
        ownerId: String,
        privacy: PrivacyConfig,
        network: NetworkConfig,
    ): WebViewEngineController {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Engine controllers must be created on the main thread"
        }
        dataDirManager.applySuffix(ownerId)
        val fingerprint = FingerprintScripts.newSessionPayload(
            screenWidth = realScreenWidth(),
            screenHeight = realScreenHeight(),
            colorDepth = 24,
            devicePixelRatio = realDevicePixelRatio(),
            hardwareConcurrency = Runtime.getRuntime().availableProcessors(),
            deviceMemory = 4,
            timezone = null, // never spoof by default — honest, coherent config
            timezoneOffsetMinutes = 0,
            locale = Locale.getDefault().toLanguageTag(),
            webglVendor = "GPU vendor (masked)",
            webglRenderer = "GPU renderer (masked)",
        )
        return WebViewEngineController(
            context = context,
            ownerId = ownerId,
            trackerEngine = trackerEngine,
            permissionGate = permissionGate,
            fingerprintJson = FingerprintScripts.encodePayload(fingerprint),
            initialPrivacy = privacy,
            initialNetwork = network,
        )
    }

    private fun realScreenWidth(): Int =
        context.resources.displayMetrics.widthPixels

    private fun realScreenHeight(): Int =
        context.resources.displayMetrics.heightPixels

    private fun realDevicePixelRatio(): Float =
        context.resources.displayMetrics.density

    companion object {
        fun timeZoneInfo(): Pair<String?, Int> {
            val tz = TimeZone.getDefault()
            val offsetMin = tz.getOffset(System.currentTimeMillis()) / 60_000
            return tz.id to offsetMin
        }
    }
}
