package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val hash = "b".repeat(64)
    private fun manifest(code: Int, extra: String = "") =
        """{"versionCode":$code,"versionName":"v$code","apkUrl":"https://example.com/a.apk","sha256":"$hash"$extra}""".toByteArray()

    private val day = UpdateConfig.CHECK_INTERVAL_MS
    private var now = 10 * day
    private val prefs = FakePrefs()
    private fun checker(fetcher: UpdateFetcher, installed: Int = 3, sdk: Int = 34) = UpdateChecker(fetcher, { now }, prefs, installed, sdk)
    private fun check(f: FakeUpdateFetcher, manual: Boolean = false, installed: Int = 3, sdk: Int = 34) = runBlocking { checker(f, installed, sdk).check(manual) }

    @Test fun `a newer version is available`() {
        val r = check(FakeUpdateFetcher(manifest(4)))
        assertEquals(4, (r as CheckResult.Available).manifest.versionCode)
    }

    @Test fun `the manifest url is the configured one`() {
        val f = FakeUpdateFetcher(manifest(4)); check(f)
        assertEquals(listOf(UpdateConfig.MANIFEST_URL), f.requested)
    }

    @Test fun `an automatic check within 24 hours does not fetch`() {
        prefs.lastCheckEpochMs = now - day + 1
        val f = FakeUpdateFetcher(manifest(4))
        assertEquals(CheckResult.NotDue, check(f))
        assertTrue(f.requested.isEmpty())
    }

    @Test fun `an automatic check after 24 hours fetches and stamps`() {
        prefs.lastCheckEpochMs = now - day
        assertTrue(check(FakeUpdateFetcher(manifest(4))) is CheckResult.Available)
        assertEquals(now, prefs.lastCheckEpochMs)
    }

    @Test fun `a clock that went backwards counts as due`() {
        prefs.lastCheckEpochMs = now + day
        assertTrue(check(FakeUpdateFetcher(manifest(4))) is CheckResult.Available)
    }

    @Test fun `a failed check still stamps so it is not retried in a loop`() {
        val f = FakeUpdateFetcher(failure = FetchError.CONNECTION)
        assertEquals(CheckResult.Failed, check(f))
        assertEquals(now, prefs.lastCheckEpochMs)
        assertEquals(CheckResult.NotDue, check(f))
        assertEquals(1, f.requested.size)
    }

    @Test fun `an invalid manifest is reported and stamps`() {
        assertEquals(CheckResult.Invalid, check(FakeUpdateFetcher("{}".toByteArray())))
        assertEquals(now, prefs.lastCheckEpochMs)
    }

    @Test fun `a forced check ignores the stamp`() {
        prefs.lastCheckEpochMs = now
        assertTrue(check(FakeUpdateFetcher(manifest(4)), manual = true) is CheckResult.Available)
    }

    @Test fun `a skipped version is hidden on automatic checks only`() {
        prefs.skippedVersionCode = 4
        assertEquals(CheckResult.Skipped, check(FakeUpdateFetcher(manifest(4))))
        assertEquals(4, prefs.skippedVersionCode)
        prefs.lastCheckEpochMs = 0
        assertTrue(check(FakeUpdateFetcher(manifest(4)), manual = true) is CheckResult.Available)
        assertEquals("a manual check clears the skip", 0, prefs.skippedVersionCode)
    }

    @Test fun `a newer version than the skipped one shows and clears the skip`() {
        prefs.skippedVersionCode = 4
        assertTrue(check(FakeUpdateFetcher(manifest(5))) is CheckResult.Available)
        assertEquals(0, prefs.skippedVersionCode)
    }

    @Test fun `skip stores the version`() {
        checker(FakeUpdateFetcher()).skip(7)
        assertEquals(7, prefs.skippedVersionCode)
    }

    @Test fun `a versionCode at or below the installed one is up to date`() {
        assertEquals(CheckResult.UpToDate, check(FakeUpdateFetcher(manifest(3))))
        assertEquals(CheckResult.UpToDate, check(FakeUpdateFetcher(manifest(2)), manual = true))
    }

    @Test fun `minSdk above the phone is up to date`() {
        assertEquals(CheckResult.UpToDate, check(FakeUpdateFetcher(manifest(4, ",\"minSdk\":35")), sdk = 34))
        assertTrue(check(FakeUpdateFetcher(manifest(4, ",\"minSdk\":34")), manual = true, sdk = 34) is CheckResult.Available)
    }

    @Test fun `refused and over-redirected fetches are failures not banners`() {
        for (e in listOf(FetchError.REFUSED, FetchError.TOO_MANY_REDIRECTS, FetchError.TOO_LARGE, FetchError.STATUS)) {
            assertEquals(CheckResult.Failed, check(FakeUpdateFetcher(failure = e), manual = true))
        }
    }
}
