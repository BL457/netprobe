package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.northwalessolutions.netprobe.engine.script.InstalledTool
import com.northwalessolutions.netprobe.engine.script.ScriptToolStore
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** One line of Settings > Tools > My tools: an imported tool, working or broken. [error] is set for a broken one. */
data class MyToolRow(val key: String, val name: String, val line: String, val error: String?)

/** The My tools list as Settings draws it. */
object MyTools {
    fun rows(installed: List<InstalledTool>): List<MyToolRow> =
        installed.map { t -> MyToolRow(t.key, t.name, if (t.broken) ImportCopy.brokenLine(t.error.orEmpty()) else ImportCopy.myToolLine(t.version, t.scope), t.error) }
}

/**
 * Removes an imported tool: its script, its index line and what the tool page remembered for it. The tool's past runs are rows in the
 * database that carry their own tool name, so they keep reading after the tool is gone. Built-ins and examples are not removable.
 */
class ToolRemoval(private val store: ScriptToolStore, private val forgetInputs: (String) -> Unit) {
    /** The message to show: "Removed <name>", or why nothing was removed. */
    suspend fun remove(key: String): String {
        if (!key.startsWith(ToolKeys.USER_PREFIX)) return ImportCopy.REMOVE_FAILED
        val name = store.installed.value.firstOrNull { it.key == key }?.name ?: key.removePrefix(ToolKeys.USER_PREFIX)
        if (!store.remove(key)) return ImportCopy.REMOVE_FAILED
        forgetInputs(key)
        return ImportCopy.removed(name)
    }
}

/** The confirmation before a tool is removed (the tile menu and Settings both use it). */
@Composable
fun RemoveToolDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = NetProbeColors.surface,
        title = { Text(ImportCopy.removeTitle(name), style = NetProbeText.title, color = NetProbeColors.text) },
        text = { Text(ImportCopy.REMOVE_BODY, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = Dimens.touch).testTag("removeConfirm")) { Text(ImportCopy.REMOVE, color = NetProbeColors.mark) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Dimens.touch).testTag("removeCancel")) { Text(ImportCopy.CANCEL, color = NetProbeColors.textSecondary) } })
}
