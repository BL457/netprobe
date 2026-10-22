package com.northwalessolutions.netprobe.engine.serviceid

import android.os.SystemClock
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import java.util.concurrent.atomic.AtomicLong

const val MAX_ACTIVE_PORTS = 64
const val MAX_ACTIVE_MS = 60_000L
const val HOST_WALL_MS = 20_000L

/** What an identify may send (spec 2.2.1). */
sealed interface ProbeMode {
    /** Detail Quick and every host of Quick scan all: exactly what was sent before Phase 3A. */
    data object Passive : ProbeMode
    /** Detail Full and Custom: today's steps, then the active ladder under one budget per scan. */
    class Active(val budget: ActiveProbeBudget) : ProbeMode
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
    /** Quick is passive, and so is every host of Quick scan all; Full and Custom get a fresh budget per scan (spec 2.2.1). */
    fun forProfile(profile: ScanProfile, newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget() }): ProbeMode = when (profile) {
        // Exhaustive on purpose: a new profile must choose its mode here rather than fail open into the active ladder.
        ScanProfile.Quick -> ProbeMode.Passive
        ScanProfile.Full, is ScanProfile.Custom -> ProbeMode.Active(newBudget())
    }
}
