package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.DISABLED_ALPHA
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

private val CONTROL_VISUAL = 40.dp
private val CONTROL_RADIUS = 8.dp
private val CONTROL_INSET = 4.dp

/** Detail Scan pill: teal fill "Scan", or an outlined "Stop" while scanning; 40 dp visual in a 48 dp target (spec 5.1 item 8). */
@Composable
fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = enabled || scanning
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = active, role = Role.Button, onClick = onClick)
        .alpha(if (active) 1f else DISABLED_ALPHA).testTag("detailScanButton"), contentAlignment = Alignment.Center) {
        val pill = Modifier.heightIn(min = CONTROL_VISUAL).clip(CircleShape)
            .then(if (scanning) Modifier.border(PILL_STROKE, NetProbeColors.signal, CircleShape) else Modifier.background(NetProbeColors.signal))
            .padding(horizontal = 18.dp)
        Box(pill, contentAlignment = Alignment.Center) {
            Text(if (scanning) "Stop" else "Scan", style = NetProbeText.scanPill, color = if (scanning) NetProbeColors.signal else NetProbeColors.background)
        }
    }
}

/** Equal segments in an 8 dp outlined control; 40 dp visual inside a 48 dp target; labels wrap rather than truncate (spec 5.1, 7.5). */
@Composable
fun PinnedSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, activeFill: Color, activeText: Color,
                    modifier: Modifier = Modifier, enabled: Boolean = true, activeWeight: FontWeight = FontWeight.W400) {
    Row(modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA).selectableGroup().drawBehind {
        val inset = CONTROL_INSET.toPx()
        drawRoundRect(NetProbeColors.border, topLeft = Offset(0f, inset), size = Size(size.width, size.height - 2 * inset),
            cornerRadius = CornerRadius(CONTROL_RADIUS.toPx()), style = Stroke(1.dp.toPx()))
    }) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val shape = when (i) { 0 -> RoundedCornerShape(topStart = CONTROL_RADIUS, bottomStart = CONTROL_RADIUS)
                labels.lastIndex -> RoundedCornerShape(topEnd = CONTROL_RADIUS, bottomEnd = CONTROL_RADIUS); else -> RectangleShape }
            Box(Modifier.weight(1f).heightIn(min = Dimens.touch).selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                .padding(vertical = CONTROL_INSET), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().heightIn(min = CONTROL_VISUAL).clip(shape).background(if (active) activeFill else Color.Transparent)
                    .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = NetProbeText.chip.copy(fontWeight = if (active) activeWeight else FontWeight.W400),
                        color = if (active) activeText else NetProbeColors.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** "Back to Home" with a leading chevron, a 48 dp touch row (spec 5.1 item 1, section 6). */
@Composable
fun BackToHome(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onBack).padding(horizontal = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Back to Home", style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
