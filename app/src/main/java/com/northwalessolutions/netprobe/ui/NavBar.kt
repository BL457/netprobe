package com.northwalessolutions.netprobe.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build as FilledBuild
import androidx.compose.material.icons.filled.MonitorHeart as FilledMonitorHeart
import androidx.compose.material.icons.filled.Radar as FilledRadar
import androidx.compose.material.icons.filled.Settings as FilledSettings
import androidx.compose.material.icons.outlined.Build as OutlinedBuild
import androidx.compose.material.icons.outlined.MonitorHeart as OutlinedMonitorHeart
import androidx.compose.material.icons.outlined.Radar as OutlinedRadar
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/**
 * The four plain-word icons shown to the Director on the device before the merge (standing rule: icons need his approval): radar dish,
 * pulse line, wrench, gear; outlined when unselected and filled when selected. If a glyph does not resolve, take the nearest one from the
 * same package; never add a dependency.
 */
object TabIcons {
    fun of(tab: Tab, selected: Boolean): ImageVector = when (tab) {
        Tab.DEVICES -> if (selected) Icons.Filled.FilledRadar else Icons.Outlined.OutlinedRadar
        Tab.SCANS -> if (selected) Icons.Filled.FilledMonitorHeart else Icons.Outlined.OutlinedMonitorHeart
        Tab.TOOLS -> if (selected) Icons.Filled.FilledBuild else Icons.Outlined.OutlinedBuild
        Tab.SETTINGS -> if (selected) Icons.Filled.FilledSettings else Icons.Outlined.OutlinedSettings
    }
}

/** The bar itself (3D spec 1.2): labels always shown, a 1 dp surface rule along the top edge, the running count on the Scans icon. */
@Composable
fun NetProbeBar(selected: Tab?, scansRunning: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = NetProbeColors.background,
        modifier = Modifier.testTag("navBar").drawBehind { drawLine(NetProbeColors.surface, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val withBadge = tab == Tab.SCANS && scansRunning > 0
            NavigationBarItem(selected = isSelected, onClick = { onSelect(tab) }, alwaysShowLabel = true,
                icon = {
                    if (withBadge) BadgedBox(badge = {
                        Badge(containerColor = NetProbeColors.signal, contentColor = NetProbeColors.background) {
                            Text(NavCopy.badgeText(scansRunning), style = NetProbeText.caption, modifier = Modifier.testTag("scansBadge"))
                        }
                    }) { Icon(TabIcons.of(tab, isSelected), contentDescription = null) }
                    else Icon(TabIcons.of(tab, isSelected), contentDescription = null)
                },
                label = { Text(NavCopy.label(tab), style = NetProbeText.chip, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = NetProbeColors.text, selectedTextColor = NetProbeColors.text,
                    indicatorColor = NetProbeColors.surface, unselectedIconColor = NetProbeColors.textMuted, unselectedTextColor = NetProbeColors.textMuted),
                modifier = Modifier.testTag("tab:${tab.root}").then(
                    if (withBadge) Modifier.semantics { contentDescription = NavCopy.scansBadge(scansRunning) } else Modifier))
        }
    }
}
