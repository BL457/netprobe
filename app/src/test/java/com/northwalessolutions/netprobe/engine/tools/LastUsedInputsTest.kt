package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteInputs
import com.northwalessolutions.netprobe.ui.tools.ToolFormRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Last-used inputs: what is kept, what is never kept, what is dropped on the way back (G10, G14), and the one-time dir-brute migration. */
class LastUsedInputsTest {
    private class MemoryStorage : LastUsedStorage {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val storage = MemoryStorage()
    private val used = LastUsedInputs(storage)

    private fun tool(key: String = "user:my-tool", vararg inputs: InputSpec, target: TargetKind = TargetKind.NONE) =
        ToolDescriptor(key, "My tool", "", ToolIcon.TERMINAL, ToolCategory.MINE, target, inputs.toList())

    private val path = InputSpec.Text("path", "Path", default = "/")
    private val depth = InputSpec.Number("depth", "Depth", default = 2, min = 1, max = 5)
    private val mode = InputSpec.Choice("mode", "Mode", listOf(ChoiceOption("fast"), ChoiceOption("slow")), default = "fast")
    private val deep = InputSpec.Bool("deep", "Deep")
    private val kinds = InputSpec.MultiChoice("kinds", "Kinds", listOf(ChoiceOption("a"), ChoiceOption("b"), ChoiceOption("c")), default = listOf("a"))
    private val words = InputSpec.TextList("words", "Words", required = false)
    private val secret = InputSpec.Secret("token", "Token")

    @Test fun `values come back as they were saved`() {
        val d = tool("user:my-tool", path, depth, mode, deep, kinds, words)
        used.save(d, mapOf("path" to "/admin", "depth" to "4", "mode" to "slow", "deep" to "true", "kinds" to "b,c", "words" to "x,y"))
        assertEquals(mapOf("path" to "/admin", "depth" to "4", "mode" to "slow", "deep" to "true", "kinds" to "b,c", "words" to "x,y"), used.restore(d))
    }

    @Test fun `an optional field emptied on purpose stays empty`() {
        val d = tool("user:my-tool", words)
        used.save(d, mapOf("words" to ""))
        assertEquals(mapOf("words" to ""), used.restore(d))
    }

    @Test fun `secret inputs are never stored`() {
        val d = tool("user:my-tool", path, secret)
        used.save(d, mapOf("path" to "/x", "token" to "hunter2-secret-value"))
        assertFalse(storage.map.values.any { it.contains("hunter2") || it.contains("token") })
        assertEquals(mapOf("path" to "/x"), used.restore(d))
        val onlySecret = tool("user:only-secret", secret)
        used.save(onlySecret, mapOf("token" to "hunter2"))
        assertNull(storage.map[LastUsedInputs.storageKey("user:only-secret")])
    }

    @Test fun `the confirmation flag and the implicit target inputs are never stored`() {
        val d = tool("user:my-tool", path, target = TargetKind.PORT)
        used.save(d, mapOf("path" to "/x", "confirmAny" to "true", "ip" to "192.168.1.5", "port" to "80", "tls" to "true"))
        val json = storage.map.getValue(LastUsedInputs.storageKey("user:my-tool"))
        for (word in listOf("confirmAny", "192.168.1.5", "\"ip\"", "\"port\"", "\"tls\"")) assertFalse(word, json.contains(word))
        // even an input that is named like the flag is left out
        val sneaky = tool("user:sneaky", InputSpec.Bool("confirmAny", "Confirm"), path)
        used.save(sneaky, mapOf("confirmAny" to "true", "path" to "/y"))
        assertFalse(storage.map.getValue(LastUsedInputs.storageKey("user:sneaky")).contains("confirmAny"))
        assertEquals(mapOf("path" to "/y"), used.restore(sneaky))
    }

    @Test fun `a value whose input is gone is dropped`() {
        used.save(tool("user:my-tool", path, depth), mapOf("path" to "/a", "depth" to "3"))
        assertEquals(mapOf("depth" to "3"), used.restore(tool("user:my-tool", depth)))
        assertTrue(used.restore(tool("user:my-tool", mode)).isEmpty())
    }

    @Test fun `a value whose input changed type is dropped`() {
        used.save(tool("user:my-tool", path, depth), mapOf("path" to "/a", "depth" to "3"))
        val changed = tool("user:my-tool", InputSpec.Text("depth", "Depth"), InputSpec.Choice("path", "Path", listOf(ChoiceOption("/a")), default = "/a"))
        assertTrue(used.restore(changed).isEmpty())
    }

    @Test fun `a value the input no longer accepts is dropped`() {
        used.save(tool("user:my-tool", depth, mode, kinds), mapOf("depth" to "4", "mode" to "slow", "kinds" to "b,c"))
        val narrower = tool("user:my-tool", InputSpec.Number("depth", "Depth", min = 1, max = 3), InputSpec.Choice("mode", "Mode", listOf(ChoiceOption("fast")), default = "fast"),
            InputSpec.MultiChoice("kinds", "Kinds", listOf(ChoiceOption("a"), ChoiceOption("b")), default = listOf("a")))
        assertTrue(used.restore(narrower).isEmpty())
    }

    @Test fun `a required field that was blanked falls back to its default`() {
        used.save(tool("user:my-tool", path), mapOf("path" to ""))
        assertTrue(used.restore(tool("user:my-tool", path)).isEmpty())
    }

    @Test fun `a deleted wordlist is dropped only when the lists are known`() {
        val d = tool("user:my-tool", InputSpec.Wordlist("list", "List", default = "builtin:common-short"))
        used.save(d, mapOf("list" to "import:mine"))
        assertEquals(mapOf("list" to "import:mine"), used.restore(d, knownWordlists = null))
        assertEquals(mapOf("list" to "import:mine"), used.restore(d, knownWordlists = setOf("import:mine", "builtin:common-short")))
        assertTrue(used.restore(d, knownWordlists = setOf("builtin:common-short")).isEmpty())
    }

    @Test fun `each tool has its own entry and forget removes it`() {
        val a = tool("user:a-tool", path); val b = tool("user:b-tool", path)
        used.save(a, mapOf("path" to "/a")); used.save(b, mapOf("path" to "/b"))
        assertEquals(mapOf("path" to "/a"), used.restore(a)); assertEquals(mapOf("path" to "/b"), used.restore(b))
        assertEquals(listOf("tool.lastInputs.user:a-tool", "tool.lastInputs.user:b-tool"), storage.map.keys.toList())
        used.forget("user:a-tool")
        assertTrue(used.restore(a).isEmpty()); assertEquals(mapOf("path" to "/b"), used.restore(b))
    }

    @Test fun `unreadable stored text restores nothing and a later save replaces it`() {
        val d = tool("user:my-tool", path)
        storage.put(LastUsedInputs.storageKey("user:my-tool"), "{ not json")
        assertTrue(used.restore(d).isEmpty())
        used.save(d, mapOf("path" to "/ok"))
        assertEquals(mapOf("path" to "/ok"), used.restore(d))
    }

    @Test fun `the form starts from the remembered values and the defaults for the rest`() {
        val d = tool("user:my-tool", path, depth)
        val form = ToolFormRules.initial(d, remembered = mapOf("depth" to "4", "gone" to "x"))
        assertEquals(mapOf("path" to "/", "depth" to "4"), form.values)
        assertEquals(mapOf("path" to "/", "depth" to "2"), ToolFormRules.initial(d).values)
    }

    // migration of the old brute-force options

    private val dirBrute = ToolDescriptor(ToolKeys.DIR_BRUTEFORCE, "Directory brute force", "", ToolIcon.FOLDER, ToolCategory.WEB, TargetKind.PORT, DirBruteInputs.specs)

    @Test fun `the old brute-force options move into the dir-brute tool once and the old key is deleted`() {
        val old = """{"wordlistId":"builtin:admin-panels","extensions":["php","bak"],"method":"GET_ONLY","concurrency":12,"timeoutS":5,"statusCodes":[200,403],"baseline":false}"""
        storage.put(LastUsedInputs.LEGACY_DIR_BRUTE_KEY, old)
        storage.put("other.key", "keep")
        assertTrue(used.migrateDirBrute())
        assertNull(storage.map[LastUsedInputs.LEGACY_DIR_BRUTE_KEY])
        assertEquals("keep", storage.map["other.key"])
        assertEquals(mapOf(DirBruteInputs.WORDLIST to "builtin:admin-panels", DirBruteInputs.EXTENSIONS to "php,bak", DirBruteInputs.METHOD to "GET_ONLY",
            DirBruteInputs.CONCURRENCY to "12", DirBruteInputs.TIMEOUT_S to "5", DirBruteInputs.STATUS_CODES to "200,403", DirBruteInputs.BASELINE to "false"), used.restore(dirBrute))
        assertFalse(used.migrateDirBrute())
        storage.put(LastUsedInputs.storageKey(ToolKeys.DIR_BRUTEFORCE), "changed-since")
        assertFalse(used.migrateDirBrute())
        assertEquals("changed-since", storage.map[LastUsedInputs.storageKey(ToolKeys.DIR_BRUTEFORCE)])
    }

    @Test fun `migration keeps a newer remembered entry and still deletes the old key`() {
        used.save(dirBrute, mapOf(DirBruteInputs.CONCURRENCY to "3"))
        storage.put(LastUsedInputs.LEGACY_DIR_BRUTE_KEY, """{"concurrency":9}""")
        assertTrue(used.migrateDirBrute())
        assertEquals(mapOf(DirBruteInputs.CONCURRENCY to "3"), used.restore(dirBrute))
        assertNull(storage.map[LastUsedInputs.LEGACY_DIR_BRUTE_KEY])
    }

    @Test fun `an unreadable old value is deleted without creating anything`() {
        storage.put(LastUsedInputs.LEGACY_DIR_BRUTE_KEY, "{ not json")
        assertTrue(used.migrateDirBrute())
        assertTrue(storage.map.isEmpty())
    }

    @Test fun `old numbers are clamped and codes the form does not offer are left out`() {
        storage.put(LastUsedInputs.LEGACY_DIR_BRUTE_KEY, """{"concurrency":99,"timeoutS":0,"statusCodes":[200,418,404],"extensions":[]}""")
        used.migrateDirBrute()
        val back = used.restore(dirBrute)
        assertEquals("16", back[DirBruteInputs.CONCURRENCY]); assertEquals("1", back[DirBruteInputs.TIMEOUT_S])
        assertEquals("200", back[DirBruteInputs.STATUS_CODES])
        assertEquals("", back[DirBruteInputs.EXTENSIONS])
    }

    @Test fun `nothing happens when there is no old key`() {
        assertFalse(used.migrateDirBrute())
        assertTrue(storage.map.isEmpty())
    }
}
