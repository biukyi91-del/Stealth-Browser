package com.stealth.browser.downloads

import android.app.DownloadManager as PlatformDownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.stealth.browser.data.db.DownloadDao
import com.stealth.browser.data.db.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/**
 * Download coordination on the WebView backing:
 *  - The engine surfaces [EngineEvent.DownloadRequested]; downloads are enqueued
 *    onto the platform DownloadManager (no internal WebView downloader exists).
 *  - Destination is the public Downloads directory via the platform manager —
 *    scoped-storage compliant; no legacy WRITE_EXTERNAL_STORAGE usage.
 *  - Temporary-session downloads carry a marker prefix and are deleted
 *    (file + row) from [SessionManager] cleanup when auto-cleanup is enabled.
 */
@Singleton
class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    private fun platform(): PlatformDownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as PlatformDownloadManager

    suspend fun enqueue(
        url: String,
        suggestedName: String?,
        ownerId: String,
        isTemporary: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val marker = if (isTemporary) "stealth_tmp_" else "stealth_"
        val fileName = (marker + (suggestedName ?: "download.bin"))
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val request = PlatformDownloadManager.Request(Uri.parse(url)).apply {
            // setDestinationInExternalPublicDir is deprecated in favor of MediaStore
            // path handling, but remains the supported way to pin a custom name
            // *inside* Publications/Downloads through the platform manager.
            @Suppress("DEPRECATION")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setTitle(fileName)
            setDescription(if (isTemporary) "Temporary session download" else "Stealth Browser download")
            setNotificationVisibility(PlatformDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val platformId = platform().enqueue(request)
        downloadDao.upsert(
            DownloadEntity(
                id = id,
                ownerId = ownerId,
                url = url,
                filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .resolve(fileName).absolutePath,
                fileName = fileName,
                status = DownloadStatus.QUEUED.name,
                bytesTotal = 0L,
                bytesDone = 0L,
                startedAtMs = System.currentTimeMillis(),
                platformId = platformId,
            ),
        )
        id
    }

    /** Deletes temporary-session download files and their rows. */
    suspend fun purgeTemporary(ownerId: String) = withContext(Dispatchers.IO) {
        downloadDao.rowsForOwner(ownerId)
            .filter { it.fileName.startsWith("stealth_tmp_") }
            .forEach { row ->
                File(row.filePath).takeIf { it.exists() }?.delete()
                downloadDao.delete(row.id)
            }
    }

    suspend fun deleteAllForOwner(ownerId: String) = withContext(Dispatchers.IO) {
        downloadDao.rowsForOwner(ownerId).forEach { row ->
            File(row.filePath).takeIf { it.exists() }?.delete()
            downloadDao.delete(row.id)
        }
    }

    /** Live status/progress triple for one row, read from the platform DownloadManager. */
    fun queryPlatformProgress(platformId: Long): Triple<DownloadStatus, Long, Long>? {
        if (platformId <= 0) return null
        val cursor = platform().query(
            PlatformDownloadManager.Query().setFilterById(platformId),
        )
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val status = c.getInt(c.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_BYTES_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val statusName = when (status) {
                PlatformDownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.DONE
                PlatformDownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.RUNNING
            }
            Triple(statusName, done, total)
        }
    }

    suspend fun refreshStatuses(ownerId: String) = withContext(Dispatchers.IO) {
        downloadDao.rowsForOwner(ownerId).forEach { row ->
            queryPlatformProgress(row.platformId)?.let { (status, done, total) ->
                if (row.status != status.name || row.bytesDone != done) {
                    downloadDao.upsert(
                        row.copy(
                            status = status.name,
                            bytesDone = done,
                            bytesTotal = total,
                            finishedAtMs = if (status == DownloadStatus.DONE) System.currentTimeMillis() else null,
                        ),
                    )
                }
            }
        }
    }
}
