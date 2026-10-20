package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Quick scan all always uses Quick, so it never runs the active ladder (spec 2.2.1, Gruff M3). */
object QuickScanAllPolicy {
    val profile: ScanProfile = ScanProfile.Quick
    fun mode(): ProbeMode = ProbeModes.forProfile(profile)
}

/**
 * Quick scan all's host loop (spec 5.3 step 1): [skip] is checked immediately before each host, and each host runs in its own child
 * job, registered in [jobs] before it starts (plan review G13), so Remove device can cancel exactly that host and wait for it. That
 * one cancellation means "skip this host"; stopping the whole scan still stops the loop. [scanOne] must catch its own failures.
 */
object QuickScanAllLoop {
    suspend fun <H> run(hosts: List<H>, idOf: (H) -> Long, skip: (H) -> Boolean, jobs: MutableMap<Long, Job>, scanOne: suspend (H) -> Unit) {
        coroutineScope {
            for (h in hosts) {
                if (skip(h)) continue
                val id = idOf(h)
                val job = launch(start = CoroutineStart.LAZY) { scanOne(h) }
                jobs[id] = job
                job.start()
                try { job.join() } finally { jobs.remove(id, job) }
                ensureActive()
            }
        }
    }
}
