package com.stealth.browser.session

import com.stealth.browser.data.db.HistoryDao
import com.stealth.browser.data.db.HistoryEntity
import com.stealth.browser.downloads.DownloadCoordinator
import com.stealth.browser.engine.core.EngineEvent
import com.stealth.browser.network.NetworkManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fans engine events out to the subsystems that own them:
 *  - DownloadRequested → DownloadCoordinator (platform download, temp-session marker)
 *  - ProxyFailure → NetworkManager kill switch (no silent direct fallback)
 *  - PageFinished → history row for the owning profile (or session-scoped row)
 */
@Singleton
class EngineEventCoordinator @Inject constructor(
    private val enginePool: EnginePool,
    private val sessionManager: SessionManager,
    private val downloads: DownloadCoordinator,
    private val networkManager: NetworkManager,
    private val historyDao: HistoryDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** url → last title, from TitleChanged events; used for history rows. */
    private val titles = HashMap<String, String>(64)

    /** Owning profile for the ACTIVE session; second element = sessionId when temporary. */
    private fun historyOwner(): Pair<String, String?> = when (val owner = sessionManager.activeOwner.value) {
        is PermanentProfileOwner -> owner.profileId to null
        is TemporarySessionOwner -> (owner.sourceProfileId ?: "unassigned") to owner.sessionInstanceId
        null -> "unassigned" to null
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            enginePool.eventBus.collect { event ->
                when (event) {
                    is EngineEvent.DownloadRequested -> downloads.enqueue(
                        url = event.url,
                        suggestedName = event.suggestedName,
                        ownerId = enginePool.activeTabId.value ?: "unowned",
                        isTemporary = sessionManager.activeOwner.value?.isTemporary ?: false,
                    )

                    is EngineEvent.ProxyFailure -> networkManager.onEngineEvent(event.message)

                    is EngineEvent.TitleChanged -> titles[event.url] = event.title

                    is EngineEvent.PageFinished -> {
                        val (profileId, sessionId) = historyOwner()
                        runCatching {
                            historyDao.upsert(
                                HistoryEntity(
                                    id = UUID.randomUUID().toString(),
                                    profileId = profileId,
                                    sessionId = sessionId,
                                    url = event.url,
                                    title = titles[event.url] ?: event.url,
                                    visitedAtMs = System.currentTimeMillis(),
                                ),
                            )
                            if (titles.size > 256) titles.clear() // simple memory bound
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}
