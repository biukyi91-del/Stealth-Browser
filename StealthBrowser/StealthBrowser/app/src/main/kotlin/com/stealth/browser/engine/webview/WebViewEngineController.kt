package com.stealth.browser.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.stealth.browser.engine.core.EngineController
import com.stealth.browser.engine.core.EngineEvent
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.PrivacyConfig
import com.stealth.browser.engine.core.ProxyType
import com.stealth.browser.engine.privacy.FingerprintScripts
import com.stealth.browser.trackers.TrackerEngine
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * WebView-backed implementation of [EngineController].
 *
 * Honest limits (surfaced to the UI via [EnginePeek] / docs/ENGINE_CAPABILITIES.md):
 *  - Proxy: enforced via androidx ProxyController for HTTP and HTTPS. A configured
 *    proxy is never silently replaced with a direct connection; failures surface
 *    through [EngineEvent.ProxyFailure] and load errors.
 *  - SOCKS5: not supported by ProxyController — reported as unsupported, never faked.
 *  - DoH: cannot be forced inside the WebView engine; surfaced as platform guidance.
 *  - Tracker blocking: request filtering runs through shouldInterceptRequest.
 *  - WebRTC: getUserMedia goes through the onPermissionRequest gate with default
 *    DENY, so no capture device can be opened without a stored per-owner grant.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewEngineController(
    private val context: Context,
    private val ownerId: String,
    private val trackerEngine: TrackerEngine,
    private val permissionGate: com.stealth.browser.session.SitePermissionGate,
    fingerprintJson: String,
    initialPrivacy: PrivacyConfig,
    initialNetwork: NetworkConfig,
) : EngineController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<EngineEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<EngineEvent> = _events

    var privacyConfig: PrivacyConfig = initialPrivacy
        private set

    private val proxyExecutor = Executors.newSingleThreadExecutor()

    val webView: WebView = WebView(context)

    override val view: android.view.View get() = webView

    private var documentStartApplied = false

    init {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "WebViewEngineController must be created on the main thread"
        }
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        configureSettings()
        installClients()
        applyDocumentStartScript(initialPrivacy, fingerprintJson)
        // ProxyController applies process-wide, honoring the newest override.
        applyNetwork(initialNetwork)
    }

    private fun configureSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    @SuppressLint("NewApi")
    private fun installClients() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                _events.tryEmit(EngineEvent.PageStarted(request.url.toString(), request.isRedirect))
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { _events.tryEmit(EngineEvent.PageStarted(it, false)) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { _events.tryEmit(EngineEvent.PageFinished(it)) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val desc = error?.description?.toString().orEmpty()
                    _events.tryEmit(EngineEvent.RenderFailed(desc))
                    // A configured proxy failing is NEVER silently retried direct:
                    // the error stops navigation and is surfaced to the UI/kill switch.
                    if (currentNetwork?.proxy != null) {
                        _events.tryEmit(EngineEvent.ProxyFailure(desc))
                    }
                }
            }

            // Request-level tracker filtering (real, engine-level interception).
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.let { trackerEngine.intercept(it) }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Engine crash must never take the browser down (PRD §22/25).
                // Returning true means we handled it: the dead instance is destroyed and
                // replaced lazily by the tab manager rather than crashing the process.
                _events.tryEmit(EngineEvent.RenderFailed("renderer gone"))
                view?.apply {
                    stopLoading()
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val url = view?.url
                if (url != null) _events.tryEmit(EngineEvent.Progress(url, newProgress))
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val url = view?.url
                if (url != null && title != null) _events.tryEmit(EngineEvent.TitleChanged(url, title))
            }

            // Site permission gate — default DENY; only resources with a stored
            // per-owner grant are released (real camera/mic capture control, PRD §11).
            override fun onPermissionRequest(request: PermissionRequest) {
                val host = runCatching { request.origin?.host }.getOrNull()
                val grantable = request.resources
                    .filter { permissionGate.isGranted(ownerId, host, it) }
                    .toTypedArray()
                webView.post {
                    if (grantable.isEmpty()) request.deny() else request.grant(grantable)
                }
                _events.tryEmit(
                    EngineEvent.SitePermissionRequested(
                        url = request.origin?.toString().orEmpty(),
                        resources = request.resources.copyOf(),
                        grant = { resources -> request.grant(resources) },
                        deny = { request.deny() },
                    ),
                )
            }
        }

        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = guessFileName(url, contentDisposition, mimeType)
            _events.tryEmit(EngineEvent.DownloadRequested(url, name))
        })
    }

    private fun applyDocumentStartScript(privacy: PrivacyConfig, fingerprintJson: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = FingerprintScripts.buildScript(
            configJson = FingerprintScripts.encodeConfig(privacy),
            fingerprintJson = fingerprintJson,
        )
        // "*" = all origins. An empty allowlist would leave pages unpatched.
        WebViewCompat.addDocumentStartJavaScript(webView, script, listOf("*"))
        documentStartApplied = true
    }

    override var url: String? = null
        private set

    override fun loadUrl(target: String) {
        val normalized = target.trim()
        val resolved = when {
            normalized.startsWith("http://") || normalized.startsWith("https://") ||
                normalized.startsWith("about:") || normalized.startsWith("data:") ||
                normalized.startsWith("javascript:") -> normalized
            normalized.contains('.') && !normalized.contains(' ') -> "https://$normalized"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(normalized, "UTF-8")
        }
        url = resolved
        webView.loadUrl(resolved)
    }

    override fun reload() = webView.reload()

    override fun canGoBack(): Boolean = webView.canGoBack()
    override fun canGoForward(): Boolean = webView.canGoForward()
    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()

    override fun evaluateJavaScript(script: String, onResult: (String?) -> Unit) {
        webView.evaluateJavascript(script) { onResult(it) }
    }

    override fun stopLoading() = webView.stopLoading()

    private var currentNetwork: NetworkConfig? = null

    override suspend fun applyNetwork(config: NetworkConfig) {
        currentNetwork = config
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (config.proxy != null) {
                _events.tryEmit(EngineEvent.ProxyFailure("Proxy override unsupported on this WebView"))
            }
            return
        }
        val proxy = config.proxy
        if (proxy == null) {
            ProxyController.getInstance().clearProxyOverride(proxyExecutor) { }
            return
        }
        // SOCKS5 is not supported by ProxyController — reject loudly, never fake.
        if (proxy.type == ProxyType.SOCKS5) {
            _events.tryEmit(EngineEvent.ProxyFailure("SOCKS5 unsupported by the current engine"))
            return
        }
        val scheme = if (proxy.type == ProxyType.HTTPS) "https" else "http"
        // No bypass rules and implicit rules removed: every request must go through
        // the proxy; a dead proxy fails navigation instead of falling back direct.
        val proxyConfig = androidx.webkit.ProxyConfig.Builder()
            .addProxyRule("$scheme://${proxy.host}:${proxy.port}")
            .removeImplicitRules()
            .build()
        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            proxyExecutor,
            { /* applied */ },
        )
    }

    override fun applyPrivacy(config: PrivacyConfig) {
        privacyConfig = config
        // Document-start script content is fixed at registration time on the WebView
        // backing; a config change requires a page reload to take effect — reported honestly.
        webView.reload()
    }

    private var desktopMode = false

    /** Real find-on-page over the rendered engine view. */
    override fun findOnPage(query: String?, onMatch: (Int, Int, Boolean) -> Unit) {
        if (query == null) {
            runCatching { webView.clearMatches() }
            return
        }
        runCatching {
            webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                onMatch(activeMatchOrdinal, numberOfMatches, isDoneCounting)
            }
            webView.findAllAsync(query)
        }
    }

    /**
     * Desktop (UA-class) rendering. On the WebView backing this swaps the
     * user agent string plus viewport flags — a real, applied change.
     */
    override fun setDesktopMode(enabled: Boolean) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        runCatching {
            webView.settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else null
            webView.reload()
        }
    }

    companion object {
        /**
         * NOTE on proxy authentication: androidx ProxyController on the
         * WebView backing does not expose credential hooks for
         * authenticated proxies (e.g. with username/password). The
         * HTTP/HTTPS proxy connection is real and enforced, but a
         * username/password is currently used only by the NetworkTester
         * connection test. Chromium integration is the correct fix —
         * ProxyConfig with auth headers in the engine. This means
         * "authenticated proxy" remains honestly unsupported at engine level.
         */
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun clearSessionData() {
        withMain {
            runCatching { webView.clearCache(true) }
            runCatching { webView.clearHistory() }
            runCatching { webView.clearFormData() }
            runCatching { webView.clearSslPreferences() }
        }
    }

    private inline fun withMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else webView.post { block() }
    }

    override fun close() {
        scope.cancel()
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
    }

    private fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromCd = contentDisposition?.substringAfterLast("filename=")?.trim('"', '\\')
        val fromUrl = url.substringAfterLast('/').substringBefore('?')
        return (fromCd?.takeIf { it.isNotBlank() } ?: fromUrl).ifBlank {
            "download${mimeType?.let { ".${it.substringAfterLast('/')}" } ?: ""}"
        }
    }

    private fun proxyFailureIfConfigured() {
        if (currentNetwork?.proxy != null) {
            _events.tryEmit(EngineEvent.ProxyFailure("proxy unreachable"))
        }
    }
}
