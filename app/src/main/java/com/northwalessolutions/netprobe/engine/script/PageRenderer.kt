package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy

/**
 * One page to draw (spec section 12). [allow] is the run's network scope: the renderer calls it for the page and for every redirect hop and
 * every resource the page asks for, before connecting, and must not fetch a URL it answered false for. [mainFrame] is true for the page
 * itself and its redirects, false for images, scripts and the like (a refused resource is simply left out of the picture).
 */
class RenderRequest(
    val url: String, val timeoutMs: Int, val width: Int, val allow: (url: String, mainFrame: Boolean) -> Boolean,
    /** How long to wait after the page finished loading before drawing, inside [timeoutMs]. */
    val settleMs: Int = SnapshotLimits.DEFAULT_SETTLE_MS,
)

/**
 * A page that was loaded: the HTTP status of the page itself (0 when it never answered), its title, the address it ended on and the picture as
 * JPEG, at most [SnapshotLimits.MAX_IMAGE_WIDTH] wide. When the main frame failed (a network error, or a status of 400 or more) or drew nothing,
 * [jpeg] is null and [error] says why.
 */
class RenderedPage(val status: Int, val title: String, val finalUrl: String, val jpeg: ByteArray?, val error: String? = null)

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
    const val DEFAULT_SETTLE_MS = 2_500
    const val MAX_SETTLE_MS = 10_000
    const val DEFAULT_TIMEOUT_MS = 15_000
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    const val MAX_TITLE_CHARS = 200
}

/** How long the renderer may wait between the page finishing and drawing. */
object SettleTime {
    /** Room kept at the end of the overall timeout to draw and compress. */
    const val DRAW_MARGIN_MS = 500L
    /** [wantedMs] cut so it fits in [remainingMs] of the overall timeout; never negative. */
    fun clip(wantedMs: Int, remainingMs: Long): Long = minOf(wantedMs.toLong(), remainingMs - DRAW_MARGIN_MS).coerceAtLeast(0L)
}

/** The "has the page drawn anything" test, on a small copy of the picture. */
object BlankCheck {
    private const val CHANNEL_TOLERANCE = 2
    private const val SHIFT_RED = 16
    private const val SHIFT_GREEN = 8
    private const val MASK = 0xFF

    /** True when every pixel (ARGB ints) is the same colour, give or take [CHANNEL_TOLERANCE] per channel, or there are none. */
    fun isFlat(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val first = pixels[0]
        return pixels.all { close(it, first) }
    }

    private fun channel(p: Int, shift: Int) = (p shr shift) and MASK
    private fun close(a: Int, b: Int): Boolean = listOf(SHIFT_RED, SHIFT_GREEN, 0).all { Math.abs(channel(a, it) - channel(b, it)) <= CHANNEL_TOLERANCE }
}
