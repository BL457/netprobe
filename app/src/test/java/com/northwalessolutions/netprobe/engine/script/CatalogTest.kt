package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real files in `catalog/` (spec section 11): every script evaluates in the sandbox, parses to a valid description, and matches its line
 * in `index.json`. A contributor who changes a script without running tools/make-catalog-index.ps1 fails here.
 */
class CatalogTest {
    private val dir = File("../catalog")
    private val engine = RhinoScriptEngine(ScriptLimits())

    private fun scripts(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }!!.sortedBy { it.name }
    private fun index(): CatalogIndexResult.Parsed =
        CatalogIndex.parse(File(dir, "index.json").readText(Charsets.UTF_8)) as? CatalogIndexResult.Parsed ?: error("catalog/index.json is not readable")
    /** What GitHub serves: the file with Unix line endings. */
    private fun served(f: File): String = f.readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test fun `the index is readable and every line is accepted`() {
        val parsed = index()
        assertTrue("rejected lines: ${parsed.rejected}", parsed.rejected.isEmpty())
        assertTrue(parsed.entries.isNotEmpty())
    }

    @Test fun `the index lists exactly the scripts in the folder`() {
        assertEquals(scripts().map { it.name }, index().entries.map { it.file }.sorted())
        assertEquals("the index is sorted by id", index().entries.map { it.id }.sorted(), index().entries.map { it.id })
    }

    @Test fun `every script evaluates in the sandbox, parses, and matches its index line`() {
        val entries = index().entries.associateBy { it.file }
        for (file in scripts()) {
            val source = served(file)
            assertTrue("${file.name} is larger than ${ScriptCaps.MAX_SOURCE_BYTES} bytes", source.toByteArray(Charsets.UTF_8).size <= ScriptCaps.MAX_SOURCE_BYTES)
            val loaded = kotlinx.coroutines.runBlocking { engine.evaluate(source) }
            assertTrue("${file.name}: $loaded", loaded is ScriptEvaluation.Loaded)
            loaded as ScriptEvaluation.Loaded
            val parsed = ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.BuiltIn)
            assertTrue("${file.name}: $parsed", parsed is ParsedTool.Valid)
            val d = (parsed as ParsedTool.Valid).descriptor
            val e = entries.getValue(file.name)
            val name = file.name
            assertEquals("$name key", "user:${file.name.removeSuffix(".js")}", d.key)
            assertEquals("$name id", e.id, d.key.removePrefix(ToolKeys.USER_PREFIX))
            assertEquals("$name name", e.name, d.name)
            assertEquals("$name version", e.version, d.version)
            assertEquals("$name network", e.network, d.network)
            assertEquals("$name broadcast", e.broadcast, d.broadcast)
            assertEquals("$name description", e.description, d.description)
            assertEquals("$name sha256 (run tools/make-catalog-index.ps1)", e.sha256, ScriptBytes.sha256(source))
        }
    }

    @Test fun `the folder holds only scripts, the index and the readme`() {
        val others = dir.listFiles()!!.map { it.name }.filter { !it.endsWith(".js") } .sorted()
        assertEquals(listOf("README.md", "index.json"), others)
    }
}
