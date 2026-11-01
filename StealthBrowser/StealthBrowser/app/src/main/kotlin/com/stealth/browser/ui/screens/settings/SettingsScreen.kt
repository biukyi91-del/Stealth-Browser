package com.stealth.browser.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.prefs.AppSettings
import com.stealth.browser.data.prefs.ExtensionLoadingPolicy
import com.stealth.browser.data.prefs.SearchEngine
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.data.prefs.StartupBehavior
import com.stealth.browser.data.prefs.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings = settingsStore.settings

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState(initial = AppSettings())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            item { Text("General", style = MaterialTheme.typography.titleMedium) }
            item {
                Card {
                    Column(Modifier.padding(8.dp)) {
                        EnumPicker("Theme", settings.themeMode, ThemeMode.entries.toList()) {
                            viewModel.set { s -> s.copy(themeMode = it) }
                        }
                        EnumPicker("Startup behavior", settings.startupBehavior, StartupBehavior.entries.toList()) {
                            viewModel.set { s -> s.copy(startupBehavior = it) }
                        }
                        EnumPicker("Default search engine", settings.searchEngine, SearchEngine.entries.toList()) {
                            viewModel.set { s -> s.copy(searchEngine = it) }
                        }
                        OutlinedTextField(
                            value = settings.homepageUrl,
                            onValueChange = { url -> viewModel.set { s -> s.copy(homepageUrl = url) } },
                            label = { Text("Homepage") },
                            singleLine = true,
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        )
                    }
                }
            }

            item { Text("Network", style = MaterialTheme.typography.titleMedium) }
            item {
                Card {
                    Toggle("Network kill switch — blocks navigation when a proxy fails", settings.killSwitchEnabled) {
                        viewModel.set { s -> s.copy(killSwitchEnabled = it) }
                    }
                }
            }

            item { Text("Session", style = MaterialTheme.typography.titleMedium) }
            item {
                Card {
                    Column {
                        Toggle("Default to temporary sessions", settings.defaultSessionTypeIsTemporary) {
                            viewModel.set { s -> s.copy(defaultSessionTypeIsTemporary = it) }
                        }
                        Toggle("Automatic cleanup when session closes", settings.autoCleanupOnClose) {
                            viewModel.set { s -> s.copy(autoCleanupOnClose = it) }
                        }
                        Toggle("Confirm before closing session", settings.requireSessionConfirm) {
                            viewModel.set { s -> s.copy(requireSessionConfirm = it) }
                        }
                        EnumPicker(
                            "Extension loading policy",
                            settings.extensionLoadingPolicy,
                            ExtensionLoadingPolicy.entries.toList(),
                        ) { policy ->
                            viewModel.set { s -> s.copy(extensionLoadingPolicy = policy) }
                        }
                    }
                }
            }

            item { Text("Extensions", style = MaterialTheme.typography.titleMedium) }
            item {
                Card {
                    Column {
                        Toggle("Check for extension updates", settings.extensionAutoUpdate) {
                            viewModel.set { s -> s.copy(extensionAutoUpdate = it) }
                        }
                        Toggle("Developer mode (unpacked packages)", settings.extensionDeveloperMode) {
                            viewModel.set { s -> s.copy(extensionDeveloperMode = it) }
                        }
                        Text(
                            "Note: the engine reports extension runtime availability honestly — packages can be installed, validated and permission-reviewed, and live loading is handled by the Chromium extension runtime.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun <E : Enum<E>> EnumPicker(
    label: String,
    current: E,
    options: List<E>,
    onPick: (E) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(current.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) { Text("Choose") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name.replace('_', ' ')) },
                        onClick = { onPick(option); expanded = false },
                    )
                }
            }
        },
    )
}
