package com.northwalessolutions.netprobe.wordlists

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.zip.GZIPOutputStream

/**
 * 3C spec 8.2 item 7 (G10): Android's OkHttp-backed HttpURLConnection, on the phone, against a ServerSocket on 127.0.0.1. Writes only
 * under cacheDir/url-fetch-test-*: never the app's wordlists directory, its database or its preferences.
 */
@RunWith(AndroidJUnit4::class)
class UrlWordlistFetcherDeviceTest {
    private val servers = mutableListOf<LoopbackHttpServer>()
    private lateinit var dir: File
    @Before fun setUp() { dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "url-fetch-test-${System.nanoTime()}") }
    @After fun tearDown() { servers.forEach { it.close() }; dir.deleteRecursively() }
    private fun serve(handler: (LoopbackHttpServer.Request) -> LoopbackHttpServer.Response) = LoopbackHttpServer(handler).also { servers += it }
    private fun store() = WordlistStore(dir, emptyList())
    private fun listFiles() = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
    private fun plainHead(extra: String = "") = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n${extra}Connection: close\r\n\r\n"

    @Test fun aGzipBodyOverTenMegabytesStopsAtTheCapAndReportsNoTotal() = runBlocking {   // assumption A4
        val plain = "abcdefghij\n".repeat(1_000_000).toByteArray()                            // 11 MB once decoded
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(plain) } }.toByteArray()
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n")) { it.write(gz) } }
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        val r = UrlWordlistFetcher().fetch(srv.url("/big.txt"), store()) { a, b -> progress += a to b }
        assertEquals("the cap applies to the decoded text", UrlImportResult.Stored(ImportResult.TooLarge), r)
        assertTrue("Android decodes gzip itself and drops the compressed length", progress.all { it.second == null })
        assertTrue("decoded bytes were counted", progress.any { it.first > 0 })
        assertTrue(listFiles().isEmpty())
    }
    @Test fun cancelDuringASlowBodyReturnsWithinOneSecondAndLeavesNoFile() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out ->
            repeat(600) { n -> out.write("word$n\n".repeat(100).toByteArray()); out.flush(); Thread.sleep(100) }
        } }
        val s = store()
        val job = launch(Dispatchers.Default) { UrlWordlistFetcher().fetch(srv.url("/slow.txt"), s) { _, _ -> started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(s.lists.value.isEmpty()); assertTrue(listFiles().isEmpty())
    }
    @Test fun aContentLengthAboveTheCapReadsNothing() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 11000000\r\n")) { out -> out.write("a\n".repeat(1_000).toByteArray()) } }
        val progress = mutableListOf<Pair<Long, Long?>>()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), UrlWordlistFetcher().fetch(srv.url("/big.txt"), store()) { a, b -> progress += a to b })
        assertTrue(progress.isEmpty())
    }
    @Test fun progressHasATotalWithContentLengthAndNoneWhenChunked() = runBlocking {
        val body = "x\n".repeat(70_000).toByteArray()   // 140 KB: two progress steps
        val srv = serve { req ->
            if (req.path == "/len.txt") LoopbackHttpServer.ok("text/plain", body)
            else LoopbackHttpServer.Response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n") { out ->
                out.write("${Integer.toHexString(body.size)}\r\n".toByteArray()); out.write(body); out.write("\r\n0\r\n\r\n".toByteArray())
            }
        }
        val withLength = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        UrlWordlistFetcher().fetch(srv.url("/len.txt"), store()) { a, b -> withLength += a to b }
        assertTrue(withLength.isNotEmpty() && withLength.all { it.second == body.size.toLong() })
        val chunked = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        UrlWordlistFetcher().fetch(srv.url("/chunked.txt"), store()) { a, b -> chunked += a to b }
        assertTrue(chunked.isNotEmpty() && chunked.all { it.second == null })
    }
    @Test fun redirectsAreFollowedByTheFetcherAcrossHostsAndStopAtThree() = runBlocking {
        lateinit var a: LoopbackHttpServer
        lateinit var b: LoopbackHttpServer
        b = serve { req ->
            when (req.path) {
                "/h2" -> LoopbackHttpServer.redirect(302, "http://127.0.0.1:${a.port}/h3")
                else -> LoopbackHttpServer.ok("text/plain", "one\n".toByteArray())
            }
        }
        a = serve { req ->
            when (req.path) {
                "/h1" -> LoopbackHttpServer.redirect(302, "http://localhost:${b.port}/h2")      // another host name: 127.0.0.1 to localhost
                "/h3" -> LoopbackHttpServer.redirect(302, "http://localhost:${b.port}/list.txt")
                "/c1" -> LoopbackHttpServer.redirect(302, "/c2")
                "/c2" -> LoopbackHttpServer.redirect(302, "/c3")
                "/c3" -> LoopbackHttpServer.redirect(302, "/c4")
                "/c4" -> LoopbackHttpServer.redirect(302, "/list.txt")
                else -> LoopbackHttpServer.ok("text/plain", "one\n".toByteArray())
            }
        }
        assertTrue("three hops across two host names", UrlWordlistFetcher().fetch(a.url("/h1"), store()) is UrlImportResult.Stored)
        // The platform would follow up to 20 redirects itself; the fetcher stops at the fourth, so the platform is not following.
        assertEquals(UrlImportResult.Failed(FetchFailure.TooManyRedirects), UrlWordlistFetcher().fetch(a.url("/c1"), store()))
        assertEquals(0, a.requests.count { it.path == "/list.txt" })
    }
}
