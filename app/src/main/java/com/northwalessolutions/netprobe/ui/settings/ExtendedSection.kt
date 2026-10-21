package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlin.math.ceil

/** Settings copy owned by the extended-discovery spec 8.3, renamed by the 3C spec 2.3 and 7.2 ("Aggressive scan", "Smart discovery"). */
object ExtendedCopy {
    const val TITLE = "Aggressive scan"
    const val SUPPORT = "Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach."
    const val MAX_LABEL = "Maximum extra ranges"
    const val PROBE_LABEL = "Smart discovery"
    /** Worst case: 1,572 first-pass attempts plus at most 256 for the outward walk, about 13 s plus 12.8 s (3C spec 2.5). */
    const val PROBE_SUPPORT = "Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s."
    const val MANUAL_LINE = "Swept first on every scan, with Aggressive scan on or off"
    const val NO_NETWORK = "Connect to a network to edit its ranges"
    const val BEYOND_CAP = "Beyond cap, will be skipped"
    const val ADD = "Add range"
    const val SECONDS_PER_RANGE = 16   // measured on the Pixel 7 Pro (Task 20 check 11): 13.7 to 16.2 s per /24, mean 15.5 s
    private const val SECONDS_PER_MINUTE = 60.0
    fun capLine(n: Int): String {
        val minutes = ceil(n * SECONDS_PER_RANGE / SECONDS_PER_MINUTE).toInt()
        val nets = if (n == 1) "1 extra /24 network" else "$n extra /24 networks"
        return "At most $nets (about ${if (minutes == 1) "1 minute" else "$minutes minutes"})"
    }
    fun manualTitle(networkName: String) = "Manual ranges for $networkName"
}

object ManualCap {
    /** An entry is beyond the cap when its /24 units, counted in list order, run past [cap] (manual entries come first under the cap). */
    fun beyondCap(entries: List<Subnet>, cap: Int): List<Boolean> {
        var used = 0
        return entries.map { used += CidrParser.units(it).size; used > cap }
    }
}

private const val NO_UNIT = ""

/**
 * The Aggressive scan section (3C spec 2.3). Only "Smart discovery" depends on the switch: the stepper caps manual ranges with
 * Aggressive scan off too, and the editor is enabled whenever a network is current, because manual ranges sweep on every scan (D8).
 */
@Composable
fun ExtendedSection(state: SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, networkName: String?,
                    manual: List<Subnet>, local: Subnet?, networkId: Long? = null, onManualChange: (List<Subnet>) -> Unit) {
    val on = state.extEnabled
    SwitchRow(ExtendedCopy.TITLE, on, supporting = ExtendedCopy.SUPPORT) { v -> update { it.copy(extEnabled = v) } }
    Stepper(ExtendedCopy.MAX_LABEL, state.extMax, AppSettings.EXT_MAX_MIN, AppSettings.EXT_MAX_MAX, ExtendedCopy.capLine(state.extMax), enabled = true,
        default = SettingsDefaults.DEFAULTS.extMax) { v -> update { it.copy(extMax = v) } }
    SwitchRow(ExtendedCopy.PROBE_LABEL, state.extGatewayProbe, supporting = ExtendedCopy.PROBE_SUPPORT, enabled = on) { v -> update { it.copy(extGatewayProbe = v) } }
    ManualEditor(networkName, networkId, manual, local, state.extMax, enabled = networkName != null, onManualChange)
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, line: String, enabled: Boolean, default: Int? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else DISABLED_ALPHA), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        if (default != null) DefaultMarker.text(value, default, NO_UNIT)?.let { text ->
            DefaultMarkerButton(text, SettingsCopy.resetDescription(label, default, NO_UNIT), "default:$label", enabled) { onChange(default) }
        }
        StepButton(Icons.Default.Remove, "Fewer ranges", enabled && value > min) { onChange(value - 1) }
        Text("$value", style = NetProbeText.data, color = NetProbeColors.signal, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
        StepButton(Icons.Default.Add, "More ranges", enabled && value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description, tint = if (enabled) NetProbeColors.signal else NetProbeColors.border) }
}

@Composable
private fun ManualEditor(networkName: String?, networkId: Long?, manual: List<Subnet>, local: Subnet?, cap: Int, enabled: Boolean, onChange: (List<Subnet>) -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Text(networkName?.let { ExtendedCopy.manualTitle(it) } ?: ExtendedCopy.NO_NETWORK, style = NetProbeText.secondary, color = NetProbeColors.text,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        if (networkName != null) Text(ExtendedCopy.MANUAL_LINE, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        val beyond = ManualCap.beyondCap(manual, cap)
        manual.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                Text(s.toString(), style = NetProbeText.data, color = NetProbeColors.text, modifier = Modifier.weight(1f).testTag("manualRange"))
                if (beyond[i]) Text(ExtendedCopy.BEYOND_CAP, style = NetProbeText.caption, color = NetProbeColors.mark, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = { onChange(manual - s) }, enabled = enabled) { Icon(Icons.Default.Close, "Remove $s", tint = NetProbeColors.textMuted) }
            }
        }
        var text by rememberSaveable(networkName) { mutableStateOf("") }
        var notes by remember(networkId) { mutableStateOf(emptyList<String>()) }   // one network's notes never show for the next (Task 17 M2)
        val result = if (text.isBlank()) null else CidrParser.parse(text, local)
        val error = result?.error
        val helper = (result?.entries?.mapNotNull { it.note }.orEmpty() + notes).joinToString("\n").ifEmpty { null }
        val messageInView = remember { BringIntoViewRequester() }
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // The scroll column does not follow supporting text on its own, so pull the message above the keyboard when it appears or the keyboard opens.
        LaunchedEffect(error ?: helper, imeUp) { if ((error ?: helper) != null) messageInView.bringIntoView() }
        OutlinedTextField(text, { text = it; notes = emptyList() }, Modifier.fillMaxWidth().testTag("addRange"), enabled = enabled,
            label = { Text(ExtendedCopy.ADD) }, placeholder = { Text("10.0.4.0/24") }, isError = error != null, textStyle = NetProbeText.data,
            supportingText = when {
                error != null -> { { Text(error, color = NetProbeColors.error, modifier = Modifier.bringIntoViewRequester(messageInView)) } }
                helper != null -> { { Text(helper, color = NetProbeColors.textMuted, modifier = Modifier.bringIntoViewRequester(messageInView)) } }
                else -> null
            },
            colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        val canAdd = enabled && result != null && error == null && result.entries.isNotEmpty()
        TextButton(onClick = {
            val (merged, dupNotes) = CidrParser.merge(manual, result?.subnets.orEmpty())
            onChange(merged); notes = dupNotes; text = ""
        }, enabled = canAdd, modifier = Modifier.heightIn(min = Dimens.touch).testTag("addRangeButton")) {
            Text(ExtendedCopy.ADD, style = NetProbeText.action, color = if (canAdd) NetProbeColors.signal else NetProbeColors.border)
        }
    }
}
