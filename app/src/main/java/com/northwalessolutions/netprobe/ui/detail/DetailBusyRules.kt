package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel

/** Which Detail actions the host's current work refuses (spec 4.3); pure, so each refusal is tested without a view-model. */
object DetailBusyRules {
    /** Remove device is refused only while a tool run holds the host. */
    fun removeAllowed(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): Boolean = busy[hostId] != HostBusyRegistry.Work.TOOL

    /** A Detail port scan is refused by any work on the host; a tool run keeps its own wording. */
    fun scanRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): String? {
        val work = busy[hostId] ?: return null
        return if (work == HostBusyRegistry.Work.TOOL) ToolCopy.SCAN_REFUSED else ToolsUiModel.busyMessage(work)
    }

    /** A tool start is refused by any work on the host. */
    fun toolRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): String? = busy[hostId]?.let(ToolsUiModel::busyMessage)
}
