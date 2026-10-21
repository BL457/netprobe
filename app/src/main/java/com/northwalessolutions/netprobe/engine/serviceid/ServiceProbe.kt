package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class ProbeSafety { SAFE, CAREFUL }
enum class TlsMode { NEVER, ALSO, ONLY }
enum class MatchRole { DEVICE, SOFTWARE }

/** One line of a probe's match list: a regex over the reply text and the fields it sets (Phase 3E spec 4). */
class ProbeMatch(
    val regex: Regex, val service: String, val product: String?, val version: String?, val detail: String?, val stack: String?,
    val role: MatchRole, val soft: Boolean, val narrow: List<String>,
)

/** What a match produced, with the `$1..$9` and `$$` substitutions already made. */
data class ProbeHit(val probeId: String, val service: String, val product: String?, val version: String?, val detail: String?,
                    val stack: String?, val role: MatchRole, val soft: Boolean, val narrow: List<String>)

/**
 * One hello. [sendTemplate] is the decoded bytes as ISO-8859-1 text with `{ip}`, `{port}` and `{rand}` still in it; they are
 * substituted at send time by [sendBytes]. [protocols] holds "tcp" and or "null" ("null" means the matches are also tried on the
 * null-probe reply).
 */
class ServiceProbe(
    val id: String, val service: String, val protocols: Set<String>, val sendTemplate: String, val ports: List<Int>, val rarity: Int,
    val safety: ProbeSafety, val tls: TlsMode, val waitMs: Int, val maxBytes: Int, val parser: String?, val quick: Boolean,
    val matches: List<ProbeMatch>, val samples: List<String>, val negativeSamples: List<String>,
) {
    val sendsBytes: Boolean get() = "tcp" in protocols && sendTemplate.isNotEmpty()
    val matchesNullReply: Boolean get() = "null" in protocols

    fun sendBytes(ip: String, port: Int, rand: String): ByteArray =
        sendTemplate.replace("{ip}", ip).replace("{port}", port.toString()).replace("{rand}", rand).toByteArray(Charsets.ISO_8859_1)

    /** The reply as the matches see it: cut at [maxBytes], run through the parser if there is one, as ISO-8859-1 text. Null when the parser refuses it. */
    fun replyText(reply: ByteArray): String? {
        val cut = if (reply.size > maxBytes) reply.copyOf(maxBytes) else reply
        return if (parser == null) String(cut, Charsets.ISO_8859_1) else ProbeParsers.parse(parser, cut)
    }

    /** The first match that hits, or null. */
    fun match(reply: ByteArray): ProbeHit? {
        val text = replyText(reply) ?: return null
        for (m in matches) {
            val r = m.regex.find(text) ?: continue
            return ProbeHit(id, m.service, expand(m.product, r), expand(m.version, r), expand(m.detail, r), expand(m.stack, r), m.role, m.soft, m.narrow)
        }
        return null
    }

    companion object {
        private const val MAX_FIELD = 120
        /** `$1`..`$9` are the regex groups (empty when absent), `$$` a literal dollar; the result is trimmed, capped, and null when empty. */
        fun expand(template: String?, r: MatchResult): String? {
            if (template == null) return null
            val out = StringBuilder(); var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '$' && i + 1 < template.length) {
                    val n = template[i + 1]
                    if (n == '$') { out.append('$'); i += 2; continue }
                    if (n in '1'..'9') { out.append(r.groupValues.getOrNull(n - '0')?.takeIf { it.isNotEmpty() }.orEmpty()); i += 2; continue }
                }
                out.append(c); i++
            }
            return out.toString().trim().take(MAX_FIELD).ifEmpty { null }
        }
    }
}

class SkippedProbe(val id: String, val reason: String)
class LoadedProbes(val probes: List<ServiceProbe>, val skipped: List<SkippedProbe>)

/** Loads `service_probes.json`: a probe that fails validation is skipped with a reason and the rest still load (spec 4). */
object ServiceProbeRules {
    const val MAX_SEND = 512
    const val MAX_REPLY = 2048
    private const val MAX_WAIT_MS = 5000
    private const val DEFAULT_WAIT_MS = 800
    private val PROTOCOLS = setOf("tcp", "null")
    private val json = Json

    fun load(text: String, knownParsers: Set<String> = ProbeParsers.NAMES): LoadedProbes {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { return LoadedProbes(emptyList(), listOf(SkippedProbe("(file)", "not valid JSON: ${e.message?.take(80)}"))) }
        val array = root as? JsonArray ?: return LoadedProbes(emptyList(), listOf(SkippedProbe("(file)", "top level is not a list")))
        val ok = mutableListOf<ServiceProbe>(); val skipped = mutableListOf<SkippedProbe>(); val seen = HashSet<String>()
        array.forEachIndexed { index, el ->
            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "(entry $index)"
            try {
                val probe = parse(el, knownParsers)
                if (!seen.add(probe.id)) skipped += SkippedProbe(probe.id, "duplicate id") else ok += probe
            } catch (e: InvalidProbe) { skipped += SkippedProbe(id, e.message.orEmpty()) }
            catch (e: Exception) { skipped += SkippedProbe(id, "unreadable: ${e.message?.take(80)}") }
        }
        // A soft match may only narrow to probes that exist.
        val ids = ok.map { it.id }.toSet()
        val dangling = ok.filter { p -> p.matches.any { m -> m.narrow.any { it !in ids } } }
        dangling.forEach { p -> skipped += SkippedProbe(p.id, "narrow names an unknown probe") }
        return LoadedProbes(ok - dangling.toSet(), skipped)
    }

    private class InvalidProbe(message: String) : Exception(message)
    private fun bad(msg: String): Nothing = throw InvalidProbe(msg)

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun strList(o: JsonObject, key: String): List<String> = (o[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: bad("$key holds a non-string") }.orEmpty()

    private fun parse(el: JsonElement, knownParsers: Set<String>): ServiceProbe {
        val o = el as? JsonObject ?: bad("not an object")
        val id = str(o, "id")?.takeIf { it.isNotBlank() } ?: bad("missing id")
        val service = str(o, "service")?.takeIf { it.isNotBlank() } ?: bad("missing service")
        val protocols = strList(o, "protocols").toSet()
        if (protocols.isEmpty() || !PROTOCOLS.containsAll(protocols)) bad("protocols must be tcp and or null")
        val sendRaw = str(o, "send") ?: bad("missing send")
        val template = try { decodeEscapes(sendRaw) } catch (e: IllegalArgumentException) { bad("send: ${e.message}") }
        if (template.length > MAX_SEND) bad("send is ${template.length} bytes, over $MAX_SEND")
        if (template.isEmpty() && "null" !in protocols) bad("empty send needs the null protocol")
        if (protocols == setOf("null") && template.isNotEmpty()) bad("a null-only probe sends nothing")
        val ports = (o["ports"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull?.takeIf { p -> p in 1..65535 } ?: bad("bad port") }.orEmpty()
        val rarity = (o["rarity"] as? JsonPrimitive)?.intOrNull ?: bad("missing rarity")
        if (rarity !in 1..9) bad("rarity $rarity outside 1..9")
        val safety = when (str(o, "safety")) { "safe" -> ProbeSafety.SAFE; "careful" -> ProbeSafety.CAREFUL; else -> bad("safety must be safe or careful") }
        val tls = when (str(o, "tls")) { "never" -> TlsMode.NEVER; "also" -> TlsMode.ALSO; "only" -> TlsMode.ONLY; else -> bad("tls must be never, also or only") }
        val waitMs = (o["waitMs"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_WAIT_MS
        if (waitMs !in 1..MAX_WAIT_MS) bad("waitMs $waitMs outside 1..$MAX_WAIT_MS")
        val maxBytes = (o["maxBytes"] as? JsonPrimitive)?.intOrNull ?: MAX_REPLY
        if (maxBytes !in 1..MAX_REPLY) bad("maxBytes $maxBytes over $MAX_REPLY")
        val parser = str(o, "parser")
        if (parser != null && parser !in knownParsers) bad("unknown parser $parser")
        val quick = (o["quick"] as? JsonPrimitive)?.booleanOrNull ?: false
        val matchEls = o["matches"] as? JsonArray ?: bad("missing matches")
        if (matchEls.isEmpty()) bad("matches is empty")
        val matches = matchEls.map { parseMatch(it) }
        return ServiceProbe(id, service, protocols, template, ports, rarity, safety, tls, waitMs, maxBytes, parser, quick, matches,
            strList(o, "samples"), strList(o, "negativeSamples"))
    }

    private fun parseMatch(el: JsonElement): ProbeMatch {
        val m = el as? JsonObject ?: bad("a match is not an object")
        val pattern = str(m, "regex")?.takeIf { it.isNotEmpty() } ?: bad("a match has no regex")
        RegexLint.refusal(pattern)?.let { bad("regex /$pattern/ refused: $it") }
        val regex = try { Regex(pattern) } catch (e: Exception) { bad("regex /$pattern/ does not compile") }   // MULTILINE stays off
        val service = str(m, "service")?.takeIf { it.isNotBlank() } ?: bad("a match has no service")
        val role = when (str(m, "role") ?: "software") { "software" -> MatchRole.SOFTWARE; "device" -> MatchRole.DEVICE; else -> bad("role must be device or software") }
        return ProbeMatch(regex, service, str(m, "product"), str(m, "version"), str(m, "detail"), str(m, "stack"), role,
            (m["soft"] as? JsonPrimitive)?.booleanOrNull ?: false, strList(m, "narrow"))
    }

    /** nmap's escapes: `\r \n \t \0 \xNN \\`. Anything else after a backslash is an error. Output is ISO-8859-1 text (one char per byte). */
    fun decodeEscapes(s: String): String {
        val out = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { out.append(c); i++; continue }
            if (i + 1 >= s.length) throw IllegalArgumentException("dangling backslash")
            when (val n = s[i + 1]) {
                'r' -> { out.append('\r'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                '0' -> { out.append('\u0000'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'x' -> {
                    val hex = s.substring(i + 2, minOf(i + 4, s.length))
                    if (hex.length != 2 || hex.any { it.digitToIntOrNull(16) == null }) throw IllegalArgumentException("bad \\x escape")
                    out.append(hex.toInt(16).toChar()); i += 4
                }
                else -> throw IllegalArgumentException("unknown escape \\$n")
            }
        }
        return out.toString()
    }
}

/** The one cheap ReDoS lint: an unbounded quantifier on a group that itself holds an unbounded quantifier (`(a+)+`, `(a*)*`, `(a+)*`). */
object RegexLint {
    /** A reason to refuse [pattern], or null. */
    fun refusal(pattern: String): String? {
        val unboundedInside = ArrayDeque<Boolean>()
        var inClass = false; var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass -> { if (c == ']') inClass = false }
                c == '[' -> inClass = true
                c == '(' -> { unboundedInside.addLast(false); if (pattern.startsWith("(?", i)) i += 1 /* the ? of (?: is not a quantifier */ }
                c == ')' -> {
                    val inner = if (unboundedInside.isEmpty()) false else unboundedInside.removeLast()
                    val q = quantifierAt(pattern, i + 1)
                    if (q.unbounded && inner) return "nested quantifier"
                    if (unboundedInside.isNotEmpty() && (inner || q.unbounded)) { unboundedInside.removeLast(); unboundedInside.addLast(true) }
                }
                c == '+' || c == '*' -> { if (unboundedInside.isNotEmpty()) { unboundedInside.removeLast(); unboundedInside.addLast(true) } }
                c == '{' -> { if (quantifierAt(pattern, i).unbounded && unboundedInside.isNotEmpty()) { unboundedInside.removeLast(); unboundedInside.addLast(true) } }
            }
            i++
        }
        return null
    }

    private class Q(val unbounded: Boolean)
    private val unboundedBrace = Regex("^\\{\\d+,\\}")
    private fun quantifierAt(p: String, at: Int): Q {
        if (at >= p.length) return Q(false)
        return when (p[at]) { '+', '*' -> Q(true); '{' -> Q(unboundedBrace.containsMatchIn(p.substring(at))); else -> Q(false) }
    }
}
