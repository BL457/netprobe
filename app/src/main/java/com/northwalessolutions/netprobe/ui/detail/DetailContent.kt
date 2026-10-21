package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.home.IdentityLine
import com.northwalessolutions.netprobe.ui.home.arrivalDelay
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.theme.motionSpec
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.ui.tools.*
import com.northwalessolutions.netprobe.ui.vulns.VulnSheet

data class DetailActions(
    val onBack: () -> Unit = {}, val onEdit: (DeviceEdits) -> Unit = {}, val onStatus: (HostStatus) -> Unit = {}, val onNote: (String) -> Unit = {},
    val onProfile: (ScanProfile) -> Unit = {}, val onCustom: (String) -> Unit = {}, val onScan: () -> Unit = {}, val onStop: () -> Unit = {},
    val onAction: (DeviceAction) -> Unit = {}, val onPortVulns: (Int) -> Unit = {}, val onDeviceVulns: () -> Unit = {}, val onCloseVulns: () -> Unit = {},
    val onRerunVulns: (String) -> Unit = {}, val onOpenLink: (String) -> Unit = {}, val onDismissMessage: () -> Unit = {},
    val onRemove: () -> Unit = {},
)

/** All Detail layout (Radar spec section 5). PINNED: the topBar column; only `portList` scrolls. */
@Composable
fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false,
                  toolState: DetailToolsState = DetailToolsState(), toolActions: ToolActions = ToolActions(),
                  /** The bar's per-tick fields (DetailViewModel.toolBar); null uses toolState.bar as it stands. Read only in leaf composables. */
                  liveToolBar: (() -> ToolRunBar?)? = null) {
    val reduced = LocalReducedMotion.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    LaunchedEffect(toolState.appMessage?.id) {
        toolState.appMessage?.let { m -> snackbar.showSnackbar(m.text); toolActions.onDismissAppMessage(m.id) }
    }
    var toolsExpanded by rememberSaveable(state.host?.id) { mutableStateOf(false) }
    var why by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var sheetPort by rememberSaveable { mutableStateOf<Int?>(null) }
    val ip = state.host?.ip.orEmpty()
    val focusManager = LocalFocusManager.current
    // Detail port rows arrive as found (spec 1.10): the set listed when the scan starts never fades or rises.
    var portBaseline by remember { mutableStateOf<Set<Int>?>(null) }
    LaunchedEffect(state.scanning) { portBaseline = if (state.scanning) state.ports.map { it.port }.toSet() else null }
    val risePx = with(LocalDensity.current) { Motion.ROW_RISE.toPx() }
    // The IME joins the content insets (as on Home): the list and the empty state end at the keyboard top. A separate imePadding
    // on the list counted the navigation bar twice and left a 48 dp gap above the keyboard on the Pixel.
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).testTag("detailHeader")) {
            BackToHome(actions.onBack) { if (state.host != null) DetailMenu(onEdit = { editing = true }, removeEnabled = toolState.removeAllowed, onRemove = { if (state.scanning) actions.onStop(); actions.onRemove(); actions.onBack() }) }
            state.host?.let { h ->
                Column(Modifier.padding(horizontal = Dimens.gutter)) {
                    DetailName(state.displayName) { editing = true }
                    identityWithType(IdentityLine.of(h), Effective.type(h))?.let { line ->
                        Crossfade(line, animationSpec = motionSpec(reduced, Motion.IDENTITY_FADE_MS), label = "identity") {
                            IdentityText(it, state.displayName) { editing = true }
                        }
                    }
                    Text(listOfNotNull(h.ip, h.mac).joinToString("  "), style = NetProbeText.data, color = NetProbeColors.textMuted)
                    SoftwareChips(softwareLabels(h))
                    DetailLinks(onWhy = { why = true }, onVulns = actions.onDeviceVulns)
                }
                StatusSegments(h.status, actions.onStatus)
                NoteLine(h.id, h.note, actions.onNote, startExpanded = startNoteExpanded)
            }
            ScanBar(state.profile, state.customPortsText, state.customError, state.scanning, state.progress, enabled = state.host != null,
                identifying = state.identifying, identified = state.identified,
                onProfile = actions.onProfile, onCustom = actions.onCustom, onScan = { if (state.scanning) actions.onStop() else actions.onScan() },
                toolBar = toolState.bar, onStopTool = toolActions.onStopTool, liveToolBar = liveToolBar ?: { toolState.bar })
        }
    }) { pad ->
        val tools = toolState.tools
        if (state.ports.isEmpty() && tools == null) EmptyState(if (state.scanning) "Scanning ports" else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }   // a tap outside the note field collapses the note; rows consume their own taps
            .testTag("portList")) {
            tools?.let { t ->
                item(key = "tools") {
                    ToolsBlock(t, toolsExpanded, busy = toolState.busy != null, onToggle = { toolsExpanded = !toolsExpanded },
                        onRunChip = toolActions.onRunTool, onResults = toolActions.onOpenResults)
                }
            }
            items(state.ports, key = { it.port }) { p ->
                val base = portBaseline
                val arriving = !reduced && base != null && p.port !in base
                val rise = remember(p.port) { Animatable(if (arriving) 1f else 0f) }
                LaunchedEffect(p.port) { if (rise.value > 0f) rise.animateTo(0f, tween(Motion.PORT_ROW_MS, easing = FastOutSlowInEasing)) }
                // While a scan runs every row gets the placement spec, so rows below a new port slide down instead of jumping
                // (spec 9.2 test 8); only the new row fades and rises.
                val itemModifier = if (reduced || base == null) Modifier else Modifier.animateItem(
                    fadeInSpec = if (arriving) tween(Motion.PORT_ROW_MS) else null,
                    placementSpec = tween(Motion.PORT_ROW_MS, easing = FastOutSlowInEasing), fadeOutSpec = null)
                PortRowItem(ip, p, onAction = actions.onAction, onLongPress = { sheetPort = p.port },
                    modifier = itemModifier.graphicsLayer { translationY = rise.value * risePx }.semantics { arrivalDelay = if (arriving) 0 else -1 })
            }
        }
    }
    if (why) state.host?.let { ReasonsSheet(it) { why = false } }
    if (editing) state.host?.let { h ->
        EditDeviceDialog(EditDraft.initial(h), Detected.of(h), onSave = { actions.onEdit(it); editing = false }, onCancel = { editing = false })
    }
    sheetPort?.let { port ->
        state.ports.firstOrNull { it.port == port }?.let { p ->
            PortSheet(ip, p, onAction = actions.onAction, onVulns = { actions.onPortVulns(p.port) }, onClose = { sheetPort = null },
                tools = ToolApplicability.toolsFor(p.port, p.service), toolsBusy = toolState.busy != null, onTool = { toolActions.onRunTool(it, p.port) })
        }
    }
    state.vuln?.let { VulnSheet(it, onClose = actions.onCloseVulns, onRerun = actions.onRerunVulns, onOpen = actions.onOpenLink) }
    toolState.dirBrute?.let { DirBruteSheet(it, onRun = toolActions.onStartDirBrute, onManageWordlists = { toolActions.onCloseDirBrute(); toolActions.onManageWordlists() }, onDismiss = toolActions.onCloseDirBrute) }
    toolState.results?.let { r ->
        // A live run's status line follows the per-tick bar, read inside the sheet's status text only.
        val liveStatus: (() -> String)? = if (r.active && liveToolBar != null) { { liveToolBar()?.let { ToolCopy.resultsRunning(it.progress) } ?: r.statusLine } } else null
        ToolResultsSheet(r, toolActions.onOlderRun, toolActions.onNewerRun, toolActions.onCopyFinding, toolActions.onOpenFinding, toolActions.onShare, toolActions.onCloseResults,
            liveStatus = liveStatus)
    }
}
