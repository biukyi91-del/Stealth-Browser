package com.stealth.browser.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A browsing profile. Temporary sessions are NOT rows here — they live in-memory only. */
@Entity(tableName = "profiles", indices = [Index(unique = true, value = ["name"])])
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    val isDefault: Boolean = false,
    /** JSON-serialized PrivacyConfig fingerprint flag override profile. */
    val privacyJson: String? = null,
    /** Which proxy config (see proxy_configs.id) applies when this profile is active. */
    val proxyConfigId: String? = null,
    /** Whether a temporary session started from this profile follows its privacy settings. */
    val lockedDuringTempSession: Boolean = true,
)

@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val url: String?,
    val title: String,
    val position: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Owning temporary session, if this tab belongs to one. */
    val sessionId: String? = null,
)

@Entity(tableName = "bookmarks", indices = [Index("profileId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val url: String,
    val title: String,
    val createdAtMs: Long,
)

@Entity(tableName = "history", indices = [Index("profileId")])
data class HistoryEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    /** Set when the visit happened inside a temporary session. */
    val sessionId: String? = null,
    val url: String,
    val title: String,
    val visitedAtMs: Long,
)

/** Installed extension packages (manifest-level data). Profile-specific enablement lives in extension_profile_state. */
@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val id: String, // extension UUID (declared in manifest.json "key", else generated)
    val name: String,
    val version: String,
    val manifestVersion: Int,
    val developerName: String,
    val installedAtMs: Long,
    val description: String,
    /** Path of the installed package on internal storage. */
    val packagePath: String,
    /** Serialized ExtensionPermissions. */
    val permissionsJson: String,
    /** Serialized CompatibilityReport. */
    val compatibilityJson: String,
    /** Serialized list of supported API names. */
    val supportedApisJson: String,
    /** Serialized list of unsupported API names. */
    val unsupportedApisJson: String,
    val status: String, // ExtensionStatus enum name
    val optionsPage: String? = null,
)

@Entity(tableName = "extension_profile_state", primaryKeys = ["extensionId", "profileId"])
data class ExtensionProfileStateEntity(
    val extensionId: String,
    val profileId: String,
    val enabled: Boolean,
    /** SiteAccessMode enum name. */
    val siteAccess: String,
    val updatedAtMs: Long,
)

@Entity(tableName = "proxy_configs")
data class ProxyConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ProxyType enum name. */
    val type: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    /** Reference key into the secure credential store (never plaintext). */
    val passwordRef: String? = null,
    val createdAtMs: Long,
)

@Entity(tableName = "site_permissions", primaryKeys = ["ownerId", "host", "permission"])
data class SitePermissionEntity(
    val ownerId: String,
    val host: String,
    val permission: String,
    /** CAMERA, MICROPHONE, LOCATION, NOTIFICATION, GEO etc. value = granted. */
    val granted: Boolean,
    val updatedAtMs: Long,
)

@Entity(tableName = "downloads", indices = [Index("ownerId")])
data class DownloadEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val url: String,
    val filePath: String,
    val fileName: String,
    val status: String, // DownloadStatus enum name
    val bytesTotal: Long,
    val bytesDone: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    /** Platform DownloadManager row id, for live progress polling. */
    val platformId: Long = -1L,
)

@Entity(tableName = "tracker_stats", primaryKeys = ["ownerId", "statKey"])
data class TrackerStatEntity(
    val ownerId: String,
    val statKey: String,
    val count: Long,
    val updatedAtMs: Long,
)
