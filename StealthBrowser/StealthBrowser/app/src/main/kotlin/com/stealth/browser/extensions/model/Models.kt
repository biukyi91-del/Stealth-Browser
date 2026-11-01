package com.stealth.browser.extensions.model

import kotlinx.serialization.Serializable

/** Union view of a Chromium extension manifest (v2 and v3 fields both present, flagged by version). */
@Serializable
data class ExtensionManifest(
    val manifestVersion: Int,
    val name: String,
    val shortName: String? = null,
    val description: String? = null,
    val version: String,
    val versionName: String? = null,
    val minimumChromeVersion: String? = null,
    val author: String? = null,
    /** Optional public key used to derive the extension ID; else the ID is hashed from the package. */
    val key: String? = null,
    /** Google Web Store key (base64). */
    val updateUrl: String? = null,
    val permissions: List<String> = emptyList(),
    val optionalPermissions: List<String> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
    val optionalHostPermissions: List<String> = emptyList(),
    val contentScripts: List<ContentScriptDeclaration> = emptyList(),
    val background: BackgroundDeclaration? = null,
    val action: ActionDeclaration? = null,
    /** MV2 alias of [action]. */
    val browserAction: ActionDeclaration? = null,
    val optionsPage: String? = null,
    val optionsUi: OptionsUiDeclaration? = null,
    val icons: Map<String, String> = emptyMap(),
    val defaultLocale: String? = null,
    val commands: Map<String, CommandDeclaration> = emptyMap(),
    val webAccessibleResources: List<String> = emptyList(),
    val chromeUrlOverrides: Map<String, String> = emptyMap(),
    val declarativeNetRequest: DeclarativeNetRequestDeclaration? = null,
    val contentSecurityPolicy: String? = null,
) {
    @Serializable
    data class ContentScriptDeclaration(
        val matches: List<String> = emptyList(),
        val js: List<String> = emptyList(),
        val css: List<String> = emptyList(),
        val runAt: String? = null,
        val allFrames: Boolean = false,
    )

    @Serializable
    data class BackgroundDeclaration(
        val serviceWorker: String? = null, // MV3
        val page: String? = null,          // MV2
        val scripts: List<String>? = null, // MV2
        val persistent: Boolean = true,
    )

    @Serializable
    data class ActionDeclaration(
        val defaultTitle: String? = null,
        val defaultPopup: String? = null,
        val defaultIcon: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class OptionsUiDeclaration(
        val page: String? = null,
        val openInTab: Boolean = false,
    )

    @Serializable
    data class CommandDeclaration(
        val description: String? = null,
        val suggestedKey: String? = null,
    )

    @Serializable
    data class DeclarativeNetRequestDeclaration(
        val ruleResources: List<RuleResource> = emptyList(),
    ) {
        @Serializable
        data class RuleResource(val id: String, val enabled: Boolean, val path: String)
    }
}

enum class ExtensionStatus {
    /** Installed and runnable under the engine's extension runtime. */
    FULLY_COMPATIBLE,
    /** Installed; some declared APIs are unsupported and will not function. */
    PARTIALLY_COMPATIBLE,
    /** Declared APIs are all unsupported on this platform/configuration. */
    NOT_COMPATIBLE,
    DISABLED,
    INSTALLATION_FAILED,
}

/** Site access modes — only exposed when the engine actually enforces them. */
enum class SiteAccessMode { ALL_SITES, SELECTED_SITES, ON_CLICK, UNSUPPORTED }

/** Effective runtime capabilities, one row per Chromium extension API family (PRD §13.4). */
data class CompatibilityReport(
    val manifestVersion: Int,
    val supportedApis: List<String>,
    val unsupportedApis: List<String>,
    val blockedReasons: List<String>,
    val result: ExtensionStatus,
)

object ExtensionCapabilities {
    /** API families the engine can run. Derived from the BACKING capability matrix (honest, no faking). */
    val CURRENT: Set<String> = setOf(
        "storage", "tabs", "runtime", "action", "browserAction",
        "options_page", "webNavigation", "contextMenus",
        "declarativeNetRequest(static rules)", "alarms",
    )

    /** Known desktop APIs that this platform's runtime does not implement. */
    val UNSUPPORTED: Set<String> = setOf(
        "webRequest(blocking)", "webRequest(filtering listener)", "cookies",
        "downloads", "history", "bookmarks", "idle", "notifications",
        "privacy", "proxy", "commands", "offscreen", "sidePanel", "chrome.identity",
        "chrome.nativeMessaging", "windows", "system.*",
    )
}

object SiteAccessDefaults {
    const val UNSUPPORTED_NOTE =
        "Site access enforcement requires the extension runtime. Until Chromium " +
            "integration lands, this browser reports UNSUPPORTED instead of " +
            "presenting controls that would not be enforced (PRD rule §13.6)."
}
