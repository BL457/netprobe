package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

class ActionProviderTest {
    private fun svc(name: String) = ServiceInfo(name, confidence = Confidence.PORT_TABLE)
    private val all = ActionProvider { true }
    private val none = ActionProvider { false }
    private val onlyHttp = ActionProvider { it.startsWith("http") }

    @Test fun `http builds browser action with port`() {
        val a = all.primary("192.168.1.1", 8080, svc("HTTP")) as DeviceAction.OpenUri
        assertEquals("http://192.168.1.1:8080/", a.uri); assertEquals("Open in browser", a.label); assertTrue(a.hasHandler)
    }
    @Test fun `smb uses host only`() { assertEquals("smb://192.168.1.5/", (all.primary("192.168.1.5", 445, svc("SMB")) as DeviceAction.OpenUri).uri) }
    @Test fun `ssh vnc rdp telnet ftp schemes`() {
        assertEquals("ssh://10.0.0.2:22", (all.primary("10.0.0.2", 22, svc("SSH")) as DeviceAction.OpenUri).uri)
        assertEquals("vnc://10.0.0.2:5901", (all.primary("10.0.0.2", 5901, svc("VNC")) as DeviceAction.OpenUri).uri)
        assertEquals("rdp://10.0.0.2:3389", (all.primary("10.0.0.2", 3389, svc("RDP")) as DeviceAction.OpenUri).uri)
        assertEquals("telnet://10.0.0.2:23", (all.primary("10.0.0.2", 23, svc("Telnet")) as DeviceAction.OpenUri).uri)
        assertEquals("ftp://10.0.0.2:21/", (all.primary("10.0.0.2", 21, svc("FTP")) as DeviceAction.OpenUri).uri)
    }
    @Test fun `unknown name falls back to port and HTTPS from TLS name`() {
        assertEquals("https://10.0.0.2:8443/", (all.primary("10.0.0.2", 8443, svc("Unknown")) as DeviceAction.OpenUri).uri)
        assertEquals("https://10.0.0.2:443/", (all.primary("10.0.0.2", 443, svc("HTTPS")) as DeviceAction.OpenUri).uri)
        assertEquals("vnc://10.0.0.2:5900", (all.primary("10.0.0.2", 5900, svc("Unknown")) as DeviceAction.OpenUri).uri)
        assertNull(all.primary("10.0.0.2", 31337, svc("Unknown")))
    }
    @Test fun `no handler means no primary but the list still offers copy and open with`() {
        assertNull(none.primary("10.0.0.2", 445, svc("SMB")))
        val list = none.actionsFor("10.0.0.2", 445, svc("SMB"))
        val open = list.filterIsInstance<DeviceAction.OpenUri>().single()
        assertFalse(open.hasHandler); assertEquals("No app installed for smb://", open.label)
        assertTrue(list.any { it is DeviceAction.OpenWith }); assertEquals(listOf("Copy IP", "Copy IP:port"), list.filterIsInstance<DeviceAction.Copy>().map { it.label })
        assertEquals("10.0.0.2:445", list.filterIsInstance<DeviceAction.Copy>().last().text)
    }
    @Test fun `handler resolution is per scheme`() {
        assertNotNull(onlyHttp.primary("10.0.0.2", 80, svc("HTTP"))); assertNull(onlyHttp.primary("10.0.0.2", 22, svc("SSH")))
    }
    @Test fun `copy actions exist even for ports with no mapping`() {
        val list = all.actionsFor("10.0.0.2", 31337, svc("Unknown"))
        assertEquals(2, list.filterIsInstance<DeviceAction.Copy>().size); assertTrue(list.none { it is DeviceAction.OpenUri })
    }
}
