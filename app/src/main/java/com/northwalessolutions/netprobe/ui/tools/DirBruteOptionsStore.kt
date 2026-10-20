package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import kotlinx.serialization.json.Json

/** Last-used brute-force options, saved globally when Run is tapped (spec 5.1). Invalid stored JSON loads the defaults. */
class DirBruteOptionsStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): DirBruteOptions {
        val stored = read()?.let { runCatching { json.decodeFromString<DirBruteOptions>(it) }.getOrNull() }
        return stored?.coerced() ?: DirBruteOptions()
    }
    fun save(o: DirBruteOptions) { write(json.encodeToString(DirBruteOptions.serializer(), o.coerced())) }
    companion object { const val KEY = "tool.dirBrute.options" }
}
