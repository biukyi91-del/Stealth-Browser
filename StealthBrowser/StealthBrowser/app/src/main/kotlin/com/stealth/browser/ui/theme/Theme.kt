package com.stealth.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stealth.browser.data.prefs.ThemeMode

val StealthNavy = Color(0xFF0B1220)
val StealthPanel = Color(0xFF131C2B)
val StealthAccent = Color(0xFF4FD1C5)
val StealthAccentDim = Color(0xFF2C7A7B)
val StealthWarn = Color(0xFFF6AD55)
val StealthError = Color(0xFFFC8181)
val StealthOk = Color(0xFF68D391)

private val DarkScheme = darkColorScheme(
    primary = StealthAccent,
    secondary = StealthAccentDim,
    background = StealthNavy,
    surface = StealthPanel,
    onSurface = Color(0xFFE2E8F0),
    error = StealthError,
)

private val LightScheme = lightColorScheme(
    primary = StealthAccentDim,
    secondary = StealthAccent,
    background = Color(0xFFF7FAFC),
    surface = Color.White,
    onSurface = Color(0xFF1A202C),
)

@Composable
fun StealthBrowserTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
