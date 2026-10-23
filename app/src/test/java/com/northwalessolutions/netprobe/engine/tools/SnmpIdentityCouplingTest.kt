package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.oidv
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.str
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Hint-to-rule coupling: the snmp.* hints the real walk emits set the vendor, os and type through the real identify rules. */
class SnmpIdentityCouplingTest {
    private val identifier = DeviceIdentifier(IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()))
    private val host = ip("10.0.3.9")

    private suspend fun identify(sysObjectId: String): DeviceIdentity {
        val p = CollectingProgress()
        val mib = listOf(str("1.3.6.1.2.1.1.1.0", "an agent"), oidv("1.3.6.1.2.1.1.2.0", sysObjectId), str("1.3.6.1.2.1.1.5.0", "box"))
        SnmpWalkTool { _, _ -> SnmpTestAgent.exchange(mib) }.run(ToolTarget(1, host, 161, false, null), ToolInputs.NONE, control(), p)
        return identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = p.hints()))
    }
    @Test fun `a synology enterprise oid sets vendor and type`() = runTest {
        identify("1.3.6.1.4.1.6574.1").let { assertEquals("Synology", it.vendor); assertEquals(DeviceType.NAS, it.type) }
    }
    @Test fun `a mikrotik enterprise oid sets vendor, os and type`() = runTest {
        identify("1.3.6.1.4.1.14988.1").let { assertEquals("MikroTik", it.vendor); assertEquals("RouterOS", it.os); assertEquals(DeviceType.ROUTER, it.type) }
    }
    @Test fun `a net-snmp enterprise oid sets the os`() = runTest {
        assertEquals("Linux", identify("1.3.6.1.4.1.8072.3.2.10").os)
    }
}
