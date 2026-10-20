package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class WordlistStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun builtIn(id: String, name: String, body: String) = BuiltInList(id, name) { body.toByteArray(Charsets.UTF_8).inputStream() }
    private fun store(vararg b: BuiltInList) = WordlistStore(tmp.newFolder("wl-${b.size}-${System.nanoTime()}"), b.toList())
    private val commons = builtIn("builtin:common-short", "common-short", "admin\nlogin\nbackup")

    @Test fun `built ins come first with a computed entry count and cannot be deleted`() = runTest {
        val s = store(commons)
        val meta = s.lists.first().first()
        assertEquals("builtin:common-short", meta.id); assertEquals(3, meta.entries); assertTrue(meta.builtIn)
        s.delete("builtin:common-short")
        assertTrue(s.lists.first().any { it.id == "builtin:common-short" })
    }
    @Test fun `import writes a normalised file and an index entry, newest first`() = runTest {
        val s = store(commons)
        val r1 = s.import("list one.txt", "a\n/b\n#c\na\n".byteInputStream()) as ImportResult.Imported
        assertEquals("list one", r1.meta.name); assertEquals(2, r1.meta.entries); assertFalse(r1.meta.builtIn)
        val lists = s.lists.first()
        assertEquals("builtin:common-short", lists.first().id)       // built-in still first
        assertEquals(r1.meta.id, lists[1].id)
        assertEquals(listOf("a", "b"), s.withEntries(r1.meta.id) { it.toList() })
    }
    @Test fun `a name clash gets a numbered suffix`() = runTest {
        val s = store(commons)
        s.import("dup", "x".byteInputStream()); val second = s.import("dup", "y".byteInputStream()) as ImportResult.Imported
        assertEquals("dup (2)", second.meta.name)
    }
    @Test fun `the byte limit aborts mid stream with no size hint`() = runTest {
        val s = store()
        val big = ByteArray(WordlistStore.MAX_IMPORT_BYTES + 10) { 'a'.code.toByte() }
        assertEquals(ImportResult.TooLarge, s.import("big", big.inputStream()))
    }
    @Test fun `the byte limit never reads more than the cap plus one byte`() = runTest {
        val s = store()
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); served += len; return len }
        }
        assertEquals(ImportResult.TooLarge, s.import("endless", endless))
        assertTrue("read $served", served <= WordlistStore.MAX_IMPORT_BYTES + 1L)
    }
    @Test fun `too many entries and empty are rejected and keep nothing`() = runTest {
        val s = store()
        val many = (1..WordlistStore.MAX_ENTRIES + 1).joinToString("\n") { "p$it" }        // about 1.5 MB, under the byte cap
        assertEquals(ImportResult.TooMany, s.import("m", many.byteInputStream()))
        assertEquals(ImportResult.Empty, s.import("e", "\n\n# only comments".byteInputStream()))
        assertTrue(s.lists.first().isEmpty())
        assertTrue("nothing written", tmp.root.walk().none { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".tmp")) })
    }
    @Test fun `delete removes the file and the entry`() = runTest {
        val s = store(commons); val m = (s.import("gone", "a\nb".byteInputStream()) as ImportResult.Imported).meta
        s.delete(m.id)
        assertNull(s.withEntries(m.id) { it.toList() })
        assertTrue(s.lists.first().none { it.id == m.id })
    }
    /** Counts close() so a test can see the reader released (plan review G12). */
    private class Tracking(body: String) : InputStream() {
        private val inner = body.toByteArray(Charsets.UTF_8).inputStream()
        @Volatile var closed = false
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() { closed = true }
    }
    @Test fun `withEntries closes the reader when the block is cancelled`() = runBlocking {
        var last: Tracking? = null
        val s = store(BuiltInList("builtin:t", "t") { Tracking((1..1000).joinToString("\n") { "p$it" }).also { last = it } })
        val entered = CompletableDeferred<String>()
        val job = launch(Dispatchers.Default) { s.withEntries("builtin:t") { seq -> entered.complete(seq.first()); awaitCancellation() } }
        assertEquals("p1", entered.await())
        assertFalse("open while the block runs", last!!.closed)
        job.cancelAndJoin()
        assertTrue("the reader must close when the block is cancelled", last!!.closed)
    }
    @Test fun `deleting a list during withEntries lets the block finish`() = runTest {
        val s = store(); val m = (s.import("live", "a\nb\nc".byteInputStream()) as ImportResult.Imported).meta
        val got = s.withEntries(m.id) { seq ->
            val it = seq.iterator(); val first = it.next()
            s.delete(m.id)                                           // spec 12.1: the run that is reading keeps reading
            listOf(first) + it.asSequence().toList()
        }
        assertEquals(listOf("a", "b", "c"), got)
        assertTrue(s.lists.first().none { it.id == m.id })
    }
    @Test fun `concurrent imports keep every index entry`() = runTest {
        val s = store()
        (1..8).map { n -> async(Dispatchers.Default) { s.import("l$n", "x$n".byteInputStream()) } }.awaitAll()
        assertEquals(8, s.lists.first().size)
        assertEquals(8, WordlistStore(s.dirForTest, emptyList()).lists.first().size)        // the index on disk has all eight
    }
    @Test fun `with a valid index orphan files are removed`() = runTest {
        val dir = tmp.newFolder("orphans")
        File(dir, "index.json").writeText("[]"); File(dir, "orphan.txt").writeText("x")
        val s = WordlistStore(dir, listOf(commons))
        assertEquals(listOf("builtin:common-short"), s.lists.first().map { it.id })
        assertFalse(File(dir, "orphan.txt").exists())
    }
    @Test fun `writing the index leaves no temp file behind`() = runTest {
        val s = store(); s.import("a", "x".byteInputStream())
        assertTrue(s.dirForTest.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(File(s.dirForTest, "index.json").exists())
    }
    @Test fun `a failure before the index move leaves the old index intact and removes the import file`() = runTest {
        val s = store(); s.import("keep", "x".byteInputStream())
        val before = File(s.dirForTest, "index.json").readText()
        File(s.dirForTest, "index.json.tmp").mkdir()                 // the temp write now fails with an IOException
        assertEquals(ImportResult.Unreadable, s.import("fail", "y".byteInputStream()))
        assertEquals(before, File(s.dirForTest, "index.json").readText())
        assertEquals(1, s.lists.first().size)
        assertEquals(1, s.dirForTest.listFiles()!!.count { it.name.endsWith(".txt") })
    }
    @Test fun `an id that is not a user uuid never resolves to a path`() = runTest {
        val s = store()
        File(s.dirForTest.parentFile, "x.txt").writeText("secret")
        assertNull(s.withEntries("user:../x") { it.toList() })
        s.delete("user:../x")
        assertTrue(File(s.dirForTest.parentFile, "x.txt").exists())
    }
    @Test fun `a truncated index loads empty and keeps the user files`() = runTest {
        val dir = tmp.newFolder("corrupt")
        File(dir, "index.json").writeText("{ not json"); File(dir, "orphan.txt").writeText("x"); File(dir, "half.txt.tmp").writeText("x")
        val s = WordlistStore(dir, listOf(commons))
        assertEquals(listOf("builtin:common-short"), s.lists.first().map { it.id })
        assertTrue("user files survive a corrupt index", File(dir, "orphan.txt").exists())
        assertEquals("{ not json", File(dir, "index.json").readText())
        assertFalse("an interrupted import's temp file is removed (plan review G23)", File(dir, "half.txt.tmp").exists())
    }
}
