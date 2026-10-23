package com.northwalessolutions.netprobe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val hash = "a".repeat(64)
    private fun parse(json: String) = UpdateManifestParser.parse(json.toByteArray())
    private fun full(vararg drop: String, extra: String = ""): String {
        val fields = linkedMapOf(
            "versionCode" to "4", "versionName" to "\"0.4.0\"", "apkUrl" to "\"https://example.com/a.apk\"", "sha256" to "\"$hash\"")
        drop.forEach { fields.remove(it) }
        return "{" + (fields.map { "\"${it.key}\":${it.value}" } + listOfNotNull(extra.takeIf { it.isNotEmpty() })).joinToString(",") + "}"
    }

    @Test fun `a valid manifest parses with optional fields absent`() {
        val m = (parse(full()) as ManifestParse.Valid).manifest
        assertEquals(4, m.versionCode); assertEquals("0.4.0", m.versionName); assertEquals(null, m.sizeBytes); assertEquals("", m.notes); assertEquals(null, m.minSdk)
    }

    @Test fun `optional fields are read`() {
        val m = (parse(full(extra = "\"sizeBytes\":1234,\"notes\":\"Fixes\\nMore\",\"minSdk\":26")) as ManifestParse.Valid).manifest
        assertEquals(1234L, m.sizeBytes); assertEquals("Fixes\nMore", m.notes); assertEquals(26, m.minSdk)
    }

    @Test fun `each missing required field makes it invalid`() {
        for (f in listOf("versionCode", "versionName", "apkUrl", "sha256")) assertEquals("without $f", ManifestParse.Invalid, parse(full(f)))
    }

    @Test fun `an http apkUrl is invalid`() {
        assertEquals(ManifestParse.Invalid, parse(full().replace("https://example.com", "http://example.com")))
    }

    @Test fun `a non-url apkUrl and credentials in the url are invalid`() {
        assertEquals(ManifestParse.Invalid, parse(full().replace("https://example.com/a.apk", "file:///sdcard/a.apk")))
        assertEquals(ManifestParse.Invalid, parse(full().replace("https://example.com", "https://user:pw@example.com")))
    }

    @Test fun `a malformed sha256 is invalid`() {
        assertEquals(ManifestParse.Invalid, parse(full().replace(hash, "a".repeat(63))))
        assertEquals(ManifestParse.Invalid, parse(full().replace(hash, "g".repeat(64))))
        assertEquals(ManifestParse.Invalid, parse(full().replace(hash, "a".repeat(65))))
    }

    @Test fun `the hash is lower-cased`() {
        val m = (parse(full().replace(hash, "AB".repeat(32))) as ManifestParse.Valid).manifest
        assertEquals("ab".repeat(32), m.sha256)
    }

    @Test fun `unknown keys are ignored`() {
        assertTrue(parse(full(extra = "\"future\":{\"a\":1}")) is ManifestParse.Valid)
    }

    @Test fun `notes are truncated to 2000 characters`() {
        val m = (parse(full(extra = "\"notes\":\"${"x".repeat(5000)}\"")) as ManifestParse.Valid).manifest
        assertEquals(UpdateConfig.NOTES_MAX_CHARS, m.notes.length)
    }

    @Test fun `garbage and wrong types are invalid`() {
        assertEquals(ManifestParse.Invalid, parse("not json"))
        assertEquals(ManifestParse.Invalid, parse(""))
        assertEquals(ManifestParse.Invalid, parse("[]"))
        assertEquals(ManifestParse.Invalid, parse(full().replace("\"versionCode\":4", "\"versionCode\":\"four\"")))
    }

    @Test fun `non-positive version or size is invalid`() {
        assertEquals(ManifestParse.Invalid, parse(full().replace("\"versionCode\":4", "\"versionCode\":0")))
        assertEquals(ManifestParse.Invalid, parse(full(extra = "\"sizeBytes\":0")))
    }
}
