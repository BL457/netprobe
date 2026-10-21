package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.portscan.PortScans
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel

/** Which Detail actions the host's current work refuses (spec 4.3); pure, so each refusal is tested without a view-model. */
object DetailBusyRules {
    /** Remove device is refused only while a tool run holds the host. */
    fun removeAllowed(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): Boolean = busy[hostId] != HostBusyRegistry.Work.TOOL

    /**
     * A Detail port scan is refused by any work on the host (a tool run keeps its own wording), and, with [running] port scans already
     * at the cap, by the cap (3D spec 3.3). Work on the host itself wins, so the host's own running scan never reads as "too many".
     */
    fun scanRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long, running: Int = 0): String? {
        val work = busy[hostId] ?: return capRefusal(running)
        return if (work == HostBusyRegistry.Work.TOOL) ToolCopy.SCAN_REFUSED else ToolsUiModel.busyMessage(work)
    }

    /** The refusal line while [running] port scans occupy every slot, else null. */
    fun capRefusal(running: Int): String? =
        if (running >= PortScans.MAX_PORT_SCANS) DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS) else null

    /** A tool start is refused by any work on the host. */
    fun toolRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): String? = busy[hostId]?.let(ToolsUiModel::busyMessage)
}
