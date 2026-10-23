package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.AppMessageDelivery
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.ui.BackRow
import com.northwalessolutions.netprobe.ui.LiveLine
import com.northwalessolutions.netprobe.ui.NavCopy
import com.northwalessolutions.netprobe.ui.StopPill
import com.northwalessolutions.netprobe.ui.Tab
import com.northwalessolutions.netprobe.ui.theme.*

/** Every action the tool page fires; the Compose tests record them. */
data class ToolPageActions(
    val form: ToolFormActions = ToolFormActions(), val onBack: () -> Unit = {}, val onRun: (confirmed: Boolean) -> Unit = {}, val onStop: () -> Unit = {},
    val onOpenRun: (Long) -> Unit = {}, val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {}, val onCloseSheet: () -> Unit = {}, val onShare: () -> Unit = {},
    val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {}, val onDismissMessage: () -> Unit = {}, val onDismissApp: (Long) -> Unit = {},
)

private val SECTION_TOP = 16.dp
private val ROW_VERTICAL = 8.dp
private const val RESULTS_INDEX = 2

@Composable
fun ToolPageScreen(container: AppContainer, key: String, hostId: Long?, port: Int?, onBack: () -> Unit, onOpenWordlists: () -> Unit, backLabel: String = NavCopy.backTo(Tab.TOOLS)) {
    val vm: ToolPageViewModel = viewModel(key = "tool:$key:$hostId:$port", factory = viewModelFactory { initializer { ToolPageViewModel(container, key, hostId, port) } })
    val s by vm.state.collectAsStateWithLifecycle()
    // Not delegated: the per-tick text is read only inside LiveLine's draw (3B QA F4).
    val liveProgress = vm.liveProgress.collectAsStateWithLifecycle()
    val liveSheet = vm.liveSheet.collectAsStateWithLifecycle()
    ToolPageContent(s, vm.form, ToolPageActions(
        form = ToolFormActions(onDevice = vm::chooseDevice, onPort = vm::setPort, onHttps = vm::setHttps, onInput = vm::setInput, onManageWordlists = onOpenWordlists),
        onBack = onBack, onRun = vm::run, onStop = vm::stop, onOpenRun = vm::openRun, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseSheet = vm::closeSheet,
        onShare = vm::shareRun, onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding, onDismissMessage = vm::dismissMessage, onDismissApp = vm::dismissAppMessage),
        liveProgress = { liveProgress.value }, liveSheetStatus = { liveSheet.value }, backLabel = backLabel)
}

/**
 * A tool's page (spec section 5): name and description, the generated form, then the run's progress, findings and the previous runs, all in
 * one list; Run (or Stop with the live progress while running) is pinned under it, outside the scroll. Stateless.
 */
@Composable
fun ToolPageContent(state: ToolPageState, form: ToolForm?, actions: ToolPageActions, liveProgress: () -> String? = { null }, liveSheetStatus: () -> String? = { null },
                    backLabel: String = NavCopy.backTo(Tab.TOOLS)) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    val messageScope = rememberCoroutineScope()
    LaunchedEffect(state.appMessage?.id) { state.appMessage?.let { m -> AppMessageDelivery.consume(messageScope, m, actions.onDismissApp) { snackbar.showSnackbar(it) } } }
    val module = state.module
    if (module == null || form == null) {
        Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars, snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) { BackRow(backLabel, actions.onBack) } }) { pad ->
            if (module == null) Text(ToolsUiCopy.NOT_FOUND, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(pad).padding(Dimens.gutter).testTag("toolNotFound"))
        }
        return
    }
    val d = module.descriptor
    val evaluation = remember(module, form, state.context) { ToolFormRules.evaluate(module, form, state.context) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val focus: FocusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.runId) { if (state.runId != null) listState.scrollToItem(RESULTS_INDEX) }
    val run = state.run
    val runFindings = remember(run?.findings, state.runStatus) { run?.let { RunFindingsSplit.of(it.findings, state.runStatus == ToolRunStatus.FAILED) } }

    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
                BackRow(backLabel, actions.onBack)
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ToolIcons.vector(d.icon), null, tint = NetProbeColors.signal)
                    Spacer(Modifier.width(10.dp))
                    Text(d.name, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.semantics { heading() }.testTag("toolTitle"))
                }
            }
        },
        bottomBar = {
            RunBar(state.running, evaluation.canRun, ToolFormRules.hint(evaluation, state.context.lan), liveProgress,
                onRun = { focus.clearFocus(); if (ToolFormRules.needsConfirm(d)) confirm = true else actions.onRun(false) }, onStop = actions.onStop)
        }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("toolPage"), state = listState) {
            item(key = "description") {
                Column(Modifier.padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL)) {
                    Text(d.description, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                    if (d.network != NetworkScope.TARGET) Text(ToolsUiCopy.scope(d.network), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 4.dp).testTag("scopeLine"))
                }
            }
            item(key = "form") {
                Box(Modifier.padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL)) { ToolFormFields(module, form, evaluation, state, enabled = !state.running, actions = actions.form) }
            }
            if (run != null && runFindings != null) {
                item(key = "resultsHeader") {
                    Column(Modifier.padding(horizontal = Dimens.gutter).padding(top = SECTION_TOP, bottom = ROW_VERTICAL).testTag("runHeader")) {
                        Text(ToolsUiCopy.RESULTS, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.semantics { heading() })
                        if (state.running) LiveLine({ liveProgress() ?: ToolCopy.STARTING }, style = NetProbeText.data, color = NetProbeColors.textMuted, textModifier = Modifier.testTag("runProgress"))
                        else Text(run.statusLine, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("runStatus"))
                        run.route?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted) }
                        if (run.summary.isNotEmpty()) Text(run.summary, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("runSummary"))
                    }
                }
                if (runFindings.findings.isEmpty()) item(key = "noFindings") {
                    Text(if (state.running) ToolCopy.EMPTY_RUNNING else ToolCopy.NO_FINDINGS, style = NetProbeText.secondary, color = NetProbeColors.textMuted,
                        modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL))
                }
                items(runFindings.findings, key = { "f${it.id}" }) { row -> FindingRowItem(row, actions.onCopyFinding, actions.onOpenFinding) }
                if (runFindings.showLog) item(key = "log") { LogSection(runFindings.log, actions.onCopyFinding, actions.onOpenFinding) }
            }
            item(key = "previousHeader") {
                Text(ToolsUiCopy.PREVIOUS_RUNS, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter).padding(top = SECTION_TOP, bottom = ROW_VERTICAL).semantics { heading() })
            }
            if (state.previous.isEmpty()) item(key = "previousEmpty") {
                Text(ToolsUiCopy.NO_PREVIOUS_RUNS, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter))
            }
            items(state.previous, key = { "p${it.runId}" }) { r -> PreviousRunRow(r) { actions.onOpenRun(r.runId) } }
            item(key = "end") { Spacer(Modifier.height(SECTION_TOP)) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = NetProbeColors.surface,
        title = { Text(ToolsUiCopy.confirmTitle(d.name), style = NetProbeText.title, color = NetProbeColors.text) },
        text = { Text(ToolsUiCopy.confirmBody(d.name), style = NetProbeText.secondary, color = NetProbeColors.textSecondary) },
        confirmButton = {
            TextButton(onClick = { confirm = false; actions.onRun(true) }, modifier = Modifier.heightIn(min = Dimens.touch).testTag("confirmRun")) { Text(ToolsUiCopy.CONFIRM_RUN, color = NetProbeColors.signal) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = Dimens.touch).testTag("confirmCancel")) { Text(ToolsUiCopy.CANCEL, color = NetProbeColors.textMuted) } })
    state.sheet?.let { r ->
        val liveStatus: (() -> String)? = if (r.active) { { liveSheetStatus() ?: r.statusLine } } else null
        ToolResultsSheet(r, actions.onOlderRun, actions.onNewerRun, actions.onCopyFinding, actions.onOpenFinding, actions.onShare, actions.onCloseSheet, liveStatus = liveStatus)
    }
}

/** Run, or Stop with the live progress while a run is live. Pinned: it is the Scaffold's bottom bar, never inside the scroll. */
@Composable
private fun RunBar(running: Boolean, canRun: Boolean, hint: String?, liveProgress: () -> String?, onRun: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).testTag("runBar")) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                StopPill(onStop, description = ToolCopy.STOP_DESCRIPTION, tag = "toolStop")
                LiveLine({ liveProgress() ?: ToolCopy.STARTING }, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f).padding(start = 12.dp),
                    textModifier = Modifier.testTag("barProgress"))
            } else {
                ActionPill(ToolCopy.RUN, enabled = canRun, onClick = onRun, tag = "toolRun")
                hint?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("runHint")) }
            }
        }
    }
}

/** The script's log lines after a failed run, collapsed until the Director opens them. */
@Composable
private fun LogSection(lines: List<FindingRow>, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("logSection")) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = Dimens.gutter).testTag("logToggle"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(ToolsUiCopy.LOG, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(end = 12.dp))
            Text(ToolsUiCopy.logToggle(expanded, lines.size), style = NetProbeText.action, color = NetProbeColors.signal)
        }
        if (expanded) lines.forEach { FindingRowItem(it, onCopy, onOpen) }
    }
}

@Composable
private fun PreviousRunRow(r: PreviousRun, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = r.description }
        .padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("previousRun:${r.runId}")) {
        Text(r.title, style = NetProbeText.secondary, color = NetProbeColors.text)
        Text(r.line, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
