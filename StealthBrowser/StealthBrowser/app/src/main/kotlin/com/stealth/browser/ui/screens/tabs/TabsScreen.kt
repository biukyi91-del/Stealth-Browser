package com.stealth.browser.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.TabDao
import com.stealth.browser.data.prefs.AppSettings
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TabsViewModel @Inject constructor(
    private val tabDao: TabDao,
    private val enginePool: EnginePool,
    private val sessionManager: SessionManager,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _tabs = MutableStateFlow<List<TabRow>>(emptyList())
    val tabs: StateFlow<List<TabRow>> = _tabs

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    data class TabRow(val id: String, val ownerId: String, val title: String, val url: String?, val temp: Boolean)

    init {
        viewModelScope.launch {
            tabDao.observeAll().collect { rows ->
                _tabs.value = rows.map {
                    TabRow(it.id, it.ownerId, it.title, it.url, it.sessionId != null)
                }
            }
        }
    }

    fun close(tabId: String) {
        viewModelScope.launch { enginePool.closeTab(tabId) }
    }

    val activeTab: String? get() = enginePool.activeTabId.value

    fun closeOwnerSession(sessionId: String) {
        viewModelScope.launch {
            enginePool.closeAllForOwner(sessionId)
            sessionManager.closeTempSession(sessionId)
        }
    }

    // ----- Session-end confirmation (PRD §17 SESSION.requireSessionConfirm) -----
    private val _pendingConfirm = MutableStateFlow<String?>(null)
    val pendingConfirm: StateFlow<String?> = _pendingConfirm

    fun requestEndSession(ownerId: String) {
        viewModelScope.launch {
            if (settings.value.requireSessionConfirm) {
                _pendingConfirm.value = ownerId
            } else {
                closeOwnerSession(ownerId)
            }
        }
    }

    fun dismissConfirm() {
        _pendingConfirm.value = null
    }

    fun confirmEnd() {
        val id = _pendingConfirm.value
        _pendingConfirm.value = null
        if (id != null) closeOwnerSession(id)
    }
}

@Composable
fun TabsScreen(
    onOpenTab: (ownerId: String, tabId: String) -> Unit,
    viewModel: TabsViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val pendingConfirm by viewModel.pendingConfirm.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Open Tabs", style = MaterialTheme.typography.titleLarge)
        }
        if (tabs.isEmpty()) {
            item {
                Text("No open tabs. Start a NEW STEALTH SESSION from Home.")
            }
        }
        tabs.forEach { tab ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(Icons.Filled.Tab, contentDescription = null)
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(tab.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = (tab.url ?: "—") + if (tab.temp) "  · temp session ${tab.ownerId.take(10)}…" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { viewModel.close(tab.id) }) {
                            Icon(Icons.Filled.Close, "Close tab")
                        }
                    }
                }
            }
        }

        // Temporary session lifecycle (PRD §4): one-tap end with confirmation gate.
        val tempOwners = tabs.filter { it.temp }.map { it.ownerId }.distinct()
        if (tempOwners.isNotEmpty()) {
            item {
                Text(
                    "Temporary sessions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            tempOwners.forEach { ownerId ->
                val count = tabs.count { it.ownerId == ownerId }
                item(key = "session-$ownerId") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Session ${ownerId.take(10)}…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "$count tab(s) · all data purged when ended",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { viewModel.requestEndSession(ownerId) }) {
                                Text("End session")
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    if (settings.requireSessionConfirm) {
                        "Confirmation is enabled (Settings → Confirm before closing session)."
                    } else {
                        "Ends immediately — confirmation is disabled in Settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    pendingConfirm?.let { ownerId ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("End temporary session?") },
            text = {
                Text(
                    "Tabs in session ${ownerId.take(10)}… will close and the session's " +
                        "browsing data will be purged now. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = viewModel::confirmEnd) { Text("End session") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirm) { Text("Keep") }
            },
        )
    }
}
