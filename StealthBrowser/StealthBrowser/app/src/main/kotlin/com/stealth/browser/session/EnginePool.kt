package com.stealth.browser.session

import com.stealth.browser.data.db.TabDao
import com.stealth.browser.data.db.TabEntity
import com.stealth.browser.engine.core.EngineController
import com.stealth.browser.engine.core.EngineEvent
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.PrivacyConfig
import com.stealth.browser.engine.webview.WebViewEngineProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BrowserTab(
    val tabId: String,
    val ownerId: String,
    val isTempSession: Boolean,
)

/**
 * Owns live [EngineController] instances, one per tab, each bound to its owner.
 *
 * Soundness notes:
 *  - Controllers are created lazily and destroyed deterministically on tab close.
 *  - Proxy on the WebView backing is PROCESS-WIDE (ProxyController) — documented
 *    honestly in the engine capability matrix; [applyNetworkToAll] pushes the last
 *    configured network to every live controller, so the newest override wins.
 *  - Events from every controller fan out to a single bus for the UI.
 */
@Singleton
class EnginePool @Inject constructor(
    private val engineProvider: WebViewEngineProvider,
    private val tabDao: TabDao,
) {
    private val _controllers = MutableStateFlow<Map<String, EngineController>>(emptyMap())
    val controllers: StateFlow<Map<String, EngineController>> = _controllers

    private val _eventBus = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val eventBus: SharedFlow<EngineEvent> = _eventBus

    val activeTabId = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Last network config supplied by [SessionEngine]/NetworkManager; applied to every new controller. */
    @Volatile
    var lastNetworkConfig: NetworkConfig = NetworkConfig.DIRECT

    suspend fun createTab(owner: BrowserOwner, startUrl: String?): String {
        val privacy: PrivacyConfig = when (owner) {
            is TemporarySessionOwner -> owner.privacyConfig
            is PermanentProfileOwner -> owner.privacyConfig ?: PrivacyConfig.OFF
        }
        val controller = engineProvider.create(
            ownerId = owner.ownerId,
            privacy = privacy,
            network = lastNetworkConfig,
        )
        val tabId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val position = tabDao.countForOwner(owner.ownerId)
        tabDao.upsert(
            TabEntity(
                id = tabId,
                ownerId = owner.ownerId,
                url = startUrl,
                title = "New tab",
                position = position,
                createdAtMs = now,
                updatedAtMs = now,
                sessionId = if (owner.isTemporary) owner.ownerId else null,
            ),
        )
        _controllers.value = _controllers.value + (tabId to controller)
        activeTabId.value = tabId
        scope.launch {
            controller.events.collect { _eventBus.emit(it) }
        }
        startUrl?.let { controller.loadUrl(it) }
        return tabId
    }

    fun controllerFor(tabId: String): EngineController? = _controllers.value[tabId]

    suspend fun closeTab(tabId: String) {
        val controller = _controllers.value[tabId] ?: return
        controller.close()
        _controllers.value = _controllers.value - tabId
        tabDao.delete(tabId)
        if (activeTabId.value == tabId) {
            activeTabId.value = _controllers.value.keys.firstOrNull()
        }
    }

    /** Closes & destroys every controller of [ownerId]; pending tabs row-deleted by callers. */
    suspend fun closeAllForOwner(ownerId: String): List<String> {
        val closed = mutableListOf<String>()
        for ((tabId, _) in _controllers.value.toList()) {
            val tab = tabDao.byId(tabId)
            if (tab?.ownerId == ownerId) {
                closed += tabId
                closeTab(tabId)
            }
        }
        return closed
    }

    /** Applies [network] to every live controller (newest process-wide override wins). */
    fun applyNetworkToAll(network: NetworkConfig) {
        lastNetworkConfig = network
        scope.launch {
            for ((_, controller) in _controllers.value) {
                controller.applyNetwork(network)
            }
        }
    }

    val enginePeek: com.stealth.browser.engine.core.EnginePeek get() = engineProvider.peek
}
