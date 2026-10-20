package com.northwalessolutions.netprobe.wordlists

import org.junit.Assert.*
import org.junit.Test

/** spec 5.6.4: BOM, line endings, trimming, comments, blanks, leading slashes, duplicates, over-long and bad bytes. */
class WordlistParserTest {
    private fun parse(s: String) = WordlistParser.parse(s.toByteArray(Charsets.UTF_8).inputStream())
    private fun parse(b: ByteArray) = WordlistParser.parse(b.inputStream())

    @Test fun `splits on lf crlf and a lone cr and trims`() {
        assertEquals(listOf("a", "b", "c", "d"), parse("a\nb\r\nc\rd").entries)
        assertEquals(listOf("x", "y"), parse("  x  \n\ty\t").entries)
    }
    @Test fun `drops a bom, blanks and hash comments without counting them`() {
        val r = parse("\uFEFFadmin\n\n# a comment\nlogin\n")
        assertEquals(listOf("admin", "login"), r.entries); assertEquals(0, r.skipped)
    }
    @Test fun `strips leading slashes and drops a line that becomes empty`() {
        assertEquals(listOf("admin", "a/b"), parse("///admin\n//\n/a/b").entries)
    }
    @Test fun `removes duplicates keeping the first and the order`() {
        assertEquals(listOf("a", "b", "c"), parse("a\nb\na\nc\nb").entries)
    }
    @Test fun `skips and counts over long lines and control characters`() {
        val long = "a".repeat(257)
        val r = parse("ok\n$long\nbad\u0001char\nfine")
        assertEquals(listOf("ok", "fine"), r.entries); assertEquals(2, r.skipped)
    }
    @Test fun `skips and counts non utf8 bytes`() {
        val bytes = "good\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\nalsogood".toByteArray(Charsets.UTF_8)
        val r = parse(bytes)
        assertEquals(listOf("good", "alsogood"), r.entries); assertEquals(1, r.skipped)
    }
}
