package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.ScanPill
import com.northwalessolutions.netprobe.ui.home.label
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.delay

private const val NOTE_DEBOUNCE_MS = 400L
private const val NOTE_MIN_LINES = 2
private const val NOTE_MAX_LINES = 4
private const val NOTE_COLLAPSED_LINES = 2
/** One line of progress digits, measured once to reserve the progress line. */
private const val PROGRESS_PROBE = "0 of 0, found 0"
private val PORT_COLUMN = 52.dp
private val BOX_RADIUS = 8.dp
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val STATUSES = listOf(HostStatus.UNMARKED, HostStatus.INTERESTING, HostStatus.INVESTIGATED, HostStatus.IGNORED)
private val PROFILES = listOf<ScanProfile>(ScanProfile.Quick, ScanProfile.Full, ScanProfile.Custom(emptyList()))

/** Title; a tap opens the rename dialog (Save, Reset, Cancel), unchanged rules. */
@Composable
fun EditableName(name: String, customName: String?, onRename: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { editing = true }
        .semantics { contentDescription = "Rename, $name" }, contentAlignment = Alignment.CenterStart) {
        Text(name, style = NetProbeText.title, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (editing) {
        var text by remember { mutableStateOf(customName ?: name) }
        AlertDialog(
            onDismissRequest = { editing = false }, containerColor = NetProbeColors.surface,
            title = { Text("Rename device", style = NetProbeText.rowName, color = NetProbeColors.text) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS)) },
            confirmButton = {
                TextButton({
                    // Blank, or the discovered name left unchanged, is not a custom name: store null so "Reset" only appears for real renames.
                    val t = text.trim()
                    onRename(if (t.isEmpty() || (customName == null && t == name)) null else t); editing = false
                }) { Text("Save", color = NetProbeColors.signal) }
            },
            dismissButton = {
                Row {
                    if (customName != null) TextButton({ onRename(null); editing = false }) { Text("Reset", color = NetProbeColors.signal) }
                    TextButton({ editing = false }) { Text("Cancel", color = NetProbeColors.textSecondary) }
                }
            },
        )
    }
}

@Composable
fun DetailLinks(onWhy: () -> Unit, onVulns: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) { LinkText("Why this identity", onWhy); LinkText("Look up vulnerabilities", onVulns) }
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, style = NetProbeText.secondary.copy(fontWeight = FontWeight.W500), color = NetProbeColors.signal)
    }
}

/** Four equal segments; exactly one active, in mark yellow (spec 5.1 item 6). */
@Composable
fun StatusSegments(current: HostStatus, onSelect: (HostStatus) -> Unit) {
    PinnedSegmented(STATUSES.map { it.label() }, STATUSES.indexOf(current), { onSelect(STATUSES[it]) },
        activeFill = NetProbeColors.mark, activeText = NetProbeColors.background, activeWeight = FontWeight.W500,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).testTag("statusSegments"))
}

/** Saves 400 ms after the last keystroke, and on leaving the screen if an edit is still pending (MVP behaviour). */
@Composable
fun NoteField(hostId: Long, note: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    // A TextFieldValue so the cursor starts after the existing text: tapping the note to add to it appends, never prefixes.
    var value by rememberSaveable(hostId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(note, TextRange(note.length))) }
    val text = value.text
    val latestText by rememberUpdatedState(text); val latestNote by rememberUpdatedState(note); val save by rememberUpdatedState(onSave)
    LaunchedEffect(text) { if (text != note) { delay(NOTE_DEBOUNCE_MS); onSave(text) } }
    DisposableEffect(hostId) { onDispose { if (latestText != latestNote) save(latestText) } }
    // Default IME action: Enter inserts a line break (multi-line note, spec 5.1 item 7). Focus loss collapses the note (NoteLine);
    // a tap on the list clears focus (DetailContent).
    OutlinedTextField(value, { value = it }, modifier.fillMaxWidth().testTag("note"), textStyle = NetProbeText.secondaryItalic,
        minLines = NOTE_MIN_LINES, maxLines = NOTE_MAX_LINES, colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS))
}

/** Collapsed: a two-line box ("Add a note" when empty). Tap swaps in the field; losing focus collapses it (spec 5.1 item 7). */
@Composable
fun NoteLine(hostId: Long, note: String, onSave: (String) -> Unit, startExpanded: Boolean = false) {
    var expanded by rememberSaveable(hostId) { mutableStateOf(startExpanded) }
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 4.dp)) {
        if (expanded) {
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            NoteField(hostId, note, onSave, Modifier.focusRequester(focus).onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) expanded = false
            })
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(BOX_RADIUS))
                .border(1.dp, NetProbeColors.border, RoundedCornerShape(BOX_RADIUS)).clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("note"), contentAlignment = Alignment.CenterStart) {
                if (note.isBlank()) Text("Add a note", style = NetProbeText.secondaryItalic, color = NetProbeColors.textMuted)
                else Text(note, style = NetProbeText.secondaryItalic, color = NetProbeColors.textSecondary, maxLines = NOTE_COLLAPSED_LINES, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Profile control, Scan pill, Custom ports field, progress text and the 2 dp progress line, all pinned (spec 5.1 items 8 and 9). */
@Composable
fun ScanBar(profile: ScanProfile, customText: String, customError: String?, scanning: Boolean, progress: ScanProgress?, enabled: Boolean,
            onProfile: (ScanProfile) -> Unit, onCustom: (String) -> Unit, onScan: () -> Unit) {
    // Reserved from the data style's line height, so the bar never jumps when a scan starts (about 21 dp at 1.3). The sp line height
    // alone came out 1 px short of the laid-out text on the Pixel (pixel rounding), so the measured line wins when it is taller.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val progressHeight = remember(density, measurer) {
        with(density) { maxOf(NetProbeText.data.lineHeight.toDp(), measurer.measure(PROGRESS_PROBE, NetProbeText.data).size.height.toDp()) }
    }
    Column(Modifier.fillMaxWidth().testTag("detailScanBar")) {
        Row(Modifier.padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            PinnedSegmented(PROFILES.map { it.label }, PROFILES.indexOfFirst { it.label == profile.label }, { onProfile(PROFILES[it]) },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, enabled = !scanning, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            ScanPill(scanning, enabled = enabled && !(profile is ScanProfile.Custom && customError != null), onClick = onScan)
        }
        if (profile is ScanProfile.Custom) {
            OutlinedTextField(customText, onCustom, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), label = { Text("Ports") },
                placeholder = { Text("22,80,8000-8100") }, singleLine = true, enabled = !scanning, isError = customError != null,
                supportingText = customError?.let { e -> { Text(e, color = NetProbeColors.error) } }, textStyle = NetProbeText.data,
                colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).heightIn(min = progressHeight), contentAlignment = Alignment.CenterEnd) {
            if (scanning) Text(detailProgress(progress), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (scanning) LinearProgressIndicator(progress = { progress?.let { p -> if (p.total == 0) 0f else p.done / p.total.toFloat() } ?: 0f },
                modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal, trackColor = NetProbeColors.surface,
                gapSize = 0.dp, drawStopIndicator = {})   // a plain 2 dp line as drawn: no M3 gap or end dot
        }
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
    }
}

/** Port row: number, service and version, one action word; long-press opens the sheet (spec 5.2). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortRowItem(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onLongPress: () -> Unit) {
    val word = actionWord(schemeOf(p))
    val run = { onAction(primaryAction(ip, p)) }
    val copy = word == "Copy"
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")
        .padding(horizontal = Dimens.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${p.port}", style = NetProbeText.port, color = NetProbeColors.text, modifier = Modifier.width(PORT_COLUMN))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.service.name, style = NetProbeText.secondary, color = NetProbeColors.text)
            versionLine(p.service)?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(10.dp))
        // The word is its own target (not merged into the row) and long-presses exactly like the row body: a long press never runs the action.
        Box(Modifier.sizeIn(minWidth = Dimens.touch, minHeight = Dimens.touch)
            .combinedClickable(role = Role.Button, onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")
            .semantics { contentDescription = actionDescription(ip, p) }.testTag("portAction"), contentAlignment = Alignment.CenterEnd) {
            Text(word, style = if (copy) NetProbeText.action.copy(fontWeight = FontWeight.W400) else NetProbeText.action,
                color = if (copy) NetProbeColors.textMuted else NetProbeColors.signal, maxLines = 1)
        }
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortSheet(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onVulns: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp)) {
            Text("$ip:${p.port} ${p.service.name}", style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.padding(bottom = 8.dp))
            sheetActions(p).forEach { (label, a) -> SheetRow(label, if (a is DeviceAction.OpenWith) NetProbeColors.signal else NetProbeColors.text) { onAction(a); onClose() } }
            SheetRow("Look up vulnerabilities for this service", NetProbeColors.signal) { onVulns(); onClose() }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, style = NetProbeText.secondary, color = color)
    }
}

/** "Why this identity" is always a sheet, so the pinned block has no variable-height part (spec 5.1 item 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasonsSheet(h: HostEntity, onClose: () -> Unit) {
    val found = listOfNotNull(h.mdnsName?.let { "$it (mDNS)" }, h.netbiosName?.let { "$it (NetBIOS)" }, h.upnpName?.let { "$it (UPnP)" }, h.reverseDnsName?.let { "$it (DNS)" })
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Why this identity", style = NetProbeText.rowName, color = NetProbeColors.text)
            h.identReasons.forEach { Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) }
            if (found.isNotEmpty()) Text("Found as: ${found.joinToString(", ")}", style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
    }
}

private val SOFTWARE_CHIP_RADIUS = 4.dp

/** One neutral chip per software name in a single horizontally scrolling row; the mark-chip shape, not a status (spec 3.6). */
@Composable
fun SoftwareChips(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp).testTag("softwareChips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Text(label, style = NetProbeText.caption, color = NetProbeColors.textSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(SOFTWARE_CHIP_RADIUS)).background(NetProbeColors.surface)
                    .padding(vertical = 2.dp, horizontal = 6.dp).semantics { contentDescription = label })
        }
    }
}
