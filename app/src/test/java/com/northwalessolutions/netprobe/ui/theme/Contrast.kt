package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2.x relative luminance and contrast ratio, plus sRGB-space alpha blending as Android composites it. */
object Contrast {
    private fun channel(c: Float): Double { val v = c.toDouble(); return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    fun over(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha), green = fg.green * alpha + bg.green * (1 - alpha), blue = fg.blue * alpha + bg.blue * (1 - alpha))
}
