package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** Each scope, each refusal (D4, G2, G3; Gruff review findings 2 and 3). No resolver here ever reaches a real DNS server. */
class NetworkScopeGuardTest {
    private val target = ScopeTarget(Ipv4.parse("192.168.1.20")!!, "nas.local")
    private val lan = ScopeLan(Ipv4.parse("192.168.1.50")!!, 24)
    private val lookups = ArrayList<String>()
    private var answers: (String) -> List<InetAddress> = { emptyList() }

    private fun guard(scope: NetworkScope, t: ScopeTarget? = target, l: ScopeLan? = lan) =
        NetworkScopeGuard(scope, t, l) { lookups += it.name; answers(it.name) }

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private fun refused(g: ScopeGate, host: String, port: Int = 80, message: String? = null) {
        val e = runCatching { g.clearConnect(host, port) }.exceptionOrNull()
        assertTrue("accepted: $host", e is ScopeViolation)
        if (message != null) assertEquals(host, message, e!!.message)
    }
    private fun cleared(g: ScopeGate, host: String, port: Int = 80): Cleared = g.clearConnect(host, port)

    // TARGET

    @Test fun `target scope accepts only the device by address or by its own name`() {
        val g = guard(NetworkScope.TARGET)
        assertEquals(target.ip, cleared(g, "192.168.1.20", 8080).address)
        assertEquals(target.ip, cleared(g, "NAS.local").address)
        assertTrue("the device's own name is never looked up", lookups.isEmpty())
        refused(g, "192.168.1.21"); refused(g, "10.0.0.1"); refused(g, "127.0.0.1"); refused(g, "other.local"); refused(g, "example.com")
        refused(g, "2001:db8::1"); refused(g, "::1")
        assertTrue(lookups.isEmpty())
    }

    @Test fun `target scope with no device refuses everything`() {
        val g = guard(NetworkScope.TARGET, t = null)
        refused(g, "192.168.1.20", message = "This tool can only contact the device it is run against, and none was chosen")
        refused(g, "nas.local")
        assertTrue(runCatching { g.clearName("nas.local") }.exceptionOrNull() is ScopeViolation)
    }

    @Test fun `target scope limits dns to the device's own name`() {
        val g = guard(NetworkScope.TARGET)
        assertEquals("nas.local", g.clearName("nas.local").name)
        assertEquals("nas.local", g.clearName("NAS.LOCAL").name)
        assertEquals("192.168.1.20", g.clearName("192.168.1.20").name)
        for (name in listOf("secret.evil.com", "other.local", "192.168.1.21", "evil.com.", "x".repeat(64) + ".com")) {
            assertTrue(name, runCatching { g.clearName(name) }.exceptionOrNull() is ScopeViolation)
        }
        assertEquals(target.ip, g.clearReverse("192.168.1.20").address)
        assertTrue(runCatching { g.clearReverse("192.168.1.21") }.exceptionOrNull() is ScopeViolation)
        assertTrue(runCatching { g.clearReverse("nas.local") }.exceptionOrNull() is ScopeViolation)
    }

    @Test fun `a target without a name accepts only its address`() {
        val g = guard(NetworkScope.TARGET, t = ScopeTarget(target.ip, null))
        assertEquals(target.ip, cleared(g, "192.168.1.20").address)
        refused(g, "nas.local")
    }

    // Non-canonical literals, in every scope

    private val spellings = listOf(
        "3232235796",              // decimal
        "0xC0A80114", "0xc0.0xa8.0x01.0x14", "0xC0.168.1.20",      // hex
        "0300.0250.0001.0024", "0300.168.1.20", "192.168.001.020", "192.168.1.020",      // octal and leading zeros
        "192.168.276", "192.11010068", "192.168.1", "192.168",       // short forms
        "192.168.1.20.", "192.168.1.20.5", "192.168.1.256", "192.168.1.-1", "192.168.1.+20", "192.168.1.2 0", " 192.168.1.20", "192.168.1.20 ",   // junk
        "::ffff:192.168.1.20", "::ffff:c0a8:114", "[::ffff:192.168.1.20]", "0:0:0:0:0:ffff:c0a8:114", "::192.168.1.20", "::c0a8:114", "fe80::1%wlan0", "2001:db8::1%1",   // v6 forms
        "0x7f.1", "2130706433", "017700000001", "127.1", "0177.0.0.1", "0", "00", "0x0",      // loopback spellings
    )

    @Test fun `non canonical literals are refused in every scope`() {
        for (scope in NetworkScope.entries) {
            val g = guard(scope)
            for (s in spellings) refused(g, s)
            for (s in spellings) assertTrue("$scope dns $s", runCatching { g.clearName(s) }.exceptionOrNull() is ScopeViolation)
            for (s in spellings) assertTrue("$scope reverse $s", runCatching { g.clearReverse(s) }.exceptionOrNull() is ScopeViolation)
        }
        assertTrue("no spelling ever reached the resolver", lookups.isEmpty())
    }

    @Test fun `invalid host text is refused`() {
        val g = guard(NetworkScope.ANY)
        for (h in listOf("", "a b", "a..b", "-a.com", "a-.com", "a_b.-c", "é.com", "a@b.com", "a/b", "a\\b", "a:b", "http://x", "a\u0000b", "x".repeat(64) + ".com", "a.".repeat(130), "example.com.")) refused(g, h)
        assertTrue(lookups.isEmpty())
    }

    @Test fun `ports are checked`() {
        val g = guard(NetworkScope.TARGET)
        for (p in listOf(0, -1, 65536, 100000)) refused(g, "192.168.1.20", p, "The port must be a number from 1 to 65535")
        assertEquals(65535, cleared(g, "192.168.1.20", 65535).address.let { 65535 })
    }

    // LAN

    @Test fun `lan scope accepts hosts of the current subnet only`() {
        val g = guard(NetworkScope.LAN)
        for (h in listOf("192.168.1.1", "192.168.1.20", "192.168.1.254")) assertEquals(ip(h), cleared(g, h).address)
        for (h in listOf("192.168.1.0", "192.168.1.255", "192.168.1.50", "192.168.2.1", "192.168.0.255", "10.0.0.1", "172.16.0.1", "8.8.8.8", "127.0.0.1", "127.1.2.3", "0.0.0.0",
            "224.0.0.251", "239.255.255.250", "255.255.255.255", "169.254.1.1", "100.64.0.1", "::1", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1")) refused(g, h)
    }

    @Test fun `lan scope honours the prefix length`() {
        val wide = guard(NetworkScope.LAN, l = ScopeLan(Ipv4.parse("10.1.2.3")!!, 16))
        assertEquals(ip("10.1.200.9"), cleared(wide, "10.1.200.9").address)
        refused(wide, "10.1.0.0"); refused(wide, "10.1.255.255"); refused(wide, "10.2.0.1"); refused(wide, "10.1.2.3")
        val p2p = guard(NetworkScope.LAN, l = ScopeLan(Ipv4.parse("10.9.9.4")!!, 31))
        assertEquals(ip("10.9.9.5"), cleared(p2p, "10.9.9.5").address)
        refused(p2p, "10.9.9.4"); refused(p2p, "10.9.9.6")
        val single = guard(NetworkScope.LAN, l = ScopeLan(Ipv4.parse("10.9.9.4")!!, 32))
        refused(single, "10.9.9.4"); refused(single, "10.9.9.5")
        val small = guard(NetworkScope.LAN, l = ScopeLan(Ipv4.parse("10.9.9.5")!!, 30))
        assertEquals(ip("10.9.9.6"), cleared(small, "10.9.9.6").address)
        refused(small, "10.9.9.4"); refused(small, "10.9.9.7")
    }

    @Test fun `lan scope with no network refuses everything`() {
        val g = guard(NetworkScope.LAN, l = null)
        refused(g, "192.168.1.20", message = "This tool needs a connected network")
        refused(g, "printer.local", message = "This tool needs a connected network")
    }

    @Test fun `a name is resolved once and the chosen address is the connected one`() {
        val g = guard(NetworkScope.LAN)
        val wanted = ip("192.168.1.7")
        answers = { listOf(ip("8.8.8.8"), wanted, ip("192.168.1.8")) }
        val c = cleared(g, "Printer.local", 631)
        assertSame(wanted, c.address)
        assertEquals("Printer.local", c.host)
        assertEquals(listOf("printer.local"), lookups)
    }

    @Test fun `a name that resolves outside the lan is refused, however it answers`() {
        val g = guard(NetworkScope.LAN)
        for (answer in listOf(listOf("8.8.8.8"), listOf("127.0.0.1"), listOf("192.168.1.0"), listOf("192.168.1.255"), listOf("192.168.1.50"), listOf("::1"), listOf("fe80::1"), listOf("224.0.0.1"), emptyList())) {
            answers = { answer.map { ip(it) } }
            refused(g, "rebind.example.com", message = "The name does not lead to an address this tool may contact")
        }
    }

    @Test fun `a v4 mapped answer is treated as the plain address`() {
        val g = guard(NetworkScope.LAN)
        answers = { listOf(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -64, -88, 1, 9.toByte()))) }
        assertTrue(answers("x").single() is Inet4Address)
        assertEquals(ip("192.168.1.9"), cleared(g, "mapped.local").address)
        answers = { listOf(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1))) }
        refused(g, "mapped-loopback.local")
    }

    @Test fun `lan dns lookups are limited to local looking names`() {
        val g = guard(NetworkScope.LAN)
        for (n in listOf("printer", "printer.local", "nas.lan", "x.home.arpa", "box.localdomain", "a.b.internal")) assertEquals(n, n, g.clearName(n).name)
        for (n in listOf("secret.evil.com", "example.org", "a.b.c.d.example.net", "local.evil.com")) {
            assertTrue(n, runCatching { g.clearName(n) }.exceptionOrNull() is ScopeViolation)
        }
        assertEquals(ip("192.168.1.9"), g.clearReverse("192.168.1.9").address)
        assertTrue(runCatching { g.clearReverse("8.8.8.8") }.exceptionOrNull() is ScopeViolation)
        assertTrue(runCatching { g.clearReverse("192.168.1.255") }.exceptionOrNull() is ScopeViolation)
    }

    // ANY

    @Test fun `any scope accepts unicast addresses of every range except the unsafe ones`() {
        val g = guard(NetworkScope.ANY)
        for (h in listOf("93.184.216.34", "10.0.0.1", "172.16.5.5", "192.168.9.9", "100.64.0.1", "169.254.7.7", "2001:db8::1", "fd00::5", "2606:4700::1111")) assertEquals(h, ip(h), cleared(g, h).address)
        for (h in listOf("127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.1.1.1", "255.255.255.255", "240.0.0.1", "255.255.255.254", "192.168.1.50", "::1", "::", "fe80::1", "ff02::1", "ff05::2")) refused(g, h)
    }

    @Test fun `any scope resolves names and skips unsafe answers`() {
        val g = guard(NetworkScope.ANY)
        answers = { listOf(ip("127.0.0.1"), ip("224.0.0.1"), ip("93.184.216.34")) }
        assertEquals(ip("93.184.216.34"), cleared(g, "example.com").address)
        answers = { listOf(ip("127.0.0.1")) }
        refused(g, "localhost")
        assertEquals("secret.evil.com", g.clearName("Secret.Evil.com").name)
        assertEquals(ip("8.8.8.8"), g.clearReverse("8.8.8.8").address)
        assertTrue(runCatching { g.clearReverse("224.0.0.1") }.exceptionOrNull() is ScopeViolation)
    }

    @Test fun `any scope without a network still works for public addresses`() {
        val g = guard(NetworkScope.ANY, t = null, l = null)
        assertEquals(ip("93.184.216.34"), cleared(g, "93.184.216.34").address)
        refused(g, "127.0.0.1")
    }

    @Test fun `the default gate refuses everything`() {
        refused(DenyAllGate, "192.168.1.20", message = "This tool has no network access")
        assertTrue(runCatching { DenyAllGate.clearName("x.local") }.exceptionOrNull() is ScopeViolation)
        assertTrue(runCatching { DenyAllGate.clearReverse("192.168.1.20") }.exceptionOrNull() is ScopeViolation)
    }
}
