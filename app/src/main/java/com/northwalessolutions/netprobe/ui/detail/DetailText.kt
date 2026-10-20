package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.SoftwareColumn
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.home.IdentityLine

private const val WORD_OPEN = "Open"
private const val WORD_CONNECT = "Connect"
private const val WORD_SHARE = "Open share"
private const val WORD_COPY = "Copy"
private const val COPY_IP_PORT = "Copy IP:port"
private val OPEN_SCHEMES = setOf("http", "https", "ftp")
private val CONNECT_SCHEMES = setOf("ssh", "telnet", "vnc", "rdp")
private val BROWSER_SCHEMES = setOf("http", "https")

/** One word per port row, keyed on the primary action's URI scheme; `ActionProvider` is untouched (spec 5.2). */
fun actionWord(uriScheme: String?): String = when (uriScheme?.lowercase()) {
    in OPEN_SCHEMES -> WORD_OPEN
    in CONNECT_SCHEMES -> WORD_CONNECT
    "smb" -> WORD_SHARE
    else -> WORD_COPY
}

/** Scheme of the primary action; null when no app handles the service (the provider returns no primary then). */
fun schemeOf(p: PortRow): String? = (p.primary as? DeviceAction.OpenUri)?.uri?.substringBefore("://")?.lowercase()

/** The row tap: the primary action, or copying "ip:port" when no app handles it. */
fun primaryAction(ip: String, p: PortRow): DeviceAction = p.primary ?: DeviceAction.Copy(COPY_IP_PORT, "$ip:${p.port}")

fun actionDescription(ip: String, p: PortRow): String {
    val scheme = schemeOf(p)
    return when (actionWord(scheme)) {
        WORD_OPEN -> if (scheme in BROWSER_SCHEMES) "Open port ${p.port} in browser" else "Open port ${p.port}"
        WORD_CONNECT -> "Connect to port ${p.port}"
        WORD_SHARE -> "Open share on port ${p.port}"
        else -> "Copy $ip:${p.port}"
    }
}

/** Sheet wording; null drops the "No app installed" entry exactly as the MVP row did. */
fun sheetLabel(action: DeviceAction): String? = when {
    action is DeviceAction.OpenUri && !action.hasHandler -> null
    action is DeviceAction.Copy && action.label == COPY_IP_PORT -> "Copy IP and port"
    else -> action.label
}

/** Long-press sheet rows: every action except the primary (that is the row tap) and the no-app entry. */
fun sheetActions(p: PortRow): List<Pair<String, DeviceAction>> =
    p.actions.filter { it != p.primary }.mapNotNull { a -> sheetLabel(a)?.let { it to a } }

fun versionLine(s: ServiceInfo): String? = listOfNotNull(s.product, s.version, s.detail).joinToString(" ").trim().ifEmpty { null }

/** "Synology DS220+  NAS"; the label is skipped when the identity already ends with it or the type is UNKNOWN (spec 5.1). */
fun identityWithType(identity: String, type: DeviceType): String? {
    val label = with(IdentityLine) { type.label }
    val id = identity.trim()
    return when {
        label.isEmpty() -> id.ifEmpty { null }
        id.isEmpty() -> label
        id.endsWith(label, ignoreCase = true) -> id
        else -> "$id  $label"
    }
}

fun detailProgress(p: ScanProgress?): String = p?.let { "${it.done} of ${it.total}, found ${it.open}" } ?: "starting"

/** "Runs Home Assistant" chips, in the stored rule-file order (spec 3.6). */
fun softwareLabels(h: HostEntity): List<String> = SoftwareColumn.decode(h.identSoftware).map(DetailCopy::runs)

/** "Set by you: type NAS, vendor Synology, model DS220+": only the set fields, in that order; the name is the title (spec 4.4). */
fun setByYouLine(h: HostEntity): String? {
    val parts = listOfNotNull(h.userType?.let { "type ${EditCopy.typeLabel(it)}" }, h.userVendor?.let { "vendor $it" }, h.userModel?.let { "model $it" })
    return if (parts.isEmpty()) null else DetailCopy.setByYou(parts.joinToString(", "))
}
