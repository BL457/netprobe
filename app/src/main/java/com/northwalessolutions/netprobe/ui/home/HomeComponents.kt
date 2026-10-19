package com.northwalessolutions.netprobe.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport

private const val STALE_ALPHA = 0.5f

fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.ROUTER -> Icons.Default.Router; DeviceType.PHONE -> Icons.Default.Smartphone; DeviceType.COMPUTER -> Icons.Default.Computer
    DeviceType.PRINTER -> Icons.Default.Print; DeviceType.MEDIA -> Icons.Default.Tv; DeviceType.IOT -> Icons.Default.Sensors; DeviceType.NAS -> Icons.Default.Storage
    DeviceType.CAMERA -> Icons.Default.Videocam; DeviceType.SERVER -> Icons.Default.Dns; DeviceType.UNKNOWN -> Icons.Default.DeviceUnknown
}

fun HostStatus.label(): String = when (this) {
    HostStatus.UNMARKED -> "Unmarked"; HostStatus.INTERESTING -> "Interesting"; HostStatus.INVESTIGATED -> "Investigated"; HostStatus.IGNORED -> "Ignored"
}

private const val DNS_SHOWN = 2

/** Second panel line: address, gateway and the first two DNS servers (spec 2.1 item 7). */
fun networkDetailLine(env: NetworkEnv): String {
    val dns = env.dnsServers.take(DNS_SHOWN).mapNotNull { it.hostAddress }.joinToString(", ")
    val base = "${env.interfaceIp.hostAddress}/${env.prefixLength}  gateway ${env.gateway?.hostAddress ?: "none"}"
    return if (dns.isEmpty()) base else "$base  dns $dns"
}

@Composable
fun NetworkPanel(env: NetworkEnv?, networkName: String?, onRequestLocation: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (env == null) {
            Text("No network", style = MaterialTheme.typography.titleMedium)
            Text("Not connected to Wi-Fi or Ethernet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val transport = if (env.transport == Transport.WIFI) "Wi-Fi" else "Ethernet"
        // networkName is the SSID when Android reveals it (location permission), else the gateway; only the SSID is a name.
        val name = networkName?.takeIf { it == env.ssid } ?: transport
        val needsLocation = env.ssid == null && env.transport == Transport.WIFI
        if (needsLocation) {
            Text("Wi-Fi (tap to show the name, needs location)", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onRequestLocation).testTag("wifiNameHint"))
        } else {
            Text(if (name == transport) name else "$name · $transport", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(networkDetailLine(env), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FilterRow(selected: StatusFilter, onSelect: (StatusFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("filterRow").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusFilter.entries.forEach { f -> FilterChip(selected = f == selected, onClick = { onSelect(f) }, label = { Text(f.label) }) }
    }
}

fun rowSecondaryLine(r: HostRow): String {
    val base = if (r.displayName == r.ip) r.identityLine else "${r.ip}  ·  ${r.identityLine}"
    return if (r.otherSubnet != null) "$base, other subnet ${r.otherSubnet}" else base
}

@Composable
fun HostRowItem(r: HostRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).alpha(if (r.stale) STALE_ALPHA else 1f),
        leadingContent = { Icon(r.type.icon(), contentDescription = null) },
        headlineContent = { Text(r.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(rowSecondaryLine(r), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (r.isGateway) Text("Gateway", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (r.openPorts > 0) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${r.openPorts} open", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (r.status != HostStatus.UNMARKED) {
                    AssistChip(onClick = onClick, label = { Text(r.status.label(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                }
            }
        },
    )
}
