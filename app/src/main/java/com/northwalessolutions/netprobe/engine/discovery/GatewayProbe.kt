package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.portscan.QuickPorts
import com.northwalessolutions.netprobe.netenv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The gateway probe's hit /24s, every address that answered (the canary must not reuse them, final review I3), and whether the outward
 * walk was skipped because more than [GatewayProbe.WALK_MAX_SEEDS] seeds answered (3C spec 2.5 step 1).
 */
data class GatewayProbeResult(val hits: List<Subnet>, val answered: Set<Inet4Address> = emptySet(), val walkSkippedManySeeds: Boolean = false)

/**
 * What the outward walk may do (3C spec 2.5 step 2, plan review G8). [budget] gives the cap slots left for a list of hits, computed by
 * the same CandidateRanges.derive that sweeps, so a walked hit derive would drop frees no slot. [skip] holds the manual and evidence
 * units, which are candidates already and are never probed by the walk.
 */
data class WalkPlan(val budget: (List<Subnet>) -> Int = { 0 }, val skip: List<Subnet> = emptyList())

/** What the gateway probe walks (extended spec 4.3 steps 1 to 4). */
data class GatewayTargets(val enclosing: Subnet?, val exclude: Subnet, val gatewayOffset: Int?, val seeds: List<Inet4Address>) {
    fun byUnit(): Map<Subnet, List<Inet4Address>> {
        val out = LinkedHashMap<Subnet, MutableList<Inet4Address>>()
        val offsets = listOfNotNull(LOW, HIGH, gatewayOffset).distinct()
        enclosing?.slash24Units()?.filterNot { it.overlaps(exclude) }?.forEach { u -> out[u] = offsets.map { SubnetMath.toAddress(u.network + it) }.toMutableList() }
        seeds.forEach { s -> out.getOrPut(SubnetMath.of(s, UNIT_PREFIX)) { mutableListOf() }.add(s) }
        return out
    }

    companion object {
        const val LOW = 1
        const val HIGH = 254
        private const val UNIT_PREFIX = 24
        private const val LAST_OCTET = 0xFF
        /** Common gateways in the other RFC1918 blocks, so a guest Wi-Fi on 192.168.x can find server ranges on 10.x or 172.16.x. */
        val SEEDS = listOf("10.0.0.1", "10.0.1.1", "10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "10.0.0.254", "172.16.0.1", "172.16.1.1",
            "172.16.16.1", "172.17.0.1", "172.31.0.1", "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1", "192.168.10.1",
            "192.168.88.1", "192.168.100.1", "192.168.178.1")

        fun of(env: NetworkEnv, scanned: Subnet): GatewayTargets {
            val enclosing = Rfc1918.enclosing16(env.interfaceIp)
            val offset = env.gateway?.let { SubnetMath.toInt(it) and LAST_OCTET }?.takeUnless { it == LOW || it == HIGH || it == 0 }
            val seeds = SEEDS.mapNotNull { Ipv4.parse(it) }.filter { s -> (enclosing == null || !enclosing.contains(s)) && !scanned.contains(s) }
            return GatewayTargets(enclosing, scanned, offset, seeds)
        }
    }
}

/** TEST-NET pre-check (extended spec 4.3 step 0): RFC 5737 addresses are never assigned, so any answer means "answers for everything". */
object AnswersForEverything {
    val TEST_NET: List<Inet4Address> = listOf("192.0.2.1", "198.51.100.1").map { Ipv4.parse(it)!! }
    const val TEST_PORT = 80
    fun answered(o: Probe.Outcome): Boolean = o is Probe.Outcome.Connected || o == Probe.Outcome.Refused
    fun of(outcomes: List<Probe.Outcome>): Boolean = outcomes.any(::answered)
}

/** One random high port per run, never a liveness or Quick port, for the pre-check and the canaries (extended spec 4.7). */
object RandomHighPort {
    const val MIN = 49152
    const val MAX_EXCLUSIVE = 65536
    private val EXCLUDED: Set<Int> = (HostProber.DEFAULT_PORTS + QuickPorts.list).toSet()
    fun pick(random: Random): Int {
        while (true) { val p = random.nextInt(MIN, MAX_EXCLUSIVE); if (p !in EXCLUDED) return p }
    }
}

/**
 * Smart discovery's outward walk (3C spec 2.5 steps 3 and 4): two directions per answering seed, seeds in ascending address order,
 * one unit per live direction per round, only the seed's own last octet probed, within the seed's /16. [probed] starts as the first
 * pass's units and grows as directions take units, so no unit is probed twice; [skip] units (manual, evidence) are never probed.
 */
internal class OutwardWalk(seeds: List<Inet4Address>, private val probed: MutableSet<Subnet>, private val home: Subnet,
                           private val skip: List<Subnet> = emptyList()) {
    class Direction(val seed: Inet4Address, val step: Int) {
        var misses = 0
        var ended = false
    }
    val directions: List<Direction> = seeds.sortedBy { SubnetMath.toInt(it).toLong() and UNSIGNED }.flatMap { listOf(Direction(it, DOWN), Direction(it, UP)) }

    /** Round [k]'s units in direction order; ends directions that cross the octet boundary. A skipped unit is neither a miss nor a hit. */
    fun round(k: Int): List<Pair<Direction, Inet4Address>> {
        val out = ArrayList<Pair<Direction, Inet4Address>>()
        for (d in directions) {
            if (d.ended) continue
            val s = SubnetMath.toInt(d.seed)
            val c = ((s ushr THIRD_SHIFT) and OCTET_MASK) + d.step * k
            if (c < 0 || c > OCTET_MASK) { d.ended = true; continue }   // the seed's own /16 ends here
            val a = SubnetMath.toAddress((s and THIRD_CLEAR) or (c shl THIRD_SHIFT))
            val unit = SubnetMath.of(a, UNIT_PREFIX)
            // The home range, a manual or evidence unit, or one already probed (first pass, a seed, another direction): skipped.
            if (unit.overlaps(home) || skip.any { it.overlaps(unit) } || !probed.add(unit)) continue
            out += d to a
        }
        return out
    }
    fun record(d: Direction, hit: Boolean, k: Int) {
        if (hit) d.misses = 0 else d.misses++
        if (d.misses >= GatewayProbe.OUTWARD_MISS_LIMIT || k >= GatewayProbe.OUTWARD_MAX_UNITS) d.ended = true
    }
    fun allEnded(): Boolean = directions.all { it.ended }

    private companion object {
        const val DOWN = -1
        const val UP = 1
        const val THIRD_SHIFT = 8
        const val OCTET_MASK = 0xFF
        const val UNIT_PREFIX = 24
        const val UNSIGNED = 0xFFFFFFFFL
        val THIRD_CLEAR = 0xFFFF00FF.toInt()
    }
}

object GatewayProbe {
    const val TIMEOUT_MS = 400
    const val CONCURRENCY = 64
    val PORTS = listOf(80, 443)
    /** More answering seeds than this reads as a bogon-reject firewall answering for every private address: no walk (3C spec 2.5 step 1, G12). */
    const val WALK_MAX_SEEDS = 4
    /** Furthest a direction walks from its seed, in /24 units. */
    const val OUTWARD_MAX_UNITS = 16
    /** Consecutive misses that end a direction. */
    const val OUTWARD_MISS_LIMIT = 2
    private const val UNIT_PREFIX = 24

    /**
     * First pass: a /24 is a hit when any probed address in it connects or is refused on 80 or 443. Then the outward walk (3C spec 2.5)
     * around answering seeds outside the enclosing /16, bounded by [walk] (no budget means no walk). Hits come back ordered by address.
     * [onAnswer] gets each answering address, first pass and walk.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
                    onAnswer: (Inet4Address) -> Unit = {},
                    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY),
                    walk: WalkPlan = WalkPlan()): GatewayProbeResult {
        val units = targets.byUnit()
        val sem = Semaphore(CONCURRENCY)
        val hits = ConcurrentHashMap.newKeySet<Subnet>()
        val answered = ConcurrentHashMap.newKeySet<Inet4Address>()
        val attempts = AtomicInteger()
        val lock = Any()
        var done = 0
        var total = units.size
        onProgress(0, total, 0)
        suspend fun probeOne(a: Inet4Address): Boolean {
            val ok = sem.withPermit { answers(a, connect, attempts) }
            if (ok) { answered += a; onAnswer(a) }
            return ok
        }
        // Counter and callback under one lock: two units finishing together can never report 272 before 271, so the status never steps back.
        fun finishedOne() = synchronized(lock) { done++; onProgress(done, total, attempts.get()) }

        coroutineScope {
            for ((unit, addresses) in units) launch(dispatcher) {
                for (a in addresses) if (probeOne(a)) { hits += unit; break }
                finishedOne()
            }
        }
        val firstPass = hits.sortedBy { it.start() }
        val enclosing = targets.enclosing
        val seeds = targets.seeds.filter { it in answered && (enclosing == null || !enclosing.contains(it)) }
        if (seeds.size > WALK_MAX_SEEDS) return GatewayProbeResult(firstPass, answered.toSet(), walkSkippedManySeeds = true)
        val budget = if (seeds.isEmpty()) 0 else walk.budget(firstPass)
        if (budget <= 0) return GatewayProbeResult(firstPass, answered.toSet())

        val outward = OutwardWalk(seeds, units.keys.toHashSet(), targets.exclude, walk.skip)
        for (k in 1..OUTWARD_MAX_UNITS) {
            if (outward.allEnded()) break
            val round = outward.round(k)
            if (round.isEmpty()) continue                                  // every live direction skipped a unit this round
            synchronized(lock) { total += round.size; onProgress(done, total, attempts.get()) }   // the total grows before the round starts
            val results = coroutineScope {
                round.map { (d, a) -> async(dispatcher) { val ok = probeOne(a); finishedOne(); Triple(d, a, ok) } }.awaitAll()
            }
            for ((d, a, ok) in results) {
                outward.record(d, ok, k)
                if (ok) hits += SubnetMath.of(a, UNIT_PREFIX)
            }
            // The same derive that sweeps decides (plan review G8): extra hits of this round stay, and derive reports them as truncated.
            if (walk.budget(hits.sortedBy { it.start() }) <= 0) break
        }
        return GatewayProbeResult(hits.sortedBy { it.start() }, answered.toSet())
    }

    /** Ports in order, stopping at the first definitive answer: a connect or refusal is yes, unreachable is no, a timeout tries the next. Each connect is one counted attempt. */
    private suspend fun answers(a: Inet4Address, connect: suspend (Inet4Address, Int) -> Probe.Outcome, attempts: AtomicInteger): Boolean {
        for (port in PORTS) {
            attempts.incrementAndGet()
            when (connect(a, port)) {
                is Probe.Outcome.Connected, Probe.Outcome.Refused -> return true
                Probe.Outcome.Unreachable -> return false
                Probe.Outcome.Timeout -> Unit
            }
        }
        return false
    }
}
