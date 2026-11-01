package com.stealth.browser.session

import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.data.db.ProfileEntity
import com.stealth.browser.engine.core.PrivacyConfig
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Manages permanent profile lifecycle (create/rename/delete/switch/backup/restore). */
@Singleton
class ProfileManager @Inject constructor(
    private val profileDao: ProfileDao,
) {
    fun observeProfiles() = profileDao.observeAll()

    suspend fun profiles(): List<ProfileEntity> = profileDao.all()

    suspend fun defaultProfile(): ProfileEntity? = profileDao.default()

    suspend fun getProfile(id: String): ProfileEntity? = profileDao.byId(id)

    suspend fun createProfile(name: String): ProfileEntity {
        require(name.isNotBlank()) { "Profile name must not be blank" }
        val entity = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAtMs = System.currentTimeMillis(),
        )
        profileDao.upsert(entity)
        if (profileDao.default() == null) profileDao.setDefault(entity.id)
        return entity
    }

    suspend fun renameProfile(id: String, newName: String) {
        require(newName.isNotBlank())
        profileDao.byId(id)?.let { profileDao.upsert(it.copy(name = newName.trim())) }
    }

    suspend fun deleteProfile(id: String) {
        profileDao.delete(id)
        if (profileDao.default() == null) {
            profileDao.all().firstOrNull()?.let { profileDao.setDefault(it.id) }
        }
    }

    suspend fun setDefault(id: String) = profileDao.setDefault(id)

    /** PrivacyConfig override per profile — unique per profile, null = engine defaults. */
    suspend fun updatePrivacy(id: String, config: PrivacyConfig) {
        profileDao.byId(id)?.let {
            profileDao.upsert(it.copy(privacyJson = PrivacyCodec.encode(config)))
        }
    }

    suspend fun privacyOf(id: String): PrivacyConfig =
        PrivacyCodec.decode(profileDao.byId(id)?.privacyJson) ?: PrivacyConfig.OFF

    suspend fun setProxyConfig(profileId: String, proxyConfigId: String?) {
        profileDao.byId(profileId)?.let {
            profileDao.upsert(it.copy(proxyConfigId = proxyConfigId))
        }
    }

    /**
     * Backs up the SETTINGS subset of a profile (PRD §7: "backup supported settings").
     * Browsing data is deliberately excluded — backup only carries configuration.
     */
    suspend fun backup(profileId: String): String? {
        val p = profileDao.byId(profileId) ?: return null
        val privacy = PrivacyCodec.decode(p.privacyJson) ?: PrivacyConfig.OFF
        return PrivacyCodec.json.encodeToString(
            kotlinx.serialization.serializer<Map<String, String>>(),
            mapOf(
                "name" to p.name,
                "privacy" to PrivacyCodec.encode(privacy),
                "proxyConfigId" to p.proxyConfigId,
            ),
        )
    }

    suspend fun restore(backupJson: String): ProfileEntity? =
        runCatching {
            val payload = PrivacyCodec.json.decodeFromString<Map<String, String>>(backupJson)
            val created = createProfile(
                payload["name"]?.ifBlank { "Restored profile" } ?: "Restored profile",
            )
            PrivacyCodec.decode(payload["privacy"])?.let { updatePrivacy(created.id, it) }
            payload["proxyConfigId"]?.let { setProxyConfig(created.id, it) }
            profileDao.byId(created.id)
        }.getOrNull()
}
