package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val PILL_STROKE = 1.5.dp

/** Outlined teal "Stop" pill, as drawn, with a 48 dp minimum touch height (spec 3.3, 7.2). */
@Composable
fun StopPill(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Stop scan", tag: String = "stopPill") {
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.border(PILL_STROKE, NetProbeColors.signal, CircleShape).padding(vertical = 4.dp, horizontal = 14.dp)) {
            Text("Stop", style = NetProbeText.action, color = NetProbeColors.signal)
        }
    }
}

/** Outlined field restyle (spec 4.1): 1 dp border, teal focus and cursor, muted placeholder. */
@Composable
fun npFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NetProbeColors.signal, unfocusedBorderColor = NetProbeColors.border, disabledBorderColor = NetProbeColors.border,
    errorBorderColor = NetProbeColors.error, cursorColor = NetProbeColors.signal,
    focusedTextColor = NetProbeColors.text, unfocusedTextColor = NetProbeColors.text, disabledTextColor = NetProbeColors.textMuted,
    focusedPlaceholderColor = NetProbeColors.textMuted, unfocusedPlaceholderColor = NetProbeColors.textMuted,
    focusedLabelColor = NetProbeColors.textSecondary, unfocusedLabelColor = NetProbeColors.textMuted,
    focusedLeadingIconColor = NetProbeColors.textMuted, unfocusedLeadingIconColor = NetProbeColors.textMuted,
    errorSupportingTextColor = NetProbeColors.error, focusedSupportingTextColor = NetProbeColors.textMuted, unfocusedSupportingTextColor = NetProbeColors.textMuted,
)
