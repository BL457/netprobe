package com.northwalessolutions.netprobe.ui

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import com.northwalessolutions.netprobe.ui.detail.portSubLine
import com.northwalessolutions.netprobe.ui.detail.versionLine
import org.junit.Assert.*
import org.junit.Test

/** Phase 3E copy held as regex templates: every state yields a string that matches one template (doubled backslashes). */
class ServiceCopyTest {
    private val why = listOf(Regex("Port \\d+: [^()]+( \\([a-z0-9-]+\\))?"))
    // "<stack or product> <version> <detail>", or "<stack> (<product> <version>) <detail>" when the stack and the product differ.
    private val portLine = listOf(Regex("[^()]+"), Regex("[^()]+ \\([^()]+\\)( .+)?"))
    private fun inTable(s: String, templates: List<Regex>) = assertTrue("not in the copy table: \"$s\"", templates.any { it.matches(s) })

    @Test fun `the why this identity line`() {
        inTable(ServiceCopy.whyLine(8123, "Home Assistant", "home-assistant"), why)
        inTable(ServiceCopy.whyLine(22, "OpenSSH", "ssh"), why)
        inTable(ServiceCopy.whyLine(443, "Synology DSM", null), why)
        assertEquals("Port 8123: Home Assistant (home-assistant)", ServiceCopy.whyLine(8123, "Home Assistant", "home-assistant"))
        assertEquals("Port 443: Synology DSM", ServiceCopy.whyLine(443, "Synology DSM", null))
    }
    @Test fun `the port row text shows the stack when set, else the product, then the version`() {
        assertEquals("Home Assistant 2024.1", DetailCopy.portVersion("Home Assistant", "Home Assistant", "2024.1", null))
        assertEquals("Home Assistant 2024.1", DetailCopy.portVersion("Home Assistant", null, "2024.1", null))
        assertEquals("OpenSSH 8.2p1", DetailCopy.portVersion(null, "OpenSSH", "8.2p1", null))
        assertEquals("Home Assistant (nginx 1.25.3) 200 OK", DetailCopy.portVersion("Home Assistant", "nginx", "1.25.3", "200 OK"))
        assertEquals("Grafana (nginx)", DetailCopy.portVersion("Grafana", "nginx", null, null))
        assertEquals("cert chnas.local", DetailCopy.portVersion(null, null, null, "cert chnas.local"))
        assertEquals("", DetailCopy.portVersion(null, null, null, null))
        for (s in listOf(DetailCopy.portVersion("Home Assistant", null, "2024.1", null), DetailCopy.portVersion("Grafana", "nginx", "1.25.3", "200 OK"),
            DetailCopy.portVersion(null, "OpenSSH", "8.2p1", null), DetailCopy.portVersion("Plex", "plex", null, null))) inTable(s, portLine)
    }
    @Test fun `the port row reads the stack and keeps the title in front`() {
        val s = ServiceInfo("HTTP", "nginx", "1.25.3", "200 OK", Confidence.CONFIRMED, title = "Home", stack = "Home Assistant", stackRole = "software", probe = "home-assistant")
        assertEquals("Home Assistant (nginx 1.25.3) 200 OK", versionLine(s)); assertEquals("Home  Home Assistant (nginx 1.25.3) 200 OK", portSubLine(s))
        assertEquals("nginx 1.25.3 200 OK", versionLine(s.copy(stack = null)))
        assertNull(versionLine(ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)))
    }
}
