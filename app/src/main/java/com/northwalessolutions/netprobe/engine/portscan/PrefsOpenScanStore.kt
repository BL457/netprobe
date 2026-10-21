package com.northwalessolutions.netprobe.engine.portscan

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The record of port scans that were running (3D spec 3.3): written at start, removed at end, read once at the next launch. */
data class OpenScan(val hostId: Long, val networkId: Long, val startedAt: Long)

interface OpenScanStore {
    fun add(scan: OpenScan)
    fun remove(hostId: Long)
    /** Returns every record and clears the store. */
    fun drain(): List<OpenScan>
}

/** `scansOpenScans` in the `netprobe` preferences: a JSON list. It is data, so "Reset to defaults" never touches it. */
class PrefsOpenScanStore(private val prefs: SharedPreferences) : OpenScanStore {
    @Synchronized override fun add(scan: OpenScan) { write(read().filterNot { it.hostId == scan.hostId } + scan) }
    @Synchronized override fun remove(hostId: Long) { write(read().filterNot { it.hostId == hostId }) }
    @Synchronized override fun drain(): List<OpenScan> { val all = read(); write(emptyList()); return all }

    private fun read(): List<OpenScan> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val hostId = o["hostId"]?.jsonPrimitive?.longOrNull
                val networkId = o["networkId"]?.jsonPrimitive?.longOrNull
                val startedAt = o["startedAt"]?.jsonPrimitive?.longOrNull
                if (hostId == null || networkId == null || startedAt == null) null else OpenScan(hostId, networkId, startedAt)
            }
        } catch (_: SerializationException) {
            emptyList()      // a damaged record cannot be recovered: it only costs an Interrupted line, never a crash at launch
        } catch (_: IllegalArgumentException) {
            emptyList()      // valid JSON of the wrong shape: same
        }
    }
    private fun write(list: List<OpenScan>) {
        val editor = prefs.edit()
        if (list.isEmpty()) editor.remove(KEY)
        else editor.putString(KEY, buildJsonArray {
            list.forEach { s -> add(buildJsonObject { put("hostId", s.hostId); put("networkId", s.networkId); put("startedAt", s.startedAt) }) }
        }.toString())
        editor.apply()
    }

    companion object { const val KEY = "scansOpenScans" }
}
