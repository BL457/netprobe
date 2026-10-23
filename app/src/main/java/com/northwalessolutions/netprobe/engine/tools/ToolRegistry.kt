package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tools the app can run (spec section 3): the built-ins in their given order, then script tools. [all] republishes when the script
 * tools change, so a later batch can plug its store in through [setScriptTools] without touching the built-ins. A script tool whose key
 * equals a built-in key, or repeats an earlier script key, or whose inputs break [DescriptorRules], is dropped (spec D5).
 */
class ToolRegistry(private val builtIns: List<ToolModule>) {
    init {
        for (m in builtIns) require(DescriptorRules.errors(m.descriptor).isEmpty()) { "built-in ${m.descriptor.key}: ${DescriptorRules.errors(m.descriptor)}" }
    }
    private val _all = MutableStateFlow(builtIns)
    val all: StateFlow<List<ToolModule>> = _all

    fun byKey(key: String): ToolModule? = _all.value.firstOrNull { it.descriptor.key == key }

    fun setScriptTools(tools: List<ToolModule>) {
        val taken = builtIns.mapTo(HashSet()) { it.descriptor.key }
        _all.value = builtIns + tools.filter { DescriptorRules.errors(it.descriptor).isEmpty() && taken.add(it.descriptor.key) }
    }
}
