package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The fetcher against a fake HttpURLConnection per URL: no socket is ever opened. */
class HttpsUpdateFetcherTest {
    private class Conn(url: URL, private val code: Int, private val location: String? = null, private val body: ByteArray = ByteArray(0),
                       private val declared: Long = body.size.toLong()) : HttpURLConnection(url) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getContentLengthLong() = declared
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private val opened = mutableListOf<String>()
    private fun fetcher(vararg routes: Pair<String, (URL) -> Conn>): HttpsUpdateFetcher {
        val map = routes.toMap()
        return HttpsUpdateFetcher { url -> opened += url.toString(); map.getValue(url.toString())(url) }
    }
    private fun run(f: HttpsUpdateFetcher, url: String, max: Long = 1000, out: ByteArrayOutputStream = ByteArrayOutputStream()) =
        runBlocking { f.fetch(URL(url), max, out) }

    private fun redirectTo(location: String): (URL) -> Conn = { u -> Conn(u, 302, location) }
    private fun serve(text: String): (URL) -> Conn = { u -> Conn(u, 200, body = text.toByteArray()) }

    @Test fun `a plain body is fetched`() {
        val out = ByteArrayOutputStream()
        val r = run(fetcher("https://a.example/x" to serve("hello")), "https://a.example/x", out = out)
        assertEquals(UpdateFetch.Ok(5), r); assertEquals("hello", out.toString())
    }

    @Test fun `https redirects across hosts are followed`() {
        val f = fetcher("https://github.example/latest" to redirectTo("https://objects.example/file"), "https://objects.example/file" to serve("ok"))
        assertEquals(UpdateFetch.Ok(2), run(f, "https://github.example/latest"))
        assertEquals(listOf("https://github.example/latest", "https://objects.example/file"), opened)
    }

    @Test fun `a relative redirect is resolved`() {
        val f = fetcher("https://a.example/x" to redirectTo("/y"), "https://a.example/y" to serve("z"))
        assertEquals(UpdateFetch.Ok(1), run(f, "https://a.example/x"))
    }

    @Test fun `an http hop is refused and never requested`() {
        val f = fetcher("https://a.example/x" to redirectTo("http://b.example/y"))
        assertEquals(UpdateFetch.Failed(FetchError.REFUSED), run(f, "https://a.example/x"))
        assertEquals(listOf("https://a.example/x"), opened)
    }

    @Test fun `an http start url is refused without a request`() {
        assertEquals(UpdateFetch.Failed(FetchError.REFUSED), run(fetcher(), "http://a.example/x"))
        assertTrue(opened.isEmpty())
    }

    @Test fun `a redirect into the local network is refused`() {
        val f = fetcher("https://a.example/x" to redirectTo("https://192.168.1.5/y"))
        assertEquals(UpdateFetch.Failed(FetchError.REFUSED), run(f, "https://a.example/x"))
    }

    @Test fun `the sixth redirect is refused`() {
        val routes = (0..6).map { i -> "https://h$i.example/" to redirectTo("https://h${i + 1}.example/") }
        assertEquals(UpdateFetch.Failed(FetchError.TOO_MANY_REDIRECTS), run(fetcher(*routes.toTypedArray()), "https://h0.example/"))
        assertEquals(6, opened.size)   // the original plus five followed hops
    }

    @Test fun `five redirects are followed`() {
        val routes = (0..4).map { i -> "https://h$i.example/" to redirectTo("https://h${i + 1}.example/") } + ("https://h5.example/" to serve("k"))
        assertEquals(UpdateFetch.Ok(1), run(fetcher(*routes.toTypedArray()), "https://h0.example/"))
    }

    @Test fun `a non-200 is a status failure`() {
        assertEquals(UpdateFetch.Failed(FetchError.STATUS), run(fetcher("https://a.example/x" to { u -> Conn(u, 404) }), "https://a.example/x"))
    }

    @Test fun `a declared length over the cap is refused before any byte is read`() {
        val out = ByteArrayOutputStream()
        val r = run(fetcher("https://a.example/x" to { u -> Conn(u, 200, body = ByteArray(10), declared = 5000) }), "https://a.example/x", max = 1000, out = out)
        assertEquals(UpdateFetch.Failed(FetchError.TOO_LARGE), r); assertEquals(0, out.size())
    }

    @Test fun `a body over the cap with no declared length is cut off`() {
        val r = run(fetcher("https://a.example/x" to { u -> Conn(u, 200, body = ByteArray(2000), declared = -1) }), "https://a.example/x", max = 1000)
        assertEquals(UpdateFetch.Failed(FetchError.TOO_LARGE), r)
    }

    @Test fun `a body shorter than its declared length is a connection failure`() {
        val r = run(fetcher("https://a.example/x" to { u -> Conn(u, 200, body = ByteArray(10), declared = 50) }), "https://a.example/x")
        assertEquals(UpdateFetch.Failed(FetchError.CONNECTION), r)
    }

    @Test fun `every connection is disconnected`() {
        val conns = mutableListOf<Conn>()
        val f = HttpsUpdateFetcher { u -> Conn(u, 200, body = "a".toByteArray()).also { conns += it } }
        run(f, "https://a.example/x")
        assertTrue(conns.isNotEmpty() && conns.all { it.disconnected })
    }
}
