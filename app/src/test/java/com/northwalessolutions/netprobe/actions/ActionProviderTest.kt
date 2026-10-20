package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.detail.sheetLabel
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
    private val confirmed = { name: String -> ServiceInfo(name, confidence = Confidence.CONFIRMED) }
    @Test fun `an http banner on any port gives an open primary`() {
        assertEquals("http://10.0.3.59:8123/", (all.primary("10.0.3.59", 8123, confirmed("HTTP")) as DeviceAction.OpenUri).uri)
        assertEquals("http://10.0.3.59:8090/", (all.primary("10.0.3.59", 8090, confirmed("HTTP")) as DeviceAction.OpenUri).uri)
        assertEquals("https://10.0.3.59:9443/", (all.primary("10.0.3.59", 9443, confirmed("HTTPS")) as DeviceAction.OpenUri).uri)
    }
    @Test fun `a bare tls port gets no primary and open with defaults to https`() {
        val tls = ServiceInfo("TLS", confidence = Confidence.CONFIRMED, certCn = "imap.lan")
        assertNull(all.primary("10.0.3.59", 993, tls))
        assertEquals(listOf(DeviceAction.OpenWith("https://10.0.3.59:993/")), all.actionsFor("10.0.3.59", 993, tls).filterIsInstance<DeviceAction.OpenWith>())
    }
    @Test fun `an unknown port gets no primary and open with defaults to http`() {
        assertNull(all.primary("10.0.3.59", 7000, svc("Unknown")))
        assertEquals(listOf(DeviceAction.OpenWith("http://10.0.3.59:7000/")), all.actionsFor("10.0.3.59", 7000, svc("Unknown")).filterIsInstance<DeviceAction.OpenWith>())
    }
    @Test fun `every port has exactly one open with`() {
        for ((port, name) in listOf(80 to "HTTP", 22 to "SSH", 445 to "SMB", 993 to "TLS", 7000 to "Unknown", 3389 to "RDP", 8090 to "HTTP"))
            for (p in listOf(all, none, onlyHttp)) assertEquals("$name on $port", 1, p.actionsFor("10.0.3.59", port, svc(name)).count { it is DeviceAction.OpenWith })
    }
    @Test fun `no app installed is still produced and still filtered by the sheet`() {
        val open = none.actionsFor("10.0.3.59", 8090, confirmed("HTTP")).filterIsInstance<DeviceAction.OpenUri>().single()
        assertEquals("No app installed for http://", open.label); assertFalse(open.hasHandler); assertNull(sheetLabel(open))
    }
    @Test fun `try as offers only schemes with a handler, in order`() {
        val some = ActionProvider { it.startsWith("ssh") || it.startsWith("http") }
        assertEquals(listOf("http" to "http://10.0.3.59:8090/", "https" to "https://10.0.3.59:8090/", "ssh" to "ssh://10.0.3.59:8090"), some.tryAs("10.0.3.59", 8090))
        assertEquals(emptyList<Pair<String, String>>(), none.tryAs("10.0.3.59", 8090))
        assertEquals(PortUris.TRY_AS, all.tryAs("10.0.3.59", 445).map { it.first })
    }
}
