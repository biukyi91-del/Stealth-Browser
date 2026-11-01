package com.stealth.browser.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.ExtensionEntity
import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.extensions.ExtensionManager
import com.stealth.browser.extensions.model.CompatibilityReport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PRD §13.2 detail view + §13.6 permission screen + §13.10 compatibility report.
 * Site access rows are ONLY shown for modes the engine honestly enforces.
 */
@HiltViewModel
class ExtensionDetailViewModel @Inject constructor(
    private val extensionManager: ExtensionManager,
    private val profileDao: ProfileDao,
) : ViewModel() {

    private val _extension = MutableStateFlow<ExtensionEntity?>(null)
    val extension: StateFlow<ExtensionEntity?> = _extension

    private val _compat = MutableStateFlow<CompatibilityReport?>(null)
    val compat: StateFlow<CompatibilityReport?> = _compat

    private val _permissions = MutableStateFlow<ExtensionManager.PermissionSummaryPayload?>(null)
    val permissions: StateFlow<ExtensionManager.PermissionSummaryPayload?> = _permissions

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId

    private val _enabledState = MutableStateFlow(false)
    val enabledState: StateFlow<Boolean> = _enabledState

    fun load(extensionId: String) {
        viewModelScope.launch {
            _extension.value = extensionManager.byId(extensionId)
            _compat.value = extensionManager.compatibilityReport(extensionId)
            _permissions.value = extensionManager.permissionSummary(extensionId)
            val profiles = profileDao.all()
            val profileId = profiles.firstOrNull { it.isDefault }?.id
                ?: profiles.firstOrNull()?.id
            _activeProfileId.value = profileId
            profileId?.let { pid ->
                _enabledState.value = extensionManager.stateFor(extensionId, pid)?.enabled ?: false
            }
        }
    }

    fun setEnabled(extensionId: String, enabled: Boolean) {
        val pid = _activeProfileId.value ?: return
        viewModelScope.launch {
            extensionManager.setEnabled(extensionId, pid, enabled)
            _enabledState.value = enabled
        }
    }

    fun uninstall(extensionId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            extensionManager.uninstall(extensionId)
            onDone()
        }
    }
}

@Composable
fun ExtensionDetailScreen(
    extensionId: String,
    onBack: () -> Unit,
    viewModel: ExtensionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(extensionId) { viewModel.load(extensionId) }

    val extension by viewModel.extension.collectAsState()
    val compat by viewModel.compat.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val enabled by viewModel.enabledState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(extension?.name ?: "Extension") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            extension?.let { ext ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ext.name, style = MaterialTheme.typography.titleMedium)
                            Text("Version ${ext.version} · Manifest V${ext.manifestVersion}", style = MaterialTheme.typography.bodySmall)
                            Text("Developer: ${ext.developerName}", style = MaterialTheme.typography.bodySmall)
                            Text("Installed: ${ext.installedAtMs.toInstalledDate()}", style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${ext.status.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                            ext.description.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("Enabled for active profile") },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = {
                                viewModel.setEnabled(extensionId, it)
                            })
                        },
                    )
                }

                permissions?.let { perms ->
                    item { Text("Requested permissions", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (perms.apis.isEmpty() && perms.hosts.isEmpty()) {
                                    Text("No permissions requested.", style = MaterialTheme.typography.bodySmall)
                                }
                                perms.apis.forEach { Text("• API: $it", style = MaterialTheme.typography.bodySmall) }
                                perms.hosts.forEach {
                                    Text("• Read/act on site: $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                compat?.let { report ->
                    item { Text("Compatibility report", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Manifest V${report.manifestVersion}")
                                Text("Result: ${report.result.name.lowercase().replace('_', ' ')}")
                                if (report.supportedApis.isNotEmpty()) {
                                    Text("Supported APIs:", style = MaterialTheme.typography.titleSmall)
                                    report.supportedApis.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (report.unsupportedApis.isNotEmpty()) {
                                    Text(
                                        "Unsupported APIs:",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    report.unsupportedApis.forEach {
                                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                report.blockedReasons.forEach {
                                    Text("– $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = { viewModel.uninstall(extensionId) { onBack() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Uninstall") }
                }
            }
        }
    }
}

private fun Long.toInstalledDate(): String =
    java.text.DateFormat.getDateInstance().format(java.util.Date(this))
