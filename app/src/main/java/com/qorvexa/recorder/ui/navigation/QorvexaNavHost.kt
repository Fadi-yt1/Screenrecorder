package com.qorvexa.recorder.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qorvexa.recorder.ui.AppViewModel
import com.qorvexa.recorder.ui.about.AboutScreen
import com.qorvexa.recorder.ui.device.DeviceScreen
import com.qorvexa.recorder.ui.home.HomeScreen
import com.qorvexa.recorder.ui.library.LibraryScreen
import com.qorvexa.recorder.ui.library.PlayerScreen
import com.qorvexa.recorder.ui.recording.RecordingScreen
import com.qorvexa.recorder.ui.settings.AdvancedSettingsScreen
import com.qorvexa.recorder.ui.settings.AudioSettingsScreen
import com.qorvexa.recorder.ui.settings.EngineScreen
import com.qorvexa.recorder.ui.settings.GamingScreen
import com.qorvexa.recorder.ui.settings.OverlaySettingsScreen
import com.qorvexa.recorder.ui.settings.SettingsScreen
import com.qorvexa.recorder.ui.settings.VideoSettingsScreen
import com.qorvexa.recorder.ui.theme.LocalQx

object Routes {
    const val HOME = "home"
    const val RECORDINGS = "recordings"
    const val SETTINGS = "settings"
    const val DEVICE = "device"
    const val ABOUT = "about"
    const val VIDEO = "settings/video"
    const val AUDIO = "settings/audio"
    const val ENGINE = "settings/engine"
    const val OVERLAY = "settings/overlay"
    const val GAMING = "settings/gaming"
    const val ADVANCED = "settings/advanced"
    const val RECORDING = "recording"
    const val PLAYER = "player/{uri}"
    fun player(uri: Uri) = "player/" + Uri.encode(uri.toString())
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.RECORDINGS, "Recordings", Icons.Filled.VideoLibrary),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    Tab(Routes.DEVICE, "Device", Icons.Filled.Memory),
    Tab(Routes.ABOUT, "About", Icons.Filled.Info),
)

@Composable
fun QorvexaApp(vm: AppViewModel, deepLink: String?, onDeepLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            nav.navigateTab(deepLink)
            onDeepLinkHandled()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = LocalQx.current.card, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.RECORDINGS) { LibraryScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Routes.DEVICE) { DeviceScreen(vm) }
            composable(Routes.ABOUT) { AboutScreen() }
            composable(Routes.VIDEO) { VideoSettingsScreen(vm) { nav.popBackStack() } }
            composable(Routes.AUDIO) { AudioSettingsScreen(vm) { nav.popBackStack() } }
            composable(Routes.ENGINE) { EngineScreen(vm) { nav.popBackStack() } }
            composable(Routes.OVERLAY) { OverlaySettingsScreen(vm) { nav.popBackStack() } }
            composable(Routes.GAMING) { GamingScreen(vm) { nav.popBackStack() } }
            composable(Routes.ADVANCED) { AdvancedSettingsScreen(vm) { nav.popBackStack() } }
            composable(Routes.RECORDING) { RecordingScreen(vm) { nav.popBackStack() } }
            composable(Routes.PLAYER, arguments = listOf(navArgument("uri") { type = NavType.StringType })) { e ->
                val uri = Uri.parse(Uri.decode(e.arguments?.getString("uri") ?: ""))
                PlayerScreen(uri) { nav.popBackStack() }
            }
        }
    }
}

fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
