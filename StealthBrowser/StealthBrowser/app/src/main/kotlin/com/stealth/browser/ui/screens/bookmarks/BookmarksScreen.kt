package com.stealth.browser.ui.screens.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.BookmarkDao
import com.stealth.browser.data.db.ProfileDao
import com.stealth.browser.data.db.ProfileEntity
import com.stealth.browser.data.db.BookmarkEntity
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.PermanentProfileOwner
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val profileManager: ProfileManager,
    private val enginePool: EnginePool,
) : ViewModel() {

    data class BookmarkRow(val id: String, val title: String, val url: String)

    private val _rows = MutableStateFlow<List<BookmarkRow>>(emptyList())
    val rows: StateFlow<List<BookmarkRow>> = _rows

    private val _pendingOpen = MutableStateFlow<Pair<String, String>?>(null)
    val pendingOpen: StateFlow<Pair<String, String>?> = _pendingOpen

    init {
        viewModelScope.launch {
            val profile = defaultProfile()
            profile?.id?.let { pid ->
                bookmarkDao.observeForProfile(pid).collect { list ->
                    _rows.value = list.map { BookmarkRow(it.id, it.title, it.url) }
                }
            }
        }
    }

    private suspend fun defaultProfile(): ProfileEntity? {
        val profiles = profileManager.profiles()
        return profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    }

    /** Opens the bookmark in a NEW tab on this bookmark's owning profile. */
    fun open(url: String) {
        viewModelScope.launch {
            runCatching {
                val profile = defaultProfile() ?: return@launch
                val owner = PermanentProfileOwner(
                    profileId = profile.id,
                    privacyConfig = profileManager.privacyOf(profile.id),
                )
                enginePool.createTab(owner = owner, startUrl = url)
                _pendingOpen.value = owner.ownerId to enginePool.activeTabId.value!!
            }
        }
    }

    /** Deletes a bookmark by id (default profile scope). */
    fun remove(bookmarkId: String) {
        viewModelScope.launch {
            runCatching { bookmarkDao.deleteById(bookmarkId) }
        }
    }

    fun clearPending() {
        _pendingOpen.value = null
    }
}

@Composable
fun BookmarksScreen(
    onOpenBookmark: (ownerId: String, tabId: String) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val pendingOpen by viewModel.pendingOpen.collectAsState()

    pendingOpen?.let { (ownerId, tabId) ->
        LaunchedEffect(ownerId, tabId) { onOpenBookmark(ownerId, tabId); viewModel.clearPending() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Bookmarks", style = MaterialTheme.typography.titleLarge) }
        if (rows.isEmpty()) {
            item { Text("No bookmarks yet — tap the star in the address bar while browsing.") }
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
                    }
                }
            }
        }
    }
}
