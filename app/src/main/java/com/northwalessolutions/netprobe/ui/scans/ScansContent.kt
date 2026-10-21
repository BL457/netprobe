package com.northwalessolutions.netprobe.ui.scans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.rootInsets
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolResultsSheet

data class ScansActions(
    val onStop: (ScansItem) -> Unit = {}, val onOpen: (ScansItem) -> Unit = {}, val onClear: () -> Unit = {}, val onUndo: () -> Unit = {},
    val onUndoClear: () -> Unit = {}, val onClearDismissed: () -> Unit = {}, val onDismissApp: (Long) -> Unit = {},
    val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {}, val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {},
    val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {},
)

private val TITLE_ROW_MIN = 56.dp
private val RUNNING_ROW_MIN = 56.dp
private val RECENT_ROW_MIN = 48.dp
private val ROW_VERTICAL = 6.dp
private val PROGRESS_HEIGHT = 2.dp
private val STOP_GAP = 12.dp

@Composable
fun ScansScreen(container: AppContainer, onOpenDevices: () -> Unit, onOpenHost: (Long) -> Unit, onOpenWordlists: () -> Unit) {
    val vm: ScansViewModel = viewModel(factory = viewModelFactory { initializer { ScansViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    ScansContent(s, ScansActions(onStop = vm::stop, onClear = vm::clearHistory, onUndo = vm::undo, onUndoClear = vm::undoClear, onClearDismissed = vm::dismissClear,
        onDismissApp = vm::dismissApp, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseResults = vm::closeResults, onShare = vm::shareRun,
        onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding,
        onOpen = { item ->
            when (val tap = ScansTargets.tap(item, s.visibleHostIds)) {
                ScansTap.DevicesTab -> onOpenDevices()
                is ScansTap.Device -> onOpenHost(tap.hostId)
                is ScansTap.Results -> vm.openRun(tap.runId)
                ScansTap.Wordlists -> onOpenWordlists()
                ScansTap.None -> Unit
            }
        }))
}

/** Stateless, so the Compose tests drive any state. PINNED: the title row and the Running block (with every Stop) are the Scaffold topBar. */
@Composable
fun ScansContent(state: ScansUiState, actions: ScansActions) {
    val snackbar = remember { SnackbarHostState() }
    val snack = ScansRules.snack(state.undoText, state.clearPrompt, state.appMessage)
    LaunchedEffect(snack?.key) {
        when (snack) {
            // A dismissal or timeout commits nothing: the Undo stays pending under the UndoStore rules (spec 3.5 item 6).
            is ScansSnack.Undo -> if (snackbar.showSnackbar(snack.text, actionLabel = ScansCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) actions.onUndo()
            ScansSnack.Cleared ->
                if (snackbar.showSnackbar(ScansCopy.CLEARED, actionLabel = ScansCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) actions.onUndoClear()
                else actions.onClearDismissed()
            is ScansSnack.App -> { snackbar.showSnackbar(snack.text); actions.onDismissApp(snack.id) }
            null -> Unit
        }
    }
    // Leaving the screen ends the chance to undo a clear (spec 3.8, as in 3C 1.2).
    DisposableEffect(Unit) { onDispose { actions.onClearDismissed() } }
    val list = state.list
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).testTag("scansPinned")) {
            TitleRow(canClear = list.recent.isNotEmpty(), onClear = actions.onClear)
            if (list.running.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("scansRunning")) {
                Text(ScansCopy.RUNNING, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 4.dp).semantics { heading() })
                list.running.forEach { item ->
                    key(item.key) { RunningRow(item, tappable = ScansTargets.tap(item, state.visibleHostIds) != ScansTap.None, actions) }
                }
            }
        }
    }) { pad ->
        if (list.running.isEmpty() && list.recent.isEmpty()) EmptyState(ScansCopy.EMPTY, pad, line = ScansCopy.EMPTY_LINE)
        else LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("scansRecent")) {
            if (list.recent.isNotEmpty()) item(key = "recentHeader") {
                Text(ScansCopy.RECENT, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 8.dp).semantics { heading() })
            }
            items(list.recent, key = { it.key }) { item ->
                RecentRow(item, tappable = ScansTargets.tap(item, state.visibleHostIds) != ScansTap.None) { actions.onOpen(item) }
            }
        }
    }
    state.results?.let { r ->
        ToolResultsSheet(r, actions.onOlderRun, actions.onNewerRun, actions.onCopyFinding, actions.onOpenFinding, actions.onShare, actions.onCloseResults)
    }
}

@Composable
private fun TitleRow(canClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
        Text(ScansCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text,
            modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).alpha(if (canClear) 1f else DISABLED_ALPHA)
            .clickable(enabled = canClear, role = Role.Button, onClick = onClear).testTag("scansClear"), contentAlignment = Alignment.Center) {
            Text(ScansCopy.CLEAR, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = Dimens.gutter))
        }
    }
}

/** Title, progress text, a 2 dp line, and the Stop pill, which is never inside a scroll container (the block is the topBar). */
@Composable
private fun RunningRow(item: ScansItem, tappable: Boolean, actions: ScansActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = RUNNING_ROW_MIN).padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("running:${item.key}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = tappable, role = Role.Button) { actions.onOpen(item) }) {
            Text(item.title, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.detail, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RowProgress(item)
        }
        ActionPill(ScansCopy.STOP, enabled = true, onClick = { actions.onStop(item) }, tag = "stop:${item.key}",
            modifier = Modifier.padding(start = STOP_GAP).semantics { contentDescription = ScansCopy.stopDescription(item.title) })
    }
}

/** Determinate when the total is known; indeterminate otherwise, and absent under reduced motion (spec 3.5 item 2). */
@Composable
private fun RowProgress(item: ScansItem) {
    val total = item.total; val done = item.done
    val modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(PROGRESS_HEIGHT)
    if (total != null && total > 0 && done != null) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier, color = NetProbeColors.signal, trackColor = NetProbeColors.surface)
    } else if (!LocalReducedMotion.current) {
        LinearProgressIndicator(modifier = modifier, color = NetProbeColors.signal, trackColor = NetProbeColors.surface)
    }
}

/** Recent rows carry no controls (spec 3.5 item 3). */
@Composable
private fun RecentRow(item: ScansItem, tappable: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = RECENT_ROW_MIN).clickable(enabled = tappable, role = Role.Button, onClick = onClick)
        .padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("recent:${item.key}")) {
        Text(item.title, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.detail, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
