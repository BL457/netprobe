package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRule
import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRules
import com.northwalessolutions.netprobe.engine.serviceid.HttpStacks
import com.northwalessolutions.netprobe.engine.serviceid.StackSources

/** What a rule reads (spec 6.1): response headers in order, cookie names, the page title and generator, the page body and the error page body. */
data class FpSources(val headers: List<Pair<String, String>>, val cookies: List<String>, val title: String?, val generator: String?, val body: String, val errorBody: String,
                     val status: Int? = null)
data class FpMatch(val name: String, val excerpt: String)

/** The Fingerprint tool's reader of `http_stacks.json` (Phase 3E spec 5): the same rules the service ladder uses, tested over the tool's sources. */
object FingerprintRules {
    fun load(text: String, cpeProducts: Set<String>? = null): List<HttpStackRule> = HttpStackRules.load(text, cpeProducts).rules

    /** The resolved stack name (with its version when the rule captured one) and the matched text when every condition holds, else null. */
    fun match(rule: HttpStackRule, sources: FpSources): FpMatch? {
        val s = StackSources(sources.headers, sources.cookies, sources.title, sources.generator, sources.body, sources.errorBody, sources.status)
        val m = HttpStacks.test(rule, s) ?: return null
        val name = listOfNotNull(HttpStacks.expand(rule.stack, m.capture), HttpStacks.expand(rule.version, m.capture)).joinToString(" ")
        return FpMatch(name.ifEmpty { rule.stack }, m.excerpt)
    }
}
