package io.github.alessiop04.atacschedule

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import io.github.alessiop04.atacschedule.system.Shortcuts
import io.github.alessiop04.atacschedule.ui.AddScreen
import io.github.alessiop04.atacschedule.ui.AppTheme
import io.github.alessiop04.atacschedule.ui.HomeScreen
import io.github.alessiop04.atacschedule.ui.LineScreen
import io.github.alessiop04.atacschedule.ui.map.MapMode
import io.github.alessiop04.atacschedule.ui.map.MapScreen

private const val PICKED_STOP = "picked_stop"

private fun routePath(routeId: String, direction: Int, headsign: String, stopId: String?) =
    "route?r=${Uri.encode(routeId)}&d=$direction&h=${Uri.encode(headsign)}" + (stopId?.let { "&s=${Uri.encode(it)}" } ?: "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(onOpen = { nav.navigate("line/$it") }, onAdd = { nav.navigate("add") })
                    }
                    composable("add") { entry ->
                        // fermata scelta sulla mappa (vedi composable("map"))
                        val picked = entry.savedStateHandle.getStateFlow<String?>(PICKED_STOP, null).collectAsStateWithLifecycle().value
                        AddScreen(
                            onBack = { nav.popBackStack() },
                            onSaved = { nav.popBackStack("home", inclusive = false) },
                            onMap = { nav.navigate("map") },
                            onShowRoute = { line -> nav.navigate(routePath(line.key.routeId, line.key.direction, line.key.headsign, line.key.stopId)) },
                            pickedStop = picked,
                            onPickedConsumed = { entry.savedStateHandle[PICKED_STOP] = null },
                        )
                    }
                    composable("map") {
                        MapScreen(
                            mode = MapMode.Pick,
                            onBack = { nav.popBackStack() },
                            onPickStop = { stopId ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(PICKED_STOP, stopId)
                                nav.popBackStack()
                            },
                        )
                    }
                    composable(
                        "route?r={r}&d={d}&h={h}&s={s}",
                        arguments = listOf(
                            navArgument("r") { type = NavType.StringType },
                            navArgument("d") { type = NavType.IntType },
                            navArgument("h") { type = NavType.StringType },
                            navArgument("s") { type = NavType.StringType; nullable = true },
                        ),
                    ) { entry ->
                        val a = entry.arguments!!
                        MapScreen(
                            mode = MapMode.Route(a.getString("r")!!, a.getInt("d"), a.getString("h")!!, a.getString("s")),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(
                        "line/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        // aperta direttamente da scorciatoie e widget (Shortcuts.lineUri)
                        deepLinks = listOf(navDeepLink { uriPattern = "${Shortcuts.SCHEME}://line/{id}" }),
                    ) { entry ->
                        LineScreen(
                            entry.arguments!!.getLong("id"),
                            onBack = { nav.popBackStack() },
                            onShowRoute = { key -> nav.navigate(routePath(key.routeId, key.direction, key.headsign, key.stopId)) },
                        )
                    }
                }
            }
        }
    }
}
