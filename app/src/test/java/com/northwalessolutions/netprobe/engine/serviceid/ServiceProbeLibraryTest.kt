package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The hello library of `service_probes.json`: every entry is well formed, the counts only go up, and every probe added in the
 * library build-out has one response fixture (`service_probe_fixtures.json`, written from the protocol specifications) that its
 * rules identify, and that no unrelated reply is mistaken for.
 */
class ServiceProbeLibraryTest {
    private fun file(dir: String, name: String): File = listOf("src/$dir", "app/src/$dir").map { File("$it/$name") }.first { it.exists() }
    private val probeText = file("main/res/raw", "service_probes.json").readText()
    private val loaded = ServiceProbeRules.load(probeText)
    private val probes = loaded.probes
    private val fixtures: List<JsonObject> = (Json.parseToJsonElement(file("test/resources", "service_probe_fixtures.json").readText()) as JsonArray).map { it as JsonObject }

    /** The 31 entries that existed before the library build-out, in file order; they must stay where they are. */
    private val originalIds = listOf("null", "tls", "http-head", "http-get", "rtsp-options", "rdp-x224", "ssh", "smtp", "ftp", "pop3", "imap", "telnet",
        "vnc-rfb", "mysql", "nats", "rsync", "x11", "git-daemon", "http-null", "memcached-version", "redis-info", "zookeeper-ruok", "docker-engine",
        "sip-options", "smb2-negotiate", "postgresql-startup", "mongodb-ismaster", "mqtt-connect", "amqp-header", "modbus-device-id", "ipp-head")

    private fun str(o: JsonObject, k: String): String = (o[k] as JsonPrimitive).contentOrNull ?: error("$k is null")
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    /** Careful hellos with a side effect on the target: no hint, so only the unsafe-hellos switch ever sends them; their usual ports are a commonPorts note. */
    private val behindTheSwitch = setOf("source-rcon-auth", "cvs-pserver", "irc-register", "mysql-quit-early", "bgp-open")
    private val net = FakeNetwork()
    private val newProbes get() = probes.filter { it.id !in originalIds }

    @Test fun `every entry is well formed`() {
        assertTrue("skipped: ${loaded.skipped.map { it.id + ": " + it.reason }}", loaded.skipped.isEmpty())
        val rawIds = (Json.parseToJsonElement(probeText) as JsonArray).map { str(it as JsonObject, "id") }
        assertEquals("ids are unique", rawIds.size, rawIds.toSet().size)
        assertEquals(rawIds, probes.map { it.id })
        for (p in probes) {
            assertTrue("${p.id}: id shape", Regex("[a-z0-9][a-z0-9-]*").matches(p.id))
            assertTrue("${p.id}: at least one match rule", p.matches.isNotEmpty())
            assertTrue("${p.id}: ports in range", p.ports.all { it in 1..65535 } && p.ports.size == p.ports.toSet().size)
            assertTrue("${p.id}: rarity ${p.rarity}", p.rarity in 1..9)
            assertTrue("${p.id}: send is bytes (one char per byte) and within the cap", p.sendTemplate.all { it.code in 0..255 } && p.sendTemplate.length <= ServiceProbeRules.MAX_SEND)
            val sent = String(p.sendBytes("10.0.3.5", 1234, "ABCDEFGH"), Charsets.ISO_8859_1)
            assertFalse("${p.id}: a placeholder survived", "{ip}" in sent || "{port}" in sent || "{rand}" in sent)
            if (p.sendsBytes && p.id !in behindTheSwitch) assertTrue("${p.id}: a hello that sends bytes names the ports it is for", p.ports.isNotEmpty())
            if (!p.sendsBytes && p.builtin == null) assertTrue("${p.id}: a banner matcher takes the null read", p.matchesNullReply)
            assertTrue("${p.id}: has a sample and a negative sample", p.samples.isNotEmpty() && p.negativeSamples.isNotEmpty())
        }
    }
    @Test fun `the original entries and their order are untouched`() {
        assertEquals(originalIds, probes.take(originalIds.size).map { it.id })
    }
    @Test fun `the library keeps its size`() {
        val sendingSafe = probes.count { it.sendsBytes && it.safety == ProbeSafety.SAFE }
        val sendingCareful = probes.count { it.sendsBytes && it.safety == ProbeSafety.CAREFUL }
        assertTrue("entries ${probes.size}", probes.size >= 151)
        assertTrue("safe hellos that send bytes: $sendingSafe", sendingSafe >= 102)
        assertTrue("careful hellos: $sendingCareful", sendingCareful >= 24)
        assertTrue("new entries ${newProbes.size}", newProbes.size >= 120)
    }
    @Test fun `safety follows the rules of the ladder`() {
        for (p in probes.filter { it.sendsBytes }) {
            if (p.safety == ProbeSafety.SAFE) assertTrue("${p.id}: a safe hello is rarity 1 to 4", p.rarity <= 4)
            else assertTrue("${p.id}: a careful hello is rarity 5 or more", p.rarity >= 5)
            p.ports.filter { it in ServiceProbes.INDUSTRIAL }.forEach { assertEquals("${p.id} is hinted on fieldbus port $it", ProbeSafety.CAREFUL, p.safety) }
            p.ports.filter { it in ServiceProbes.HINT_ONLY }.forEach { assertEquals("only the IPP HEAD may be hinted on $it", "ipp-head", p.id) }
        }
        listOf("s7-cotp", "enip-listidentity", "dnp3-link-status", "iec104-testfr", "jetdirect-pjl-id", "openvpn-tcp-reset", "iscsi-login", "bgp-open",
            "source-rcon-auth", "cvs-pserver", "irc-register", "mysql-quit-early", "smb1-negotiate", "mongodb-opquery", "mqtt-v31", "mqtt-v5")
            .forEach { id -> assertEquals(id, ProbeSafety.CAREFUL, probes.first { it.id == id }.safety) }
    }
    @Test fun `every new probe has exactly one fixture and the fixture's request is the probe's request`() {
        assertEquals(newProbes.map { it.id }.sorted(), fixtures.map { str(it, "id") }.sorted())
        for (f in fixtures) {
            val p = probes.first { it.id == str(f, "id") }
            assertTrue("${p.id}: the fixture says which field the rule keys on", str(f, "keysOn").length > 8)
            assertArrayEquals("${p.id}: request bytes", hex(str(f, "sendHex")), bytes(p.sendTemplate))
        }
    }
    @Test fun `each fixture reply is identified with the expected name, product and version`() {
        for (f in fixtures) {
            val p = probes.first { it.id == str(f, "id") }
            val hit = p.match(hex(str(f, "sampleHex")))
            assertNotNull("${p.id} keys on: ${str(f, "keysOn")}", hit)
            assertEquals("${p.id} service", str(f, "service"), hit!!.service)
            assertEquals("${p.id} product", (f["product"] as JsonPrimitive).contentOrNull, hit.product)
            assertEquals("${p.id} version", (f["version"] as JsonPrimitive).contentOrNull, hit.version)
        }
    }
    @Test fun `an unrelated reply is not identified`() {
        val unrelated = listOf(
            "HTTP/1.1 404 Not Found\r\nServer: nginx\r\nContent-Length: 0\r\n\r\n", "\u0015\u0003\u0001\u0000\u0002\u0002(", "\u0000".repeat(16),
            "garbage-that-is-nothing\r\n", "SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n", "<html><body>hello</body></html>", "\r\n")
        for (f in fixtures) {
            val p = probes.first { it.id == str(f, "id") }
            for (u in unrelated) {
                if (p.id == "ssh-version-exchange" && u.startsWith("SSH-")) continue
                assertNull("${p.id} must not claim: ${u.take(30)}", p.match(bytes(u)))
            }
        }
    }
    @Test fun `a reply of one new protocol is not read as another`() {
        // Same-family pairs that legitimately share a reply shape (RPC replies, UPnP descriptions, AirTunes headers).
        val family = setOf("rpc-portmap-null" to "nfs-null", "nfs-null" to "rpc-portmap-null", "upnp-description" to "upnp-rootdesc", "upnp-rootdesc" to "upnp-description",
            "upnp-description" to "sonos-description", "upnp-rootdesc" to "sonos-description", "airplay-server-info" to "raop-options")
        for (f in fixtures) {
            val sample = hex(str(f, "sampleHex")); val owner = str(f, "id")
            for (p in newProbes.filter { it.id != owner && (it.id to owner) !in family }) {
                assertNull("${p.id} claims the reply of $owner", p.match(sample))
            }
        }
    }
    @Test fun `every fieldbus port has only careful hellos hinted for it`() {
        for (port in listOf(102, 502, 44818)) {   // 47808 (BACnet) is UDP, which the ladder does not speak
            val hinted = probes.filter { port in it.ports && it.sendsBytes }
            assertTrue("port $port has a careful hello", hinted.isNotEmpty())
            assertTrue("port $port: every hello hinted for it is careful", hinted.all { it.safety == ProbeSafety.CAREFUL })
        }
    }
    @Test fun `the side-effect hellos carry no hint, only a commonPorts note, and need the unsafe switch`() {
        val raw = (Json.parseToJsonElement(probeText) as JsonArray).map { it as JsonObject }.associateBy { str(it, "id") }
        for (id in behindTheSwitch) {
            val p = probes.first { it.id == id }
            assertTrue("$id has no hint", p.ports.isEmpty()); assertEquals(ProbeSafety.CAREFUL, p.safety)
            assertTrue("$id keeps its usual ports as a note", (raw.getValue(id)["commonPorts"] as JsonArray).isNotEmpty())
        }
        val ladder = ServiceProbes(probes, net::transport, clock = { net.clock.now })
        fun ran(port: Int, limits: LadderLimits): List<String> = kotlinx.coroutines.runBlocking {
            net.respond = { _, _, _ -> Reply.SILENT }
            val b = ActiveProbeBudget(MAX_ACTIVE_PORTS, Long.MAX_VALUE, Long.MAX_VALUE) { net.clock.now }
            ladder.run(java.net.InetAddress.getByName("10.0.3.59"), port, port, "Unknown", ProbeMode.Active(b, hellos = true, limits = limits), 1L).trace.map { it.id }
        }
        for (limits in listOf(LadderLimits.SCAN, LadderLimits.THOROUGH)) assertTrue(ran(6667, limits).none { it in behindTheSwitch })
        val on = ran(6667, LadderLimits.thorough(true))
        assertTrue(behindTheSwitch.all { it in on })
    }
}
