package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy

/**
 * ctx.regex (G4): [MiniRegex] with a step budget per call. A pattern that backtracks past [maxSteps] is stopped with a catchable error
 * instead of burning the CPU, which RegExp in a script could not be stopped from doing. [alive] runs now and then so a Stop or a budget
 * ends a long match too.
 */
internal class HostRegex(private val maxSteps: Long, private val alive: () -> Unit) {
    private fun compile(pattern: String, flags: String?): MiniRegex {
        if (pattern.length > ScriptCaps.MAX_REGEX_PATTERN_CHARS) throw ScriptHostError(ScriptCopy.REGEX_PATTERN_TOO_LONG)
        val bits = try { MiniRegex.flagsOf(flags) } catch (e: MiniRegex.SyntaxError) { throw ScriptHostError(ScriptCopy.regexFlag(e.message?.removePrefix("flag ")?.firstOrNull() ?: '?')) }
        try { return MiniRegex.compile(pattern, bits) } catch (e: MiniRegex.SyntaxError) { throw ScriptHostError(ScriptCopy.REGEX_INVALID) }
    }

    private fun <T> guarded(text: String, block: (steps: MiniRegex.Steps) -> T): T {
        if (text.length > ScriptCaps.MAX_STRING_CHARS) throw ScriptHostError(ScriptCopy.STRING_TOO_LONG)
        try { return block(MiniRegex.Steps(maxSteps, alive)) }
        catch (_: MiniRegex.StepLimit) { throw ScriptHostError(ScriptCopy.REGEX_TOO_SLOW) }
        catch (_: StackOverflowError) { throw ScriptHostError(ScriptCopy.REGEX_TOO_SLOW) }
    }

    fun test(pattern: String, text: String, flags: String?): Boolean {
        val re = compile(pattern, flags)
        return guarded(text) { re.find(text, 0, it) != null }
    }

    fun exec(pattern: String, text: String, flags: String?): Map<String, Any?>? {
        val re = compile(pattern, flags)
        return guarded(text) { steps -> re.find(text, 0, steps)?.let { describe(it, text) } }
    }

    fun matchAll(pattern: String, text: String, flags: String?, limit: Int): List<Map<String, Any?>> {
        val re = compile(pattern, flags)
        return guarded(text) { steps ->
            val out = ArrayList<Map<String, Any?>>()
            var from = 0
            while (out.size < limit && from <= text.length) {
                val m = re.find(text, from, steps) ?: break
                out += describe(m, text)
                from = if (m.end == m.start) m.end + 1 else m.end
            }
            out
        }
    }

    /** Replaces every match. In [replacement], `$1` to `$9` insert a group, `$&` the whole match and `$$` a dollar sign. */
    fun replace(pattern: String, text: String, replacement: String, flags: String?): String {
        val re = compile(pattern, flags)
        return guarded(text) { steps ->
            val sb = StringBuilder()
            var from = 0; var last = 0
            while (from <= text.length) {
                val m = re.find(text, from, steps) ?: break
                sb.append(text, last, m.start)
                expand(sb, replacement, m, text)
                if (sb.length > ScriptCaps.MAX_STRING_CHARS) throw ScriptHostError(ScriptCopy.STRING_TOO_LONG)
                last = m.end
                from = if (m.end == m.start) m.end + 1 else m.end
            }
            sb.append(text, last, text.length)
            if (sb.length > ScriptCaps.MAX_STRING_CHARS) throw ScriptHostError(ScriptCopy.STRING_TOO_LONG)
            sb.toString()
        }
    }

    fun split(pattern: String, text: String, limit: Int): List<String> {
        val re = compile(pattern, null)
        return guarded(text) { steps ->
            val out = ArrayList<String>()
            var from = 0; var last = 0
            while (out.size < limit - 1 && from <= text.length) {
                val m = re.find(text, from, steps) ?: break
                if (m.end == m.start) { if (m.start >= text.length) break; from = m.start + 1; if (m.start == last) continue }
                else from = m.end
                out += text.substring(last, m.start)
                last = m.end
            }
            out += text.substring(last)
            out
        }
    }

    private fun expand(sb: StringBuilder, replacement: String, m: MiniRegex.Match, text: String) {
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '$' && i + 1 < replacement.length) {
                val n = replacement[i + 1]
                when {
                    n == '$' -> { sb.append('$'); i += 2; continue }
                    n == '&' -> { sb.append(text, m.start, m.end); i += 2; continue }
                    n in '1'..'9' && (n - '0') < m.starts.size -> {
                        val g = n - '0'
                        if (m.starts[g] >= 0) sb.append(text, m.starts[g], m.ends[g])
                        i += 2; continue
                    }
                }
            }
            sb.append(c); i++
        }
    }

    private fun describe(m: MiniRegex.Match, text: String): Map<String, Any?> = mapOf(
        "match" to text.substring(m.start, m.end), "index" to m.start.toDouble(),
        "groups" to (1 until m.starts.size).map { g -> if (m.starts[g] < 0) null else text.substring(m.starts[g], m.ends[g]) },
    )
}
