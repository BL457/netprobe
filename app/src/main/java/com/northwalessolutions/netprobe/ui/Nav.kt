package com.northwalessolutions.netprobe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen

@Composable
fun NetProbeNav(container: AppContainer) {
    val keepOn by container.extendedRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(keepOn)
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") }) }
        composable("host/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { DetailScreen(container, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
