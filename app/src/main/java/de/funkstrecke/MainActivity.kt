package de.funkstrecke

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.funkstrecke.ui.LiveScreen
import de.funkstrecke.ui.MapScreen
import de.funkstrecke.ui.ScannerViewModel
import de.funkstrecke.ui.SettingsScreen
import de.funkstrecke.ui.StationDetailScreen
import de.funkstrecke.ui.StationsScreen
import de.funkstrecke.ui.theme.FunkTheme
import de.funkstrecke.util.SettingsStore

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("live", "Live", Icons.Filled.SettingsInputAntenna),
    Tab("stations", "Stationen", Icons.AutoMirrored.Filled.List),
    Tab("map", "Karte", Icons.Filled.Map),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        enableEdgeToEdge()
        setContent {
            FunkTheme {
                val nav = rememberNavController()
                val vm: ScannerViewModel = viewModel()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val showTabs = tabs.any { it.route == route }

                fun openStation(key: String) = nav.navigate("station/${Uri.encode(key)}")

                Scaffold(
                    bottomBar = {
                        if (showTabs) {
                            NavigationBar {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "live",
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    ) {
                        composable("live") { LiveScreen(vm, onSettings = { nav.navigate("settings") }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                        composable("stations") { StationsScreen(vm, onOpen = ::openStation) }
                        composable("map") { MapScreen(vm, onOpen = ::openStation) }
                        composable(
                            "station/{key}",
                            arguments = listOf(navArgument("key") { type = NavType.StringType }),
                        ) { entry ->
                            StationDetailScreen(
                                vm = vm,
                                stationKey = entry.arguments?.getString("key") ?: "",
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
