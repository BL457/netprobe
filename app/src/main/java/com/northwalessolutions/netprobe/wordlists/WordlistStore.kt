package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@Serializable data class WordlistMeta(val id: String, val name: String, val entries: Int, val importedAt: Long?, val builtIn: Boolean)

sealed interface ImportResult {
    data class Imported(val meta: WordlistMeta, val skipped: Int) : ImportResult
    object TooLarge : ImportResult; object TooMany : ImportResult; object Empty : ImportResult; object Unreadable : ImportResult
}

/**
 * Wordlists as files (spec 5.6.3): built-ins read from their raw resources, imports normalised into [dir] as <uuid>.txt with
 * metadata in index.json. [lists] is built-ins first (table order), then imported newest first. withEntries streams and always closes.
 */
class WordlistStore(private val dir: File, private val builtIns: List<BuiltInList>) {
    private val index = File(dir, "index.json")
    /** Guards every read-modify-write of index.json and [_lists] (plan review G23); withEntries never takes it. */
    private val indexLock = Mutex()
    internal val dirForTest: File get() = dir
    private val builtInMeta: List<WordlistMeta> = builtIns.map { b -> WordlistMeta(b.id, b.name, countOf(b.open()), null, true) }
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<List<WordlistMeta>> = _lists

    private fun load(): List<WordlistMeta> {
        dir.mkdirs()
        val imported = runCatching { Json.decodeFromString<List<WordlistMeta>>(index.takeIf { it.exists() }?.readText() ?: "[]") }.getOrElse { emptyList() }
        // Drop index entries whose file is gone, and delete .txt files with no index entry.
        val present = imported.filter { File(dir, fileName(it.id)).exists() }
        val known = present.map { fileName(it.id) }.toSet() + "index.json"
        dir.listFiles()?.forEach { if ((it.name !in known && it.name.endsWith(".txt")) || it.name.endsWith(TMP)) it.delete() }   // orphans and half imports
        if (present.size != imported.size) writeIndex(present)
        return builtInMeta + present.sortedByDescending { it.importedAt ?: 0 }
    }
    private fun fileName(id: String) = id.removePrefix("user:") + ".txt"
    private fun writeIndex(imported: List<WordlistMeta>) { index.writeText(Json.encodeToString(imported)) }
    private fun countOf(stream: InputStream): Int = stream.use { WordlistParser.parse(it).entries.size }

    suspend fun import(displayName: String?, source: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val bytes = try { readCapped(source) ?: return@withContext ImportResult.TooLarge } catch (e: IOException) { return@withContext ImportResult.Unreadable }
        val parsed = runCatching { WordlistParser.parse(bytes.inputStream()) }.getOrNull() ?: return@withContext ImportResult.Unreadable
        if (parsed.entries.size > MAX_ENTRIES) return@withContext ImportResult.TooMany
        if (parsed.entries.isEmpty()) return@withContext ImportResult.Empty
        val baseName = cleanName(displayName)
        val id = "user:" + UUID.randomUUID().toString()
        val target = File(dir, fileName(id))
        val temp = File(dir, fileName(id) + TMP)
        try {
            dir.mkdirs()
            temp.writeText(parsed.entries.joinToString("\n"))
        } catch (e: IOException) { temp.delete(); return@withContext ImportResult.Unreadable }
        if (!temp.renameTo(target)) { temp.delete(); return@withContext ImportResult.Unreadable }
        indexLock.withLock {
            val meta = WordlistMeta(id, uniqueName(baseName), parsed.entries.size, System.currentTimeMillis(), false)
            val imported = currentImported() + meta
            writeIndex(imported); _lists.value = builtInMeta + imported.sortedByDescending { it.importedAt ?: 0 }
            ImportResult.Imported(meta, parsed.skipped)
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        if (id.startsWith("builtin:")) return@withContext
        indexLock.withLock {
            File(dir, fileName(id)).delete()                       // a run reading it keeps its open reader (spec 5.6.3)
            val imported = currentImported().filterNot { it.id == id }
            writeIndex(imported); _lists.value = builtInMeta + imported.sortedByDescending { it.importedAt ?: 0 }
        }
    }

    suspend fun <T> withEntries(id: String, block: suspend (Sequence<String>) -> T): T? = withContext(Dispatchers.IO) {
        val reader: BufferedReader = when {
            id.startsWith("builtin:") -> builtIns.firstOrNull { it.id == id }?.open()?.bufferedReader() ?: return@withContext null
            else -> File(dir, fileName(id)).takeIf { it.exists() }?.bufferedReader() ?: return@withContext null
        }
        try { block(reader.lineSequence().mapNotNull { normalise(it) }) } finally { reader.close() }
    }

    private fun normalise(line: String): String? {
        var t = line.trim(); if (t.isEmpty() || t.startsWith("#")) return null
        t = t.trimStart('/'); if (t.isEmpty() || t.length > WordlistParser.MAX_ENTRY_CHARS || t.any { it.isISOControl() }) return null
        return t
    }
    /** The display name without its extension, control characters removed and length capped; files are named by uuid so the name never touches the filesystem. */
    private fun cleanName(displayName: String?): String {
        val base = displayName?.substringBeforeLast('.')?.filterNot { it.isISOControl() }?.trim().orEmpty().take(MAX_NAME_CHARS).trim()
        return base.ifEmpty { WordlistCopy.DEFAULT_NAME }
    }
    private fun currentImported(): List<WordlistMeta> = _lists.value.filter { !it.builtIn }
    private fun uniqueName(base: String): String {
        val taken = _lists.value.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2; while ("$base ($n)" in taken) n++; return "$base ($n)"
    }
    /** Reads at most MAX_IMPORT_BYTES + 1 bytes from [source]; null when it holds more than the cap. */
    private fun readCapped(source: InputStream): ByteArray? = source.use {
        val out = ByteArrayOutputStream(); val buf = ByteArray(READ_CHUNK); var total = 0
        while (true) {
            val want = minOf(buf.size, MAX_IMPORT_BYTES + 1 - total)
            val n = it.read(buf, 0, want); if (n < 0) break
            total += n; if (total > MAX_IMPORT_BYTES) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    companion object {
        const val MAX_IMPORT_BYTES = 10_485_760
        const val MAX_ENTRIES = 200_000
        private const val TMP = ".tmp"
        private const val MAX_NAME_CHARS = 80
        private const val READ_CHUNK = 8192
    }
}
