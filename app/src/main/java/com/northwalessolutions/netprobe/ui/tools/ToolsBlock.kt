package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.*

private val ROW_MIN = 58.dp
private val NAME_GAP = 10.dp
private val CHIP_VISUAL = 32.dp
private val CHIP_GAP = 8.dp
private val CHIP_PAD = 12.dp
private val CHEVRON = 20.dp
private val BLOCK_VERTICAL = 10.dp

/**
 * The first item of the Device screen's port list (spec 8.1): collapsed, one row the height of a port row; expanded, one block per
 * applicable tool with its history line and its target chips. While the host is held the chips are dimmed and a tap is answered with
 * the busy snackbar by the view model. No Run or Stop control lives here: runs start from chips, and stop from the pinned bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsBlock(state: ToolsUiState, expanded: Boolean, busy: Boolean, onToggle: () -> Unit, onRunChip: (String, Int) -> Unit, onResults: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // The tag goes before clearAndSetSemantics: anything after it in the chain is cleared with the children (3B QA F6).
        Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(onClick = onToggle).testTag("toolsHeader")
            .clearAndSetSemantics { contentDescription = ToolCopy.rowDescription(state.available, expanded); role = Role.Button }
            .padding(start = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolCopy.TOOLS, style = NetProbeText.secondary, color = NetProbeColors.text)
                    Spacer(Modifier.width(NAME_GAP))
                    Text(ToolCopy.available(state.available), style = NetProbeText.data, color = NetProbeColors.textMuted)
                }
                Text(state.headerHistory, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(Dimens.touch), contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        if (expanded) state.blocks.forEach { block ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = BLOCK_VERTICAL)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(block.name, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
                    if (block.hasRun) {
                        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onResults(block.toolId) }
                            .semantics { contentDescription = ToolCopy.resultsDescription(block.name) }.testTag("toolResults:${block.toolId}"),
                            contentAlignment = Alignment.CenterEnd) {
                            Text(ToolCopy.RESULTS, style = NetProbeText.action, color = NetProbeColors.signal)
                        }
                    }
                }
                Text(block.history, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    block.chips.forEach { chip ->
                        Box(Modifier.heightIn(min = Dimens.touch).alpha(if (busy) DISABLED_ALPHA else 1f).clickable(role = Role.Button) { onRunChip(chip.toolId, chip.port) }
                            .semantics { contentDescription = chip.description }.testTag("toolChip:${chip.toolId}:${chip.port}"),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.heightIn(min = CHIP_VISUAL).clip(CircleShape).border(1.dp, NetProbeColors.border, CircleShape).padding(horizontal = CHIP_PAD),
                                contentAlignment = Alignment.Center) {
                                Text(chip.label, style = NetProbeText.data, color = NetProbeColors.textSecondary)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        }
    }
}
