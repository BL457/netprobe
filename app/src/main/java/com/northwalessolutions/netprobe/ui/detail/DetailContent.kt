package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.home.IdentityLine
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.ui.vulns.VulnSheet

data class DetailActions(
    val onBack: () -> Unit = {}, val onRename: (String?) -> Unit = {}, val onStatus: (HostStatus) -> Unit = {}, val onNote: (String) -> Unit = {},
    val onProfile: (ScanProfile) -> Unit = {}, val onCustom: (String) -> Unit = {}, val onScan: () -> Unit = {}, val onStop: () -> Unit = {},
    val onAction: (DeviceAction) -> Unit = {}, val onPortVulns: (Int) -> Unit = {}, val onDeviceVulns: () -> Unit = {}, val onCloseVulns: () -> Unit = {},
    val onRerunVulns: (String) -> Unit = {}, val onOpenLink: (String) -> Unit = {}, val onDismissMessage: () -> Unit = {},
)

/** All Detail layout (Radar spec section 5). PINNED: the topBar column; only `portList` scrolls. */
@Composable
fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    var why by rememberSaveable { mutableStateOf(false) }
    var sheetPort by rememberSaveable { mutableStateOf<Int?>(null) }
    val ip = state.host?.ip.orEmpty()
    val focusManager = LocalFocusManager.current
    // The IME joins the content insets (as on Home): the list and the empty state end at the keyboard top. A separate imePadding
    // on the list counted the navigation bar twice and left a 48 dp gap above the keyboard on the Pixel.
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).testTag("detailHeader")) {
            BackToHome(actions.onBack)
            state.host?.let { h ->
                Column(Modifier.padding(horizontal = Dimens.gutter)) {
                    EditableName(state.displayName, h.customName, actions.onRename)
                    identityWithType(IdentityLine.of(h), h.deviceType)?.let {
                        Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(listOfNotNull(h.ip, h.mac).joinToString("  "), style = NetProbeText.data, color = NetProbeColors.textMuted)
                    SoftwareChips(softwareLabels(h))
                    DetailLinks(onWhy = { why = true }, onVulns = actions.onDeviceVulns)
                }
                StatusSegments(h.status, actions.onStatus)
                NoteLine(h.id, h.note, actions.onNote, startExpanded = startNoteExpanded)
            }
            ScanBar(state.profile, state.customPortsText, state.customError, state.scanning, state.progress, enabled = state.host != null,
                onProfile = actions.onProfile, onCustom = actions.onCustom, onScan = { if (state.scanning) actions.onStop() else actions.onScan() })
        }
    }) { pad ->
        if (state.ports.isEmpty()) EmptyState(if (state.scanning) "Scanning ports" else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }   // a tap outside the note field collapses the note; rows consume their own taps
            .testTag("portList")) {
            items(state.ports, key = { it.port }) { p -> PortRowItem(ip, p, onAction = actions.onAction, onLongPress = { sheetPort = p.port }) }
        }
    }
    if (why) state.host?.let { ReasonsSheet(it) { why = false } }
    sheetPort?.let { port ->
        state.ports.firstOrNull { it.port == port }?.let { p ->
            PortSheet(ip, p, onAction = actions.onAction, onVulns = { actions.onPortVulns(p.port) }, onClose = { sheetPort = null })
        }
    }
    state.vuln?.let { VulnSheet(it, onClose = actions.onCloseVulns, onRerun = actions.onRerunVulns, onOpen = actions.onOpenLink) }
}
