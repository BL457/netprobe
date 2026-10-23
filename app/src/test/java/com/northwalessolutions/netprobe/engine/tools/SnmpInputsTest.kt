package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.str
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** SNMP walk inputs: a masked community and a version, defaults public and v2c, and the community never reaches optionsJson. */
class SnmpInputsTest {
    private val target = ToolTarget(7, ToolTestSupport.ip("10.0.3.1"), 161, false, null)
    private val mib = listOf(str("1.3.6.1.2.1.1.1.0", "Linux gw"), str("1.3.6.1.2.1.1.5.0", "gw"))
    private val descriptor = TestRegistry.registry().byKey("snmp-walk")!!.descriptor

    @Test fun `no inputs walk with public over v2c`() = runTest {
        var seen: SnmpSettings? = null
        SnmpWalkTool { _, s -> seen = s; SnmpTestAgent.exchange(mib) }.run(target, ToolInputs.NONE, control(), CollectingProgress())
        assertEquals(SnmpSettings("public", SnmpPdu.VERSION_2C), seen)
    }
    @Test fun `the chosen community and version reach the exchange`() = runTest {
        val v = (ToolInputs.validate(descriptor, mapOf("ip" to "10.0.3.1", "community" to "s3cret", "version" to "v1")) as InputsResult.Valid).inputs
        var seen: SnmpSettings? = null
        SnmpWalkTool { _, s -> seen = s; SnmpTestAgent.exchange(mib) }.run(target, v, control(), CollectingProgress())
        assertEquals(SnmpSettings("s3cret", SnmpPdu.VERSION_1), seen)
    }
    @Test fun `a form with nothing typed gets the defaults`() {
        val v = (ToolInputs.validate(descriptor, mapOf("ip" to "10.0.3.1")) as InputsResult.Valid).inputs
        assertEquals("public", v.text("community")); assertEquals("v2c", v.choice("version"))
    }
    @Test fun `the community is never written to optionsJson, and the version is`() {
        val v = (ToolInputs.validate(descriptor, mapOf("ip" to "10.0.3.1", "community" to "s3cret", "version" to "v1")) as InputsResult.Valid).inputs
        val json = ToolOptionsJson.encode(target, descriptor, v)
        assertFalse(json, json.contains("s3cret")); assertFalse(json.contains("community"))
        assertEquals("{\"tls\":false,\"serverName\":null,\"version\":\"v1\"}", json)
    }
    @Test fun `the pdu carries the version on the wire`() {
        val v1 = SnmpPdu.build(SnmpPdu.GET, listOf("1.3.6.1.2.1.1.1.0"), "public", 5, SnmpPdu.VERSION_1)
        val v2 = SnmpPdu.build(SnmpPdu.GET, listOf("1.3.6.1.2.1.1.1.0"), "public", 5)
        assertEquals(0, v1[4].toInt()); assertEquals(1, v2[4].toInt())       // SEQUENCE, length, INTEGER tag, length 1, then the version value
        assertEquals(v1.size, v2.size)
    }
}
