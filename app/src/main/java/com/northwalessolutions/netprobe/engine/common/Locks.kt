package com.northwalessolutions.netprobe.engine.common

import android.content.Context
import android.net.wifi.WifiManager

class Locks(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicast = wifi.createMulticastLock("netprobe-mdns").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION") private val perf = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "netprobe-scan").apply { setReferenceCounted(false) }
    fun acquire() { runCatching { multicast.acquire() }; runCatching { perf.acquire() } }
    fun release() { runCatching { if (multicast.isHeld) multicast.release() }; runCatching { if (perf.isHeld) perf.release() } }
}
