package com.northwalessolutions.netprobe.ui.vulns

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.vulns.Vuln
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import com.northwalessolutions.netprobe.ui.detail.VulnSheetState
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SEVERITIES = listOf("CRITICAL" to "Critical", "HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low")
private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

private fun formatTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

/** Text plus count, never colour alone (spec 5.3). */
private fun severityColor(severity: String): Color = when (severity.uppercase(Locale.ROOT)) {
    "CRITICAL" -> NetProbeColors.error; "HIGH" -> NetProbeColors.severityHigh; "MEDIUM" -> NetProbeColors.mark; else -> NetProbeColors.textMuted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VulnSheet(state: VulnSheetState, onClose: () -> Unit, onRerun: (String) -> Unit, onOpen: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vulnerabilities", style = NetProbeText.rowName, color = NetProbeColors.text)
            when (state) {
                is VulnSheetState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = NetProbeColors.signal); Spacer(Modifier.width(12.dp))
                    Text("Searching NVD for ${state.query}", style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                }
                is VulnSheetState.Ready -> Ready(state, onRerun, onOpen)
            }
        }
    }
}

@Composable
private fun Ready(state: VulnSheetState.Ready, onRerun: (String) -> Unit, onOpen: (String) -> Unit) {
    var text by remember(state.query) { mutableStateOf(state.query) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Query") }, textStyle = NetProbeText.secondary,
            colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(8.dp))
        TextButton({ onRerun(text) }, enabled = text.isNotBlank()) { Text("Search again", style = NetProbeText.action, color = NetProbeColors.signal) }
    }
    when (val r = state.result) {
        is VulnResult.Ok -> OkResult(r, state.query, onOpen)
        is VulnResult.Failed -> {
            val cached = r.cached
            if (cached != null) {
                Text("${r.reason}, showing cached results from ${formatTime(cached.fetchedAt)}", style = NetProbeText.secondary, color = NetProbeColors.textMuted)
                OkResult(cached, state.query, onOpen)
            } else Text(r.reason, style = NetProbeText.secondary, color = NetProbeColors.textMuted)
        }
    }
}

@Composable
private fun OkResult(r: VulnResult.Ok, query: String, onOpen: (String) -> Unit) {
    if (r.vulns.isEmpty()) { Text("No CVEs found for $query", style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = 16.dp)); return }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SEVERITIES.forEach { (key, label) -> Text("$label ${r.vulns.count { it.severity.uppercase(Locale.ROOT) == key }}", style = NetProbeText.data, color = severityColor(key)) }
    }
    val shown = if (r.total > r.vulns.size) "Showing ${r.vulns.size} of ${r.total}" else "${r.total} found"
    Text(if (r.fromCache) "$shown, cached ${formatTime(r.fetchedAt)}" else shown, style = NetProbeText.data, color = NetProbeColors.textMuted)
    LazyColumn(Modifier.fillMaxWidth()) { items(r.vulns) { v -> VulnRow(v, onOpen) } }
}

@Composable
private fun VulnRow(v: Vuln, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable { onOpen(v.url) }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.cveId, style = NetProbeText.port, color = NetProbeColors.text)
            v.cvss?.let { score ->
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.ROOT, "%.1f", score), style = NetProbeText.caption, color = NetProbeColors.background,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(severityColor(v.severity)).padding(vertical = 1.dp, horizontal = 6.dp))
            }
        }
        Text(v.summary, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v.published, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}
