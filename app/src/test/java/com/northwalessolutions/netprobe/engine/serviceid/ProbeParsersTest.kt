package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 9. */
class ProbeParsersTest {
    @Test fun `rtsp reply from a hikvision camera`() {
        val r = BannerParsers.rtspReply("RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hikvision/1.0\r\nPublic: OPTIONS, DESCRIBE, PLAY\r\n\r\n")!!
        assertEquals("RTSP", r.name); assertEquals(Confidence.CONFIRMED, r.confidence)
        assertEquals("Hikvision", r.product); assertEquals("1.0", r.version); assertEquals("Hikvision/1.0", r.server)
    }
    @Test fun `non rtsp text is not rtsp`() {
        assertNull(BannerParsers.rtspReply("HTTP/1.1 400 Bad Request\r\n\r\n")); assertNull(BannerParsers.rtspReply(""))
    }
    @Test fun `an rtsp server line without a colon gives no server`() {
        val r = BannerParsers.rtspReply("RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer\r\n\r\n")!!
        assertEquals("RTSP", r.name); assertNull(r.server); assertNull(r.product)
    }
}
