package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 4.7, Review Focus 4: tool hints add evidence, read by the toolHint field, with reasons "toolHint: <hint>". */
class DeviceIdentifierToolHintTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private fun ev(hints: List<String>) = HostEvidence(DiscoveredHost(ip), emptySet(), emptyMap(), null, toolHints = hints)
    private fun rule(id: String, pattern: String, vendor: String? = null, model: String? = null, os: String? = null, type: String? = null, weight: Int = 90) =
        Rule(id = id, field = "toolHint", pattern = pattern, weight = weight, vendor = vendor, model = model, os = os, type = type)
    private fun one(r: Rule, hints: List<String>) = DeviceIdentifier(listOf(r)).identify(ev(hints))

    @Test fun `a synology framework hint sets vendor and type and names itself in the reason`() {
        val r = one(rule("hint-synology-dsm", "^http\\.framework=Synology DSM$", vendor = "Synology", type = "NAS"), listOf("http.framework=Synology DSM"))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type); assertEquals(listOf("toolHint: http.framework=Synology DSM"), r.reasons)
    }
    @Test fun `a non matching hint yields nothing`() {
        assertEquals(DeviceIdentity.UNKNOWN, one(rule("hint-pihole", "^http\\.framework=Pi-hole$", model = "Pi-hole", type = "SERVER"), listOf("http.framework=Something Else")))
    }
    @Test fun `tls and snmp hints match their fields`() {
        assertEquals("Ubiquiti", one(rule("hint-tls-ubiquiti", "^tls\\.issuer=.*(UniFi|Ubiquiti)", vendor = "Ubiquiti", type = "ROUTER", weight = 70), listOf("tls.issuer=UniFi CA")).vendor)
        assertEquals("MikroTik", one(rule("hint-snmp-mikrotik", "^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.14988\\.", vendor = "MikroTik", os = "RouterOS", type = "ROUTER"), listOf("snmp.sysObjectID=1.3.6.1.4.1.14988.1")).vendor)
    }
    @Test fun `every seeded hint rule fires on its own sample`() {
        val rules = IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()).filter { it.field == "toolHint" }
        assertEquals(14, rules.size)
        for (r in rules) {
            val got = DeviceIdentifier(listOf(r)).identify(ev(listOf(r.sample!!)))
            assertNotEquals("${r.id} did not fire on its sample", DeviceIdentity.UNKNOWN, got)
            assertEquals("${r.id} weight", r.weight, got.confidence)
        }
    }
}
