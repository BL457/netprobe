package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.ui.home.IdentityLine
import com.northwalessolutions.netprobe.ui.home.label
import kotlinx.coroutines.delay

private const val NOTE_DEBOUNCE_MS = 400L
private val COMPACT_BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

@Composable
fun EditableName(name: String, customName: String?, onRename: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.clickable { editing = true }, verticalAlignment = Alignment.CenterVertically) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.Edit, "Rename", Modifier.size(18.dp))
    }
    if (editing) {
        var text by remember { mutableStateOf(customName ?: name) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Rename device") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton({
                    // Blank, or the discovered name left unchanged, is not a custom name: store null so "Reset" only appears for real renames.
                    val t = text.trim()
                    onRename(if (t.isEmpty() || (customName == null && t == name)) null else t); editing = false
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (customName != null) TextButton({ onRename(null); editing = false }) { Text("Reset") }
                    TextButton({ editing = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
fun IdentityBlock(h: HostEntity, onLookupVulns: () -> Unit) {
    var why by remember { mutableStateOf(false) }
    Column {
        Text(IdentityLine.of(h), style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull(h.ip, h.mac, h.vendor).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val found = listOfNotNull(h.mdnsName?.let { "$it (mDNS)" }, h.netbiosName?.let { "$it (NetBIOS)" }, h.upnpName?.let { "$it (UPnP)" }, h.reverseDnsName?.let { "$it (DNS)" })
        if (found.isNotEmpty()) Text("Found as: ${found.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (h.identReasons.isNotEmpty()) TextButton({ why = !why }, contentPadding = COMPACT_BUTTON_PADDING) { Text(if (why) "Hide why" else "Why?") }
            TextButton(onLookupVulns, contentPadding = COMPACT_BUTTON_PADDING) { Text("Look up vulnerabilities") }
        }
        if (why) h.identReasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun StatusSelector(current: HostStatus, onSelect: (HostStatus) -> Unit) {
    val options = listOf(HostStatus.UNMARKED, HostStatus.INTERESTING, HostStatus.INVESTIGATED, HostStatus.IGNORED)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, st ->
            SegmentedButton(selected = st == current, onClick = { onSelect(st) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) {
                // Shrinks to fit instead of ellipsizing ("Investigated" clipped at the Pixel's 1.15 font scale).
                BasicText(st.label(), style = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current), maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize))
            }
        }
    }
}

/** Saves 400 ms after the last keystroke, and on leaving the screen if an edit is still pending. */
@Composable
fun NoteField(hostId: Long, note: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(hostId) { mutableStateOf(note) }
    val latestText by rememberUpdatedState(text); val latestNote by rememberUpdatedState(note); val save by rememberUpdatedState(onSave)
    LaunchedEffect(text) { if (text != note) { delay(NOTE_DEBOUNCE_MS); onSave(text) } }
    DisposableEffect(hostId) { onDispose { if (latestText != latestNote) save(latestText) } }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("note"), label = { Text("Note") }, minLines = 2, maxLines = 4)
}

@Composable
fun ScanBar(profile: ScanProfile, customText: String, customError: String?, scanning: Boolean, progress: ScanProgress?, enabled: Boolean,
            onProfile: (ScanProfile) -> Unit, onCustom: (String) -> Unit, onScan: () -> Unit) {
    val profiles = listOf<ScanProfile>(ScanProfile.Quick, ScanProfile.Full, ScanProfile.Custom(emptyList()))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                profiles.forEachIndexed { i, p ->
                    SegmentedButton(selected = p.label == profile.label, onClick = { onProfile(p) }, shape = SegmentedButtonDefaults.itemShape(i, profiles.size),
                        enabled = !scanning, icon = {}) { Text(p.label, maxLines = 1) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onScan, enabled = enabled || scanning, modifier = Modifier.testTag("detailScanButton")) { Text(if (scanning) "Stop" else "Scan") }
        }
        if (profile is ScanProfile.Custom) {
            OutlinedTextField(customText, onCustom, Modifier.fillMaxWidth(), label = { Text("Ports") }, placeholder = { Text("22,80,8000-8100") }, singleLine = true,
                enabled = !scanning, isError = customError != null, supportingText = customError?.let { e -> { Text(e) } })
        }
        if (scanning) {
            val p = progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { if (p == null || p.total == 0) 0f else p.done / p.total.toFloat() }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(if (p == null) "Starting..." else "${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun PortRowItem(p: PortRow, onAction: (DeviceAction) -> Unit, onVulns: () -> Unit) {
    val noHandler = p.actions.filterIsInstance<DeviceAction.OpenUri>().firstOrNull { !it.hasHandler }
    val overflow = p.actions.filter { it != p.primary && it != noHandler }
    val supporting = listOfNotNull(p.service.product, p.service.version, p.service.detail).joinToString(" ")
        .ifEmpty { if (p.service.confidence == Confidence.PORT_TABLE) "Guessed from port number" else "" }
    ListItem(
        headlineContent = { Text("${p.port}  ${p.service.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (supporting.isEmpty()) null else { { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    p.primary != null -> Button({ onAction(p.primary) }, contentPadding = COMPACT_BUTTON_PADDING) { Text(p.primary.label, maxLines = 1) }
                    noHandler != null -> OutlinedButton({}, enabled = false, contentPadding = COMPACT_BUTTON_PADDING) { Text("No app installed", maxLines = 1) }
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More actions for port ${p.port}") }
                    DropdownMenu(menu, { menu = false }) {
                        overflow.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; onAction(a) }) }
                        DropdownMenuItem(text = { Text("Look up vulnerabilities") }, onClick = { menu = false; onVulns() })
                    }
                }
            }
        },
    )
}
