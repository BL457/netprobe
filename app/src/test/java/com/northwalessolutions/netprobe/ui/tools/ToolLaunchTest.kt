package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.ToolRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolResult
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import org.junit.Assert.*
import org.junit.Test

/** The device page buttons (ruling b) and where they send a tool: straight to a run, or to the tool page with the device and port filled in. */
class ToolLaunchTest {
    private val registry = TestRegistry.registry()
    private fun d(key: String) = registry.byKey(key)!!.descriptor
    private val http = ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)

    @Test fun `tools with no inputs of their own run at once from the device page`() {
        listOf(ToolKeys.HTTP_FINGERPRINT, ToolKeys.TLS_CERTIFICATE, ToolKeys.SMB_SESSION, ToolKeys.IDENTIFY, ToolKeys.IDENTIFY_ALL).forEach {
            assertFalse(it, ToolLaunch.opensPage(d(it)))
        }
    }
    @Test fun `tools with inputs open their page`() {
        assertTrue(ToolLaunch.opensPage(d(ToolKeys.DIR_BRUTEFORCE)))
        assertTrue("snmp asks for a community and a version", ToolLaunch.opensPage(d(ToolKeys.SNMP_WALK)))
    }
    @Test fun `a network any tool opens its page even with no inputs, because the confirmation lives there`() {
        assertTrue(ToolLaunch.opensPage(UiToolFakes.descriptor("user:a", network = NetworkScope.ANY)))
        assertFalse(ToolLaunch.opensPage(UiToolFakes.descriptor("user:a", network = NetworkScope.LAN)))
        assertTrue(ToolLaunch.opensPage(UiToolFakes.descriptor("user:a", inputs = listOf(InputSpec.Bool("b", "B")))))
    }

    /** A script tool as the registry sees it: [portRule] answers the per-port question, [hostRule] the per-host one. */
    private class Offering(override val descriptor: ToolDescriptor, private val portRule: (Int) -> Boolean, private val hostRule: (HostEntity) -> Boolean) : ToolModule {
        override fun applies(port: Int, service: ServiceInfo) = portRule(port)
        override fun appliesToHost(host: HostEntity) = hostRule(host)
        override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress) = ToolResult(ToolRunStatus.DONE, "")
    }
    private fun withScripts(vararg tools: ToolModule) = ToolRegistry(TestRegistry.builtIns()).also { it.setScriptTools(tools.toList()) }

    @Test fun `a script port tool gets a chip on every port it applies to, after the built-ins`() {
        val port = Offering(UiToolFakes.descriptor("user:probe", "Probe", ToolCategory.MINE, TargetKind.PORT), { true }, { false })
        val reg = withScripts(port)
        val state = ToolsUiModel.of(reg, TestHosts.host().copy(id = 7), listOf(80 to http, 22 to ServiceInfo("SSH", confidence = Confidence.CONFIRMED)), emptyList(), 0L)!!
        val block = state.blocks.single { it.toolId == "user:probe" }
        assertEquals(listOf("22", "80"), block.chips.map { it.label }); assertEquals("Probe", block.name)
        assertEquals("the script block comes after the built-in blocks", "user:probe", state.blocks.last().toolId)
        assertEquals(listOf("user:probe"), ToolApplicability.toolsFor(reg, 22, http).map { it.descriptor.key }.filter { it.startsWith("user:") })
    }
    @Test fun `a script host tool gets one chip on a host it applies to and none on another`() {
        val lanOnly = Offering(UiToolFakes.descriptor("user:sweep", "Sweep", ToolCategory.MINE, TargetKind.HOST, network = NetworkScope.LAN), { true }, { it.ip.startsWith("192.168.1.") })
        val reg = withScripts(lanOnly)
        val inside = ToolsUiModel.of(reg, TestHosts.host("192.168.1.20").copy(id = 7), listOf(80 to http), emptyList(), 0L)!!
        assertEquals(listOf("0" to "all open ports"), inside.blocks.single { it.toolId == "user:sweep" }.chips.map { it.port.toString() to it.label })
        val outside = ToolsUiModel.of(reg, TestHosts.host("172.16.0.5").copy(id = 8), listOf(80 to http), emptyList(), 0L)!!
        assertTrue(outside.blocks.none { it.toolId == "user:sweep" })
    }
    @Test fun `a script tool with no target never appears on the device page`() {
        val none = Offering(UiToolFakes.descriptor("user:dns", "Dns", ToolCategory.MINE, TargetKind.NONE), { true }, { true })
        val state = ToolsUiModel.of(withScripts(none), TestHosts.host().copy(id = 7), listOf(80 to http), emptyList(), 0L)!!
        assertTrue(state.blocks.none { it.toolId == "user:dns" })
    }
}
