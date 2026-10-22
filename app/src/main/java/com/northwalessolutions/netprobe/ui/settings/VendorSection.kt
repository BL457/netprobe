package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.oui.OuiCopy
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** What the Settings vendor row shows: the status line (or the last result), and the progress line while an update runs. */
data class VendorDbUi(val status: String = OuiCopy.NOT_DOWNLOADED, val running: String? = null, val result: String? = null)

/** The Settings row "Vendor database" (Director ruling 2026-10-22 b): caption, status line and the update button. The Stop control is pinned in the top bar. */
@Composable
internal fun VendorSection(ui: VendorDbUi, onUpdate: () -> Unit) {
    Section(OuiCopy.TITLE)
    Text(OuiCopy.CAPTION, style = NetProbeText.data, color = NetProbeColors.textMuted)
    Text(ui.result ?: ui.status, style = NetProbeText.data, color = NetProbeColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp).testTag("vendorStatus"))
    TextButton(onClick = onUpdate, enabled = ui.running == null, modifier = Modifier.heightIn(min = Dimens.touch).testTag("vendorUpdate")) {
        Text(OuiCopy.BUTTON, style = NetProbeText.action, color = if (ui.running == null) NetProbeColors.signal else NetProbeColors.textMuted)
    }
}

/** Pinned under the Settings title while an update runs: the progress line and Stop, never inside the scrolling column. */
@Composable
internal fun VendorRunningBar(text: String, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter).testTag("vendorRunning"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = Dimens.touch).testTag("vendorStop")) {
            Text(OuiCopy.STOP, style = NetProbeText.action, color = NetProbeColors.signal)
        }
    }
}
