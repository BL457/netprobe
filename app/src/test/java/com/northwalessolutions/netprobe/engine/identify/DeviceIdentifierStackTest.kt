package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRules
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** Phase 3E spec 7: `stack` is a port field for identify_rules.json, device stacks make weight 60 rules, software stacks feed "Runs ...". */
class DeviceIdentifierStackTest {
    private val host = DiscoveredHost(InetAddress.getByName("10.0.3.9") as Inet4Address)
    private fun evidence(vararg ports: Pair<Int, ServiceInfo>) = HostEvidence(host, ports.map { it.first }.toSet(), mapOf(*ports), null)
    private fun svc(stack: String?, role: String?, probe: String? = null, product: String? = null) =
        ServiceInfo("HTTP", product, null, null, Confidence.CONFIRMED, stack = stack, stackRole = role, probe = probe)
    private fun rule(id: String, field: String, pattern: String, weight: Int, vendor: String? = null, model: String? = null, type: String? = null, software: String? = null) =
        Rule(id = id, field = field, pattern = pattern, vendor = vendor, model = model, type = type, weight = weight, software = software)

    @Test fun `stack is a port field so an identify rule can match on it`() {
        val id = DeviceIdentifier(listOf(rule("by-stack", "stack", "^Home Assistant$", 70, vendor = "Nabu Casa", type = "SERVER")))
        val r = id.identify(evidence(8123 to svc("Home Assistant", "software", "home-assistant")))
        assertEquals("Nabu Casa", r.vendor); assertEquals(DeviceType.SERVER, r.type); assertEquals(70, r.confidence)
        assertTrue(r.reasons.contains("port 8123 stack: Home Assistant"))
        val none = id.identify(evidence(8123 to svc("Grafana", "software", "grafana")))
        assertNull(none.vendor)
    }
    @Test fun `the onPorts limit applies to the stack field like the other port fields`() {
        val id = DeviceIdentifier(listOf(rule("limited", "stack", "Plex", 70, type = "MEDIA").copy(onPorts = listOf(32400))))
        assertEquals(DeviceType.UNKNOWN, id.identify(evidence(80 to svc("Plex Media Server", "software"))).type)
        assertEquals(DeviceType.MEDIA, id.identify(evidence(32400 to svc("Plex Media Server", "software"))).type)
    }

    private val stacks = HttpStackRules.load("""[
        {"id":"syno","all":[{"where":"title","pattern":"DiskStation"}],"stack":"Synology DSM","role":"device","vendor":"Synology","model":"DiskStation","type":"NAS","sample":"DiskStation"},
        {"id":"syno2","all":[{"where":"body","pattern":"SYNO"}],"stack":"Synology DSM","role":"device","vendor":"Other","model":"Other","type":"SERVER","sample":"SYNO"},
        {"id":"plex","all":[{"where":"title","pattern":"Plex"}],"stack":"Plex Media Server","role":"software","sample":"Plex"},
        {"id":"hue","all":[{"where":"body","pattern":"hue"}],"stack":"Philips Hue","role":"device","vendor":"Philips Hue","type":"HUB","sample":"hue"}
    ]""").rules
    private val generated = StackIdentity.rules(stacks)

    @Test fun `a device stack becomes one rule of weight 60 that sets vendor model and type`() {
        assertEquals(listOf("stack-syno", "stack-hue"), generated.map { it.id })
        val g = generated.first()
        assertEquals(60, g.weight); assertEquals("stack", g.field); assertEquals("Synology", g.vendor); assertEquals("DiskStation", g.model); assertEquals("NAS", g.type)
        val r = DeviceIdentifier(generated).identify(evidence(5000 to svc("Synology DSM", "device", "syno")))
        assertEquals("Synology", r.vendor); assertEquals("DiskStation", r.model); assertEquals(DeviceType.NAS, r.type); assertEquals(60, r.confidence)
        assertEquals("a HUB is an IoT box", DeviceType.IOT, DeviceIdentifier(generated).identify(evidence(80 to svc("Philips Hue", "device", "hue"))).type)
    }
    @Test fun `a stack name with regex characters matches only itself`() {
        val odd = StackIdentity.rules(HttpStackRules.load("""[{"id":"x","all":[{"where":"title","pattern":"x"}],"stack":"FRITZ!Box (7590)+","role":"device","type":"ROUTER","sample":"x"}]""").rules)
        assertEquals(DeviceType.ROUTER, DeviceIdentifier(odd).identify(evidence(80 to svc("FRITZ!Box (7590)+", "device"))).type)
        assertEquals(DeviceType.UNKNOWN, DeviceIdentifier(odd).identify(evidence(80 to svc("FRITZ!Box (7590)+x", "device"))).type)
    }
    @Test fun `a software stack makes no rule and feeds only the software slot, ties keep the first rule`() {
        assertTrue(generated.none { it.software != null })
        val identify = listOf(rule("first", "stack", "^Synology DSM$", 60, vendor = "FirstVendor", type = "NAS"))
        val r = DeviceIdentifier(identify + generated).identify(evidence(5000 to svc("Synology DSM", "device", "syno"), 32400 to svc("Plex Media Server", "software", "plex")))
        assertEquals("a tie at equal weight keeps the first in file order", "FirstVendor", r.vendor)
        assertEquals("the later generated rule still fills a slot nothing set", "DiskStation", r.model)
        assertEquals(listOf("Plex Media Server"), r.software)
    }
    @Test fun `software alone beside a platform leaves vendor model type and confidence untouched`() {
        val platform = listOf(rule("oui-ish", "service", "^HTTP$", 80, vendor = "Acme", type = "SERVER"))
        val r = DeviceIdentifier(platform + generated).identify(evidence(32400 to svc("Plex Media Server", "software", "plex")))
        assertEquals("Acme", r.vendor); assertEquals(DeviceType.SERVER, r.type); assertEquals(80, r.confidence); assertEquals(listOf("Plex Media Server"), r.software)
    }
    @Test fun `a host with only a software stack is no longer unknown and still sets no type`() {
        val r = DeviceIdentifier(generated).identify(evidence(32400 to svc("Plex Media Server", "software", "plex")))
        assertEquals(listOf("Plex Media Server"), r.software); assertEquals(DeviceType.UNKNOWN, r.type); assertEquals(0, r.confidence)
        assertEquals(listOf("Port 32400: Plex Media Server (plex)"), r.reasons)
    }
    @Test fun `why this identity lists the stack or the product with the probe id, ports ascending`() {
        val r = DeviceIdentifier(generated).identify(evidence(
            22 to ServiceInfo("SSH", "OpenSSH", "9.6", null, Confidence.CONFIRMED, probe = "ssh"),
            8123 to svc("Home Assistant", "software", "home-assistant", product = "nginx"),
            5000 to svc("Synology DSM", "device", "syno")))
        assertEquals(listOf("Port 22: OpenSSH (ssh)", "Port 5000: Synology DSM (syno)", "Port 8123: Home Assistant (home-assistant)"), r.reasons)
    }
    @Test fun `a product without a probe id and without a stack gets no why line, and a host with nothing to say stays unknown`() {
        val r = DeviceIdentifier(generated).identify(evidence(22 to ServiceInfo("SSH", "OpenSSH", "9.6", null, Confidence.CONFIRMED)))
        assertEquals(DeviceIdentity.UNKNOWN, r)
        val ssh = DeviceIdentifier(generated).identify(evidence(22 to ServiceInfo("SSH", "OpenSSH", "9.6", null, Confidence.CONFIRMED, probe = "ssh")))
        assertEquals("a probe id alone does not make an identity", DeviceIdentity.UNKNOWN, ssh)
    }
    @Test fun `a generated rule's own hit adds no second reason line`() {
        val r = DeviceIdentifier(generated).identify(evidence(5000 to svc("Synology DSM", "device", "syno")))
        assertEquals(listOf("Port 5000: Synology DSM (syno)"), r.reasons)
    }
    @Test fun `the shipped stack file makes the device rules the spec names and no rule for software`() {
        val shipped = HttpStackRules.load(javaClass.classLoader!!.getResource("http_stacks.json")!!.readText()).rules
        val rules = StackIdentity.rules(shipped)
        assertTrue(rules.all { it.weight == 60 && it.field == "stack" && it.software == null })
        val ids = IdentifyFileRules.rules
        val all = DeviceIdentifier(ids + rules)
        fun id(stack: String) = all.identify(evidence(80 to svc(stack, "device", "x")))
        assertEquals(DeviceType.CAMERA, id("Hikvision").type); assertEquals(DeviceType.PRINTER, id("Brother printer").type)
        assertEquals(DeviceType.ROUTER, id("FRITZ!Box").type); assertEquals("AVM", id("FRITZ!Box").vendor); assertEquals(DeviceType.NAS, id("QNAP QTS").type)
        assertEquals(DeviceType.IOT, id("Shelly").type); assertEquals(DeviceType.NAS, id("TrueNAS").type)
        assertTrue("no software stack makes a rule", rules.none { it.pattern!!.contains("Home Assistant") || it.pattern!!.contains("Plex") })
    }
}

private object IdentifyFileRules {
    val rules: List<Rule> by lazy { IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()) }
}
