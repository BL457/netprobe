package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shipped `service_probes.json`: it loads clean, and every probe's hand-written samples hit it and its negative samples do not (spec 10). */
class ServiceProbeFileTest {
    private val text: String = listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()
    private val loaded = ServiceProbeRules.load(text)
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
    private fun u16le(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)

    @Test fun `every probe in the file passes validation`() {
        assertTrue("skipped: ${loaded.skipped.map { it.id + ": " + it.reason }}", loaded.skipped.isEmpty())
        assertEquals(151, loaded.probes.size)
    }
    @Test fun `the Phase 3A steps are the first probes in file order`() {
        assertEquals(listOf("null", "tls", "http-head", "http-get", "rtsp-options", "rdp-x224"), loaded.probes.take(6).map { it.id })
    }
    @Test fun `the initial set of spec 4 is all there`() {
        val want = listOf("ssh", "ftp", "smtp", "pop3", "imap", "telnet", "vnc-rfb", "mysql", "nats", "rsync", "x11", "git-daemon", "http-null",
            "sip-options", "redis-info", "memcached-version", "zookeeper-ruok", "docker-engine",
            "smb2-negotiate", "postgresql-startup", "mongodb-ismaster", "mqtt-connect", "amqp-header", "modbus-device-id", "ipp-head")
        assertTrue("missing ${want - loaded.probes.map { it.id }.toSet()}", loaded.probes.map { it.id }.containsAll(want))
    }
    @Test fun `every probe has a sample that hits and a negative sample that does not`() {
        for (p in loaded.probes) {
            assertTrue("${p.id} has no sample", p.samples.isNotEmpty()); assertTrue("${p.id} has no negative sample", p.negativeSamples.isNotEmpty())
            p.samples.forEach { assertNotNull("sample of ${p.id}: ${it.take(40)}", p.match(bytes(it))) }
            p.negativeSamples.forEach { assertNull("negative sample of ${p.id}: ${it.take(40)}", p.match(bytes(it))) }
        }
    }
    @Test fun `every binary or authenticating probe is careful and hinted`() {
        val careful = listOf("smb2-negotiate", "postgresql-startup", "mongodb-ismaster", "mqtt-connect", "amqp-header", "modbus-device-id", "rdp-x224", "ipp-head")
        for (id in careful) { val p = loaded.probes.first { it.id == id }; assertEquals(id, ProbeSafety.CAREFUL, p.safety); assertTrue("$id needs a hint", p.ports.isNotEmpty()) }
        loaded.probes.filter { it.safety == ProbeSafety.SAFE && it.sendsBytes }.forEach { assertTrue("${it.id}: a safe hello is rarity 1 to 4", it.rarity <= 4) }
    }
    @Test fun `binary requests carry the exact bytes the spec names`() {
        fun send(id: String) = loaded.probes.first { it.id == id }.sendBytes("10.0.3.5", 1, "ABCDEFGH")
        val smb = send("smb2-negotiate")
        assertEquals(0, smb[0].toInt())
        assertEquals(smb.size - 4, ((smb[1].toInt() and 255) shl 16) or ((smb[2].toInt() and 255) shl 8) or (smb[3].toInt() and 255))
        assertEquals("fe 53 4d 42", hex(smb.copyOfRange(4, 8)))
        assertEquals(5, u16le(smb, 4 + 64 + 2))                                            // DialectCount
        listOf(0x0202, 0x0210, 0x0300, 0x0302, 0x0311).forEachIndexed { i, d -> assertEquals(d, u16le(smb, 4 + 64 + 36 + i * 2)) }
        assertEquals("41 4d 51 50 00 00 09 01", hex(send("amqp-header")))
        assertTrue(String(send("rdp-x224"), Charsets.ISO_8859_1).contains("Cookie: mstshash=netprobe\r\n"))
        assertEquals("00 01 00 00 00 05 01 2b 0e 01 00", hex(send("modbus-device-id")))
        val pg = send("postgresql-startup")
        assertEquals("00 00 00 08 04 d2 16 2f", hex(pg.copyOf(8)))
        assertTrue(String(pg, Charsets.ISO_8859_1).contains("user\u0000netprobe\u0000database\u0000postgres\u0000"))
        val mqtt = send("mqtt-connect")
        assertEquals(0x10, mqtt[0].toInt()); assertEquals(mqtt.size - 2, mqtt[1].toInt()); assertTrue(String(mqtt, Charsets.ISO_8859_1).endsWith("netprobe-ABCDEFGH"))
        val mongo = send("mongodb-ismaster")
        assertEquals(mongo.size, u16le(mongo, 0)); assertTrue(String(mongo, Charsets.ISO_8859_1).contains("isMaster"))
    }
    @Test fun `no send is over the cap`() {
        loaded.probes.forEach { assertTrue(it.id, it.sendTemplate.length <= ServiceProbeRules.MAX_SEND) }
    }
}
