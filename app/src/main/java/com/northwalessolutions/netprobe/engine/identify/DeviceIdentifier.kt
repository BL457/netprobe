package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.serviceid.Confidence

class DeviceIdentifier(private val rules: List<Rule>) {
    private class Slot(var value: String? = null, var weight: Int = -1)
    /** A matched candidate: the text the rule saw, the port it came from (null for discovery-time evidence) and its groups. */
    private class Hit(val text: String, val port: Int?, val group1: String?, val txtValue: String? = null)
    private companion object {
        const val MAX_VALUE_LEN = 80
        const val MAX_REASON_LEN = 80
        val CANONICAL_VENDORS = mapOf(
            "hp" to "HP", "hewlett-packard" to "HP", "epson" to "Epson", "canon" to "Canon", "brother" to "Brother",
            "lexmark" to "Lexmark", "kyocera" to "Kyocera", "xerox" to "Xerox",
            // Router vendors, so "$1" from a page title or realm is spelled one way (spec 3.2, Gruff m10).
            "tp-link" to "TP-Link", "netgear" to "NETGEAR", "asus" to "ASUS", "linksys" to "Linksys", "zyxel" to "Zyxel",
            "huawei" to "Huawei", "zte" to "ZTE", "sagemcom" to "Sagemcom", "technicolor" to "Technicolor", "tenda" to "Tenda",
        )
        /** Fields matched against stored port findings (spec 3.2); the three http* fields also read discovery evidence first. */
        val PORT_FIELDS = setOf("httpTitle", "httpServer", "httpRealm", "sshBanner", "poweredBy", "certName", "certOrg", "portBanner", "service")
    }
    private fun canonicalVendor(v: String?): String? = v?.let { CANONICAL_VENDORS[it.lowercase()] ?: it }
    private fun group1Of(m: MatchResult): String? = if (m.groups.size > 1) m.groups[1]?.value else null

    /** Discovery-time candidates of a port field; none when the rule is limited to ports. */
    private fun hostCandidates(r: Rule, e: HostEvidence): List<String> {
        if (r.onPorts != null) return emptyList()
        val h = e.host
        return when (r.field) {
            "httpTitle" -> listOfNotNull(h.httpTitle)
            "httpServer" -> listOfNotNull(h.httpServer)
            "httpRealm" -> listOfNotNull(h.httpAuthRealm)
            else -> emptyList()
        }
    }

    /** Port candidates of a field, ports ascending, limited to [Rule.onPorts] when it is set (spec 3.2 table). */
    private fun portCandidates(r: Rule, e: HostEvidence): List<Pair<Int, String>> =
        e.services.entries.sortedBy { it.key }.filter { r.onPorts == null || it.key in r.onPorts }.flatMap { (port, s) ->
            val values = when (r.field) {
                "httpTitle" -> listOfNotNull(s.title)
                "httpServer" -> listOfNotNull(s.server)
                "httpRealm" -> listOfNotNull(s.realm)
                "sshBanner" -> if (s.name.equals("SSH", ignoreCase = true)) listOfNotNull(s.product, s.detail) else emptyList()
                "poweredBy" -> listOfNotNull(s.poweredBy)
                "certName" -> listOfNotNull(s.certCn)
                "certOrg" -> listOfNotNull(s.certOrg)
                "portBanner" -> listOf(listOfNotNull(s.product, s.version, s.detail).joinToString(" ")).filter { it.isNotBlank() }
                "service" -> if (s.confidence == Confidence.CONFIRMED) listOf(s.name) else emptyList()
                else -> emptyList()
            }
            values.map { port to it }
        }

    fun identify(e: HostEvidence): DeviceIdentity {
        val vendor = Slot(); val model = Slot(); val os = Slot(); val type = Slot()
        val reasons = ArrayList<String>(); val software = LinkedHashSet<String>(); var confidence = 0
        fun set(slot: Slot, v: String?, w: Int) { if (v != null && w > slot.weight) { slot.value = v; slot.weight = w } }
        fun apply(r: Rule, hit: Hit) {
            fun sub(s: String?) = s?.replace("\$1", hit.group1 ?: "")?.replace("\$txt", hit.txtValue ?: "")?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
            set(vendor, canonicalVendor(sub(r.vendor)), r.weight); set(model, sub(r.model), r.weight); set(os, sub(r.os), r.weight); set(type, r.type, r.weight)
            r.software?.let { software += it }
            val where = hit.port?.let { "port $it " }.orEmpty()
            reasons += "$where${r.field}: ${hit.text.take(MAX_REASON_LEN)}"; confidence = maxOf(confidence, r.weight)
        }
        val h = e.host
        // Direct extraction, weight 100.
        val upnpMaker = h.upnpManufacturer?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
        val upnpModel = h.upnpModel?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
        if (upnpMaker != null || upnpModel != null) {
            set(vendor, upnpMaker, 100); set(model, upnpModel, 100); reasons += "upnp: ${listOfNotNull(upnpMaker, upnpModel).joinToString(" ")}"; confidence = 100
        }
        val names = listOfNotNull(h.mdnsName, h.netbiosName, h.upnpFriendlyName, h.reverseDnsName) + h.mdnsInstanceNames
        val txtStrings = h.mdnsTxt.flatMap { (t, kv) -> kv.map { (k, v) -> Triple("$t:$k=$v", k, v) } }
        for (r in rules) {
            val re = r.pattern?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            fun first(cands: List<String>): Hit? = cands.firstNotNullOfOrNull { c -> re?.find(c)?.let { m -> Hit(c, null, group1Of(m)) } }
            val hit: Hit? = when (r.field) {
                in PORT_FIELDS -> first(hostCandidates(r, e))
                    ?: portCandidates(r, e).firstNotNullOfOrNull { (p, c) -> re?.find(c)?.let { m -> Hit(c, p, group1Of(m)) } }
                "mdnsType" -> first(h.mdnsServiceTypes.toList())
                "mdnsTxt" -> txtStrings.firstNotNullOfOrNull { (s, _, v) -> re?.find(s)?.let { m -> Hit(s, null, group1Of(m), v) } }
                "upnpManufacturer" -> first(listOfNotNull(h.upnpManufacturer))
                "upnpDeviceType" -> first(listOfNotNull(h.upnpDeviceType))
                "snmpSysDescr" -> first(listOfNotNull(h.snmpSysDescr))
                "hostname" -> first(names)
                "netbiosName" -> first(listOfNotNull(h.netbiosName))
                "ports" -> if (r.ports != null && e.openPorts.containsAll(r.ports)) Hit(r.ports.joinToString(","), null, null) else null
                "anyPort" -> r.anyPort?.firstOrNull { it in e.openPorts }?.let { Hit(it.toString(), null, null) }
                "gateway" -> if (h.isGateway) Hit("default gateway", null, null) else null
                else -> null
            }
            if (r.field == "oui") { if (e.ouiVendor != null) apply(r.copy(vendor = e.ouiVendor), Hit(e.ouiVendor, null, null)) }
            else hit?.let { apply(r, it) }
        }
        if (reasons.isEmpty()) return DeviceIdentity.UNKNOWN
        val t = type.value?.let { runCatching { DeviceType.valueOf(it) }.getOrNull() } ?: DeviceType.UNKNOWN
        return DeviceIdentity(vendor.value, model.value, os.value, t, confidence, reasons.distinct(), software.toList())
    }
}
