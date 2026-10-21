package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner

/**
 * The structural key of the runner's active map for one host (3B QA F4): DetailViewModel.toolState re-emits only when this changes.
 * The runner republishes every PROGRESS_INTERVAL_MS with new done, found, errors and activeMs; none of those may be in the key, or the
 * Device screen recomposes on every tick again. They reach the screen through DetailViewModel.toolBar instead. Pure, so it is tested
 * without a view-model.
 */
object ToolStateKey {
    data class Key(val runId: Long?, val toolId: ToolId?, val totalKnown: Boolean, val runnerFull: Boolean)

    fun of(active: Map<Long, ToolRunner.Active>, hostId: Long): Key {
        val a = active[hostId]
        // totalKnown picks the determinate line over the indeterminate one; runnerFull feeds the options sheet's busy reason.
        return Key(a?.runId, a?.toolId, a?.total != null, active.size >= ToolRunner.MAX_ACTIVE_RUNS)
    }
}
