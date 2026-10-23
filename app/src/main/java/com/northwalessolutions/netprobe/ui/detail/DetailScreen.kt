package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.NavCopy
import com.northwalessolutions.netprobe.ui.Tab
import com.northwalessolutions.netprobe.ui.tools.ToolActions
import com.northwalessolutions.netprobe.ui.tools.ToolLaunch

/** Thin wrapper over [DetailContent]; the view model and its behaviour are unchanged. */
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit, onOpenWordlists: () -> Unit = {},
                 onOpenTool: (toolKey: String, hostId: Long, port: Int) -> Unit = { _, _, _ -> }, backLabel: String = NavCopy.backTo(Tab.DEVICES)) {
    val vm: DetailViewModel = viewModel(key = "host$hostId", factory = viewModelFactory { initializer { DetailViewModel(container, hostId) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val tools by vm.toolState.collectAsStateWithLifecycle()
    // Not delegated: the per-tick bar is read only inside the leaf composables, through the lambda below (3B QA F4).
    val liveBar = vm.toolBar.collectAsStateWithLifecycle()
    DetailContent(s, DetailActions(onBack = onBack, onEdit = vm::setOverrides, onStatus = vm::setStatus, onNote = vm::setNote,
        onProfile = vm::setProfile, onCustom = vm::setCustomPorts, onScan = vm::startScan, onStop = vm::stopScan, onAction = vm::runAction,
        onPortVulns = vm::lookupVulnsForService, onDeviceVulns = vm::lookupVulnsForDevice, onCloseVulns = vm::closeVulns,
        onRerunVulns = vm::rerunVulnQuery, onOpenLink = vm::openLink, onDismissMessage = vm::dismissMessage, onRemove = vm::removeDevice),
        toolState = tools, toolActions = ToolActions(
            // A tool with inputs to ask (or a network "any" scope that needs its per-run confirmation) opens its page with this device and port filled in;
            // any other tool runs at once, as before.
            onRunTool = { key, port ->
                if (container.toolRegistry.byKey(key)?.descriptor?.let(ToolLaunch::opensPage) == true) onOpenTool(key, hostId, port) else vm.runTool(key, port)
            },
            onOpenResults = vm::openResults, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseResults = vm::closeResults, onShare = vm::shareRun,
            onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding, onStopTool = vm::stopTool, onDismissAppMessage = vm::dismissAppMessage),
        liveToolBar = { liveBar.value }, backLabel = backLabel)
}
