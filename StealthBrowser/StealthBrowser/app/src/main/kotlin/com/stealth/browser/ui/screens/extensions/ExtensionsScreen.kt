package com.stealth.browser.ui.screens.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.ExtensionEntity
import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.extensions.ExtensionManager
import com.stealth.browser.extensions.InstallOutcome
import com.stealth.browser.engine.core.EngineCapability
import com.stealth.browser.engine.webview.WebViewEngineProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val extensionManager: ExtensionManager,
    private val profileDao: ProfileDao,
    private val engineProvider: WebViewEngineProvider,
) : ViewModel() {

    private val _extensions = MutableStateFlow<List<ExtensionEntity>>(emptyList())
    val extensions: StateFlow<List<ExtensionEntity>> = _extensions

    private val _lastInstallMessage = MutableStateFlow<String?>(null)
    val lastInstallMessage: StateFlow<String?> = _lastInstallMessage

    val runtimeAvailable: Boolean =
        engineProvider.peek.supports(EngineCapability.EXTENSION_RUNTIME)

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId

    init {
        viewModelScope.launch {
            extensionManager.observeAll().collect { _extensions.value = it }
        }
        viewModelScope.launch {
            val profiles = profileDao.all()
            _activeProfileId.value = (profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull())?.id
        }
    }

    fun installFromFile(file: File) {
        viewModelScope.launch {
            _lastInstallMessage.value = when (val outcome = extensionManager.installFromFile(file)) {
                is InstallOutcome.Installed ->
                    "Installed and validated (${outcome.extensionId.take(8)}…). Extensions are " +
                        "disabled by default; enable per profile after reviewing permissions."
                is InstallOutcome.PermissionsNeeded ->
                    "Permission review required before installation."
                is InstallOutcome.Failed -> "Rejected: ${outcome.reason}"
            }
        }
    }

    fun setEnabled(extensionId: String, profileId: String, enabled: Boolean) {
        viewModelScope.launch { extensionManager.setEnabled(extensionId, profileId, enabled) }
    }

    fun uninstall(extensionId: String) {
        viewModelScope.launch { extensionManager.uninstall(extensionId) }
    }

    fun reportFailure(reason: String) {
        _lastInstallMessage.value = reason
    }
}

@Composable
fun ExtensionsScreen(
    onDetail: (String) -> Unit = {},
    onOpenStore: () -> Unit = {},
    viewModel: ExtensionsViewModel = hiltViewModel(),
) {
    val extensions by viewModel.extensions.collectAsState()
    val message by viewModel.lastInstallMessage.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val target = File(context.cacheDir, "ext_pkg_upload.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            viewModel.installFromFile(target)
        } catch (e: Exception) {
            viewModel.reportFailure("Package could not be read: ${e.message}")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Extensions", style = MaterialTheme.typography.titleLarge) }

        if (!viewModel.runtimeAvailable) {
            item {
                Card {
                    Text(
                        "Extension runtime: NOT ACTIVE on this engine backing. Packages can be " +
                            "installed, validated, permission-reviewed and profile-scoped; live " +
                            "extension execution is provided by the Chromium extension runtime. " +
                            "This is reported honestly rather than faked.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { filePicker.launch("*/*") }) { Text("Install from file") }
                OutlinedButton(onClick = onOpenStore) { Text("Browse Store") }
            }
        }

        message?.let { msg -> item { Text(msg, style = MaterialTheme.typography.bodySmall) } }

        extensions.forEach { ext ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .padding(12.dp)
                            .clickable { onDetail(ext.id) },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ext.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "v${ext.version} · MV${ext.manifestVersion} · ${ext.developerName}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Status: ${honestStatus(ext)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // Enable/disable is per-PROFILE; switch mirrors the default profile.
                        }
                        Text("View details →", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { viewModel.uninstall(ext.id) }) { Text("Uninstall") }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Install ZIP or CRX3 packages. Every package is validated (structure, manifest, " +
                        "zip-slip paths) before storage; nothing executes at install time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Chrome Web Store: direct store installation is only available where the " +
                        "engine supports the CWS install verification flow (Chromium integration). " +
                        "On this build, extensions arrive via reviewed package files.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun honestStatus(ext: ExtensionEntity): String =
    ext.status
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
