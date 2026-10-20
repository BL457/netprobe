package com.northwalessolutions.netprobe.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Radar timing (Radar spec 3.6) and the Phase 3A motion tokens (Phase 3A spec 1.2). */
object Motion {
    const val SIZE_MS = 360
    const val LABEL_FADE_MS = 160
    const val BLIP_IN_MS = 300
    const val BLIP_OUT_MS = 200
    /** One full turn, LinearEasing (A1: was 2400). */
    const val ARM_TURN_MS = 3000
    /** Quick scan all's arc keeps its Phase 2 speed (2400 / 2), not half of the slower arm turn (plan review G17). */
    const val QUICK_ARC_TURN_MS = 1200
    const val ROW_ARRIVAL_MS = 600
    const val STATIC_ARM_DEG = 40f
    const val PROGRESS_SAMPLE_MS = 250L
    val rowEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val ROW_RISE = 6.dp

    // The sweep (spec 1.3 to 1.7)
    const val TRAIL_DEG = 140f
    const val ARM_HEAD_ALPHA = 0.55f
    const val HEAD_LINE_ALPHA = 0.85f
    val HEAD_LINE_WIDTH = 1.5.dp
    const val AFTERGLOW_DEG = 120f
    const val BLIP_REST_ALPHA = 0.45f
    const val BLIP_STATIC_ALPHA = 1f
    const val GLOW_REST_ALPHA = 0.15f
    const val GLOW_LIT_ALPHA = 0.45f
    const val GLOW_LIT_SCALE = 1.5f
    const val PULSE_MS = 700
    const val PULSE_ALPHA = 0.35f
    val PULSE_STROKE = 1.5.dp
    const val PULSE_MIN_GAP_MS = 350L
    const val BREATH_MIN = 0.10f
    const val BREATH_MAX = 0.20f
    const val SETTLE_DAMPING = 0.7f
    const val SETTLE_STIFFNESS = Spring.StiffnessMediumLow   // 400f
    const val ARM_FADE_MS = 200

    // Elsewhere (spec 1.8 to 1.14)
    const val ROW_STAGGER_MS = 40
    const val ROW_STAGGER_MAX_MS = 200
    const val NAV_MS = 240
    const val NAV_ENTER_OFFSET = 0.25f
    const val NAV_EXIT_OFFSET = 0.10f
    const val PORT_ROW_MS = 240
    const val SELECT_MS = 160
    const val PILL_MORPH_MS = 220
    const val PILL_SQUASH = 0.88f
    const val PILL_SQUASH_AT_MS = 90
    const val NOTICE_IN_MS = 200
    const val NOTICE_OUT_MS = 160
    const val IDENTITY_FADE_MS = 240
    const val EDGE_COLOUR_MS = 300
    const val PROGRESS_MS = 250
}

/** Provided by NetProbeTheme; tests override it without touching system settings (spec 3.7). */
val LocalReducedMotion = compositionLocalOf { false }

object ReducedMotion {
    /** Android's "Remove animations" and a developer animator scale of "off" both set the scale to 0. */
    fun fromScale(scale: Float): Boolean = scale == 0f
}

private fun readReducedMotion(ctx: Context): Boolean =
    ReducedMotion.fromScale(Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/** Re-read every time the lifecycle resumes, so a change in system settings applies on return. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    var reduced by remember { mutableStateOf(readReducedMotion(ctx)) }
    LifecycleResumeEffect(ctx) {
        reduced = readReducedMotion(ctx)
        onPauseOrDispose { }
    }
    return reduced
}
