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

/** Thin wrapper over [DetailContent]; the view model and its behaviour are unchanged. */
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit, onOpenWordlists: () -> Unit = {},
                 backLabel: String = NavCopy.backTo(Tab.DEVICES)) {
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
            onRunTool = vm::runTool, onStartDirBrute = vm::startDirBrute, onCloseDirBrute = vm::closeDirBrute, onManageWordlists = onOpenWordlists,
            onOpenResults = vm::openResults, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseResults = vm::closeResults, onShare = vm::shareRun,
            onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding, onStopTool = vm::stopTool, onDismissAppMessage = vm::dismissAppMessage),
        liveToolBar = { liveBar.value }, backLabel = backLabel)
}
