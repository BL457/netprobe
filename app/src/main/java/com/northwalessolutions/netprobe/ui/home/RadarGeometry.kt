package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.geometry.Offset
import com.northwalessolutions.netprobe.ui.theme.Motion
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RadarGeometry {
    const val MAX_BLIPS = 64
    /**
     * Raised from the spec's 0.22 (deviation, recorded in the Task 4 report): the count sits on plain fill inside the 40 dp
     * centre (0.47 of the radius), so a blip nearer than that would either be hidden under the mask or drawn over the count.
     * At 0.5 the nearest dot centre is 43 dp out and the closest pair of the first 64 is 5.5 dp apart (guard: 4 dp).
     */
    const val MIN_RADIUS_FRACTION = 0.5f
    const val MAX_RADIUS_FRACTION = 0.88f
    const val GOLDEN_ANGLE_DEG = 137.50776f
    const val JITTER_DEG = 16f
    const val ARM_MASK_FRACTION = 0.47f   // 40 dp of the 86 dp full radius
    private const val BYTE_MASK = 0xFF
    private const val BYTE_MAX = 255f
    private const val HALF = 0.5f

    /** The plain-fill centre behind the count never exceeds [ARM_MASK_FRACTION] of the disc radius, so it stays inside the disc while the disc grows. */
    fun armMask(maskPx: Float, discRadiusPx: Float): Float = minOf(maskPx, discRadiusPx * ARM_MASK_FRACTION)

    /** Fixed 32-bit integer hash (murmur3 finaliser); Int overflow wraps exactly as the unsigned arithmetic. */
    fun mix(i: Int): Int {
        var x = i * -0x61c88647            // 0x9E3779B9
        x = x xor (x ushr 16); x *= -0x7a143595   // 0x85EBCA6B
        x = x xor (x ushr 13); x *= -0x3d4d51cb   // 0xC2B2AE35
        return x xor (x ushr 16)
    }

    internal fun jitterDeg(i: Int): Float = ((mix(i) and BYTE_MASK) / BYTE_MAX - HALF) * JITTER_DEG
    internal fun radiusFraction(i: Int): Float =
        MIN_RADIUS_FRACTION + (MAX_RADIUS_FRACTION - MIN_RADIUS_FRACTION) * sqrt((Math.floorMod(i, MAX_BLIPS) + HALF) / MAX_BLIPS)
}

/** Stable, scattered blip position relative to the disc centre: a golden-angle spiral with a hashed jitter (see the Task 4 note). */
fun blipOffset(index: Int, radiusPx: Float): Offset {
    val angle = Math.toRadians((index * RadarGeometry.GOLDEN_ANGLE_DEG + RadarGeometry.jitterDeg(index)).toDouble())
    val d = RadarGeometry.radiusFraction(index) * radiusPx
    return Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat())
}

private const val FULL_TURN_DEG = 360f
private const val HALF_F = 0.5f

/** The sweep wedge (spec 1.3): head at 360 degrees, a 140 degree tail behind it; (position, alpha of signal) for Brush.sweepGradient. */
object ArmTrail {
    private val DEGREES_ALPHA = listOf(0f to 0f, 220f to 0f, 260f to 0.03f, 300f to 0.09f, 330f to 0.20f, 350f to 0.38f,
        359f to Motion.ARM_HEAD_ALPHA, 360f to Motion.ARM_HEAD_ALPHA)
    fun stops(): List<Pair<Float, Float>> = DEGREES_ALPHA.map { (deg, alpha) -> deg / FULL_TURN_DEG to alpha }
}

/** 1 when the arm head is on the blip, falling to 0 at AFTERGLOW_DEG behind it; 0 ahead of the arm (spec 1.4). */
fun afterglow(armDeg: Float, blipDeg: Float): Float {
    val d = ((armDeg - blipDeg) % FULL_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG
    if (d >= Motion.AFTERGLOW_DEG) return 0f
    val g = 1f - d / Motion.AFTERGLOW_DEG
    return g * g
}

/** Disc outline tint towards signal, once per arm turn: BREATH_MAX at 0 degrees, BREATH_MIN at 180 (spec 1.6). */
fun breath(armDeg: Float): Float =
    Motion.BREATH_MIN + (Motion.BREATH_MAX - Motion.BREATH_MIN) * (HALF_F + HALF_F * cos(Math.toRadians(armDeg.toDouble())).toFloat())

/** The clockwise screen angle of a blip offset in [0, 360); screen y points down, as the arm's rotationZ does (spec 1.4). */
fun blipAngleDeg(offset: Offset): Float {
    val deg = Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
    return (deg % FULL_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG
}

/** Blip core alpha: lerp(BLIP_REST_ALPHA, 1, glow), or BLIP_STATIC_ALPHA under reduced motion (spec 1.4, 1.15). */
fun blipCoreAlpha(glow: Float, reduced: Boolean): Float = if (reduced) Motion.BLIP_STATIC_ALPHA else Motion.BLIP_REST_ALPHA + (1f - Motion.BLIP_REST_ALPHA) * glow
/** Blip glow alpha: lerp(GLOW_REST_ALPHA, GLOW_LIT_ALPHA, glow), or GLOW_REST_ALPHA under reduced motion. */
fun blipGlowAlpha(glow: Float, reduced: Boolean): Float = if (reduced) Motion.GLOW_REST_ALPHA else Motion.GLOW_REST_ALPHA + (Motion.GLOW_LIT_ALPHA - Motion.GLOW_REST_ALPHA) * glow
/** Glow scale about the blip centre: lerp(1, GLOW_LIT_SCALE, glow), or 1 under reduced motion. */
fun blipGlowScale(glow: Float, reduced: Boolean): Float = if (reduced) 1f else 1f + (Motion.GLOW_LIT_SCALE - 1f) * glow
