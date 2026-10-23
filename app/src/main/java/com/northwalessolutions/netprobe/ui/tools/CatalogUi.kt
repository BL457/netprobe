package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.engine.script.CatalogFailure
import com.northwalessolutions.netprobe.engine.script.CatalogItem
import com.northwalessolutions.netprobe.engine.script.CatalogPlan
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.ui.theme.*

private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private const val SHEET_MAX_FRACTION = 0.9f

/** The catalog flow for whichever screen shows it: the working dialog, the error dialog and the review sheet, drawn from the container's controller. */
@Composable
fun CatalogHost(controller: CatalogController, onNotice: (String) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val notice by controller.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { notice?.let { onNotice(it); controller.consumeNotice() } }
    when (val s = state) {
        CatalogState.Closed -> Unit
        is CatalogState.Working -> CatalogWorkingDialog(s, controller::close)
        is CatalogState.Failed -> CatalogFailedDialog(s.reason, onRetry = controller::retry, onClose = controller::close)
        is CatalogState.Review -> CatalogReviewSheet(s, onAcknowledged = controller::setAcknowledged, onInstall = controller::install, onCancel = controller::close)
    }
}

@Composable
fun CatalogWorkingDialog(state: CatalogState.Working, onCancel: () -> Unit) {
    val line = when {
        state.installing -> CatalogCopy.INSTALLING
        state.total == 0 -> CatalogCopy.READING_INDEX
        else -> CatalogCopy.checking(state.done.coerceAtLeast(1).coerceAtMost(state.total), state.total)
    }
    AlertDialog(onDismissRequest = onCancel, containerColor = NetProbeColors.surface,
        title = { Text(CatalogCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Row(Modifier.testTag("catalogWorking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), color = NetProbeColors.signal, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(line, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("catalogWorkingLine"))
            }
        },
        confirmButton = {
            if (!state.installing) TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch).testTag("catalogWorkingCancel")) { Text(ImportCopy.CANCEL, color = NetProbeColors.signal) }
        })
}

@Composable
fun CatalogFailedDialog(reason: String, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, containerColor = NetProbeColors.surface,
        title = { Text(CatalogCopy.FAILED_TITLE, style = NetProbeText.title, color = NetProbeColors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("catalogError")) {
                reason.lines().forEach { Text(it, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Dimens.touch).testTag("catalogErrorClose")) { Text(ImportCopy.CLOSE, color = NetProbeColors.signal) } },
        dismissButton = { TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.touch).testTag("catalogRetry")) { Text(CatalogCopy.TRY_AGAIN, color = NetProbeColors.textSecondary) } })
}

/**
 * The review sheet (spec section 11): New, Updated and Failed lists, the count of unchanged tools, and Install / Cancel pinned below the
 * scroll (the pinned-controls rule). When an update widens a tool's network scope or newly lets it send broadcast packets, the extra yes
 * (G9) is pinned with the buttons and Install waits for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogReviewSheet(state: CatalogState.Review, onAcknowledged: (Boolean) -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    val plan = state.plan
    val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NetProbeColors.surface,
        shape = SHEET_SHAPE, contentWindowInsets = { WindowInsets.navigationBars }) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("catalogReview")) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
                Text(CatalogCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.semantics { heading() })
                CatalogLists(plan)
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            if (plan.needsYes) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).heightIn(min = Dimens.touch)
                    .toggleable(value = state.acknowledged, role = Role.Checkbox, onValueChange = onAcknowledged).testTag("catalogAck"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.acknowledged, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = NetProbeColors.signal, checkmarkColor = NetProbeColors.background, uncheckedColor = NetProbeColors.border))
                    Spacer(Modifier.width(8.dp))
                    Text(CatalogCopy.ACK, style = NetProbeText.secondary, color = NetProbeColors.text)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.touch).testTag("catalogCancel")) { Text(ImportCopy.CANCEL, style = NetProbeText.action, color = NetProbeColors.textSecondary) }
                TextButton(onClick = onInstall, enabled = state.canInstall, modifier = Modifier.heightIn(min = Dimens.touch).testTag("catalogInstall")) {
                    Text(CatalogCopy.INSTALL, style = NetProbeText.action, color = if (state.canInstall) NetProbeColors.signal else NetProbeColors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun CatalogLists(plan: CatalogPlan) {
    if (plan.new.isNotEmpty()) {
        ListHeader(CatalogCopy.newHeader(plan.new.size), "catalogNewHeader")
        plan.new.forEach { NewRow(it) }
    }
    if (plan.updated.isNotEmpty()) {
        ListHeader(CatalogCopy.updatedHeader(plan.updated.size), "catalogUpdatedHeader")
        plan.updated.forEach { UpdatedRow(it) }
    }
    if (plan.failed.isNotEmpty()) {
        ListHeader(CatalogCopy.failedHeader(plan.failed.size), "catalogFailedHeader")
        plan.failed.forEachIndexed { i, f -> FailedRow(f, i) }
    }
    if (plan.installable == 0 && plan.failed.isEmpty()) Text(CatalogCopy.NOTHING_NEW, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.padding(top = 8.dp).testTag("catalogNothing"))
    if (plan.unchanged > 0) Text(CatalogCopy.upToDate(plan.unchanged), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = 12.dp).testTag("catalogUnchanged"))
}

@Composable
private fun ListHeader(text: String, tag: String) {
    Text(text, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() }.testTag(tag))
}

@Composable
private fun NewRow(item: CatalogItem) {
    val d = item.descriptor
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("catalogNew:${item.entry.id}")) {
        Text(CatalogCopy.nameVersion(d.name, d.version), style = NetProbeText.rowName, color = NetProbeColors.text)
        if (d.description.isNotEmpty()) Text(d.description, style = NetProbeText.data, color = NetProbeColors.textMuted)
        ScopeLine(d.network, warn = d.network == NetworkScope.ANY)
        if (d.broadcast) Text(ToolsUiCopy.BROADCAST_CAN, style = NetProbeText.data, color = NetProbeColors.signal, modifier = Modifier.testTag("catalogBroadcast:${item.entry.id}"))
    }
}

@Composable
private fun UpdatedRow(item: CatalogItem) {
    val d = item.descriptor
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("catalogUpdated:${item.entry.id}")) {
        Text(CatalogCopy.updatedLine(d.name, item.oldVersion.orEmpty(), d.version), style = NetProbeText.rowName, color = NetProbeColors.text)
        if (item.replacesLocal) Text(CatalogCopy.REPLACES_COPY, style = NetProbeText.data, color = NetProbeColors.textSecondary, modifier = Modifier.testTag("catalogReplacesCopy:${item.entry.id}"))
        if (item.widened) {
            Text(ImportCopy.widened(ImportCopy.scopeWord(item.scopeFrom ?: NetworkScope.TARGET), ImportCopy.scopeWord(d.network)), style = NetProbeText.data, color = NetProbeColors.mark,
                modifier = Modifier.testTag("catalogWidened:${item.entry.id}"))
        } else {
            ScopeLine(d.network, warn = false)
        }
        if (item.broadcastNew) Text(CatalogCopy.BROADCAST_NEW, style = NetProbeText.data, color = NetProbeColors.mark, modifier = Modifier.testTag("catalogBroadcastNew:${item.entry.id}"))
        else if (d.broadcast) Text(ToolsUiCopy.BROADCAST_CAN, style = NetProbeText.data, color = NetProbeColors.signal)
    }
}

@Composable
private fun ScopeLine(scope: NetworkScope, warn: Boolean) {
    Text(ToolsUiCopy.scope(scope), style = NetProbeText.data, color = if (warn) NetProbeColors.mark else NetProbeColors.textSecondary)
}

@Composable
private fun FailedRow(f: CatalogFailure, index: Int) {
    Text(CatalogCopy.failureLine(f.name, f.reason), style = NetProbeText.data, color = NetProbeColors.text, modifier = Modifier.padding(vertical = 4.dp).testTag("catalogFailed:$index"))
}
