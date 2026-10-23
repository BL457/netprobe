package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.script.InputKinds
import com.northwalessolutions.netprobe.engine.script.ScriptDescriptorParser
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteInputs
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the remembered inputs live: one string per key. The app backs it with the `netprobe` SharedPreferences. */
interface LastUsedStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The last inputs a tool was run with, per tool key, as JSON (tools platform spec section 7, G10, G14). Saved when a run starts, put back
 * into the form when the page opens.
 *
 * What is never kept: a `secret` input, the per-run `confirmAny` flag, and the implicit `ip`, `port` and `tls` (the device and port are the
 * Director's choice each time). What is dropped when it is put back: a value whose input no longer exists, whose type changed, or that the
 * input's own rules no longer accept (a choice that lost the option, a number outside a new range); a wordlist that has been deleted.
 */
class LastUsedInputs(private val storage: LastUsedStorage) {
    @Serializable
    private data class Entry(val t: String, val v: String)

    @Serializable
    private data class Stored(val format: Int = 1, val inputs: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Remembers the raw form [values] of [descriptor]'s own inputs. Nothing to remember removes the stored entry. */
    fun save(descriptor: ToolDescriptor, values: Map<String, String>) {
        val entries = LinkedHashMap<String, Entry>()
        for (spec in descriptor.inputs) {
            if (!rememberable(spec)) continue
            val raw = values[spec.name] ?: continue
            entries[spec.name] = Entry(InputKinds.of(spec), raw)
        }
        write(descriptor.key, entries)
    }

    /**
     * The remembered raw values that still fit [descriptor]. [knownWordlists] are the ids of the wordlists that exist now, or null when that
     * is not known yet (then a wordlist value is kept and the form's own check handles a missing list).
     */
    fun restore(descriptor: ToolDescriptor, knownWordlists: Set<String>? = null): Map<String, String> {
        val stored = read(descriptor.key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (spec in descriptor.inputs) {
            if (!rememberable(spec)) continue
            val entry = stored.inputs[spec.name] ?: continue
            if (entry.t != InputKinds.of(spec)) continue
            if (!stillValid(descriptor, spec, entry.v)) continue
            if (spec is InputSpec.Wordlist && knownWordlists != null && entry.v.isNotEmpty() && entry.v !in knownWordlists) continue
            out[spec.name] = entry.v
        }
        return out
    }

    fun forget(key: String) { storage.remove(storageKey(key)) }

    /**
     * Moves the old brute-force options (`tool.dirBrute.options`, one global JSON object) into the dir-brute tool's remembered inputs, once,
     * and deletes the old key. A remembered entry the tool already has is kept (it is newer). An unreadable old value is simply deleted.
     * True when an old key was found.
     */
    fun migrateDirBrute(): Boolean {
        val old = storage.get(LEGACY_DIR_BRUTE_KEY) ?: return false
        try {
            if (storage.get(storageKey(ToolKeys.DIR_BRUTEFORCE)) == null) {
                val options = runCatching { json.decodeFromString<DirBruteOptions>(old) }.getOrNull()?.coerced()
                if (options != null) {
                    val raw = linkedMapOf(
                        DirBruteInputs.WORDLIST to options.wordlistId,
                        DirBruteInputs.EXTENSIONS to options.extensions.joinToString(","),
                        DirBruteInputs.METHOD to options.method.name,
                        DirBruteInputs.CONCURRENCY to options.concurrency.toString(),
                        DirBruteInputs.TIMEOUT_S to options.timeoutS.toString(),
                        DirBruteInputs.STATUS_CODES to options.statusCodes.filter { it in DirBruteInputs.STATUS_OPTIONS }.joinToString(","),
                        DirBruteInputs.BASELINE to options.baseline.toString(),
                    )
                    val entries = LinkedHashMap<String, Entry>()
                    for (spec in DirBruteInputs.specs) raw[spec.name]?.let { entries[spec.name] = Entry(InputKinds.of(spec), it) }
                    write(ToolKeys.DIR_BRUTEFORCE, entries)
                }
            }
        } finally {
            storage.remove(LEGACY_DIR_BRUTE_KEY)
        }
        return true
    }

    private fun rememberable(spec: InputSpec): Boolean =
        spec !is InputSpec.Secret && spec.name != ScriptDescriptorParser.CONFIRM_ANY && spec.name !in DescriptorRules.RESERVED

    /** The input alone, judged by the same rules the form uses, so a stored value the descriptor no longer accepts is not put back. */
    private fun stillValid(descriptor: ToolDescriptor, spec: InputSpec, raw: String): Boolean =
        ToolInputs.validate(descriptor.copy(target = TargetKind.NONE, inputs = listOf(spec)), mapOf(spec.name to raw)) is InputsResult.Valid

    private fun write(key: String, entries: Map<String, Entry>) {
        if (entries.isEmpty()) { storage.remove(storageKey(key)); return }
        storage.put(storageKey(key), json.encodeToString(Stored.serializer(), Stored(inputs = entries)))
    }

    private fun read(key: String): Stored? {
        val text = storage.get(storageKey(key)) ?: return null
        return runCatching { json.decodeFromString<Stored>(text) }.getOrNull()
    }

    companion object {
        const val KEY_PREFIX = "tool.lastInputs."
        /** The key the removed brute-force options store used. */
        const val LEGACY_DIR_BRUTE_KEY = "tool.dirBrute.options"
        fun storageKey(toolKey: String) = KEY_PREFIX + toolKey
    }
}
