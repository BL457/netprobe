package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy

/**
 * One page to draw (spec section 12). [allow] is the run's network scope: the renderer calls it for the page and for every redirect hop and
 * every resource the page asks for, before connecting, and must not fetch a URL it answered false for. [mainFrame] is true for the page
 * itself and its redirects, false for images, scripts and the like (a refused resource is simply left out of the picture).
 */
class RenderRequest(val url: String, val timeoutMs: Int, val width: Int, val allow: (url: String, mainFrame: Boolean) -> Boolean)

/** A drawn page: the HTTP status of the page itself, its title, the address it ended on and the picture as JPEG, at most [SnapshotLimits.MAX_IMAGE_WIDTH] wide. */
class RenderedPage(val status: Int, val title: String, val finalUrl: String, val jpeg: ByteArray)

/** A page that could not be drawn (no connection, a timeout, no web view on the phone). The script sees the message as a catchable error. */
class RenderFailure(message: String) : ScriptHostError(message)

/**
 * Draws a web page to a picture. The shipped implementation is an off-screen Android WebView; tests use a fake. [render] blocks the calling
 * script thread, draws one page at a time, and polls [IoScope.ensureAlive] while it waits so Stop ends it.
 */
interface PageRenderer {
    fun render(request: RenderRequest, io: IoScope): RenderedPage
}

/** The renderer a run has when the app supplies none: every snapshot fails with a clear message. */
object NoPageRenderer : PageRenderer {
    override fun render(request: RenderRequest, io: IoScope): RenderedPage = throw RenderFailure(ScriptCopy.SNAPSHOT_UNAVAILABLE)
}

/** The numbers of ctx.web.snapshot (spec section 12). */
object SnapshotLimits {
    const val MAX_PER_RUN = 200
    const val MAX_BYTES_PER_RUN = 20L * 1024 * 1024
    const val MAX_IMAGE_WIDTH = 640
    const val DEFAULT_WIDTH = 1024
    const val MIN_WIDTH = 320
    const val MAX_WIDTH = 2048
    const val DEFAULT_TIMEOUT_MS = 15_000
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    const val MAX_TITLE_CHARS = 200
}
