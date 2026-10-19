package com.northwalessolutions.netprobe.engine.vulns

import org.junit.Assert.*
import org.junit.Test

class NvdParserTest {
    private val json = javaClass.getResource("/fixtures/nvd_response.json")!!.readText()
    @Test fun `parses vulns sorted by score desc with severity fallbacks`() {
        val (v, total) = NvdParser.parse(json)!!
        assertEquals(2, total); assertEquals("CVE-2023-38408", v[0].cveId); assertEquals(9.8, v[0].cvss!!, 0.01); assertEquals("CRITICAL", v[0].severity)
        assertTrue(v[0].summary.startsWith("The PKCS#11")); assertEquals("https://nvd.nist.gov/vuln/detail/CVE-2023-38408", v[0].url)
        assertEquals(4.4, v[1].cvss!!, 0.01); assertEquals("MEDIUM", v[1].severity); assertEquals("2021-09-26", v[1].published.take(10))
    }
    @Test fun `malformed yields null`() { assertNull(NvdParser.parse("{")); assertNull(NvdParser.parse("")); assertNull(NvdParser.parse("""{"vulnerabilities": "nope"}""")) }
    @Test fun `empty result list is ok`() { assertEquals(0 to 0, NvdParser.parse("""{"totalResults":0,"vulnerabilities":[]}""")!!.let { it.first.size to it.second }) }
}
