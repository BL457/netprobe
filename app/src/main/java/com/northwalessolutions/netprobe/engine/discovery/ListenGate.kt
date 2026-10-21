package com.northwalessolutions.netprobe.engine.discovery

/** What a listening session opens (3C spec 3.2). */
data class ListenPlan(val mdns: Boolean, val ssdp: Boolean)

object ListenGate {
    /** Null means: hold no lock, open no socket (spec 3.2). The mDNS and SSDP switches are the scan's own (Settings "Discovery"). */
    fun plan(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean): ListenPlan? =
        if (!listen || !foreground || !hasLan || (!mdns && !ssdp)) null else ListenPlan(mdns, ssdp)
}

/** The Settings status line under "Listen between scans" (spec 3.1); OFF shows nothing. */
enum class ListenStatus { OFF, BOTH, MDNS_ONLY, SSDP_ONLY, NO_PORTS, NO_NETWORK, PAUSED_SCAN, BOTH_OFF, NOT_SCANNED, RETRYING }

object ListenStatusRules {
    private val LISTENING = setOf(ListenStatus.BOTH, ListenStatus.MDNS_ONLY, ListenStatus.SSDP_ONLY)

    fun opened(mdns: Boolean, ssdp: Boolean): ListenStatus = when {
        mdns && ssdp -> ListenStatus.BOTH
        mdns -> ListenStatus.MDNS_ONLY
        ssdp -> ListenStatus.SSDP_ONLY
        else -> ListenStatus.NO_PORTS
    }

    /** [session] is what the running session opened, or NO_NETWORK when it found no network row, or null when no session runs. */
    fun of(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean, session: ListenStatus?, scanActive: Boolean): ListenStatus = when {
        !listen || !foreground -> ListenStatus.OFF
        !mdns && !ssdp -> ListenStatus.BOTH_OFF
        !hasLan || session == null -> ListenStatus.NO_NETWORK
        scanActive && session in LISTENING -> ListenStatus.PAUSED_SCAN
        else -> session
    }
}
