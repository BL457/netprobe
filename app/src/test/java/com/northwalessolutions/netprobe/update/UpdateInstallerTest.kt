package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeVerifier(var verdict: ApkVerdict = ApkVerdict.OK) : ApkVerifier {
        val seen = mutableListOf<Pair<String, Long>>()
        override fun verify(file: File, installedVersionCode: Long): ApkVerdict { seen += file.name to installedVersionCode; return verdict }
    }

    private val body = "pretend this is an apk".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun manifest(code: Int = 4, hash: String = sha(body), size: Long? = body.size.toLong()) =
        UpdateManifest(code, "0.4.0", "https://example.com/a.apk", hash, size)
    private fun updates(cache: File) = File(cache, "updates")
    private fun installer(cache: File, fetcher: UpdateFetcher, verifier: ApkVerifier = FakeVerifier(), installed: Int = 3) = UpdateInstaller(cache, fetcher, verifier, installed)

    @Test fun `a good download is renamed and verified`() = runBlocking {
        val cache = tmp.newFolder(); val v = FakeVerifier()
        val r = installer(cache, FakeUpdateFetcher(body), v).download(manifest(), { _, _ -> })
        val file = (r as PrepResult.Ready).file
        assertEquals("netprobe-4.apk", file.name); assertTrue(file.isFile)
        assertEquals(listOf("netprobe-4.apk" to 3L), v.seen)
        assertEquals(listOf("netprobe-4.apk"), updates(cache).list()!!.toList())
    }

    @Test fun `onVerifying fires after the bytes arrive`() = runBlocking {
        var verifying = false
        installer(tmp.newFolder(), FakeUpdateFetcher(body)).download(manifest(), { _, _ -> }, onVerifying = { verifying = true })
        assertTrue(verifying)
    }

    @Test fun `a sha256 mismatch deletes the file`() = runBlocking {
        val cache = tmp.newFolder()
        val r = installer(cache, FakeUpdateFetcher(body)).download(manifest(hash = "0".repeat(64)), { _, _ -> })
        assertEquals(PrepResult.Failed(InstallFailure.HASH), r)
        assertEquals(0, updates(cache).list()!!.size)
    }

    @Test fun `a size mismatch deletes the file`() = runBlocking {
        val cache = tmp.newFolder()
        val r = installer(cache, FakeUpdateFetcher(body)).download(manifest(size = body.size + 1L), { _, _ -> })
        assertEquals(PrepResult.Failed(InstallFailure.SIZE), r)
        assertEquals(0, updates(cache).list()!!.size)
    }

    @Test fun `no declared size skips the size check`() = runBlocking {
        assertTrue(installer(tmp.newFolder(), FakeUpdateFetcher(body)).download(manifest(size = null), { _, _ -> }) is PrepResult.Ready)
    }

    @Test fun `a declared size over 64 MB is refused without a request`() = runBlocking {
        val f = FakeUpdateFetcher(body)
        val r = installer(tmp.newFolder(), f).download(manifest(size = UpdateConfig.APK_MAX_BYTES + 1), { _, _ -> })
        assertEquals(PrepResult.Failed(InstallFailure.TOO_LARGE), r); assertTrue(f.requested.isEmpty())
    }

    @Test fun `a signing or package rejection deletes the file`() = runBlocking {
        for (verdict in listOf(ApkVerdict.WRONG_SIGNER, ApkVerdict.WRONG_PACKAGE, ApkVerdict.NOT_NEWER, ApkVerdict.UNREADABLE)) {
            val cache = tmp.newFolder()
            val r = installer(cache, FakeUpdateFetcher(body), FakeVerifier(verdict)).download(manifest(), { _, _ -> })
            assertEquals(verdict.name, PrepResult.Failed(InstallFailure.REJECTED), r)
            assertEquals(0, updates(cache).list()!!.size)
        }
    }

    @Test fun `a failed fetch leaves nothing`() = runBlocking {
        val cache = tmp.newFolder()
        assertEquals(PrepResult.Failed(InstallFailure.DOWNLOAD), installer(cache, FakeUpdateFetcher(failure = FetchError.CONNECTION)).download(manifest(), { _, _ -> }))
        assertEquals(PrepResult.Failed(InstallFailure.TOO_LARGE), installer(cache, FakeUpdateFetcher(failure = FetchError.TOO_LARGE)).download(manifest(), { _, _ -> }))
        assertEquals(0, updates(cache).list()!!.size)
    }

    @Test fun `cancel deletes the part file`() = runBlocking {
        val cache = tmp.newFolder()
        val started = CompletableDeferred<Unit>()
        val f = FakeUpdateFetcher(body, beforeFinish = { started.complete(Unit); awaitCancellation() })
        val job = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { installer(cache, f).download(manifest(), { _, _ -> }) }
        withTimeout(5_000) { started.await() }
        assertTrue("part file exists while downloading", File(updates(cache), "netprobe-4.apk.part").exists())
        job.cancelAndJoin()
        assertEquals(0, updates(cache).list()!!.size)
    }

    @Test fun `housekeeping deletes part files and stale versions only`() {
        val cache = tmp.newFolder(); val dir = updates(cache); dir.mkdirs()
        listOf("netprobe-2.apk", "netprobe-3.apk", "netprobe-4.apk", "netprobe-5.apk.part", "netprobe-9.apk.part", "junk.txt", "netprobe-x.apk").forEach { File(dir, it).writeText("x") }
        installer(cache, FakeUpdateFetcher(), installed = 3).housekeeping()
        assertEquals(listOf("netprobe-4.apk"), dir.list()!!.toList())
    }

    @Test fun `housekeeping with no updates directory does nothing`() {
        val cache = tmp.newFolder()
        installer(cache, FakeUpdateFetcher()).housekeeping()
        assertTrue(cache.list()!!.isEmpty())
    }
}
