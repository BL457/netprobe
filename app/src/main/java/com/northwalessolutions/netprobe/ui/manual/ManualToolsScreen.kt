package com.northwalessolutions.netprobe.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.ui.rootInsets
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.LiveLine
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.settings.SwitchRow
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheet
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolResultsSheet

/** Every action the screen fires; ManualToolsScreen wires them to ManualToolsViewModel, the Compose tests record them. */
data class ManualActions(
    val onBack: () -> Unit = {}, val onIp: (String) -> Unit = {}, val onPort: (String) -> Unit = {}, val onTool: (ToolId) -> Unit = {},
    val onHttps: (Boolean) -> Unit = {}, val onRun: () -> Unit = {}, val onStop: () -> Unit = {}, val onOpenRun: (Long) -> Unit = {},
    val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {}, val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {},
    val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {}, val onStartDirBrute: (DirBruteOptions) -> Unit = {},
    val onCloseDirBrute: () -> Unit = {}, val onManageWordlists: () -> Unit = {}, val onDismissMessage: () -> Unit = {}, val onDismissApp: (Long) -> Unit = {},
)

private const val IP_WEIGHT = 0.65f
private const val PORT_WEIGHT = 0.35f
private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val FIELD_GAP = 8.dp
private val LINE_VERTICAL = 6.dp
private val ROW_VERTICAL = 8.dp
private val PROGRESS_START = 12.dp

@Composable
fun ManualToolsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {
    val vm: ManualToolsViewModel = viewModel(factory = viewModelFactory { initializer { ManualToolsViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    // Not delegated: the per-tick text is read only inside LiveLine's draw and the sheet's live status, through the lambdas below (3B QA F4).
    val liveProgress = vm.liveProgress.collectAsStateWithLifecycle()
    val liveResults = vm.liveResults.collectAsStateWithLifecycle()
    ManualToolsContent(s, ManualActions(onBack = onBack, onIp = vm::setIp, onPort = vm::setPort, onTool = vm::setTool, onHttps = vm::setHttps,
        onRun = vm::runTool, onStop = vm::stopTool, onOpenRun = vm::openRun, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun,
        onCloseResults = vm::closeResults, onShare = vm::shareRun, onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding,
        onStartDirBrute = vm::startDirBrute, onCloseDirBrute = vm::closeDirBrute, onManageWordlists = onOpenWordlists,
        onDismissMessage = vm::dismissMessage, onDismissApp = vm::dismissAppMessage),
        liveProgress = { liveProgress.value }, liveResultsStatus = { liveResults.value })
}

/**
 * "Run a tool" (3C spec 4.1). Stateless. PINNED: the fields, the tool row and the Run / Stop pill sit in the topBar and never scroll; the
 * history list below is the only scrolling part.
 */
@Composable
fun ManualToolsContent(state: ManualUiState, actions: ManualActions, liveProgress: () -> String? = { null }, liveResultsStatus: () -> String? = { null }) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    LaunchedEffect(state.appMessage?.id) { state.appMessage?.let { m -> snackbar.showSnackbar(m.text); actions.onDismissApp(m.id) } }
    val editable = state.lan && !state.running
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 10.dp)) {
                BackToHome(actions.onBack)
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                    Text(ManualCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.semantics { heading() })
                    Row(Modifier.fillMaxWidth().padding(top = ROW_VERTICAL), horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
                        OutlinedTextField(state.form.ipText, actions.onIp, Modifier.weight(IP_WEIGHT).testTag("manualIp"), enabled = editable,
                            label = { Text(ManualCopy.IP) }, placeholder = { Text(ManualCopy.IP_PLACEHOLDER) }, singleLine = true,
                            isError = state.ipError != null, supportingText = state.ipError?.let { e -> { Text(e) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = npFieldColors(), shape = FIELD_SHAPE)
                        OutlinedTextField(state.form.portText, actions.onPort, Modifier.weight(PORT_WEIGHT).testTag("manualPort"),
                            enabled = editable && state.portEnabled, label = { Text(ManualCopy.PORT) }, placeholder = { Text(ManualCopy.PORT_PLACEHOLDER) },
                            singleLine = true, isError = state.portError != null, supportingText = state.portError?.let { e -> { Text(e) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = npFieldColors(), shape = FIELD_SHAPE)
                    }
                    ToolPicker(state.form.tool, editable, actions.onTool)
                    if (state.showHttps) SwitchRow(ManualCopy.USE_HTTPS, state.https, enabled = editable) { actions.onHttps(it) }
                    state.fixedLine?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = LINE_VERTICAL)) }
                    state.infoLine?.let {
                        Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = LINE_VERTICAL).testTag("manualInfo"))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = ROW_VERTICAL), verticalAlignment = Alignment.CenterVertically) {
                        ActionPill(if (state.running) HomeCopy.STOP else ToolCopy.RUN, enabled = state.lan || state.running,
                            onClick = if (state.running) actions.onStop else actions.onRun, tag = "manualRun",
                            modifier = if (state.running) Modifier.semantics { contentDescription = ToolCopy.STOP_DESCRIPTION } else Modifier)
                        // Drawn per tick, never recomposed (3B QA F4): the text is read in LiveLine's draw only.
                        if (state.running) LiveLine({ liveProgress() ?: ToolCopy.STARTING }, style = NetProbeText.data, color = NetProbeColors.textMuted,
                            modifier = Modifier.weight(1f).padding(start = PROGRESS_START), textModifier = Modifier.testTag("manualProgress"))
                    }
                }
            }
        }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("manualHistory")) {
            item(key = "historyHeader") {
                Text(ManualCopy.HISTORY, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).semantics { heading() })
            }
            if (state.history.isEmpty()) item(key = "historyEmpty") {
                Text(ManualCopy.HISTORY_EMPTY, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter))
            }
            items(state.history, key = { it.runId }) { r -> HistoryRow(r) { actions.onOpenRun(r.runId) } }
        }
    }
    state.dirBrute?.let {
        DirBruteSheet(it, onRun = actions.onStartDirBrute, onManageWordlists = { actions.onCloseDirBrute(); actions.onManageWordlists() }, onDismiss = actions.onCloseDirBrute)
    }
    state.results?.let { r ->
        // A live run's status line follows the per-tick text, read inside the sheet's status line only (as DetailContent does, 3B QA F4).
        val liveStatus: (() -> String)? = if (r.active) { { liveResultsStatus() ?: r.statusLine } } else null
        ToolResultsSheet(r, actions.onOlderRun, actions.onNewerRun, actions.onCopyFinding, actions.onOpenFinding, actions.onShare, actions.onCloseResults,
            liveStatus = liveStatus)
    }
}

/** "Tool: HTTP fingerprint" with a chevron; the menu lists the five tools in ToolId order (spec 4.1 item 4). */
@Composable
private fun ToolPicker(tool: ToolId, enabled: Boolean, onPick: (ToolId) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.Button) { open = true }.testTag("manualTool"), verticalAlignment = Alignment.CenterVertically) {
            Text(ManualCopy.toolRow(ToolCopy.toolName(tool)), style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            ToolId.entries.forEach { t ->
                DropdownMenuItem(text = { Text(ToolCopy.toolName(t)) }, onClick = { open = false; onPick(t) }, modifier = Modifier.testTag("manualTool:${t.key}"))
            }
        }
    }
}

/** One history row, 48 dp minimum, its description naming the tool and target (spec 4.1). */
@Composable
private fun HistoryRow(r: ManualHistoryRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = r.description }
        .padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("historyRow:${r.runId}")) {
        Text(r.title, style = NetProbeText.secondary, color = NetProbeColors.text)
        Text(r.line, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
