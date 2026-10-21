package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** 3C spec 8.1 item 11, Review Focus 5. */
class UrlImportRulesTest {
    private fun ok(text: String) = (UrlImportRules.validate(text) as UrlCheck.Ok).url.toString()
    private fun err(text: String) = (UrlImportRules.validate(text) as UrlCheck.Invalid).error
    private fun url(s: String) = URL(s)
    /** Compared as text: URL.equals resolves host names, which a unit test must never do. */
    private fun follow(d: RedirectDecision) = (d as RedirectDecision.Follow).url.toString()

    @Test fun `validate prepends https, keeps http and ports, drops the fragment`() {
        assertEquals("https://example.com/list.txt", ok("example.com/list.txt"))
        assertEquals("http://10.0.3.5:8000/w.txt", ok("  http://10.0.3.5:8000/w.txt  "))
        assertEquals("https://example.com:8080/x", ok("example.com:8080/x"))
        assertEquals("https://example.com/list.txt", ok("https://example.com/list.txt#top"))
        assertNull((UrlImportRules.validate("https://example.com/list.txt#top") as UrlCheck.Ok).url.ref)
    }
    @Test fun `validate refuses other schemes, credentials, spaces, blanks and long addresses`() {
        assertEquals(WordlistCopy.ERR_URL, err("ftp://example.com/x"))
        assertEquals(WordlistCopy.ERR_URL, err("javascript:alert(1)"))
        assertEquals(WordlistCopy.ERR_URL_USER, err("https://user:pass@example.com/l.txt"))
        assertEquals(WordlistCopy.ERR_URL_USER, err("user:pass@example.com/l.txt"))
        assertEquals(WordlistCopy.ERR_URL, err("https://example.com/a b"))
        assertEquals(WordlistCopy.ERR_URL, err(""))
        assertEquals(WordlistCopy.ERR_URL, err("https://"))
        assertEquals(WordlistCopy.ERR_URL_LONG, err("https://example.com/" + "a".repeat(UrlImportRules.MAX_URL_CHARS)))
    }
    @Test fun `redirect follows three hops to other hosts and refuses a fourth`() {
        val gh = url("https://github.com/danielmiessler/SecLists/raw/master/Discovery/Web-Content/common.txt")
        val raw = "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Discovery/Web-Content/common.txt"
        assertEquals(raw, follow(UrlImportRules.redirect(gh, gh, raw, 0)))
        val box = url("https://www.dropbox.com/s/abc/list.txt?dl=1")
        assertEquals("https://dl.dropboxusercontent.com/s/abc/list.txt", follow(UrlImportRules.redirect(box, box, "https://dl.dropboxusercontent.com/s/abc/list.txt", 1)))
        assertTrue(UrlImportRules.redirect(gh, gh, raw, 2) is RedirectDecision.Follow)
        assertEquals(RedirectDecision.TooMany, UrlImportRules.redirect(gh, gh, raw, 3))
    }
    @Test fun `redirect never downgrades and allows an upgrade`() {
        val s = url("https://example.com/a"); val p = url("http://example.com/a")
        assertEquals(RedirectDecision.Insecure, UrlImportRules.redirect(s, s, "http://example.com/b", 0))
        assertEquals("https://example.com/b", follow(UrlImportRules.redirect(p, p, "https://example.com/b", 0)))
    }
    @Test fun `a public host never bounces the phone into a local address`() {
        val p = url("http://example.com/a")
        for (target in listOf("http://192.168.1.1/", "http://10.0.0.5/", "http://127.0.0.1/", "http://169.254.1.1/", "http://172.16.0.9/",
                "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[::ffff:10.0.0.5]/",
                "http://localhost:8080/", "http://foo.localhost/", "http://router/", "http://nas.local/", "http://router.lan/", "http://box.home.arpa/"))
            assertEquals(target, RedirectDecision.LocalTarget, UrlImportRules.redirect(p, p, target, 0))
        val lan = url("http://10.0.3.5/a")
        assertEquals("http://10.0.3.6/b", follow(UrlImportRules.redirect(lan, lan, "http://10.0.3.6/b", 0)))   // started local: allowed
        val named = url("http://nas.local/a")
        assertEquals("http://nas.local/b", follow(UrlImportRules.redirect(named, named, "http://nas.local/b", 0)))   // a local name typed by the Director: allowed
        assertEquals("http://cdn.example.net/x", follow(UrlImportRules.redirect(p, p, "http://cdn.example.net/x", 0)))  // a public name: followed, resolved by the fetcher
        assertEquals("http://example.com/next", follow(UrlImportRules.redirect(p, p, "/next", 0)))           // relative
    }
    @Test fun `a missing or unusable location, another scheme or credentials are invalid`() {
        val p = url("https://example.com/a")
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, null, 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "  ", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "javascript:alert(1)", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "ftp://example.com/x", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "https://u:p@example.com/x", 0))
    }
    @Test fun `content type accepts text except html, octet-stream and none`() {
        listOf("text/plain; charset=utf-8", "TEXT/PLAIN", "text/x-wordlist", "application/octet-stream", null, "", "  ").forEach { assertTrue("$it", UrlImportRules.contentType(it)) }
        listOf("text/html", "text/html; charset=utf-8", "application/zip", "application/json", "image/png").forEach { assertFalse(it, UrlImportRules.contentType(it)) }
        assertEquals("text/html", UrlImportRules.mediaType("Text/HTML; charset=utf-8")); assertNull(UrlImportRules.mediaType(null))
    }
    @Test fun `a nul in the first 8 KB is binary and one after it is not`() {
        assertTrue(UrlImportRules.looksBinary(ByteArray(UrlImportRules.BINARY_SNIFF_BYTES) { if (it == 100) 0 else 'a'.code.toByte() }))
        assertFalse(UrlImportRules.looksBinary(ByteArray(UrlImportRules.BINARY_SNIFF_BYTES + 1) { if (it == UrlImportRules.BINARY_SNIFF_BYTES) 0 else 'a'.code.toByte() }))
        assertFalse(UrlImportRules.looksBinary(ByteArray(0)))
    }
    @Test fun `the display name is the decoded last segment, else the host`() {
        assertEquals("my words.txt", UrlImportRules.displayName(url("https://x.example/lists/my%20words.txt")))
        assertEquals("common.txt", UrlImportRules.displayName(url("https://raw.githubusercontent.com/a/b/master/common.txt")))
        assertEquals("example.com", UrlImportRules.displayName(url("https://example.com/")))
        assertEquals("example.com", UrlImportRules.displayName(url("https://example.com")))
    }
    @Test fun `resolved addresses in the LAN or on the phone are local and public ones are not`() {   // plan review G4; literals only, no lookup
        for (a in listOf("10.0.0.5", "192.168.1.1", "172.16.0.9", "127.0.0.1", "169.254.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"))
            assertTrue(a, UrlImportRules.isLocalAddress(InetAddress.getByName(a)))
        for (a in listOf("93.184.216.34", "2606:4700::1111")) assertFalse(a, UrlImportRules.isLocalAddress(InetAddress.getByName(a)))
        assertTrue(UrlImportRules.isLocalHost("Router.LAN.")); assertFalse(UrlImportRules.isLocalHost("raw.githubusercontent.com"))
    }
    @Test fun `failures map by exception type`() {
        assertEquals(FetchFailure.UnknownHost, UrlImportRules.failureOf(UnknownHostException("x")))
        assertEquals(FetchFailure.NoAnswer, UrlImportRules.failureOf(SocketTimeoutException("x")))
        assertEquals(FetchFailure.Tls, UrlImportRules.failureOf(SSLHandshakeException("x")))
        assertEquals(FetchFailure.Connection, UrlImportRules.failureOf(IOException("x")))
    }
}
