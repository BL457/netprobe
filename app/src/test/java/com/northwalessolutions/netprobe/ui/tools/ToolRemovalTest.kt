package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.script.InstalledTool
import com.northwalessolutions.netprobe.engine.script.RhinoScriptEngine
import com.northwalessolutions.netprobe.engine.script.ScriptAssets
import com.northwalessolutions.netprobe.engine.script.ScriptDescriptorParser
import com.northwalessolutions.netprobe.engine.script.ScriptEvaluation
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.script.ScriptToolStore
import com.northwalessolutions.netprobe.engine.script.ParsedTool
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** My tools rows and Remove: the script goes, the remembered inputs go, history keeps reading by the stored tool name. */
class ToolRemovalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = NetworkEnv(Ipv4.parse("192.168.1.50")!!, 24, null, emptyList(), null, null, Transport.WIFI)
    private val engine = RhinoScriptEngine()
    private val root: File by lazy { File(tmp.root, "tools") }
    private val store: ScriptToolStore by lazy {
        ScriptToolStore(ScriptAssets { null }, engine, { d, s -> ScriptToolModule(d, s, engine, { env }, { null }) }, {}, root, { 9L })
    }
    private val forgotten = ArrayList<String>()
    private val removal by lazy { ToolRemoval(store) { forgotten += it } }

    private fun script(id: String, name: String, version: String = "1.0", network: String = "target") =
        """tool = { id: "$id", name: "$name", version: "$version", target: "none", network: "$network", run: function () {} };"""

    private fun install(id: String, name: String, version: String = "1.0", network: String = "target") {
        val source = script(id, name, version, network)
        val loaded = runBlocking { engine.evaluate(source) } as ScriptEvaluation.Loaded
        val d = (ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.Script(File("x"), "")) as ParsedTool.Valid).descriptor
        runBlocking { store.save(d, source, null, "$id.js") }
    }

    @Test fun `My tools rows carry name version and scope, and a broken one its error`() {
        val rows = MyTools.rows(listOf(
            InstalledTool("user:a-tool", "A tool", "1.2", NetworkScope.LAN, 1L, "ab", null, "a.js", null),
            InstalledTool("user:b-tool", "B tool", "", null, 0L, "", null, null, ImportCopy.FILE_MISSING),
        ))
        assertEquals(MyToolRow("user:a-tool", "A tool", "Version 1.2, contacts your network", null), rows[0])
        assertEquals(MyToolRow("user:b-tool", "B tool", "Broken: The script file is missing", ImportCopy.FILE_MISSING), rows[1])
        assertTrue(MyTools.rows(emptyList()).isEmpty())
        assertEquals("Version 1", ImportCopy.myToolLine("1", null))
    }

    @Test fun `Remove deletes the script, forgets the inputs and names the tool`() {
        install("my-tool", "My tool"); install("other-tool", "Other")
        assertEquals("Removed My tool", runBlocking { removal.remove("user:my-tool") })
        assertEquals(listOf("user:my-tool"), forgotten)
        assertFalse(File(root, "my-tool.js").exists())
        assertEquals(listOf("user:other-tool"), store.installed.value.map { it.key })
        assertEquals(listOf("user:other-tool"), store.modules.value.map { it.descriptor.key })
    }

    @Test fun `a broken tool can be removed`() {
        install("my-tool", "My tool")
        File(root, "my-tool.js").delete()
        runBlocking { store.load() }
        assertTrue(store.installed.value.single().broken)
        assertEquals("Removed My tool", runBlocking { removal.remove("user:my-tool") })
        assertTrue(store.installed.value.isEmpty())
    }

    @Test fun `built-ins examples and unknown tools are not removable`() {
        install("my-tool", "My tool")
        for (key in listOf("dir-bruteforce", "example:http-headers", "user:nothing-here", "user:../x")) {
            assertEquals(key, ImportCopy.REMOVE_FAILED, runBlocking { removal.remove(key) })
        }
        assertTrue(forgotten.isEmpty())
        assertEquals(1, store.installed.value.size)
    }

    @Test fun `a removed tool's runs keep reading by the name stored with them`() {
        install("my-tool", "My tool")
        val registry = ToolRegistry(emptyList())
        registry.setScriptTools(store.modules.value)
        val run = ToolRunEntity(id = 1, hostId = null, toolId = "user:my-tool", port = 0, startedAt = 1, finishedAt = 2, status = ToolRunStatus.DONE, optionsJson = "{}", summary = "", toolName = "My tool")
        assertEquals("My tool", ToolsUiModel.nameOf(registry, run))
        assertTrue(runBlocking { removal.remove("user:my-tool") }.startsWith("Removed"))
        registry.setScriptTools(store.modules.value)
        assertNull(registry.byKey("user:my-tool"))
        assertEquals("My tool", ToolsUiModel.nameOf(registry, run))
    }
}
