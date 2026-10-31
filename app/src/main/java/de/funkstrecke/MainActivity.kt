package de.funkstrecke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.funkstrecke.ui.MapScreen
import de.funkstrecke.ui.StationEditScreen
import de.funkstrecke.ui.StationListScreen
import de.funkstrecke.ui.StationsViewModel
import de.funkstrecke.ui.theme.FunkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FunkTheme {
                val nav = rememberNavController()
                val vm: StationsViewModel = viewModel()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        StationListScreen(
                            vm = vm,
                            onOpen = { id -> nav.navigate("edit/$id") },
                            onNew = { nav.navigate("edit/0") },
                            onMap = { nav.navigate("map") },
                        )
                    }
                    composable(
                        "edit/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        StationEditScreen(
                            vm = vm,
                            stationId = entry.arguments?.getLong("id") ?: 0L,
                            onClose = { nav.popBackStack() },
                        )
                    }
                    composable("map") {
                        MapScreen(
                            vm = vm,
                            onOpen = { id -> nav.navigate("edit/$id") },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
