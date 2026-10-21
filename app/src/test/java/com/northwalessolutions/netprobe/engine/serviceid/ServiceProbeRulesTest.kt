package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

class ServiceProbeRulesTest {
    private fun probe(id: String = "p", send: String = "PING\\r\\n", extra: String = "", protocols: String = "[\"tcp\"]", rarity: Int = 2,
                      safety: String = "safe", tls: String = "never", matches: String = """[{"regex":"^PONG","service":"Ping"}]""") =
        """{"id":"$id","service":"Ping","protocols":$protocols,"send":"$send","ports":[7],"rarity":$rarity,"safety":"$safety","tls":"$tls",
            "waitMs":500,"maxBytes":2048,"parser":null,$extra "matches":$matches,"samples":["PONG\r\n"],"negativeSamples":["HTTP/1.1 400\r\n"]}"""
    private fun load(vararg p: String, parsers: Set<String> = emptySet()) = ServiceProbeRules.load("[" + p.joinToString(",") + "]", parsers)
    private fun skippedWhy(p: String): String? = load(p).skipped.singleOrNull()?.reason

    @Test fun `a good probe loads`() {
        val r = load(probe()); assertEquals(1, r.probes.size); assertTrue(r.skipped.isEmpty())
        assertEquals("PING\r\n", r.probes[0].sendTemplate)
    }
    @Test fun `nmap escapes decode`() {
        assertEquals("a\r\n\t\u0000\\ÿz", ServiceProbeRules.decodeEscapes("a\\r\\n\\t\\0\\\\\\xffz"))
        assertThrows(IllegalArgumentException::class.java) { ServiceProbeRules.decodeEscapes("\\q") }
        assertThrows(IllegalArgumentException::class.java) { ServiceProbeRules.decodeEscapes("\\x4") }
        assertThrows(IllegalArgumentException::class.java) { ServiceProbeRules.decodeEscapes("\\xZZ") }
    }
    @Test fun `ip port and rand are substituted at send time`() {
        val p = load(probe(send = "SIP {ip}:{port} {rand}\\r\\n")).probes.single()
        assertEquals("SIP 10.0.3.5:5060 abcd1234\r\n", String(p.sendBytes("10.0.3.5", 5060, "abcd1234"), Charsets.ISO_8859_1))
    }
    @Test fun `binary bytes survive the round trip`() {
        val p = load(probe(send = "\\\\xfe\\\\x00\\\\x80\\\\xff")).probes.single()
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0, 0x80.toByte(), 0xFF.toByte()), p.sendBytes("1.2.3.4", 1, "x"))
    }
    @Test fun `duplicate ids keep the first`() {
        val r = load(probe("a"), probe("a")); assertEquals(1, r.probes.size); assertEquals("duplicate id", r.skipped.single().reason)
    }
    @Test fun `send over 512 bytes is refused`() { assertTrue(skippedWhy(probe(send = "A".repeat(513)))!!.contains("512")) }
    @Test fun `send of exactly 512 bytes is accepted`() { assertEquals(1, load(probe(send = "A".repeat(512))).probes.size) }
    @Test fun `empty send needs the null protocol`() {
        assertNotNull(skippedWhy(probe(send = "")))
        assertEquals(1, load(probe(send = "", protocols = "[\"null\"]")).probes.size)
        assertEquals(1, load(probe(send = "", protocols = "[\"tcp\",\"null\"]")).probes.size)
        assertNotNull("a null-only probe sends nothing", skippedWhy(probe(send = "X", protocols = "[\"null\"]")))
    }
    @Test fun `rarity safety and tls are range checked`() {
        assertNotNull(skippedWhy(probe(rarity = 0))); assertNotNull(skippedWhy(probe(rarity = 10)))
        assertEquals(2, load(probe("a", rarity = 1), probe("b", rarity = 9)).probes.size)
        assertNotNull(skippedWhy(probe(safety = "dangerous"))); assertNotNull(skippedWhy(probe(tls = "sometimes")))
        assertEquals(3, load(probe("a", tls = "never"), probe("b", tls = "also"), probe("c", tls = "only")).probes.size)
    }
    @Test fun `maxBytes over 2048 is refused`() {
        assertNotNull(skippedWhy(probe().replace("\"maxBytes\":2048", "\"maxBytes\":2049")))
    }
    @Test fun `empty matches and a missing regex are refused`() {
        assertNotNull(skippedWhy(probe(matches = "[]")))
        assertNotNull(skippedWhy(probe(matches = """[{"service":"x"}]""")))
        assertNotNull(skippedWhy(probe(matches = """[{"regex":"^x"}]""")))
    }
    @Test fun `a regex that does not compile skips its probe`() {
        assertTrue(skippedWhy(probe(matches = """[{"regex":"^(unclosed","service":"x"}]"""))!!.contains("compile"))
    }
    @Test fun `nested quantifiers are refused and safe shapes pass`() {
        for (bad in listOf("(a+)+", "(a*)*", "(a+)*", "^(?:\\\\w+\\\\s?)+$", "((ab)+c*)+", "(a{2,})+")) {
            assertNotNull("should refuse $bad", RegexLint.refusal(bad.replace("\\\\", "\\")))
        }
        for (ok in listOf("(a|b)+", "(?:ab)+", "^([\\d.]+)", "a+b*", "(a+)b", "([a-z]+)\\s+(\\d+)", "[(+*]+", "\\(a+\\)+", "(?s)^(.*?)\\r\\n", "(a?)+", "(\\d{1,3}\\.){3}")) {
            assertNull("should allow $ok", RegexLint.refusal(ok))
        }
        assertNotNull(skippedWhy(probe(matches = """[{"regex":"^(a+)+$","service":"x"}]""")))
    }
    @Test fun `unknown parser and bad port are refused`() {
        assertNotNull(skippedWhy(probe(extra = "\"x\":1,").replace("\"parser\":null", "\"parser\":\"nope\"")))
        assertNotNull(skippedWhy(probe().replace("\"ports\":[7]", "\"ports\":[70000]")))
    }
    @Test fun `a bad probe is skipped and the good ones load`() {
        val r = load(probe("one"), probe("bad", rarity = 12), probe("two"))
        assertEquals(listOf("one", "two"), r.probes.map { it.id }); assertEquals("bad", r.skipped.single().id)
    }
    @Test fun `a narrow list naming an unknown probe skips that probe`() {
        val soft = probe("soft", matches = """[{"regex":"^x","service":"x","soft":true,"narrow":["ghost"]}]""")
        val r = load(soft, probe("real")); assertEquals(listOf("real"), r.probes.map { it.id }); assertTrue(r.skipped.single().reason.contains("narrow"))
    }
    @Test fun `file that is not json or not a list loads nothing and says why`() {
        assertEquals("(file)", ServiceProbeRules.load("{", emptySet()).skipped.single().id)
        assertEquals("(file)", ServiceProbeRules.load("{}", emptySet()).skipped.single().id)
    }
    @Test fun `dollar groups substitute and double dollar is literal`() {
        val p = load(probe(matches = """[{"regex":"^v(\\d+)\\.(\\d+) (\\w+)?","service":"S","product":"Prod $3","version":"$1.$2","detail":"cost $$5 ref $9"}]""")).probes.single()
        val hit = p.match("v7.2 \r\n".toByteArray())!!
        assertEquals("7.2", hit.version); assertEquals("cost \$5 ref", hit.detail); assertEquals("Prod", hit.product)
    }
    @Test fun `multiline is off so caret is the start of the reply only`() {
        val p = load(probe(matches = """[{"regex":"^PONG","service":"Ping"}]""")).probes.single()
        assertNotNull(p.match("PONG".toByteArray())); assertNull(p.match("junk\r\nPONG".toByteArray()))
    }
    @Test fun `inline s flag lets dot cross lines`() {
        val p = load(probe(matches = """[{"regex":"(?s)^A.*Z","service":"x"}]""")).probes.single()
        assertNotNull(p.match("A\r\n\r\nZ".toByteArray()))
    }
    @Test fun `a reply is cut at maxBytes before matching`() {
        val p = load(probe(matches = """[{"regex":"TAIL","service":"x"}]""").replace("\"maxBytes\":2048", "\"maxBytes\":16")).probes.single()
        assertNull(p.match(("A".repeat(16) + "TAIL").toByteArray()))
        assertNotNull(p.match(("A".repeat(8) + "TAIL").toByteArray()))
    }
    @Test fun `samples must hit and negative samples must not`() {
        for (p in load(probe()).probes) {
            p.samples.forEach { assertNotNull("sample for ${p.id}", p.match(it.toByteArray(Charsets.ISO_8859_1))) }
            p.negativeSamples.forEach { assertNull("negative sample for ${p.id}", p.match(it.toByteArray(Charsets.ISO_8859_1))) }
        }
    }
}
