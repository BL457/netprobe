package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WhatsNewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `first install shows nothing and records the version`() {
        val prefs = FakePrefs()
        val s = WhatsNewState(prefs, 6, "0.3.3")
        assertNull(s.card.value); assertEquals(6, prefs.lastSeenVersionCode)
    }

    @Test fun `matching pending notes show the card`() {
        val prefs = FakePrefs(lastSeenVersionCode = 5, pendingVersionCode = 6, pendingNotes = "Line one\nLine two")
        val card = WhatsNewState(prefs, 6, "0.3.3").card.value
        assertEquals(WhatsNewCard("0.3.3", "Line one\nLine two"), card)
        assertEquals(5, prefs.lastSeenVersionCode)   // not recorded until Got it
    }

    @Test fun `mismatched pending notes show nothing and record the version`() {
        val prefs = FakePrefs(lastSeenVersionCode = 4, pendingVersionCode = 6, pendingNotes = "Old")
        val s = WhatsNewState(prefs, 5, "0.3.2")
        assertNull(s.card.value); assertEquals(5, prefs.lastSeenVersionCode)
    }

    @Test fun `blank pending notes show nothing`() {
        val prefs = FakePrefs(lastSeenVersionCode = 5, pendingVersionCode = 6, pendingNotes = "  \n")
        assertNull(WhatsNewState(prefs, 6, "0.3.3").card.value); assertEquals(6, prefs.lastSeenVersionCode)
    }

    @Test fun `dismissing stores the version and clears the card and pending notes`() {
        val prefs = FakePrefs(lastSeenVersionCode = 5, pendingVersionCode = 6, pendingNotes = "Notes")
        val s = WhatsNewState(prefs, 6, "0.3.3")
        assertNotNull(s.card.value)
        s.dismiss()
        assertNull(s.card.value); assertEquals(6, prefs.lastSeenVersionCode); assertEquals("", prefs.pendingNotes)
        assertNull(WhatsNewState(prefs, 6, "0.3.3").card.value)   // next open: nothing
    }

    @Test fun `already seen version shows nothing even with matching pending notes`() {
        assertNull(WhatsNewRules.notesToShow(6, 6, 6, "Notes"))
    }

    @Test fun `the installer saves the manifest notes only for a verified download`() = runBlocking {
        val body = "pretend this is an apk".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val m = UpdateManifest(4, "0.4.0", "https://example.com/a.apk", hash, body.size.toLong(), "Fixes\nMore")
        val bad = FakePrefs()
        UpdateInstaller(tmp.newFolder(), FakeUpdateFetcher(body), object : ApkVerifier { override fun verify(file: File, installedVersionCode: Long) = ApkVerdict.WRONG_SIGNER }, 3, prefs = bad)
            .download(m, { _, _ -> })
        assertEquals(0, bad.pendingVersionCode)
        val good = FakePrefs()
        UpdateInstaller(tmp.newFolder(), FakeUpdateFetcher(body), object : ApkVerifier { override fun verify(file: File, installedVersionCode: Long) = ApkVerdict.OK }, 3, prefs = good)
            .download(m, { _, _ -> })
        assertEquals(4, good.pendingVersionCode); assertEquals("Fixes\nMore", good.pendingNotes)
    }
}
