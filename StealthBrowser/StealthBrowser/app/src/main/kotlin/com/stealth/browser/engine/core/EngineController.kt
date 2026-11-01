package com.stealth.browser.engine.core

import kotlinx.coroutines.flow.Flow

/**
 * Controller for one engine rendering instance (one tab).
 * Implemented by [com.stealth.browser.engine.webview.WebViewEngineController];
 * a future Chromium backing implements the same interface (see docs/CHROMIUM_INTEGRATION.md).
 */
interface EngineController {
    val events: Flow<EngineEvent>

    /** The platform View to be attached to the UI tree. Android-specific by design. */
    val view: android.view.View

    var url: String?
    fun loadUrl(url: String)
    fun reload()

    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun goBack()
    fun goForward()

    fun evaluateJavaScript(script: String, onResult: (String?) -> Unit = {})
    fun stopLoading()

    /**
     * Re-applies network and privacy configuration live.
     * Proxy changes on the WebView backing go through ProxyController and
     * clear navigation; direct fallback must NOT occur on failure.
     */
    suspend fun applyNetwork(config: NetworkConfig)

    /** Applies document-start fingerprint/permission scripts. Re-adds on next page load. */
    fun applyPrivacy(config: PrivacyConfig)

    /** Find-on-page within the currently rendered content. Null query = cancel. */
    fun findOnPage(query: String?, onMatch: (Int, Int, Boolean) -> Unit = { _, _, _ -> })

    /** Render as desktop (desktop-class UA + viewport) and reload. */
    fun setDesktopMode(enabled: Boolean)

    /** Wipes this controller's own cookies and storage tracking state. */
    suspend fun clearSessionData()

    fun close()
}
