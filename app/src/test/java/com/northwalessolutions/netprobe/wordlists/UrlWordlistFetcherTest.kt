package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections

/**
 * 3C spec 8.1 item 12: the rules and wiring against a loopback server over HTTP, on the desktop JDK. Android's OkHttp-backed
 * HttpURLConnection (gzip, disconnect, redirects) is proved by UrlWordlistFetcherDeviceTest in Task 11.
 */
class UrlWordlistFetcherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val servers = mutableListOf<LoopbackHttpServer>()
    @After fun stop() { servers.forEach { it.close() } }
    private fun serve(handler: (LoopbackHttpServer.Request) -> LoopbackHttpServer.Response) = LoopbackHttpServer(handler).also { servers += it }
    private fun store() = WordlistStore(tmp.newFolder("wl-${System.nanoTime()}"), emptyList())
    private fun noListFiles(s: WordlistStore) = s.dirForTest.listFiles { f -> f.name.endsWith(".txt") }.isNullOrEmpty()
    private val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
    private suspend fun fetch(s: WordlistStore, url: URL) = UrlWordlistFetcher().fetch(url, s) { read, total -> progress += read to total }
    private fun plainHead(extra: String = "") = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n${extra}Connection: close\r\n\r\n"

    @Test fun `a text plain list is imported and named from the url`() = runBlocking {
        val body = "admin\nlogin\n/backup\n".toByteArray()
        val srv = serve { LoopbackHttpServer.ok("text/plain; charset=utf-8", body) }
        val r = fetch(store(), srv.url("/lists/words.txt")) as UrlImportResult.Stored
        val imported = r.result as ImportResult.Imported
        assertEquals("words", imported.meta.name); assertEquals(3, imported.meta.entries)
        assertEquals("the total comes from Content-Length", 0L to body.size.toLong(), progress.first())
    }
    @Test fun `a 404, an html page and a nul byte body give their failures and store nothing`() = runBlocking {
        val srv = serve { req ->
            when (req.path) {
                "/missing" -> LoopbackHttpServer.status(404, "Not Found")
                "/page" -> LoopbackHttpServer.ok("text/html; charset=utf-8", "<html><body>Sign in</body></html>".toByteArray())
                else -> LoopbackHttpServer.ok("application/octet-stream", ByteArray(64) { if (it == 10) 0 else 'a'.code.toByte() })
            }
        }
        val s = store()
        assertEquals(UrlImportResult.Failed(FetchFailure.Status(404)), fetch(s, srv.url("/missing")))
        assertEquals(UrlImportResult.Failed(FetchFailure.NotText("text/html")), fetch(s, srv.url("/page")))
        assertEquals(UrlImportResult.Failed(FetchFailure.NotText(null)), fetch(s, srv.url("/blob.bin")))
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a content length over the cap is rejected before any body byte is read`() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 11000000\r\n")) { out -> out.write("a\n".repeat(1_000).toByteArray()); out.flush() } }
        val s = store()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), fetch(s, srv.url("/big.txt")))
        assertTrue("no body progress at all", progress.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a body over 10 MB with no content length is TooLarge and nothing is kept`() = runBlocking {
        val chunk = "abcdefghij\n".repeat(6_000).toByteArray()   // 66,000 bytes; 170 of them are 11.2 MB
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out -> repeat(170) { out.write(chunk) }; out.flush() } }
        val s = store()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), fetch(s, srv.url("/huge.txt")))
        assertTrue(noListFiles(s)); assertTrue("no total without Content-Length", progress.all { it.second == null })
        assertTrue("progress is stepped, not per read", progress.size <= (WordlistStore.MAX_IMPORT_BYTES / UrlWordlistFetcher.PROGRESS_STEP_BYTES).toInt() + 2)
    }
    @Test fun `three redirects are followed and a fourth is refused`() = runBlocking {
        lateinit var srv: LoopbackHttpServer
        srv = serve { req ->
            when (req.path) {
                "/r1" -> LoopbackHttpServer.redirect(302, "/r2")
                "/r2" -> LoopbackHttpServer.redirect(301, "http://127.0.0.1:${srv.port}/r3")
                "/r3" -> LoopbackHttpServer.redirect(307, "/final/list.txt")
                "/a1" -> LoopbackHttpServer.redirect(302, "/a2")
                "/a2" -> LoopbackHttpServer.redirect(302, "/a3")
                "/a3" -> LoopbackHttpServer.redirect(302, "/a4")
                "/a4" -> LoopbackHttpServer.redirect(302, "/final/list.txt")
                else -> LoopbackHttpServer.ok("text/plain", "one\ntwo\n".toByteArray())
            }
        }
        val s = store()
        val ok = fetch(s, srv.url("/r1")) as UrlImportResult.Stored
        assertEquals("named from the final URL", "list", (ok.result as ImportResult.Imported).meta.name)
        assertEquals(UrlImportResult.Failed(FetchFailure.TooManyRedirects), fetch(s, srv.url("/a1")))
        assertEquals("the fourth hop's target is never requested", 1, srv.requests.count { it.path == "/final/list.txt" })
    }
    @Test fun `a hop whose public name resolves into the LAN is refused before it is requested`() = runBlocking {   // plan review G4
        val srv = serve { req ->
            when (req.path) {
                "/start" -> LoopbackHttpServer.redirect(302, "http://evil.test/x")
                "/start2" -> LoopbackHttpServer.redirect(302, "http://cdn.test/list.txt")
                else -> LoopbackHttpServer.ok("text/plain", "one\n".toByteArray())
            }
        }
        // Every URL is opened on the loopback server; only the names differ. No real lookup: the resolver is a table of literals.
        val fetcher = UrlWordlistFetcher(open = { u -> URL("http", "127.0.0.1", srv.port, u.file).openConnection() as HttpURLConnection },
            resolve = { host -> listOf(InetAddress.getByAddress(host, if (host == "evil.test") byteArrayOf(10, 0, 0, 5) else byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) })
        assertEquals(UrlImportResult.Failed(FetchFailure.LocalTarget), fetcher.fetch(URL("http://public.test/start"), store()))
        assertFalse("the LAN target is never requested", srv.requests.any { it.path == "/x" })
        assertTrue("a public name is followed", fetcher.fetch(URL("http://public.test/start2"), store()) is UrlImportResult.Stored)
    }
    @Test fun `a redirect without a location is the server's status`() = runBlocking {
        val srv = serve { LoopbackHttpServer.redirect(302, null) }
        assertEquals(UrlImportResult.Failed(FetchFailure.Status(302)), fetch(store(), srv.url("/x.txt")))
    }
    @Test fun `requests carry the user agent and accept header and never a cookie or authorization`() = runBlocking {
        assertNull("the app installs no CookieHandler", CookieHandler.getDefault())
        val srv = serve { LoopbackHttpServer.ok("text/plain", "x\n".toByteArray()) }
        fetch(store(), srv.url("/l.txt"))
        val h = srv.requests.single().headers
        assertEquals("NetProbe/0.1", h["user-agent"]); assertEquals("text/plain, */*;q=0.5", h["accept"])
        assertFalse("cookie" in h); assertFalse("authorization" in h)
    }
    @Test fun `a refused connection is a connection failure`() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }   // free, then closed: nothing listens
        assertEquals(UrlImportResult.Failed(FetchFailure.Connection), fetch(store(), URL("http://127.0.0.1:$port/l.txt")))
    }
    @Test fun `a server that sends headers then stalls past the read timeout is no answer, not an unreadable file`() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 5000\r\n")) { out -> out.write("abc\n".toByteArray()); out.flush(); Thread.sleep(STALL_MS) } }
        val s = store()
        val fetcher = UrlWordlistFetcher(readTimeoutMs = SHORT_READ_TIMEOUT_MS)
        assertEquals(UrlImportResult.Failed(FetchFailure.NoAnswer), fetcher.fetch(srv.url("/stall.txt"), s))
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a stall after the sniffed prefix is also no answer`() = runBlocking {
        val first = "abcdefghi\n".repeat(1_000).toByteArray()   // 10,000 bytes: past the 8 KB sniff, so the store's read is what times out
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 50000\r\n")) { out -> out.write(first); out.flush(); Thread.sleep(STALL_MS) } }
        val s = store()
        assertEquals(UrlImportResult.Failed(FetchFailure.NoAnswer), UrlWordlistFetcher(readTimeoutMs = SHORT_READ_TIMEOUT_MS).fetch(srv.url("/stall2.txt"), s))
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a body shorter than its content length is a connection failure and nothing is stored`() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 1000\r\n")) { out -> out.write("abc\n".repeat(25).toByteArray()); out.flush() } }
        val s = store()
        assertEquals(UrlImportResult.Failed(FetchFailure.Connection), fetch(s, srv.url("/short.txt")))
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `cancel during a stalled name lookup ends within a second`() = runBlocking {   // final review S1
        val srv = serve { LoopbackHttpServer.redirect(302, "http://slow.test/list.txt") }
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val fetcher = UrlWordlistFetcher(open = { u -> URL("http", "127.0.0.1", srv.port, u.file).openConnection() as HttpURLConnection },
            resolve = { entered.complete(Unit); release.await(); emptyList() })
        try {
            val job = launch(Dispatchers.Default) { fetcher.fetch(URL("http://public.test/start"), store()) }
            withTimeout(10_000) { entered.await() }
            val t0 = System.nanoTime(); withTimeoutOrNull(3_000) { job.cancelAndJoin() }; val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $ms ms", ms < 1_000)
        } finally { release.countDown() }
    }
    @Test fun `cancel during a slow body ends within a second and stores nothing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out ->
            repeat(600) { n -> out.write("word$n\n".repeat(100).toByteArray()); out.flush(); Thread.sleep(SLOW_CHUNK_MS) }
        } }
        val s = store()
        val job = launch(Dispatchers.Default) { UrlWordlistFetcher().fetch(srv.url("/slow.txt"), s) { _, _ -> started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(job.isCancelled)
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }

    private companion object { const val SLOW_CHUNK_MS = 100L; const val SHORT_READ_TIMEOUT_MS = 300; const val STALL_MS = 3_000L }
}
