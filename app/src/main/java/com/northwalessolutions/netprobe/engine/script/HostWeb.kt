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
        val settleMs = o.int("settleMs", SnapshotLimits.DEFAULT_SETTLE_MS, 0, SnapshotLimits.MAX_SETTLE_MS)
        val files = store ?: throw RenderFailure(ScriptCopy.SNAPSHOT_UNAVAILABLE)
        if (stored >= SnapshotLimits.MAX_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_LIMIT)
        if (storedBytes >= SnapshotLimits.MAX_BYTES_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_SIZE_LIMIT)
        s.chargeCalls(1)
        s.gateWait()
        gate.clearConnect(url.host, url.port)
        val refusal = AtomicReference<ScopeViolation?>(null)
        val allow = { target: String, mainFrame: Boolean -> cleared(target, mainFrame, refusal) }
        val page = s.hostCall { renderer.render(RenderRequest(url.text, timeoutMs, width, allow, settleMs), s) }
        refusal.get()?.let { throw it }
        if (!cleared(page.finalUrl, true, refusal)) throw refusal.get() ?: ScopeViolation(ScriptCopy.LAN_ONLY)
        val jpeg = page.jpeg
        // A page that failed or drew nothing has no picture: nothing is written, charged or counted against the run's caps.
        if (jpeg == null) return result(page, null)
        s.addTransfer(jpeg.size.toLong())
        if (storedBytes + jpeg.size > SnapshotLimits.MAX_BYTES_PER_RUN) throw ScriptHostError(ScriptCopy.SNAPSHOT_SIZE_LIMIT)
        val n = stored + 1
        val relative = try { files.write(runId, n, jpeg) } catch (_: IOException) { throw ScriptHostError(ScriptCopy.SNAPSHOT_SAVE_FAILED) }
        stored = n
        storedBytes += jpeg.size
        val id = "img$n"
        s.images[id] = relative
        return result(page, id)
    }

    /** The script's view of a page: [image] is null when there is no picture, and [error] then says why. [httpStatus] is 0 when the page never answered. */
    private fun result(page: RenderedPage, image: String?): Map<String, Any?> = mapOf(
        "status" to page.status.toDouble(), "httpStatus" to page.status.toDouble(), "title" to page.title.take(SnapshotLimits.MAX_TITLE_CHARS),
        "finalUrl" to page.finalUrl, "image" to image, "error" to page.error,
    )

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
