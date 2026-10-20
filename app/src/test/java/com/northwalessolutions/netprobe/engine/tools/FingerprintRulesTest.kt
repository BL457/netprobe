package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 6.1: every seed rule fires on its sample and not on its negativeSample; $1 substitutes and trims. */
class FingerprintRulesTest {
    private val rules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText())
    private fun sources(rule: FingerprintRule, text: String) = when {
        rule.source == "title" -> FpSources(emptyList(), emptyList(), text, null, "", "")
        rule.source == "generator" -> FpSources(emptyList(), emptyList(), null, text, "", "")
        rule.source == "body" -> FpSources(emptyList(), emptyList(), null, null, text, "")
        rule.source == "errorBody" -> FpSources(emptyList(), emptyList(), null, null, "", text)
        rule.source == "cookie" -> FpSources(emptyList(), listOf(text), null, null, "", "")
        else -> FpSources(listOf(rule.source.removePrefix("header:") to text), emptyList(), null, null, "", "")
    }

    @Test fun `the file loads all 27 seed rules with unique ids`() {
        assertEquals(27, rules.size); assertEquals(27, rules.map { it.id }.toSet().size)
    }
    @Test fun `every rule matches its sample and not its negative sample`() {
        for (r in rules) {
            assertNotNull("${r.id} did not match its sample", FingerprintRules.match(r, sources(r, r.sample)))
            r.negativeSample?.let { assertNull("${r.id} matched its negative sample", FingerprintRules.match(r, sources(r, it))) }
        }
    }
    @Test fun `a version group substitutes and an empty group trims`() {
        val nginx = rules.single { it.id == "nginx" }
        assertEquals("nginx 1.25.3", FingerprintRules.match(nginx, sources(nginx, "nginx/1.25.3"))!!.name)
        assertEquals("nginx", FingerprintRules.match(nginx, sources(nginx, "nginx"))!!.name)
    }
    @Test fun `unifi os is exact and unifi network does not match`() {
        val unifi = rules.single { it.id == "unifi-os" }
        assertNotNull(FingerprintRules.match(unifi, sources(unifi, "UniFi OS"))); assertNull(FingerprintRules.match(unifi, sources(unifi, "UniFi Network")))
    }
    @Test fun `header rules read the named header case insensitively and the excerpt is the text read`() {
        val plex = rules.single { it.id == "plex" }
        val m = FingerprintRules.match(plex, FpSources(listOf("X-Plex-Protocol" to "1.0"), emptyList(), null, null, "", ""))!!
        assertEquals("Plex Media Server", m.name); assertEquals("1.0", m.excerpt)
    }
    @Test fun `the file has no em-dashes`() {
        val text = javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText()
        assertFalse(text.contains('—'))
    }
}
