package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.Ber
import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** spec 6.4 and 12.1: a loopback DatagramSocket agent end to end; one retry; mismatched request ids ignored. */
class UdpSnmpExchangeTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val agents = mutableListOf<DatagramSocket>()
    @After fun tearDown() { agents.forEach { it.close() } }

    /** Reads the request id of a GET or GETNEXT and the first OID. */
    private fun request(bytes: ByteArray): Pair<Int, String> {
        val r = Ber.Reader(bytes)
        r.tag(); r.length(); r.int(); r.tag(); r.bytes(r.length())    // sequence, version, community
        r.tag(); r.length(); val id = r.int(); r.int(); r.int()        // pdu, id, error, index
        r.tag(); r.length(); r.tag(); r.length(); r.tag()              // varbind list, first varbind, OID tag
        return id to r.oidString(r.bytes(r.length()))
    }
    private fun response(id: Int, oid: String, value: String): ByteArray =
        Ber.seq(Ber.int(1), Ber.str("public"), Ber.tlv(SnmpPdu.RESPONSE, Ber.int(id) + Ber.int(0) + Ber.int(0) + Ber.seq(Ber.seq(Ber.oid(SnmpPdu.oidParts(oid)), Ber.str(value)))))

    /** An agent that ignores the first [drop] requests and, for the rest, first sends a reply with the wrong id, then the right one. */
    private fun agent(drop: Int): Int {
        val s = DatagramSocket(0, lo); agents += s
        thread(isDaemon = true) {
            var seen = 0
            runCatching {
                while (true) {
                    val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    if (seen++ < drop) continue
                    val (id, oid) = request(buf.copyOf(p.length))
                    for (reply in listOf(response(id + 1000, "$oid.0", "wrong"), response(id, "$oid.0", "right")))
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            }
        }
        return s.localPort
    }

    @Test fun `getnext answers through a loopback agent and ignores a mismatched id`() = runBlocking {
        val step = UdpSnmpExchange(lo, agent(drop = 0), timeoutMs = 500).getNext(listOf("1.3.6.1.2.1.1.5")) as SnmpStep.Ok
        assertEquals("1.3.6.1.2.1.1.5.0", step.varbinds.single().oid); assertEquals("right", step.varbinds.single().str)
    }
    @Test fun `one lost datagram is retried`() = runBlocking {
        val step = UdpSnmpExchange(lo, agent(drop = 1), timeoutMs = 300).getNext(listOf("1.3.6.1.2.1.1.5"))
        assertTrue(step is SnmpStep.Ok)
    }
    @Test fun `two lost datagrams are a timeout`() = runBlocking {
        assertEquals(SnmpStep.Timeout, UdpSnmpExchange(lo, agent(drop = 2), timeoutMs = 200).getNext(listOf("1.3.6.1.2.1.1.5")))
    }
    @Test fun `a correct id reply from another source port is ignored`() = runBlocking {
        val s = DatagramSocket(0, lo); val spoof = DatagramSocket(0, lo); agents += s; agents += spoof
        val sent = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    val (id, oid) = request(buf.copyOf(p.length))
                    val reply = response(id, "$oid.0", "spoofed")
                    spoof.send(DatagramPacket(reply, reply.size, p.address, p.port)); sent.incrementAndGet()   // right id, wrong source port
                }
            }
        }
        assertEquals(SnmpStep.Timeout, UdpSnmpExchange(lo, s.localPort, timeoutMs = 200).getNext(listOf("1.3.6.1.2.1.1.5")))
        assertEquals(2, sent.get())
    }
}
