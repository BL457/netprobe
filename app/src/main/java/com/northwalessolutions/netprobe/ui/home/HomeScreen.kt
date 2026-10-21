package com.northwalessolutions.netprobe.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.ui.theme.Motion
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

private const val TICK_SCANNING_MS = 1_000L
private const val TICK_IDLE_MS = 30_000L

/** Thin wrapper: builds the view model, samples progress, keeps a clock, and hands everything to [HomeContent]. */
@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    // Re-read the environment after the answer: the SSID becomes visible without waiting for a link change.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.refreshEnv(granted) }
    // Progress reaches the header at most every 250 ms (spec 3.6). The view model nulls `status` in the same emission scanning
    // stops, so the last non-null value is latched for the settle (spec 3.3) and cleared when a new scan starts.
    val sampled by remember(vm) { vm.state.map { it.status }.filterNotNull().distinctUntilChanged().sample(Motion.PROGRESS_SAMPLE_MS) }
        .collectAsStateWithLifecycle(null)
    var latched by remember { mutableStateOf<DiscoveryStatus?>(null) }
    LaunchedEffect(s.scanning) { if (s.scanning) latched = null }
    LaunchedEffect(sampled) { latched = sampled }
    val now by produceState(System.currentTimeMillis(), s.scanning) {
        while (true) { value = System.currentTimeMillis(); delay(if (s.scanning) TICK_SCANNING_MS else TICK_IDLE_MS) }
    }
    HomeContent(
        state = s,
        actions = HomeActions(onScan = vm::startScan, onStop = vm::stopScan, onOpenHost = onOpenHost, onDismissMessage = vm::dismissMessage,
            onFilter = vm::setFilter, onQuery = vm::setQuery, onQuickScanAll = vm::quickScanAll, onStopQuickScan = vm::stopQuickScan,
            onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onForget = vm::forgetNetwork, onUndo = vm::undo, onDismissUndo = vm::dismissUndo, onDismissApp = vm::dismissAppMessage),
        progress = latched, now = now,
    )
}
