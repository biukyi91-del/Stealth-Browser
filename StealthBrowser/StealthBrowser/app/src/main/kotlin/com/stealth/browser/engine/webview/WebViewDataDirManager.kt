package com.stealth.browser.engine.webview

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real per-profile storage isolation on the Android WebView backing.
 *
 * Mechanism: [WebView.setDataDirectorySuffix] — every WebView created after setting a suffix
 * writes its cookies, cache, and site storage (Local Storage, IndexedDB, Service Workers)
 * into its own data directory. The suffix is derived from the profile/session identity.
 *
 * Cleanup of a temporary session = deleting that directory + wiping trackers that are
 * process-global on the WebView backing (CookieManager, WebStorage, WebViewDatabase).
 * This is conservative: on the WebView backing global managers cannot target a single
 * suffix, so a temporary-session wipe also clears the process-global jar — this is the
 * safe (privacy-first) direction and is reported honestly by [caps][EnginePeek].
 */
@Singleton
class WebViewDataDirManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Returns the suffix to set BEFORE creating a WebView for [ownerId]. */
    fun suffixFor(ownerId: String): String = sanitize(ownerId)

    fun applySuffix(ownerId: String): Boolean {
        val suffix = suffixFor(ownerId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(suffix)
            return true
        }
        return false // minSdk is 29, so this branch is unreachable in practice
    }

    /** The on-disk data directory backing [ownerId] (best-effort derivation). */
    fun dataDirFor(ownerId: String): File {
        val dataDir = context.applicationContext.getDataDir()
        return File(dataDir, "app_webview_" + suffixFor(ownerId))
    }

    suspend fun deleteDataDir(ownerId: String): Boolean = withContext(Dispatchers.IO) {
        dataDirFor(ownerId).deleteRecursively()
    }

    suspend fun dataSize(ownerId: String): Long = withContext(Dispatchers.IO) {
        dirSize(dataDirFor(ownerId))
    }

    /**
     * Wipes all process-global WebView trackers. Called when destroying a temporary
     * session (privacy-first: see class kdoc) or an explicit "clear browser data" action.
     */
    suspend fun wipeGlobalTrackers() = withContext(Dispatchers.Main) {
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            WebViewDatabase.getInstance(context).apply {
                clearFormData()
                clearHttpAuthUsernamePassword()
            }
        }
        Unit
    }

    suspend fun wipeGlobalCookies(forDomain: String? = null) = withContext(Dispatchers.Main) {
        runCatching {
            val cm = CookieManager.getInstance()
            if (forDomain == null) cm.removeAllCookies(null)
            else cm.removeSessionCookies(null)
            cm.flush()
        }
        Unit
    }

    private fun dirSize(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf { dirSize(it) } ?: 0L
        else -> 0L
    }

    /** Suffixes are used as directory names — restrict to a safe charset. */
    private fun sanitize(ownerId: String): String =
        ownerId.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(48)
}
