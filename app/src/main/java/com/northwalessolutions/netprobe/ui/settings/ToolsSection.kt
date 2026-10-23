package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
internal fun ToolsSection(imported: Int?, onOpenWordlists: () -> Unit, onImportTool: () -> Unit = {}) {
    Section(WordlistCopy.SETTINGS_SECTION)
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(role = Role.Button, onClick = onImportTool).testTag("importToolRow"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ImportCopy.IMPORT_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(ImportCopy.IMPORT_SUPPORT, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON_SIZE))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(role = Role.Button, onClick = onOpenWordlists).testTag("wordlistsRow"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(WordlistCopy.SETTINGS_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(WordlistCopy.settingsSupport(imported), style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON_SIZE))
    }
}
