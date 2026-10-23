package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 20 (4.2, ruling 3). */
class ManualTargetTest {
    private val home = SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24)
    private fun check(ip: String, port: String = "443", tool: String = ToolKeys.HTTP_FINGERPRINT, attempted: Boolean = true) =
        ManualTarget.check(ip, port, tool, home, attempted)

    @Test fun `every error string`() {
        assertEquals(ManualCopy.ERR_IP, check("").ipError)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3").ipError)
        assertEquals("no name lookup", ManualCopy.ERR_IP, check("chnas.local").ipError)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3.256").ipError)
        assertEquals(ManualCopy.ERR_ADDRESS, check("127.0.0.1").ipError)
        assertEquals(ManualCopy.ERR_PORT_NEEDED, check("10.0.3.5", "").portError)
        for (p in listOf("0", "65536", "99999", "123456", "44a")) assertEquals(p, ManualCopy.ERR_PORT_RANGE, check("10.0.3.5", p).portError)
    }
    @Test fun `reserved addresses and the home range's network and broadcast are refused`() {
        for (ip in listOf("0.1.2.3", "127.0.0.1", "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255", "10.0.3.0", "10.0.3.255"))
            assertEquals(ip, ManualCopy.ERR_ADDRESS, check(ip).ipError)
    }
    @Test fun `any other address is accepted, public included`() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "100.64.1.1", "169.254.10.10", "10.20.0.5", "10.0.3.5", "10.0.3.1")) {
            val c = check(ip)
            assertTrue(ip, c.canRun); assertEquals(Ipv4.parse(ip), c.ip); assertEquals(443, c.port)
        }
    }
    @Test fun `SMB and SNMP fix their ports and ignore the port field`() {
        check("10.0.3.59", "", ToolKeys.SMB_SESSION).let { assertNull(it.portError); assertEquals(445, it.port); assertTrue(it.canRun) }
        check("10.0.3.1", "abc", ToolKeys.SNMP_WALK).let { assertNull(it.portError); assertEquals(161, it.port) }
        assertEquals(445, ManualTarget.fixedPort(ToolKeys.SMB_SESSION)); assertEquals(161, ManualTarget.fixedPort(ToolKeys.SNMP_WALK))
        assertNull(ManualTarget.fixedPort(ToolKeys.TLS_CERTIFICATE))
    }
    @Test fun `nothing shows until the first Run tap, then errors follow the fields`() {
        ManualTarget.check("", "", ToolKeys.HTTP_FINGERPRINT, home, attempted = false).let { assertNull(it.ipError); assertNull(it.portError); assertFalse(it.canRun) }
        assertNull("a half-typed address is not an error before Run", check("10.0.3", attempted = false).ipError)
        assertFalse(check("10.0.3", attempted = false).canRun)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3", attempted = true).ipError)
    }
    @Test fun `with no home range only the reserved blocks are refused`() {
        assertTrue(ManualTarget.check("10.0.3.0", "443", ToolKeys.HTTP_FINGERPRINT, null, true).canRun)
        assertFalse(ManualTarget.check("127.0.0.1", "443", ToolKeys.HTTP_FINGERPRINT, null, true).canRun)
    }
    @Test fun `tls follows the tool and the switch`() {
        assertTrue(ManualTarget.tls(ToolKeys.TLS_CERTIFICATE, https = false))
        assertFalse(ManualTarget.tls(ToolKeys.SMB_SESSION, https = true)); assertFalse(ManualTarget.tls(ToolKeys.SNMP_WALK, https = true))
        assertTrue(ManualTarget.tls(ToolKeys.HTTP_FINGERPRINT, https = true)); assertFalse(ManualTarget.tls(ToolKeys.DIR_BRUTEFORCE, https = false))
    }
    @Test fun `https defaults from the port, or from a known host's stored service on it`() {
        assertTrue(ManualTarget.httpsDefault(ToolKeys.HTTP_FINGERPRINT, 443, null)); assertTrue(ManualTarget.httpsDefault(ToolKeys.DIR_BRUTEFORCE, 5001, null))
        assertFalse(ManualTarget.httpsDefault(ToolKeys.HTTP_FINGERPRINT, 8080, null)); assertFalse(ManualTarget.httpsDefault(ToolKeys.HTTP_FINGERPRINT, null, null))
        assertTrue(ManualTarget.httpsDefault(ToolKeys.HTTP_FINGERPRINT, 5000, ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED)))
        assertFalse(ManualTarget.httpsDefault(ToolKeys.HTTP_FINGERPRINT, 443, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)))
    }
}
