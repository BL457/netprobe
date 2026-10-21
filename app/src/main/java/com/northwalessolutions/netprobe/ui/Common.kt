package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** Centred placeholder for an empty list area (spec 4.4); [pad] is the Scaffold content padding when the caller has one. */
@Composable
fun EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp), line: String? = null) {
    Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = NetProbeText.secondary, textAlign = TextAlign.Center, color = NetProbeColors.textMuted)
            // The optional second line (Scans: what shows here); Home's callers pass nothing and look as before.
            line?.let { Text(it, style = NetProbeText.data, textAlign = TextAlign.Center, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
