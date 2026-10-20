package com.northwalessolutions.netprobe.netenv

data class CidrEntry(val subnet: Subnet, val note: String?)
data class CidrResult(val entries: List<CidrEntry>, val error: String?) { val subnets: List<Subnet> get() = entries.map { it.subnet } }

/** Validation for the per-network manual list (extended spec 4.4). Pure; the messages are the inline Settings errors. */
object CidrParser {
    const val MIN_PREFIX = 20
    const val MAX_PREFIX = 30
    private const val MAX_BITS = 32
    private const val MAX_PREFIX_DIGITS = 2
    const val ERR_FORMAT = "Not an IPv4 range, use a.b.c.d/p"
    const val ERR_NO_PREFIX = "Add a prefix, for example /24"
    const val ERR_PREFIX = "Prefix must be a number from 20 to 30"
    const val ERR_TOO_LARGE = "Too large, use /20 or smaller"
    const val ERR_TOO_SMALL = "Too small to sweep, use /30 or larger"
    const val ERR_PRIVATE = "Only private ranges are allowed"
    const val ERR_OWN = "This is your own network"
    private val SEPARATORS = Regex("[,\\r\\n]+")

    private fun fail(message: String): Result<CidrEntry> = Result.failure(IllegalArgumentException(message))

    fun parseOne(token: String, local: Subnet?): Result<CidrEntry> {
        val t = token.filterNot { it.isWhitespace() }
        if (t.contains(':')) return fail(ERR_FORMAT)                                      // IPv6
        val parts = t.split('/')
        if (parts.size == 1) return fail(if (Ipv4.parse(parts[0]) != null) ERR_NO_PREFIX else ERR_FORMAT)
        if (parts.size != 2) return fail(ERR_FORMAT)
        val ip = Ipv4.parse(parts[0]) ?: return fail(ERR_FORMAT)
        val p = parts[1].takeIf { it.isNotEmpty() && it.length <= MAX_PREFIX_DIGITS && it.all(Char::isDigit) }?.toInt() ?: return fail(ERR_PREFIX)
        if (p > MAX_BITS) return fail(ERR_PREFIX)
        if (p < MIN_PREFIX) return fail(ERR_TOO_LARGE)
        if (p > MAX_PREFIX) return fail(ERR_TOO_SMALL)
        val s = SubnetMath.of(ip, p)
        if (!Rfc1918.contains(s)) return fail(ERR_PRIVATE)
        if (local != null && local.containsSubnet(s)) return fail(ERR_OWN)
        return Result.success(CidrEntry(s, if (SubnetMath.toInt(ip) != s.network) "Normalised to $s" else null))
    }

    /** One entry per line or comma separated; stops at the first invalid entry so the field shows one error. */
    fun parse(text: String, local: Subnet?): CidrResult {
        val out = ArrayList<CidrEntry>()
        for (token in text.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = parseOne(token, local)
            r.exceptionOrNull()?.let { return CidrResult(out, it.message) }
            out += r.getOrThrow()
        }
        return CidrResult(out, null)
    }

    /** A stored line back to a subnet; invalid lines give null and are dropped (spec 4.4). The own-network check runs at derive time. */
    fun subnetOf(text: String): Subnet? = parseOne(text, null).getOrNull()?.subnet

    fun units(s: Subnet): List<Subnet> = s.slash24Units()

    fun merge(existing: List<Subnet>, added: List<Subnet>): Pair<List<Subnet>, List<String>> {
        val list = existing.toMutableList(); val notes = ArrayList<String>()
        for (s in added) if (s in list) notes += "Duplicate removed: $s" else list += s
        return list to notes
    }
}
