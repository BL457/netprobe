package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** Radar palette (Radar spec 2.1). The token `surface` is NOT the Material role `surface`: see the role table in Theme.kt. */
object NetProbeColors {
    val background = Color(0xFF16202B)
    val surface = Color(0xFF233447)
    val rowDivider = Color(0xFF1D2A38)
    val borderSubtle = Color(0xFF2A3E54)
    val border = Color(0xFF3B5368)
    val text = Color(0xFFE6EDF3)
    val textSecondary = Color(0xFFB9C7D4)
    val textMuted = Color(0xFF8FA3B5)
    val signal = Color(0xFF35D4C8)
    val mark = Color(0xFFFFD56B)
    val error = Color(0xFFFF8A8A)
    /** Plain fill under the radar count; the arm is masked out of this centre (spec 3.3). */
    val discCentre = Color(0xFF1B2836)
    /** "High" severity label in the vulnerability sheet (spec 5.3). */
    val severityHigh = Color(0xFFFFB86B)
}

/** Type marker colours: ten hues that are easy to tell apart on the dark background (Director ruling R3, 2026-10-22; replaces spec 2.1). */
object EdgeColors {
    val router = Color(0xFF2DD4BF)
    val nas = Color(0xFF60A5FA)
    val media = Color(0xFFC084FC)
    val iot = Color(0xFFFBBF24)
    val phone = Color(0xFF7DD3FC)
    val computer = Color(0xFFA3E635)
    val server = Color(0xFFFB7185)
    val camera = Color(0xFFEF4444)
    val printer = Color(0xFFD6D3D1)
    val unknown = Color(0xFF475569)
}

/** Gateway always teal (D15: any ROUTER is teal too). For a gateway-only teal, return #5FB8B0 for non-gateway routers here. */
fun DeviceType.edgeColor(isGateway: Boolean = false): Color = if (isGateway) EdgeColors.router else when (this) {
    DeviceType.ROUTER -> EdgeColors.router; DeviceType.NAS -> EdgeColors.nas; DeviceType.MEDIA -> EdgeColors.media
    DeviceType.IOT -> EdgeColors.iot; DeviceType.PHONE -> EdgeColors.phone; DeviceType.COMPUTER -> EdgeColors.computer
    DeviceType.SERVER -> EdgeColors.server; DeviceType.CAMERA -> EdgeColors.camera; DeviceType.PRINTER -> EdgeColors.printer
    DeviceType.UNKNOWN -> EdgeColors.unknown
}

/** Stale edge alpha: 0.8, not the spec's 0.5, so every stale marker keeps 3:1 against the background; the ruled camera red needs 0.77 (ContrastTest). */
const val STALE_EDGE_ALPHA = 0.8f
/** Stale name alpha (spec 2.1); ContrastTest keeps it at or above 4.5:1. */
const val STALE_NAME_ALPHA = 0.6f
/** Disabled controls and the extended-discovery rows while the master switch is off. */
const val DISABLED_ALPHA = 0.5f

/** Spacing and sizes from the mockups (spec 2.3, 3). */
object Dimens {
    val gutter = 18.dp
    val rowStart = 14.dp
    val edgeWidth = 4.dp
    val edgeHeight = 34.dp
    val edgeRadius = 2.dp
    val rowGap = 12.dp
    val rowVertical = 9.dp
    val touch = 48.dp
    val ringDiameter = 56.dp
    val ringTarget = 64.dp
    val discDiameter = 172.dp
    val heroButton = 84.dp
    val discInnerRing1 = 28.dp
    val discInnerRing2 = 58.dp
    val discCentreMask = 40.dp
    /** The arm cut-out diameter (2 x 40 dp), so the count always sits on plain fill (spec 3.3). */
    val countMaxWidth = 80.dp
    val blip = 6.dp
    val blipGlow = 8.dp
}
