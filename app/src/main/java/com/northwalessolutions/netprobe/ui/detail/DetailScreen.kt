package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer

/** Thin wrapper over [DetailContent]; the view model and its behaviour are unchanged. */
@Composable
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "host$hostId", factory = viewModelFactory { initializer { DetailViewModel(container, hostId) } })
    val s by vm.state.collectAsStateWithLifecycle()
    DetailContent(s, DetailActions(onBack = onBack, onEdit = vm::setOverrides, onStatus = vm::setStatus, onNote = vm::setNote,
        onProfile = vm::setProfile, onCustom = vm::setCustomPorts, onScan = vm::startScan, onStop = vm::stopScan, onAction = vm::runAction,
        onPortVulns = vm::lookupVulnsForService, onDeviceVulns = vm::lookupVulnsForDevice, onCloseVulns = vm::closeVulns,
        onRerunVulns = vm::rerunVulnQuery, onOpenLink = vm::openLink, onDismissMessage = vm::dismissMessage))
}
