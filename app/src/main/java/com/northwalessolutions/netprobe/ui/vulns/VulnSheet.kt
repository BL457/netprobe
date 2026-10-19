package com.northwalessolutions.netprobe.ui.vulns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.vulns.Vuln
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import com.northwalessolutions.netprobe.ui.detail.VulnSheetState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SEVERITIES = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")

private fun formatTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VulnSheet(state: VulnSheetState, onClose: () -> Unit, onRerun: (String) -> Unit, onOpen: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vulnerabilities", style = MaterialTheme.typography.titleLarge)
            when (state) {
                is VulnSheetState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Searching NVD for ${state.query}")
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
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Query") })
        Spacer(Modifier.width(8.dp))
        Button({ onRerun(text) }, enabled = text.isNotBlank()) { Text("Search again") }
    }
    Text("Keyword matches can be noisy. Edit the query to narrow it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (val r = state.result) {
        is VulnResult.Ok -> OkResult(r, state.query, onOpen)
        is VulnResult.Failed -> {
            Text(r.reason, color = MaterialTheme.colorScheme.error)
            r.cached?.let { c ->
                Text("Showing cached results from ${formatTime(c.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                OkResult(c, state.query, onOpen)
            }
        }
    }
}

@Composable
private fun OkResult(r: VulnResult.Ok, query: String, onOpen: (String) -> Unit) {
    if (r.vulns.isEmpty()) { Text("No CVEs found for $query", modifier = Modifier.padding(vertical = 16.dp)); return }
    val counts = SEVERITIES.map { sev -> sev to r.vulns.count { it.severity.uppercase(Locale.ROOT) == sev } }
    Text(counts.joinToString("   ") { (sev, n) -> "$sev $n" }, style = MaterialTheme.typography.labelLarge)
    val shown = if (r.total > r.vulns.size) "Showing ${r.vulns.size} of ${r.total}" else "${r.total} found"
    Text(if (r.fromCache) "$shown · cached ${formatTime(r.fetchedAt)}" else shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.fillMaxWidth()) { items(r.vulns) { v -> VulnItem(v, onOpen) } }
}

@Composable
private fun VulnItem(v: Vuln, onOpen: (String) -> Unit) {
    val score = listOfNotNull(v.cvss?.let { String.format(Locale.ROOT, "%.1f", it) }, v.severity.takeIf { it.isNotBlank() }).joinToString(" ")
    ListItem(
        modifier = Modifier.clickable { onOpen(v.url) },
        headlineContent = { Text(v.cveId) },
        supportingContent = { Text(v.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { if (score.isNotEmpty()) AssistChip(onClick = { onOpen(v.url) }, label = { Text(score) }) },
    )
}
