package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 6 and 7. */
class PortUrisTest {
    private fun s(name: String, c: Confidence = Confidence.CONFIRMED) = ServiceInfo(name, confidence = c)
    private val ip = "10.0.3.59"

    @Test fun `uri forms for every try as scheme`() {
        assertEquals(listOf("http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb"), PortUris.TRY_AS)
        assertEquals("http://10.0.3.59:8090/", PortUris.uri("http", ip, 8090)); assertEquals("https://10.0.3.59:9443/", PortUris.uri("https", ip, 9443))
        assertEquals("ftp://10.0.3.59:21/", PortUris.uri("ftp", ip, 21))
        assertEquals("ssh://10.0.3.59:2200", PortUris.uri("ssh", ip, 2200)); assertEquals("vnc://10.0.3.59:5901", PortUris.uri("vnc", ip, 5901))
        assertEquals("rdp://10.0.3.59:3389", PortUris.uri("rdp", ip, 3389)); assertEquals("telnet://10.0.3.59:23", PortUris.uri("telnet", ip, 23))
        assertEquals("smb://10.0.3.59/", PortUris.uri("smb", ip, 445)); assertEquals("smb://10.0.3.59/", PortUris.uri("smb", ip, 139))
        assertEquals("smb://10.0.3.59:8445/", PortUris.uri("smb", ip, 8445))
    }
    @Test fun `guess from the service name wins over the port`() {
        assertEquals("http", PortUris.guess(8090, s("HTTP"))); assertEquals("https", PortUris.guess(9443, s("HTTPS")))
        assertNull("bare TLS is not a guess", PortUris.guess(993, s("TLS"))); assertNull(PortUris.guess(8009, s("TLS")))
        assertEquals("ssh", PortUris.guess(2200, s("SSH"))); assertEquals("telnet", PortUris.guess(2323, s("Telnet")))
        assertNull(PortUris.guess(7000, s("Unknown", Confidence.PORT_TABLE)))
    }
    @Test fun `port fallbacks are unchanged`() {
        val unknown = s("Unknown", Confidence.PORT_TABLE)
        for (p in listOf(80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000)) assertEquals("$p", "http", PortUris.guess(p, unknown))
        for (p in listOf(443, 8443)) assertEquals("https", PortUris.guess(p, unknown))
        for (p in listOf(445, 139)) assertEquals("smb", PortUris.guess(p, unknown))
        for (p in listOf(22, 2222)) assertEquals("ssh", PortUris.guess(p, unknown))
        assertEquals("telnet", PortUris.guess(23, unknown)); assertEquals("rdp", PortUris.guess(3389, unknown)); assertEquals("ftp", PortUris.guess(21, unknown))
        for (p in 5900..5910) assertEquals("vnc", PortUris.guess(p, unknown))
    }
    @Test fun `open with fallback is https for tls or a certificate and http otherwise`() {
        assertEquals("https", PortUris.fallback(s("TLS")))
        assertEquals("https", PortUris.fallback(ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE, certCn = "x.lan")))
        assertEquals("http", PortUris.fallback(s("Unknown", Confidence.PORT_TABLE)))
    }
}
