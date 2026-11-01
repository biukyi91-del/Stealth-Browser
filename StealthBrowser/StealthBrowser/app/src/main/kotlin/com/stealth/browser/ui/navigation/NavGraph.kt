package com.stealth.browser.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stealth.browser.ui.screens.browser.BrowserScreen
import com.stealth.browser.ui.screens.bookmarks.BookmarksScreen
import com.stealth.browser.ui.screens.downloads.DownloadsScreen
import com.stealth.browser.ui.screens.extensions.ExtensionDetailScreen
import com.stealth.browser.ui.screens.extensions.ExtensionsScreen
import com.stealth.browser.ui.screens.history.HistoryScreen
import com.stealth.browser.ui.screens.home.HomeScreen
import com.stealth.browser.ui.screens.network.NetworkScreen
import com.stealth.browser.ui.screens.onboarding.OnboardingScreen
import com.stealth.browser.ui.screens.privacy.PrivacyScreen
import com.stealth.browser.ui.screens.profiles.ProfilesScreen
import com.stealth.browser.ui.screens.settings.SettingsScreen
import com.stealth.browser.ui.screens.tabs.TabsScreen

object Routes {
    const val HOME = "home"
    const val BROWSER = "browser/{ownerId}/{tabId}"
    const val TABS = "tabs"
    const val PROFILES = "profiles"
    const val EXTENSIONS = "extensions"
    const val EXTENSION_DETAIL = "extension/{extensionId}"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val NETWORK = "network"
    const val PRIVACY_CONFIG = "privacy-config/{profileId}"
    const val EXTENSION_STORE = "extension-store"

    fun privacyConfig(profileId: String): String = "privacy-config/$profileId"
    const val PRIVACY = "privacy"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"

    fun browser(ownerId: String, tabId: String): String = "browser/$ownerId/$tabId"
    fun extensionDetail(extensionId: String): String = "extension/$extensionId"
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun StealthNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Browser, settings and onboarding are full-screen (no bottom bar).
    val showBottomBar = currentRoute in setOf(
        Routes.HOME, Routes.TABS, Routes.PROFILES, Routes.EXTENSIONS, Routes.PRIVACY,
    )
    val items = listOf(
        NavItem(Routes.HOME, "Home", Icons.Filled.Home),
        NavItem(Routes.TABS, "Tabs", Icons.AutoMirrored.Filled.List),
        NavItem(Routes.PROFILES, "Profiles", Icons.Filled.Person),
        NavItem(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
        NavItem(Routes.PRIVACY, "Privacy", Icons.Filled.Shield),
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNewSession = { ownerId, tabId -> navController.navigate(Routes.browser(ownerId, tabId)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                    onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                    onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onOpenTabs = { navController.navigate(Routes.TABS) },
                    onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                    onOpenNetwork = { navController.navigate(Routes.NETWORK) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                )
            }
            composable(Routes.BROWSER) { entry ->
                val ownerId = entry.arguments?.getString("ownerId").orEmpty()
                val tabId = entry.arguments?.getString("tabId").orEmpty()
                BrowserScreen(ownerId = ownerId, tabId = tabId, onBack = { navController.popBackStack() })
            }
            composable(Routes.TABS) {
                TabsScreen(
                    onOpenTab = { ownerId, tabId -> navController.navigate(Routes.browser(ownerId, tabId)) },
                )
            }
            composable(Routes.PROFILES) { ProfilesScreen() }
            composable(Routes.EXTENSIONS) {
                ExtensionsScreen(
                    onDetail = { extensionId -> navController.navigate(Routes.extensionDetail(extensionId)) },
                    onOpenStore = { navController.navigate(Routes.EXTENSION_STORE) },
                )
            }
            composable(Routes.EXTENSION_STORE) {
                ExtensionStoreScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTab = { ownerId, tabId -> navController.navigate(Routes.browser(ownerId, tabId)) },
                )
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(
                    onOpenProfileConfig = { navController.navigate(Routes.privacyConfig("default")) },
                )
            }
            composable(Routes.PRIVACY_CONFIG) {
                PrivacyConfigScreen(
                    profileId = "default",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.EXTENSION_DETAIL) { entry ->
                val extensionId = entry.arguments?.getString("extensionId").orEmpty()
                ExtensionDetailScreen(extensionId = extensionId, onBack = { navController.popBackStack() })
            }
            composable(Routes.BOOKMARKS) {
                BookmarksScreen(
                    onOpenBookmark = { ownerId, tabId -> navController.navigate(Routes.browser(ownerId, tabId)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenHistoryEntry = { ownerId, tabId -> navController.navigate(Routes.browser(ownerId, tabId)) },
                )
            }
            composable(Routes.DOWNLOADS) { DownloadsScreen() }
            composable(Routes.NETWORK) { NetworkScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ONBOARDING) { OnboardingScreen(onDone = { navController.popBackStack() }) }
        }
    }
}
