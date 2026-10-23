package com.northwalessolutions.netprobe.data

import android.content.Context
import android.content.SharedPreferences
import com.northwalessolutions.netprobe.engine.portscan.ScanOptions
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** How a Devices row shows the device type (Director ruling R3): the colour stripe or a small icon. */
enum class TypeMarker { STRIPE, ICON }

data class SettingsState(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22,
    val scanConcurrency: Int = 128, val scanTimeoutMs: Int = 600, val nvdApiKey: String = "",
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
    /** "Listen between scans" (3C spec 3.1): off by default; covered by SettingsDefaults.reset, so a reset turns it off (D3). */
    val listenMulticast: Boolean = false,
    /** "Device type marker" (R3): the icon by default; covered by reset. */
    val typeMarker: TypeMarker = TypeMarker.ICON,
    /** "Check for updates automatically" (in-app updater spec 2): on by default; covered by reset. */
    val updateAuto: Boolean = true,
)

/** The single source of defaults (3C spec 1.1). AppSettings.load reads every default from [DEFAULTS], so the two can never drift. */
object SettingsDefaults {
    val DEFAULTS = SettingsState()
    /** Every covered value back to its default; the NVD key is kept (D3). Manual ranges, wordlists and the brute-force options are data, not settings. */
    fun reset(current: SettingsState): SettingsState = DEFAULTS.copy(nvdApiKey = current.nvdApiKey)
    fun differs(current: SettingsState): Boolean = reset(current) != current
    /** Undo of a reset: the snapshot from before it, with any NVD key typed since kept (spec 1.2 step 4). */
    fun undo(before: SettingsState, current: SettingsState): SettingsState = before.copy(nvdApiKey = current.nvdApiKey)
}

/**
 * Reset, then Undo when [showUndo] reports the Director tapped it (spec 1.2). Each step is one AppSettings.update, so one
 * SharedPreferences edit: a kill mid-reset never leaves half the values reset. Undo restores the whole snapshot, so a slider moved
 * between the reset and the Undo tap goes back too (accepted, spec 1.2, G18).
 */
object SettingsReset {
    suspend fun run(current: () -> SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, showUndo: suspend () -> Boolean) {
        val before = current()
        update { SettingsDefaults.reset(it) }
        if (showUndo()) update { SettingsDefaults.undo(before, it) }
    }
}

class AppSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    /** The current network's manual ranges, keyed per network row so a list typed at one site is never swept at another. */
    val manual = ManualRangeStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    /** Every default comes from SettingsDefaults.DEFAULTS (3C spec 1.1); keys and coercions are unchanged. */
    private fun load(): SettingsState {
        val d = SettingsDefaults.DEFAULTS
        return SettingsState(
            mdns = prefs.getBoolean("mdns", d.mdns), ssdp = prefs.getBoolean("ssdp", d.ssdp), netbios = prefs.getBoolean("netbios", d.netbios),
            reverseDns = prefs.getBoolean("rdns", d.reverseDns), snmp = prefs.getBoolean("snmp", d.snmp), httpTitle = prefs.getBoolean("http", d.httpTitle),
            sweepConcurrency = prefs.getInt("sweepConc", d.sweepConcurrency).coerceIn(32, 512),
            connectTimeoutMs = prefs.getInt("connTimeout", d.connectTimeoutMs).coerceIn(200, 2000),
            maxPrefix = prefs.getInt("maxPrefix", d.maxPrefix).coerceIn(20, 24),
            scanConcurrency = prefs.getInt("scanConc", d.scanConcurrency).coerceIn(32, 512),
            scanTimeoutMs = prefs.getInt("scanTimeout", d.scanTimeoutMs).coerceIn(200, 2000),
            nvdApiKey = prefs.getString("nvdKey", d.nvdApiKey) ?: d.nvdApiKey,
            extEnabled = prefs.getBoolean("extEnabled", d.extEnabled),
            extMax = prefs.getInt("extMax", d.extMax).coerceIn(EXT_MAX_MIN, EXT_MAX_MAX),
            extGatewayProbe = prefs.getBoolean("extGatewayProbe", d.extGatewayProbe),
            listenMulticast = prefs.getBoolean("listenMulticast", d.listenMulticast),
            typeMarker = TypeMarker.entries.firstOrNull { it.name == prefs.getString("typeMarker", d.typeMarker.name) } ?: d.typeMarker,
            updateAuto = prefs.getBoolean("updateAuto", d.updateAuto))
    }
    /** Port-scan options for the detail screen and "Quick scan all". */
    fun scanOptions() = ScanOptions(concurrency = state.value.scanConcurrency, connectTimeoutMs = state.value.scanTimeoutMs)
    fun update(f: (SettingsState) -> SettingsState) {
        val s = f(_state.value)
        prefs.edit().putBoolean("mdns", s.mdns).putBoolean("ssdp", s.ssdp).putBoolean("netbios", s.netbios).putBoolean("rdns", s.reverseDns).putBoolean("snmp", s.snmp).putBoolean("http", s.httpTitle)
            .putInt("sweepConc", s.sweepConcurrency).putInt("connTimeout", s.connectTimeoutMs).putInt("maxPrefix", s.maxPrefix).putInt("scanConc", s.scanConcurrency).putInt("scanTimeout", s.scanTimeoutMs)
            .putString("nvdKey", s.nvdApiKey)
            .putBoolean("extEnabled", s.extEnabled).putInt("extMax", s.extMax.coerceIn(EXT_MAX_MIN, EXT_MAX_MAX)).putBoolean("extGatewayProbe", s.extGatewayProbe).putBoolean("listenMulticast", s.listenMulticast).putString("typeMarker", s.typeMarker.name).putBoolean("updateAuto", s.updateAuto).apply()
        _state.value = load()
    }

    companion object {
        const val PREFS_NAME = "netprobe"
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
