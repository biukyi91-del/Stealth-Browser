package com.stealth.browser.ui.screens.onboarding

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * PRD §20 onboarding: 7 screens (welcome, privacy intro, search engine, network
 * privacy, browsing mode, extensions intro, start). Navigation feeds back the
 * user's choices through the same SettingsStore used by Settings.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 7 })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        LinearProgressIndicator(
            progress = { (pagerState.currentPage + 1) / 7f },
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIndex ->
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (pageIndex) {
                    0 -> {
                        Text("Welcome to Stealth Browser", style = MaterialTheme.typography.headlineMedium)
                        Text("\nFresh, isolated browsing environments plus privacy controls, with one tap.", style = MaterialTheme.typography.bodyLarge)
                    }
                    1 -> {
                        Text("Privacy protection introduction", style = MaterialTheme.typography.headlineMedium)
                        Text("\nProfiles and temporary sessions get their own cookies, storage and permissions. Fingerprint surfaces can be patched at the engine level, with honest, coherent values. Tracker requests are filtered.", style = MaterialTheme.typography.bodyLarge)
                    }
                    2 -> {
                        Text("Choose search engine", style = MaterialTheme.typography.headlineMedium)
                        Text("\nPick a search engine in Settings → General after setup. Defaults to DuckDuckGo.", style = MaterialTheme.typography.bodyLarge)
                    }
                    3 -> {
                        Text("Configure network privacy", style = MaterialTheme.typography.headlineMedium)
                        Text("\nAdd HTTP/HTTPS proxies per profile. A configured proxy will NEVER silently fall back to a direct connection; a kill switch blocks navigation instead.", style = MaterialTheme.typography.bodyLarge)
                    }
                    4 -> {
                        Text("Choose browsing mode", style = MaterialTheme.typography.headlineMedium)
                        Text("\n• Temporary sessions: one tap, wiped on close.\n• Permanent profiles: independent long-term environments.", style = MaterialTheme.typography.bodyLarge)
                    }
                    5 -> {
                        Text("Chrome extensions", style = MaterialTheme.typography.headlineMedium)
                        Text("\nCompatible extensions can be installed from packages and reviewed before permission grants. Temporary sessions follow your configured extension loading policy.", style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> {
                        Text("Start browsing", style = MaterialTheme.typography.headlineMedium)
                        Text("\nYou're ready. Press NEW STEALTH SESSION any time from Home.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pagerState.currentPage > 0) {
                OutlinedButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) { Text("Back") }
            }
            Button(onClick = {
                if (pagerState.currentPage == 6) onDone()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }) { Text(if (pagerState.currentPage == 6) "Start browsing" else "Next") }
        }
    }
}
