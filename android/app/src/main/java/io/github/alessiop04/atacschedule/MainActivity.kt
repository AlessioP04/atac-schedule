package io.github.alessiop04.atacschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.alessiop04.atacschedule.ui.AddScreen
import io.github.alessiop04.atacschedule.ui.AppTheme
import io.github.alessiop04.atacschedule.ui.HomeScreen
import io.github.alessiop04.atacschedule.ui.LineScreen

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
                    composable("add") {
                        AddScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack("home", inclusive = false) })
                    }
                    composable("line/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        LineScreen(entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
