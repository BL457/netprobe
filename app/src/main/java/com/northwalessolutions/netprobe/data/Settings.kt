package com.northwalessolutions.netprobe.data

import android.content.Context
import com.northwalessolutions.netprobe.engine.portscan.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsState(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22,
    val scanConcurrency: Int = 128, val scanTimeoutMs: Int = 600, val nvdApiKey: String = "",
)
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("netprobe", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    private fun load() = SettingsState(
        mdns = prefs.getBoolean("mdns", true), ssdp = prefs.getBoolean("ssdp", true), netbios = prefs.getBoolean("netbios", true), reverseDns = prefs.getBoolean("rdns", true),
        snmp = prefs.getBoolean("snmp", true), httpTitle = prefs.getBoolean("http", true), sweepConcurrency = prefs.getInt("sweepConc", 128).coerceIn(32, 512),
        connectTimeoutMs = prefs.getInt("connTimeout", 600).coerceIn(200, 2000), maxPrefix = prefs.getInt("maxPrefix", 22).coerceIn(20, 24),
        scanConcurrency = prefs.getInt("scanConc", 128).coerceIn(32, 512), scanTimeoutMs = prefs.getInt("scanTimeout", 600).coerceIn(200, 2000), nvdApiKey = prefs.getString("nvdKey", "") ?: "")
    /** Port-scan options for the detail screen and "Quick scan all". */
    fun scanOptions() = ScanOptions(concurrency = state.value.scanConcurrency, connectTimeoutMs = state.value.scanTimeoutMs)
    fun update(f: (SettingsState) -> SettingsState) {
        val s = f(_state.value)
        prefs.edit().putBoolean("mdns", s.mdns).putBoolean("ssdp", s.ssdp).putBoolean("netbios", s.netbios).putBoolean("rdns", s.reverseDns).putBoolean("snmp", s.snmp).putBoolean("http", s.httpTitle)
            .putInt("sweepConc", s.sweepConcurrency).putInt("connTimeout", s.connectTimeoutMs).putInt("maxPrefix", s.maxPrefix).putInt("scanConc", s.scanConcurrency).putInt("scanTimeout", s.scanTimeoutMs)
            .putString("nvdKey", s.nvdApiKey).apply()
        _state.value = load()
    }
}
