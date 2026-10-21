package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 14 and the 3.1 status line. */
class ListenGateTest {
    @Test fun `null for each of switch off, background, no LAN and both protocols off`() {
        assertNull(ListenGate.plan(listen = false, mdns = true, ssdp = true, foreground = true, hasLan = true))
        assertNull(ListenGate.plan(listen = true, mdns = true, ssdp = true, foreground = false, hasLan = true))
        assertNull(ListenGate.plan(listen = true, mdns = true, ssdp = true, foreground = true, hasLan = false))
        assertNull(ListenGate.plan(listen = true, mdns = false, ssdp = false, foreground = true, hasLan = true))
    }
    @Test fun `the plan carries the mDNS and SSDP switches`() {
        assertEquals(ListenPlan(mdns = true, ssdp = true), ListenGate.plan(true, true, true, true, true))
        assertEquals(ListenPlan(mdns = true, ssdp = false), ListenGate.plan(true, true, false, true, true))
        assertEquals(ListenPlan(mdns = false, ssdp = true), ListenGate.plan(true, false, true, true, true))
    }
    @Test fun `what was opened names the status`() {
        assertEquals(ListenStatus.BOTH, ListenStatusRules.opened(true, true)); assertEquals(ListenStatus.MDNS_ONLY, ListenStatusRules.opened(true, false))
        assertEquals(ListenStatus.SSDP_ONLY, ListenStatusRules.opened(false, true)); assertEquals(ListenStatus.NO_PORTS, ListenStatusRules.opened(false, false))
    }
    @Test fun `the status line follows the switch, the protocols, the LAN, the session and a running scan`() {
        fun of(listen: Boolean = true, mdns: Boolean = true, ssdp: Boolean = true, fg: Boolean = true, lan: Boolean = true,
               session: ListenStatus? = ListenStatus.BOTH, scan: Boolean = false) = ListenStatusRules.of(listen, mdns, ssdp, fg, lan, session, scan)
        assertEquals(ListenStatus.OFF, of(listen = false)); assertEquals(ListenStatus.OFF, of(fg = false))
        assertEquals(ListenStatus.BOTH_OFF, of(mdns = false, ssdp = false, session = null))
        assertEquals(ListenStatus.NO_NETWORK, of(lan = false, session = null)); assertEquals(ListenStatus.NO_NETWORK, of(session = null))
        assertEquals(ListenStatus.BOTH, of()); assertEquals(ListenStatus.MDNS_ONLY, of(session = ListenStatus.MDNS_ONLY))
        assertEquals(ListenStatus.PAUSED_SCAN, of(scan = true)); assertEquals(ListenStatus.PAUSED_SCAN, of(session = ListenStatus.SSDP_ONLY, scan = true))
        assertEquals("nothing to pause when no port opened", ListenStatus.NO_PORTS, of(session = ListenStatus.NO_PORTS, scan = true))
        assertEquals("R3: an unscanned network says so", ListenStatus.NOT_SCANNED, of(session = ListenStatus.NOT_SCANNED, scan = true))
        assertEquals("G4: a failed session says it is retrying", ListenStatus.RETRYING, of(session = ListenStatus.RETRYING))
    }
}
