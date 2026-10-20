package com.northwalessolutions.netprobe.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.runtime.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.DISABLED_ALPHA
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
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

/**
 * Detail Scan pill (spec 5.1 item 8): teal fill "Scan", or an outlined "Stop" while scanning. Phase 3A 1.12: the fill fades out as
 * the outline fades in and the label colour swaps, 220 ms, with a scaleX squash through a layer; the layout width stays max(Scan, Stop)
 * so the profile control never shifts, and the pill stays one node.
 */
@Composable
fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val active = enabled || scanning
    val floatSpec: AnimationSpec<Float> = if (reduced) snap() else tween(Motion.PILL_MORPH_MS, easing = FastOutSlowInEasing)
    val colorSpec: AnimationSpec<Color> = if (reduced) snap() else tween(Motion.PILL_MORPH_MS, easing = FastOutSlowInEasing)
    val fill by animateFloatAsState(if (scanning) 0f else 1f, floatSpec, label = "pillFill")
    val outline by animateFloatAsState(if (scanning) 1f else 0f, floatSpec, label = "pillOutline")
    val labelColor by animateColorAsState(if (scanning) NetProbeColors.signal else NetProbeColors.background, colorSpec, label = "pillLabel")
    val squash = remember { Animatable(1f) }
    val composedOnce = remember { booleanArrayOf(false) }
    LaunchedEffect(scanning) {
        if (!composedOnce[0]) { composedOnce[0] = true; return@LaunchedEffect }   // the first composition is not a morph
        if (reduced) return@LaunchedEffect
        squash.snapTo(1f)
        squash.animateTo(1f, keyframes {
            durationMillis = Motion.PILL_MORPH_MS
            1f at 0; Motion.PILL_SQUASH at Motion.PILL_SQUASH_AT_MS; 1f at Motion.PILL_MORPH_MS
        })
    }
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = active, role = Role.Button, onClick = onClick)
        .alpha(if (active) 1f else DISABLED_ALPHA).testTag("detailScanButton"), contentAlignment = Alignment.Center) {
        // Fill and outline alphas are read in the draw phase only; the squash in the layer block.
        val pill = Modifier.heightIn(min = CONTROL_VISUAL).graphicsLayer { scaleX = squash.value }.drawBehind {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(NetProbeColors.signal, cornerRadius = radius, alpha = fill)
            val w = PILL_STROKE.toPx()
            drawRoundRect(NetProbeColors.signal, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((size.height - w) / 2), style = Stroke(w), alpha = outline)
        }.padding(horizontal = 18.dp)
        // Both labels are laid out and the unused one is invisible, so the pill is max(Scan, Stop) wide at any font scale.
        Box(pill, contentAlignment = Alignment.Center) {
            PillLabel("Scan", labelColor, visible = !scanning)
            PillLabel("Stop", labelColor, visible = scanning)
        }
    }
}

@Composable
private fun PillLabel(text: String, color: Color, visible: Boolean) {
    Text(text, style = NetProbeText.scanPill, color = color,
        modifier = if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
}

/** Label padding inside a segment: 2 dp, so "Investigated" at 12 sp x 1.3 stays on one line in a quarter of the 411 dp Pixel 7 Pro width. */
private val SEGMENT_TEXT_PAD = 2.dp

/** Equal segments in an 8 dp outlined control; 40 dp visual inside a 48 dp target; labels wrap rather than truncate (spec 5.1, 7.5). */
@Composable
fun PinnedSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, activeFill: Color, activeText: Color,
                    modifier: Modifier = Modifier, enabled: Boolean = true, activeWeight: FontWeight = FontWeight.W400) {
    val reduced = LocalReducedMotion.current
    val colorSpec: AnimationSpec<Color> = if (reduced) snap() else tween(Motion.SELECT_MS, easing = FastOutSlowInEasing)
    Row(modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA).selectableGroup().drawBehind {
        val inset = CONTROL_INSET.toPx()
        drawRoundRect(NetProbeColors.border, topLeft = Offset(0f, inset), size = Size(size.width, size.height - 2 * inset),
            cornerRadius = CornerRadius(CONTROL_RADIUS.toPx()), style = Stroke(1.dp.toPx()))
    }) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val shape = when (i) { 0 -> RoundedCornerShape(topStart = CONTROL_RADIUS, bottomStart = CONTROL_RADIUS)
                labels.lastIndex -> RoundedCornerShape(topEnd = CONTROL_RADIUS, bottomEnd = CONTROL_RADIUS); else -> RectangleShape }
            val fill by animateColorAsState(if (active) activeFill else activeFill.copy(alpha = 0f), colorSpec, label = "segmentFill")
            val textColour by animateColorAsState(if (active) activeText else NetProbeColors.textSecondary, colorSpec, label = "segmentText")
            Box(Modifier.weight(1f).heightIn(min = Dimens.touch).selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                .padding(vertical = CONTROL_INSET).testTag("segment:$label"), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().heightIn(min = CONTROL_VISUAL).clip(shape).background(fill)
                    .padding(horizontal = SEGMENT_TEXT_PAD), contentAlignment = Alignment.Center) {
                    // The weight change is not animated; it switches at the start (spec 1.11).
                    Text(label, style = NetProbeText.chip.copy(fontWeight = if (active) activeWeight else FontWeight.W400), color = textColour, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** "Back to Home" with a leading chevron, a 48 dp touch row (spec 5.1 item 1, section 6); [trailing] holds Detail's overflow (Phase 3A 4.5). */
@Composable
fun BackToHome(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onBack).padding(horizontal = Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Back to Home", style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        trailing()
    }
}
