package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.*
import java.net.Inet4Address

enum class RangeSource { MANUAL, EVIDENCE, GATEWAY_PROBE }
data class CandidateRange(val subnet: Subnet, val source: RangeSource)
/** [truncated] holds valid items rejected only because the cap was full, so the engine can report them. */
data class CandidateResult(val ranges: List<CandidateRange>, val truncated: List<CandidateRange>)

object CandidateRanges {
    const val MIN_CAP = 1
    const val MAX_CAP = 16
    private const val UNIT_PREFIX = 24

    /**
     * Extended spec 4.5: manual units first (list order), then evidence /24s by distinct-address count then address, then gateway
     * hits by address. An item is accepted if it is RFC1918, does not overlap the swept local range and does not overlap an earlier
     * item; accepting stops at the cap and later valid items go to [CandidateResult.truncated].
     */
    fun derive(scanned: Subnet, manual: List<Subnet>, evidence: List<Inet4Address>, gatewayHits: List<Subnet>, cap: Int): CandidateResult {
        val limit = cap.coerceIn(MIN_CAP, MAX_CAP)
        val ordered = ArrayList<CandidateRange>()
        manual.flatMap { CidrParser.units(it) }.forEach { ordered += CandidateRange(it, RangeSource.MANUAL) }
        evidence.filter { Rfc1918.contains(it) && !scanned.contains(it) }.distinct()
            .groupBy { SubnetMath.of(it, UNIT_PREFIX) }.entries
            .sortedWith(compareByDescending<Map.Entry<Subnet, List<Inet4Address>>> { it.value.size }.thenBy { it.key.start() })
            .forEach { ordered += CandidateRange(it.key, RangeSource.EVIDENCE) }
        gatewayHits.sortedBy { it.start() }.forEach { ordered += CandidateRange(it, RangeSource.GATEWAY_PROBE) }

        val accepted = ArrayList<CandidateRange>(); val truncated = ArrayList<CandidateRange>()
        for (c in ordered) {
            if (!Rfc1918.contains(c.subnet) || c.subnet.overlaps(scanned)) continue
            if (accepted.any { it.subnet.overlaps(c.subnet) } || truncated.any { it.subnet.overlaps(c.subnet) }) continue
            if (accepted.size < limit) accepted += c else truncated += c
        }
        return CandidateResult(accepted, truncated)
    }
}

object Evidence {
    /** RFC1918 DNS servers outside the swept range: on client sites usually the server range (extended spec 4.2). */
    fun dnsServers(env: NetworkEnv, scanned: Subnet): List<Inet4Address> =
        env.dnsServers.filterIsInstance<Inet4Address>().filter { Rfc1918.contains(it) && !scanned.contains(it) }
}
