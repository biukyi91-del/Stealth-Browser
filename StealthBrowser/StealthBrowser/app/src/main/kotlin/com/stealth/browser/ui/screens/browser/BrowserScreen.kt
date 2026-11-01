package com.stealth.browser.ui.screens.browser

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.BookmarkDao
import com.stealth.browser.data.db.BookmarkEntity
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.engine.core.EngineController
import com.stealth.browser.engine.core.EngineEvent
import com.stealth.browser.network.NetworkManager
import com.stealth.browser.network.SuggestionClient
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.ProfileManager
import com.stealth.browser.session.SessionManager
import com.stealth.browser.session.SitePermissionGate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SitePermissionPrompt(
    val host: String,
    val resources: Array<String>,
    val grant: (Array<String>) -> Unit,
    val deny: () -> Unit,
)

data class BrowserUiState(
    val address: String = "",
    val title: String? = null,
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val proxyFailure: String? = null,
    val isBookmarked: Boolean = false,
    val desktopMode: Boolean = false,
    val findOpen: Boolean = false,
    val findQuery: String = "",
    val findMatches: Int = 0,
    val activeMatchIndex: Int = 0,
    val permissionPrompt: SitePermissionPrompt? = null,
    /** Suggestions fetched from the search engine's real suggestion endpoint. */
    val suggestions: List<String> = emptyList(),
    val showSuggestions: Boolean = false,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val enginePool: EnginePool,
    private val networkManager: NetworkManager,
    private val bookmarkDao: BookmarkDao,
    private val profileManager: ProfileManager,
    private val permissionGate: SitePermissionGate,
    private val sessionManager: SessionManager,
    private val suggestionClient: SuggestionClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state

    /** URL waiting for the Compose layer to hand to the system share sheet. */
    private val _shareRequest = MutableStateFlow<String?>(null)
    val shareRequest: StateFlow<String?> = _shareRequest

    private var tabIdRef: String? = null

    fun bind(tabId: String) {
        tabIdRef = tabId
    }

    fun start() {
        viewModelScope.launch {
            enginePool.eventBus.collect { handle(it) }
        }
        viewModelScope.launch {
            networkManager.status.collect { st ->
                if (st.killSwitchEngaged) {
                    setState {
                        copy(
                            proxyFailure = st.failureMessage
                                ?: "Proxy unreachable. Navigation halted — no silent direct fallback.",
                        )
                    }
                }
            }
        }
    }

    private fun handle(event: EngineEvent) {
        val controller = tabIdRef?.let { enginePool.controllerFor(it) }
        when (event) {
            is EngineEvent.PageStarted -> setState { copy(address = event.url, progress = 0) }
            is EngineEvent.Progress -> setState { copy(progress = event.progress) }
            is EngineEvent.TitleChanged -> setState { copy(title = event.title) }
            is EngineEvent.PageFinished -> {
                setState { copy(progress = 100) }
                refreshBookmarkState(event.url)
            }
            is EngineEvent.ProxyFailure -> setState {
                copy(
                    proxyFailure =
                    "Proxy failure: ${event.message}. Navigation stopped — direct fallback is disabled.",
                )
            }
            is EngineEvent.RenderFailed -> setState {
                copy(proxyFailure = proxyFailure ?: "Engine notification: ${event.description}")
            }
            is EngineEvent.SitePermissionRequested -> {
                val host = runCatching { java.net.URI(event.url).host }.getOrNull()
                if (host != null && event.resources.isNotEmpty()) {
                    setState {
                        copy(
                            permissionPrompt = SitePermissionPrompt(
                                host = host,
                                resources = event.resources.copyOf(),
                                grant = event.grant,
                                deny = event.deny,
                            ),
                        )
                    }
                }
            }
            else -> Unit
        }
        refreshNavState(controller)
    }

    private inline fun setState(transform: BrowserUiState.() -> BrowserUiState) {
        _state.value = _state.value.transform()
    }

    private fun refreshNavState(controller: EngineController?) {
        setState {
            copy(
                canGoBack = controller?.canGoBack() ?: false,
                canGoForward = controller?.canGoForward() ?: false,
            )
        }
    }

    fun controllerFor(tabId: String): EngineController? = enginePool.controllerFor(tabId)

    // ----- Address bar / navigation -----
    fun updateAddress(text: String) = setState { copy(address = text) }

    private var suggestJob: Job? = null

    /**
     * Called only for USER-EDITED address text. Programmatic updates (page loads,
     * voice results) use [updateAddress]. Debounced ~300ms; queries the search
     * engine's real suggestion endpoint; engines without one return none (honest).
     */
    fun onAddressEdited(text: String) {
        setState { copy(address = text) }
        suggestJob?.cancel()
        if (text.length < 2 || looksLikeUrl(text)) {
            setState { copy(showSuggestions = false, suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            // ~300ms debounce; canceled by the next edit.
            delay(300)
            val engine = settingsStore.current().searchEngine
            val items = suggestionClient.fetch(engine, text)
            setState {
                // Stale response guard: only raise suggestions for the current text.
                if (address == text) copy(showSuggestions = true, suggestions = items) else this
            }
        }
    }

    fun pickSuggestion(s: String) {
        suggestJob?.cancel()
        setState { copy(address = s, showSuggestions = false, suggestions = emptyList()) }
    }

    /** Suggestions are pointless for URL-shaped input — PRD-honest, no mixed UX. */
    private fun looksLikeUrl(text: String): Boolean =
        text.startsWith("http://") || text.startsWith("https://") ||
            (!text.contains(' ') && text.contains('.'))

    fun submitAddress(tabId: String) {
        suggestJob?.cancel()
        setState { copy(showSuggestions = false) }
        val target = _state.value.address
        if (target.isNotBlank()) enginePool.controllerFor(tabId)?.loadUrl(target)
    }

    fun back(tabId: String) = enginePool.controllerFor(tabId)?.goBack()
    fun forward(tabId: String) = enginePool.controllerFor(tabId)?.goForward()
    fun reload(tabId: String) = enginePool.controllerFor(tabId)?.reload()

    fun dismissFailure() = setState { copy(proxyFailure = null) }

    // ----- Find on page (real engine find) -----
    fun openFind() = setState { copy(findOpen = true, findMatches = 0, activeMatchIndex = 0) }

    fun closeFind(tabId: String) {
        enginePool.controllerFor(tabId)?.findOnPage(null)
        setState { copy(findOpen = false, findQuery = "", findMatches = 0, activeMatchIndex = 0) }
    }

    fun updateFind(tabId: String, query: String) {
        setState { copy(findQuery = query) }
        enginePool.controllerFor(tabId)?.findOnPage(query.takeIf { it.isNotBlank() }) { ordinal, count, _ ->
            setState {
                copy(
                    findMatches = count,
                    activeMatchIndex = if (count > 0) ordinal + 1 else 0,
                )
            }
        }
    }

    // ----- Desktop mode -----
    fun toggleDesktopMode(tabId: String) {
        val controller = enginePool.controllerFor(tabId) ?: return
        val next = !_state.value.desktopMode
        controller.setDesktopMode(next)
        setState { copy(desktopMode = next) }
    }

    // ----- Bookmark toggle (persists on the DEFAULT profile; PRD §15) -----
    private fun refreshBookmarkState(url: String) {
        viewModelScope.launch {
            val profile = profileManager.defaultProfile()
            setState {
                copy(isBookmarked = profile?.let { bookmarkDao.byUrl(it.id, url) != null } ?: false)
            }
        }
    }

    fun toggleBookmark(currentUrl: String, currentTitle: String?) {
        viewModelScope.launch {
            val profile = profileManager.defaultProfile() ?: return@launch
            val existing = bookmarkDao.byUrl(profile.id, currentUrl)
            if (existing != null) {
                bookmarkDao.deleteById(existing.id)
                setState { copy(isBookmarked = false) }
            } else {
                bookmarkDao.upsert(
                    BookmarkEntity(
                        id = UUID.randomUUID().toString(),
                        profileId = profile.id,
                        url = currentUrl,
                        title = currentTitle?.takeIf { it.isNotBlank() } ?: currentUrl,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
                setState { copy(isBookmarked = true) }
            }
        }
    }

    // ----- Share -----
    fun share() {
        val url = _state.value.address
        if (url.isNotBlank()) _shareRequest.value = url
    }

    fun consumeShare() {
        _shareRequest.value = null
    }

    // ----- Site permission dialog (real engine gate callback) -----
    fun resolvePermission(grantForever: Boolean) {
        val prompt = _state.value.permissionPrompt ?: return
        if (grantForever) {
            val ownerId = sessionManager.activeOwner.value?.ownerId ?: return
            viewModelScope.launch {
                prompt.resources.forEach { resource ->
                    permissionGate.set(ownerId, prompt.host, resource, grantedNow = true)
                }
                prompt.grant(prompt.resources)
            }
        } else {
            prompt.grant(prompt.resources) // one-shot, in-memory only
        }
        setState { copy(permissionPrompt = null) }
    }

    fun denyPermission() {
        _state.value.permissionPrompt?.deny()
        setState { copy(permissionPrompt = null) }
    }
}

@Composable
fun BrowserScreen(
    ownerId: String,
    tabId: String,
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Voice search through the platform recognizer.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            viewModel.updateAddress(spoken)
            viewModel.submitAddress(tabId)
        }
    }

    // System share sheet handoff.
    LaunchedEffect(Unit) {
        viewModel.shareRequest.collect { url ->
            if (url != null) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            },
                            "Share via",
                        ),
                    )
                }
                viewModel.consumeShare()
            }
        }
    }

    LaunchedEffect(ownerId, tabId) {
        viewModel.bind(tabId)
        viewModel.start()
        val currentUrl = viewModel.controllerFor(tabId)?.url.orEmpty()
        viewModel.updateAddress(currentUrl)
    }

    // One-shot grant persistence flag, decided in the dialog here.
    val rememberGrant = remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    OutlinedTextField(
                        value = state.address,
                        onValueChange = { viewModel.onAddressEdited(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.submitAddress(tabId) }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.share() }) { Icon(Icons.Filled.Share, "Share") }
                    IconButton(
                        onClick = { viewModel.toggleBookmark(state.address, state.title) },
                    ) {
                        Icon(
                            if (state.isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Bookmark",
                        )
                    }
                    IconButton(onClick = { viewModel.openFind() }) { Icon(Icons.Filled.FindInPage, "Find") }
                    IconButton(
                        onClick = { viewModel.toggleDesktopMode(tabId) },
                    ) { Icon(Icons.Filled.DesktopWindows, "Desktop mode") }
                    IconButton(onClick = { viewModel.reload(tabId) }) {
                        Icon(Icons.Filled.Refresh, "Reload")
                    }
                    IconButton(
                        onClick = {
                            runCatching {
                                voiceLauncher.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        putExtra(
                                            RecognizerIntent.EXTRA_PROMPT,
                                            "Search or speak a URL",
                                        )
                                    },
                                )
                            }
                        },
                    ) { Icon(Icons.Filled.Mic, "Voice search") }
                }
                // Search suggestions — hidden while find-on-page is open, only for
                // user-typed text (never raised for programmatic page-load URLs).
                if (state.showSuggestions && !state.findOpen && state.suggestions.isNotEmpty()) {
                    Column {
                        state.suggestions.forEach { s ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.pickSuggestion(s)
                                        viewModel.submitAddress(tabId)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = null)
                                Text(
                                    s,
                                    modifier = Modifier.padding(start = 12.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                if (state.progress < 100) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.findOpen) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = state.findQuery,
                            onValueChange = { viewModel.updateFind(tabId, it) },
                            singleLine = true,
                            label = { Text("Find in page") },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (state.findMatches > 0) {
                                "${state.activeMatchIndex}/${state.findMatches}"
                            } else {
                                "0/0"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(onClick = { viewModel.closeFind(tabId) }) {
                            Icon(Icons.Filled.Close, "Close find")
                        }
                    }
                }
            }
        }

        state.proxyFailure?.let { message ->
            Surface(color = MaterialTheme.colorScheme.error) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissFailure) { Text("Dismiss") }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val controller = viewModel.controllerFor(tabId)
            if (controller != null) {
                AndroidView(
                    factory = { controller.view },
                    update = { v ->
                        if (v.layoutParams?.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                            v.layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "This tab has no engine instance (open via Tabs or NEW SESSION).",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    state.permissionPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::denyPermission,
            title = { Text("Site permission") },
            text = {
                Column {
                    Text("${prompt.host} wants access to:")
                    prompt.resources.forEach { Text("• $it") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = rememberGrant.value, onCheckedChange = { rememberGrant.value = it })
                        Text(
                            "Remember this grant",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Default is deny. \"Remember\" persists the grant per-session/profile — " +
                            "temporary sessions clear it on cleanup.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.resolvePermission(grantForever = rememberGrant.value) }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::denyPermission) { Text("Deny") }
            },
        )
    }
}
