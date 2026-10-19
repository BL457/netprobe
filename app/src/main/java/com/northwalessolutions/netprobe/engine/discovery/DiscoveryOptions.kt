package com.northwalessolutions.netprobe.engine.discovery

data class DiscoveryOptions(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true,
    val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000,
)
data class DiscoveryStatus(val probed: Int = 0, val total: Int = 0, val found: Int = 0, val clampedFrom: Int? = null, val neighbourAvailable: Boolean? = null)
