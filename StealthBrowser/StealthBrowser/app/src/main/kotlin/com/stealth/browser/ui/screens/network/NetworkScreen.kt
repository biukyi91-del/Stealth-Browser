package com.stealth.browser.ui.screens.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealth.browser.data.db.ProxyConfigEntity
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.ProxyConfig
import com.stealth.browser.engine.core.ProxyType
import com.stealth.browser.network.NetworkManager
import com.stealth.browser.network.NetworkTester
import com.stealth.browser.network.ProxyStore
import com.stealth.browser.session.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NetworkUiState(
    val proxies: List<ProxyConfigEntity> = emptyList(),
    val defaultProfileId: String? = null,
    val defaultProfileName: String? = null,
    val boundProxyId: String? = null,
    val testResult: String? = null,
    val busy: Boolean = false,
    val dohResult: String? = null,
)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val proxyStore: ProxyStore,
    private val profileManager: ProfileManager,
    private val tester: NetworkTester,
    private val networkManager: NetworkManager,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state

    init {
        viewModelScope.launch {
            proxyStore.observeAll().collect { list ->
                _state.value = _state.value.copy(proxies = list)
            }
        }
        viewModelScope.launch {
            val profile = defaultProfile()
            _state.value = _state.value.copy(
                defaultProfileId = profile?.id,
                defaultProfileName = profile?.name,
                boundProxyId = profile?.proxyConfigId,
            )
        }
    }

    private suspend fun defaultProfile() =
        (profileManager.profiles().firstOrNull { it.isDefault } ?: profileManager.profiles().firstOrNull())

    // ----- CRUD -----
    fun addProxy(name: String, type: ProxyType, host: String, port: Int, username: String?, password: CharArray?) {
        viewModelScope.launch {
            runCatching {
                proxyStore.add(name, type, host.trim(), port, username?.trim() ?: "", password)
            }
        }
    }

    fun deleteProxy(id: String) {
        viewModelScope.launch {
            proxyStore.delete(id)
            if (_state.value.boundProxyId == id) {
                unbindDefaultProfile()
            }
        }
    }

    // ----- Per-profile binding (PRD §9: per-profile network configuration) -----
    fun bindToDefaultProfile(proxyId: String?) {
        val profileId = _state.value.defaultProfileId ?: return
        viewModelScope.launch {
            profileManager.setProxyConfig(profileId, proxyId)
            networkManager.bind(profileId, proxyId)
            networkManager.applyForOwner(profileId)
            _state.value = _state.value.copy(boundProxyId = proxyId)
        }
    }

    private fun unbindDefaultProfile() = bindToDefaultProfile(null)

    // ----- Real connection test -----
    fun testProxy(proxy: ProxyConfigEntity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, testResult = "Testing ${proxy.host}…")
            val engineProxy = proxyStore.toProxyConfig(proxy)
            if (engineProxy == null) {
                _state.value = _state.value.copy(busy = false, testResult = "Config invalid — password unreadable")
                return@launch
            }
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tester.testProxy(NetworkConfig(proxy = engineProxy))
            }
            _state.value = _state.value.copy(
                busy = false,
                testResult = if (result.ok) {
                    "OK via ${proxy.host}:${proxy.port} — egress IP: ${result.egressIp ?: "(mask omitted by server)"} (${result.latencyMs} ms)"
                } else {
                    "FAILED: ${result.error} — the app will NOT fall back to a direct connection while this proxy is bound."
                },
            )
        }
    }

    // ----- DoH provider reachability (honest diagnostics; engine DoH pending) -----
    fun testDoH() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, dohResult = "Probing DoH providers…")
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tester.testSecureDns("https://one.one.one.one/cdn-cgi/trace")
            }
            _state.value = _state.value.copy(
                busy = false,
                dohResult = if (res.ok) "Reachable (${res.latencyMs} ms); egress ${res.egressIp}" else "Unreachable: ${res.error}",
            )
        }
    }
}

@Composable
fun NetworkScreen(viewModel: NetworkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Network privacy", style = MaterialTheme.typography.titleLarge) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active binding", style = MaterialTheme.typography.titleSmall)
                    val bound = state.proxies.firstOrNull { it.id == state.boundProxyId }
                    Text(
                        when {
                            bound != null -> "${bound.name} (${bound.type.lowercase()}://${bound.host}:${bound.port}) on profile ${state.defaultProfileName}"
                            else -> "Direct connection (no proxy bound)",
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "A bound proxy NEVER silently falls back to direct. Navigation stops and the kill switch engages on failure.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAdd = true }) { Text("Add proxy") }
                OutlinedButton(onClick = { viewModel.testDoH() }) { Text("DoH diagnostics") }
            }
        }

        state.dohResult?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }
        state.testResult?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }

        item { Text("Configured proxies", style = MaterialTheme.typography.titleMedium) }
        if (state.proxies.isEmpty()) {
            item { Text("No proxies configured. Direct connection is used.") }
        }
        state.proxies.forEach { proxyEntity ->
            item(key = proxyEntity.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(proxyEntity.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${proxyEntity.type.lowercase()}://${proxyEntity.host}:${proxyEntity.port}" +
                                        if (proxyEntity.username != null) " (with password ${"*".repeat(8)})" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { viewModel.deleteProxy(proxyEntity.id) }) { Text("Delete") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.testProxy(proxyEntity) }) { Text("Test connection") }
                            if (state.boundProxyId == proxyEntity.id) {
                                TextButton(onClick = { viewModel.bindToDefaultProfile(null) }) {
                                    Text("Unbind")
                                }
                            } else {
                                TextButton(onClick = { viewModel.bindToDefaultProfile(proxyEntity.id) }) {
                                    Text("Use for profile")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Engine honestly notes: HTTP and HTTPS proxies are enforced through the " +
                    "WebView ProxyController. SOCKS5 and authenticated proxies (username/password) " +
                    "are connection-tested but NOT yet supported by the engine routing; binding one " +
                    "would fail closed rather than silently going direct.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showAdd) {
        AddProxyDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, type, host, port, username, password ->
                viewModel.addProxy(name, type, host, port, username, password)
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddProxyDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, type: ProxyType, host: String, port: Int, username: String?, password: CharArray?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProxyType.HTTP) }
    var useAuth by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add proxy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(ProxyType.HTTP, ProxyType.HTTPS).forEach { t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = if (t == ProxyType.HTTP) 0 else 1,
                                count = 2,
                            ),
                            label = { Text(t.name) },
                        )
                    }
                }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedButton(onClick = { useAuth = !useAuth }) {
                    Text(if (useAuth) "Remove credentials" else "Add username/password")
                }
                if (useAuth) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (tested, not engine-routed)") })
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        },
        confirmButton = {
            val portNumber = port.toIntOrNull()
            Button(
                enabled = name.isNotBlank() && host.isNotBlank() && portNumber != null &&
                    portNumber in 1..65535,
                onClick = {
                    onAdd(
                        name,
                        type,
                        host,
                        portNumber ?: 8080,
                        username.takeIf { useAuth && it.isNotBlank() },
                        password.takeIf { useAuth && it.isNotBlank() }?.toCharArray(),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
