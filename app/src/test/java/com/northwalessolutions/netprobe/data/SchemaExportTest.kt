package com.northwalessolutions.netprobe.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Reads the exported Room schemas; Android unit tests run with the module directory (app/) as the working directory. */
class SchemaExportTest {
    private val dir = File("schemas/com.northwalessolutions.netprobe.data.NetProbeDb")
    private fun root(version: Int): JsonObject = Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject
    /** table -> column -> field object. */
    private fun columns(version: Int): Map<String, Map<String, JsonObject>> =
        root(version)["database"]!!.jsonObject["entities"]!!.jsonArray.associate { e ->
            val o = e.jsonObject
            o["tableName"]!!.jsonPrimitive.content to o["fields"]!!.jsonArray.associate { f -> f.jsonObject["columnName"]!!.jsonPrimitive.content to f.jsonObject }
        }
    private val added = mapOf(
        "hosts" to mapOf("userType" to "TEXT", "userVendor" to "TEXT", "userModel" to "TEXT", "identSoftware" to "TEXT"),
        "networks" to mapOf("lastRunAt" to "INTEGER", "forgotten" to "INTEGER"),
        "ports" to mapOf("title" to "TEXT", "server" to "TEXT", "realm" to "TEXT", "poweredBy" to "TEXT", "certCn" to "TEXT", "certOrg" to "TEXT"),
    )

    @Test fun `version 1 schema is untouched and version 2 is exported`() {
        assertEquals("01cb3d4a14204fafc8d16708cf6b8f6a", root(1)["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content)
        assertEquals(2, root(2)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }
    @Test fun `version 2 keeps every version 1 column unchanged`() {
        val v1 = columns(1); val v2 = columns(2)
        assertEquals(v1.keys, v2.keys)
        for ((table, cols) in v1) for ((name, field) in cols) {
            val now = v2.getValue(table)[name]
            assertNotNull("$table.$name was dropped", now)
            assertEquals("$table.$name affinity", field["affinity"], now!!["affinity"])
            assertEquals("$table.$name notNull", field["notNull"], now["notNull"])
        }
    }
    @Test fun `version 2 adds exactly the twelve nullable columns`() {
        val v1 = columns(1); val v2 = columns(2)
        for ((table, expected) in added) {
            assertEquals("new columns of $table", expected.keys, v2.getValue(table).keys - v1.getValue(table).keys)
            for ((name, affinity) in expected) {
                val f = v2.getValue(table).getValue(name)
                assertEquals("$table.$name affinity", affinity, f["affinity"]!!.jsonPrimitive.content)
                // Room 2.7 omits "notNull" for nullable columns; an explicit true would mean NOT NULL.
                assertNotEquals("$table.$name must be nullable", true, f["notNull"]?.jsonPrimitive?.boolean)
                assertNull("$table.$name must have no default", f["defaultValue"])
            }
        }
        assertEquals(v1.getValue("vuln_cache").keys, v2.getValue("vuln_cache").keys)
    }
    @Test fun `version 3 is exported`() {
        assertEquals(3, root(3)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }
    @Test fun `version 3 keeps every version 2 table and column unchanged`() {
        val v2 = columns(2); val v3 = columns(3)
        for ((table, cols) in v2) {
            val now = v3[table]
            assertNotNull("table $table was dropped", now)
            for ((name, field) in cols) {
                val col = now!![name]
                assertNotNull("$table.$name was dropped", col)
                assertEquals("$table.$name affinity", field["affinity"], col!!["affinity"])
                assertEquals("$table.$name notNull", field["notNull"], col["notNull"])
            }
        }
    }
    @Test fun `version 3 adds exactly tool_runs and tool_findings`() {
        val v2 = columns(2); val v3 = columns(3)
        assertEquals(setOf("tool_runs", "tool_findings"), v3.keys - v2.keys)
        assertEquals(setOf("id", "hostId", "toolId", "port", "startedAt", "finishedAt", "status", "optionsJson", "summary"), v3.getValue("tool_runs").keys)
        assertEquals(setOf("id", "runId", "kind", "value", "detail", "extra"), v3.getValue("tool_findings").keys)
        assertEquals("INTEGER", v3.getValue("tool_runs").getValue("port")["affinity"]!!.jsonPrimitive.content)
        assertTrue("port is NOT NULL", v3.getValue("tool_runs").getValue("port")["notNull"]!!.jsonPrimitive.boolean)
    }
}
