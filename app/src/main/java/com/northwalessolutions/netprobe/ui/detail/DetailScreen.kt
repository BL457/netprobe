package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.vulns.VulnSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "host$hostId", factory = viewModelFactory { initializer { DetailViewModel(container, hostId) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth()) {   // PINNED header + scan bar: only the ports list below scrolls
            TopAppBar(
                title = { EditableName(s.displayName, s.host?.customName, onRename = vm::setCustomName) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.host?.let { h ->
                    IdentityBlock(h, onLookupVulns = vm::lookupVulnsForDevice)
                    StatusSelector(h.status, vm::setStatus)
                    NoteField(h.id, h.note, vm::setNote)
                }
                ScanBar(s.profile, s.customPortsText, s.customError, s.scanning, s.progress, enabled = s.host != null,
                    onProfile = vm::setProfile, onCustom = vm::setCustomPorts, onScan = { if (s.scanning) vm.stopScan() else vm.startScan() })
                HorizontalDivider()
            }
        }
    }) { pad ->
        if (s.ports.isEmpty()) EmptyState(if (s.scanning) "Scanning ports..." else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("portList")) {
            items(s.ports, key = { it.port }) { p -> PortRowItem(p, onAction = vm::runAction, onVulns = { vm.lookupVulnsForService(p.port) }); HorizontalDivider() }
        }
    }
    s.vuln?.let { VulnSheet(it, onClose = vm::closeVulns, onRerun = vm::rerunVulnQuery, onOpen = vm::openLink) }
}
