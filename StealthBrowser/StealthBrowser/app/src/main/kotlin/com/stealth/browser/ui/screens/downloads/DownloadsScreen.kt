package com.stealth.browser.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
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
import com.stealth.browser.data.db.DownloadDao
import com.stealth.browser.downloads.DownloadCoordinator
import com.stealth.browser.downloads.DownloadStatus
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadDao: DownloadDao,
    private val coordinator: DownloadCoordinator,
    private val profileManager: ProfileManager,
) : ViewModel() {

    data class DownloadRow(
        val id: String,
        val fileName: String,
        val status: String,
        val progressPercent: Float,
        val startedAtMs: Long,
    )

    private val _rows = MutableStateFlow<List<DownloadRow>>(emptyList())
    val rows: StateFlow<List<DownloadRow>> = _rows

    /** Runs a light poll loop while the ViewModel lives (progress from platform manager). */
    fun poll() {
        viewModelScope.launch {
            while (kotlin.coroutines.coroutineContext.isActive) {
                refreshLive()
                delay(1_500)
            }
        }
    }

    private suspend fun refreshLive() {
        profileManager.profiles().forEach { profile ->
            coordinator.refreshStatuses(profile.id)
        }
        val entities = profileManager.profiles().flatMap { profile ->
            downloadDao.rowsForOwner(profile.id)
        }
        _rows.value = toRows(entities)
    }

    private fun toRows(entities: List<com.stealth.browser.data.db.DownloadEntity>): List<DownloadRow> =
        entities.map { entity ->
            DownloadRow(
                id = entity.id,
                fileName = entity.fileName,
                status = entity.status,
                progressPercent = when {
                    entity.status == DownloadStatus.DONE.name -> 1f
                    entity.bytesTotal > 0 -> entity.bytesDone.toFloat() / entity.bytesTotal
                    else -> 0f
                },
                startedAtMs = entity.startedAtMs,
            )
        }

    fun clearAll() {
        viewModelScope.launch {
            profileManager.profiles().forEach { coordinator.deleteAllForOwner(it.id) }
            refreshLive()
        }
    }
}

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()

    LaunchedEffect(Unit) { viewModel.poll() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Downloads", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { viewModel.clearAll() }) { Text("Clear") }
            }
        }
        if (rows.isEmpty()) {
            item { Text("No downloads. Files you download appear here.") }
        }
        rows.forEach { row ->
            item(key = row.id) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${row.status.lowercase()} · " +
                                DateFormat.getDateTimeInstance().format(Date(row.startedAtMs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (row.status == DownloadStatus.RUNNING.name) {
                            LinearProgressIndicator(progress = { row.progressPercent })
                        }
                    }
                }
            }
        }
    }
}
