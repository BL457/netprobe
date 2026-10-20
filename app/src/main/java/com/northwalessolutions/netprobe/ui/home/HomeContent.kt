package com.northwalessolutions.netprobe.ui.home

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.theme.*

data class HomeActions(
    val onScan: () -> Unit = {}, val onStop: () -> Unit = {}, val onOpenHost: (Long) -> Unit = {}, val onDismissMessage: () -> Unit = {},
    val onFilter: (StatusFilter) -> Unit = {}, val onQuery: (String) -> Unit = {}, val onQuickScanAll: () -> Unit = {},
    val onStopQuickScan: () -> Unit = {}, val onOpenSettings: () -> Unit = {}, val onRequestLocation: () -> Unit = {},
    val onForget: () -> Unit = {}, val onUndo: () -> Unit = {}, val onDismissUndo: () -> Unit = {},
)

private const val MS_PER_S = 1000L

private fun emptyText(s: HomeUiState): String = when {
    s.query.isNotBlank() -> HomeCopy.emptySearch(s.query)
    s.filter != StatusFilter.ALL -> HomeCopy.emptyFilter(s.filter.label)
    s.scanning -> HomeCopy.EMPTY_SCANNING
    else -> HomeCopy.EMPTY_NONE
}

/**
 * All Home layout (Radar spec section 4). Stateless: tests drive any state and record which action fired.
 * PINNED: network line, radar, notice, chips and search live in the Scaffold topBar, never inside the list.
 */
@Composable
fun HomeContent(state: HomeUiState, actions: HomeActions, progress: DiscoveryStatus? = state.status,
                now: Long = System.currentTimeMillis(), ownModel: String = Build.MODEL) {
    // lastScanAt is null when a finished scan stored no host; the in-memory finish time keeps that case SETTLED (spec 3.1), not HERO.
    val mode = RadarModes.of(state.scanning, state.totalHosts, state.lastScanAt ?: state.scanFinishedAt)
    val localSubnet = state.env?.let { SubnetMath.of(it.interfaceIp, it.prefixLength).toString() }
    val elapsedS = state.scanStartedAt?.let { ((now - it) / MS_PER_S).coerceAtLeast(0) } ?: 0L
    val summary = RadarSummaries.settled(state.totalHosts, state.otherSubnetHosts, state.lastScanAt, state.scanStartedAt, state.scanFinishedAt, now, completed = state.scanCompleted)
    // The IME joins the content insets so an empty state or the last rows never sit behind the keyboard while searching.
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = Dimens.gutter, bottom = 10.dp)) {
            NetworkLine(state.env, state.networkName, actions.onRequestLocation, offlineText = HomeCopy.HERO_NO_NETWORK.takeIf { mode != RadarMode.HERO }) {
                HomeMenu(quickEnabled = state.quickScanning || (!state.scanning && state.totalHosts > 0 && state.env != null),
                    quickScanning = state.quickScanning, onQuick = actions.onQuickScanAll, onStopQuick = actions.onStopQuickScan, onSettings = actions.onOpenSettings,
                    forgetEnabled = state.networkId != null && !state.scanning && !state.quickScanning, onForget = actions.onForget)
            }
            RadarBlock(mode, progress?.found ?: 0, ProgressLine.of(progress, elapsedS), summary, canScan = state.env != null,
                onScan = { actions.onDismissMessage(); actions.onScan() }, onStop = actions.onStop, modifier = Modifier.padding(top = 12.dp),
                heroSubnet = localSubnet, quickScanning = state.quickScanning, onStopQuick = actions.onStopQuickScan,
                scanCompleted = state.scanCompleted)
            // While an Undo is pending it takes the notice line; an earlier notice was not dismissed and shows again afterwards (spec 5.3).
            when {
                state.undoText != null -> NoticeLine(state.undoText, actions.onDismissUndo, onUndo = actions.onUndo)
                state.message != null -> NoticeLine(state.message, actions.onDismissMessage)
            }
            if (HeaderRules.showFilters(mode, state.filter, state.query)) {
                FilterRow(state.filter, actions.onFilter)
                SearchField(state.query, actions.onQuery)
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (mode == RadarMode.SETTLED) RangeSummary(state.lastRunRanges)   // nothing when the last run had no extra ranges
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mode == RadarMode.HERO -> HeroPlaceholder()
                    state.rows.isEmpty() -> EmptyState(emptyText(state))
                    else -> DeviceList(state.groups, state, actions, ownModel)
                }
            }
        }
    }
}

@Composable
private fun DeviceList(groups: List<HostGroup>, state: HomeUiState, actions: HomeActions, ownModel: String) {
    val reduced = LocalReducedMotion.current
    // Rows present when the scan began never animate; nor do initial load, filter changes or returning to the screen (spec 3.6).
    var baseline by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(state.scanning) { baseline = if (state.scanning) state.rows.map { it.id }.toSet() else null }
    val risePx = with(LocalDensity.current) { Motion.ROW_RISE.toPx() }
    LazyColumn(Modifier.fillMaxSize().testTag("deviceList")) {
        groups.forEach { g ->
            stickyHeader(key = "header:${g.subnet}:${g.local}") { GroupHeader(GroupHeaders.text(g.subnet, g.local)) }
            items(g.rows, key = { it.id }) { r ->
                val arriving = !reduced && baseline?.let { r.id !in it } == true
                val rise = remember(r.id) { Animatable(if (arriving) 1f else 0f) }
                LaunchedEffect(r.id) { if (rise.value > 0f) rise.animateTo(0f, tween(Motion.ROW_ARRIVAL_MS, easing = Motion.rowEasing)) }
                val itemModifier = if (arriving) Modifier.animateItem(fadeInSpec = tween(Motion.ROW_ARRIVAL_MS, easing = Motion.rowEasing),
                    placementSpec = null, fadeOutSpec = null) else Modifier
                HostRowItem(r, own = RowRules.isOwn(r.ip, state.env), ownModel = ownModel, onClick = { actions.onOpenHost(r.id) },
                    modifier = itemModifier.graphicsLayer { translationY = rise.value * risePx })
            }
        }
    }
}
