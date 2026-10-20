package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRangeStoreTest {
    private val prefs = HashMap<String, String>()
    private val store = ManualRangeStore({ prefs[it] }, { k, v -> if (v == null) prefs.remove(k) else prefs[k] = v })
    private fun net(s: String) = CidrParser.subnetOf(s)!!

    @Test fun `lists for two networks stay separate`() {
        store.set(1, listOf(net("10.0.4.0/24"))); store.set(2, listOf(net("172.16.9.0/24")))
        assertEquals(listOf(net("10.0.4.0/24")), store.get(1)); assertEquals(listOf(net("172.16.9.0/24")), store.get(2))
        assertEquals("10.0.4.0/24", prefs["extManual.1"])
    }
    @Test fun `no current network gives an empty list`() { store.set(1, listOf(net("10.0.4.0/24"))); assertEquals(emptyList<Any>(), store.forNetwork(null)) }
    @Test fun `invalid strings on load are dropped and order is kept`() {
        prefs["extManual.3"] = "10.0.9.0/24\nnot a range\n8.8.8.0/24\n\n10.0.4.0/24"
        assertEquals(listOf(net("10.0.9.0/24"), net("10.0.4.0/24")), store.get(3))
    }
    @Test fun `an empty list removes the key and every write bumps the revision`() {
        val before = store.revision.value
        store.set(4, listOf(net("10.0.4.0/24"))); store.set(4, emptyList())
        assertEquals(null, prefs["extManual.4"]); assertEquals(before + 2, store.revision.value)
    }
}
