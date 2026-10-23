package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.tools.MyToolRow
import com.northwalessolutions.netprobe.ui.tools.RemoveToolDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy

private val ROW_MIN = 56.dp
private val CHEVRON_SIZE = 20.dp

/** Settings > Tools (spec 8.7): one row that opens the Wordlists screen; tool defaults live in the options sheet. */
@Composable
internal fun ToolsSection(imported: Int?, onOpenWordlists: () -> Unit, onImportTool: () -> Unit = {}, myTools: List<MyToolRow> = emptyList(), onRemoveTool: (String) -> Unit = {}) {
    var removing by remember { mutableStateOf<MyToolRow?>(null) }
    Section(WordlistCopy.SETTINGS_SECTION)
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(role = Role.Button, onClick = onImportTool).testTag("importToolRow"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ImportCopy.IMPORT_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(ImportCopy.IMPORT_SUPPORT, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON_SIZE))
    }
    MyToolsList(myTools) { removing = it }
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(role = Role.Button, onClick = onOpenWordlists).testTag("wordlistsRow"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(WordlistCopy.SETTINGS_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(WordlistCopy.settingsSupport(imported), style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON_SIZE))
    }
    removing?.let { row -> RemoveToolDialog(row.name, onConfirm = { removing = null; onRemoveTool(row.key) }, onDismiss = { removing = null }) }
}

/** My tools: name, version and scope per imported tool, a broken one with its error, and Remove on every row. */
@Composable
private fun MyToolsList(rows: List<MyToolRow>, onRemove: (MyToolRow) -> Unit) {
    Text(ImportCopy.MY_TOOLS, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() })
    if (rows.isEmpty()) Text(ImportCopy.NO_TOOLS, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(bottom = 8.dp).testTag("myToolsEmpty"))
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).testTag("myTool:${row.key}"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(row.name, style = NetProbeText.secondary, color = NetProbeColors.text)
                Text(row.line, style = NetProbeText.data, color = if (row.error != null) NetProbeColors.mark else NetProbeColors.textMuted, modifier = Modifier.testTag("myToolLine:${row.key}"))
            }
            TextButton(onClick = { onRemove(row) }, modifier = Modifier.heightIn(min = Dimens.touch).semantics { contentDescription = ImportCopy.removeDescription(row.name) }.testTag("myToolRemove:${row.key}")) {
                Text(ImportCopy.REMOVE, style = NetProbeText.action, color = NetProbeColors.signal)
            }
        }
    }
}
