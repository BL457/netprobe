package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry

/** When "Forget this network" may run (3D spec D19): not while a scan, Quick scan all, a port scan or a tool run is live on it. */
object ForgetGuard {
    /** True when any host of the shown network holds a claim of either kind. [hosts] are every row of the network, manual targets included. */
    fun workRunning(hosts: List<HostEntity>, busy: Map<Long, HostBusyRegistry.Work>): Boolean = hosts.any { busy.containsKey(it.id) }

    fun allowed(radarRunning: Boolean, quickRunning: Boolean, workRunning: Boolean): Boolean = !radarRunning && !quickRunning && !workRunning
}
