package com.northwalessolutions.netprobe

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 30, Review Focus 4: at most one Undo; every scan start commits it, so a restore can never land mid-scan. */
class UndoStoreTest {
    private val log = mutableListOf<String>()
    private fun undoable(name: String) = PendingUndo("Removed $name", restore = { log += "restore $name" }, commit = { log += "commit $name" })

    @Test fun `post commits the previous undo first`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.post(undoable("B"))
        assertEquals(listOf("commit A"), log); assertEquals("Removed B", s.pending.value?.text)
    }
    @Test fun `undo runs restore once and clears`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.undo(); s.undo()
        assertEquals(listOf("restore A"), log); assertNull(s.pending.value)
    }
    @Test fun `commit is idempotent`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.commit(); s.commit()
        assertEquals(listOf("commit A"), log); assertNull(s.pending.value)
    }
    @Test fun `dismissing commits and a later undo restores nothing`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.commit(); s.undo()
        assertEquals(listOf("commit A"), log)
    }
    @Test fun `a scan body runs only after the pending undo is committed`() = runTest {   // Review Focus 4, plan review G8
        val s = UndoStore(); s.post(undoable("A"))
        val result = s.committedBefore { log += "scan starts, pending = ${s.pending.value?.text}"; 7 }
        assertEquals(listOf("commit A", "scan starts, pending = null"), log); assertEquals(7, result)
        s.committedBefore { log += "second scan" }
        assertEquals("nothing pending: nothing committed twice", 1, log.count { it.startsWith("commit") })
    }
}
