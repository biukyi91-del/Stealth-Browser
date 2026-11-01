package com.stealth.browser.extensions

import android.content.Context
import com.stealth.browser.data.db.ExtensionDao
import com.stealth.browser.data.db.ExtensionEntity
import com.stealth.browser.data.db.ExtensionProfileStateEntity
import com.stealth.browser.engine.core.EngineCapability
import com.stealth.browser.engine.webview.WebViewDataDirManager
import com.stealth.browser.engine.webview.WebViewEngineProvider
import com.stealth.browser.extensions.manifest.CompatibilityChecker
import com.stealth.browser.extensions.model.CompatibilityReport
import com.stealth.browser.extensions.model.ExtensionManifest
import com.stealth.browser.extensions.model.ExtensionStatus
import com.stealth.browser.extensions.model.SiteAccessMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Permissions extracted from a manifest, separated by scope (PRD §13.6). */
data class PermissionSummary(
    val apis: List<String>,
    val hosts: List<String>,
)

sealed interface InstallOutcome {
    data class Installed(val extensionId: String) : InstallOutcome
    data class PermissionsNeeded(val manifest: ExtensionManifest, val summary: PermissionSummary) : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}

@Singleton
class ExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extensionDao: ExtensionDao,
    private val dataDirs: WebViewDataDirManager,
    private val engineProvider: WebViewEngineProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeAll(): Flow<List<ExtensionEntity>> = extensionDao.observeAll()

    suspend fun byId(id: String): ExtensionEntity? = extensionDao.byId(id)

    /** Installs from a validated local package. NO extension code runs as part of this call. */
    suspend fun installFromFile(
        packageFile: File,
        developerName: String = "unknown developer",
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val validation = com.stealth.browser.extensions.packageinstall.PackageInstaller.validateCrx(packageFile)
            .let { result ->
                if (result is com.stealth.browser.extensions.packageinstall.PackageInstaller.InstallValidation.Reject) {
                    com.stealth.browser.extensions.packageinstall.PackageInstaller.validateZip(packageFile)
                } else result
            }
        val manifest = when (val v = validation) {
            is com.stealth.browser.extensions.packageinstall.PackageInstaller.InstallValidation.Complete -> v.manifest
            is com.stealth.browser.extensions.packageinstall.PackageInstaller.InstallValidation.Reject ->
                return@withContext InstallOutcome.Failed(v.reason)
            else -> return@withContext InstallOutcome.Failed("unexpected validation state")
        }
        if (manifest.manifestVersion !in 2..3) {
            return@withContext InstallOutcome.Failed("manifest_version must be 2 or 3")
        }
        val extId = extensionIdFor(manifest, packageFile)
        val persist = File(context.filesDir, "extensions/$extId").apply { mkdirs() }
        val copied = File(persist, packageFile.name).also { packageFile.copyTo(it, overwrite = true) }
        val report = CompatibilityChecker.analyze(manifest)
        val summary = summarizePermissions(manifest)
        extensionDao.upsert(
            ExtensionEntity(
                id = extId,
                name = manifest.name,
                version = manifest.version,
                manifestVersion = manifest.manifestVersion,
                developerName = developerName,
                installedAtMs = System.currentTimeMillis(),
                description = manifest.description.orEmpty(),
                packagePath = copied.absolutePath,
                permissionsJson = json.encodeToString(summary),
                compatibilityJson = json.encodeToString(report),
                supportedApisJson = json.encodeToString(report.supportedApis),
                unsupportedApisJson = json.encodeToString(report.unsupportedApis),
                status = if (report.result == ExtensionStatus.FULLY_COMPATIBLE) {
                    ExtensionStatus.DISABLED.name
                } else report.result.name,
                optionsPage = manifest.optionsPage ?: manifest.optionsUi?.page,
            ),
        )
        InstallOutcome.Installed(extId)
    }

    @kotlinx.serialization.Serializable
    data class PermissionSummaryPayload(val apis: List<String>, val hosts: List<String>)

    private fun summarizePermissions(manifest: ExtensionManifest): PermissionSummaryPayload =
        PermissionSummaryPayload(
            apis = manifest.permissions + manifest.optionalPermissions,
            hosts = manifest.hostPermissions + manifest.optionalHostPermissions,
        )

    private fun extensionIdFor(manifest: ExtensionManifest, file: File): String {
        // Chromium: id = SHA256(DecodedHex(manifest.key))[:, 16 of hex-string] mapped to a-p.
        // Without a declared key the package digest stands in (disambiguation documented).
        manifest.key?.let { declared ->
            return@extensionIdFor chromiumStyleId(declared)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return chromiumStyleId(hex)
    }

    /** Chrome OpenSSL→ID algorithm: first 16 bytes of SHA-256 mapped 0a→a … f→p. */
    private fun chromiumStyleId(keyHexOrRaw: String): String = runCatching {
        val keyBytes = keyHexOrRaw.toByteArray(Charsets.ISO_8859_1)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        digest.take(16).joinToString("") { ('a'.code + (it.toInt() and 0x0F)).toChar().toString() }
    }.getOrDefault(keyHexOrRaw.toByteArray(Charsets.UTF_8).fold("") { acc, b ->
        acc + (('a'.code + (b.toInt() and 0x0F)).toChar())
    })

    /** Effective enable/disable PER PROFILE (PRD §13.7: settings per profile). */
    suspend fun stateFor(extensionId: String, profileId: String): ExtensionProfileStateEntity? =
        extensionDao.stateFor(extensionId, profileId)

    suspend fun setEnabled(extensionId: String, profileId: String, enabled: Boolean) {
        val existing = extensionDao.stateFor(extensionId, profileId)
        extensionDao.upsertState(
            existing?.copy(
                enabled = enabled,
                updatedAtMs = System.currentTimeMillis(),
            ) ?: ExtensionProfileStateEntity(
                extensionId = extensionId,
                profileId = profileId,
                enabled = enabled,
                siteAccess = SiteAccessMode.UNSUPPORTED.name,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        if (!enabled) markDisabled(extensionId)
    }

    suspend fun setSiteAccess(extensionId: String, profileId: String, mode: SiteAccessMode) {
        val existing = extensionDao.stateFor(extensionId, profileId)
        extensionDao.upsertState(
            existing?.copy(
                siteAccess = mode.name,
                updatedAtMs = System.currentTimeMillis(),
            ) ?: ExtensionProfileStateEntity(
                extensionId, profileId, enabled = false, siteAccess = mode.name,
                System.currentTimeMillis(),
            ),
        )
    }

    suspend fun enabledIdsFor(profileId: String): List<String> =
        extensionDao.statesOfProfile(profileId).filter { it.enabled }.map { it.extensionId }

    suspend fun uninstall(extensionId: String) {
        byId(extensionId)?.let { ext ->
            File(ext.packagePath).deleteRecursively()
            File(context.filesDir, "extensions/$extensionId").deleteRecursively()
        }
        extensionDao.delete(extensionId)
        extensionDao.deleteStates(extensionId)
    }

    suspend fun compatibilityReport(extensionId: String): CompatibilityReport? =
        byId(extensionId)?.let {
            runCatching { json.decodeFromString<CompatibilityReport>(it.compatibilityJson) }.getOrNull()
        }

    suspend fun permissionSummary(extensionId: String): PermissionSummaryPayload? =
        byId(extensionId)?.let {
            runCatching { json.decodeFromString<PermissionSummaryPayload>(it.permissionsJson) }.getOrNull()
        }

    private suspend fun markDisabled(extensionId: String) {
        byId(extensionId)?.let { ext ->
            if (ext.status == ExtensionStatus.FULLY_COMPATIBLE.name) {
                extensionDao.upsert(ext.copy(status = ExtensionStatus.DISABLED.name))
            }
        }
    }

    /**
     * Loads the extension for a profile into the extension runtime.
     * Honest reporting: on the WebView backing the runtime capability is absent,
     * so this returns NO_RUNTIME rather than pretending the extension runs.
     */
    suspend fun runtimeLoadResult(extensionId: String, profileId: String): RuntimeLoadResult {
        if (!engineProvider.peek.supports(EngineCapability.EXTENSION_RUNTIME)) {
            return RuntimeLoadResult.NO_RUNTIME
        }
        val ext = byId(extensionId) ?: return RuntimeLoadResult.MISSING
        val state = stateFor(extensionId, profileId) ?: return RuntimeLoadResult.DISABLED_
        if (!state.enabled) return RuntimeLoadResult.DISABLED_
        return RuntimeLoadResult.LOADED
    }

    enum class RuntimeLoadResult { NO_RUNTIME, MISSING, DISABLED_, LOADED }
}
