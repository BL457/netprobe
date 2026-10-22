package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
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
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolRunBar
import com.northwalessolutions.netprobe.ui.LiveLine
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

/** Title; a tap opens Edit device (spec 4.3, replaces the rename dialog). */
@Composable
fun DetailName(name: String, onEdit: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onEdit)
        .semantics { contentDescription = DetailCopy.editDescription(name) }.testTag("detailName"), contentAlignment = Alignment.CenterStart) {
        Text(name, style = NetProbeText.title, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The identity line; a tap opens Edit device too (spec 4.3). */
@Composable
fun IdentityText(text: String, name: String, onEdit: () -> Unit) {
    Text(text, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(role = Role.Button, onClick = onEdit).semantics { contentDescription = DetailCopy.editDescription(name) }.testTag("identityLine"))
}

/** Overflow on the back row (spec 4.5): "Edit device" and, from Task 14, "Remove device"; both always enabled. */
@Composable
fun DetailMenu(onEdit: () -> Unit, onRemove: (() -> Unit)? = null, removeEnabled: Boolean = true, onIdentifyAll: (() -> Unit)? = null) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("detailMenu")) {
            Icon(Icons.Default.MoreVert, DetailCopy.MORE_OPTIONS, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(DetailCopy.EDIT_DEVICE) }, onClick = { open = false; onEdit() })
            onIdentifyAll?.let { all -> DropdownMenuItem(text = { Text(ToolCopy.IDENTIFY_ALL) }, onClick = { open = false; all() }, modifier = Modifier.testTag("identifyAll")) }
            onRemove?.let { remove -> DropdownMenuItem(text = { Text(DetailCopy.REMOVE_DEVICE) }, enabled = removeEnabled, onClick = { open = false; remove() }) }
        }
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

/** Fixed fill of the tool bar line under reduced motion (no indeterminate sweep). */
private const val STATIC_TOOL_FRACTION = 0.3f

/** Profile control, Scan pill, Custom ports field, progress text and the 2 dp progress line, all pinned (spec 5.1 items 8 and 9). */
@Composable
fun ScanBar(profile: ScanProfile, customText: String, customError: String?, scanning: Boolean, progress: ScanProgress?, enabled: Boolean, identifying: Int = 0, identified: Int = 0,
            onProfile: (ScanProfile) -> Unit, onCustom: (String) -> Unit, onScan: () -> Unit,
            toolBar: ToolRunBar? = null, onStopTool: () -> Unit = {}, liveToolBar: () -> ToolRunBar? = { toolBar },
            /** Why Scan is disabled (3D spec 3.3); shown in the progress line's reserved row, so the bar never changes height. */
            refusal: String? = null) {
    // Reserved from the data style's line height, so the bar never jumps when a scan starts (about 21 dp at 1.3). The sp line height
    // alone came out 1 px short of the laid-out text on the Pixel (pixel rounding), so the measured line wins when it is taller.
    val reduced = LocalReducedMotion.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val progressHeight = remember(density, measurer) {
        with(density) { maxOf(NetProbeText.data.lineHeight.toDp(), measurer.measure(PROGRESS_PROBE, NetProbeText.data).size.height.toDp()) }
    }
    // The last live bar of this run, held so the frame where the live bar has turned null but toolBar has not yet never redraws the
    // run-start snapshot (toolBar's progress and fraction are from the start of the run). Written in draw, so it is a plain holder.
    val lastLive = remember(toolBar?.label) { arrayOfNulls<ToolRunBar>(1) }
    val heldBar: () -> ToolRunBar? = { liveToolBar()?.also { lastLive[0] = it } ?: lastLive[0] }
    Column(Modifier.fillMaxWidth().testTag("detailScanBar")) {
        Row(Modifier.padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            PinnedSegmented(PROFILES.map { it.label }, PROFILES.indexOfFirst { it.label == profile.label }, { onProfile(PROFILES[it]) },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, enabled = !scanning && toolBar == null, modifier = Modifier.weight(1f).testTag("profileSegments"))
            Spacer(Modifier.width(12.dp))
            if (toolBar != null) ScanPill(scanning = true, enabled = true, onClick = onStopTool, stopDescription = ToolCopy.STOP_DESCRIPTION)
            else ScanPill(scanning, enabled = enabled && !(profile is ScanProfile.Custom && customError != null), onClick = onScan)
        }
        if (profile is ScanProfile.Custom) {
            OutlinedTextField(customText, onCustom, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), label = { Text("Ports") },
                placeholder = { Text("22,80,8000-8100") }, singleLine = true, enabled = !scanning, isError = customError != null,
                supportingText = customError?.let { e -> { Text(e, color = NetProbeColors.error) } }, textStyle = NetProbeText.data,
                colors = npFieldColors(), shape = RoundedCornerShape(BOX_RADIUS))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).heightIn(min = progressHeight), contentAlignment = Alignment.CenterEnd) {
            when {
                // The per-tick progress is read in LiveLine's draw only: a tick neither recomposes, relayouts nor changes the semantics
                // of the Device screen (3B QA F4). heldBar keeps the last live value for the frame where the run has ended.
                toolBar != null -> LiveLine({ heldBar()?.progress ?: toolBar.progress }, style = NetProbeText.data, color = NetProbeColors.textMuted,
                    lead = toolBar.label, leadModifier = Modifier.testTag("toolBarLabel"), textModifier = Modifier.testTag("toolBarProgress"))
                scanning -> Text(detailProgress(progress, identifying), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
                refusal != null -> Text(refusal, style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End, maxLines = 2,
                    modifier = Modifier.testTag("scanRefusal"))
            }
        }
        val target = detailFraction(progress, identified)
        val shown by animateFloatAsState(target, motionSpec(reduced, Motion.PROGRESS_MS, LinearEasing), label = "progress")
        val toolFraction = toolBar?.fraction
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            when {
                // Drawn, not an M3 indicator: its progress semantics read the lambda, so every tick changed the screen's semantics and
                // cost a whole-tree accessibility diff (3B QA F4). The live fraction is read in draw only (3A per-frame rule).
                toolFraction != null -> Box(Modifier.fillMaxSize().graphicsLayer {}.drawBehind {
                    drawRect(NetProbeColors.surface)
                    val f = (heldBar()?.fraction ?: toolFraction).coerceIn(0f, 1f)
                    val w = size.width * f
                    drawRect(NetProbeColors.signal, topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f, 0f), size = Size(w, size.height))
                }.testTag("toolBarLine"))
                toolBar != null && reduced -> LinearProgressIndicator(progress = { STATIC_TOOL_FRACTION }, modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal,
                    trackColor = NetProbeColors.surface, gapSize = 0.dp, drawStopIndicator = {})   // reduced motion: a fixed partial bar, no sweep
                toolBar != null -> LinearProgressIndicator(modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal, trackColor = NetProbeColors.surface, gapSize = 0.dp)
                scanning -> LinearProgressIndicator(progress = { shown },
                    modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal, trackColor = NetProbeColors.surface,
                    gapSize = 0.dp, drawStopIndicator = {})   // a plain 2 dp line as drawn: no M3 gap or end dot
            }
        }
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
    }
}

/** Port row: number, service and version, one action word; long-press opens the sheet (spec 5.2). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortRowItem(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier,
                /** The Identify action (R2); null hides it. [identifying] shows the running word instead and ignores taps; [identifyMuted] greys it while the host is busy. */
                onIdentify: (() -> Unit)? = null, identifying: Boolean = false, identifyMuted: Boolean = false) {
    val word = actionWord(schemeOf(p))
    val run = { onAction(primaryAction(ip, p)) }
    val copy = word == "Copy"
    Column(modifier) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")
            .padding(horizontal = Dimens.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.port}", style = NetProbeText.port, color = NetProbeColors.text, modifier = Modifier.width(PORT_COLUMN))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.service.name, style = NetProbeText.secondary, color = NetProbeColors.text)
                portSubLine(p.service)?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (onIdentify != null) {
                // Pinned in the row beside the open word, never a scroller of its own. A tap while another tool runs reaches the busy message.
                Box(Modifier.sizeIn(minWidth = Dimens.touch, minHeight = Dimens.touch)
                    .then(if (identifying) Modifier else Modifier.clickable(role = Role.Button, onClick = onIdentify))
                    .semantics { contentDescription = ToolCopy.identifyPortDescription(p.port) }.testTag("portIdentify"), contentAlignment = Alignment.CenterEnd) {
                    Text(if (identifying) ToolCopy.IDENTIFYING else ToolCopy.IDENTIFY_PORT, style = NetProbeText.action.copy(fontWeight = FontWeight.W400),
                        color = if (identifying || identifyMuted) NetProbeColors.textMuted else NetProbeColors.signal, maxLines = 1)
                }
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
}

private val TRY_AS_CHIP_HEIGHT = 32.dp

/** "Try as": a caption, then one scrolling row of chips, one per scheme with an installed handler (spec 2.4 item 4). */
@Composable
private fun TryAsRow(ip: String, port: Int, pairs: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(DetailCopy.TRY_AS, style = NetProbeText.caption, color = NetProbeColors.textMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pairs.forEach { (scheme, uri) ->
                Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button) { onPick(uri) }
                    .semantics { contentDescription = DetailCopy.tryAsDescription(ip, port, scheme) }.testTag("tryAs:$scheme"), contentAlignment = Alignment.Center) {
                    Box(Modifier.heightIn(min = TRY_AS_CHIP_HEIGHT).clip(CircleShape).border(1.dp, NetProbeColors.border, CircleShape).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text(scheme, style = NetProbeText.chip, color = NetProbeColors.textSecondary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortSheet(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onVulns: () -> Unit, onClose: () -> Unit,
              tools: List<ToolId> = emptyList(), toolsBusy: Boolean = false, onTool: (ToolId) -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp)) {
            Text("$ip:${p.port} ${p.service.name}", style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.padding(bottom = 8.dp))
            val (head, tail) = sheetSplit(p)
            head.forEach { (label, a) -> SheetRow(label, if (a is DeviceAction.OpenWith) NetProbeColors.signal else NetProbeColors.text) { onAction(a); onClose() } }
            if (p.tryAs.isNotEmpty()) TryAsRow(ip, p.port, p.tryAs) { uri -> onAction(DeviceAction.OpenWith(uri)); onClose() }
            tail.forEach { (label, a) -> SheetRow(label, NetProbeColors.text) { onAction(a); onClose() } }
            if (tools.isNotEmpty()) {
                Text(ToolCopy.RUN_TOOL, style = NetProbeText.caption, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 4.dp))
                tools.forEach { tool ->
                    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onTool(tool); onClose() }.testTag("sheetTool:${tool.key}"),
                        contentAlignment = Alignment.CenterStart) {
                        Text(ToolCopy.toolName(tool), style = NetProbeText.secondary, color = if (toolsBusy) NetProbeColors.textMuted else NetProbeColors.signal)
                    }
                }
            }
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
            setByYouLine(h)?.let { Text(it, style = NetProbeText.secondary, color = NetProbeColors.text) }
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
