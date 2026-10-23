package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolCategory
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reads a bundled file under assets/tools/; null when it is missing. */
fun interface ScriptAssets {
    fun read(name: String): String?
}

/** One line of files/tools/index.json (spec D9). The scope is "target", "lan" or "any"; one of [sourceUrl] and [fileName] says where the script came from. */
@Serializable
data class ToolIndexEntry(
    val key: String, val name: String, val version: String, val sha256: String, val scope: String, val importedAt: Long,
    val sourceUrl: String? = null, val fileName: String? = null,
    /** True when the tool was installed by "Update tools from GitHub": [sourceUrl] and [fileName] then name the catalog script. */
    val catalog: Boolean = false,
)

@Serializable
private data class ToolIndex(val format: Int = 1, val tools: List<ToolIndexEntry> = emptyList())

/** An imported tool as My tools lists it. [error] is set for a broken entry: it is shown and removable, and is never offered as a tool. */
data class InstalledTool(
    val key: String, val name: String, val version: String, val scope: NetworkScope?, val importedAt: Long, val sha256: String,
    val sourceUrl: String?, val fileName: String?, val error: String?, val catalog: Boolean = false,
) {
    val broken: Boolean get() = error != null
}

sealed interface SaveResult {
    data class Saved(val replaced: Boolean) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * Where script tools come from (spec D8, D9): the three bundled examples, and the imported scripts under `<dir>/<slug>.js` with
 * `<dir>/index.json`. [load] reads everything again; a script that does not evaluate, whose description has errors, whose file is missing
 * or whose bytes no longer match the recorded sha256 is a broken entry, listed in [installed] with its error and left out of [modules], so
 * one bad file never hides the others. [save] and [remove] change the files and both flows; the registry follows [modules].
 *
 * Nothing here fetches anything: an imported script is never downloaded again by the app (spec section 8).
 */
class ScriptToolStore(
    private val assets: ScriptAssets,
    private val engine: ScriptEngine,
    private val moduleFor: (ToolDescriptor, String) -> ToolModule,
    private val log: (String) -> Unit = {},
    /** files/tools; null keeps the store to the bundled examples (the old behaviour, used by the example tests). */
    private val dir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var examples: List<ToolModule> = emptyList()
    private var installedModules: Map<String, ToolModule> = emptyMap()
    private var entries: List<ToolIndexEntry> = emptyList()
    /** Why each broken tool is broken, by key; [brokenKeys] are the broken ones with no index line (a script file the list does not know). */
    private var broken: Map<String, String> = emptyMap()
    private var brokenKeys: Set<String> = emptySet()

    private val _modules = MutableStateFlow<List<ToolModule>>(emptyList())
    private val _installed = MutableStateFlow<List<InstalledTool>>(emptyList())
    /** The examples and every working imported tool. */
    val modules: StateFlow<List<ToolModule>> = _modules
    /** Every imported tool, working or broken, by name. */
    val installed: StateFlow<List<InstalledTool>> = _installed

    suspend fun load(): List<ToolModule> {
        return mutex.withLock {
            examples = loadExamples()
            loadInstalled()
            publish()
            _modules.value
        }
    }

    suspend fun loadExamples(): List<ToolModule> {
        val out = ArrayList<ToolModule>()
        for (file in EXAMPLE_FILES) {
            val source = assets.read(file)
            if (source == null) { log("example script missing: $file"); continue }
            val loaded = engine.evaluate(source)
            if (loaded !is ScriptEvaluation.Loaded) { log("example script $file: ${(loaded as ScriptEvaluation.Failed).message}"); continue }
            val sha = ScriptBytes.sha256(source)
            when (val parsed = ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, EXAMPLE_PREFIX, ToolCategory.EXAMPLES, ToolSource.Script(File("assets/tools/$file"), sha))) {
                is ParsedTool.Valid -> out += moduleFor(parsed.descriptor, source)
                is ParsedTool.Invalid -> log("example script $file: ${parsed.errors.joinToString("; ")}")
            }
        }
        return out
    }

    /** The script file a key's slug names; null for a key that is not a well-formed "user:<slug>" (so no path is ever built from odd text). */
    fun fileFor(key: String): File? {
        val slug = slugOf(key) ?: return null
        return dir?.let { File(it, "$slug.js") }
    }

    /** The index line for [key], when it is installed (working or broken). */
    fun entry(key: String): ToolIndexEntry? = entries.firstOrNull { it.key == key }

    /** The working module of an imported tool, for the diff on a replace. */
    fun moduleOf(key: String): ToolModule? = installedModules[key]

    /**
     * Writes the script and its index line, replacing a tool with the same key. The script goes to a temporary file first and the old one
     * is put back if the index cannot be written, so a failure leaves the previous tool as it was.
     */
    suspend fun save(descriptor: ToolDescriptor, source: String, sourceUrl: String?, fileName: String?, catalog: Boolean = false): SaveResult {
        return mutex.withLock { saveLocked(descriptor, source, sourceUrl, fileName, catalog) }
    }

    private suspend fun saveLocked(descriptor: ToolDescriptor, source: String, sourceUrl: String?, fileName: String?, catalog: Boolean): SaveResult {
        val root = dir ?: return SaveResult.Failed(ImportCopy.SAVE_FAILED)
        val slug = slugOf(descriptor.key) ?: return SaveResult.Failed(ImportCopy.SAVE_FAILED)
        val sha = ScriptBytes.sha256(source)
        val file = File(root, "$slug.js")
        val entry = ToolIndexEntry(descriptor.key, descriptor.name, descriptor.version, sha, scopeId(descriptor.network), clock(), sourceUrl, fileName, catalog)
        val replaced = entries.any { it.key == descriptor.key }
        val ok = withContext(Dispatchers.IO) {
            try {
                root.mkdirs()
                val previous = file.takeIf { it.isFile }?.readBytes()
                writeAtomic(file, source.toByteArray(Charsets.UTF_8))
                try {
                    writeIndex(root, entries.filter { it.key != entry.key } + entry)
                    true
                } catch (e: Exception) {
                    runCatching { if (previous != null) writeAtomic(file, previous) else file.delete() }
                    throw e
                }
            } catch (e: Exception) {
                log("save ${descriptor.key}: $e")
                false
            }
        }
        if (!ok) return SaveResult.Failed(ImportCopy.SAVE_FAILED)
        entries = entries.filter { it.key != entry.key } + entry
        val module = moduleFor(descriptor.copy(source = sourceOf(catalog, file, sha)), source)
        installedModules = installedModules + (descriptor.key to module)
        brokenKeys = brokenKeys - descriptor.key
        broken = broken - descriptor.key
        publish()
        return SaveResult.Saved(replaced)
    }

    /** Deletes the script and its index line. True when the tool was installed (working or broken) and is gone. */
    suspend fun remove(key: String): Boolean {
        return mutex.withLock { removeLocked(key) }
    }

    private suspend fun removeLocked(key: String): Boolean {
        val root = dir ?: return false
        val slug = slugOf(key) ?: return false
        val known = entries.any { it.key == key } || key in brokenKeys
        if (!known) return false
        val ok = withContext(Dispatchers.IO) {
            try {
                val file = File(root, "$slug.js")
                if (file.exists() && !file.delete()) throw java.io.IOException("could not delete $file")
                writeIndex(root, entries.filter { it.key != key })
                true
            } catch (e: Exception) {
                log("remove $key: $e")
                false
            }
        }
        if (!ok) return false
        entries = entries.filter { it.key != key }
        installedModules = installedModules - key
        brokenKeys = brokenKeys - key
        broken = broken - key
        publish()
        return true
    }

    private suspend fun loadInstalled() {
        val root = dir
        if (root == null) { entries = emptyList(); installedModules = emptyMap(); broken = emptyMap(); brokenKeys = emptySet(); return }
        val read = withContext(Dispatchers.IO) { readIndex(root) }
        val problems = LinkedHashMap<String, String>()
        val modules = LinkedHashMap<String, ToolModule>()
        val indexed = read.entries.filter { slugOf(it.key) != null }.distinctBy { it.key }
        for (entry in indexed) {
            val slug = slugOf(entry.key) ?: continue
            val loaded = loadOne(File(root, "$slug.js"), entry)
            if (loaded.module != null) modules[entry.key] = loaded.module else problems[entry.key] = loaded.error ?: ImportCopy.FILE_UNREADABLE
        }
        // Script files the list does not know: listed so they can be removed, never loaded.
        val orphanReason = if (read.unreadable) ImportCopy.LIST_UNREADABLE else ImportCopy.NOT_IN_LIST
        val known = indexed.mapTo(HashSet()) { slugOf(it.key) }
        withContext(Dispatchers.IO) { root.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.toList().orEmpty() }.sortedBy { it.name }.forEach { f ->
            val slug = f.name.removeSuffix(".js")
            if (SLUG.matches(slug) && slug !in known) problems[ToolKeys.USER_PREFIX + slug] = orphanReason
        }
        entries = indexed
        installedModules = modules
        broken = problems
        brokenKeys = problems.keys.filter { key -> indexed.none { it.key == key } }.toSet()
    }

    private class One(val module: ToolModule?, val error: String?)

    private suspend fun loadOne(file: File, entry: ToolIndexEntry): One {
        val bytes = withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) null to ImportCopy.FILE_MISSING
                else if (file.length() > ScriptCaps.MAX_SOURCE_BYTES) null to ScriptCopy.SOURCE_TOO_LARGE
                else file.readBytes() to null
            } catch (e: Exception) { log("read ${file.name}: $e"); null to ImportCopy.FILE_UNREADABLE }
        }
        val raw = bytes.first ?: return One(null, bytes.second)
        if (!ScriptBytes.sha256(raw).equals(entry.sha256, ignoreCase = true)) return One(null, ImportCopy.FILE_CHANGED)
        val source = ScriptBytes.decode(raw) ?: return One(null, ImportCopy.FILE_NOT_TEXT)
        val evaluated = engine.evaluate(source)
        if (evaluated !is ScriptEvaluation.Loaded) return One(null, ImportCopy.cannotLoad((evaluated as ScriptEvaluation.Failed).message))
        val parsed = ScriptDescriptorParser.parse(evaluated.tool, evaluated.hasRun, ToolKeys.USER_PREFIX, null, sourceOf(entry.catalog, file, entry.sha256))
        return when (parsed) {
            is ParsedTool.Invalid -> One(null, ImportCopy.cannotLoad(parsed.errors.joinToString("; ")))
            is ParsedTool.Valid ->
                if (parsed.descriptor.key != entry.key) One(null, ImportCopy.KEY_MISMATCH)
                else One(moduleFor(parsed.descriptor, source), null)
        }
    }

    private fun publish() {
        _modules.value = examples + installedModules.values
        val working = installedModules.mapValues { (_, m) -> m.descriptor }
        val rows = ArrayList<InstalledTool>()
        for (e in entries) {
            val d = working[e.key]
            rows += InstalledTool(e.key, d?.name ?: e.name, d?.version ?: e.version, d?.network ?: scopeOf(e.scope), e.importedAt, e.sha256, e.sourceUrl, e.fileName, broken[e.key], e.catalog)
        }
        for (key in brokenKeys) rows += InstalledTool(key, key.removePrefix(ToolKeys.USER_PREFIX), "", null, 0L, "", null, null, broken[key])
        _installed.value = rows.sortedWith(compareBy<InstalledTool> { it.name.lowercase() }.thenBy { it.key })
    }

    private data class IndexRead(val entries: List<ToolIndexEntry>, val unreadable: Boolean)

    private fun readIndex(root: File): IndexRead {
        val file = File(root, INDEX_FILE)
        if (!file.isFile) return IndexRead(emptyList(), false)
        return try {
            IndexRead(json.decodeFromString<ToolIndex>(file.readText(Charsets.UTF_8)).tools, false)
        } catch (e: Exception) {
            log("tool list: $e")
            IndexRead(emptyList(), true)
        }
    }

    private fun writeIndex(root: File, list: List<ToolIndexEntry>) {
        writeAtomic(File(root, INDEX_FILE), json.encodeToString(ToolIndex.serializer(), ToolIndex(tools = list)).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Tool keys of the bundled examples are "example:<id>", so they can never clash with an imported "user:<slug>" (spec D5). */
        const val EXAMPLE_PREFIX = "example:"
        val EXAMPLE_FILES = listOf("http-headers.js", "banner-grab.js", "dns-lookup.js")
        const val INDEX_FILE = "index.json"
        private val SLUG = Regex("[a-z0-9-]{3,40}")

        /** The file stem of an imported tool's key, or null when the key is not "user:" plus a valid slug. */
        fun slugOf(key: String): String? = key.removePrefix(ToolKeys.USER_PREFIX).takeIf { key.startsWith(ToolKeys.USER_PREFIX) && SLUG.matches(it) }

        fun sourceOf(catalog: Boolean, file: File, sha: String): ToolSource = if (catalog) ToolSource.Catalog(file, sha) else ToolSource.Script(file, sha)

        fun scopeId(s: NetworkScope): String = when (s) { NetworkScope.TARGET -> "target"; NetworkScope.LAN -> "lan"; NetworkScope.ANY -> "any" }
        fun scopeOf(id: String): NetworkScope? = when (id) { "target" -> NetworkScope.TARGET; "lan" -> NetworkScope.LAN; "any" -> NetworkScope.ANY; else -> null }
    }
}
