package com.northwalessolutions.netprobe.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

/** Test hooks: the edge colour of a row and the alpha a text is drawn with. Not announced by TalkBack. */
val EdgeColorKey = SemanticsPropertyKey<Color>("EdgeColor")
private var SemanticsPropertyReceiver.edgeColor by EdgeColorKey
/** Test hook (spec 1.17): a row's stagger delay in ms, or -1 when it does not animate. Home rows and Detail port rows. */
val ArrivalDelayKey = SemanticsPropertyKey<Int>("ArrivalDelay")
var SemanticsPropertyReceiver.arrivalDelay by ArrivalDelayKey
val TextAlphaKey = SemanticsPropertyKey<Float>("TextAlpha")
private var SemanticsPropertyReceiver.textAlpha by TextAlphaKey

/** Draws at alpha [a] and publishes the same value to tests, so a test reads what is drawn and a hard-coded constant cannot pass it (spec 10.2 test 6). */
fun Modifier.drawnAlpha(a: Float): Modifier = alpha(a).semantics { textAlpha = a }

private const val DNS_SHOWN = 2
private const val NOTICE_MAX_LINES = 4   // the notice is pinned: it never pushes the list off screen
private val CHIP_HEIGHT = 32.dp
private val SEGMENT_RADIUS = 8.dp
private val MARK_RADIUS = 4.dp
private val ICON_SMALL = 16.dp

fun HostStatus.label(): String = when (this) {
    HostStatus.UNMARKED -> "Unmarked"; HostStatus.INTERESTING -> "Interesting"; HostStatus.INVESTIGATED -> "Investigated"; HostStatus.IGNORED -> "Ignored"
}

/** Address, gateway and the first two DNS servers (MVP spec 2.1 item 7). Kept with its test; Home now shows [networkLine]. */
fun networkDetailLine(env: NetworkEnv): String {
    val dns = env.dnsServers.take(DNS_SHOWN).mapNotNull { it.hostAddress }.joinToString(", ")
    val base = "${env.interfaceIp.hostAddress}/${env.prefixLength}  gateway ${env.gateway?.hostAddress ?: "none"}"
    return if (dns.isEmpty()) base else "$base  dns $dns"
}

/** The SSID when Android reveals it, else "Wi-Fi" or "Ethernet" (unchanged name rule). */
fun networkTitle(env: NetworkEnv, networkName: String?): String {
    val transport = if (env.transport == Transport.WIFI) "Wi-Fi" else "Ethernet"
    return networkName?.takeIf { it == env.ssid } ?: transport
}

/** "10.0.3.0/24  gateway 10.0.3.1": one line, no DNS suffix (spec 4.1). */
fun networkLine(env: NetworkEnv): String =
    "${SubnetMath.of(env.interfaceIp, env.prefixLength)}  gateway ${env.gateway?.hostAddress ?: "none"}"

/** The only place the network name appears; the overflow menu sits at its right end (spec 4.1 item 1). */
@Composable
fun NetworkLine(env: NetworkEnv?, networkName: String?, onRequestLocation: () -> Unit, offlineText: String? = null, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (env != null) {
                if (env.ssid == null && env.transport == Transport.WIFI) {
                    Text("Wi-Fi (tap to show the name, needs location)", style = NetProbeText.rowName, color = NetProbeColors.signal, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onRequestLocation).testTag("wifiNameHint"))
                } else {
                    Text(networkTitle(env, networkName), style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(networkLine(env), style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (offlineText != null) {   // settled with no live network: say why the ring is grey (HERO owns its own wording)
                Text(offlineText, style = NetProbeText.rowName, color = NetProbeColors.textMuted, maxLines = 1)
            }
        }
        menu()
    }
}

@Composable
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}, onAbout: () -> Unit = {}) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("homeMenu")) { Icon(Icons.Default.MoreVert, "Menu", tint = NetProbeColors.textSecondary) }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(if (quickScanning) "Stop quick scan" else "Quick scan all") }, enabled = quickEnabled,
                onClick = { open = false; if (quickScanning) onStopQuick() else onQuick() })
            DropdownMenuItem(text = { Text(HomeCopy.FORGET_NETWORK) }, enabled = forgetEnabled, onClick = { open = false; onForget() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text(HomeCopy.ABOUT) }, modifier = Modifier.testTag("menuAbout"), onClick = { open = false; onAbout() })
        }
    }
}

@Composable
fun FilterRow(selected: StatusFilter, onSelect: (StatusFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("filterRow").horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusFilter.entries.forEach { f -> FilterPill(f.label, f == selected) { onSelect(f) } }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val spec: AnimationSpec<Color> = motionSpec(reduced, Motion.SELECT_MS)
    // Only alpha animates on the fill, so mid-fade pixels lie between the background and the selected surface (spec 1.11).
    val fill by animateColorAsState(if (selected) NetProbeColors.surface else NetProbeColors.surface.copy(alpha = 0f), spec, label = "chipFill")
    val outline by animateColorAsState(if (selected) NetProbeColors.surface else NetProbeColors.border, spec, label = "chipOutline")
    val text by animateColorAsState(if (selected) NetProbeColors.text else NetProbeColors.textSecondary, spec, label = "chipText")
    Box(Modifier.heightIn(min = Dimens.touch).selectable(selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = "Filter: $label" + if (selected) ", selected" else "" }.testTag("chip:$label"), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = CHIP_HEIGHT).clip(CircleShape).background(fill).border(1.dp, outline, CircleShape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.chip, color = text)
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).testTag("search"),
        placeholder = { Text(HomeCopy.SEARCH_PLACEHOLDER, style = NetProbeText.secondary) },
        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, textStyle = NetProbeText.secondary,
        shape = RoundedCornerShape(SEGMENT_RADIUS), colors = npFieldColors())
}

/** One notice line under the radar for every transient message (D17), with a 48 dp dismiss target; [onUndo] adds the Undo word (spec 5.3). */
@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter).testTag("notice"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = NOTICE_MAX_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onUndo != null) Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(onClick = onUndo)
            .semantics { contentDescription = HomeCopy.UNDO; role = Role.Button }.testTag("noticeUndo"), contentAlignment = Alignment.Center) {
            Text(HomeCopy.UNDO, style = NetProbeText.action, color = NetProbeColors.signal)
        }
        Box(Modifier.size(Dimens.touch).clickable(onClick = onDismiss).semantics { contentDescription = "Dismiss notice"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(ICON_SMALL))
        }
    }
}

data class Notice(val text: String, val undo: Boolean, val app: Boolean = false)

/**
 * The notice line expands and fades in over 200 ms and shrinks and fades out over 160 ms (spec 1.13); the last notice is latched so
 * the exit animates the old words, not a blank line. A late notice therefore slides the list down instead of jumping it.
 */
@Composable
fun NoticeArea(notice: Notice?, onDismiss: () -> Unit, onUndo: () -> Unit, onDismissUndo: () -> Unit, onDismissApp: () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    var last by remember { mutableStateOf(notice) }
    LaunchedEffect(notice) { if (notice != null) last = notice }
    val shown = notice ?: last
    AnimatedVisibility(visible = notice != null, modifier = Modifier.testTag("noticeArea"),
        enter = if (reduced) EnterTransition.None else expandVertically(tween(Motion.NOTICE_IN_MS)) + fadeIn(tween(Motion.NOTICE_IN_MS)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(Motion.NOTICE_OUT_MS)) + fadeOut(tween(Motion.NOTICE_OUT_MS))) {
        shown?.let { n -> when { n.undo -> NoticeLine(n.text, onDismissUndo, onUndo = onUndo); n.app -> NoticeLine(n.text, onDismissApp); else -> NoticeLine(n.text, onDismiss) } }
    }
}

/** Sticky group header: caption on opaque background with a 1 dp surface rule above (spec 4.2). */
@Composable
fun GroupHeader(text: String) {
    Column(Modifier.fillMaxWidth().background(NetProbeColors.background).testTag("groupHeader").semantics { heading() }) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(text, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = 10.dp, end = Dimens.gutter, bottom = 4.dp))
    }
}

@Composable
fun TypeEdge(color: Color, stale: Boolean) {
    Box(Modifier.width(Dimens.edgeWidth).height(Dimens.edgeHeight).clip(RoundedCornerShape(Dimens.edgeRadius))
        .background(color.copy(alpha = if (stale) STALE_EDGE_ALPHA else 1f)))
}

@Composable
fun MarkChip(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NetProbeText.caption, color = NetProbeColors.background,
        modifier = modifier.clip(RoundedCornerShape(MARK_RADIUS)).background(NetProbeColors.mark).padding(vertical = 1.dp, horizontal = 6.dp))
}

/** Row anatomy (spec 4.3): edge, name with mark chip, data line, open-port count. The whole row is the click target. */
@Composable
fun HostRowItem(r: HostRow, own: Boolean, ownModel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val edgeTarget = if (own) EdgeColors.phone else r.type.edgeColor(r.isGateway)
    val edge by animateColorAsState(edgeTarget, motionSpec(reduced, Motion.EDGE_COLOUR_MS), label = "edge")
    val description = RowText.description(r, if (own && r.identityLine == RowRules.OWN_LINE) ownModel else null)
    val nameAlpha = if (r.stale) STALE_NAME_ALPHA else 1f
    Row(modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClick = onClick).drawnAlpha(1f)   // the row itself is never dimmed: only edge and name are (spec 4.3)
        .semantics(mergeDescendants = true) { contentDescription = description; edgeColor = edgeTarget }.testTag("hostRow")
        .padding(start = Dimens.rowStart, end = Dimens.gutter, top = Dimens.rowVertical, bottom = Dimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically) {
        TypeEdge(edge, r.stale)
        Spacer(Modifier.width(Dimens.rowGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.displayName, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).drawnAlpha(nameAlpha).testTag("rowName"))
                if (r.status != HostStatus.UNMARKED) MarkChip(r.status.label(), Modifier.padding(start = 6.dp))
            }
            // Cross-fades when an identity improves (spec 1.14); Crossfade does not animate on first composition, so a scrolled-in row never fades.
            Crossfade(RowText.dataLine(r, if (own && r.identityLine == RowRules.OWN_LINE) ownModel else null),
                animationSpec = motionSpec(reduced, Motion.IDENTITY_FADE_MS), label = "rowData") { line ->
                Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.drawnAlpha(1f).testTag("rowData"))
            }
        }
        if (r.running) Text(ToolCopy.RUNNING_MARKER, style = NetProbeText.caption, color = NetProbeColors.signal, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).testTag("rowRunning"))
        else if (r.openPorts > 0) Text("${r.openPorts} open", style = NetProbeText.count, color = NetProbeColors.signal, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp))
    }
}

/** HERO list area: a group-header-style line, as drawn (spec 3.5). */
@Composable
fun HeroPlaceholder() {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(HomeCopy.EMPTY_HERO, style = NetProbeText.caption, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = 10.dp, end = Dimens.gutter))
    }
}

private val RANGE_LIST_MAX = 160.dp

/** The last extended run's ranges (extended spec 8.2): "3 other ranges checked", tap for one line per range. Scrolls inside its own cap, so a long list never pushes the devices away. */
@Composable
fun RangeSummary(ranges: List<RangeProgress>) {
    if (ranges.size < 2) return
    val headline = RangeSummaryText.checked(ranges) ?: return   // nothing swept (an early Stop): the notice line says what happened (M4)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("rangeSummary")) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClickLabel = if (open) "Hide ranges" else "Show ranges", role = Role.Button) { open = !open }
            .padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Text(headline, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textMuted)
        }
        if (open) Column(Modifier.heightIn(max = RANGE_LIST_MAX).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).testTag("rangeLines")) {
            RangeSummaryText.lines(ranges).forEach { Text(it, style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
