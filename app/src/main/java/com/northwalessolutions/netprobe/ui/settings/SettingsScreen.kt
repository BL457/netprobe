package com.northwalessolutions.netprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.SettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONC_MIN = 32; private const val CONC_MAX = 512; private const val CONC_STEP = 32
private const val TIMEOUT_MIN = 200; private const val TIMEOUT_MAX = 2000; private const val TIMEOUT_STEP = 100
private val PREFIX_OPTIONS = listOf(20, 22, 24)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val update: ((SettingsState) -> SettingsState) -> Unit = { container.settings.update(it) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", s.mdns) { v -> update { it.copy(mdns = v) } }
            SwitchRow("SSDP / UPnP", s.ssdp) { v -> update { it.copy(ssdp = v) } }
            SwitchRow("NetBIOS names", s.netbios) { v -> update { it.copy(netbios = v) } }
            SwitchRow("Reverse DNS", s.reverseDns) { v -> update { it.copy(reverseDns = v) } }
            SwitchRow("SNMP", s.snmp) { v -> update { it.copy(snmp = v) } }
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }

            Section("Speed")
            StepSlider("Sweep concurrency", s.sweepConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, "") { v -> update { it.copy(sweepConcurrency = v) } }
            StepSlider("Sweep connect timeout", s.connectTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, " ms") { v -> update { it.copy(connectTimeoutMs = v) } }
            StepSlider("Port scan concurrency", s.scanConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, "") { v -> update { it.copy(scanConcurrency = v) } }
            StepSlider("Port scan timeout", s.scanTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, " ms") { v -> update { it.copy(scanTimeoutMs = v) } }

            Section("Large networks")
            Text("Largest network to sweep. Bigger networks are cut down to your own /24 unless widened here.", style = MaterialTheme.typography.bodySmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PREFIX_OPTIONS.forEachIndexed { i, p ->
                    SegmentedButton(selected = s.maxPrefix == p, onClick = { update { it.copy(maxPrefix = p) } }, shape = SegmentedButtonDefaults.itemShape(i, PREFIX_OPTIONS.size)) { Text("/$p") }
                }
            }

            Section("Permissions")
            LocationPermissionRow()

            Section("Vulnerabilities")
            var key by remember { mutableStateOf(s.nvdApiKey) }
            OutlinedTextField(key, { key = it; update { st -> st.copy(nvdApiKey = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("NVD API key (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(onClick = {
                scope.launch {
                    val msg = try { container.vulnCache.clear(); "Vulnerability cache cleared" } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "Could not clear cache: ${e.message}" }
                    snackbar.showSnackbar(msg)
                }
            }) { Text("Clear vulnerability cache") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}

/** Moves locally while dragging and saves once on release, so preferences are not rewritten on every drag frame. */
@Composable
private fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
    Column {
        Row { Text(label, Modifier.weight(1f)); Text("$snapped$unit", style = MaterialTheme.typography.labelLarge) }
        Slider(value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1,
            onValueChangeFinished = { if (snapped != value) onCommit(snapped) })
    }
}

@Composable
private fun LocationPermissionRow() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Text("Android only reveals the Wi-Fi name and BSSID with location permission. It also lets NetProbe tell two networks apart that share 192.168.1.1/24.",
        style = MaterialTheme.typography.bodySmall)
    if (granted) Text("Location permission granted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    else Button(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow location for Wi-Fi name") }
}
