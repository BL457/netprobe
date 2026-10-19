package com.northwalessolutions.netprobe.engine.portscan

object QuickPorts {
    val list: List<Int> = listOf(
        7, 9, 13, 21, 22, 23, 25, 26, 37, 53, 79, 80, 81, 88, 106, 110, 111, 113, 119, 135, 139, 143, 144, 179, 199,
        389, 427, 443, 444, 445, 465, 513, 514, 515, 543, 544, 548, 554, 587, 631, 646, 873, 990, 993, 995,
        1025, 1026, 1027, 1028, 1029, 1110, 1433, 1720, 1723, 1755, 1900, 2000, 2001, 2049, 2121, 2717,
        3000, 3128, 3306, 3389, 3986, 4899, 5000, 5009, 5051, 5060, 5101, 5190, 5357, 5432, 5631, 5666, 5800, 5900,
        6000, 6001, 6646, 7070, 8000, 8008, 8009, 8080, 8081, 8443, 8888, 9100, 9999, 10000, 32768,
        49152, 49153, 49154, 49155, 49156, 49157,
        // LAN extras
        1883, 2222, 3001, 5001, 5555, 5985, 8006, 8096, 8123, 8291, 8880, 9000, 9090, 32400, 62078,
    )
}

sealed class ScanProfile {
    abstract val ports: List<Int>
    abstract val label: String
    object Quick : ScanProfile() { override val ports get() = QuickPorts.list; override val label = "Quick" }
    object Full : ScanProfile() { override val ports get() = (1..65535).toList(); override val label = "Full" }
    data class Custom(override val ports: List<Int>) : ScanProfile() { override val label = "Custom" }
}

object ProfileParser {
    private const val MAX_PORT = 65535
    private val TOKEN = Regex("""^(\d+)(?:-(\d+))?$""")

    fun parse(text: String): Result<ScanProfile.Custom> {
        val tokens = text.split(',').map { it.trim() }
        if (text.isBlank() || tokens.any { it.isEmpty() }) return Result.failure(IllegalArgumentException("Enter ports like 22,80,8000-8100"))
        val out = sortedSetOf<Int>()
        for (t in tokens) {
            val m = TOKEN.matchEntire(t) ?: return Result.failure(IllegalArgumentException("Not a port or range: '$t'"))
            val a = m.groupValues[1].toIntOrNull() ?: return Result.failure(IllegalArgumentException("Not a number: '$t'"))
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Not a number: '$t'"))
            if (a !in 1..MAX_PORT || b !in 1..MAX_PORT) return Result.failure(IllegalArgumentException("Ports must be 1 to 65535: '$t'"))
            if (b < a) return Result.failure(IllegalArgumentException("Range is backwards: '$t'"))
            out.addAll(a..b)
        }
        return Result.success(ScanProfile.Custom(out.toList()))
    }
}
