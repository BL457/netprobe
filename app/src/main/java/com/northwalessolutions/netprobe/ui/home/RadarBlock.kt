package com.northwalessolutions.netprobe.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.northwalessolutions.netprobe.ui.StopPill
import com.northwalessolutions.netprobe.ui.theme.*
import kotlin.math.abs

/** Test hook: true only while the arm is turning (SCANNING, resumed, motion allowed). */
val ArmSpinningKey = SemanticsPropertyKey<Boolean>("ArmSpinning")
private var SemanticsPropertyReceiver.armSpinning by ArmSpinningKey

private const val ANNOUNCE_STEP = 5
private const val MIN_COUNT_SP = 12
private const val ARM_HEAD_ALPHA = 0.55f
private const val ARM_TAIL_ALPHA = 0.08f
private const val ARM_TAIL_STOP = 70f / 360f
private const val ARM_CLEAR_STOP = 110f / 360f
private const val GLOW_ALPHA = 0.35f
private const val FILL_INNER_STOP = 0.3f
private const val FILL_OUTER_STOP = 0.7f
private const val QUICK_ARC_SWEEP = 90f
private val RING_STROKE = 3.dp
private val DISC_STROKE = 2.dp
private val INNER_RING_STROKE = 1.dp
private val TEXT_GAP = 10.dp
/** Three static dots suggest a radar at rest in HERO, as fractions of the disc radius. */
private val HERO_DOTS = listOf(Offset(-0.45f, -0.30f), Offset(0.50f, -0.10f), Offset(0.15f, 0.55f))

/**
 * The scan control (Radar spec section 3): one composable owns HERO, SCANNING and SETTLED so the size change is one continuous
 * animation driven by a single `fraction` (0 = ring, 1 = disc). Only the ring, the HERO circle and the Stop pill are actionable.
 */
@Composable
fun RadarBlock(
    mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean,
    onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
    heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val spinning = mode == RadarMode.SCANNING && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !reduced
    val transition = updateTransition(mode, label = "radar")
    val fraction by transition.animateFloat(
        transitionSpec = { tween(if (reduced) 0 else Motion.SIZE_MS, easing = FastOutSlowInEasing) }, label = "fraction",
    ) { if (it == RadarMode.SETTLED) 0f else 1f }
    val labelAlpha by animateFloatAsState(if (mode == RadarMode.SETTLED) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "label")
    val heroAlpha by animateFloatAsState(if (mode == RadarMode.HERO) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "hero")
    val blipsAlpha by animateFloatAsState(
        if (mode == RadarMode.SCANNING) 1f else 0f,
        tween(if (reduced) 0 else if (mode == RadarMode.SCANNING) Motion.BLIP_IN_MS else Motion.BLIP_OUT_MS), label = "blips")

    // Blips: one per new host, the newest batch fades in; at most 64 are drawn, the count stays exact.
    val newest = remember { Animatable(1f) }
    var shownBlips by remember { mutableIntStateOf(0) }
    var newestFrom by remember { mutableIntStateOf(0) }
    LaunchedEffect(found, mode) {
        if (mode != RadarMode.SCANNING) return@LaunchedEffect
        val n = found.coerceAtMost(RadarGeometry.MAX_BLIPS)
        if (n < shownBlips) { shownBlips = n; newestFrom = n; return@LaunchedEffect }
        if (n > shownBlips) {
            newestFrom = shownBlips; shownBlips = n
            if (reduced) newest.snapTo(1f) else { newest.snapTo(0f); newest.animateTo(1f, tween(Motion.BLIP_IN_MS)) }
        }
    }

    // TalkBack: polite updates in steps of 5, one announcement when the scan finishes (spec 7.3).
    var announced by remember { mutableStateOf(0 to progressLine) }
    LaunchedEffect(mode) { if (mode == RadarMode.SCANNING) announced = 0 to progressLine }
    LaunchedEffect(found) { if (mode == RadarMode.SCANNING && abs(found - announced.first) >= ANNOUNCE_STEP) announced = found to progressLine }
    val view = LocalView.current
    var previous by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        @Suppress("DEPRECATION")
        if (previous == RadarMode.SCANNING && mode == RadarMode.SETTLED) view.announceForAccessibility(RadarText.finished(summary.headline))
        previous = mode
    }

    val live = canScan || quickScanning
    BoxWithConstraints(modifier.fillMaxWidth().testTag("radarBlock")) {
        val width = maxWidth
        val diameter = lerp(Dimens.ringDiameter, Dimens.discDiameter, fraction)
        val target = diameter + (Dimens.ringTarget - Dimens.ringDiameter) * (1f - fraction)
        val centreX = lerp(Dimens.gutter + Dimens.ringTarget / 2, width / 2, fraction)
        val areaHeight = lerp(Dimens.ringTarget, Dimens.discDiameter, fraction)
        val stroke = lerp(RING_STROKE, DISC_STROKE, fraction)
        // Quick scan dims the ring's base stroke to `border` so the indeterminate `signal` arc reads over it (spec 3.3, 12.9).
        val ringColor = if (live && !quickScanning) NetProbeColors.signal else NetProbeColors.border
        val strokeColor = lerp(ringColor, NetProbeColors.borderSubtle, fraction)
        val modeModifier = when (mode) {
            RadarMode.SETTLED -> Modifier.clip(CircleShape)
                .clickable(enabled = live, role = Role.Button, onClick = if (quickScanning) onStopQuick else onScan)
                .semantics { contentDescription = when { quickScanning -> RadarText.STOP_QUICK; canScan -> RadarText.ringDescription(summary); else -> RadarText.RING_DISABLED } }
                .testTag("radarRing")
            RadarMode.SCANNING -> Modifier
                .semantics { contentDescription = RadarText.scanningDescription(announced.first, announced.second); liveRegion = LiveRegionMode.Polite }
                .testTag("radarDisc")
            RadarMode.HERO -> Modifier.testTag("radarDisc")
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = areaHeight)) {
                Box(Modifier.offset(x = centreX - target / 2, y = (areaHeight - target) / 2).size(target).then(modeModifier), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                        DiscBackground(fraction, stroke, strokeColor, mode == RadarMode.HERO, Modifier.matchParentSize().clearAndSetSemantics { })
                        if (mode == RadarMode.SCANNING) Arm(spinning, Modifier.matchParentSize())
                        if (shownBlips > 0) Blips(shownBlips, newestFrom, { newest.value }, blipsAlpha, Modifier.matchParentSize().clearAndSetSemantics { })
                        if (mode == RadarMode.SETTLED && quickScanning) QuickArc(reduced, Modifier.matchParentSize())
                        if (mode != RadarMode.HERO && labelAlpha > 0f) Text(if (quickScanning) HomeCopy.STOP else HomeCopy.SCAN, style = NetProbeText.ringLabel,
                            color = if (live) NetProbeColors.signal else NetProbeColors.border, modifier = Modifier.alpha(labelAlpha))
                        if (mode == RadarMode.SCANNING || (mode == RadarMode.SETTLED && labelAlpha < 1f))
                            CountColumn(found, Modifier.alpha((1f - labelAlpha) * (1f - heroAlpha)))
                        if (heroAlpha > 0f) HeroButton(canScan && mode == RadarMode.HERO, canScan, heroSubnet, onScan, Modifier.alpha(heroAlpha))
                    }
                }
                if (fraction < 1f) Column(Modifier.align(Alignment.CenterStart).padding(start = Dimens.gutter + Dimens.ringTarget + TEXT_GAP, end = Dimens.gutter)
                    .alpha(1f - fraction)) {
                    Text(summary.headline, style = NetProbeText.summary, color = NetProbeColors.text, maxLines = 1)
                    Text(if (quickScanning) HomeCopy.QUICK_SCANNING else summary.detail, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                }
            }
            if (mode == RadarMode.HERO) Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = Dimens.gutter, end = Dimens.gutter),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (canScan) HomeCopy.HERO_TITLE else HomeCopy.HERO_NO_NETWORK, style = NetProbeText.summary, color = NetProbeColors.text)
                Text(if (canScan && heroSubnet != null) HomeCopy.heroSubtitle(heroSubnet) else HomeCopy.HERO_NO_NETWORK_SUB,
                    style = NetProbeText.secondary, color = NetProbeColors.textMuted, textAlign = TextAlign.Center)
            }
            if (mode == RadarMode.SCANNING) {
                val pillAlpha = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    pillAlpha.animateTo(1f, tween(if (reduced) 0 else Motion.PILL_FADE_MS, delayMillis = if (reduced) 0 else Motion.PILL_DELAY_MS))
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).graphicsLayer { alpha = pillAlpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                    StopPill(onStop, description = RadarText.STOP_SCAN, tag = "radarStop")
                    Spacer(Modifier.height(6.dp))
                    Text(progressLine.orEmpty(), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Dimens.gutter).testTag("radarProgress"))
                }
            }
        }
    }
}

/** Static disc, outline, inner rings and HERO dots; re-recorded only when its inputs or the size change (spec 3.6). */
@Composable
private fun DiscBackground(fraction: Float, stroke: Dp, strokeColor: Color, hero: Boolean, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val fill = Brush.radialGradient(0f to NetProbeColors.discCentre, FILL_INNER_STOP to NetProbeColors.discCentre,
            FILL_OUTER_STOP to NetProbeColors.background, 1f to NetProbeColors.background, center = c, radius = r.coerceAtLeast(1f))
        val ring1 = r - Dimens.discInnerRing1.toPx(); val ring2 = r - Dimens.discInnerRing2.toPx()
        val strokePx = stroke.toPx(); val thin = INNER_RING_STROKE.toPx()
        val dotR = Dimens.blip.toPx() / 2
        val dots = HERO_DOTS.map { c + it * r }
        onDrawBehind {
            if (fraction > 0f) {
                drawCircle(fill, r, c, alpha = fraction)
                if (ring1 > 0f) drawCircle(NetProbeColors.borderSubtle, ring1, c, alpha = fraction, style = Stroke(thin))
                if (ring2 > 0f) drawCircle(NetProbeColors.borderSubtle, ring2, c, alpha = fraction, style = Stroke(thin))
            }
            drawCircle(strokeColor, r - strokePx / 2, c, style = Stroke(strokePx))
            if (hero) dots.forEach { drawCircle(NetProbeColors.textMuted, dotR, it) }
        }
    })
}

/**
 * Blips above the arm, as in the mockup, so the arm's centre cut-out never hides or halves a blip. Positions and glows are
 * re-recorded only when the blip count or the size changes; the newest batch's fade is read in the draw phase only.
 */
@Composable
private fun Blips(blips: Int, newestFrom: Int, newestAlpha: () -> Float, blipsAlpha: Float, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val blipR = Dimens.blip.toPx() / 2; val glowR = blipR + Dimens.blipGlow.toPx()
        val points = (0 until blips).map { i -> (c + blipOffset(i, r)).let { p -> p to Brush.radialGradient(listOf(NetProbeColors.signal.copy(alpha = GLOW_ALPHA), Color.Transparent), center = p, radius = glowR) } }
        onDrawBehind {
            points.forEachIndexed { i, (p, glow) ->
                val a = blipsAlpha * if (i >= newestFrom) newestAlpha() else 1f
                drawCircle(glow, glowR, p, alpha = a); drawCircle(NetProbeColors.signal, blipR, p, alpha = a)
            }
        }
    })
}

/** The sweep arm: its own layer, rotated in the layer block so a frame never recomposes or re-records the disc (spec 3.6). */
@Composable
private fun Arm(spinning: Boolean, modifier: Modifier) {
    if (spinning) {
        val angle = rememberInfiniteTransition(label = "arm")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.ARM_TURN_MS, easing = LinearEasing)), label = "angle")
        ArmLayer({ angle.value }, spinning = true, modifier)
    } else ArmLayer({ Motion.STATIC_ARM_DEG }, spinning = false, modifier)
}

@Composable
private fun ArmLayer(angle: () -> Float, spinning: Boolean, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { armSpinning = spinning }.graphicsLayer { rotationZ = angle() }.drawWithCache {
        val r = size.minDimension / 2
        val sweep = Brush.sweepGradient(0f to NetProbeColors.signal.copy(alpha = ARM_HEAD_ALPHA), ARM_TAIL_STOP to NetProbeColors.signal.copy(alpha = ARM_TAIL_ALPHA),
            ARM_CLEAR_STOP to Color.Transparent, 1f to Color.Transparent, center = size.center)
        // The arm is an annulus: inside the disc outline, with the centre cut out (even-odd) so the count sits on the disc's own
        // plain fill and the inner ring shows through; the mask never outgrows the disc while it grows.
        val outer = (r - DISC_STROKE.toPx()).coerceAtLeast(0f)
        val inner = RadarGeometry.armMask(Dimens.discCentreMask.toPx(), r)
        val c = size.center
        val annulus = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(c, outer)); addOval(Rect(c, inner))
        }
        onDrawBehind { drawPath(annulus, sweep) }
    })
}

/** Quick scan all: an indeterminate teal arc on the ring; the disc never grows for it (spec 3.3). */
@Composable
private fun QuickArc(reduced: Boolean, modifier: Modifier) {
    if (reduced) QuickArcLayer({ Motion.STATIC_ARM_DEG }, modifier)
    else {
        val angle = rememberInfiniteTransition(label = "quick")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.ARM_TURN_MS / 2, easing = LinearEasing)), label = "quickAngle")
        QuickArcLayer({ angle.value }, modifier)
    }
}

@Composable
private fun QuickArcLayer(angle: () -> Float, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { }.graphicsLayer { rotationZ = angle() }.drawWithCache {
        val w = RING_STROKE.toPx()
        onDrawBehind {
            drawArc(NetProbeColors.signal, 0f, QUICK_ARC_SWEEP, false, topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
        }
    })
}

@Composable
private fun CountColumn(found: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("$found", style = NetProbeText.radarCount.copy(color = NetProbeColors.text, textAlign = TextAlign.Center), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_COUNT_SP.sp, maxFontSize = NetProbeText.radarCount.fontSize),
            modifier = Modifier.widthIn(max = Dimens.countMaxWidth).testTag("radarCount"))
        Text(HomeCopy.FOUND, style = NetProbeText.radarLabel, color = NetProbeColors.textMuted)
    }
}

/** The 84 dp filled Scan circle on the HERO disc: the only actionable part of the disc (spec 3.5). */
@Composable
private fun HeroButton(enabled: Boolean, canScan: Boolean, subnet: String?, onScan: () -> Unit, modifier: Modifier) {
    Box(modifier.size(Dimens.heroButton).clip(CircleShape).background(if (canScan) NetProbeColors.signal else NetProbeColors.border)
        .clickable(enabled = enabled, role = Role.Button, onClick = onScan)
        .semantics { contentDescription = RadarText.heroDescription(if (canScan) subnet else null) }.testTag("scanButton"),
        contentAlignment = Alignment.Center) {
        Text(HomeCopy.SCAN, style = NetProbeText.heroButton, color = if (canScan) NetProbeColors.background else NetProbeColors.textMuted)
    }
}
