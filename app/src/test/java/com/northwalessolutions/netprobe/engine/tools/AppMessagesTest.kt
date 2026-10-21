package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 4.3 and final review S8: a short FIFO queue; dismiss clears only the head it names and brings up the next. */
class AppMessagesTest {
    private val m = AppMessages()
    @Test fun `a second post queues behind the first instead of replacing it and ids are unique`() {
        m.post("a", 1); val first = m.current.value!!
        m.post("b", 2)
        assertEquals("the unread message stays on screen", "a", m.current.value?.text)
        m.dismiss(first.id)
        val second = m.current.value!!
        assertEquals("b", second.text); assertEquals(2L, second.hostId); assertNotEquals(first.id, second.id)
        m.dismiss(second.id); assertNull(m.current.value)
    }
    @Test fun `messages drain in order, one at a time`() {
        listOf("a", "b", "c").forEach { m.post(it) }
        val seen = mutableListOf<String>()
        while (m.current.value != null) { seen += m.current.value!!.text; m.dismiss(m.current.value!!.id) }
        assertEquals(listOf("a", "b", "c"), seen)
    }
    @Test fun `past the capacity the oldest waiting message is dropped and the head is kept`() {
        listOf("a", "b", "c", "d").forEach { m.post(it) }
        val seen = mutableListOf<String>()
        while (m.current.value != null) { seen += m.current.value!!.text; m.dismiss(m.current.value!!.id) }
        assertEquals(listOf("a", "c", "d"), seen)
    }
    @Test fun `a stale dismiss of a queued message does not remove it`() {
        m.post("a"); m.post("b")
        val queuedId = m.current.value!!.id + 1
        m.dismiss(queuedId)
        assertEquals("a", m.current.value?.text)
        m.dismiss(m.current.value!!.id); assertEquals("b", m.current.value?.text)
    }
    @Test fun `dismiss clears only the message it names`() {
        m.post("a"); val id = m.current.value!!.id
        m.dismiss(id + 1); assertNotNull(m.current.value)   // a stale dismiss is ignored
        m.dismiss(id); assertNull(m.current.value)
    }
}
