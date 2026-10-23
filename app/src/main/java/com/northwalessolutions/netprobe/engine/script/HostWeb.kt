package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * ctx.web.snapshot (spec section 12). Charges one host call, takes the run's gate, clears the first hop with the scope guard, then hands the
 * page to the [PageRenderer] with the guard as its `allow` test, so every redirect hop and resource is cleared before it is fetched. A page
 * the guard refuses as the page itself (any hop, or the address it ended on) is a [ScopeViolation] the script can catch. The picture is
 * stored by the [SnapshotStore] and named to the script by an opaque image id that only this run's findings accept.
 */
internal class HostWeb(
    private val s: RunSession, private val gate: ScopeGate, private val renderer: PageRenderer, private val store: SnapshotStore?, private val runId: Long,
) {
    private var stored = 0
    private var storedBytes = 0L

    fun snapshot(o: Opts): Map<String, Any?> {
        val url = ScriptUrl.parse(o.string("url"))
        val timeoutMs = o.int("timeoutMs", SnapshotLimits.DEFAULT_TIMEOUT_MS, SnapshotLimits.MIN_TIMEOUT_MS, SnapshotLimits.MAX_TIMEOUT_MS)
        val width = o.int("width", SnapshotLimits.DEFAULT_WIDTH, SnapshotLimits.MIN_WIDTH, SnapshotLimits.MAX_WIDTH)
        val files = store ?: throw RenderFailure(ScriptCopy.SNAPSHOT_UNAVAILABLE)
        if (stored >= SnapshotLimits.MAX_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_LIMIT)
        if (storedBytes >= SnapshotLimits.MAX_BYTES_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_SIZE_LIMIT)
        s.chargeCalls(1)
        s.gateWait()
        gate.clearConnect(url.host, url.port)
        val refusal = AtomicReference<ScopeViolation?>(null)
        val allow = { target: String, mainFrame: Boolean -> cleared(target, mainFrame, refusal) }
        val page = s.hostCall { renderer.render(RenderRequest(url.text, timeoutMs, width, allow), s) }
        refusal.get()?.let { throw it }
        if (!cleared(page.finalUrl, true, refusal)) throw refusal.get() ?: ScopeViolation(ScriptCopy.LAN_ONLY)
        s.addTransfer(page.jpeg.size.toLong())
        if (storedBytes + page.jpeg.size > SnapshotLimits.MAX_BYTES_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_SIZE_LIMIT)
        val n = stored + 1
        val relative = try { files.write(runId, n, page.jpeg) } catch (_: IOException) { throw ScriptHostError(ScriptCopy.SNAPSHOT_SAVE_FAILED) }
        stored = n
        storedBytes += page.jpeg.size
        val id = "img$n"
        s.images[id] = relative
        return mapOf("status" to page.status.toDouble(), "title" to page.title.take(SnapshotLimits.MAX_TITLE_CHARS), "finalUrl" to page.finalUrl, "image" to id)
    }

    /** The scope test for one URL. A refused page address is remembered for the script; a refused resource is just left out. */
    private fun cleared(target: String, mainFrame: Boolean, refusal: AtomicReference<ScopeViolation?>): Boolean {
        return try {
            val u = ScriptUrl.parse(target)
            gate.clearConnect(u.host, u.port)
            true
        } catch (e: ScopeViolation) {
            if (mainFrame) refusal.compareAndSet(null, e)
            false
        } catch (_: ScriptHostError) {
            false
        }
    }
}
