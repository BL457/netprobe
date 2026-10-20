package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Subnet

/** Extended discovery inputs (extended spec 5.1). [knownIps] are the network's stored IPs, used to pick canary addresses (spec 4.7). */
data class ExtendedOptions(
    val enabled: Boolean = false,
    val maxRanges: Int = 4,                  // coerced to 1..16 by CandidateRanges.derive
    val gatewayProbe: Boolean = false,
    val manual: List<Subnet> = emptyList(),  // the current network's list (spec 4.4)
    val knownIps: Set<String> = emptySet(),
)

data class DiscoveryOptions(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true,
    val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000,
    val extended: ExtendedOptions = ExtendedOptions(),
)

enum class RangeState { PENDING, RUNNING, DONE, CANCELLED, PHANTOM, TRUNCATED }
enum class DiscoveryPhase { LOCAL, GATEWAY_PROBE, EXTENDED, DONE }
enum class ExtendedNote { GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, GATEWAY_PROBE_FOUND_NONE, NO_EXTRA_RANGES, SKIPPED_BIND_FAILED }

/** Per-range progress; probed and total are this range's own and are not monotonic across ranges. [source] null is the local range. */
data class RangeProgress(val subnet: String, val source: RangeSource?, val state: RangeState, val probed: Int, val total: Int, val found: Int)
/** [label] is the walked /16 (for example "10.0.0.0/16"); done and total count /24 units; [attempts] counts connects tried, for the device check. */
data class GatewayProbeProgress(val label: String, val done: Int, val total: Int, val attempts: Int = 0)

/** Top-level probed, total and found keep their MVP meaning (local sweep, monotonic total). */
data class DiscoveryStatus(
    val probed: Int = 0, val total: Int = 0, val found: Int = 0, val clampedFrom: Int? = null, val neighbourAvailable: Boolean? = null,
    val phase: DiscoveryPhase = DiscoveryPhase.LOCAL, val ranges: List<RangeProgress> = emptyList(), val truncatedRanges: Int = 0,
    val notes: List<ExtendedNote> = emptyList(), val gatewayProbe: GatewayProbeProgress? = null,
)
