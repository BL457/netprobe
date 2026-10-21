package com.northwalessolutions.netprobe.engine.common

import android.content.Context
import android.net.wifi.WifiManager

/** A Wi-Fi lock NetProbe holds. acquire and release never throw; release is a no-op when not held. */
interface LockHandle {
    fun acquire()
    fun release()
}

/**
 * The scan's multicast and high-performance locks, and the listener's own multicast lock (3C spec 3.2). They are separate objects: a scan
 * ending calls [release], which never touches [listen], and ListenController releasing [listen] never touches the scan's locks.
 */
class Locks internal constructor(private val multicast: LockHandle, private val perf: LockHandle, val listen: LockHandle) {
    constructor(context: Context) : this(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)

    @Suppress("DEPRECATION")
    private constructor(wifi: WifiManager) : this(
        MulticastHandle(wifi.createMulticastLock(SCAN_TAG)),
        WifiHandle(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, PERF_TAG)),
        MulticastHandle(wifi.createMulticastLock(LISTEN_TAG)))

    fun acquire() { multicast.acquire(); perf.acquire() }
    fun release() { multicast.release(); perf.release() }

    // runCatching as before 3C: a refused lock only means fewer multicast frames or a slower radio; it must never fail a scan or a session.
    private class MulticastHandle(private val lock: WifiManager.MulticastLock) : LockHandle {
        init { lock.setReferenceCounted(false) }
        override fun acquire() { runCatching { lock.acquire() } }
        override fun release() { runCatching { if (lock.isHeld) lock.release() } }
    }
    private class WifiHandle(private val lock: WifiManager.WifiLock) : LockHandle {
        init { lock.setReferenceCounted(false) }
        override fun acquire() { runCatching { lock.acquire() } }
        override fun release() { runCatching { if (lock.isHeld) lock.release() } }
    }

    companion object {
        const val SCAN_TAG = "netprobe-mdns"
        const val PERF_TAG = "netprobe-scan"
        const val LISTEN_TAG = "netprobe-listen"
    }
}
