package com.stealth.browser.ui.screens.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.session.EnginePool
import com.stealth.browser.session.PermanentProfileOwner
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PRD §13.3 store: discovery UI with categories. HONESTY NOTE surfaced in the
 * screen: direct Chrome Web Store installation requires the CWS install
 * verification flow (Chromium integration). This screen navigates well-known
 * store entries, shows requested-permission context, and hands users to the
 * validated package-install workflow — it never claims in-app store installs.
 */
@HiltViewModel
class ExtensionStoreViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val enginePool: EnginePool,
) : ViewModel() {

    private val _pendingOpen = MutableStateFlow<Pair<String, String>?>(null)
    val pendingOpen: StateFlow<Pair<String, String>?> = _pendingOpen

    private val _category = MutableStateFlow(StoreCatalog.CATEGORIES.first())
    val category: StateFlow<String> = _category

    fun pickCategory(c: String) {
        _category.value = c
    }

    val items: List<StoreCatalog.StoreItem>
        get() = StoreCatalog.CATALOG

    /** Opens the CWS page in a REAL tab on the default profile. */
    fun openCws(url: String) {
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

object StoreCatalog {
    val CATEGORIES = listOf(
        "Privacy", "Security", "Productivity", "Ad Blocking",
        "Password Management", "Developer Tools", "Shopping", "Utilities",
    )

    data class StoreItem(
        val name: String,
        val category: String,
        val cwsUrl: String,
        val permissionsNote: String,
    )

    val CATALOG: List<StoreItem> = listOf(
        StoreItem(
            "uBlock Origin",
            "Ad Blocking",
            "https://chromewebstore.google.com/detail/ublock-origin/cjpalhdlnbpafiamejdnhcphjbkeiagm",
            "MV3 fork is uBOL; MV2 may work while the engine retains MV2 support.",
        ),
        StoreItem(
            "uBlock Origin Lite",
            "Ad Blocking",
            "https://chromewebstore.google.com/detail/ublock-origin-lite/ddkjiahejlhfcafbddmgiahcphecmpfh",
            "MV3 declarativeNetRequest — targets the supported blocking path.",
        ),
        StoreItem(
            "NoScript Security Suite",
            "Security",
            "https://chromewebstore.google.com/detail/noscript-security-suite/doojmbjmlfjjnbmnoijecmcbfeoakpjm",
            "Content-script heavy; verify support for scripting APIs.",
        ),
        StoreItem(
            "Decentraleyes",
            "Privacy",
            "https://chromewebstore.google.com/detail/decentraleyes/ldpochfccmkkmhdbclfhpagapcfdljkj",
            "Local CDN resources; safe content-script scope.",
        ),
        StoreItem(
            "Privacy Badger",
            "Privacy",
            "https://chromewebstore.google.com/detail/privacy-badger/pkehgijcmpdhfbdbbnkijodmdjhjjlgm",
            "Blocks trackers; uses webRequest-era APIs — check compatibility page.",
        ),
        StoreItem(
            "Keepassxc-Browser",
            "Password Management",
            "https://chromewebstore.google.com/detail/keepassxc-browser/oboonakodofbidihbdponmnoljgamfei",
            "Needs native messaging for full features — unsupported here.",
        ),
        StoreItem(
            "Bitwarden Password Manager",
            "Password Management",
            "https://chromewebstore.google.com/detail/bitwarden-password-manager/nngceckbapebfimnlniiiahkandclblb",
            "Core fill features are content-script driven; vault sync is network neutral.",
        ),
        StoreItem(
            "Momentum",
            "Productivity",
            "https://chromewebstore.google.com/detail/momentum/laookkfknpbbblfpciffpaejjkokdgca",
            "New-tab override — chrome_url_overrides support depends on runtime.",
        ),
        StoreItem(
            "Tampermonkey",
            "Developer Tools",
            "https://chromewebstore.google.com/detail/tampermonkey/dhdgffkkebhmkfjojejmpbldmpobfkfo",
            "MV3 build exists; user-script injection uses scripting API.",
        ),
        StoreItem(
            "JSON Formatter",
            "Developer Tools",
            "https://chromewebstore.google.com/detail/json-formatter/bcjindcccaagfpapjjmafapmmgkkhgoa",
            "Content script rendering only; generally compatible.",
        ),
        StoreItem(
            "Honey",
            "Shopping",
            "https://chromewebstore.google.com/detail/honey/jmedldimjedpdgdjommckhashppbjmpe",
            "Network call heavy; review permission summary.",
        ),
        StoreItem(
            "Augmented Steam",
            "Utilities",
            "https://chromewebstore.google.com/detail/augmented-steam/jkagatndllnmmonmofdahalgldfbdndc",
            "Site-scoped content script.",
        ),
    )
}

@Composable
fun ExtensionStoreScreen(
    onBack: () -> Unit,
    onOpenTab: (ownerId: String, tabId: String) -> Unit,
    viewModel: ExtensionStoreViewModel = hiltViewModel(),
) {
    val category by viewModel.category.collectAsState()
    val pendingOpen by viewModel.pendingOpen.collectAsState()

    pendingOpen?.let { (ownerId, tabId) ->
        LaunchedEffect(ownerId, tabId) { onOpenTab(ownerId, tabId); viewModel.clearPending() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Extension Store", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Direct CWS installation requires Chromium's install-verification flow " +
                            "(pending engine integration). Until then: open the store page to review, " +
                            "then install the CRX/ZIP package through Extensions → Install from file. " +
                            "Packages always pass structure/manifest validation and permission review.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StoreCatalog.CATEGORIES.forEach { cat ->
                    AssistChip(
                        onClick = { viewModel.pickCategory(cat) },
                        label = {
                            Text(
                                cat + if (cat == category) " ✓" else "",
                            )
                        },
                    )
                }
            }
        }

        viewModel.items.filter { it.category == category }.forEach { item ->
            item(key = item.name) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openCws(item.cwsUrl) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(item.permissionsNote, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.openCws(item.cwsUrl) }) {
                            Text("Open in Chrome Web Store →")
                        }
                    }
                }
            }
        }
    }
}
