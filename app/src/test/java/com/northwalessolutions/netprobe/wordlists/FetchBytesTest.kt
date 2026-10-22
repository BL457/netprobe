package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** fetchBytes shares the wordlist fetcher's rules; here only what differs: the body stays in memory and the cap is the caller's. */
class FetchBytesTest {
    private val servers = mutableListOf<LoopbackHttpServer>()
    @After fun stop() { servers.forEach { it.close() } }
    private fun serve(handler: (LoopbackHttpServer.Request) -> LoopbackHttpServer.Response) = LoopbackHttpServer(handler).also { servers += it }

    @Test fun `a csv body comes back whole`() = runBlocking {
        val body = "Registry,Assignment\nMA-L,001122\n".toByteArray()
        val srv = serve { LoopbackHttpServer.ok("text/csv", body) }
        val r = UrlWordlistFetcher().fetchBytes(srv.url("/oui.csv"), 1_000_000) as UrlImportResult.Fetched
        assertArrayEquals(body, r.bytes)
    }
    @Test fun `a body over the caller cap is TooLarge by content length and by streaming`() = runBlocking {
        val declared = serve { LoopbackHttpServer.ok("text/csv", ByteArray(5_000) { 'a'.code.toByte() }) }
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), UrlWordlistFetcher().fetchBytes(declared.url("/a.csv"), 1_000))
        val streamed = serve { LoopbackHttpServer.Response("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nConnection: close\r\n\r\n") { out -> out.write(ByteArray(5_000) { 'a'.code.toByte() }); out.flush() } }
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), UrlWordlistFetcher().fetchBytes(streamed.url("/b.csv"), 1_000))
    }
    @Test fun `a 404 is the usual failure`() = runBlocking {
        val srv = serve { LoopbackHttpServer.status(404, "Not Found") }
        assertEquals(UrlImportResult.Failed(FetchFailure.Status(404)), UrlWordlistFetcher().fetchBytes(srv.url("/x.csv"), 1_000))
    }
}
