package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SweepRange

/**
 * What a scan run reads once at its start (3C spec 2.2, D21): Aggressive scan, the Large networks limit and this network's manual
 * ranges. The run's home range, binding and grouping use only this, so a toggle or a reset mid-run affects the next run only.
 */
data class RunSettings(val aggressive: Boolean, val maxPrefix: Int, val manual: List<Subnet>) {
    /** Rows outside the home range are bound to Wi-Fi when this holds (spec 2.4); with neither, binding is the MVP's (unbound). */
    val bindOutside: Boolean get() = aggressive || manual.isNotEmpty()
    fun homeRange(env: NetworkEnv): SweepRange = HomeRange.of(env.interfaceIp, env.prefixLength, maxPrefix, aggressive)
    fun home(env: NetworkEnv): Subnet = homeRange(env).subnet

    companion object {
        fun of(s: SettingsState, manual: List<Subnet>): RunSettings = RunSettings(s.extEnabled, s.maxPrefix, manual)
        /** The last radar run's snapshot when this network has one in memory, else the live settings (spec 2.2). */
        fun effective(snapshot: RunSettings?, live: SettingsState, liveManual: List<Subnet>): RunSettings = snapshot ?: of(live, liveManual)
    }
}
