package com.stealth.browser.session

import com.stealth.browser.data.db.SitePermissionDao
import com.stealth.browser.data.db.SitePermissionEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real site-permission gate consulted synchronously by the engine's
 * onPermissionRequest. Default DENY. A grant applies ONLY to the owning
 * owner (profile or temporary session) — engine data dirs plus this gate
 * together implement permission isolation (PRD §7).
 */
@Singleton
class SitePermissionGate @Inject constructor(
    private val dao: SitePermissionDao,
) {
    // ownerId|host → set of granted permissions
    private val granted = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun load(ownerId: String) {
        val rows = dao.forOwner(ownerId)
        granted[ownerId] = rows
            .filter { it.granted }
            .mapTo(mutableSetOf()) { "${it.host}|${it.permission}" }
    }

    fun isGranted(ownerId: String, host: String?, permission: String): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase()
        val perOwner = granted[ownerId] ?: return false
        return perOwner.contains("$normalized|$permission")
    }

    suspend fun set(ownerId: String, host: String, permission: String, grantedNow: Boolean) {
        val key = "${host.lowercase()}|$permission"
        val perOwner = granted.getOrPut(ownerId) { mutableSetOf() }
        if (grantedNow) perOwner.add(key) else perOwner.remove(key)
        dao.upsert(
            SitePermissionEntity(
                ownerId = ownerId,
                host = host.lowercase(),
                permission = permission,
                granted = grantedNow,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearOwner(ownerId: String) {
        granted.remove(ownerId)
        dao.clearForOwner(ownerId)
    }
}
