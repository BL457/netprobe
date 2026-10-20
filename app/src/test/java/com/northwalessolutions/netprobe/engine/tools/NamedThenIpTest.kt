package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 4.9: the name first, the IP only after a failed TLS handshake, never more than two exchanges. */
class NamedThenIpTest {
    private val okResult = HttpResult.Ok(HttpResponse(200, "OK", emptyList(), ByteArray(0), false, 0))
    private fun failed(f: HttpFailure) = HttpResult.Failed(f)

    @Test fun `a handshake failure on tls with a name retries once on the ip, name first`() = runTest {
        val names = mutableListOf<String?>()
        val r = namedThenIp(true, "nas.local") { n -> names += n; if (n != null) failed(HttpFailure.Handshake) else okResult }
        assertEquals(listOf<String?>("nas.local", null), names); assertSame(okResult, r)
    }
    @Test fun `a second failure is returned and there are at most two exchanges`() = runTest {
        var calls = 0
        val r = namedThenIp(true, "nas.local") { calls++; failed(HttpFailure.Handshake) }
        assertEquals(2, calls); assertTrue(r is HttpResult.Failed)
    }
    @Test fun `plain http is never retried`() = runTest {
        var calls = 0
        namedThenIp(false, "nas.local") { calls++; failed(HttpFailure.Handshake) }
        assertEquals(1, calls)
    }
    @Test fun `timeout, refusal and reset are not retried`() = runTest {
        for (f in listOf(HttpFailure.Timeout, HttpFailure.Refused, HttpFailure.Reset, HttpFailure.Malformed)) {
            var calls = 0
            namedThenIp(true, "nas.local") { calls++; failed(f) }
            assertEquals(f.toString(), 1, calls)
        }
    }
    @Test fun `no name means one exchange`() = runTest {
        var calls = 0
        namedThenIp(true, null) { calls++; failed(HttpFailure.Handshake) }
        assertEquals(1, calls)
    }
    @Test fun `a success is returned at once`() = runTest {
        var calls = 0
        assertSame(okResult, namedThenIp(true, "nas.local") { calls++; okResult }); assertEquals(1, calls)
    }
}
