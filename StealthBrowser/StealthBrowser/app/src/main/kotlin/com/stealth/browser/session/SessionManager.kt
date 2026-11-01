package com.stealth.browser.session

import com.stealth.browser.data.db.HistoryDao
import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.data.db.TabDao
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.engine.core.PrivacyConfig
import com.stealth.browser.engine.webview.WebViewDataDirManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-click temporary browsing session lifecycle.
 *
 * Guarantees implemented here (PRD §6/§7):
 *  - Unique session ID per tap; unique engine data directory per session.
 *  - No inheritance of previous session data — a fresh data dir means empty
 *    cookies/cache/Local Storage/IndexedDB/service workers/permissions.
 *  - Privacy engine state: a temp session inherits its source profile's
 *    PrivacyConfig (coherent configuration, no randomization), or engine
 *    defaults (all off) when unbound.
 *  - On close: data dir deleted, tabs/history rows purged, process-global
 *    trackers wiped (privacy-first direction — see WebViewDataDirManager docs
 *    for the honest limitation this covers).
 */
@Singleton
class SessionManager @Inject constructor(
    private val dataDirManager: WebViewDataDirManager,
    private val tabDao: TabDao,
    private val historyDao: HistoryDao,
    private val profileDao: ProfileDao,
    private val downloadCoordinator: com.stealth.browser.downloads.DownloadCoordinator,
    private val settingsStore: SettingsStore,
) {
    private val _activeOwner = MutableStateFlow<BrowserOwner?>(null)
    val activeOwner: StateFlow<BrowserOwner?> = _activeOwner

    private val _tempSessions = MutableStateFlow<Map<String, TemporarySessionOwner>>(emptyMap())
    val tempSessions: StateFlow<Map<String, TemporarySessionOwner>> = _tempSessions

    suspend fun newTempSession(
        sourceProfileId: String?,
        approvedExtensionIds: Set<String>,
    ): TemporarySessionOwner {
        val privacy: PrivacyConfig =
            PrivacyCodec.decode(profileDao.byId(sourceProfileId.orEmpty())?.privacyJson)
                ?: PrivacyConfig.OFF
        val session = TemporarySessionOwner(
            sessionInstanceId = "tmp_" + UUID.randomUUID().toString().replace("-", "").take(24),
            sourceProfileId = sourceProfileId,
            privacyConfig = privacy,
            approvedExtensionIds = approvedExtensionIds,
        )
        _tempSessions.value = _tempSessions.value + (session.sessionInstanceId to session)
        _activeOwner.value = session
        return session
    }

    suspend fun closeTempSession(sessionId: String) {
        val session = _tempSessions.value[sessionId] ?: return
        val settings = settingsStore.current()
        if (!settings.autoCleanupOnClose) {
            // Auto-cleanup disabled: engine instances are closed but the data
            // directories remain until a manual "wipe session data" action.
            _tempSessions.value = _tempSessions.value - sessionId
            if (_activeOwner.value?.ownerId == sessionId) _activeOwner.value = null
            return
        }
        cleanupSessionData(sessionId)
        _tempSessions.value = _tempSessions.value - sessionId
        if (_activeOwner.value?.ownerId == sessionId) _activeOwner.value = null
    }

    suspend fun cleanupSessionData(sessionId: String) {
        dataDirManager.deleteDataDir(sessionId)
        dataDirManager.wipeGlobalTrackers()
        tabDao.deleteForSession(sessionId)
        historyDao.clearForSession(sessionId)
        downloadCoordinator.purgeTemporary(sessionId)
    }

    fun activateOwner(owner: BrowserOwner) {
        _activeOwner.value = owner
    }
}
