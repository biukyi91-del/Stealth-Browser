package com.stealth.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAtMs ASC")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun byId(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun default(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE profiles SET isDefault = 0")
    suspend fun clearDefaultFlag()

    @Transaction
    suspend fun setDefault(id: String) {
        clearDefaultFlag()
        byId(id)?.let { upsert(it.copy(isDefault = true)) }
    }
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs WHERE ownerId = :ownerId ORDER BY position ASC")
    fun observeForOwner(ownerId: String): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs ORDER BY position ASC")
    fun observeAll(): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun byId(id: String): TabEntity?

    @Query("SELECT COUNT(*) FROM tabs WHERE ownerId = :ownerId")
    suspend fun countForOwner(ownerId: String): Int

    @Upsert
    suspend fun upsert(tab: TabEntity)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tabs WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM tabs WHERE ownerId = :ownerId")
    suspend fun deleteForOwner(ownerId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE profileId = :profileId ORDER BY createdAtMs DESC")
    fun observeForProfile(profileId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url AND profileId = :profileId LIMIT 1")
    suspend fun byUrl(profileId: String, url: String): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAtMs DESC LIMIT :limit")
    fun observeForProfile(profileId: String, limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAtMs DESC LIMIT :limit")
    suspend fun rowsForProfile(profileId: String, limit: Int): List<HistoryEntity>

    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)

    @Query("DELETE FROM history WHERE sessionId = :sessionId")
    suspend fun clearForSession(sessionId: String)
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun byId(id: String): ExtensionEntity?

    @Upsert
    suspend fun upsert(extension: ExtensionEntity)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM extension_profile_state WHERE profileId = :profileId")
    suspend fun statesOfProfile(profileId: String): List<ExtensionProfileStateEntity>

    @Query("SELECT * FROM extension_profile_state WHERE extensionId = :extensionId AND profileId = :profileId")
    suspend fun stateFor(extensionId: String, profileId: String): ExtensionProfileStateEntity?

    @Upsert
    suspend fun upsertState(state: ExtensionProfileStateEntity)

    @Query("DELETE FROM extension_profile_state WHERE extensionId = :extensionId")
    suspend fun deleteStates(extensionId: String)

    @Query("DELETE FROM extension_profile_state WHERE profileId = :profileId")
    suspend fun deleteStatesForProfile(profileId: String)
}

@Dao
interface ProxyConfigDao {
    @Query("SELECT * FROM proxy_configs ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<ProxyConfigEntity>>

    @Query("SELECT * FROM proxy_configs")
    suspend fun all(): List<ProxyConfigEntity>

    @Query("SELECT * FROM proxy_configs WHERE id = :id")
    suspend fun byId(id: String): ProxyConfigEntity?

    @Upsert
    suspend fun upsert(config: ProxyConfigEntity)

    @Query("DELETE FROM proxy_configs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions WHERE ownerId = :ownerId")
    suspend fun forOwner(ownerId: String): List<SitePermissionEntity>

    @Query("SELECT granted FROM site_permissions WHERE ownerId = :ownerId AND host = :host AND permission = :permission")
    suspend fun granted(ownerId: String, host: String, permission: String): Boolean?

    @Upsert
    suspend fun upsert(permission: SitePermissionEntity)

    @Query("DELETE FROM site_permissions WHERE ownerId = :ownerId")
    suspend fun clearForOwner(ownerId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE ownerId = :ownerId ORDER BY startedAtMs DESC")
    fun observeForOwner(ownerId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE ownerId = :ownerId ORDER BY startedAtMs DESC")
    suspend fun rowsForOwner(ownerId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE platformId = :platformId")
    suspend fun byPlatformId(platformId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status AND ownerId = :ownerId")
    suspend fun byStatus(ownerId: String, status: String): List<DownloadEntity>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE ownerId = :ownerId AND fileName LIKE :pattern")
    suspend fun deleteTemporaryForOwner(ownerId: String, pattern: String)

    @Query("DELETE FROM downloads WHERE ownerId = :ownerId")
    suspend fun deleteForOwner(ownerId: String)
}

@Dao
interface TrackerStatDao {
    @Query("SELECT COALESCE(SUM(count),0) FROM tracker_stats")
    fun observeTotalBlocked(): Flow<Long>

    @Upsert
    suspend fun bump(stat: TrackerStatEntity)

    @Query("DELETE FROM tracker_stats")
    suspend fun clearAll()
}
