package com.northwalessolutions.netprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONC_MIN = 32; private const val CONC_MAX = 512; private const val CONC_STEP = 32
private const val TIMEOUT_MIN = 200; private const val TIMEOUT_MAX = 2000; private const val TIMEOUT_STEP = 100
private val PREFIX_OPTIONS = listOf(20, 22, 24)
private val SWITCH_ROW_MIN = 56.dp

// The IME joins the content insets, as on Home and Detail; a separate imePadding would count the navigation bar twice.
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val update: ((SettingsState) -> SettingsState) -> Unit = { container.settings.update(it) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { BackToHome(onBack) } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
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
            Text("Largest network to sweep. Bigger networks are cut down to your own /24 unless widened here.", style = NetProbeText.data, color = NetProbeColors.textMuted)
            PinnedSegmented(PREFIX_OPTIONS.map { "/$it" }, PREFIX_OPTIONS.indexOf(s.maxPrefix), { i -> update { it.copy(maxPrefix = PREFIX_OPTIONS[i]) } },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())

            Section("Extended discovery")
            val network by container.currentNetwork.collectAsStateWithLifecycle()
            val revision by container.settings.manual.revision.collectAsStateWithLifecycle()
            val manual = remember(network?.id, revision) { container.settings.manual.forNetwork(network?.id) }
            val local = remember(s.maxPrefix) { container.networkEnvMonitor.current()?.let { SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, s.maxPrefix).subnet } }
            ExtendedSection(s, update, networkName = network?.displayName, manual = manual, local = local) { list ->
                network?.id?.let { container.settings.manual.set(it, list) }
            }

            Section("Permissions")
            LocationPermissionRow()

            Section("Vulnerabilities")
            var key by remember { mutableStateOf(s.nvdApiKey) }
            OutlinedTextField(key, { key = it; update { st -> st.copy(nvdApiKey = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("NVD API key (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
            TextButton(onClick = {
                scope.launch {
                    val msg = try { container.vulnCache.clear(); "Vulnerability cache cleared" } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "Could not clear cache: ${e.message}" }
                    snackbar.showSnackbar(msg)
                }
            }, modifier = Modifier.heightIn(min = Dimens.touch)) { Text("Clear vulnerability cache", style = NetProbeText.action, color = NetProbeColors.signal) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Caption in textMuted over a 1 dp surface rule, the Home group-header pattern; never teal (spec section 6). */
@Composable
internal fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(title, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
internal fun npSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = NetProbeColors.signal, checkedThumbColor = NetProbeColors.background, checkedBorderColor = NetProbeColors.signal,
    uncheckedTrackColor = NetProbeColors.surface, uncheckedThumbColor = NetProbeColors.textMuted, uncheckedBorderColor = NetProbeColors.border,
)

/** Label, optional supporting text, a switch; the whole row toggles; 56 dp minimum (spec section 6). */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SWITCH_ROW_MIN).alpha(if (enabled) 1f else DISABLED_ALPHA)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            supporting?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = npSwitchColors())
    }
}

/** Moves locally while dragging and saves once on release, so preferences are not rewritten on every drag frame. */
@Composable
internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
    Column(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Row(Modifier.padding(top = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            Text("$snapped$unit", style = NetProbeText.data, color = NetProbeColors.signal)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1, enabled = enabled,
            onValueChangeFinished = { if (snapped != value) onCommit(snapped) },
            colors = SliderDefaults.colors(thumbColor = NetProbeColors.signal, activeTrackColor = NetProbeColors.signal, inactiveTrackColor = NetProbeColors.surface,
                activeTickColor = NetProbeColors.background, inactiveTickColor = NetProbeColors.border))
    }
}

@Composable
private fun LocationPermissionRow() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Text("Android only reveals the Wi-Fi name with location permission. It is shown for display only: NetProbe tells networks apart by their gateway.",
        style = NetProbeText.data, color = NetProbeColors.textMuted)
    if (granted) Text("Location permission granted", style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
    else TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.heightIn(min = Dimens.touch)) {
        Text("Allow location for Wi-Fi name", style = NetProbeText.action, color = NetProbeColors.signal)
    }
}
