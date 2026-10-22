package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One test of an HTTP exchange: [where] names what is read, [regex] is tried on it (`status` and `favicon` compare the whole number). */
class StackCondition(val where: String, val regex: Regex) {
    val isFavicon: Boolean get() = where == HttpStackRules.FAVICON
}

/** An idempotent GET a rule may spend to confirm itself: the reply must have [status] and, when [body] is set, match it. */
class StackPath(val path: String, val status: Int, val body: Regex?)

/** A sample as the data file gives it: one text for every condition (key "*"), or one text per `where`. */
class StackSample(val byWhere: Map<String, String>) {
    fun textFor(where: String): String? = byWhere[where] ?: byWhere["*"]
    val single: String? get() = byWhere["*"]
}

/**
 * One line of `http_stacks.json` (Phase 3E spec 5). [all] is AND; an empty [all] with [paths] is a path-only rule. [stack] and
 * [version] may carry `$1`, the first capture of the first condition that captured. [role] DEVICE rules may name vendor, model and
 * type; SOFTWARE rules feed "Runs <stack>" only. [cpeProduct] names an entry of cpe_map.json, never a free CPE.
 */
class HttpStackRule(
    val id: String, val all: List<StackCondition>, val stack: String, val product: String?, val version: String?, val role: MatchRole,
    val vendor: String?, val model: String?, val type: DeviceType?, val cpeProduct: String?, val paths: List<StackPath>, val hard: Boolean,
    val sample: StackSample, val negativeSample: StackSample?,
) {
    /** The first condition's source, for the Fingerprint tool's "Matched <source>" line. */
    val source: String get() = all.firstOrNull()?.where ?: "path"
    val pathOnly: Boolean get() = all.isEmpty()
    val needsFavicon: Boolean get() = all.any { it.isFavicon }
}

class SkippedStack(val id: String, val reason: String)
class LoadedStacks(val rules: List<HttpStackRule>, val skipped: List<SkippedStack>)

/** Loads `http_stacks.json`: a rule that fails validation is skipped with a reason and the rest still load (spec 5, 13). */
object HttpStackRules {
    const val FAVICON = "favicon"
    const val MAX_PATHS_PER_RULE = 3
    private val WHERE = setOf("title", "server", "poweredBy", "cookie", "body", "status", FAVICON, "generator", "errorBody")
    private val json = Json

    fun load(text: String, cpeProducts: Set<String>? = null): LoadedStacks {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { return LoadedStacks(emptyList(), listOf(SkippedStack("(file)", "not valid JSON: ${e.message?.take(80)}"))) }
        val array = root as? JsonArray ?: return LoadedStacks(emptyList(), listOf(SkippedStack("(file)", "top level is not a list")))
        val ok = mutableListOf<HttpStackRule>(); val skipped = mutableListOf<SkippedStack>(); val seen = HashSet<String>()
        val cpeLower = cpeProducts?.map { it.lowercase() }?.toSet()
        array.forEachIndexed { index, el ->
            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "(entry $index)"
            try {
                val rule = parse(el, cpeLower)
                if (!seen.add(rule.id)) skipped += SkippedStack(rule.id, "duplicate id") else ok += rule
            } catch (e: Invalid) { skipped += SkippedStack(id, e.message.orEmpty()) }
            catch (e: Exception) { skipped += SkippedStack(id, "unreadable: ${e.message?.take(80)}") }
        }
        return LoadedStacks(ok, skipped)
    }

    private class Invalid(message: String) : Exception(message)
    private fun bad(msg: String): Nothing = throw Invalid(msg)
    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun regexOf(pattern: String, what: String): Regex {
        RegexLint.refusal(pattern)?.let { bad("$what /$pattern/ refused: $it") }
        return try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { bad("$what /$pattern/ does not compile") }
    }

    private fun condition(o: JsonObject): StackCondition {
        val where = str(o, "where") ?: str(o, "source") ?: bad("a condition has no where")
        val named = where.startsWith("header:") && where.length > "header:".length && where.substring("header:".length).all { it.isLetterOrDigit() || it == '-' }
        if (where !in WHERE && !named) bad("unknown where $where")
        val pattern = str(o, "pattern")?.takeIf { it.isNotEmpty() } ?: bad("a condition has no pattern")
        return StackCondition(if (named) "header:" + where.substring("header:".length).lowercase() else where, regexOf(pattern, "pattern"))
    }

    private fun sample(el: JsonElement?, what: String): StackSample? = when (el) {
        null, is JsonNull -> null
        is JsonPrimitive -> StackSample(mapOf("*" to (el.contentOrNull ?: bad("$what is not text"))))
        is JsonObject -> StackSample(el.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: bad("$what holds a non-text value") })
        else -> bad("$what is not text or an object")
    }

    private fun path(el: JsonElement): StackPath {
        val o = el as? JsonObject ?: bad("a path is not an object")
        val p = str(o, "path") ?: bad("a path has no path")
        if (!p.startsWith("/") || p.startsWith("//") || p.any { it.code !in 0x21..0x7E }) bad("path $p is not a plain absolute path")
        val status = (o["status"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 } ?: bad("path $p has no valid status")
        val body = str(o, "body")?.let { regexOf(it, "path body") }
        return StackPath(p, status, body)
    }

    private fun parse(el: JsonElement, cpeProducts: Set<String>?): HttpStackRule {
        val o = el as? JsonObject ?: bad("not an object")
        val id = str(o, "id")?.takeIf { it.isNotBlank() } ?: bad("missing id")
        if ("cpe" in o) bad("a free CPE is refused: name a cpeProduct from cpe_map.json")
        val conditions: List<StackCondition> = when {
            o["all"] is JsonArray -> (o["all"] as JsonArray).map { condition(it as? JsonObject ?: bad("a condition is not an object")) }
            str(o, "pattern") != null -> listOf(condition(o))                      // the old single source/pattern pair
            else -> emptyList()
        }
        val stack = (str(o, "stack") ?: str(o, "name"))?.takeIf { it.isNotBlank() } ?: bad("missing stack")
        val paths = (o["paths"] as? JsonArray)?.map { path(it) }.orEmpty()
        if (paths.size > MAX_PATHS_PER_RULE) bad("more than $MAX_PATHS_PER_RULE paths")
        if (conditions.isEmpty() && paths.isEmpty()) bad("no conditions and no paths")
        if (conditions.isEmpty() && paths.any { it.body == null }) bad("a path-only rule needs a body pattern on every path")
        val role = when (str(o, "role") ?: "software") { "software" -> MatchRole.SOFTWARE; "device" -> MatchRole.DEVICE; else -> bad("role must be device or software") }
        val vendor = str(o, "vendor"); val model = str(o, "model"); val typeName = str(o, "type")
        if (role == MatchRole.SOFTWARE && (typeName != null || vendor != null || model != null)) bad("a software rule may not carry vendor, model or type")
        val type = typeName?.let { n -> if (n == "HUB") DeviceType.IOT else DeviceType.entries.firstOrNull { it.name == n && it != DeviceType.UNKNOWN } ?: bad("unknown type $n") }
        val cpe = str(o, "cpeProduct")
        if (cpe != null && cpeProducts != null && cpe.lowercase() !in cpeProducts) bad("cpeProduct $cpe is not in cpe_map.json")
        val hard = (o["hard"] as? JsonPrimitive)?.booleanOrNull ?: (paths.isNotEmpty() || conditions.any { it.isFavicon })
        val sample = sample(o["sample"], "sample") ?: bad("missing sample")
        return HttpStackRule(id, conditions, stack, str(o, "product"), str(o, "version"), role, vendor, model, type, cpe, paths, hard, sample,
            sample(o["negativeSample"], "negativeSample"))
    }
}
