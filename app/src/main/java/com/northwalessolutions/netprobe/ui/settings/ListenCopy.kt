package com.northwalessolutions.netprobe.ui.settings

import com.northwalessolutions.netprobe.engine.discovery.ListenStatus

/** Every "Listen between scans" string (3C spec 7.2). Templates live in CopyTableTest. */
object ListenCopy {
    const val LABEL = "Listen between scans"
    const val SUPPORT = "While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP."
    const val BOTH = "Listening for mDNS and SSDP"
    const val MDNS_ONLY = "Listening for mDNS only"
    const val SSDP_ONLY = "Listening for SSDP only"
    const val NO_PORTS = "Could not open the multicast ports"
    const val NO_NETWORK = "Not listening: connect to Wi-Fi or Ethernet"
    const val PAUSED_SCAN = "Paused while a scan runs"
    const val BOTH_OFF = "mDNS and SSDP are off above"
    /** Director ruling R3 (amendment A3): no completed scan on this network yet. */
    const val NOT_SCANNED = "Scan this network once to start listening"
    /** Plan review G4 (amendment A3): a session failed and is waiting its backoff before the next attempt. */
    const val RETRYING = "Listening stopped after an error, retrying"

    fun status(s: ListenStatus): String? = when (s) {
        ListenStatus.OFF -> null
        ListenStatus.BOTH -> BOTH
        ListenStatus.MDNS_ONLY -> MDNS_ONLY
        ListenStatus.SSDP_ONLY -> SSDP_ONLY
        ListenStatus.NO_PORTS -> NO_PORTS
        ListenStatus.NO_NETWORK -> NO_NETWORK
        ListenStatus.PAUSED_SCAN -> PAUSED_SCAN
        ListenStatus.BOTH_OFF -> BOTH_OFF
        ListenStatus.NOT_SCANNED -> NOT_SCANNED
        ListenStatus.RETRYING -> RETRYING
    }
}
