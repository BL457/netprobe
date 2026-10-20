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

/** The gateway probe's hit /24s and every address that answered, which the canary must not reuse (final review I3). */
data class GatewayProbeResult(val hits: List<Subnet>, val answered: Set<Inet4Address> = emptySet())

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

object GatewayProbe {
    const val TIMEOUT_MS = 400
    const val CONCURRENCY = 64
    val PORTS = listOf(80, 443)

    /** A /24 is a hit when any probed address in it connects or is refused on 80 or 443; hits come back ordered by address. [onAnswer] gets each answering address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
                    onAnswer: (Inet4Address) -> Unit = {},
                    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)): List<Subnet> {
        val units = targets.byUnit()
        val sem = Semaphore(CONCURRENCY)
        val hits = ConcurrentHashMap.newKeySet<Subnet>()
        val attempts = AtomicInteger()
        val lock = Any()
        var done = 0
        onProgress(0, units.size, 0)
        coroutineScope {
            for ((unit, addresses) in units) launch(dispatcher) {
                for (a in addresses) if (sem.withPermit { answers(a, connect, attempts) }) { onAnswer(a); hits += unit; break }
                // Counter and callback under one lock: two units finishing together can never report 272 before 271, so the status never steps back.
                synchronized(lock) { done++; onProgress(done, units.size, attempts.get()) }
            }
        }
        return hits.sortedBy { it.start() }
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
