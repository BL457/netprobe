package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes

/** Quick scan all always uses Quick, so it never runs the active ladder (spec 2.2.1, Gruff M3). */
object QuickScanAllPolicy {
    val profile: ScanProfile = ScanProfile.Quick
    fun mode(): ProbeMode = ProbeModes.forProfile(profile)
}
