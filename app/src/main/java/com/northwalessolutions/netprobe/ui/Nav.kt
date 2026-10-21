package com.northwalessolutions.netprobe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.manual.ManualToolsScreen
import com.northwalessolutions.netprobe.ui.scans.ScansScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.wordlists.WordlistsScreen

/** The four top-level destinations in bar order (3D spec 1.1). [graph] is the nested graph's route, [root] its start destination. */
enum class Tab(val graph: String, val root: String) {
    DEVICES("devicesTab", "devices"), SCANS("scansTab", "scans"), TOOLS("toolsTab", "tools"), SETTINGS("settingsTab", "settings")
}

/** Every route string in one place. A route may appear once in a NavHost, so the host and wordlists routes are per destination. */
object Routes {
    const val HOST_ARG = "id"
    private const val GRAPH_SUFFIX = "Tab"
    private const val HOST_SEGMENT = "/host/"

    fun hostPattern(tab: Tab): String = "${tab.root}/host/{$HOST_ARG}"
    fun host(tab: Tab, id: Long): String = "${tab.root}/host/$id"
    fun wordlists(tab: Tab): String = "${tab.root}/wordlists"
    /** The routes a destination's graph declares: its root first. Only Devices and Scans open a Device screen. */
    fun routesOf(tab: Tab): List<String> = when (tab) {
        Tab.DEVICES, Tab.SCANS -> listOf(tab.root, hostPattern(tab), wordlists(tab))
        Tab.TOOLS, Tab.SETTINGS -> listOf(tab.root, wordlists(tab))
    }
    /** Works for a graph route ("scansTab"), a root, a pattern ("devices/host/{id}") and a concrete route ("devices/host/5"). */
    fun tabOf(route: String?): Tab? {
        if (route == null) return null
        val head = route.substringBefore('/').removeSuffix(GRAPH_SUFFIX)
        return Tab.entries.firstOrNull { it.root == head }
    }
    fun isRoot(route: String?): Boolean = Tab.entries.any { it.root == route }
    fun isHost(route: String?): Boolean = route != null && route.contains(HOST_SEGMENT)
}

/** The bar shows on the four roots only and hides while the keyboard is up (D5, D6). */
object BarRules {
    fun visible(route: String?, imeVisible: Boolean): Boolean = Routes.isRoot(route) && !imeVisible
}

enum class NavMotionKind { SLIDE, FADE, NONE }

/** Slide inside a destination (3A 1.9), cross-fade between destinations, nothing under reduced motion (D7). */
object NavMotion {
    fun kind(from: String?, to: String?, reduced: Boolean): NavMotionKind {
        if (reduced) return NavMotionKind.NONE
        return if (Routes.tabOf(from) != Routes.tabOf(to)) NavMotionKind.FADE else NavMotionKind.SLIDE
    }
}

/** A bar tap (spec 1.3 rules 1 and 2): each destination keeps its own back stack; tapping the current root does nothing. */
object TabSwitch {
    fun select(nav: NavHostController, tab: Tab) {
        val route = nav.currentDestination?.route
        if (Routes.isRoot(route) && Routes.tabOf(route) == tab) return
        nav.navigate(tab.graph) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * What each route shows. The Compose tests pass fakes, so no test runs a real discovery. [host] and [wordlists] get the label of their
 * back row (D18), already computed from where they were opened.
 */
class NavSlots(
    val devices: @Composable () -> Unit, val scans: @Composable () -> Unit, val tools: @Composable () -> Unit, val settings: @Composable () -> Unit,
    val host: @Composable (tab: Tab, id: Long, backLabel: String) -> Unit = { _, _, _ -> },
    val wordlists: @Composable (tab: Tab, backLabel: String) -> Unit = { _, _ -> },
)

private val BAR_HEIGHT = 80.dp

@Composable
fun NetProbeNav(container: AppContainer) {
    val extended by container.extendedRunActive.collectAsStateWithLifecycle()
    val toolRun by container.toolRunActive.collectAsStateWithLifecycle()
    val portScan by container.portScans.active.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun || portScan)
    val running by container.scansRunning.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    NetProbeShell(nav, running, NavSlots(
        devices = { HomeScreen(container, onOpenHost = { nav.navigate(Routes.host(Tab.DEVICES, it)) }) },
        scans = {
            ScansScreen(container, onOpenDevices = { TabSwitch.select(nav, Tab.DEVICES) }, onOpenHost = { nav.navigate(Routes.host(Tab.SCANS, it)) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SCANS)) { launchSingleTop = true } })
        },
        tools = {
            ManualToolsScreen(container,
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.TOOLS)) { launchSingleTop = true } })
        },
        settings = {
            SettingsScreen(container,
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SETTINGS)) { launchSingleTop = true } })
        },
        host = { tab, id, _ ->
            DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate(Routes.wordlists(tab)) { launchSingleTop = true } })
        },
        wordlists = { _, _ -> WordlistsScreen(container, onBack = { nav.popBackStack() }) }))
}

/**
 * The NavHost with the bar over it (3D spec 1.2). The bar is aligned to the bottom of a Box, not a child of any screen, so it is outside
 * every scroll container by construction. Its height is published through [LocalBarInset] while it shows, so a root screen's last row
 * clears it and the inset is never counted twice; pushed screens keep their own insets and lay out at full height from the first frame.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetProbeShell(nav: NavHostController, scansRunning: Int, slots: NavSlots) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val barVisible = BarRules.visible(route, WindowInsets.isImeVisible)
    val reduced = LocalReducedMotion.current
    val inset: WindowInsets = if (barVisible) WindowInsets(bottom = BAR_HEIGHT).add(WindowInsets.navigationBars) else WindowInsets.navigationBars
    CompositionLocalProvider(LocalBarInset provides inset) {
        Box(Modifier.fillMaxSize()) {
            NetProbeNavHost(nav, slots)
            AnimatedVisibility(visible = barVisible, modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (reduced) EnterTransition.None else slideInVertically(tween(Motion.NAV_MS)) { it } + fadeIn(tween(Motion.NAV_MS)),
                exit = if (reduced) ExitTransition.None else slideOutVertically(tween(Motion.NAV_MS)) { it } + fadeOut(tween(Motion.NAV_MS))) {
                NetProbeBar(selected = Routes.tabOf(route), scansRunning = scansRunning, onSelect = { TabSwitch.select(nav, it) })
            }
        }
    }
}

/** The routes with their transitions (spec 1.5). Stateless over its destinations. */
@Composable
fun NetProbeNavHost(nav: NavHostController, slots: NavSlots, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val slide = tween<IntOffset>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    fun kindOf(from: NavBackStackEntry, to: NavBackStackEntry) = NavMotion.kind(from.destination.route, to.destination.route, reduced)
    NavHost(nav, startDestination = Tab.DEVICES.graph, modifier = modifier,
        enterTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> EnterTransition.None
                NavMotionKind.FADE -> fadeIn(fade)
                NavMotionKind.SLIDE -> slideInHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeIn(fade)
            }
        },
        exitTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> ExitTransition.None
                NavMotionKind.FADE -> fadeOut(fade)
                NavMotionKind.SLIDE -> slideOutHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeOut(fade)
            }
        },
        popEnterTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> EnterTransition.None
                NavMotionKind.FADE -> fadeIn(fade)
                NavMotionKind.SLIDE -> slideInHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeIn(fade)
            }
        },
        popExitTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> ExitTransition.None
                NavMotionKind.FADE -> fadeOut(fade)
                NavMotionKind.SLIDE -> slideOutHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeOut(fade)
            }
        },
    ) {
        Tab.entries.forEach { tabGraph(it, slots, nav) }
    }
}

private fun NavGraphBuilder.tabGraph(tab: Tab, slots: NavSlots, nav: NavHostController) {
    navigation(startDestination = tab.root, route = tab.graph) {
        composable(tab.root) {
            when (tab) {
                Tab.DEVICES -> slots.devices()
                Tab.SCANS -> slots.scans()
                Tab.TOOLS -> slots.tools()
                Tab.SETTINGS -> slots.settings()
            }
        }
        if (tab == Tab.DEVICES || tab == Tab.SCANS) {
            composable(Routes.hostPattern(tab), arguments = listOf(navArgument(Routes.HOST_ARG) { type = NavType.LongType })) { entry ->
                slots.host(tab, entry.arguments!!.getLong(Routes.HOST_ARG), NavLabels.host(tab))
            }
        }
        composable(Routes.wordlists(tab)) { entry ->
            // Frozen at first composition: while this entry pops, the previous entry changes, and the label must not flicker.
            val previous = remember(entry.id) { nav.previousBackStackEntry?.destination?.route }
            slots.wordlists(tab, NavLabels.wordlists(previous, tab))
        }
    }
}
