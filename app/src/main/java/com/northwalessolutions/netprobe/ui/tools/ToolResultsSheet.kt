package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.LiveLine
import com.northwalessolutions.netprobe.ui.theme.*

private val RESULTS_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private const val RESULTS_MAX_FRACTION = 0.9f
private const val VALUE_COLLAPSED_LINES = 3
private val ROW_VERTICAL = 10.dp

/**
 * The results of one run (spec 8.5): a fixed header with the run switcher, the findings in a LazyColumn, and a fixed footer with
 * Share (disabled while the run is active). The view model holds the run id and closes the sheet when that run disappears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolResultsSheet(state: ToolResultsState, onOlder: () -> Unit, onNewer: () -> Unit, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit,
                     onShare: () -> Unit, onDismiss: () -> Unit, liveStatus: (() -> String)? = null) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * RESULTS_MAX_FRACTION).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = RESULTS_SHAPE,
        contentWindowInsets = { WindowInsets.navigationBars }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.title, style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onNewer, enabled = state.index > 1, modifier = Modifier.testTag("newerRun").semantics { contentDescription = ToolCopy.NEWER_RUN }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = if (state.index > 1) NetProbeColors.textSecondary else NetProbeColors.border)
                    }
                    Text(ToolCopy.runOf(state.index, state.count), style = NetProbeText.data, color = NetProbeColors.textMuted)
                    IconButton(onClick = onOlder, enabled = state.index < state.count, modifier = Modifier.testTag("olderRun").semantics { contentDescription = ToolCopy.OLDER_RUN }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (state.index < state.count) NetProbeColors.textSecondary else NetProbeColors.border)
                    }
                }
                state.route?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("routeLine")) }   // 3C spec 4.5
                if (liveStatus != null) LiveLine(liveStatus, style = NetProbeText.data, color = NetProbeColors.textMuted)
                else Text(state.statusLine, style = NetProbeText.data, color = NetProbeColors.textMuted)
                val (fallback, summary) = ToolCopy.splitFallback(state.summary)
                fallback?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("fallbackLine")) }
                if (summary.isNotEmpty()) Text(summary, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider, modifier = Modifier.padding(top = 8.dp))
            if (state.findings.isEmpty()) {
                Text(if (state.active) ToolCopy.EMPTY_RUNNING else ToolCopy.NO_FINDINGS, style = NetProbeText.secondary, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(Dimens.gutter))
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).testTag("resultsList")) {
                    items(state.findings, key = { it.id }) { row -> FindingRowItem(row, onCopy, onOpen) }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onShare, enabled = !state.active, modifier = Modifier.heightIn(min = Dimens.touch).testTag("shareRun")) {
                    Text(ToolCopy.SHARE, style = NetProbeText.action, color = if (state.active) NetProbeColors.textMuted else NetProbeColors.signal)
                }
            }
        }
    }
}

/** One finding: its script kind first when it has one (an OTHER finding with extra.kind, ruling c), then the value and detail, with Open and Copy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FindingRowItem(row: FindingRow, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("finding:${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).combinedClickable(onLongClick = { onCopy(row) }, onLongClickLabel = ToolCopy.COPY, onClickLabel = ToolCopy.EXPAND, onClick = { expanded = !expanded })) {
            row.label?.let { Text(it, style = NetProbeText.caption, color = NetProbeColors.signal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("findingLabel:${row.id}")) }
            Text(row.value, style = NetProbeText.data, color = NetProbeColors.text, maxLines = if (expanded) Int.MAX_VALUE else VALUE_COLLAPSED_LINES, overflow = TextOverflow.Ellipsis)
            row.detail?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        row.url?.let { url ->
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onOpen(row) }
                .semantics { contentDescription = ToolCopy.openDescription(url) }.testTag("open:${row.id}"), contentAlignment = Alignment.Center) {
                Text(ToolCopy.OPEN_WITH, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onCopy(row) }
            .semantics { contentDescription = ToolCopy.copyDescription(row.value) }.testTag("copy:${row.id}"), contentAlignment = Alignment.Center) {
            Text(ToolCopy.COPY, style = NetProbeText.action.copy(fontWeight = FontWeight.W400), color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}
