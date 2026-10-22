package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class CpeTest {
    private val map = Cpe.load(javaClass.getResource("/cpe_map.json")!!.readText())
    @Test fun `map loads required products`() {
        for (p in listOf("OpenSSH", "nginx", "Apache", "lighttpd", "dropbear", "ProFTPD", "vsFTPd", "Samba", "MiniUPnPd", "BusyBox", "Exim", "Postfix", "Dovecot", "Pure-FTPd", "Microsoft-IIS"))
            assertTrue("missing $p", map.keys.any { it.equals(p, ignoreCase = true) })
        map.values.forEach { assertTrue(it.startsWith("cpe:2.3:a:") && it.contains("{v}")) }
    }
    @Test fun `service to cpe`() {
        assertEquals("cpe:2.3:a:openbsd:openssh:9.6:p1:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("SSH", "OpenSSH", "9.6p1", null, Confidence.CONFIRMED)))
        assertEquals("cpe:2.3:a:dropbear_ssh_project:dropbear_ssh:2022.83:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("SSH", "dropbear", "2022.83", null, Confidence.CONFIRMED)))
        assertEquals("cpe:2.3:a:f5:nginx:1.18.0:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.18.0", null, Confidence.CONFIRMED)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", null, null, Confidence.CONFIRMED)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "GoAhead-Webs", "1.0", null, Confidence.CONFIRMED)))
    }
    @Test fun `openssh for windows maps to the openssh cpe`() {
        assertEquals("cpe:2.3:a:openbsd:openssh:8.1:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("SSH", "OpenSSH_for_Windows", "8.1", null, Confidence.CONFIRMED)))
        val parsed = com.northwalessolutions.netprobe.engine.serviceid.BannerParsers.ssh("SSH-2.0-OpenSSH_for_Windows_9.5")!!
        assertEquals("cpe:2.3:a:openbsd:openssh:9.5:*:*:*:*:*:*:*", Cpe.forService(map, parsed))
    }
    @Test fun `a cpe product named by a rule builds from the map template and the version`() {
        assertEquals("cpe:2.3:a:home-assistant:home-assistant:2024.1.0:*:*:*:*:*:*:*",
            Cpe.forService(map, ServiceInfo("HTTP", "Home Assistant", "2024.1.0", null, Confidence.CONFIRMED, cpe = "Home Assistant")))
        assertEquals("the cpe name wins over the product", "cpe:2.3:a:jenkins:jenkins:2.426.1:*:*:*:*:*:*:*",
            Cpe.forService(map, ServiceInfo("HTTP", "Jetty", "2.426.1", null, Confidence.CONFIRMED, cpe = "jenkins")))
        assertNull("no version, no cpe", Cpe.forService(map, ServiceInfo("HTTP", "Grafana", null, null, Confidence.CONFIRMED, cpe = "Grafana")))
    }
    @Test fun `a free cpe string or an unknown name is refused`() {
        val free = "cpe:2.3:a:evil:evil:{v}:*:*:*:*:*:*:*"
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.0", null, Confidence.CONFIRMED, cpe = free)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.0", null, Confidence.CONFIRMED, cpe = "not in the map")))
        assertNull("a bad cpe name does not fall back to the product", Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.0", null, Confidence.CONFIRMED, cpe = "nope")))
    }
    @Test fun `the version of a cpe product still has to pass the safe version check`() {
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "x", "1.0:*:*", null, Confidence.CONFIRMED, cpe = "Grafana")))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "x", "1 0", null, Confidence.CONFIRMED, cpe = "Grafana")))
        assertEquals("cpe:2.3:a:grafana:grafana:10.2.3:*:*:*:*:*:*:*", Cpe.forService(map, ServiceInfo("HTTP", "x", "10.2.3", null, Confidence.CONFIRMED, cpe = "Grafana")))
    }
    @Test fun `every template in the map takes only the version`() {
        map.values.forEach { assertTrue(it, it.count { c -> c == '{' } == 1 && it.contains("{v}")) }
    }
    @Test fun `version with unsafe characters yields no cpe`() {
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.2 beta", null, Confidence.CONFIRMED)))
        assertNull(Cpe.forService(map, ServiceInfo("HTTP", "nginx", "1.2:*", null, Confidence.CONFIRMED)))
    }
}
