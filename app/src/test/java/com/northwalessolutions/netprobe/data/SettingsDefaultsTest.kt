package com.northwalessolutions.netprobe.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 1. */
class SettingsDefaultsTest {
    private val d = SettingsDefaults.DEFAULTS
    private val tuned = SettingsState(mdns = false, ssdp = false, netbios = false, reverseDns = false, snmp = false, httpTitle = false,
        sweepConcurrency = 256, connectTimeoutMs = 900, maxPrefix = 20, scanConcurrency = 64, scanTimeoutMs = 1200, nvdApiKey = "key-1",
        extEnabled = true, extMax = 9, extGatewayProbe = true, listenMulticast = true)

    /** Each stored key and what the state looks like when only that key is missing from the tuned prefs. */
    private val oneDefault: Map<String, (SettingsState) -> SettingsState> = mapOf(
        "mdns" to { s -> s.copy(mdns = d.mdns) }, "ssdp" to { s -> s.copy(ssdp = d.ssdp) }, "netbios" to { s -> s.copy(netbios = d.netbios) },
        "rdns" to { s -> s.copy(reverseDns = d.reverseDns) }, "snmp" to { s -> s.copy(snmp = d.snmp) }, "http" to { s -> s.copy(httpTitle = d.httpTitle) },
        "sweepConc" to { s -> s.copy(sweepConcurrency = d.sweepConcurrency) }, "connTimeout" to { s -> s.copy(connectTimeoutMs = d.connectTimeoutMs) },
        "maxPrefix" to { s -> s.copy(maxPrefix = d.maxPrefix) }, "scanConc" to { s -> s.copy(scanConcurrency = d.scanConcurrency) },
        "scanTimeout" to { s -> s.copy(scanTimeoutMs = d.scanTimeoutMs) }, "nvdKey" to { s -> s.copy(nvdApiKey = d.nvdApiKey) },
        "extEnabled" to { s -> s.copy(extEnabled = d.extEnabled) }, "extMax" to { s -> s.copy(extMax = d.extMax) },
        "extGatewayProbe" to { s -> s.copy(extGatewayProbe = d.extGatewayProbe) },
        "listenMulticast" to { s -> s.copy(listenMulticast = d.listenMulticast) })

    @Test fun `reset returns SettingsState() with the current NVD key`() {
        assertEquals(SettingsState(), d)
        assertEquals(SettingsState(nvdApiKey = "key-1"), SettingsDefaults.reset(tuned))
    }
    @Test fun `differs is false at defaults and true after any one covered value changes`() {
        assertFalse(SettingsDefaults.differs(SettingsState()))
        assertFalse("the NVD key is not a covered value", SettingsDefaults.differs(SettingsState(nvdApiKey = "k")))
        val oneEach = listOf(d.copy(mdns = false), d.copy(ssdp = false), d.copy(netbios = false), d.copy(reverseDns = false), d.copy(snmp = false),
            d.copy(httpTitle = false), d.copy(sweepConcurrency = 160), d.copy(connectTimeoutMs = 700), d.copy(maxPrefix = 24), d.copy(scanConcurrency = 96),
            d.copy(scanTimeoutMs = 500), d.copy(extEnabled = true), d.copy(extMax = 5), d.copy(extGatewayProbe = true), d.copy(listenMulticast = true))
        assertEquals(15, oneEach.size)   // the 15 covered values once plan 3C-2 adds listenMulticast (spec D3)
        oneEach.forEach { assertTrue("$it", SettingsDefaults.differs(it)) }
    }
    @Test fun `fresh prefs load exactly SettingsState()`() {
        assertEquals(SettingsState(), AppSettings(FakePrefs()).state.value)
    }
    @Test fun `every key that is missing loads its SettingsState default`() {
        val prefs = FakePrefs(); AppSettings(prefs).update { tuned }
        assertEquals(oneDefault.keys, prefs.stored.keys)   // 15 covered values plus nvdKey, and nothing else
        for ((key, expected) in oneDefault) {
            val without = FakePrefs().also { p -> p.stored.putAll(prefs.stored); p.stored.remove(key) }
            assertEquals("missing $key", expected(tuned), AppSettings(without).state.value)
        }
    }
    @Test fun `a reset is one prefs edit and keeps the key`() {
        val prefs = FakePrefs(); val settings = AppSettings(prefs); settings.update { tuned }
        val before = prefs.edits
        settings.update { SettingsDefaults.reset(it) }
        assertEquals(1, prefs.edits - before)
        assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)
    }
    @Test fun `undo restores the snapshot and keeps a key typed after the reset`() = runBlocking {
        val settings = AppSettings(FakePrefs()); settings.update { tuned }
        SettingsReset.run({ settings.state.value }, settings::update) {
            assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)   // the reset is applied before Undo is offered
            settings.update { it.copy(nvdApiKey = "key-2") }                          // typed while the snackbar shows
            true
        }
        assertEquals(tuned.copy(nvdApiKey = "key-2"), settings.state.value)
    }
    @Test fun `no undo leaves the defaults`() = runBlocking {
        val settings = AppSettings(FakePrefs()); settings.update { tuned }
        SettingsReset.run({ settings.state.value }, settings::update) { false }
        assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)
    }
    @Test fun `undo after a reset with only one value changed restores exactly that state`() = runBlocking {
        val one = SettingsState(sweepConcurrency = 256, nvdApiKey = "k")
        val settings = AppSettings(FakePrefs()); settings.update { one }
        SettingsReset.run({ settings.state.value }, settings::update) { true }
        assertEquals(one, settings.state.value)
    }
    @Test fun `reset leaves the manual ranges untouched and a second reset changes nothing`() = runBlocking {
        val settings = AppSettings(FakePrefs()); settings.update { tuned }
        val ranges = listOfNotNull(com.northwalessolutions.netprobe.netenv.CidrParser.subnetOf("10.9.0.0/24"))
        assertEquals(1, ranges.size)
        settings.manual.set(7L, ranges)
        SettingsReset.run({ settings.state.value }, settings::update) { false }
        val afterOne = settings.state.value
        SettingsReset.run({ settings.state.value }, settings::update) { false }
        assertEquals(afterOne, settings.state.value)
        assertEquals(ranges, settings.manual.get(7L))
    }
    @Test fun `listening is off by default and a reset turns it off`() {
        assertFalse(SettingsState().listenMulticast)
        assertFalse(SettingsDefaults.reset(SettingsState(listenMulticast = true)).listenMulticast)
        val prefs = FakePrefs(); AppSettings(prefs).update { it.copy(listenMulticast = true) }
        assertEquals(true, prefs.stored["listenMulticast"])
        assertTrue(AppSettings(prefs).state.value.listenMulticast)
    }
}
