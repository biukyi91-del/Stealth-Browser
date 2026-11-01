package com.stealth.browser.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.prefs.SearchEngine
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.engine.core.EngineCapability
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.PermanentProfileOwner
import com.stealth.browser.session.ProfileManager
import com.stealth.browser.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PrivacyStatusRow(
    val label: String,
    val active: Boolean,
    val supportedByEngine: Boolean,
)

data class HomeUiState(
    val currentProfileName: String? = null,
    val isCreatingSession: Boolean = false,
    val pendingNavigation: Pair<String, String>? = null, // ownerId to tabId
    val privacyStatuses: List<PrivacyStatusRow> = emptyList(),
    val networkLabel: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val sessionManager: SessionManager,
    private val enginePool: EnginePool,
    private val settingsStore: SettingsStore,
    private val extensionManager: com.stealth.browser.extensions.ExtensionManager,
    private val networkManager: com.stealth.browser.network.NetworkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    val searchEngines = SearchEngine.entries.toList()

    /** Installed extensions for the PRD §6 option-B picker. */
    data class ExtensionChoice(val id: String, val name: String, val version: String)

    private val _extensionChoices = MutableStateFlow<List<ExtensionChoice>>(emptyList())
    val extensionChoices: StateFlow<List<ExtensionChoice>> = _extensionChoices

    init {
        viewModelScope.launch {
            profileManager.observeProfiles().collect { profiles ->
                val defaultName =
                    profiles.firstOrNull { it.isDefault }?.name ?: profiles.firstOrNull()?.name
                _state.value = _state.value.copy(currentProfileName = defaultName)
            }
        }
        refreshPrivacyStatuses()
    }

    /** Statuses come from the engine capability matrix — nothing is shown unless real (PRD §16). */
    private fun refreshPrivacyStatuses() {
        val peek = enginePool.enginePeek
        _state.value = _state.value.copy(
            privacyStatuses = listOf(
                PrivacyStatusRow("Fingerprint scripting", true, peek.supports(EngineCapability.FINGERPRINT_SCRIPTING)),
                PrivacyStatusRow("Storage isolation", true, peek.supports(EngineCapability.PROFILE_STORAGE_ISOLATION)),
                PrivacyStatusRow("Session cleanup", true, peek.supports(EngineCapability.SESSION_CLEANUP)),
                PrivacyStatusRow("Proxy routing", true, peek.supports(EngineCapability.PROXY)),
                PrivacyStatusRow("Tracker filtering", true, peek.supports(EngineCapability.REQUEST_FILTERING)),
                PrivacyStatusRow("Capture prevention", true, peek.supports(EngineCapability.WEBRTC_CAPTURE_PREVENTION)),
                PrivacyStatusRow("Secure DNS (DoH)", false, peek.supports(EngineCapability.DOH_IN_ENGINE)),
            ),
            networkLabel = if (peek.supports(EngineCapability.PROXY)) "Engine proxy available" else "Proxy unsupported",
        )
    }

    /** PRD §6 ONE-CLICK fresh session. [approvedExtensionIds] = explicitly approved for THIS session. */
    fun newStealthSession(approvedExtensionIds: Set<String>) {
        if (_state.value.isCreatingSession) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isCreatingSession = true)
            runCatching {
                val settings = settingsStore.current()
                val sourceProfile = profileManager.defaultProfile()
                val owner = sessionManager.newTempSession(
                    sourceProfileId = sourceProfile?.id,
                    approvedExtensionIds = approvedExtensionIds,
                )
                // New owner's network = its profile binding; MUST be applied before
                // the controller is created (proxy is process-wide at creation).
                networkManager.applyForOwner(owner.ownerId)
                val tabId = enginePool.createTab(owner = owner, startUrl = settings.homepageUrl)
                _state.value = _state.value.copy(
                    isCreatingSession = false,
                    pendingNavigation = owner.ownerId to tabId,
                )
            }.onFailure {
                _state.value = _state.value.copy(isCreatingSession = false)
            }
        }
    }

    /** Clears the pending navigation flag (consumed by the screen when it has navigated). */
    fun consumeNavigation() {
        _state.value = _state.value.copy(pendingNavigation = null)
    }

    /** PRD §13.7/§6: load installed extensions for the approval picker. */
    fun loadExtensionChoices() {
        viewModelScope.launch {
            _extensionChoices.value = extensionManager.observeAll().first().map {
                ExtensionChoice(it.id, it.name, it.version)
            }
        }
    }

    /** Search bar: creates a tab on the DEFAULT PERMANENT profile (session-safe). */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val engine = settingsStore.current().searchEngine
                val url = when {
                    query.startsWith("http://") || query.startsWith("https://") -> query
                    query.contains('.') && !query.contains(' ') -> "https://$query"
                    else -> engine.queryUrl + java.net.URLEncoder.encode(query, "UTF-8")
                }
                val profile = profileManager.defaultProfile() ?: profileManager.createProfile("Default")
                val owner = PermanentProfileOwner(
                    profileId = profile.id,
                    privacyConfig = profileManager.privacyOf(profile.id),
                )
                sessionManager.activateOwner(owner)
                val ownerId = owner.ownerId
                networkManager.applyForOwner(ownerId)
                val tabId = enginePool.createTab(owner = owner, startUrl = url)
                _state.value = _state.value.copy(pendingNavigation = ownerId to tabId)
            }
        }
    }
}

@Composable
fun HomeScreen(
    onNewSession: (ownerId: String, tabId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenOnboarding: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val extensionChoices by viewModel.extensionChoices.collectAsState()

    var showSessionOptions by remember { mutableStateOf(false) }
    var pickedExtensions by remember { mutableStateOf(setOf<String>()) }

    // Session options dialog (PRD §6 A/B) — only when extensions are installed.
    LaunchedEffect(Unit) { viewModel.loadExtensionChoices() }

    state.pendingNavigation?.let { (ownerId, tabId) ->
        LaunchedEffect(ownerId, tabId) {
            onNewSession(ownerId, tabId)
            viewModel.consumeNavigation()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Stealth Browser", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Profile: ${state.currentProfileName ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onOpenOnboarding) { Icon(Icons.Filled.Shield, "Introduction") }
                IconButton(onClick = onOpenExtensions) { Icon(Icons.Filled.Extension, "Extensions") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }

        item { SearchField(onSearch = viewModel::search) }

        item {
            Button(
                onClick = {
                    if (extensionChoices.isEmpty()) {
                        viewModel.newStealthSession(emptySet())
                    } else {
                        showSessionOptions = true
                    }
                },
                enabled = !state.isCreatingSession,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isCreatingSession) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                    Text("  Creating fresh environment…")
                } else {
                    Text("+ NEW STEALTH SESSION")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenProfiles, modifier = Modifier.weight(1f)) { Text("Profiles") }
                FilledTonalButton(onClick = onOpenTabs, modifier = Modifier.weight(1f)) { Text("Open Tabs") }
                FilledTonalButton(onClick = onOpenPrivacy, modifier = Modifier.weight(1f)) { Text("Privacy") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenBookmarks, modifier = Modifier.weight(1f)) { Text("Bookmarks") }
                FilledTonalButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) { Text("History") }
                FilledTonalButton(onClick = onOpenDownloads, modifier = Modifier.weight(1f)) { Text("Downloads") }
            }
        }

        item {
            FilledTonalButton(
                onClick = onOpenNetwork,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Network & proxies") }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Privacy Status", style = MaterialTheme.typography.titleMedium)
                    state.privacyStatuses.forEach { row ->
                        val activeShown = row.active && row.supportedByEngine
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (activeShown) Color(0xFF68D391) else Color(0xFF8B94A3),
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = row.label + when {
                                    !row.supportedByEngine -> " (unsupported by current engine)"
                                    row.active -> ""
                                    else -> " (off)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text("Network: ${state.networkLabel ?: "—"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            TextButton(onClick = onOpenOnboarding) { Text("Privacy introduction") }
        }
    }

    if (showSessionOptions) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSessionOptions = false },
            title = { Text("Start stealth session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PRD §6 — temporary sessions do not inherit extensions automatically. " +
                            "Start fresh (A), or explicitly approve extensions for THIS session (B). " +
                            "Extensions can never access another profile's data.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showSessionOptions = false
                                viewModel.newStealthSession(emptySet())
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("A · No extensions") }
                    }
                    Text("B · Approve for this session:", style = MaterialTheme.typography.titleSmall)
                    extensionChoices.forEach { choice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = choice.id in pickedExtensions,
                                onCheckedChange = { checked ->
                                    pickedExtensions = if (checked) {
                                        pickedExtensions + choice.id
                                    } else {
                                        pickedExtensions - choice.id
                                    }
                                },
                            )
                            Text("${choice.name} v${choice.version}")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = pickedExtensions.isNotEmpty(),
                    onClick = {
                        showSessionOptions = false
                        viewModel.newStealthSession(pickedExtensions)
                    },
                ) { Text("Start with approved") }
            },
            dismissButton = { TextButton(onClick = { showSessionOptions = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchField(onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search or enter URL") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { onSearch(query); query = "" }) { Icon(Icons.Filled.Search, "Search") }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
