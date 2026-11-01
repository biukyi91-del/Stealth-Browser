package com.stealth.browser.extensions.manifest

import com.stealth.browser.extensions.model.CompatibilityReport
import com.stealth.browser.extensions.model.ExtensionCapabilities
import com.stealth.browser.extensions.model.ExtensionManifest
import com.stealth.browser.extensions.model.ExtensionStatus

/**
 * Static compatibility analysis of a manifest against the platform's real
 * extension capabilities. The report feeds the Compatibility diagnostics page
 * (PRD §13.10) and the honest status badge in the manager list.
 */
object CompatibilityChecker {

    fun analyze(manifest: ExtensionManifest): CompatibilityReport {
        val reasons = mutableListOf<String>()

        if (manifest.manifestVersion == 2) {
            reasons += "Manifest V2: supported while the engine retains compatibility; " +
                "verify at runtime."
        }
        if (manifest.manifestVersion > 3) {
            reasons += "Manifest version ${manifest.manifestVersion} unknown to this engine."
        }

        val required = buildList {
            addAll(manifest.permissions)
            addAll(manifest.hostPermissions.map { "host_permissions/$it" })
        }
        val unsupportedRequired = required.filter { permissionUnsupported(it) }
        for (perm in unsupportedRequired) reasons += "Required permission not available here: $perm"

        val apis = manifest.permissions + manifest.hostPermissions
        val supported = apis.filterNot { permissionUnsupported(it) }

        val background = manifest.background
        if (background?.serviceWorker != null) {
            reasons += "Background service worker declared (MV3)."
        }
        if (background?.page != null || !background?.scripts.isNullOrEmpty()) {
            reasons += "Background page (MV2)."
        }

        if (manifest.commands.isNotEmpty()) {
            reasons += "Commands API declared — keyboard mapping is desktop Chrome specific."
        }

        val blockingWebrequest = manifest.permissions.any { it == "webRequest" } &&
            manifest.manifestVersion == 2 &&
            manifest.permissions.any { it == "webRequestBlocking" }
        if (blockingWebrequest) {
            reasons += "Blocking WebRequest listener is not supported here; declarativeNetRequest rules are."
        }

        val result = when {
            reasons.none { it.startsWith("Required permission not available") } &&
                manifest.manifestVersion == 3 -> ExtensionStatus.FULLY_COMPATIBLE
            unsupportedRequired.size < required.size -> ExtensionStatus.PARTIALLY_COMPATIBLE
            required.isNotEmpty() && unsupportedRequired.size == required.size -> ExtensionStatus.NOT_COMPATIBLE
            manifest.manifestVersion == 3 -> ExtensionStatus.FULLY_COMPATIBLE
            else -> ExtensionStatus.PARTIALLY_COMPATIBLE
        }
        return CompatibilityReport(
            manifestVersion = manifest.manifestVersion,
            supportedApis = supported.distinct(),
            unsupportedApis = unsupportedRequired.distinct(),
            blockedReasons = reasons,
            result = result,
        )
    }

    /** Maps a raw permission key to "unsupported" using the honest capability sets. */
    private fun permissionUnsupported(perm: String): Boolean {
        val family = perm.substringBefore('.').substringBefore(':')
        val exact = perm
        return ExtensionCapabilities.UNSUPPORTED.any { it.startsWith(family) } ||
            family in setOf("downloads", "history", "bookmarks", "idle", "notifications",
                "privacy", "proxy", "cookies", "identity", "nativeMessaging", "offscreen",
                "sidePanel", "system.cpu", "system.memory", "windows")
    }
}
