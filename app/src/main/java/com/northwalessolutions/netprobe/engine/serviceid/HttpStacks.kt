package com.northwalessolutions.netprobe.engine.serviceid

/** What the rules read from one HTTP exchange (spec 5): headers in order, cookie names, title, generator meta, body, error page body, status, favicon hash. */
data class StackSources(
    val headers: List<Pair<String, String>>, val cookies: List<String>, val title: String?, val generator: String?, val body: String,
    val errorBody: String = "", val status: Int? = null, val favicon: Int? = null,
)

/** The outcome of testing a rule: the text the first condition read, and the first capture (`$1`) of the first condition that captured one. */
class StackMatch(val excerpt: String, val capture: String?)

/** A reply to one of the extra GETs a rule may spend. */
class StackReply(val status: Int, val body: ByteArray)
/** One idempotent GET of [path] on the port being identified, no redirects, the body cut at [maxBytes]; null when nothing usable came back. */
fun interface StackGet { suspend fun get(path: String, maxBytes: Int): StackReply? }

class StackHit(val rule: HttpStackRule, val stack: String, val version: String?, val confirmedAt: String?)
class StackResult(val hits: List<StackHit>, val hard: StackHit?, val pathsSpent: Int, val faviconFetched: Boolean) {
    companion object { val NONE = StackResult(emptyList(), null, 0, false) }
}

object HttpStacks {
    const val MAX_PATHS = 3
    const val FAVICON_PATH = "/favicon.ico"
    const val FAVICON_MAX_BYTES = 64 * 1024
    private const val EXCERPT_CHARS = 40
    private const val BODY_CAP = 64 * 1024
    private val META_TAG = Regex("<meta\\b[^>]{0,500}>", RegexOption.IGNORE_CASE)
    private val GENERATOR_NAME = Regex("name\\s*=\\s*[\"']generator[\"']", RegexOption.IGNORE_CASE)
    private val GENERATOR_CONTENT = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** The page's generator meta content: a bounded scan per meta tag, so a hostile page cannot make the match backtrack over the body. */
    fun generator(body: String): String? =
        META_TAG.findAll(body).map { it.value }.firstOrNull { GENERATOR_NAME.containsMatchIn(it) }
            ?.let { GENERATOR_CONTENT.find(it)?.groupValues?.get(1)?.trim()?.ifEmpty { null } }

    private fun candidates(where: String, s: StackSources): List<String> = when {
        where == "title" -> listOfNotNull(s.title)
        where == "generator" -> listOfNotNull(s.generator)
        where == "body" -> listOf(s.body)
        where == "errorBody" -> listOf(s.errorBody)
        where == "cookie" -> s.cookies
        where == "status" -> listOfNotNull(s.status?.toString())
        where == HttpStackRules.FAVICON -> listOfNotNull(s.favicon?.toString())
        where == "server" -> headerValues(s, "server")
        where == "poweredBy" -> headerValues(s, "x-powered-by")
        where.startsWith("header:") -> headerValues(s, where.removePrefix("header:"))
        else -> emptyList()
    }
    private fun headerValues(s: StackSources, name: String) = s.headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /** Tests every condition of [rule] (all must hold); with [skipFavicon] a favicon condition is not tested. Null when one fails. */
    fun test(rule: HttpStackRule, s: StackSources, skipFavicon: Boolean = false): StackMatch? {
        var excerpt: String? = null; var capture: String? = null
        for (c in rule.all) {
            if (skipFavicon && c.isFavicon) continue
            val exact = c.where == "status" || c.isFavicon
            var read: String? = null; var group: String? = null
            for (cand in candidates(c.where, s)) {
                val m = (if (exact) c.regex.matchEntire(cand) else c.regex.find(cand)) ?: continue
                read = cand; group = if (m.groups.size > 1) m.groups[1]?.value?.takeIf { it.isNotEmpty() } else null
                break
            }
            if (read == null) return null
            if (excerpt == null) excerpt = read.take(EXCERPT_CHARS)
            if (capture == null) capture = group
        }
        return StackMatch(excerpt.orEmpty(), capture)
    }

    /** `$1` replaced by the capture (empty when none), trimmed; null when nothing is left. */
    fun expand(template: String?, capture: String?): String? =
        template?.replace("\$1", capture.orEmpty())?.trim()?.ifEmpty { null }

    /** The sources a rule's sample (or negativeSample) stands for: its text is offered to every condition, `status` and `favicon` need their own key. */
    fun sourcesFor(rule: HttpStackRule, sample: StackSample): StackSources {
        fun at(where: String) = sample.textFor(where)
        val headers = rule.all.filter { it.where == "server" || it.where == "poweredBy" || it.where.startsWith("header:") }.mapNotNull { c ->
            at(c.where)?.let { t -> (if (c.where == "server") "Server" else if (c.where == "poweredBy") "X-Powered-By" else c.where.removePrefix("header:")) to t }
        }
        return StackSources(headers, listOfNotNull(at("cookie")), at("title"), at("generator"), at("body").orEmpty(), at("errorBody").orEmpty(),
            sample.byWhere["status"]?.toIntOrNull(), sample.byWhere[HttpStackRules.FAVICON]?.toIntOrNull())
    }

    /** What a path-only rule's sample stands for: the sample text is the body the first path answers with. */
    fun pathReplyFor(rule: HttpStackRule, sample: StackSample): StackReply? {
        val p = rule.paths.firstOrNull() ?: return null
        val text = sample.single ?: sample.byWhere[p.path] ?: return null
        return StackReply(p.status, text.toByteArray(Charsets.UTF_8))
    }

    fun passes(p: StackPath, r: StackReply): Boolean =
        r.status == p.status && (p.body == null || p.body.containsMatchIn(String(r.body, Charsets.ISO_8859_1)))

    /**
     * The HTTP tier's stack pass (spec 5). Rules whose cheap conditions hit are hits, in file order; the favicon is fetched once, only
     * when a rule's other conditions hold and it has a favicon condition. Then the paths of the hit rules are spent to confirm them, and
     * only when no hard rule has hit and the port is HTTP the path-only rules are tried in file order, until [MAX_PATHS] GETs are spent.
     * [canSpend] lets the caller stop on a spent wall or budget. The first hard hit in file order is the stack.
     */
    suspend fun run(rules: List<HttpStackRule>, base: StackSources, isHttp: Boolean, get: StackGet, canSpend: () -> Boolean = { true }): StackResult {
        var spent = 0
        var faviconFetched = false
        var favicon: Int? = null
        val cache = HashMap<String, StackReply?>()
        suspend fun fetch(path: String): StackReply? {
            if (path in cache) return cache[path]
            if (spent >= MAX_PATHS || !canSpend()) return null
            spent++
            return get.get(path, BODY_CAP).also { cache[path] = it }
        }
        suspend fun faviconHash(): Int? {
            if (faviconFetched) return favicon
            if (!canSpend()) return null
            faviconFetched = true
            val r = get.get(FAVICON_PATH, FAVICON_MAX_BYTES)
            favicon = r?.takeIf { it.status == 200 && it.body.isNotEmpty() }?.let { Favicon.shodanHash(it.body.copyOf(minOf(it.body.size, FAVICON_MAX_BYTES))) }
            return favicon
        }

        class Found(val index: Int, val rule: HttpStackRule, val match: StackMatch?, var confirmedAt: String? = null)
        val found = ArrayList<Found>()
        rules.forEachIndexed { i, rule ->
            if (rule.pathOnly) return@forEachIndexed
            var m = test(rule, base, skipFavicon = true) ?: return@forEachIndexed
            if (rule.needsFavicon) {
                val h = faviconHash() ?: return@forEachIndexed
                m = test(rule, base.copy(favicon = h)) ?: return@forEachIndexed
            }
            found += Found(i, rule, m)
        }
        // Confirmation: the paths of rules that already hit, in file order.
        for (f in found) {
            for (p in f.rule.paths) {
                val r = fetch(p.path) ?: continue
                if (passes(p, r)) { f.confirmedAt = p.path; break }
            }
        }
        // Path-only rules: only on an HTTP port with no hard hit so far.
        if (isHttp && found.none { it.rule.hard }) {
            outer@ for ((i, rule) in rules.withIndex()) {
                if (!rule.pathOnly) continue
                for (p in rule.paths) {
                    if (spent >= MAX_PATHS && p.path !in cache) break@outer
                    val r = fetch(p.path) ?: continue
                    if (passes(p, r)) { found += Found(i, rule, null, p.path); break }
                }
            }
        }
        val hits = found.sortedBy { it.index }.map { f ->
            StackHit(f.rule, expand(f.rule.stack, f.match?.capture).orEmpty().ifEmpty { f.rule.stack }, expand(f.rule.version, f.match?.capture), f.confirmedAt)
        }
        return StackResult(hits, hits.firstOrNull { it.rule.hard }, spent, faviconFetched)
    }
}
