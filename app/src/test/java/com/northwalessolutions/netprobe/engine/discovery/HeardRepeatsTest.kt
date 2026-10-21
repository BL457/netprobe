package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 3.3 and 8.1 item 18: the repeat filter's window and bound, and the queue's overflow. */
class HeardRepeatsTest {
    @Test fun `a repeat within 60 s is dropped and one after it is kept`() {
        var now = 0L
        val r = HeardRepeats({ now })
        assertTrue(r.admit("k"))
        now = HEARD_REPEAT_MS - 1; assertFalse(r.admit("k"))
        now = HEARD_REPEAT_MS; assertTrue(r.admit("k"))
        assertTrue("another key is not a repeat", r.admit("other"))
    }
    @Test fun `the map never holds more than 1,024 keys and forgets the eldest`() {
        val r = HeardRepeats({ 0L })
        for (i in 0 until HEARD_REPEAT_KEYS + 50) assertTrue(r.admit("k$i"))
        assertEquals(HEARD_REPEAT_KEYS, r.size())
        assertFalse("a recent key is still a repeat", r.admit("k${HEARD_REPEAT_KEYS + 49}"))
        assertTrue("the eldest was evicted, so it is new again", r.admit("k0"))
        assertEquals(HEARD_REPEAT_KEYS, r.size())
    }
    @Test fun `the queue drops the oldest when the writer falls behind`() = runBlocking {
        val q = heardQueue()
        for (i in 0..HEARD_QUEUE) assertTrue(q.trySend(Heard(FakeSink.KEY_A, DiscoveredHost(Ipv4.parse("10.0.3.$i")!!), "s|10.0.3.$i")).isSuccess)
        assertEquals("the first of 65 was dropped", "s|10.0.3.1", q.receive().repeatKey)
    }
}
