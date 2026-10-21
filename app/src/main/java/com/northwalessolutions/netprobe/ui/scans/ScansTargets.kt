package com.northwalessolutions.netprobe.ui.scans

sealed interface ScansTap {
    object DevicesTab : ScansTap
    data class Device(val hostId: Long) : ScansTap
    data class Results(val runId: Long) : ScansTap
    object Wordlists : ScansTap
    object None : ScansTap
}

/** How Stop reaches the work. Every LiveWork item stops by its own id, so a stale tap can never stop a newer run of the same device. */
sealed interface StopRoute {
    data class Live(val id: Long) : StopRoute
    data class Tool(val hostId: Long) : StopRoute
}

/** Where a tap on a row goes and what its Stop does (3D spec 3.6), pure so each row of the table is tested. */
object ScansTargets {
    fun tap(item: ScansItem, visibleHostIds: Set<Long>): ScansTap {
        val hostId = item.hostId
        return when (item.source) {
            ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL -> ScansTap.DevicesTab
            ScansSource.PORT_SCAN -> if (hostId != null && hostId in visibleHostIds) ScansTap.Device(hostId) else ScansTap.None
            ScansSource.TOOL -> when {
                item.running && hostId != null && hostId in visibleHostIds -> ScansTap.Device(hostId)
                item.runId != null -> ScansTap.Results(item.runId)
                else -> ScansTap.None
            }
            ScansSource.WORDLIST_IMPORT -> ScansTap.Wordlists
        }
    }

    fun stop(item: ScansItem): StopRoute? {
        if (!item.running) return null
        return when (item.source) {
            ScansSource.TOOL -> item.hostId?.let { StopRoute.Tool(it) }
            else -> item.liveId?.let { StopRoute.Live(it) }
        }
    }
}
