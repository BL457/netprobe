package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How often the accessible copy of a [LiveLine]'s live text catches up with what is drawn. */
const val LIVE_TEXT_A11Y_REFRESH_MS = 5_000L
private const val LINE_PROBE = "Ag"

/**
 * One line of text that changes several times a second (a tool run's progress, 3B QA F4): an optional fixed [lead] on the start side,
 * ellipsized to the room left, and the live [text] on the end side (on the start side when there is no lead). [text] is read in the draw lambda only, and the line's size never
 * depends on it, so a change redraws this node alone: no recomposition, no relayout, no semantics change. That matters because while
 * any accessibility service is on, Compose diffs the whole window's semantics tree after every semantics or layout change (about 17 ms
 * on the Pixel with the Device screen's Tools block open), which on every progress tick made almost every frame late. The accessible
 * copy of [text] is therefore refreshed every [LIVE_TEXT_A11Y_REFRESH_MS]; [leadModifier] and [textModifier] go on the two semantics
 * nodes (test tags).
 */
@Composable
fun LiveLine(text: () -> String, style: TextStyle, color: Color, modifier: Modifier = Modifier, lead: String? = null, leadColor: Color = color,
             gap: Dp = 8.dp, leadModifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = remember(style, color) { style.copy(color = color) }
    val leadStyle = remember(style, leadColor) { style.copy(color = leadColor) }
    val lineHeight = remember(measurer, style, density) { with(density) { measurer.measure(LINE_PROBE, style).size.height.toDp() } }
    val current by rememberUpdatedState(text)
    // Read without observation: composition must never subscribe to the live source, or every tick recomposes this line.
    var spoken by remember { mutableStateOf(Snapshot.withoutReadObservation { text() }) }
    LaunchedEffect(Unit) { while (true) { delay(LIVE_TEXT_A11Y_REFRESH_MS); spoken = current() } }
    val a11y = spoken
    // Its own layer, so a tick re-records this line's drawing only, not the whole window's.
    Box(modifier.fillMaxWidth().height(lineHeight).graphicsLayer {}.drawBehind {
        val width = size.width.toInt()
        val live = oneLine(measurer, current(), textStyle, width)
        drawText(live, topLeft = Offset(if (lead == null) 0f else size.width - live.size.width, 0f))
        val room = width - live.size.width - gap.roundToPx()
        if (lead != null && room > 0) drawText(oneLine(measurer, lead, leadStyle, room))
    }) {
        Row(Modifier.matchParentSize()) {
            if (lead != null) Box(Modifier.weight(1f).fillMaxHeight().semantics { this.text = AnnotatedString(lead) }.then(leadModifier))
            Box(Modifier.weight(1f).fillMaxHeight().semantics { this.text = AnnotatedString(a11y) }.then(textModifier))
        }
    }
}

private fun oneLine(m: TextMeasurer, s: String, style: TextStyle, maxWidth: Int) =
    m.measure(s, style, overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(0)))
