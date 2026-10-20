package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.northwalessolutions.netprobe.R

@OptIn(ExperimentalTextApi::class)
private fun plex(weight: Int, italic: Boolean = false) = Font(
    resId = if (italic) R.font.ibm_plex_sans_italic else R.font.ibm_plex_sans,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Two variable TTFs (wdth, wght); weights come from the wght axis (spec 2.2). The italic file is used so nothing synthesises a slant. */
val PlexSans = FontFamily(plex(400), plex(500), plex(600), plex(400, italic = true), plex(500, italic = true))

private fun style(size: Int, weight: Int, line: Int, tnum: Boolean = false) = TextStyle(
    fontFamily = PlexSans, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = 0.sp,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/** The Radar type scale (spec 2.2). Colour is set by the component, never by the style. */
object NetProbeText {
    val title = style(20, 600, 26)
    val rowName = style(14, 500, 19)
    val summary = style(15, 500, 20)
    val secondary = style(13, 400, 18)
    val secondaryItalic = secondary.copy(fontStyle = FontStyle.Italic)
    val data = style(12, 400, 16, tnum = true)
    /** Counts share the data metrics and tabular figures. */
    val count = style(12, 400, 16, tnum = true)
    val chip = style(12, 400, 16)
    val action = style(12, 500, 16)
    val scanPill = style(13, 600, 18)
    val port = style(15, 600, 20, tnum = true)
    val caption = style(11, 400, 15)
    val groupHeader = caption.copy(letterSpacing = 0.02.em)
    val radarCount = style(30, 600, 36, tnum = true)
    val radarLabel = style(11, 400, 15)
    val ringLabel = style(12, 600, 16)
    val heroButton = style(16, 600, 22)
}

/** Every Material slot is overridden so no stock style leaks through. */
val NetProbeTypography = Typography(
    displayLarge = NetProbeText.title, displayMedium = NetProbeText.title, displaySmall = NetProbeText.title,
    headlineLarge = NetProbeText.title, headlineMedium = NetProbeText.title, headlineSmall = NetProbeText.title,
    titleLarge = NetProbeText.title, titleMedium = NetProbeText.rowName, titleSmall = NetProbeText.rowName,
    bodyLarge = NetProbeText.secondary, bodyMedium = NetProbeText.secondary, bodySmall = NetProbeText.data,
    labelLarge = NetProbeText.action, labelMedium = NetProbeText.chip, labelSmall = NetProbeText.caption,
)

/** Binding role table (spec 2.1): Material `surface` is the token `background`; containers are the token `surface`. */
private val RadarColorScheme = darkColorScheme(
    primary = NetProbeColors.signal, onPrimary = NetProbeColors.background,
    primaryContainer = NetProbeColors.surface, onPrimaryContainer = NetProbeColors.text,
    secondary = NetProbeColors.signal, onSecondary = NetProbeColors.background,
    secondaryContainer = NetProbeColors.surface, onSecondaryContainer = NetProbeColors.text,
    tertiary = NetProbeColors.mark, onTertiary = NetProbeColors.background,
    background = NetProbeColors.background, onBackground = NetProbeColors.text,
    surface = NetProbeColors.background, onSurface = NetProbeColors.text,
    surfaceVariant = NetProbeColors.surface, onSurfaceVariant = NetProbeColors.textSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = NetProbeColors.surface, surfaceContainerLow = NetProbeColors.surface, surfaceContainer = NetProbeColors.surface,
    surfaceContainerHigh = NetProbeColors.surface, surfaceContainerHighest = NetProbeColors.surface,
    outline = NetProbeColors.border, outlineVariant = NetProbeColors.borderSubtle,
    error = NetProbeColors.error, onError = NetProbeColors.background,
)

private val NetProbeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp),
)

/** Dark Radar look always; light mode and dynamic colour are out of scope (D1, D9). */
@Composable
fun NetProbeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(colorScheme = RadarColorScheme, typography = NetProbeTypography, shapes = NetProbeShapes, content = content)
    }
}
