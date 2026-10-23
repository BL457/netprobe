package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import com.northwalessolutions.netprobe.engine.discovery.SnmpVarbind
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy

sealed interface SnmpStep { data class Ok(val varbinds: List<SnmpVarbind>) : SnmpStep; object Timeout : SnmpStep; object Error : SnmpStep }
/** What a walk sends with: the community string (a secret, never recorded) and the wire version (SnmpPdu.VERSION_1 or VERSION_2C). */
data class SnmpSettings(val community: String, val version: Int)
interface SnmpExchange { suspend fun getNext(oids: List<String>): SnmpStep; suspend fun get(oids: List<String>): SnmpStep }

/**
 * SNMP walk, v2c and community public unless the inputs say otherwise (spec 6.4): the system group, the interface table and the address table, keyed by index suffix.
 * Read-only (GET and GETNEXT). Bounded: at most SNMP_MAX_REQUESTS requests, SNMP_MAX_INTERFACES and SNMP_MAX_ADDRESSES rows, and a
 * step ends on endOfMibView or another exception, on leaving its subtree, or on an OID that does not increase (a looping agent).
 * Device text is cleaned of control and format characters and capped at SNMP_MAX_TEXT before it reaches a finding or a hint.
 */
class SnmpWalkTool(private val exchangeFor: (ToolTarget, SnmpSettings) -> SnmpExchange) : ToolModule {
    override val descriptor = ToolDescriptor(ToolKeys.SNMP_WALK, ToolCopy.SNMP_WALK, ToolModelCopy.SNMP_WALK_DESCRIPTION, ToolIcon.NETWORK,
        ToolCategory.NETWORK, TargetKind.HOST, listOf(
            InputSpec.Secret(COMMUNITY, ToolModelCopy.SNMP_COMMUNITY, default = DEFAULT_COMMUNITY),
            InputSpec.Choice(VERSION, ToolModelCopy.SNMP_VERSION, listOf(ChoiceOption(V1), ChoiceOption(V2C)), default = V2C),
        ))
    override fun applies(port: Int, service: ServiceInfo) = false
    override fun appliesToHost(host: HostEntity) = ToolApplicability.snmp(host)

    /** Counts requests against SNMP_MAX_REQUESTS and progress; null from [next] means the cap is reached or the step ended. */
    private class Walk(val ex: SnmpExchange, val control: RunControl, val progress: ToolProgress) {
        var requests = 0; var done = 0; var found = 0
        suspend fun next(oids: List<String>, get: Boolean = false): List<SnmpVarbind>? {
            if (requests >= SNMP_MAX_REQUESTS) return null
            control.gate()
            val step = control.excusedOnce<SnmpStep>({ it !is SnmpStep.Ok }) {                // spec 4.8 (plan review G5)
                // Re-checked here so the excused retry counts against the cap too: a run never sends more than SNMP_MAX_REQUESTS.
                if (requests >= SNMP_MAX_REQUESTS) SnmpStep.Timeout else { requests++; if (get) ex.get(oids) else ex.getNext(oids) }
            }
            done++; progress.update(done, null, found)
            return (step as? SnmpStep.Ok)?.varbinds
        }
        suspend fun emit(f: Finding) { found++; progress.finding(f); progress.update(done, null, found) }
    }

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (target == null) return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolModelCopy.NO_TARGET))
        val settings = SnmpSettings(inputs.text(COMMUNITY).ifEmpty { DEFAULT_COMMUNITY }, if (inputs.choice(VERSION) == V1) SnmpPdu.VERSION_1 else SnmpPdu.VERSION_2C)
        val w = Walk(exchangeFor(target, settings), control, progress)
        var sysName: String? = null; var sysDescr: String? = null; var answered = false

        // 1. System group: GETNEXT from 1.3.6.1.2.1.1 until the reply leaves the subtree or stops increasing.
        var cursor = SYSTEM
        while (true) {
            val vb = w.next(listOf(cursor))?.firstOrNull() ?: break
            if (vb.exception || !vb.oid.startsWith("$SYSTEM.") || OID_ORDER.compare(vb.oid, cursor) <= 0) break
            answered = true; cursor = vb.oid
            val (label, value) = systemField(vb) ?: continue
            if (label == ToolCopy.SYS_NAME) sysName = value.ifEmpty { null }
            if (label == ToolCopy.SYS_DESCR) sysDescr = value.ifEmpty { null }
            w.emit(Finding(FindingKind.SNMP_SYSTEM, value, label))
            when (label) {
                ToolCopy.SYS_DESCR -> progress.finding(Finding(FindingKind.HINT, "snmp.sysDescr=$value"))
                ToolCopy.SYS_OBJECT_ID -> progress.finding(Finding(FindingKind.HINT, "snmp.sysObjectID=$value"))
                ToolCopy.SYS_NAME -> progress.finding(Finding(FindingKind.HINT, "snmp.sysName=$value"))
            }
        }
        if (!answered) return ToolResult(ToolRunStatus.FAILED, ToolCopy.SNMP_NO_ANSWER)

        // 2. Interfaces, lockstep over five columns, rows keyed by index suffix.
        val ifTable = walkTable(w, IF_COLUMNS, IF_DESCR, SNMP_MAX_INTERFACES)
        val rows = ifTable.getValue(IF_DESCR).keys.sortedWith(OID_ORDER)
        var highSpeedAnswers = true                                   // the first Timeout or Error ends the ifHighSpeed GETs for this step
        for (index in rows) {
            val descr = ifTable.getValue(IF_DESCR).getValue(index).str?.let(::clean) ?: continue
            var speedBits = ifTable.getValue(IF_SPEED)[index]?.long
            if (speedBits == SATURATED_SPEED && highSpeedAnswers) {
                val reply = w.next(listOf("$IF_HIGH_SPEED.$index"), get = true)
                if (reply == null) highSpeedAnswers = false
                // Bounded to Gauge32 before the multiply, so a hostile value can never overflow or go negative.
                reply?.firstOrNull()?.long?.takeIf { it in 0..MAX_HIGH_SPEED_MBIT }?.let { speedBits = it * BITS_PER_MBIT }
            }
            val mac = ifTable.getValue(IF_MAC)[index]?.raw?.takeIf { it.size == MAC_BYTES }?.joinToString(":") { "%02x".format(it) }
            w.emit(Finding(FindingKind.SNMP_INTERFACE, descr,
                ToolCopy.interfaceDetail(ifTable.getValue(IF_STATUS)[index]?.long, ifTable.getValue(IF_TYPE)[index]?.long, speedBits, mac)))
        }

        // 3. Addresses: ipAdEntAddr and ipAdEntNetMask in lockstep.
        val addrTable = walkTable(w, ADDR_COLUMNS, ADDR, SNMP_MAX_ADDRESSES)
        for (index in addrTable.getValue(ADDR).keys.sortedWith(OID_ORDER)) {
            val addr = addrTable.getValue(ADDR).getValue(index).str?.let(::clean)?.ifEmpty { null } ?: continue
            val mask = addrTable.getValue(MASK)[index]?.str ?: continue
            w.emit(Finding(FindingKind.SNMP_ADDRESS, ToolCopy.address(addr, prefixOf(mask)), ToolCopy.INTERFACE_ADDRESS))
        }

        return ToolResult(ToolRunStatus.DONE, ToolCopy.snmpSummary(sysName ?: sysDescr?.take(SUMMARY_DESCR_CHARS) ?: target.ip.hostAddress, rows.size))
    }

    /**
     * GETNEXT over the still-live columns until each has left its subtree or stopped increasing, the key column holds [maxRows] rows,
     * the step times out or errors, or the request cap is reached. Each column's values are keyed by the index suffix after
     * "<column>.", so a sparse column never shifts the others.
     */
    private suspend fun walkTable(w: Walk, columns: List<String>, key: String, maxRows: Int): Map<String, Map<String, SnmpVarbind>> {
        val table = columns.associateWith { LinkedHashMap<String, SnmpVarbind>() }
        val cursors = columns.associateWith { it }.toMutableMap()
        val live = columns.toMutableList()
        while (live.isNotEmpty() && table.getValue(key).size < maxRows) {
            val replies = w.next(live.map { cursors.getValue(it) }) ?: break
            val stillLive = ArrayList<String>()
            live.forEachIndexed { i, column ->
                val vb = replies.getOrNull(i) ?: return@forEachIndexed
                if (vb.exception || !vb.oid.startsWith("$column.") || OID_ORDER.compare(vb.oid, cursors.getValue(column)) <= 0) return@forEachIndexed
                table.getValue(column)[vb.oid.removePrefix("$column.")] = vb
                cursors[column] = vb.oid; stillLive += column
            }
            live.clear(); live += stillLive
        }
        return table
    }

    private fun systemField(vb: SnmpVarbind): Pair<String, String>? {
        val leaf = vb.oid.removePrefix("$SYSTEM.").substringBefore('.')
        return when (leaf) {
            "1" -> ToolCopy.SYS_DESCR to clean(vb.str ?: return null)
            "2" -> ToolCopy.SYS_OBJECT_ID to clean(vb.str ?: return null)
            "3" -> ToolCopy.UPTIME to ToolCopy.uptime(vb.long?.takeIf { it >= 0 } ?: return null)
            "4" -> ToolCopy.SYS_CONTACT to clean(vb.str ?: return null)
            "5" -> ToolCopy.SYS_NAME to clean(vb.str ?: return null)
            "6" -> ToolCopy.SYS_LOCATION to clean(vb.str ?: return null)
            else -> null
        }
    }
    private fun prefixOf(mask: String): Int = mask.split('.').sumOf { Integer.bitCount(it.toIntOrNull()?.and(0xff) ?: 0) }

    companion object {
        const val COMMUNITY = "community"; const val VERSION = "version"; const val V1 = "v1"; const val V2C = "v2c"; const val DEFAULT_COMMUNITY = "public"
        const val SNMP_TIMEOUT_MS = 1000; const val SNMP_MAX_REQUESTS = 400; const val SNMP_MAX_INTERFACES = 64; const val SNMP_MAX_ADDRESSES = 64
        /** DisplayString is at most 255 octets (RFC 2579); longer device text is cut. */
        const val SNMP_MAX_TEXT = 255
        private const val SYSTEM = "1.3.6.1.2.1.1"
        private const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"; private const val IF_TYPE = "1.3.6.1.2.1.2.2.1.3"; private const val IF_SPEED = "1.3.6.1.2.1.2.2.1.5"
        private const val IF_MAC = "1.3.6.1.2.1.2.2.1.6"; private const val IF_STATUS = "1.3.6.1.2.1.2.2.1.8"; private const val IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"
        private val IF_COLUMNS = listOf(IF_DESCR, IF_TYPE, IF_SPEED, IF_MAC, IF_STATUS)
        private const val ADDR = "1.3.6.1.2.1.4.20.1.1"; private const val MASK = "1.3.6.1.2.1.4.20.1.3"
        private val ADDR_COLUMNS = listOf(ADDR, MASK)
        private const val SATURATED_SPEED = 4_294_967_295L; private const val BITS_PER_MBIT = 1_000_000L
        private const val MAX_HIGH_SPEED_MBIT = 4_294_967_295L     // Gauge32; times BITS_PER_MBIT stays far below Long.MAX_VALUE
        private const val MAC_BYTES = 6; private const val SUMMARY_DESCR_CHARS = 40
        /** Numeric order of dotted OIDs or index suffixes, arc by arc (so "10" sorts after "2"). */
        private val OID_ORDER = Comparator<String> { a, b ->
            val x = a.split('.').map { it.toLongOrNull() ?: 0 }; val y = b.split('.').map { it.toLongOrNull() ?: 0 }
            (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }?.let { x[it].compareTo(y[it]) } ?: x.size.compareTo(y.size)
        }
        /** Control and format characters removed (no newline, escape or bidi override reaches a finding or a hint), then capped. */
        private fun clean(text: String): String =
            text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.take(SNMP_MAX_TEXT)
                .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    }
}
