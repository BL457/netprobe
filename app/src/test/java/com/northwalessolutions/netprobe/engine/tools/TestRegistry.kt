package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteforceTool
import com.northwalessolutions.netprobe.engine.tools.smb.SmbSessionTool

/** The seven real built-in modules with dependencies that fail if a test ever runs them: for descriptor, applicability and naming tests. */
object TestRegistry {
    private fun unused(): Nothing = throw IllegalStateException("not a run test")
    fun builtIns(): List<ToolModule> = listOf(
        DirBruteforceTool(exchangeFor = { _, _ -> unused() }, resolve = { null }),
        HttpFingerprintTool(getFor = { unused() }, rules = emptyList()),
        TlsCertificateTool(peek = { _, _ -> null }),
        SmbSessionTool(connect = { null }),
        SnmpWalkTool(exchangeFor = { _, _ -> unused() }),
        IdentifyServiceTool(identify = { _, _, _, _ -> unused() }, plannedProbes = { _, _ -> 0 }, apply = { _, _, _ -> }),
        IdentifyAllServicesTool(portsFor = { emptyList() }, identify = { _, _, _, _ -> unused() }, apply = { _, _, _ -> }),
    )
    fun registry(): ToolRegistry = ToolRegistry(builtIns())
}
