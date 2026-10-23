package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolIcon
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolProgress
import com.northwalessolutions.netprobe.engine.tools.ToolResult
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.engine.tools.ToolTarget

/** A module that never runs: for tile, grouping and form tests, which only read the descriptor. */
class DescribedModule(override val descriptor: ToolDescriptor, private val checks: (ToolInputs) -> Map<String, String> = { emptyMap() }) : ToolModule {
    override fun applies(port: Int, service: ServiceInfo) = false
    override fun check(inputs: ToolInputs): Map<String, String> = checks(inputs)
    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult =
        ToolResult(ToolRunStatus.DONE, "")
}

object UiToolFakes {
    fun descriptor(
        key: String, name: String = key, category: ToolCategory = ToolCategory.WEB, target: TargetKind = TargetKind.PORT, inputs: List<InputSpec> = emptyList(),
        network: NetworkScope = NetworkScope.TARGET, icon: ToolIcon = ToolIcon.TERMINAL, description: String = "Does $name", source: ToolSource = ToolSource.BuiltIn,
    ) = ToolDescriptor(key, name, description, icon, category, target, inputs, network, "1", null, source)

    fun module(
        key: String, name: String = key, category: ToolCategory = ToolCategory.WEB, target: TargetKind = TargetKind.PORT, inputs: List<InputSpec> = emptyList(),
        network: NetworkScope = NetworkScope.TARGET, description: String = "Does $name",
    ): ToolModule = DescribedModule(descriptor(key, name, category, target, inputs, network, description = description))
}
