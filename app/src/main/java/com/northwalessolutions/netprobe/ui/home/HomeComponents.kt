package com.northwalessolutions.netprobe.ui.home

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

/** Test hooks: the edge colour of a row and the alpha a text is drawn with. Not announced by TalkBack. */
val EdgeColorKey = SemanticsPropertyKey<Color>("EdgeColor")
private var SemanticsPropertyReceiver.edgeColor by EdgeColorKey
val TextAlphaKey = SemanticsPropertyKey<Float>("TextAlpha")
private var SemanticsPropertyReceiver.textAlpha by TextAlphaKey

/** Draws at alpha [a] and publishes the same value to tests, so a test reads what is drawn and a hard-coded constant cannot pass it (spec 10.2 test 6). */
fun Modifier.drawnAlpha(a: Float): Modifier = alpha(a).semantics { textAlpha = a }

private const val DNS_SHOWN = 2
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
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("homeMenu")) { Icon(Icons.Default.MoreVert, "Menu", tint = NetProbeColors.textSecondary) }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(if (quickScanning) "Stop quick scan" else "Quick scan all") }, enabled = quickEnabled,
                onClick = { open = false; if (quickScanning) onStopQuick() else onQuick() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
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
    Box(Modifier.heightIn(min = Dimens.touch).selectable(selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = "Filter: $label" + if (selected) ", selected" else "" }, contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = CHIP_HEIGHT).clip(CircleShape).background(if (selected) NetProbeColors.surface else Color.Transparent)
            .border(1.dp, if (selected) NetProbeColors.surface else NetProbeColors.border, CircleShape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.chip, color = if (selected) NetProbeColors.text else NetProbeColors.textSecondary)
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

/** One notice line under the radar for every transient message (D17), with a 48 dp dismiss target. */
@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter).testTag("notice"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
        Box(Modifier.size(Dimens.touch).clickable(onClick = onDismiss).semantics { contentDescription = "Dismiss notice"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(ICON_SMALL))
        }
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
    val edge = if (own) EdgeColors.phone else r.type.edgeColor(r.isGateway)
    val description = RowText.description(r, if (own) ownModel else null)
    val nameAlpha = if (r.stale) STALE_NAME_ALPHA else 1f
    Row(modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClick = onClick).drawnAlpha(1f)   // the row itself is never dimmed: only edge and name are (spec 4.3)
        .semantics(mergeDescendants = true) { contentDescription = description; edgeColor = edge }.testTag("hostRow")
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
            Text(RowText.dataLine(r, if (own) ownModel else null), style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.drawnAlpha(1f).testTag("rowData"))
        }
        if (r.openPorts > 0) Text("${r.openPorts} open", style = NetProbeText.count, color = NetProbeColors.signal, textAlign = TextAlign.End,
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
    val others = ranges.size - 1
    if (others < 1) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("rangeSummary")) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(onClickLabel = if (open) "Hide ranges" else "Show ranges", role = Role.Button) { open = !open }
            .padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Text(RangeSummaryText.headline(others), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textMuted)
        }
        if (open) Column(Modifier.heightIn(max = RANGE_LIST_MAX).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).testTag("rangeLines")) {
            RangeSummaryText.lines(ranges).forEach { Text(it, style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
