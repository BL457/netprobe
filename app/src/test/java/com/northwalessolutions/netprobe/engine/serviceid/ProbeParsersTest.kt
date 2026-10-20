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
    @Test fun `rtsp options request is exact`() {
        assertEquals("OPTIONS rtsp://10.0.3.40:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n", String(BannerParsers.rtspOptions("10.0.3.40", 554), Charsets.ISO_8859_1))
    }
    @Test fun `rdp connection confirm and three wrong replies`() {
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xE0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00),
            BannerParsers.RDP_CONNECTION_REQUEST)
        val confirm = byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00, 0x02, 0x1F, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals(ServiceInfo("RDP", confidence = Confidence.CONFIRMED), BannerParsers.rdpConfirm(confirm))
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf().also { it[0] = 0x15 }))              // a TLS alert, not TPKT
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf().also { it[5] = 0xE0.toByte() }))     // the request echoed back
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf(5)))                                    // too short to hold the code
    }
}
