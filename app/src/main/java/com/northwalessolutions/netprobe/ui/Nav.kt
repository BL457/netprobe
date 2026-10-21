package com.northwalessolutions.netprobe.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.manual.ManualToolsScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.wordlists.WordlistsScreen

@Composable
fun NetProbeNav(container: AppContainer) {
    val extended by container.extendedRunActive.collectAsStateWithLifecycle()
    val toolRun by container.toolRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun)
    val nav = rememberNavController()
    NetProbeNavHost(nav,
        home = { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") }) },
        detail = { id -> DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) },
        settings = { SettingsScreen(container, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) },
        wordlists = { WordlistsScreen(container, onBack = { nav.popBackStack() }) },
        tools = { ManualToolsScreen(container, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) })
}

/**
 * The routes with their transitions (spec 1.9): the entering screen slides in from a quarter of the width and fades in, the leaving
 * one slides a tenth and fades out, 240 ms FastOutSlowIn; none under reduced motion. Stateless over its destinations, so the
 * Compose test passes fakes and no test runs a real discovery. "tools" is the 3C manual tools screen (spec 4.1); no entry point yet, Phase 3D wires it.
 */
@Composable
fun NetProbeNavHost(nav: NavHostController, home: @Composable () -> Unit, detail: @Composable (Long) -> Unit, settings: @Composable () -> Unit,
                   wordlists: @Composable () -> Unit = {}, tools: @Composable () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val slide = tween<IntOffset>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    NavHost(nav, startDestination = "home",
        enterTransition = { if (reduced) EnterTransition.None else slideInHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeIn(fade) },
        exitTransition = { if (reduced) ExitTransition.None else slideOutHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeOut(fade) },
        popEnterTransition = { if (reduced) EnterTransition.None else slideInHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeIn(fade) },
        popExitTransition = { if (reduced) ExitTransition.None else slideOutHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeOut(fade) },
    ) {
        composable("home") { home() }
        composable("host/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { detail(it.arguments!!.getLong("id")) }
        composable("settings") { settings() }
        composable("wordlists") { wordlists() }
        composable("tools") { tools() }
    }
}
