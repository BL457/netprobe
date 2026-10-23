package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import java.io.File
import java.security.MessageDigest

/** Reads a bundled file under assets/tools/; null when it is missing. */
fun interface ScriptAssets {
    fun read(name: String): String?
}

/**
 * Where script tools come from. In this batch only the three bundled examples (spec D8) are loaded; the installed and imported scripts
 * under files/tools/ (spec D9) are batch B4's, and will be added to [load]. A script that does not evaluate or whose description has
 * errors is left out and reported through [log], so one bad file never hides the others.
 */
class ScriptToolStore(
    private val assets: ScriptAssets,
    private val engine: ScriptEngine,
    private val moduleFor: (ToolDescriptor, String) -> ToolModule,
    private val log: (String) -> Unit = {},
) {
    suspend fun load(): List<ToolModule> = loadExamples()

    suspend fun loadExamples(): List<ToolModule> {
        val out = ArrayList<ToolModule>()
        for (file in EXAMPLE_FILES) {
            val source = assets.read(file)
            if (source == null) { log("example script missing: $file"); continue }
            val loaded = engine.evaluate(source)
            if (loaded !is ScriptEvaluation.Loaded) { log("example script $file: ${(loaded as ScriptEvaluation.Failed).message}"); continue }
            val sha = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            when (val parsed = ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, EXAMPLE_PREFIX, ToolCategory.EXAMPLES, ToolSource.Script(File("assets/tools/$file"), sha))) {
                is ParsedTool.Valid -> out += moduleFor(parsed.descriptor, source)
                is ParsedTool.Invalid -> log("example script $file: ${parsed.errors.joinToString("; ")}")
            }
        }
        return out
    }

    companion object {
        /** Tool keys of the bundled examples are "example:<id>", so they can never clash with an imported "user:<slug>" (spec D5). */
        const val EXAMPLE_PREFIX = "example:"
        val EXAMPLE_FILES = listOf("http-headers.js", "banner-grab.js", "dns-lookup.js")
    }
}
