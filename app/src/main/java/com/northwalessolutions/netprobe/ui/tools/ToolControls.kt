package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.DISABLED_ALPHA
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val PILL_VISUAL = 40.dp
private val PILL_PAD = 18.dp

/** A filled teal pill in the ScanPill style: 40 dp visual in a 48 dp target (spec 8.4 Run, 8.6 Import). */
@Composable
fun ActionPill(label: String, enabled: Boolean, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else DISABLED_ALPHA).testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = PILL_VISUAL).clip(CircleShape).background(NetProbeColors.signal).padding(horizontal = PILL_PAD), contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.scanPill, color = NetProbeColors.background)
        }
    }
}

/** Every tool action the Device screen fires; DetailScreen wires them to DetailViewModel (Task 16). */
data class ToolActions(
    val onRunTool: (String, Int) -> Unit = { _, _ -> },
    val onOpenResults: (String) -> Unit = {}, val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {},
    val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {}, val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {},
    val onStopTool: () -> Unit = {}, val onDismissAppMessage: (Long) -> Unit = {},
)
