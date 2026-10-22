package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** Every port read and write path goes through these, so none can drop the six evidence fields (spec 3.1 table, Gruff M5). */
class PortMappingTest {
    @Test fun `a port row carries all eleven service fields both ways`() {
        val s = ServiceInfo("HTTPS", "nginx", "1.25", "cert nas.lan", Confidence.CONFIRMED, "DiskStation", "nginx/1.25", "DSM", "PHP/8.2", "nas.lan", "Synology Inc.")
        val e = PortEntity(hostId = 7, port = 5001, serviceName = "x", product = null, version = null, detail = null, confidence = Confidence.PORT_TABLE, lastSeen = 0)
            .withService(s, now = 9)
        assertEquals(s, e.toServiceInfo()); assertEquals(9L, e.lastSeen); assertEquals(7L, e.hostId); assertEquals(5001, e.port)
        assertEquals(s, PortMapping.newRow(7, 5001, s, 3).toServiceInfo())
    }
    @Test fun `a port row carries the four stack fields both ways`() {
        val s = ServiceInfo("HTTP", "nginx", "1.25", "200 OK", Confidence.CONFIRMED, title = "Home Assistant", stack = "Home Assistant", stackRole = "software",
            cpe = "Home Assistant", probe = "home-assistant")
        val e = PortMapping.newRow(7, 8123, s, 3)
        assertEquals("Home Assistant", e.stack); assertEquals("software", e.stackRole); assertEquals("Home Assistant", e.cpe); assertEquals("home-assistant", e.probe)
        assertEquals(s, e.toServiceInfo())
        val plain = PortEntity(hostId = 7, port = 80, serviceName = "HTTP", product = null, version = null, detail = null, confidence = Confidence.CONFIRMED, lastSeen = 0)
        assertNull(plain.stack); assertNull(plain.stackRole); assertNull(plain.cpe); assertNull(plain.probe)
    }
    @Test fun `merging keeps a stored stack when the new finding has none and clears it when the name changed`() {
        val stored = PortMapping.newRow(7, 8123, ServiceInfo("HTTP", "nginx", "1.25", null, Confidence.CONFIRMED, stack = "Home Assistant", stackRole = "software", cpe = "Home Assistant", probe = "home-assistant"), 1)
        val kept = PortMapping.merge(stored, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED), now = 2)
        assertEquals("Home Assistant", kept.stack); assertEquals("home-assistant", kept.probe)
        val changed = PortMapping.merge(stored, ServiceInfo("SSH", "dropbear", null, null, Confidence.CONFIRMED, probe = "ssh"), now = 3)
        assertEquals("SSH", changed.serviceName); assertNull(changed.stack); assertNull(changed.stackRole); assertNull(changed.cpe); assertEquals("ssh", changed.probe)
        assertEquals("dropbear", changed.product); assertNull(changed.version)
    }
    @Test fun `merging into a stored row never erases evidence`() {
        val stored = PortEntity(hostId = 7, port = 5000, serviceName = "HTTP", product = "nginx", version = null, detail = "200 OK",
            confidence = Confidence.CONFIRMED, lastSeen = 1, title = "DiskStation")
        val merged = PortMapping.merge(stored, ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE), now = 2)
        assertEquals("HTTP", merged.serviceName); assertEquals(Confidence.CONFIRMED, merged.confidence); assertEquals("DiskStation", merged.title)
        assertEquals("nginx", merged.product); assertEquals(2L, merged.lastSeen); assertEquals(stored.id, merged.id)
    }
}
