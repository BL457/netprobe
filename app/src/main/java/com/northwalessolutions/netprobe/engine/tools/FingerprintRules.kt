package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class FingerprintRule(val id: String, val source: String, val pattern: String, val name: String, val sample: String, val negativeSample: String? = null)

/** What a rule reads (spec 6.1): response headers in order, cookie names, the page title and generator, the page body and the error page body. */
data class FpSources(val headers: List<Pair<String, String>>, val cookies: List<String>, val title: String?, val generator: String?, val body: String, val errorBody: String)
data class FpMatch(val name: String, val excerpt: String)

object FingerprintRules {
    private const val EXCERPT_CHARS = 40
    private val json = Json { ignoreUnknownKeys = true }
    fun load(text: String): List<FingerprintRule> = runCatching { json.decodeFromString<List<FingerprintRule>>(text) }.getOrElse { emptyList() }

    /** The resolved product name and the matched text when the rule matches, else null; $1 is the first group, trimmed. */
    fun match(rule: FingerprintRule, sources: FpSources): FpMatch? {
        val re = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val candidates: List<String> = when {
            rule.source == "title" -> listOfNotNull(sources.title)
            rule.source == "generator" -> listOfNotNull(sources.generator)
            rule.source == "body" -> listOf(sources.body)
            rule.source == "errorBody" -> listOf(sources.errorBody)
            rule.source == "cookie" -> sources.cookies
            rule.source.startsWith("header:") -> { val h = rule.source.removePrefix("header:"); sources.headers.filter { it.first.equals(h, ignoreCase = true) }.map { it.second } }
            else -> emptyList()
        }
        for (c in candidates) {
            val m = re.find(c) ?: continue
            val group = if (m.groups.size > 1) m.groups[1]?.value.orEmpty() else ""
            return FpMatch(rule.name.replace("\$1", group).trim(), c.take(EXCERPT_CHARS))   // the excerpt is the text the rule read
        }
        return null
    }
}
