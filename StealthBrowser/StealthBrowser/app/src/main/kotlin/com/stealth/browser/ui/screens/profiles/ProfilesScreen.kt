package com.stealth.browser.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.BookmarkDao
import com.stealth.browser.data.db.DownloadDao
import com.stealth.browser.data.db.ExtensionDao
import com.stealth.browser.data.db.HistoryDao
import com.stealth.browser.data.db.ProfileEntity
import com.stealth.browser.data.db.SitePermissionDao
import com.stealth.browser.data.db.TabDao
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val enginePool: EnginePool,
    private val tabDao: TabDao,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val extensionDao: ExtensionDao,
    private val sitePermissionDao: SitePermissionDao,
    private val downloadDao: DownloadDao,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> = profileManager.observeProfiles()
        .let { flow ->
            val state = MutableStateFlow<List<ProfileEntity>>(emptyList())
            viewModelScope.launch {
                flow.collect { state.value = it }
            }
            state
        }

    private val _backupPreview = MutableStateFlow<String?>(null)
    val backupPreview: StateFlow<String?> = _backupPreview

    fun create(name: String) {
        viewModelScope.launch { runCatching { profileManager.createProfile(name) } }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { runCatching { profileManager.renameProfile(id, name) } }
    }

    /**
     * Full deletion cascade for a permanent profile (PRD §17 PROFILE):
     * live tabs first, then all rows scoped to profileId/ownerId. The profile
     * row itself is removed last so a mid-cascade failure can't orphan state.
     */
    fun delete(id: String) {
        viewModelScope.launch {
            runCatching {
                enginePool.closeAllForOwner(id)
                tabDao.deleteForOwner(id)
                bookmarkDao.deleteForProfile(id)
                historyDao.clearForProfile(id)
                extensionDao.deleteStatesForProfile(id)
                sitePermissionDao.clearForOwner(id) // permanent owner id == profileId
                downloadDao.deleteForOwner(id)
                profileManager.deleteProfile(id)
            }
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch { profileManager.setDefault(id) }
    }

    fun backup(id: String) {
        viewModelScope.launch { _backupPreview.value = profileManager.backup(id) }
    }

    fun restore(json: String) {
        viewModelScope.launch { profileManager.restore(json) }
    }

    fun clearBackupPreview() {
        _backupPreview.value = null
    }
}

@Composable
fun ProfilesScreen(viewModel: ProfilesViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsState()
    val backupPreview by viewModel.backupPreview.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Profiles", style = MaterialTheme.typography.titleLarge) }
        item { Text("Each profile keeps independent cookies, history, bookmarks, storage, permissions, extensions and network settings.", style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = true }) { Text("Create profile") }
            }
        }
        profiles.forEach { profile ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = profile.isDefault,
                                onClick = { viewModel.setDefault(profile.id) },
                            )
                            Text(profile.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        Text(
                            "Extensions, permissions & network scoped to this profile.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { renameTarget = profile.id to profile.name }) { Text("Rename") }
                            TextButton(onClick = { viewModel.backup(profile.id) }) { Text("Backup") }
                            TextButton(onClick = { deleteTarget = profile.id to profile.name }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = restoreText,
                onValueChange = { restoreText = it },
                label = { Text("Paste backup JSON to restore") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { viewModel.restore(restoreText); restoreText = "" },
                enabled = restoreText.isNotBlank(),
            ) { Text("Restore profile settings") }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New profile") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.create(name); showCreate = false },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { (id, currentName) ->
        var name by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename profile") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { viewModel.rename(id, name); renameTarget = null },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete profile \"${name}\"?") },
            text = {
                Text(
                    "All of this profile's tabs, bookmarks, history, extension state, " +
                        "site permissions and downloads are deleted with it. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.delete(id); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    backupPreview?.let { json ->
        AlertDialog(
            onDismissRequest = viewModel::clearBackupPreview,
            title = { Text("Profile backup (settings only)") },
            text = { Text(json) },
            confirmButton = {
                TextButton(onClick = viewModel::clearBackupPreview) { Text("Done") }
            },
        )
    }
}
