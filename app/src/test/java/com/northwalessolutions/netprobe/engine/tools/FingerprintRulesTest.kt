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

    @Test fun `the file loads all 31 rules with unique ids`() {
        assertEquals(31, rules.size); assertEquals(31, rules.map { it.id }.toSet().size)
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
    private fun names(s: FpSources) = rules.mapNotNull { FingerprintRules.match(it, s)?.name }.distinct()
    /** CHNAS 10.0.3.59:5001 (DS220+, DSM 7) as Phase 3B QA saw it: no Server header, the device name as title, SYNO.Core.Desktop. scripts. */
    @Test fun `chnas on dsm 7 is named synology dsm from its login page marker`() {
        val chnas = FpSources(emptyList(), emptyList(), "CHNAS", null, "<title>CHNAS</title><script src=\"webapi/entry.cgi?api=SYNO.Core.Desktop.Defs\"></script>", "")
        assertEquals(listOf("Synology DSM"), names(chnas))
        assertEquals("without the marker nothing names it", emptyList<String>(), names(chnas.copy(body = "<title>CHNAS</title>")))
    }
    /** The USG 10.0.3.1:443 as Phase 3B QA saw it: Server "Server", a PHPSESSID cookie, title "UniFi Security Gateway". */
    @Test fun `the usg is named by its title ahead of the generic php cookie`() {
        val usg = FpSources(listOf("Server" to "Server"), listOf("PHPSESSID"), "UniFi Security Gateway", null, "<title>UniFi Security Gateway</title>", "")
        assertEquals(listOf("UniFi Security Gateway", "PHP"), names(usg))
    }
    @Test fun `the file has no em-dashes`() {
        val text = javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText()
        assertFalse(text.contains('\u2014'))
    }
}
