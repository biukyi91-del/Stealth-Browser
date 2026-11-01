package com.stealth.browser.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.HistoryDao
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.PermanentProfileOwner
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyDao: HistoryDao,
    private val profileManager: ProfileManager,
    private val enginePool: EnginePool,
) : ViewModel() {

    data class HistoryRow(
        val id: String,
        val profileName: String,
        val title: String,
        val url: String,
        val visitedAtMs: Long,
    )

    private val _rows = MutableStateFlow<List<HistoryRow>>(emptyList())
    val rows: StateFlow<List<HistoryRow>> = _rows

    private val _pendingOpen = MutableStateFlow<Pair<String, String>?>(null)
    val pendingOpen: StateFlow<Pair<String, String>?> = _pendingOpen

    private val profileNames = HashMap<String, String>()

    init {
        viewModelScope.launch {
            // Names refresh on profile changes; rows reload on any change.
            profileManager.observeProfiles().collect { profiles ->
                profileNames.clear()
                profiles.forEach { profileNames[it.id] = it.name }
                reload()
            }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val profiles = profileManager.profiles()
            val collected = mutableListOf<HistoryRow>()
            for (p in profiles.take(6)) { // bounded per-profile history
                historyDao.rowsForProfile(p.id, limit = 120).forEach { h ->
                    collected += HistoryRow(
                        id = h.id,
                        profileName = profileNames[p.id] ?: h.profileId.take(6),
                        title = h.title,
                        url = h.url,
                        visitedAtMs = h.visitedAtMs,
                    )
                }
            }
            collected.sortByDescending { it.visitedAtMs }
            _rows.value = collected.take(400)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            profileManager.profiles().forEach { historyDao.clearForProfile(it.id) }
            reload()
        }
    }

    fun open(url: String) {
        viewModelScope.launch {
            runCatching {
                val profile = profileManager.defaultProfile() ?: return@launch
                val owner = PermanentProfileOwner(
                    profileId = profile.id,
                    privacyConfig = profileManager.privacyOf(profile.id),
                )
                enginePool.createTab(owner = owner, startUrl = url)
                _pendingOpen.value = owner.ownerId to requireNotNull(enginePool.activeTabId.value)
            }
        }
    }

    fun clearPending() {
        _pendingOpen.value = null
    }
}

@Composable
fun HistoryScreen(
    onOpenHistoryEntry: (ownerId: String, tabId: String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val pendingOpen by viewModel.pendingOpen.collectAsState()

    pendingOpen?.let { (ownerId, tabId) ->
        LaunchedEffect(ownerId, tabId) { onOpenHistoryEntry(ownerId, tabId); viewModel.clearPending() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("History", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { viewModel.clearAll() }) { Text("Clear all") }
            }
        }
        if (rows.isEmpty()) {
            item { Text("No history recorded yet.") }
        }
        rows.forEach { row ->
            item(key = row.id) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.open(row.url) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        Text(row.url, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${DateFormat.getDateTimeInstance().format(Date(row.visitedAtMs))} · ${row.profileName}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
