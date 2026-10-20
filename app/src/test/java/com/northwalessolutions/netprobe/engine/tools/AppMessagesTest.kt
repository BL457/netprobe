package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 4.3: one unread message; post replaces; dismiss clears only if the id still matches. */
class AppMessagesTest {
    private val m = AppMessages()
    @Test fun `post replaces any unread message and ids are unique`() {
        m.post("a", 1); val first = m.current.value!!
        m.post("b", 2); val second = m.current.value!!
        assertEquals("b", second.text); assertEquals(2L, second.hostId); assertNotEquals(first.id, second.id)
    }
    @Test fun `dismiss clears only the message it names`() {
        m.post("a"); val id = m.current.value!!.id
        m.dismiss(id + 1); assertNotNull(m.current.value)   // a stale dismiss is ignored
        m.dismiss(id); assertNull(m.current.value)
    }
}
