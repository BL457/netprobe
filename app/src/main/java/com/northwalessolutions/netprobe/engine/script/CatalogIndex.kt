package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.ui.tools.CatalogCopy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One line of `catalog/index.json` (spec section 11), already checked: [network] is a scope, [sha256] is 64 lower-case hex digits, [file] is `<id>.js`. */
data class CatalogEntry(
    val id: String, val file: String, val name: String, val version: String, val sha256: String,
    val network: NetworkScope, val description: String, val broadcast: Boolean,
) {
    /** The tool key an installed copy has (spec D5). */
    val key: String get() = "user:$id"
}

/** What reading an index gives: the usable entries, and one reason for each line that was left out. */
sealed interface CatalogIndexResult {
    data class Parsed(val entries: List<CatalogEntry>, val rejected: List<String>) : CatalogIndexResult
    data object Unreadable : CatalogIndexResult
}

/**
 * The format of `catalog/index.json`: `{"format": 1, "tools": [ {id, file, name, version, sha256, network, description, broadcast}, ... ]}`.
 * Pure parsing and checking, no network. A line that does not fit is rejected with a reason and does not stop the others.
 */
object CatalogIndex {
    const val FORMAT = 1
    /** The most lines read from one index: a hostile or broken index cannot make the app fetch hundreds of scripts. */
    const val MAX_ENTRIES = 200
    private const val MAX_NAME = 40
    private const val MAX_DESCRIPTION = 200
    private const val SHA_HEX_DIGITS = 64
    private val ID = Regex("[a-z0-9-]{3,40}")
    private val VERSION = Regex("[0-9A-Za-z._-]{1,20}")
    private val SHA = Regex("[0-9a-fA-F]{$SHA_HEX_DIGITS}")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Line(
        val id: String, val file: String, val name: String, val version: String = "1", val sha256: String,
        val network: String = "target", val description: String = "", val broadcast: Boolean = false,
    )

    fun parse(text: String): CatalogIndexResult {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null } ?: return CatalogIndexResult.Unreadable
        if ((root["format"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != FORMAT) return CatalogIndexResult.Unreadable
        val tools = root["tools"] as? JsonArray ?: return CatalogIndexResult.Unreadable
        val entries = ArrayList<CatalogEntry>()
        val rejected = ArrayList<String>()
        tools.take(MAX_ENTRIES).forEachIndexed { position, element ->
            when (val checked = check(element)) {
                is Checked.Ok -> if (entries.any { it.id == checked.entry.id }) rejected += CatalogCopy.indexDuplicate(checked.entry.id) else entries += checked.entry
                is Checked.Bad -> rejected += CatalogCopy.indexLineBad(checked.label ?: "#${position + 1}", checked.reason)
            }
        }
        if (tools.size > MAX_ENTRIES) rejected += CatalogCopy.INDEX_TOO_LONG
        return CatalogIndexResult.Parsed(entries, rejected)
    }

    private sealed interface Checked {
        class Ok(val entry: CatalogEntry) : Checked
        class Bad(val label: String?, val reason: String) : Checked
    }

    private fun check(element: JsonElement): Checked {
        val line = try { json.decodeFromJsonElement(Line.serializer(), element) } catch (_: SerializationException) {
            return Checked.Bad((element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.take(MAX_NAME), CatalogCopy.INDEX_FIELDS)
        } catch (_: IllegalArgumentException) {
            return Checked.Bad(null, CatalogCopy.INDEX_FIELDS)
        }
        val label = line.id.take(MAX_NAME)
        val scope = ScriptToolStore.scopeOf(line.network)
        val reason = when {
            !ID.matches(line.id) -> CatalogCopy.INDEX_ID
            line.file != "${line.id}.js" -> CatalogCopy.INDEX_FILE
            line.name.isBlank() || line.name.length > MAX_NAME -> CatalogCopy.INDEX_NAME
            !VERSION.matches(line.version) -> CatalogCopy.INDEX_VERSION
            !SHA.matches(line.sha256) -> CatalogCopy.INDEX_SHA
            scope == null -> CatalogCopy.INDEX_NETWORK
            line.description.length > MAX_DESCRIPTION -> CatalogCopy.INDEX_DESCRIPTION
            line.broadcast && scope != NetworkScope.LAN -> CatalogCopy.INDEX_BROADCAST
            else -> null
        }
        if (reason != null) return Checked.Bad(label, reason)
        return Checked.Ok(CatalogEntry(line.id, line.file, line.name, line.version, line.sha256.lowercase(), scope!!, line.description, line.broadcast))
    }
}
