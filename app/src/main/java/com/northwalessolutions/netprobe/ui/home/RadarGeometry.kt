package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.geometry.Offset
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
        MIN_RADIUS_FRACTION + (MAX_RADIUS_FRACTION - MIN_RADIUS_FRACTION) * sqrt(((i % MAX_BLIPS) + HALF) / MAX_BLIPS)
}

/** Stable, scattered blip position relative to the disc centre: a golden-angle spiral with a hashed jitter (see the Task 4 note). */
fun blipOffset(index: Int, radiusPx: Float): Offset {
    val angle = Math.toRadians((index * RadarGeometry.GOLDEN_ANGLE_DEG + RadarGeometry.jitterDeg(index)).toDouble())
    val d = RadarGeometry.radiusFraction(index) * radiusPx
    return Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat())
}
