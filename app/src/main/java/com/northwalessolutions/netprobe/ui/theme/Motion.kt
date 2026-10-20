package com.northwalessolutions.netprobe.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Radar timing (spec 3.6). */
object Motion {
    const val SIZE_MS = 360
    const val LABEL_FADE_MS = 160
    const val BLIP_IN_MS = 300
    const val BLIP_OUT_MS = 200
    const val ARM_TURN_MS = 2400
    const val ROW_ARRIVAL_MS = 600
    const val STATIC_ARM_DEG = 40f
    const val PROGRESS_SAMPLE_MS = 250L
    val rowEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val ROW_RISE = 6.dp
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
