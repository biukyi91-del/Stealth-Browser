package com.stealth.browser.network

import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.ProxyConfig
import com.stealth.browser.session.EnginePool
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Network privacy engine coordinator:
 *  - Resolves the NetworkConfig for the ACTIVE owner (profile's proxy binding,
 *    temp session inherits its source profile at session start).
 *  - Pushes resolved config into every live controller via EnginePool.
 *  - Enforces the kill-switch: when a proxy failure event lands, ALL navigation
 *    is blocked — a configured proxy NEVER silently degrades to direct (PRD §9).
 */
@Singleton
class NetworkManager @Inject constructor(
    private val proxyStore: ProxyStore,
    private val profileDao: ProfileDao,
    private val enginePool: EnginePool,
    private val sessionManager: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    data class NetworkStatus(
        val proxyActive: Boolean = false,
        val proxyLabel: String? = null,
        val proxyFailed: Boolean = false,
        val failureMessage: String? = null,
        val killSwitchEngaged: Boolean = false,
        val dohReachable: Boolean? = null,
        val dohProvider: String? = null,
    )

    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status

    /** Per-owner binding: ownerId → stored proxy id. Profile-owned; temp sessions map to their source profile binding. */
    private val ownerProxyBindings = MutableStateFlow<Map<String, String>>(emptyMap())

    fun bind(ownerId: String, proxyConfigId: String?) {
        ownerProxyBindings.value = ownerProxyBindings.value + (ownerId to proxyConfigId)
        scope.launch { applyForOwner(ownerId) }
    }

    suspend fun resolveNetwork(ownerId: String): NetworkConfig {
        // Direct profile binding, session binding, or — for a temporary session —
        // the SOURCE profile's binding (PRD §9: per-session network configuration).
        val sourceProfileId = when (val owner = sessionManager.activeOwner.value) {
            is PermanentProfileOwner -> if (owner.ownerId == ownerId) owner.profileId else null
            is TemporarySessionOwner -> if (owner.ownerId == ownerId) owner.sourceProfileId else null
            null -> null
        }
        val proxyId = ownerProxyBindings.value[ownerId]
            ?: sourceProfileId?.let { profileDao.byId(it)?.proxyConfigId }
            ?: profileDao.byId(ownerId)?.proxyConfigId
        val proxy: ProxyConfig? = proxyId?.let { id ->
            proxyStore.get(id)?.let { proxyStore.toProxyConfig(it) }
        }
        return NetworkConfig(
            proxy = proxy,
            dohEnabled = false, // doh-in-engine unsupported on current backing; surfaced honestly
            killSwitchEnabled = _status.value.killSwitchEngaged,
        )
    }

    suspend fun applyForOwner(ownerId: String) {
        val config = resolveNetwork(ownerId)
        enginePool.applyNetworkToAll(config)
        _status.value = _status.value.copy(
            proxyActive = config.proxy != null,
            proxyLabel = config.proxy?.let { "${it.type.name.lowercase()}://${it.host}:${it.port}" },
            proxyFailed = false,
        )
    }

    fun onEngineEvent(networkFailure: String) {
        // Engine surfaced a failure while a proxy was configured: engage the
        // kill switch so no request falls back to a direct connection.
        _status.value = _status.value.copy(
            proxyFailed = true,
            failureMessage = networkFailure,
            killSwitchEngaged = true,
        )
    }

    fun disengageKillSwitch() {
        _status.value = _status.value.copy(
            proxyFailed = false, failureMessage = null, killSwitchEngaged = false,
        )
    }

    fun recordDohTest(reachable: Boolean, provider: String) {
        _status.value = _status.value.copy(dohReachable = reachable, dohProvider = provider)
    }
}
