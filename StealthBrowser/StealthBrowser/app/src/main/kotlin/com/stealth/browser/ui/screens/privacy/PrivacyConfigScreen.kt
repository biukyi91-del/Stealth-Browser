package com.stealth.browser.ui.screens.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.engine.core.PrivacyConfig
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PrivacyConfigUiState(
    val profileId: String? = null,
    val profileName: String? = null,
    val canvas: Boolean = false,
    val webgl: Boolean = false,
    val audio: Boolean = false,
    val webgpu: Boolean = false,
    val fonts: Boolean = false,
    val screen: Boolean = false,
    val hardware: Boolean = false,
    val clientHints: Boolean = false,
    val timezone: Boolean = false,
    val locale: Boolean = false,
    val userAgentOverride: String? = null,
)

/** Identifies the fingerprint knob being toggled (type-safe switch wiring). */
enum class PrivacyFlag { CANVAS, WEBGL, AUDIO, WEBGPU, FONTS, SCREEN, HARDWARE, CLIENT_HINTS, TIMEZONE, LOCALE }

/**
 * Per-profile fingerprint engine configuration (PRD §8/§17 PRIVACY).
 * Saved through ProfileManager; applies to NEW tabs/sessions of the profile —
 * existing engine instances keep their document-start script until reload (honest note in UI).
 */
@HiltViewModel
class PrivacyConfigViewModel @Inject constructor(
    private val profileManager: ProfileManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyConfigUiState())
    val state: StateFlow<PrivacyConfigUiState> = _state

    fun load(profileId: String) {
        viewModelScope.launch {
            // "default" resolves to the default profile (PRD §17 PROFILE scope).
            val profile = if (profileId == DEFAULT_TOKEN) {
                profileManager.profiles().firstOrNull { it.isDefault }
                    ?: profileManager.profiles().firstOrNull()
            } else {
                profileManager.getProfile(profileId)
            } ?: return@launch
            val config = profileManager.privacyOf(profile.id)
            _state.value = PrivacyConfigUiState(
                profileId = profile.id,
                profileName = profile.name,
                canvas = config.canvasProtection,
                webgl = config.webglPrivacy,
                audio = config.audioContextPrivacy,
                webgpu = config.webgpuPrivacy,
                fonts = config.fontEnumerationPrivacy,
                screen = config.screenInfoPrivacy,
                hardware = config.hardwareInfoPrivacy,
                clientHints = config.clientHintsPrivacy,
                timezone = config.timezonePrivacy,
                locale = config.localePrivacy,
                userAgentOverride = config.userAgentOverride,
            )
        }
    }

    private fun applyFlag(current: PrivacyConfig, flag: PrivacyFlag, enabled: Boolean): PrivacyConfig = when (flag) {
        PrivacyFlag.CANVAS -> current.copy(canvasProtection = enabled)
        PrivacyFlag.WEBGL -> current.copy(webglPrivacy = enabled)
        PrivacyFlag.AUDIO -> current.copy(audioContextPrivacy = enabled)
        PrivacyFlag.WEBGPU -> current.copy(webgpuPrivacy = enabled)
        PrivacyFlag.FONTS -> current.copy(fontEnumerationPrivacy = enabled)
        PrivacyFlag.SCREEN -> current.copy(screenInfoPrivacy = enabled)
        PrivacyFlag.HARDWARE -> current.copy(hardwareInfoPrivacy = enabled)
        PrivacyFlag.CLIENT_HINTS -> current.copy(clientHintsPrivacy = enabled)
        PrivacyFlag.TIMEZONE -> current.copy(timezonePrivacy = enabled)
        PrivacyFlag.LOCALE -> current.copy(localePrivacy = enabled)
    }

    fun setFlag(profileId: String, flag: PrivacyFlag, enabled: Boolean) {
        viewModelScope.launch {
            val current = profileManager.privacyOf(profileId)
            profileManager.updatePrivacy(profileId, applyFlag(current, flag, enabled))
            refreshLocal(profileId, flag, enabled)
        }
    }

    private suspend fun refreshLocal(profileId: String, flag: PrivacyFlag, enabled: Boolean) {
        val config = profileManager.privacyOf(profileId)
        _state.value = _state.value.copy(
            canvas = config.canvasProtection,
            webgl = config.webglPrivacy,
            audio = config.audioContextPrivacy,
            webgpu = config.webgpuPrivacy,
            fonts = config.fontEnumerationPrivacy,
            screen = config.screenInfoPrivacy,
            hardware = config.hardwareInfoPrivacy,
            clientHints = config.clientHintsPrivacy,
            timezone = config.timezonePrivacy,
            locale = config.localePrivacy,
            userAgentOverride = config.userAgentOverride,
        )
        // (flag param retained for future incremental refresh)
    }

    fun setUserAgent(profileId: String, value: String) {
        viewModelScope.launch {
            val current = profileManager.privacyOf(profileId)
            profileManager.updatePrivacy(profileId, current.copy(userAgentOverride = value.ifBlank { null }))
            _state.value = _state.value.copy(userAgentOverride = value.ifBlank { null })
        }
    }

    companion object {
        /** Sentinel id from NavGraph — means "the default profile". */
        const val DEFAULT_TOKEN = "default"
    }
}

@Composable
fun PrivacyConfigScreen(
    profileId: String,
    onBack: () -> Unit,
    viewModel: PrivacyConfigViewModel = hiltViewModel(),
) {
    LaunchedEffect(profileId) { viewModel.load(profileId) }
    val state by viewModel.state.collectAsState()

    // Mutations always use the RESOLVED profile id (the route may carry "default").
    fun onToggle(flag: PrivacyFlag): (Boolean) -> Unit = { enabled ->
        state.profileId?.let { viewModel.setFlag(it, flag, enabled) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fingerprint privacy · ${state.profileName ?: "—"}") },
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
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Patches run at document start with per-session coherent values " +
                                "(no random parameter flashing). Values apply to NEW tabs and " +
                                "sessions of this profile; open tabs reload to adopt.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SwitchRow("Canvas reading/readback protection", state.canvas, onToggle(PrivacyFlag.CANVAS)) }
            item { SwitchRow("WebGL vendor/renderer masking", state.webgl, onToggle(PrivacyFlag.WEBGL)) }
            item { SwitchRow("AudioContext fingerprint masking", state.audio, onToggle(PrivacyFlag.AUDIO)) }
            item { SwitchRow("WebGPU adapter gating", state.webgpu, onToggle(PrivacyFlag.WEBGPU)) }
            item { SwitchRow("Font enumeration restriction", state.fonts, onToggle(PrivacyFlag.FONTS)) }
            item { SwitchRow("Screen parameter masking", state.screen, onToggle(PrivacyFlag.SCREEN)) }
            item { SwitchRow("Hardware info masking (cores/memory)", state.hardware, onToggle(PrivacyFlag.HARDWARE)) }
            item { SwitchRow("Client Hints restriction", state.clientHints, onToggle(PrivacyFlag.CLIENT_HINTS)) }
            item { SwitchRow("Timezone masking (UTC)", state.timezone, onToggle(PrivacyFlag.TIMEZONE)) }
            item { SwitchRow("Language restriction", state.locale, onToggle(PrivacyFlag.LOCALE)) }

            item {
                OutlinedTextField(
                    value = state.userAgentOverride ?: "",
                    onValueChange = { ua ->
                        state.profileId?.let { viewModel.setUserAgent(it, ua) }
                    },
                    label = { Text("User-Agent override (optional)") },
                    placeholder = { Text("Leave blank to use the engine's real UA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) },
    )
}
