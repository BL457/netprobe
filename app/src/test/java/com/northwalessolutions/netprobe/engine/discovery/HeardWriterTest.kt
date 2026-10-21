package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.HOME
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.HOME_RANGE
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.KEY_A
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 18 (G4, G5, G22, Review Focus 4). */
class HeardWriterTest {
    private fun heard(ipText: String, key: String = KEY_A, location: String? = null) =
        Heard(key, DiscoveredHost(Ipv4.parse(ipText)!!, sources = setOf(Source.SSDP, Source.HEARD)), "s|$ipText", location)
    private fun writer(sink: FakeSink) = HeardWriter(1, sink)

    @Test fun `an address outside the known ranges is dropped`() = runBlocking {
        val sink = FakeSink()
        assertEquals(HeardOutcome.OUT_OF_RANGE, writer(sink).accept(heard("10.20.0.5")))
        assertTrue(sink.stored.isEmpty())
    }
    @Test fun `new rows inside the home range, a manual range and a known host's slash 24 are kept`() = runBlocking {
        val sink = FakeSink(ranges = KnownRanges.of(HOME_RANGE, listOf(CidrParser.subnetOf("10.0.4.0/24")!!), listOf(TestHosts.host(ip = "10.0.5.9"))))
        val w = writer(sink)
        for (t in listOf("10.0.3.7", "10.0.4.8", "10.0.5.20")) assertEquals(t, HeardOutcome.WRITTEN, w.accept(heard(t)))
        assertEquals(listOf(1L to "10.0.3.7", 1L to "10.0.4.8", 1L to "10.0.5.20"), sink.stored)
    }
    @Test fun `the 33rd new row of a session is dropped while an update to an existing row still applies`() = runBlocking {
        val sink = FakeSink(); sink.existing += "10.0.3.250"
        val w = writer(sink)
        for (i in 1..HEARD_NEW_MAX) assertEquals("row $i", HeardOutcome.WRITTEN, w.accept(heard("10.0.3.$i")))
        assertEquals(HeardOutcome.CAP_REACHED, w.accept(heard("10.0.3.${HEARD_NEW_MAX + 1}")))
        assertEquals("an existing row still updates", HeardOutcome.WRITTEN, w.accept(heard("10.0.3.250")))
        assertEquals(HEARD_NEW_MAX + 1, sink.stored.size)
        assertEquals("a new session starts its own count", HeardOutcome.WRITTEN, writer(sink).accept(heard("10.0.3.200")))
    }
    @Test fun `nothing is written while a radar scan runs or an Undo is pending`() = runBlocking {
        for (ctx in listOf(HOME.copy(scanActive = true), HOME.copy(undoPending = true))) {
            val sink = FakeSink(ctx = ctx)
            assertEquals("$ctx", HeardOutcome.BUSY, writer(sink).accept(heard("10.0.3.7")))
            assertTrue(sink.stored.isEmpty())
        }
    }
    @Test fun `a packet for another network or another row is dropped`() = runBlocking {
        for (ctx in listOf(null, HOME.copy(forgotten = true), HOME.copy(networkId = 2), HOME.copy(identityKey = "10.0.9.1/24"))) {
            val sink = FakeSink(ctx = ctx)
            assertEquals("$ctx", HeardOutcome.WRONG_NETWORK, writer(sink).accept(heard("10.0.3.7")))
            assertTrue(sink.stored.isEmpty())
        }
        val sink = FakeSink()
        assertEquals("heard on another env", HeardOutcome.WRONG_NETWORK, writer(sink).accept(heard("10.0.3.7", key = "10.0.9.1/24")))
        assertTrue(sink.stored.isEmpty())
    }
    @Test fun `a device removed this session is not re-added`() = runBlocking {
        val sink = FakeSink(); sink.removedSet = setOf(1L to "10.0.3.7")
        assertEquals(HeardOutcome.REMOVED, writer(sink).accept(heard("10.0.3.7")))
        assertEquals(HeardOutcome.WRITTEN, writer(sink).accept(heard("10.0.3.8")))
        assertEquals(listOf(1L to "10.0.3.8"), sink.stored)
    }
    @Test fun `the description is fetched once per IP inside the known ranges and merged into the write`() = runBlocking {
        val sink = FakeSink(device = UpnpDevice("Living Room", "Sonos", "One", null, "urn:schemas-upnp-org:device:ZonePlayer:1", null))
        val w = writer(sink)
        assertEquals(HeardOutcome.WRITTEN, w.accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/d.xml")))
        w.accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/other.xml"))
        w.accept(heard("10.20.0.5", location = "http://10.20.0.5/d.xml"))
        assertEquals("one GET, never for an address outside the known ranges", listOf("http://10.0.3.30:1400/d.xml"), sink.described)
        val h = sink.storedHosts.first()
        assertEquals("Living Room", h.upnpFriendlyName); assertEquals("Sonos", h.upnpManufacturer); assertEquals("One", h.upnpModel)
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", h.upnpDeviceType)
    }
    @Test fun `a Forget or a scan landing during the description fetch drops the write`() = runBlocking {
        for (flip in listOf(HOME.copy(forgotten = true), HOME.copy(scanActive = true), HOME.copy(undoPending = true), null)) {
            val sink = FakeSink(device = UpnpDevice("Living Room", "Sonos", "One", null, null, null))
            sink.duringDescribe = { sink.ctx = flip }
            val outcome = writer(sink).accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/d.xml"))
            assertTrue("$flip: $outcome", outcome == HeardOutcome.WRONG_NETWORK || outcome == HeardOutcome.BUSY)
            assertTrue("$flip: nothing stored", sink.stored.isEmpty())
        }
    }
    @Test fun `a datagram whose source is outside the known ranges is dropped`() = runBlocking {
        val sink = FakeSink()
        val fromTun = Heard(KEY_A, DiscoveredHost(Ipv4.parse("10.0.3.7")!!, sources = setOf(Source.MDNS, Source.HEARD)), "m|10.0.3.7|_x._tcp", source = Ipv4.parse("100.64.0.9")!!)
        assertEquals(HeardOutcome.OUT_OF_RANGE, writer(sink).accept(fromTun))
        assertTrue(sink.stored.isEmpty())
    }
    private fun answer(ipText: String, sourceText: String) =
        Heard(KEY_A, DiscoveredHost(Ipv4.parse(ipText)!!, sources = setOf(Source.MDNS, Source.HEARD)), "m|$ipText|_x._tcp", source = Ipv4.parse(sourceText)!!)
    @Test fun `an answer for another address is kept only from the gateway or a visible host`() = runBlocking {
        val sink = FakeSink(); sink.existing += "10.0.3.99"
        val w = writer(sink)
        assertEquals(HeardOutcome.FOREIGN_ADDRESS, w.accept(answer("10.0.3.7", "10.0.3.8")))
        assertEquals(HeardOutcome.WRITTEN, w.accept(answer("10.0.3.7", "10.0.3.1")))
        assertEquals(HeardOutcome.WRITTEN, w.accept(answer("10.0.3.9", "10.0.3.99")))
        assertEquals(HeardOutcome.WRITTEN, w.accept(answer("10.0.3.10", "10.0.3.10")))
        assertEquals(listOf("10.0.3.7", "10.0.3.9", "10.0.3.10"), sink.stored.map { it.second })
    }
    @Test fun `the phone's own address as source or as record is dropped`() = runBlocking {
        val sink = FakeSink()
        val w = writer(sink)
        assertEquals(HeardOutcome.SELF, w.accept(answer("10.0.3.50", "10.0.3.50")))
        assertEquals(HeardOutcome.SELF, w.accept(answer("10.0.3.7", "10.0.3.50")))
        assertEquals(HeardOutcome.SELF, w.accept(answer("10.0.3.50", "10.0.3.1")))
        assertTrue(sink.stored.isEmpty())
    }
    @Test fun `R2 off fetches no description and still writes the sighting`() = runBlocking {
        val sink = FakeSink(device = UpnpDevice("Living Room", "Sonos", "One", null, null, null))
        assertEquals(HeardOutcome.WRITTEN, HeardWriter(1, sink, fetchDescriptions = false).accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/d.xml")))
        assertTrue(sink.described.isEmpty()); assertNull(sink.storedHosts.single().upnpFriendlyName)
        assertTrue("the shipped default is the controller's recommendation", HEARD_FETCH_DESCRIPTIONS)
    }
    @Test fun `the description budget is capped per session and a long location is never fetched`() = runBlocking {
        val sink = FakeSink()
        (1..HEARD_FETCH_MAX + 5).forEach { sink.existing += "10.0.3.$it" }      // updates only, so the new-row cap is not what stops it
        val w = writer(sink)
        for (i in 1..HEARD_FETCH_MAX + 5) w.accept(heard("10.0.3.$i", location = "http://10.0.3.$i/d.xml"))
        assertEquals(HEARD_FETCH_MAX, sink.described.size)
        val fresh = FakeSink()
        writer(fresh).accept(heard("10.0.3.9", location = "http://10.0.3.9/" + "a".repeat(HEARD_LOCATION_MAX_CHARS)))
        assertTrue(fresh.described.isEmpty())
        assertEquals("the sighting itself is still written", 1, fresh.stored.size)
    }
}
