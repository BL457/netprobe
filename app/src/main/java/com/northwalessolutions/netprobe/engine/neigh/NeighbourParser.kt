package com.northwalessolutions.netprobe.engine.neigh

data class NeighbourEntry(val ip: String, val mac: String, val state: String)
class NeighbourUnavailable(reason: String) : Exception(reason)

object NeighbourParser {
    private val macRe = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    private val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val dropStates = setOf("FAILED", "INCOMPLETE", "NOARP", "NONE")

    fun parse(raw: Array<String>): Result<List<NeighbourEntry>> {
        if (raw.size == 1 && raw[0].startsWith("ERR|")) {
            val p = raw[0].split("|", limit = 3)
            return Result.failure(NeighbourUnavailable("errno ${p.getOrNull(1)}: ${p.getOrNull(2)}"))
        }
        val out = ArrayList<NeighbourEntry>()
        for (line in raw) {
            val p = line.split("|")
            if (p.size != 3) continue
            val ip = p[0]; val mac = p[1].lowercase(); val state = p[2]
            if (!ipv4Re.matches(ip) || !macRe.matches(mac)) continue
            if (mac == "00:00:00:00:00:00" || state in dropStates) continue
            out += NeighbourEntry(ip, mac, state)
        }
        return Result.success(out)
    }

    /** /proc/net/arp fallback for Android 9 and lower. Columns: ip, hw type, flags, hw address, mask, device. Flags 0x2 = complete. */
    fun parseProcArp(text: String): List<NeighbourEntry> = text.lineSequence().drop(1).mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 6) return@mapNotNull null
        val ip = p[0]; val mac = p[3].lowercase()
        if (!ipv4Re.matches(ip) || !macRe.matches(mac) || mac == "00:00:00:00:00:00" || p[2] != "0x2") null
        else NeighbourEntry(ip, mac, "REACHABLE")
    }.toList()
}
