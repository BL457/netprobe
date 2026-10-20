package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 16: one never-erase merge for the repository and the evidence builder (Gruff M5, Review Focus 3). */
class ServiceMergeTest {
    private val stored = ServiceInfo("HTTP", "nginx", "1.25", "200 OK", Confidence.CONFIRMED, title = "DiskStation", server = "nginx/1.25", poweredBy = "PHP/8.2")

    @Test fun `new evidence fields merge field by field`() {
        val m = ServiceMerge.merge(stored, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = "Synology DiskStation", realm = "DSM"))
        assertEquals("Synology DiskStation", m.title); assertEquals("nginx/1.25", m.server); assertEquals("DSM", m.realm); assertEquals("PHP/8.2", m.poweredBy)
        assertEquals("nginx", m.product); assertEquals("1.25", m.version); assertEquals("200 OK", m.detail)
    }
    @Test fun `a null never erases a stored value`() {
        assertEquals(stored, ServiceMerge.merge(stored, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)))
    }
    @Test fun `a port table result never replaces a confirmed name`() {   // a timed-out HEAD on a busy NAS
        val m = ServiceMerge.merge(stored, ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE))
        assertEquals(stored, m)
    }
    @Test fun `a new confirmed name replaces an old one`() {
        val m = ServiceMerge.merge(stored, ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = "nas.lan", certOrg = "Synology Inc."))
        assertEquals("HTTPS", m.name); assertEquals("nas.lan", m.certCn); assertEquals("Synology Inc.", m.certOrg); assertEquals("DiskStation", m.title)
    }
    @Test fun `port table replaces port table and nothing stored keeps the new value`() {
        assertEquals("Unknown", ServiceMerge.merge(ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE), ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE)).name)
        val fresh = ServiceInfo("SSH", "OpenSSH", null, null, Confidence.CONFIRMED)
        assertSame(fresh, ServiceMerge.merge(null, fresh))
    }
    @Test fun `the evidence builder uses the same merge`() {
        val host = DiscoveredHost(InetAddress.getByName("10.0.3.59") as Inet4Address)
        val e = HostEvidenceBuilder.build(host, mapOf(5000 to stored), setOf(5000), mapOf(5000 to ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE)), null)
        assertEquals(stored, e.services[5000])
    }
}
