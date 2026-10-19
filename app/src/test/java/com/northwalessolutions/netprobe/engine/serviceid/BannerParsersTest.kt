package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

class BannerParsersTest {
    @Test fun `ssh banner`() {
        val s = BannerParsers.ssh("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.4\r\n")!!
        assertEquals("SSH", s.name); assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version)
        assertEquals("Ubuntu-3ubuntu13.4", s.detail); assertEquals(Confidence.CONFIRMED, s.confidence)
        assertEquals("dropbear", BannerParsers.ssh("SSH-2.0-dropbear_2022.83")!!.product)
        assertNull(BannerParsers.ssh("220 hello"))
    }
    @Test fun `ftp and smtp greetings`() {
        val f = BannerParsers.greeting("220 ProFTPD 1.3.8 Server (Debian) [::ffff:10.0.0.5]", "FTP")!!
        assertEquals("FTP", f.name); assertEquals("ProFTPD", f.product); assertEquals("1.3.8", f.version)
        val s = BannerParsers.greeting("220 mail.example.com ESMTP Postfix (Ubuntu)", "SMTP")!!
        assertEquals("Postfix", s.product); assertNull(s.version)
        val v = BannerParsers.greeting("220 (vsFTPd 3.0.5)", "FTP")!!
        assertEquals("vsFTPd", v.product); assertEquals("3.0.5", v.version)
        assertNull(BannerParsers.greeting("", "FTP"))
    }
    @Test fun `http head response`() {
        val r = BannerParsers.httpHead("HTTP/1.1 401 Unauthorized\r\nServer: nginx/1.18.0 (Ubuntu)\r\nWWW-Authenticate: Basic realm=\"TP-LINK\"\r\n\r\n")!!
        assertEquals("HTTP", r.name); assertEquals("nginx", r.product); assertEquals("1.18.0", r.version)
        assertTrue(r.detail!!.contains("401")); assertTrue(r.detail!!.contains("TP-LINK"))
        assertEquals("Microsoft-IIS" to "10.0", BannerParsers.serverHeader("Microsoft-IIS/10.0"))
        assertEquals("lighttpd" to "1.4.59", BannerParsers.serverHeader("lighttpd/1.4.59"))
        assertEquals("Apache" to "2.4.52", BannerParsers.serverHeader("Apache/2.4.52 (Ubuntu)"))
        assertEquals("GoAhead-Webs" to null, BannerParsers.serverHeader("GoAhead-Webs"))
        assertNull(BannerParsers.httpHead("garbage"))
    }
    @Test fun `unknown bytes reclassify or keep printable excerpt`() {
        assertEquals("SSH", BannerParsers.classifyUnknown("SSH-2.0-OpenSSH_8.9\r\n".toByteArray(), "Unknown").name)
        assertEquals("HTTP", BannerParsers.classifyUnknown("HTTP/1.0 200 OK\r\n".toByteArray(), "Unknown").name)
        val bin = BannerParsers.classifyUnknown(byteArrayOf(0, 1, 2, 3, -1, -2), "Unknown")
        assertEquals("Unknown", bin.name); assertNull(bin.detail)
        val txt = BannerParsers.classifyUnknown("* OK Dovecot ready.\r\n".toByteArray(), "IMAP")
        assertEquals("IMAP", txt.name); assertEquals("* OK Dovecot ready.", txt.detail)
    }
    @Test fun `garbage never throws`() {
        for (junk in listOf("", "\u0000", "HTTP/", "SSH-", "220", "x".repeat(5000))) {
            BannerParsers.ssh(junk); BannerParsers.greeting(junk, "FTP"); BannerParsers.httpHead(junk); BannerParsers.serverHeader(junk)
            BannerParsers.classifyUnknown(junk.toByteArray(), "Unknown")
        }
    }
}
