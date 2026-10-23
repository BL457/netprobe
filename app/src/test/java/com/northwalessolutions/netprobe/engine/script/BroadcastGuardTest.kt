package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The directed broadcast permission (spec section 11): LAN only, declared only, the current subnet's directed broadcast only. */
class BroadcastGuardTest {
    private val target = ScopeTarget(Ipv4.parse("192.168.1.20")!!, "nas.local")
    private fun lan(prefix: Int = 24) = ScopeLan(Ipv4.parse("192.168.1.50")!!, prefix)

    private fun guard(scope: NetworkScope, broadcast: Boolean, l: ScopeLan? = lan()) =
        NetworkScopeGuard(scope, target, l, broadcast) { emptyList() }

    private fun refusal(g: ScopeGate, host: String, port: Int = 9, datagram: Boolean = true): String {
        val e = runCatching { if (datagram) g.clearDatagram(host, port) else g.clearConnect(host, port) }.exceptionOrNull()
        assertTrue("accepted: $host", e is ScopeViolation)
        return e!!.message!!
    }

    @Test fun `a lan tool that declared broadcast may send to the directed broadcast address`() {
        val c = guard(NetworkScope.LAN, true).clearDatagram("192.168.1.255", 9)
        assertTrue(c.broadcast)
        assertEquals("192.168.1.255", c.address.hostAddress)
    }

    @Test fun `the directed broadcast follows the prefix length`() {
        assertEquals("192.168.255.255", guard(NetworkScope.LAN, true, lan(16)).clearDatagram("192.168.255.255", 9).address.hostAddress)
        assertEquals("192.168.1.127", guard(NetworkScope.LAN, true, lan(25)).clearDatagram("192.168.1.127", 9).address.hostAddress)
        refusal(guard(NetworkScope.LAN, true, lan(25)), "192.168.1.255")           // outside a /25
        assertFalse("a host inside a /16 is not a broadcast", guard(NetworkScope.LAN, true, lan(16)).clearDatagram("192.168.1.255", 9).broadcast)
    }

    @Test fun `without the declaration the directed broadcast is refused for udp and for every other call`() {
        val g = guard(NetworkScope.LAN, false)
        assertEquals(ScriptCopy.BROADCAST_NOT_DECLARED, refusal(g, "192.168.1.255"))
        assertEquals(ScriptCopy.BROADCAST_NOT_DECLARED, refusal(g, "192.168.1.255", datagram = false))
    }

    @Test fun `a declared tool still cannot open a connection to the broadcast address`() {
        val g = guard(NetworkScope.LAN, true)
        assertEquals(ScriptCopy.BROADCAST_UDP_ONLY, refusal(g, "192.168.1.255", datagram = false))
        assertTrue(runCatching { g.clearReverse("192.168.1.255") }.exceptionOrNull() is ScopeViolation)
    }

    @Test fun `the limited broadcast 255 255 255 255 is refused in every scope, declared or not`() {
        for (scope in NetworkScope.entries) for (declared in listOf(true, false)) {
            val g = guard(scope, declared)
            refusal(g, "255.255.255.255"); refusal(g, "255.255.255.255", datagram = false)
        }
        refusal(guard(NetworkScope.LAN, true, lan(0)), "255.255.255.255")
    }

    @Test fun `only the current subnet's directed broadcast is allowed, not another subnet's`() {
        val g = guard(NetworkScope.LAN, true)
        for (host in listOf("192.168.2.255", "192.168.0.255", "10.0.0.255", "172.16.255.255", "192.168.1.0", "224.0.0.1", "127.0.0.1", "192.168.1.50")) refusal(g, host)
    }

    @Test fun `broadcast is only for the lan scope`() {
        refusal(guard(NetworkScope.TARGET, true), "192.168.1.255")
        refusal(guard(NetworkScope.ANY, true), "192.168.1.255")
        refusal(guard(NetworkScope.ANY, false), "192.168.1.255")
        refusal(guard(NetworkScope.ANY, true), "192.168.1.255", datagram = false)
    }

    @Test fun `a name never counts as the broadcast address`() {
        val g = NetworkScopeGuard(NetworkScope.LAN, target, lan(), true) { listOf(java.net.InetAddress.getByName("192.168.1.255")) }
        refusal(g, "bcast.local")
    }

    @Test fun `no network and no broadcast address on a point to point link`() {
        refusal(guard(NetworkScope.LAN, true, null), "192.168.1.255")
        refusal(guard(NetworkScope.LAN, true, lan(31)), "192.168.1.255")
        refusal(guard(NetworkScope.LAN, true, lan(32)), "192.168.1.255")
    }

    @Test fun `ordinary datagrams under lan follow the usual rules and are not marked broadcast`() {
        val g = guard(NetworkScope.LAN, true)
        val c = g.clearDatagram("192.168.1.20", 9)
        assertFalse(c.broadcast)
        refusal(g, "192.168.1.50"); refusal(g, "10.0.0.5"); refusal(g, "192.168.1.255", port = 0)
    }

    @Test fun `the target scope clearDatagram is clearConnect`() {
        val g = guard(NetworkScope.TARGET, false)
        assertEquals("192.168.1.20", g.clearDatagram("192.168.1.20", 9).address.hostAddress)
        refusal(g, "192.168.1.21")
    }
}
