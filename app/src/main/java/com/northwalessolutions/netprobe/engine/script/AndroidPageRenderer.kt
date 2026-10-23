package com.northwalessolutions.netprobe.engine.script

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shipped [PageRenderer] (spec section 12): an off-screen Android WebView, created and used on the main thread, one page at a time.
 * JavaScript is on; cookies, DOM storage, file and content access, pop-up windows and Safe Browsing are off. Every request the page makes,
 * including each redirect hop, goes through [RenderRequest.allow] in shouldInterceptRequest before the WebView connects; a refused one gets
 * an empty 403. Certificate errors are accepted, as the script transport accepts them: LAN devices nearly all use self-signed certificates.
 * The picture is drawn at most [SnapshotLimits.MAX_IMAGE_WIDTH] wide and compressed to JPEG. The WebView keeps the app's normal user agent.
 */
class AndroidPageRenderer(context: Context, private val log: (Throwable) -> Unit = {}) : PageRenderer {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val oneAtATime = Semaphore(1)

    override fun render(request: RenderRequest, io: IoScope): RenderedPage {
        while (!oneAtATime.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) io.ensureAlive()
        try {
            val page = Page(request)
            main.post { page.start() }
            try {
                while (!page.done.await(POLL_MS, TimeUnit.MILLISECONDS)) io.ensureAlive()
            } catch (e: ScriptAbort) {
                page.abandon()
                throw e
            } catch (e: InterruptedException) {
                page.abandon()
                Thread.currentThread().interrupt()
                throw ScriptAbort(AbortReason.CANCELLED, ScriptCopy.abortSummary(AbortReason.CANCELLED))
            }
            return page.result()
        } finally {
            oneAtATime.release()
        }
    }

    /** One page's life. Everything except [done], [outcome] and the checks in shouldInterceptRequest runs on the main thread. */
    private inner class Page(private val req: RenderRequest) {
        val done = CountDownLatch(1)
        @Volatile private var outcome: RenderedPage? = null
        @Volatile private var failure: String? = null
        private var web: WebView? = null
        private var ended = false
        private var status = HTTP_OK
        private var captureQueued = false
        private val timeout = Runnable { fail(ScriptCopy.SNAPSHOT_TIMEOUT) }

        fun result(): RenderedPage = outcome ?: throw RenderFailure(failure ?: ScriptCopy.SNAPSHOT_ENGINE_FAILED)

        fun abandon() { main.post { fail(ScriptCopy.SNAPSHOT_STOPPED) } }

        fun start() {
            try {
                val w = WebView(app)
                web = w
                configure(w)
                w.webViewClient = client
                val height = req.width * VIEWPORT_HEIGHT_NUM / VIEWPORT_HEIGHT_DEN
                w.measure(View.MeasureSpec.makeMeasureSpec(req.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, req.width, height)
                main.postDelayed(timeout, req.timeoutMs.toLong())
                w.loadUrl(req.url)
            } catch (t: Throwable) {
                log(t)
                fail(ScriptCopy.SNAPSHOT_ENGINE_FAILED)
            }
        }

        private fun configure(w: WebView) {
            with(w.settings) {
                javaScriptEnabled = true
                domStorageEnabled = false
                @Suppress("DEPRECATION") databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportZoom(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            w.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(false)
            cookies.setAcceptThirdPartyCookies(w, false)
        }

        private fun fail(message: String) {
            if (ended) return
            failure = message
            end()
        }

        private fun end() {
            ended = true
            main.removeCallbacks(timeout)
            web?.let { w -> runCatching { w.stopLoading(); w.destroy() }.onFailure(log) }
            web = null
            done.countDown()
        }

        /** After the page finished: wait for the engine to have painted it, let scripts settle for a moment, then draw. */
        private fun queueCapture(w: WebView) {
            if (captureQueued || ended) return
            captureQueued = true
            w.postVisualStateCallback(VISUAL_STATE_ID, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { main.postDelayed({ capture() }, SETTLE_MS) }
            })
        }

        private fun capture() {
            val w = web
            if (ended || w == null) return
            try {
                val scale = min(1f, SnapshotLimits.MAX_IMAGE_WIDTH.toFloat() / req.width)
                val outW = max(1, (req.width * scale).roundToInt())
                val outH = max(1, (req.width * VIEWPORT_HEIGHT_NUM / VIEWPORT_HEIGHT_DEN * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val jpeg = try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    canvas.scale(scale, scale)
                    w.draw(canvas)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                } finally {
                    bitmap.recycle()
                }
                outcome = RenderedPage(status, w.title.orEmpty(), w.url.orEmpty(), jpeg)
                end()
            } catch (t: Throwable) {
                log(t)
                fail(ScriptCopy.SNAPSHOT_ENGINE_FAILED)
            }
        }

        private fun isWeb(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        private val client = object : WebViewClient() {
            /** Anything but http and https never loads. Web hops are cleared in shouldInterceptRequest, where blocking is allowed. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isWeb(request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isWeb(url) && req.allow(url, request.isForMainFrame)) return null
                if (request.isForMainFrame) main.post { fail(ScriptCopy.SNAPSHOT_ENGINE_FAILED) }
                return WebResourceResponse("text/plain", "utf-8", HTTP_FORBIDDEN, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageFinished(view: WebView, url: String?) { queueCapture(view) }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(ScriptCopy.snapshotLoadFailed(error.description.toString()))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) status = errorResponse.statusCode
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.proceed() }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(ScriptCopy.SNAPSHOT_ENGINE_FAILED)
                return true
            }
        }
    }

    private companion object {
        const val POLL_MS = 50L
        const val SETTLE_MS = 600L
        const val VISUAL_STATE_ID = 1L
        const val JPEG_QUALITY = 80
        const val VIEWPORT_HEIGHT_NUM = 3
        const val VIEWPORT_HEIGHT_DEN = 4
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
    }
}
