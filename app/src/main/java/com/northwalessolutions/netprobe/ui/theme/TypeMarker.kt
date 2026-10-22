package com.northwalessolutions.netprobe.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.TypeMarker
import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** The Settings choice (Director ruling R3), provided once at the root by MainActivity; tests override it. */
val LocalTypeMarker = compositionLocalOf { TypeMarker.STRIPE }

/** The icon marker's glyph per device type (R3). Every type has one. */
fun DeviceType.markerIcon(): ImageVector = when (this) {
    DeviceType.ROUTER -> Icons.Filled.Router
    DeviceType.NAS -> Icons.Filled.Storage
    DeviceType.MEDIA -> Icons.Filled.Tv
    DeviceType.IOT -> Icons.Filled.Lightbulb
    DeviceType.PHONE -> Icons.Filled.Smartphone
    DeviceType.COMPUTER -> Icons.Filled.Laptop
    DeviceType.SERVER -> Icons.Filled.Dns
    DeviceType.CAMERA -> Icons.Filled.Videocam
    DeviceType.PRINTER -> Icons.Filled.Print
    DeviceType.UNKNOWN -> Icons.Filled.HelpOutline
}

private val ICON_SIZE = 20.dp

/** The row's type marker: the colour stripe, or a 20 dp icon in the type colour, by the Settings choice. Stale rows dim both alike. */
@Composable
fun TypeMarkerView(type: DeviceType, color: Color, stale: Boolean, marker: TypeMarker = LocalTypeMarker.current) {
    val tint = color.copy(alpha = if (stale) STALE_EDGE_ALPHA else 1f)
    when (marker) {
        TypeMarker.STRIPE -> Box(Modifier.width(Dimens.edgeWidth).height(Dimens.edgeHeight).clip(RoundedCornerShape(Dimens.edgeRadius)).background(tint))
        TypeMarker.ICON -> Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) { Icon(type.markerIcon(), contentDescription = null, tint = tint, modifier = Modifier.size(ICON_SIZE)) }
    }
}
