package com.northwalessolutions.netprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.ui.rootInsets
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.BuildConfig
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsReset
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TypeMarker
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.wordlists.rememberWordlistStore
import com.northwalessolutions.netprobe.ui.wordlists.rememberWordlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONC_MIN = 32; private const val CONC_MAX = 512; private const val CONC_STEP = 32
private const val TIMEOUT_MIN = 200; private const val TIMEOUT_MAX = 2000; private const val TIMEOUT_STEP = 100
private val PREFIX_OPTIONS = listOf(20, 22, 24)
private val SWITCH_ROW_MIN = 56.dp
private val TITLE_ROW_MIN = 56.dp
private val MARKER_PAD = 8.dp
private const val NO_UNIT = ""
private const val MS_UNIT = " ms"

/** Everything Settings shows; no AppContainer, so the Compose tests drive it with in-memory state (3C spec 8.2 item 4). */
data class SettingsUi(val state: SettingsState, val networkName: String? = null, val networkId: Long? = null, val manual: List<Subnet> = emptyList(),
                      val local: Subnet? = null, val importedWordlists: Int? = null,
                      /** The listener's status line (3C spec 3.1), already worded; null shows nothing. */
                      val listenStatus: String? = null,
                      /** The About row and its dialog (3D spec 5). */
                      val about: AboutInfo = AboutInfo("", 0, "", ""))

/** [current] reads the live settings at the Reset tap, so the Undo snapshot is never a stale recomposition value. */
data class SettingsActions(val update: ((SettingsState) -> SettingsState) -> Unit = {}, val current: () -> SettingsState = { SettingsState() },
                           val onManualChange: (List<Subnet>) -> Unit = {}, val clearVulnCache: suspend () -> String = { "" }, val onOpenWordlists: () -> Unit = {})

// The bar inset and the IME join the content insets (3D spec 1.4); a separate imePadding would count the navigation bar twice.
@Composable
fun SettingsScreen(container: AppContainer, onOpenWordlists: () -> Unit = {}) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val network by container.currentNetwork.collectAsStateWithLifecycle()
    val revision by container.settings.manual.revision.collectAsStateWithLifecycle()
    val manual = remember(network?.id, revision) { container.settings.manual.forNetwork(network?.id) }
    val local = remember(network?.id, s.maxPrefix, s.extEnabled) {   // re-read on a network switch (Task 17 M2) and when the home range changes (3C spec 2.1)
        container.networkEnvMonitor.current()?.let { HomeRange.of(it.interfaceIp, it.prefixLength, s.maxPrefix, s.extEnabled).subnet } }
    val wordlists by rememberWordlists(container)
    val wordlistsLoaded = rememberWordlistStore(container).value != null
    val listenStatus by container.listen.status.collectAsStateWithLifecycle()   // 3C spec 3.1
    SettingsContent(
        SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null,
            ListenCopy.status(listenStatus), AboutInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, BuildConfig.BUILD_TIME)),
        SettingsActions(update = { container.settings.update(it) }, current = { container.settings.state.value },
            onManualChange = { list -> network?.id?.let { container.settings.manual.set(it, list) } },
            clearVulnCache = {
                try { container.vulnCache.clear(); "Vulnerability cache cleared" } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "Could not clear cache: ${e.message}" }
            },
            onOpenWordlists = onOpenWordlists))
}

/** Settings layout (Radar spec 6, 3C spec 1 and 2.3). PINNED: the title row and the overflow menu sit in the topBar, never in the scroll. */
@Composable
fun SettingsContent(ui: SettingsUi, actions: SettingsActions) {
    val s = ui.state
    val update = actions.update
    val d = SettingsDefaults.DEFAULTS
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onReset: () -> Unit = {
        scope.launch {
            SettingsReset.run(actions.current, update) {
                snackbar.showSnackbar(SettingsCopy.RESET_DONE, actionLabel = HomeCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
            }
        }
    }
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
                Text(SettingsCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text,
                    modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
                SettingsMenu(resetEnabled = SettingsDefaults.differs(s), onReset = onReset)
            }
        }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", s.mdns) { v -> update { it.copy(mdns = v) } }
            SwitchRow("SSDP / UPnP", s.ssdp) { v -> update { it.copy(ssdp = v) } }
            SwitchRow("NetBIOS names", s.netbios) { v -> update { it.copy(netbios = v) } }
            SwitchRow("Reverse DNS", s.reverseDns) { v -> update { it.copy(reverseDns = v) } }
            SwitchRow("SNMP", s.snmp) { v -> update { it.copy(snmp = v) } }
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }
            // 3C spec 3.1: directly after HTTP page titles; the status line only while the switch is on.
            SwitchRow(ListenCopy.LABEL, s.listenMulticast, ListenCopy.SUPPORT) { v -> update { it.copy(listenMulticast = v) } }
            if (s.listenMulticast) ui.listenStatus?.let {
                Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(bottom = 6.dp).testTag("listenStatus"))
            }

            // Second, directly under Discovery (3C spec D6, 2.3): discoverable without a Home toggle.
            Section(ExtendedCopy.TITLE)
            ExtendedSection(s, update, networkName = ui.networkName, manual = ui.manual, local = ui.local, networkId = ui.networkId, onManualChange = actions.onManualChange)

            Section("Speed")
            StepSlider("Sweep concurrency", s.sweepConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, NO_UNIT, default = d.sweepConcurrency) { v -> update { it.copy(sweepConcurrency = v) } }
            StepSlider("Sweep connect timeout", s.connectTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, MS_UNIT, default = d.connectTimeoutMs) { v -> update { it.copy(connectTimeoutMs = v) } }
            StepSlider("Port scan concurrency", s.scanConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, NO_UNIT, default = d.scanConcurrency) { v -> update { it.copy(scanConcurrency = v) } }
            StepSlider("Port scan timeout", s.scanTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, MS_UNIT, default = d.scanTimeoutMs) { v -> update { it.copy(scanTimeoutMs = v) } }

            Section(SettingsCopy.DISPLAY_SECTION)
            Text(SettingsCopy.TYPE_MARKER_LABEL, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            PinnedSegmented(listOf(SettingsCopy.MARKER_STRIPE, SettingsCopy.MARKER_ICON), TypeMarker.entries.indexOf(s.typeMarker),
                { i -> update { it.copy(typeMarker = TypeMarker.entries[i]) } },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth().testTag("typeMarker"))

            Section("Large networks")
            Text(SettingsCopy.LARGE_NETWORKS_CAPTION, style = NetProbeText.data, color = NetProbeColors.textMuted)   // plan review G6
            PinnedSegmented(PREFIX_OPTIONS.map { "/$it" }, PREFIX_OPTIONS.indexOf(s.maxPrefix), { i -> update { it.copy(maxPrefix = PREFIX_OPTIONS[i]) } },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())

            Section("Permissions")
            LocationPermissionRow()

            Section("Vulnerabilities")
            var key by remember { mutableStateOf(s.nvdApiKey) }
            OutlinedTextField(key, { key = it; update { st -> st.copy(nvdApiKey = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("NVD API key (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
            TextButton(onClick = { scope.launch { snackbar.showSnackbar(actions.clearVulnCache()) } }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text("Clear vulnerability cache", style = NetProbeText.action, color = NetProbeColors.signal)
            }
            ToolsSection(imported = ui.importedWordlists, onOpenWordlists = actions.onOpenWordlists)

            Section(SettingsCopy.ABOUT_SECTION)
            AboutRow(ui.about)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The last Settings row (3D spec 5): "About" with the version line and a chevron; it opens the existing About dialog. */
@Composable
private fun AboutRow(about: AboutInfo) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = SWITCH_ROW_MIN).clickable(role = Role.Button) { open = true }.testTag("aboutRow"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(SettingsCopy.ABOUT_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(HomeCopy.version(about.versionName, about.versionCode), style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = NetProbeColors.textMuted)
    }
    if (open) AboutDialog(about) { open = false }
}

/** The Settings overflow (3C spec 1.2): one item, enabled only while some covered value differs from its default. */
@Composable
internal fun SettingsMenu(resetEnabled: Boolean, onReset: () -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.size(Dimens.touch).testTag("settingsMenu")) {
            Icon(Icons.Default.MoreVert, SettingsCopy.MENU, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(SettingsCopy.RESET) }, enabled = resetEnabled, onClick = { open = false; onReset() }, modifier = Modifier.testTag("resetDefaults"))
        }
    }
}

/** "Default 128" in caption / textSecondary inside a 48 dp target (3C spec 1.3); a tap restores that one control. */
@Composable
internal fun DefaultMarkerButton(text: String, description: String, tag: String, enabled: Boolean = true, onReset: () -> Unit) {
    Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(enabled = enabled, role = Role.Button, onClick = onReset)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, style = NetProbeText.caption, color = NetProbeColors.textSecondary, modifier = Modifier.padding(horizontal = MARKER_PAD))
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

/**
 * Moves locally while dragging and saves once on release, so preferences are not rewritten on every drag frame. With [default] set,
 * a "Default N" marker shows before the value while the saved value differs (3C spec 1.3).
 */
@Composable
internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, default: Int? = null, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
    Column(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            if (default != null) DefaultMarker.text(value, default, unit)?.let { text ->
                DefaultMarkerButton(text, SettingsCopy.resetDescription(label, default, unit), "default:$label", enabled) { onCommit(default) }
            }
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
