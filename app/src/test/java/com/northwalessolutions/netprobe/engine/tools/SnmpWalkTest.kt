package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.end
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.ip
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.num
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.oidv
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.raw
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.str
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 6.4 and 12.1: walk termination, table assembly by index suffix with a sparse column, ifHighSpeed, formatting, hints, the cap. */
class SnmpWalkTest {
    private val target = ToolTarget(7, ToolTestSupport.ip("10.0.3.1"), 161, false, null)
    private val system = listOf(
        str("1.3.6.1.2.1.1.1.0", "Linux gw 5.10"), oidv("1.3.6.1.2.1.1.2.0", "1.3.6.1.4.1.8072.3.2.10"), num("1.3.6.1.2.1.1.3.0", (12L * 86_400 + 4 * 3_600) * 100, 0x43),
        str("1.3.6.1.2.1.1.4.0", "admin"), str("1.3.6.1.2.1.1.5.0", "gw"), str("1.3.6.1.2.1.1.6.0", "rack"))
    private val interfaces = listOf(
        str("1.3.6.1.2.1.2.2.1.2.1", "eth0"), str("1.3.6.1.2.1.2.2.1.2.2", "wlan0"), str("1.3.6.1.2.1.2.2.1.2.10", "lo"),
        num("1.3.6.1.2.1.2.2.1.3.1", 6), num("1.3.6.1.2.1.2.2.1.3.10", 24),                                  // ifType missing for index 2: sparse
        num("1.3.6.1.2.1.2.2.1.5.1", 4294967295L, 0x42), num("1.3.6.1.2.1.2.2.1.5.2", 54_000_000, 0x42), num("1.3.6.1.2.1.2.2.1.5.10", 10_000_000, 0x42),
        raw("1.3.6.1.2.1.2.2.1.6.1", byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)),
        num("1.3.6.1.2.1.2.2.1.8.1", 1), num("1.3.6.1.2.1.2.2.1.8.2", 2), num("1.3.6.1.2.1.2.2.1.8.10", 1),
        num("1.3.6.1.2.1.31.1.1.1.15.1", 10_000, 0x42))                                                       // ifHighSpeed 10000 Mbit/s
    private val addresses = listOf(ip("1.3.6.1.2.1.4.20.1.1.10.0.3.1", "10.0.3.1"), ip("1.3.6.1.2.1.4.20.1.1.10.0.4.1", "10.0.4.1"),
        ip("1.3.6.1.2.1.4.20.1.3.10.0.3.1", "255.255.255.0"), ip("1.3.6.1.2.1.4.20.1.3.10.0.4.1", "255.255.0.0"))

    private suspend fun run(ex: SnmpExchange): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return SnmpWalkTool { _, _ -> ex }.run(target, ToolInputs.NONE, control(), p) to p
    }

    @Test fun `a request lost to a pause is retried once and the walk continues`() = runTest {
        val c = ToolTestSupport.EpochControl(); val agent = SnmpTestAgent.exchange(system + interfaces + addresses); var first = true
        val ex = object : SnmpExchange {
            override suspend fun getNext(oids: List<String>): SnmpStep {
                if (first) { first = false; c.epoch++; return SnmpStep.Timeout }                           // a pause began mid-request
                return agent.getNext(oids)
            }
            override suspend fun get(oids: List<String>) = agent.get(oids)
        }
        val r = SnmpWalkTool { _, _ -> ex }.run(target, ToolInputs.NONE, c, CollectingProgress())
        assertEquals("gw, 3 interfaces", r.summary)
    }
    @Test fun `no system answer is FAILED`() = runTest {
        val (r, _) = run(object : SnmpExchange { override suspend fun getNext(oids: List<String>) = SnmpStep.Timeout; override suspend fun get(oids: List<String>) = SnmpStep.Timeout })
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SNMP answer to public", r.summary)
    }
    @Test fun `the system group, its formatting and its hints`() = runTest {
        val (r, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf("sysDescr" to "Linux gw 5.10", "sysObjectID" to "1.3.6.1.4.1.8072.3.2.10", "Uptime" to "12 days 4 h",
            "sysContact" to "admin", "sysName" to "gw", "sysLocation" to "rack"), p.of(FindingKind.SNMP_SYSTEM).map { it.detail to it.value })
        assertEquals(listOf("snmp.sysDescr=Linux gw 5.10", "snmp.sysObjectID=1.3.6.1.4.1.8072.3.2.10", "snmp.sysName=gw"), p.hints())
        assertEquals("gw, 3 interfaces", r.summary); assertNull(p.lastTotal)
    }
    @Test fun `interfaces assemble by index with a sparse column and the high speed substitution`() = runTest {
        val (_, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf(
            "eth0" to "up, ethernetCsmacd, 10 Gbit/s, 00:11:22:33:44:55",
            "wlan0" to "down, 54 Mbit/s",
            "lo" to "up, softwareLoopback, 10 Mbit/s"), p.of(FindingKind.SNMP_INTERFACE).map { it.value to it.detail })
    }
    @Test fun `addresses carry their prefix`() = runTest {
        val (_, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf("10.0.3.1/24", "10.0.4.1/16"), p.of(FindingKind.SNMP_ADDRESS).map { it.value })
        assertTrue(p.of(FindingKind.SNMP_ADDRESS).all { it.detail == "Interface address" })
    }
    @Test fun `a step that times out ends that step only`() = runTest {
        val agent = SnmpTestAgent.exchange(system + interfaces + addresses)
        val ex = object : SnmpExchange {
            override suspend fun getNext(oids: List<String>) = if (oids.any { it.startsWith("1.3.6.1.2.1.2.2.1") }) SnmpStep.Timeout else agent.getNext(oids)
            override suspend fun get(oids: List<String>) = agent.get(oids)
        }
        val (r, p) = run(ex)
        assertEquals(ToolRunStatus.DONE, r.status); assertTrue(p.of(FindingKind.SNMP_INTERFACE).isEmpty())
        assertEquals(2, p.of(FindingKind.SNMP_ADDRESS).size); assertEquals("gw, 0 interfaces", r.summary)
    }
    @Test fun `an error status ends the step`() = runTest {
        val ex = object : SnmpExchange { override suspend fun getNext(oids: List<String>) = SnmpStep.Error; override suspend fun get(oids: List<String>) = SnmpStep.Error }
        assertEquals(ToolRunStatus.FAILED, run(ex).first.status)
    }
    @Test fun `the walk stops at four hundred requests`() = runTest {
        var requests = 0
        val endless = object : SnmpExchange {   // an agent that never leaves the system subtree
            override suspend fun getNext(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { str("1.3.6.1.2.1.1.9.$requests", "x") }) }
            override suspend fun get(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { end(it) }) }
        }
        run(endless)
        assertEquals(SnmpWalkTool.SNMP_MAX_REQUESTS, requests)
    }

    @Test fun `an oid that does not increase ends the step`() = runTest {
        var requests = 0
        val stuck = object : SnmpExchange {   // an agent that answers every GETNEXT with the same system OID
            override suspend fun getNext(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { str("1.3.6.1.2.1.1.5.0", "gw") }) }
            override suspend fun get(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { end(it) }) }
        }
        val (r, p) = run(stuck)
        assertEquals(1, p.of(FindingKind.SNMP_SYSTEM).size); assertEquals("gw, 0 interfaces", r.summary)
        assertTrue("requests $requests", requests <= 4)
    }
    @Test fun `device text is stripped of control characters and capped`() = runTest {
        val long = "A".repeat(1000)
        val (_, p) = run(SnmpTestAgent.exchange(listOf(str("1.3.6.1.2.1.1.1.0", "Linux\ngw\u001b[31m\u202e"), str("1.3.6.1.2.1.1.5.0", long),
            str("1.3.6.1.2.1.2.2.1.2.1", "eth\r0"))))
        assertEquals("Linuxgw[31m", p.of(FindingKind.SNMP_SYSTEM).first().value)
        assertEquals("snmp.sysDescr=Linuxgw[31m", p.hints().first())
        assertEquals(SnmpWalkTool.SNMP_MAX_TEXT, p.of(FindingKind.SNMP_SYSTEM)[1].value.length)
        assertEquals("eth0", p.of(FindingKind.SNMP_INTERFACE).single().value)
    }
    @Test fun `every request passes the gate first`() = runTest {
        var gates = 0; var requests = 0
        val c = object : RunControl { override suspend fun gate() { gates++ }; override val pauseEpoch = 0; override fun excused(startEpoch: Int) = false }
        SnmpWalkTool { _, _ -> SnmpTestAgent.exchange(system + interfaces + addresses) { requests++ } }.run(target, ToolInputs.NONE, c, CollectingProgress())
        assertTrue(requests > 0); assertEquals(requests, gates)
    }
    @Test fun `the interface table stops at its row cap`() = runTest {
        val many = (1..(SnmpWalkTool.SNMP_MAX_INTERFACES + 10)).map { str("1.3.6.1.2.1.2.2.1.2.$it", "if$it") }
        val (r, p) = run(SnmpTestAgent.exchange(system + many))
        assertEquals(SnmpWalkTool.SNMP_MAX_INTERFACES, p.of(FindingKind.SNMP_INTERFACE).size)
        assertEquals("gw, ${SnmpWalkTool.SNMP_MAX_INTERFACES} interfaces", r.summary)
    }

    // Fix round 1.
    @Test fun `a stuck ifDescr column ends the table walk`() = runTest {
        var requests = 0; val agent = SnmpTestAgent.exchange(system + interfaces + addresses)
        val ex = object : SnmpExchange {   // the ifDescr column answers its first row again and again
            override suspend fun getNext(oids: List<String>): SnmpStep {
                requests++
                return SnmpStep.Ok(oids.map { q ->
                    if (q.startsWith("1.3.6.1.2.1.2.2.1.2")) str("1.3.6.1.2.1.2.2.1.2.1", "eth0")
                    else (agent.getNext(listOf(q)) as SnmpStep.Ok).varbinds.single()
                })
            }
            override suspend fun get(oids: List<String>): SnmpStep { requests++; return agent.get(oids) }
        }
        val (r, p) = run(ex)
        assertEquals(listOf("eth0"), p.of(FindingKind.SNMP_INTERFACE).map { it.value }); assertEquals("gw, 1 interface", r.summary)
        assertTrue("requests $requests", requests < 20)
        assertEquals(2, p.of(FindingKind.SNMP_ADDRESS).size)
    }
    @Test fun `an excused retry counts against the request cap`() = runTest {
        val c = ToolTestSupport.EpochControl(); var requests = 0
        val ex = object : SnmpExchange {   // never leaves the system subtree; the 400th request is lost to a pause
            override suspend fun getNext(oids: List<String>): SnmpStep {
                requests++
                if (requests == SnmpWalkTool.SNMP_MAX_REQUESTS) { c.epoch++; return SnmpStep.Timeout }
                return SnmpStep.Ok(oids.map { str("1.3.6.1.2.1.1.9.$requests", "x") })
            }
            override suspend fun get(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { end(it) }) }
        }
        SnmpWalkTool { _, _ -> ex }.run(target, ToolInputs.NONE, c, CollectingProgress())
        assertEquals(SnmpWalkTool.SNMP_MAX_REQUESTS, requests)
    }
    @Test fun `a silent ifHighSpeed costs one request, not one per interface`() = runTest {
        val saturated = (1..3).flatMap { listOf(str("1.3.6.1.2.1.2.2.1.2.$it", "eth$it"), num("1.3.6.1.2.1.2.2.1.5.$it", 4294967295L, 0x42)) }
        val agent = SnmpTestAgent.exchange(system + saturated); var gets = 0
        val ex = object : SnmpExchange {
            override suspend fun getNext(oids: List<String>) = agent.getNext(oids)
            override suspend fun get(oids: List<String>): SnmpStep { gets++; return SnmpStep.Timeout }
        }
        val (r, p) = run(ex)
        assertEquals(1, gets); assertEquals("gw, 3 interfaces", r.summary)
        assertEquals(listOf("4294 Mbit/s", "4294 Mbit/s", "4294 Mbit/s"), p.of(FindingKind.SNMP_INTERFACE).map { it.detail })
    }
    @Test fun `a hostile ifHighSpeed never overflows the speed`() = runTest {
        val rows = listOf(str("1.3.6.1.2.1.2.2.1.2.1", "a"), num("1.3.6.1.2.1.2.2.1.5.1", 4294967295L, 0x42), num("1.3.6.1.2.1.31.1.1.1.15.1", Long.MAX_VALUE / 1000, 0x42),
            str("1.3.6.1.2.1.2.2.1.2.2", "b"), num("1.3.6.1.2.1.2.2.1.5.2", 4294967295L, 0x42), num("1.3.6.1.2.1.31.1.1.1.15.2", 4294967295L, 0x42),
            str("1.3.6.1.2.1.2.2.1.2.3", "c"), num("1.3.6.1.2.1.2.2.1.5.3", 4294967295L, 0x42), num("1.3.6.1.2.1.31.1.1.1.15.3", -5, 0x02))
        val (_, p) = run(SnmpTestAgent.exchange(system + rows))
        assertEquals(listOf("a" to "4294 Mbit/s", "b" to "4294967295 Mbit/s", "c" to "4294 Mbit/s"), p.of(FindingKind.SNMP_INTERFACE).map { it.value to it.detail })
    }
}
