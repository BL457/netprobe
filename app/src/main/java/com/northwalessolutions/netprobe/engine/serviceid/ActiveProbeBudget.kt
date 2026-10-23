package com.northwalessolutions.netprobe.engine.serviceid

import android.os.SystemClock
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import java.util.concurrent.atomic.AtomicLong

const val MAX_ACTIVE_PORTS = 64
const val MAX_ACTIVE_MS = 60_000L
const val HOST_WALL_MS = 20_000L

/**
 * How far one port's ladder may go: connections, wall time, and the highest rarity tried on a port with no hint. [SCAN] is what a scan
 * uses (many ports, many hosts); [THOROUGH] is for a manual identify of one port or one device, where the user is waiting for the answer
 * and every safe hello is worth sending. The fragile, industrial and hint-only port rules are not limits and apply under both.
 */
data class LadderLimits(val maxConnects: Int, val portWallMs: Long, val maxRarity: Int,
                        /** Manual run: every hello in the file is tried (safe by rarity, then careful by rarity), fragile and hint-only ports included. Industrial ports keep their careful-only rule. */
                        val allHellos: Boolean = false,
                        /** Settings "Include unsafe Hellos": the CAREFUL probes follow the SAFE ones on every port, industrial and fragile included. Off, a manual run sends no CAREFUL probe except on an industrial port's own careful-only rule. */
                        val includeUnsafe: Boolean = false) {
    companion object {
        const val SCAN_MAX_CONNECTS = 6
        const val SCAN_PORT_WALL_MS = 6_000L
        const val SCAN_MAX_RARITY = 3
        /** No wall at all: a manual run ends on a confirmed match, on every hello tried, or on Stop. Per-step timeouts still bound each step. */
        const val NO_WALL_MS = Long.MAX_VALUE
        const val MAX_RARITY = 9
        val SCAN = LadderLimits(SCAN_MAX_CONNECTS, SCAN_PORT_WALL_MS, SCAN_MAX_RARITY)
        /** No connection cap beyond the plan itself (the ladder caps at the number of probes planned), every rarity, no wall, every hello. */
        val THOROUGH = LadderLimits(Int.MAX_VALUE, NO_WALL_MS, MAX_RARITY, allHellos = true)
        /** The manual-run limits for the Settings switch. */
        fun thorough(includeUnsafe: Boolean): LadderLimits = if (includeUnsafe) THOROUGH.copy(includeUnsafe = true) else THOROUGH
    }
}

/** What an identify may send (spec 2.2.1). */
sealed interface ProbeMode {
    /** Detail Quick and every host of Quick scan all: exactly what was sent before Phase 3A. */
    data object Passive : ProbeMode
    /** Detail Full and Custom: today's steps, then the active ladder under one budget per scan. */
    /** [hellos] false (every scan, Director ruling R1) keeps only the 3A-equivalent steps: the first six probes of the file, no stack paths, no favicon. */
    class Active(val budget: ActiveProbeBudget, val hellos: Boolean = true, val limits: LadderLimits = LadderLimits.SCAN) : ProbeMode
}

/**
 * One per Detail Full or Custom scan, shared by its identifies, never persisted (spec 2.2.4). A port enters once before its first
 * active step; every active step runs through [step], which refuses to start once [maxMs] of summed probe time is spent and
 * otherwise adds the step's own wall time. A started step finishes; the overshoot is at most 8 steps. A GET can take a connect
 * timeout, a read timeout and a 2 s body deadline, twice with its one redirect (about 12 s), so the real worst case is about
 * 60 s + 8 x 12 s of summed probe time, not the spec's "about 76 s" (plan review G12; recorded in the Task 19 handover).
 */
class ActiveProbeBudget(val maxPorts: Int = MAX_ACTIVE_PORTS, val maxMs: Long = MAX_ACTIVE_MS, val hostWallMs: Long = HOST_WALL_MS,
                        private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val spent = AtomicLong(0)
    /** Keyed by host and port (3E spec 3): a second host is not refused for sharing port numbers with the first. */
    private val entered = HashSet<Pair<Long, Int>>()
    private val hostSpent = java.util.concurrent.ConcurrentHashMap<Long, AtomicLong>()
    val spentMs: Long get() = spent.get()
    val enteredPorts: Int get() = synchronized(entered) { entered.size }

    fun allowsStep(): Boolean = spent.get() < maxMs

    fun tryEnter(hostId: Long, port: Int): Boolean {
        if (!allowsStep()) return false
        synchronized(entered) {
            val key = hostId to port
            if (key in entered) return true
            if (entered.size >= maxPorts) return false
            entered += key
            return true
        }
    }

    /** Ladder time (every connection of every port, null probe included) a host has used; the ladder stops starting connections at [hostWallMs]. */
    fun hostSpentMs(hostId: Long): Long = hostSpent[hostId]?.get() ?: 0L
    fun hostAllows(hostId: Long): Boolean = hostSpentMs(hostId) < hostWallMs
    fun addHostTime(hostId: Long, ms: Long) { hostSpent.computeIfAbsent(hostId) { AtomicLong(0) }.addAndGet(ms) }

    suspend fun <T> step(block: suspend () -> T): T? {
        if (!allowsStep()) return null
        val t0 = clock()
        try { return block() } finally { spent.addAndGet(clock() - t0) }
    }
}

object ProbeModes {
    /**
     * Every scan is passive (3E ruling R1): Quick, and so every host of Quick scan all, sends exactly what was sent before Phase 3A; Full and Custom
     * send the 3A-equivalent steps (the null read, the first six probes on their hinted ports, the TLS check) and never a hello ladder, a stack
     * path or a favicon request. The full ladder runs only on request (ProbeMode.Active with hellos on, from the Identify tools).
     */
    fun forProfile(profile: ScanProfile, newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget() }): ProbeMode = when (profile) {
        // Exhaustive on purpose: a new profile must choose its mode here rather than fail open into the active ladder.
        ScanProfile.Quick -> ProbeMode.Passive
        ScanProfile.Full, is ScanProfile.Custom -> ProbeMode.Active(newBudget(), hellos = false)
    }
}
