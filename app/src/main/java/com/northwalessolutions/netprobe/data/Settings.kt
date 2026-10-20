package com.northwalessolutions.netprobe.data

import android.content.Context
import com.northwalessolutions.netprobe.engine.portscan.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22,
    val scanConcurrency: Int = 128, val scanTimeoutMs: Int = 600, val nvdApiKey: String = "",
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
)
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("netprobe", Context.MODE_PRIVATE)
    /** The current network's manual ranges, keyed per network row so a list typed at one site is never swept at another. */
    val manual = ManualRangeStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    private fun load() = SettingsState(
        mdns = prefs.getBoolean("mdns", true), ssdp = prefs.getBoolean("ssdp", true), netbios = prefs.getBoolean("netbios", true), reverseDns = prefs.getBoolean("rdns", true),
        snmp = prefs.getBoolean("snmp", true), httpTitle = prefs.getBoolean("http", true), sweepConcurrency = prefs.getInt("sweepConc", 128).coerceIn(32, 512),
        connectTimeoutMs = prefs.getInt("connTimeout", 600).coerceIn(200, 2000), maxPrefix = prefs.getInt("maxPrefix", 22).coerceIn(20, 24),
        scanConcurrency = prefs.getInt("scanConc", 128).coerceIn(32, 512), scanTimeoutMs = prefs.getInt("scanTimeout", 600).coerceIn(200, 2000), nvdApiKey = prefs.getString("nvdKey", "") ?: "",
        extEnabled = prefs.getBoolean("extEnabled", false),
        extMax = prefs.getInt("extMax", EXT_MAX_DEFAULT).coerceIn(EXT_MAX_MIN, EXT_MAX_MAX),
        extGatewayProbe = prefs.getBoolean("extGatewayProbe", false))
    /** Port-scan options for the detail screen and "Quick scan all". */
    fun scanOptions() = ScanOptions(concurrency = state.value.scanConcurrency, connectTimeoutMs = state.value.scanTimeoutMs)
    fun update(f: (SettingsState) -> SettingsState) {
        val s = f(_state.value)
        prefs.edit().putBoolean("mdns", s.mdns).putBoolean("ssdp", s.ssdp).putBoolean("netbios", s.netbios).putBoolean("rdns", s.reverseDns).putBoolean("snmp", s.snmp).putBoolean("http", s.httpTitle)
            .putInt("sweepConc", s.sweepConcurrency).putInt("connTimeout", s.connectTimeoutMs).putInt("maxPrefix", s.maxPrefix).putInt("scanConc", s.scanConcurrency).putInt("scanTimeout", s.scanTimeoutMs)
            .putString("nvdKey", s.nvdApiKey)
            .putBoolean("extEnabled", s.extEnabled).putInt("extMax", s.extMax.coerceIn(EXT_MAX_MIN, EXT_MAX_MAX)).putBoolean("extGatewayProbe", s.extGatewayProbe).apply()
        _state.value = load()
    }

    companion object {
        const val EXT_MAX_MIN = 1
        const val EXT_MAX_MAX = 16
        const val EXT_MAX_DEFAULT = 4
    }
}

/** `extManual.<networkId>`, newline-joined, in SharedPreferences (extended spec 4.4 and 8.3). No schema change. */
class ManualRangeStore(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    fun get(networkId: Long): List<Subnet> = read(key(networkId))?.lines()?.mapNotNull { CidrParser.subnetOf(it.trim()) }.orEmpty()
    fun set(networkId: Long, ranges: List<Subnet>) {
        write(key(networkId), ranges.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"))
        _revision.update { it + 1 }
    }
    fun forNetwork(networkId: Long?): List<Subnet> = networkId?.let(::get).orEmpty()
    companion object { fun key(networkId: Long) = "extManual.$networkId" }
}
