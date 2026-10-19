package com.northwalessolutions.netprobe.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.EmptyState

@Composable
fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Re-read the environment after the answer: the SSID becomes visible without waiting for a link change.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.refreshEnv(granted) }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {   // PINNED: never inside the list
                NetworkPanel(s.env, s.networkName, onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (s.scanning) vm.stopScan() else vm.startScan() }, enabled = s.env != null || s.scanning, modifier = Modifier.testTag("scanButton")) {
                        Text(if (s.scanning) "Stop" else "Scan")
                    }
                    Spacer(Modifier.width(12.dp))
                    val statusText = when {
                        s.status != null -> s.status!!.let { "Found ${it.found} · probed ${it.probed}/${it.total}" }
                        s.quickScanning -> "Quick scanning all devices..."
                        s.rows.isNotEmpty() -> "${s.rows.size} shown"
                        else -> null
                    }
                    Text(statusText ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("homeMenu")) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (s.quickScanning) "Stop quick scan" else "Quick scan all") }, enabled = s.quickScanning || (s.rows.isNotEmpty() && s.env != null),
                                onClick = { menu = false; if (s.quickScanning) vm.stopQuickScan() else vm.quickScanAll() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                        }
                    }
                }
                when {
                    s.scanning -> LinearProgressIndicator(progress = { s.status?.let { st -> if (st.total == 0) 0f else st.probed / st.total.toFloat() } ?: 0f }, Modifier.fillMaxWidth())
                    s.quickScanning -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                FilterRow(s.filter, vm::setFilter)
                OutlinedTextField(s.query, vm::setQuery, Modifier.fillMaxWidth().testTag("search"), placeholder = { Text("Search name, IP, vendor or MAC") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            }
        },
    ) { pad ->
        when {
            s.env == null && !s.scanning -> EmptyState("Connect to Wi-Fi or Ethernet to scan", pad)
            s.rows.isEmpty() && s.query.isNotBlank() -> EmptyState("No devices match \"${s.query}\"", pad)
            s.rows.isEmpty() && s.filter != StatusFilter.ALL -> EmptyState("No devices marked ${s.filter.label}", pad)
            s.rows.isEmpty() -> EmptyState(if (s.scanning) "Looking for devices..." else "No devices found yet, tap Scan", pad)
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("deviceList")) {
                items(s.rows, key = { it.id }) { r -> HostRowItem(r, onClick = { onOpenHost(r.id) }); HorizontalDivider() }
            }
        }
    }
}
