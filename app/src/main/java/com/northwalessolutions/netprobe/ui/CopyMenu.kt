package com.northwalessolutions.netprobe.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.home.IdentityLine
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors

/** Every string of the long-press copy menus (Director ruling R4). Templates live in CopyMenuTest. */
object CopyMenuCopy {
    const val COPY_IP = "Copy IP"
    const val COPY_MAC = "Copy MAC"
    const val COPY_NAME = "Copy name"
    const val COPY_DETAILS = "Copy details"
    const val COPY_ADDRESS = "Copy address"
    const val COPIED = "Copied"
    const val NAME = "Name"
    const val IP = "IP"
    const val MAC = "MAC"
    const val VENDOR = "Vendor"
    const val MODEL = "Model"
    const val TYPE = "Type"
    const val OPEN_PORTS = "Open ports"
    const val UNKNOWN_TYPE = "Unknown"
    fun line(label: String, value: String) = "$label: $value"
}

enum class CopyKind(val label: String) {
    IP(CopyMenuCopy.COPY_IP), MAC(CopyMenuCopy.COPY_MAC), NAME(CopyMenuCopy.COPY_NAME), DETAILS(CopyMenuCopy.COPY_DETAILS), ADDRESS(CopyMenuCopy.COPY_ADDRESS)
}

/** What a device menu offers and the text each item copies; pure, so the Compose layer and the tests share it. */
object DeviceCopy {
    /** The device menu's items in order; "Copy MAC" is hidden when the device has no MAC. */
    fun items(mac: String?): List<CopyKind> = buildList {
        add(CopyKind.IP)
        if (!mac.isNullOrBlank()) add(CopyKind.MAC)
        add(CopyKind.NAME)
        add(CopyKind.DETAILS)
    }

    /** Name, IP, MAC, vendor, model, type and open ports as one block of "Label: value" lines; a missing value drops its line. */
    fun details(name: String, ip: String, mac: String?, vendor: String?, model: String?, type: DeviceType, openPorts: List<Int>): String {
        val typeLabel = with(IdentityLine) { type.label }.ifEmpty { CopyMenuCopy.UNKNOWN_TYPE }
        return listOfNotNull(
            CopyMenuCopy.line(CopyMenuCopy.NAME, name),
            CopyMenuCopy.line(CopyMenuCopy.IP, ip),
            mac?.takeIf { it.isNotBlank() }?.let { CopyMenuCopy.line(CopyMenuCopy.MAC, it) },
            vendor?.takeIf { it.isNotBlank() }?.let { CopyMenuCopy.line(CopyMenuCopy.VENDOR, it) },
            model?.takeIf { it.isNotBlank() }?.let { CopyMenuCopy.line(CopyMenuCopy.MODEL, it) },
            CopyMenuCopy.line(CopyMenuCopy.TYPE, typeLabel),
            openPorts.takeIf { it.isNotEmpty() }?.let { CopyMenuCopy.line(CopyMenuCopy.OPEN_PORTS, it.sorted().joinToString(", ")) },
        ).joinToString("\n")
    }

    /** The text a pick copies; [details] is built lazily because Home must read the ports first. */
    fun text(kind: CopyKind, name: String, ip: String, mac: String?, details: () -> String): String = when (kind) {
        CopyKind.IP -> ip
        CopyKind.MAC -> mac.orEmpty()
        CopyKind.NAME -> name
        CopyKind.DETAILS -> details()
        CopyKind.ADDRESS -> ip
    }

    fun address(ip: String, port: Int) = "$ip:$port"
}

/** The long-press menu: one dropdown item per entry, tagged "copy:<KIND>". Stateless; the caller owns [expanded]. */
@Composable
fun CopyMenu(expanded: Boolean, items: List<CopyKind>, onPick: (CopyKind) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded, onDismiss, containerColor = NetProbeColors.surface) {
        items.forEach { kind ->
            DropdownMenuItem(text = { Text(kind.label) }, onClick = { onDismiss(); onPick(kind) }, modifier = Modifier.testTag("copy:${kind.name}"))
        }
    }
}
