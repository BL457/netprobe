package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRule
import org.junit.Assert.*
import org.junit.Test

/** spec 6.1 over http_stacks.json: every converted rule fires on its sample and not on its negativeSample; the version capture appends and trims. */
class FingerprintRulesTest {
    private val rules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_stacks.json")!!.readText())
    /** The 31 converted rules are one-condition rules; the text is offered to that condition's source. */
    private fun sources(rule: HttpStackRule, text: String): FpSources {
        val source = rule.all.single().where
        return when {
            source == "title" -> FpSources(emptyList(), emptyList(), text, null, "", "")
            source == "generator" -> FpSources(emptyList(), emptyList(), null, text, "", "")
            source == "body" -> FpSources(emptyList(), emptyList(), null, null, text, "")
            source == "errorBody" -> FpSources(emptyList(), emptyList(), null, null, "", text)
            source == "cookie" -> FpSources(emptyList(), listOf(text), null, null, "", "")
            source == "server" -> FpSources(listOf("Server" to text), emptyList(), null, null, "", "")
            source == "poweredBy" -> FpSources(listOf("X-Powered-By" to text), emptyList(), null, null, "", "")
            else -> FpSources(listOf(source.removePrefix("header:") to text), emptyList(), null, null, "", "")
        }
    }
    private val converted = rules.take(31)

    @Test fun `the file keeps the 31 converted rules first, with unique ids over the whole file`() {
        assertTrue(rules.size > 31); assertEquals(rules.size, rules.map { it.id }.toSet().size)
        assertEquals(listOf("synology-title", "synology-body", "synology-dsm7-body", "synology-csp", "synology-location"), converted.take(5).map { it.id })
        assertEquals("lighttpd", converted.last().id)
        assertTrue(converted.all { it.all.size == 1 })
    }
    @Test fun `every converted rule matches its sample and not its negative sample`() {
        for (r in converted) {
            assertNotNull("${r.id} did not match its sample", FingerprintRules.match(r, sources(r, r.sample.single!!)))
            r.negativeSample?.let { assertNull("${r.id} matched its negative sample", FingerprintRules.match(r, sources(r, it.single!!))) }
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
        val text = javaClass.classLoader!!.getResource("http_stacks.json")!!.readText()
        assertFalse(text.contains('\u2014'))
    }
}
