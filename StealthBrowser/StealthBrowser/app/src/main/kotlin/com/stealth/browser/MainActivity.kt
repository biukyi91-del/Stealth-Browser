package com.stealth.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import com.stealth.browser.data.prefs.AppSettings
import com.stealth.browser.data.prefs.SettingsStore
import com.stealth.browser.ui.navigation.StealthNavHost
import com.stealth.browser.ui.theme.StealthBrowserTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Treat as "first-frame default" until the persisted stream emits.
            val settings = settingsStore.settings.collectAsState(initial = AppSettings()).value
            StealthBrowserTheme(themeMode = settings.themeMode) {
                StealthNavHost()
            }
        }
    }
}
