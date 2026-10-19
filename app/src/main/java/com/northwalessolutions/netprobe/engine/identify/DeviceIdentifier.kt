package com.northwalessolutions.netprobe.engine.identify

class DeviceIdentifier(private val rules: List<Rule>) {
    private class Slot(var value: String? = null, var weight: Int = -1)
    private fun group1Of(m: MatchResult): String? = if (m.groups.size > 1) m.groups[1]?.value else null

    fun identify(e: HostEvidence): DeviceIdentity {
        val vendor = Slot(); val model = Slot(); val os = Slot(); val type = Slot()
        val reasons = ArrayList<String>(); var confidence = 0
        fun set(slot: Slot, v: String?, w: Int) { if (v != null && w > slot.weight) { slot.value = v; slot.weight = w } }
        fun apply(r: Rule, matched: String, group1: String?, txtValue: String?) {
            fun sub(s: String?) = s?.replace("\$1", group1 ?: "")?.replace("\$txt", txtValue ?: "")?.trim()?.takeIf { it.isNotEmpty() }
            set(vendor, sub(r.vendor), r.weight); set(model, sub(r.model), r.weight); set(os, sub(r.os), r.weight); set(type, r.type, r.weight)
            reasons += "${r.field}: ${matched.take(80)}"; confidence = maxOf(confidence, r.weight)
        }
        val h = e.host
        // Direct extraction, weight 100.
        if (h.upnpManufacturer != null || h.upnpModel != null) {
            set(vendor, h.upnpManufacturer, 100); set(model, h.upnpModel, 100); reasons += "upnp: ${listOfNotNull(h.upnpManufacturer, h.upnpModel).joinToString(" ")}"; confidence = 100
        }
        val names = listOfNotNull(h.mdnsName, h.netbiosName, h.upnpFriendlyName, h.reverseDnsName) + h.mdnsInstanceNames
        val txtStrings = h.mdnsTxt.flatMap { (t, kv) -> kv.map { (k, v) -> Triple("$t:$k=$v", k, v) } }
        for (r in rules) {
            val re = r.pattern?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            fun matchAny(cands: List<String>) = cands.firstNotNullOfOrNull { c -> re?.find(c)?.let { m -> c to group1Of(m) } }
            when (r.field) {
                "mdnsType" -> matchAny(h.mdnsServiceTypes.toList())?.let { (c, g) -> apply(r, c, g, null) }
                "mdnsTxt" -> txtStrings.firstNotNullOfOrNull { (s, _, v) -> re?.find(s)?.let { m -> Triple(s, group1Of(m), v) } }?.let { (s, g, v) -> apply(r, s, g, v) }
                "upnpDeviceType" -> matchAny(listOfNotNull(h.upnpDeviceType))?.let { (c, g) -> apply(r, c, g, null) }
                "snmpSysDescr" -> matchAny(listOfNotNull(h.snmpSysDescr))?.let { (c, g) -> apply(r, c, g, null) }
                "httpTitle" -> matchAny(listOfNotNull(h.httpTitle))?.let { (c, g) -> apply(r, c, g, null) }
                "httpRealm" -> matchAny(listOfNotNull(h.httpAuthRealm))?.let { (c, g) -> apply(r, c, g, null) }
                "httpServer" -> matchAny(listOfNotNull(h.httpServer))?.let { (c, g) -> apply(r, c, g, null) }
                "hostname" -> matchAny(names)?.let { (c, g) -> apply(r, c, g, null) }
                "netbiosName" -> matchAny(listOfNotNull(h.netbiosName))?.let { (c, g) -> apply(r, c, g, null) }
                "sshBanner" -> matchAny(e.services[22]?.let { listOfNotNull(it.product, it.detail) }.orEmpty())?.let { (c, g) -> apply(r, c, g, null) }
                "ports" -> if (r.ports != null && e.openPorts.containsAll(r.ports)) apply(r, r.ports.joinToString(","), null, null)
                "anyPort" -> r.anyPort?.firstOrNull { it in e.openPorts }?.let { apply(r, it.toString(), null, null) }
                "gateway" -> if (h.isGateway) apply(r, "default gateway", null, null)
                "oui" -> if (e.ouiVendor != null) apply(r.copy(vendor = e.ouiVendor), e.ouiVendor, null, null)
            }
        }
        if (reasons.isEmpty()) return DeviceIdentity.UNKNOWN
        val t = type.value?.let { runCatching { DeviceType.valueOf(it) }.getOrNull() } ?: DeviceType.UNKNOWN
        return DeviceIdentity(vendor.value, model.value, os.value, t, confidence, reasons.distinct())
    }
}
