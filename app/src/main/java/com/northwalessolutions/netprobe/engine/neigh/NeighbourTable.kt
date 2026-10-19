package com.northwalessolutions.netprobe.engine.neigh

import android.os.Build
import java.io.File

object NeighbourTable {
    private const val LAST_SDK_WITH_READABLE_ARP_FILE = 28
    private const val ARP_FILE = "/proc/net/arp"
    private val loaded: Boolean = try { System.loadLibrary("neigh"); true } catch (_: Throwable) { false }
    private external fun dumpRaw(): Array<String>

    /** Never throws. Failure carries NeighbourUnavailable with the errno text. Falls back to /proc/net/arp on Android 9 and lower.
     *  Calls are serialised because the native side uses a static buffer. */
    @Synchronized
    fun dump(): Result<List<NeighbourEntry>> {
        val netlink = dumpNetlink()
        if (netlink.isSuccess || Build.VERSION.SDK_INT > LAST_SDK_WITH_READABLE_ARP_FILE) return netlink
        return runCatching { NeighbourParser.parseProcArp(File(ARP_FILE).readText()) }.fold(onSuccess = { Result.success(it) }, onFailure = { netlink })
    }

    private fun dumpNetlink(): Result<List<NeighbourEntry>> {
        if (!loaded) return Result.failure(NeighbourUnavailable("libneigh not loaded"))
        return try { NeighbourParser.parse(dumpRaw()) } catch (t: Throwable) { Result.failure(NeighbourUnavailable(t.toString())) }
    }
}
