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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.northwalessolutions.netprobe.ui.StopPill
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test hooks (spec 1.17), all on "radarBlock" and set from composition state, never from the per-frame angle. */
val ArmSpinningKey = SemanticsPropertyKey<Boolean>("ArmSpinning")
private var SemanticsPropertyReceiver.armSpinning by ArmSpinningKey
val RadarFractionKey = SemanticsPropertyKey<Float>("RadarFraction")
private var SemanticsPropertyReceiver.radarFraction by RadarFractionKey
val PulseCountKey = SemanticsPropertyKey<Int>("PulseCount")
private var SemanticsPropertyReceiver.pulseCount by PulseCountKey
val PulseActiveKey = SemanticsPropertyKey<Boolean>("PulseActive")
private var SemanticsPropertyReceiver.pulseActive by PulseActiveKey
val BreathingKey = SemanticsPropertyKey<Boolean>("Breathing")
private var SemanticsPropertyReceiver.breathing by BreathingKey

private const val ANNOUNCE_STEP = 5
private const val MIN_COUNT_SP = 12
private const val FILL_INNER_STOP = 0.3f
private const val FILL_OUTER_STOP = 0.7f
private const val QUICK_ARC_SWEEP = 90f
private const val PULSE_END_FRACTION = 0.95f
private const val FULL_TURN_DEG = 360f
private val RING_STROKE = 3.dp
private val DISC_STROKE = 2.dp
private val INNER_RING_STROKE = 1.dp
private val TEXT_GAP = 10.dp
/** Three static dots suggest a radar at rest in HERO, as fractions of the disc radius. */
private val HERO_DOTS = listOf(Offset(-0.45f, -0.30f), Offset(0.50f, -0.10f), Offset(0.15f, 0.55f))

/**
 * The scan control (Radar spec section 3, Phase 3A spec section 1): one composable owns HERO, SCANNING and SETTLED so the size
 * change is one continuous animation driven by a single `fraction` (0 = ring, 1 = disc). Only the ring, the HERO circle and the
 * Stop pill are actionable. [onBusyChange] is true from SCANNING until the settle ends; [onComposed] is a test hook called after
 * every composition of this block and of its three layer composables (plan review G6).
 */
@Composable
fun RadarBlock(
    mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean,
    onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
    heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {}, scanCompleted: Boolean = true,
    onBusyChange: (Boolean) -> Unit = {}, onComposed: () -> Unit = {},
) {
    SideEffect { onComposed() }
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val transition = updateTransition(mode, label = "radar")
    // The settle springs (spec 1.7), so fraction may dip below 0: only the ring's own size reads it unclamped.
    val fraction by transition.animateFloat(transitionSpec = {
        when {
            reduced -> snap()
            RadarMode.SCANNING isTransitioningTo RadarMode.SETTLED -> spring(dampingRatio = Motion.SETTLE_DAMPING, stiffness = Motion.SETTLE_STIFFNESS)
            else -> tween(Motion.SIZE_MS, easing = FastOutSlowInEasing)
        }
    }, label = "fraction") { if (it == RadarMode.SETTLED) 0f else 1f }
    val f = fraction.coerceIn(0f, 1f)
    val atRest = transition.currentState == transition.targetState
    val settling = transition.currentState == RadarMode.SCANNING && transition.targetState == RadarMode.SETTLED
    // Arm, blips and the angle exist while scanning or settling; the Stop pill and progress line too.
    val live = mode == RadarMode.SCANNING || settling
    val latestBusy by rememberUpdatedState(onBusyChange)
    LaunchedEffect(live) { latestBusy(live) }

    val labelAlpha by animateFloatAsState(if (mode == RadarMode.SETTLED) 1f else 0f, if (reduced) snap() else tween(Motion.LABEL_FADE_MS), label = "label")
    val heroAlpha by animateFloatAsState(if (mode == RadarMode.HERO) 1f else 0f, if (reduced) snap() else tween(Motion.LABEL_FADE_MS), label = "hero")
    // The arm and blips fade in with the scan and out over ARM_FADE_MS when the settle starts (spec 1.7).
    val liveAlpha by animateFloatAsState(if (mode == RadarMode.SCANNING) 1f else 0f,
        if (reduced) snap() else tween(if (mode == RadarMode.SCANNING) Motion.BLIP_IN_MS else Motion.ARM_FADE_MS), label = "live")

    // One angle for the arm, the afterglow and the breathing, hoisted here (spec 1.3). Not resumed: frozen where it stopped and
    // resumed from there, through a non-state holder written in the arm's layer block (spec 1.9, Gruff m2).
    val lastAngle = remember { FloatArray(1).also { it[0] = Motion.STATIC_ARM_DEG } }
    val turning = live && resumed && !reduced
    val angle: () -> Float = when {
        reduced -> ({ Motion.STATIC_ARM_DEG })
        turning -> {
            val start = remember { lastAngle[0] }
            val a = rememberInfiniteTransition(label = "arm")
                .animateFloat(start, start + FULL_TURN_DEG, infiniteRepeatable(tween(Motion.ARM_TURN_MS, easing = LinearEasing)), label = "angle")
            ({ a.value })
        }
        else -> ({ lastAngle[0] })
    }
    val breathingOn = turning && mode == RadarMode.SCANNING

    // Blips: one per new host, the newest batch fades in; at most 64 are drawn, the count stays exact. The find counter only rises.
    val newest = remember { Animatable(1f) }
    var shownBlips by remember { mutableIntStateOf(0) }
    var newestFrom by remember { mutableIntStateOf(0) }
    var blipMode by remember { mutableStateOf<RadarMode?>(null) }
    var lastFound by remember { mutableIntStateOf(0) }
    var finds by remember { mutableIntStateOf(0) }
    var pulseStarts by remember { mutableIntStateOf(0) }
    LaunchedEffect(found, mode) {
        val entering = mode == RadarMode.SCANNING && blipMode != RadarMode.SCANNING
        blipMode = mode
        if (mode != RadarMode.SCANNING) return@LaunchedEffect
        if (entering) { shownBlips = 0; newestFrom = 0; finds = 0; pulseStarts = 0; lastFound = found }   // a new scan: no old blips, the reset never pulses
        else { if (found > lastFound) finds++; lastFound = found }
        val n = found.coerceAtMost(RadarGeometry.MAX_BLIPS)
        if (n < shownBlips) { shownBlips = n; newestFrom = n; return@LaunchedEffect }
        if (n > shownBlips) {
            newestFrom = shownBlips; shownBlips = n
            if (reduced) newest.snapTo(1f) else { newest.snapTo(0f); newest.animateTo(1f, tween(Motion.BLIP_IN_MS)) }
        }
    }

    // Ring pulse (spec 1.5, Gruff M2): gated by the frame clock, launched from a scope no found change can cancel, eased at draw time.
    val pulse = remember { Animatable(1f) }                 // 1 = idle
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode, reduced) {
        if (mode != RadarMode.SCANNING || reduced) return@LaunchedEffect
        var lastStart: Long? = null
        var seen = finds
        snapshotFlow { finds }.collect { n ->
            val rose = n > seen
            seen = n
            if (!rose) return@collect
            val now = withFrameMillis { it }
            if (PulseGate.shouldStart(now, lastStart)) {
                lastStart = now; pulseStarts++
                scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(Motion.PULSE_MS, easing = LinearEasing)) }
            }
        }
    }
    val pulseRunning by remember { derivedStateOf { pulse.value < 1f } }

    // TalkBack: polite updates in steps of 5, one announcement when the scan finishes or stops (spec 7.3, Phase 3A 5.4).
    var announced by remember { mutableStateOf(0 to progressLine) }
    LaunchedEffect(mode) { if (mode == RadarMode.SCANNING) announced = 0 to progressLine }
    LaunchedEffect(found) { if (mode == RadarMode.SCANNING && abs(found - announced.first) >= ANNOUNCE_STEP) announced = found to progressLine }
    val view = LocalView.current
    var previous by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        @Suppress("DEPRECATION")
        if (previous == RadarMode.SCANNING && mode == RadarMode.SETTLED)
            view.announceForAccessibility(if (scanCompleted) RadarText.finished(summary.headline) else RadarText.stopped(summary.headline))
        previous = mode
    }

    val actionable = canScan || quickScanning
    // The hooks are composition state (spec 1.17): read here, never from the per-frame angle.
    val pulsesNow = pulseStarts
    val pulseActiveNow = pulseRunning
    BoxWithConstraints(modifier.fillMaxWidth().testTag("radarBlock").semantics {
        armSpinning = turning; radarFraction = fraction; pulseCount = pulsesNow; pulseActive = pulseActiveNow; breathing = breathingOn
    }) {
        SideEffect { onComposed() }               // test hook (G6): the content scope is its own subcomposition
        val width = maxWidth
        // Unclamped: the ring's own size is the visible bounce. Everything else reads f (spec 1.7 table).
        val diameter = lerp(Dimens.ringDiameter, Dimens.discDiameter, fraction)
        val target = diameter + (Dimens.ringTarget - Dimens.ringDiameter) * (1f - fraction)
        val centreX = lerp(Dimens.gutter + Dimens.ringTarget / 2, width / 2, f)
        val areaHeight = lerp(Dimens.ringTarget, Dimens.discDiameter, f)
        // Quick scan dims the ring's base stroke to `border` so the indeterminate `signal` arc reads over it (spec 3.3, 12.9).
        val ringColor = if (actionable && !quickScanning) NetProbeColors.signal else NetProbeColors.border
        // Read in the live layer's draw, so the expand and settle do not rebuild its cache for the outline (fix round 1).
        val stroke = { lerp(RING_STROKE, DISC_STROKE, fraction) }
        val strokeColor = { lerp(ringColor, NetProbeColors.borderSubtle, fraction.coerceIn(0f, 1f)) }
        // QA F4 (jank): the three drawn layers are laid out at the full disc size and scaled in their own graphicsLayer, so their
        // draw caches (64 glow brushes, the sweep brush) survive the expand and settle; only the draw re-records per frame.
        val discScale = remember { { lerp(Dimens.ringDiameter, Dimens.discDiameter, fraction) / Dimens.discDiameter } }
        val fillAlpha = remember { { fraction.coerceIn(0f, 1f) } }
        val fullDisc = Modifier.requiredSize(Dimens.discDiameter)
        val modeModifier = when (mode) {
            RadarMode.SETTLED -> Modifier.clip(CircleShape)
                .clickable(enabled = actionable && atRest, role = Role.Button, onClick = if (quickScanning) onStopQuick else onScan)   // no tap until at rest
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
                        DiscBackground(fillAlpha, discScale, mode == RadarMode.HERO, onComposed, fullDisc.clearAndSetSemantics { })
                        if (live) ArmLayer(angle, { liveAlpha }, discScale, lastAngle, onComposed, fullDisc)
                        LiveLayer(stroke, strokeColor, breathingOn, shownBlips, newestFrom, reduced, angle, { pulse.value }, { newest.value }, { liveAlpha },
                            discScale, onComposed, fullDisc.clearAndSetSemantics { })
                        if (mode == RadarMode.SETTLED && quickScanning) QuickArc(animate = resumed && !reduced, Modifier.matchParentSize())
                        if (mode != RadarMode.HERO && labelAlpha > 0f) Text(if (quickScanning) HomeCopy.STOP else HomeCopy.SCAN, style = NetProbeText.ringLabel,
                            color = if (actionable) NetProbeColors.signal else NetProbeColors.border, modifier = Modifier.alpha(labelAlpha))
                        if (mode == RadarMode.SCANNING || (mode == RadarMode.SETTLED && labelAlpha < 1f))
                            CountColumn(found, Modifier.alpha((1f - labelAlpha) * (1f - heroAlpha)))
                        if (heroAlpha > 0f) HeroButton(canScan && mode == RadarMode.HERO, canScan, heroSubnet, onScan, Modifier.alpha(heroAlpha))
                    }
                }
                if (f < 1f) Column(Modifier.align(Alignment.CenterStart).padding(start = Dimens.gutter + Dimens.ringTarget + TEXT_GAP, end = Dimens.gutter)
                    .alpha(1f - f)) {
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
            if (live) {
                // Height and alpha follow the clamped fraction, so the list below slides, never steps, and a layout height is never negative.
                Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, (placeable.height * f).roundToInt()) { placeable.place(0, 0) }
                }.graphicsLayer { alpha = f }.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StopPill(if (mode == RadarMode.SCANNING) onStop else ({}), description = RadarText.STOP_SCAN, tag = "radarStop")
                    Spacer(Modifier.height(6.dp))
                    Text(progressLine.orEmpty(), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Dimens.gutter).testTag("radarProgress"))
                }
            }
        }
    }
}

/** Scales a fixed full-disc layer to the current disc size about its centre (QA F4). */
private fun Modifier.discScaled(scale: () -> Float): Modifier = graphicsLayer { val s = scale(); scaleX = s; scaleY = s }

/**
 * Layer 1 (spec 1.16): fill, inner rings and HERO dots on their own layer. The cache is built once at the full disc size (QA F4);
 * [fill] and [scale] are read only in the draw, which divides the fixed dp sizes by the scale so they keep their on-screen size.
 */
@Composable
private fun DiscBackground(fill: () -> Float, scale: () -> Float, hero: Boolean, onComposed: () -> Unit, modifier: Modifier) {
    SideEffect { onComposed() }                   // test hook (G6): never call fill() or scale() in this body
    Spacer(modifier.discScaled(scale).drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val brush = Brush.radialGradient(0f to NetProbeColors.discCentre, FILL_INNER_STOP to NetProbeColors.discCentre,
            FILL_OUTER_STOP to NetProbeColors.background, 1f to NetProbeColors.background, center = c, radius = r.coerceAtLeast(1f))
        val ring1Px = Dimens.discInnerRing1.toPx(); val ring2Px = Dimens.discInnerRing2.toPx()
        val thinPx = INNER_RING_STROKE.toPx()
        var thinLocal = -1f
        var thin = Stroke(0f)                     // rebuilt only when the scale changes, never per steady frame
        val dotR = Dimens.blip.toPx() / 2
        val dots = HERO_DOTS.map { c + it * r }
        onDrawBehind {
            val k = 1f / scale()
            val f = fill()
            if (f > 0f) {
                drawCircle(brush, r, c, alpha = f)
                if (thinPx * k != thinLocal) { thinLocal = thinPx * k; thin = Stroke(thinLocal) }
                val ring1 = r - ring1Px * k; val ring2 = r - ring2Px * k
                if (ring1 > 0f) drawCircle(NetProbeColors.borderSubtle, ring1, c, alpha = f, style = thin)
                if (ring2 > 0f) drawCircle(NetProbeColors.borderSubtle, ring2, c, alpha = f, style = thin)
            }
            if (hero) dots.forEach { drawCircle(NetProbeColors.textMuted, dotR * k, it) }
        }
    })
}

/**
 * Layer 2: the sweep, rotated and scaled in its layer block (a property change per frame, never a re-record); the bright edge
 * leads (spec 1.3). Laid out at the full disc size (QA F4): the sweep brush is built once, and the annulus is a stroked circle
 * whose radii come from the actual disc radius in the draw, so the outline gap and the centre mask keep their dp sizes.
 */
@Composable
private fun ArmLayer(angle: () -> Float, armAlpha: () -> Float, scale: () -> Float, lastAngle: FloatArray, onComposed: () -> Unit, modifier: Modifier) {
    SideEffect { onComposed() }                   // test hook (G6): never call angle(), armAlpha() or scale() in this body
    Spacer(modifier.clearAndSetSemantics { }.graphicsLayer {
        val a = angle()
        lastAngle[0] = a % FULL_TURN_DEG          // a non-state holder: no recomposition; the arm resumes from here
        rotationZ = a
        alpha = armAlpha()
        val s = scale(); scaleX = s; scaleY = s
    }.drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val sweep = Brush.sweepGradient(*ArmTrail.stops().map { (pos, a) -> pos to NetProbeColors.signal.copy(alpha = a) }.toTypedArray(), center = c)
        val discStrokePx = DISC_STROKE.toPx(); val maskPx = Dimens.discCentreMask.toPx()
        val head = NetProbeColors.signal.copy(alpha = Motion.HEAD_LINE_ALPHA)
        val headWidth = Motion.HEAD_LINE_WIDTH.toPx()
        var bandLocal = -1f
        var band = Stroke(0f)                     // rebuilt only when the scale changes, never per steady frame
        onDrawBehind {
            // An annulus inside the outline with the centre cut out: the count sits on the disc's plain fill. Radii in local units.
            val s = scale(); val k = 1f / s
            val outer = (r * s - discStrokePx).coerceAtLeast(0f) * k
            val inner = RadarGeometry.armMask(maskPx, r * s) * k
            val width = outer - inner
            if (width > 0f) {
                if (width != bandLocal) { bandLocal = width; band = Stroke(width) }
                drawCircle(sweep, (outer + inner) / 2, c, style = band)
            }
            drawLine(head, Offset(c.x + inner, c.y), Offset(c.x + outer, c.y), strokeWidth = headWidth * k, cap = StrokeCap.Round)
        }
    })
}

/**
 * Layer 3, the one live layer (spec 1.16): the breathing outline, the pulse ring and the blips with their afterglow, in that order,
 * above the arm. It has its own graphicsLayer, so its per-frame redraw re-records only this layer. Positions, angles and brushes are
 * built in the cache once per blip count; the draw allocates nothing in steady scanning and reads the angle only while breathing
 * or blips show. The layer is laid out at the full disc size and scaled in its layer block (QA F4), so the expand and settle keep
 * the cache; the draw divides the fixed dp sizes (outline, pulse stroke, blip and glow radii, centre mask) by the scale.
 */
@Composable
private fun LiveLayer(stroke: () -> Dp, strokeColor: () -> Color, breathing: Boolean, blips: Int, newestFrom: Int, reduced: Boolean,
                      angle: () -> Float, pulse: () -> Float, newestAlpha: () -> Float, blipsAlpha: () -> Float, scale: () -> Float,
                      onComposed: () -> Unit, modifier: Modifier) {
    SideEffect { onComposed() }                   // test hook (G6): never call angle(), pulse(), scale() or the alphas in this body
    Spacer(modifier.discScaled(scale).drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        var outlinePx = -1f
        var outline = Stroke(0f)                  // rebuilt only when the width changes, never per steady frame
        val pulsePx = Motion.PULSE_STROKE.toPx()
        var pulseLocal = -1f
        var pulseStroke = Stroke(0f)              // likewise, only when the scale changes
        val maskPx = Dimens.discCentreMask.toPx()
        val blipR = Dimens.blip.toPx() / 2; val glowR = blipR + Dimens.blipGlow.toPx()
        val offsets = List(blips) { blipOffset(it, r) }
        val points = offsets.map { c + it }
        val angles = FloatArray(blips) { blipAngleDeg(offsets[it]) }
        val glows = points.map { p -> Brush.radialGradient(listOf(NetProbeColors.signal, Color.Transparent), center = p, radius = glowR) }
        onDrawBehind {
            val s = scale(); val k = 1f / s
            val strokePx = stroke().toPx() * k    // local units: on screen it is the spec width at every size
            if (strokePx != outlinePx) { outlinePx = strokePx; outline = Stroke(strokePx) }
            val b = if (breathing) breath(angle()) else 0f
            drawCircle(lerp(strokeColor(), NetProbeColors.signal, b), r - strokePx / 2, c, style = outline)
            val p = pulse()
            if (p < 1f) {
                val e = LinearOutSlowInEasing.transform(p)
                if (pulsePx * k != pulseLocal) { pulseLocal = pulsePx * k; pulseStroke = Stroke(pulseLocal) }
                val mask = RadarGeometry.armMask(maskPx, r * s) * k
                drawCircle(NetProbeColors.signal, lerp(mask, r * PULSE_END_FRACTION, e), c, alpha = Motion.PULSE_ALPHA * (1f - e), style = pulseStroke)
            }
            val all = blipsAlpha()
            if (blips > 0 && all > 0f) {
                val arm = if (reduced) 0f else angle()
                for (i in 0 until blips) {
                    val g = if (reduced) 0f else afterglow(arm, angles[i])
                    val fade = all * if (i >= newestFrom) newestAlpha() else 1f
                    scale(blipGlowScale(g, reduced) * k, pivot = points[i]) { drawCircle(glows[i], glowR, points[i], alpha = blipGlowAlpha(g, reduced) * fade) }
                    drawCircle(NetProbeColors.signal, blipR * k, points[i], alpha = blipCoreAlpha(g, reduced) * fade)
                }
            }
        }
    })
}

/** Quick scan all: an indeterminate teal arc on the ring; the disc never grows for it (spec 3.3). */
@Composable
private fun QuickArc(animate: Boolean, modifier: Modifier) {
    // Like the arm: the infinite transition exists only while resumed and motion is allowed (spec 3.6, D11).
    if (!animate) QuickArcLayer({ Motion.STATIC_ARM_DEG }, modifier)
    else {
        val angle = rememberInfiniteTransition(label = "quick")
            .animateFloat(0f, FULL_TURN_DEG, infiniteRepeatable(tween(Motion.QUICK_ARC_TURN_MS, easing = LinearEasing)), label = "quickAngle")
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
