package com.stealth.browser.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stealth_settings")

enum class SearchEngine(val displayName: String, val queryUrl: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    MOJEEK("Mojeek", "https://www.mojeek.com/search?q="),
    GOOGLE("Google", "https://www.google.com/search?q="),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class StartupBehavior { CONTINUE, HOMEPAGE, NEW_SESSION }

enum class ExtensionLoadingPolicy {
    /** Temporary sessions always start with extensions disabled (PRD §6 option A). */
    NONE,
    /** Load only extensions explicitly approved for that session (PRD §6 option B).
     *  DryRun is reported as legacy: the WebView backing has no extension runtime, so
     *  selected extensions are recorded in the session manifest and scheduled for the
     *  Chromium integration; the UI reports this honestly. */
    APPROVED,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "system",
    val homepageUrl: String = "https://start.duckduckgo.com/",
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val startupBehavior: StartupBehavior = StartupBehavior.HOMEPAGE,
    val defaultSessionTypeIsTemporary: Boolean = true,
    val autoCleanupOnClose: Boolean = true,
    val requireSessionConfirm: Boolean = true,
    val extensionLoadingPolicy: ExtensionLoadingPolicy = ExtensionLoadingPolicy.APPROVED,
    val extensionAutoUpdate: Boolean = false,
    val extensionDeveloperMode: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val showSessionWarningOnExit: Boolean = false,
)

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val HOMEPAGE = stringPreferencesKey("homepage")
        val SEARCH = stringPreferencesKey("search_engine")
        val STARTUP = stringPreferencesKey("startup")
        val TEMP_DEFAULT = booleanPreferencesKey("default_temp_session")
        val AUTO_CLEANUP = booleanPreferencesKey("auto_cleanup")
        val SESSION_CONFIRM = booleanPreferencesKey("session_confirm")
        val EXT_POLICY = stringPreferencesKey("extension_policy")
        val EXT_AUTOUPDATE = booleanPreferencesKey("extension_autoupdate")
        val EXT_DEV_MODE = booleanPreferencesKey("extension_devmode")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            themeMode = enumOr(p[Keys.THEME], ThemeMode.SYSTEM),
            language = p[Keys.LANGUAGE] ?: "system",
            homepageUrl = p[Keys.HOMEPAGE] ?: AppSettings().homepageUrl,
            searchEngine = enumOr(p[Keys.SEARCH], SearchEngine.DUCKDUCKGO),
            startupBehavior = enumOr(p[Keys.STARTUP], StartupBehavior.HOMEPAGE),
            defaultSessionTypeIsTemporary = p[Keys.TEMP_DEFAULT] ?: true,
            autoCleanupOnClose = p[Keys.AUTO_CLEANUP] ?: true,
            requireSessionConfirm = p[Keys.SESSION_CONFIRM] ?: true,
            extensionLoadingPolicy = enumOr(p[Keys.EXT_POLICY], ExtensionLoadingPolicy.APPROVED),
            extensionAutoUpdate = p[Keys.EXT_AUTOUPDATE] ?: false,
            extensionDeveloperMode = p[Keys.EXT_DEV_MODE] ?: false,
            killSwitchEnabled = p[Keys.KILL_SWITCH] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { p ->
            val updated = transform(fromPrefs(p))
            p[Keys.THEME] = updated.themeMode.name
            p[Keys.LANGUAGE] = updated.language
            p[Keys.HOMEPAGE] = updated.homepageUrl
            p[Keys.SEARCH] = updated.searchEngine.name
            p[Keys.STARTUP] = updated.startupBehavior.name
            p[Keys.TEMP_DEFAULT] = updated.defaultSessionTypeIsTemporary
            p[Keys.AUTO_CLEANUP] = updated.autoCleanupOnClose
            p[Keys.SESSION_CONFIRM] = updated.requireSessionConfirm
            p[Keys.EXT_POLICY] = updated.extensionLoadingPolicy.name
            p[Keys.EXT_AUTOUPDATE] = updated.extensionAutoUpdate
            p[Keys.EXT_DEV_MODE] = updated.extensionDeveloperMode
            p[Keys.KILL_SWITCH] = updated.killSwitchEnabled
        }
    }

    private fun fromPrefs(p: Preferences): AppSettings {
        // Reuse the same mapping as `settings` for the current snapshot.
        return AppSettings(
            themeMode = enumOr(p[Keys.THEME], ThemeMode.SYSTEM),
            language = p[Keys.LANGUAGE] ?: "system",
            homepageUrl = p[Keys.HOMEPAGE] ?: AppSettings().homepageUrl,
            searchEngine = enumOr(p[Keys.SEARCH], SearchEngine.DUCKDUCKGO),
            startupBehavior = enumOr(p[Keys.STARTUP], StartupBehavior.HOMEPAGE),
            defaultSessionTypeIsTemporary = p[Keys.TEMP_DEFAULT] ?: true,
            autoCleanupOnClose = p[Keys.AUTO_CLEANUP] ?: true,
            requireSessionConfirm = p[Keys.SESSION_CONFIRM] ?: true,
            extensionLoadingPolicy = enumOr(p[Keys.EXT_POLICY], ExtensionLoadingPolicy.APPROVED),
            extensionAutoUpdate = p[Keys.EXT_AUTOUPDATE] ?: false,
            extensionDeveloperMode = p[Keys.EXT_DEV_MODE] ?: false,
            killSwitchEnabled = p[Keys.KILL_SWITCH] ?: false,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, fallback: E): E =
        raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback
}
