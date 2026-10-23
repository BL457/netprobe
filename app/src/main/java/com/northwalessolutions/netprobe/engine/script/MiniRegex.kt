package com.northwalessolutions.netprobe.engine.script

/**
 * A small backtracking regular expression engine with a step limit (G4). java.util.regex cannot be limited on Android, where the input is
 * handed to a native engine as a copy, so ctx.regex runs on this instead. Syntax is the common JavaScript subset: literals, `.`, classes
 * `[a-z]`, `\d \w \s \b` and their negations, `^ $`, groups `( )`, `(?: )`, lookahead `(?= )` `(?! )`, alternation `|`, quantifiers
 * `* + ? {n} {n,} {n,m}` (each with a lazy `?` form) and back references `\1` to `\9`. Flags: i (ignore case), m (multiline), s (dot matches newline).
 */
internal class MiniRegex private constructor(private val root: Node, val groupCount: Int, private val flags: Int) {
    class SyntaxError(message: String) : RuntimeException(message, null, false, false)
    class StepLimit : RuntimeException(null, null, false, false)
    class Match(val starts: IntArray, val ends: IntArray) {
        val start: Int get() = starts[0]
        val end: Int get() = ends[0]
    }

    /** Shared counter so one exec, or one replace over many matches, has a single budget. */
    class Steps(private val max: Long, private val tick: () -> Unit = {}) {
        private var used = 0L
        fun step() {
            if (++used > max) throw StepLimit()
            if (used and TICK_MASK == 0L) tick()
        }
        private companion object { const val TICK_MASK = 0xffffL }
    }

    private sealed interface Node
    private class Lit(val c: Char) : Node
    private object AnyChar : Node
    private class CharSet(val ranges: List<IntRange>, val classes: List<Char>, val negated: Boolean) : Node
    private object LineStart : Node
    private object LineEnd : Node
    private class WordBoundary(val negated: Boolean) : Node
    private class Group(val index: Int, val body: Node) : Node
    private class Cat(val parts: List<Node>) : Node
    private class Alt(val options: List<Node>) : Node
    private class Repeat(val body: Node, val min: Int, val max: Int, val greedy: Boolean) : Node
    private class Look(val body: Node, val positive: Boolean) : Node
    private class BackRef(val index: Int) : Node
    private object Empty : Node

    fun find(text: CharSequence, from: Int, steps: Steps): Match? {
        var start = from
        while (start <= text.length) {
            val run = Run(text, steps)
            val end = run.matchAt(start)
            if (end >= 0) { run.starts[0] = start; run.ends[0] = end; return Match(run.starts, run.ends) }
            start++
            steps.step()
        }
        return null
    }

    private inner class Run(val text: CharSequence, val steps: Steps) {
        val starts = IntArray(groupCount + 1) { -1 }
        val ends = IntArray(groupCount + 1) { -1 }
        private val ignoreCase = flags and I != 0
        private val multiline = flags and M != 0
        private val dotAll = flags and S != 0

        fun matchAt(start: Int): Int {
            var result = -1
            try { match(root, start) { j -> result = j; true } } catch (_: StackOverflowError) { throw StepLimit() }
            return result
        }

        private fun same(a: Char, b: Char) = a == b || (ignoreCase && (Character.toLowerCase(a) == Character.toLowerCase(b) || Character.toUpperCase(a) == Character.toUpperCase(b)))
        private fun isWord(i: Int) = i in 0 until text.length && text[i].let { it == '_' || (it.code < ASCII && it.isLetterOrDigit()) }
        private fun isLineBreak(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '

        private fun classHit(kind: Char, c: Char): Boolean = when (kind) {
            'd' -> c in '0'..'9'
            'D' -> c !in '0'..'9'
            'w' -> c == '_' || (c.code < ASCII && c.isLetterOrDigit())
            'W' -> !(c == '_' || (c.code < ASCII && c.isLetterOrDigit()))
            's' -> c.isWhitespace() || c == ' ' || c == '﻿'
            'S' -> !(c.isWhitespace() || c == ' ' || c == '﻿')
            else -> false
        }

        private fun single(n: Node, c: Char): Boolean = when (n) {
            is Lit -> same(n.c, c)
            AnyChar -> dotAll || !isLineBreak(c)
            is CharSet -> {
                val hit = n.ranges.any { c.code in it || (ignoreCase && (Character.toLowerCase(c).code in it || Character.toUpperCase(c).code in it)) } || n.classes.any { classHit(it, c) }
                hit != n.negated
            }
            else -> false
        }

        private fun isSingle(n: Node) = n is Lit || n === AnyChar || n is CharSet

        fun match(n: Node, i: Int, k: (Int) -> Boolean): Boolean {
            steps.step()
            when (n) {
                Empty -> return k(i)
                is Lit, AnyChar, is CharSet -> return i < text.length && single(n, text[i]) && k(i + 1)
                LineStart -> return (i == 0 || (multiline && isLineBreak(text[i - 1]))) && k(i)
                LineEnd -> return (i == text.length || (multiline && isLineBreak(text[i]))) && k(i)
                is WordBoundary -> return ((isWord(i - 1) != isWord(i)) != n.negated) && k(i)
                is Cat -> return cat(n.parts, 0, i, k)
                is Alt -> { for (o in n.options) if (match(o, i, k)) return true; return false }
                is Group -> {
                    val g = n.index
                    return match(n.body, i) { j ->
                        val ps = starts[g]; val pe = ends[g]
                        starts[g] = i; ends[g] = j
                        if (k(j)) true else { starts[g] = ps; ends[g] = pe; false }
                    }
                }
                is Look -> {
                    val savedS = starts.copyOf(); val savedE = ends.copyOf()
                    val hit = match(n.body, i) { true }
                    if (!n.positive || !hit) { savedS.copyInto(starts); savedE.copyInto(ends) }
                    return if (hit == n.positive) k(i) else false
                }
                is BackRef -> {
                    val s = starts[n.index]; val e = ends[n.index]
                    if (s < 0) return k(i)
                    val len = e - s
                    if (i + len > text.length) return false
                    for (x in 0 until len) if (!same(text[s + x], text[i + x])) return false
                    return k(i + len)
                }
                is Repeat -> return repeat(n, i, k)
            }
        }

        private fun cat(parts: List<Node>, idx: Int, i: Int, k: (Int) -> Boolean): Boolean =
            if (idx == parts.size) k(i) else match(parts[idx], i) { j -> cat(parts, idx + 1, j, k) }

        private fun repeat(n: Repeat, i: Int, k: (Int) -> Boolean): Boolean {
            if (isSingle(n.body)) {
                // Plain loop over one-character atoms: no recursion however long the text is.
                var count = 0
                val limit = if (n.max == INF) Int.MAX_VALUE else n.max
                if (n.greedy) {
                    while (count < limit && i + count < text.length && single(n.body, text[i + count])) { count++; if (count and COUNT_STEP_MASK == 0) steps.step() }
                    var c = count
                    while (c >= n.min) { steps.step(); if (k(i + c)) return true; c-- }
                    return false
                }
                var c = 0
                while (c < n.min) { if (!(i + c < text.length && single(n.body, text[i + c]))) return false; c++ }
                while (true) {
                    steps.step()
                    if (k(i + c)) return true
                    if (c >= limit || !(i + c < text.length && single(n.body, text[i + c]))) return false
                    c++
                }
            }
            return rep(n, i, 0, k)
        }

        private fun rep(n: Repeat, i: Int, count: Int, k: (Int) -> Boolean): Boolean {
            steps.step()
            val more = { j: Int -> if (j == i && count >= n.min) false else rep(n, j, count + 1, k) }
            if (n.greedy) {
                if ((n.max == INF || count < n.max) && match(n.body, i, more)) return true
                return count >= n.min && k(i)
            }
            if (count >= n.min && k(i)) return true
            return (n.max == INF || count < n.max) && match(n.body, i, more)
        }
    }

    private class Parser(private val src: String) {
        var pos = 0
        var groups = 0
        private var depth = 0

        fun parse(): Node {
            val n = alt()
            if (pos < src.length) throw SyntaxError("unmatched )")
            return n
        }

        private fun alt(): Node {
            if (++depth > MAX_DEPTH) throw SyntaxError("too deep")
            val options = ArrayList<Node>()
            options += cat()
            while (pos < src.length && src[pos] == '|') { pos++; options += cat() }
            depth--
            return if (options.size == 1) options[0] else Alt(options)
        }

        private fun cat(): Node {
            val parts = ArrayList<Node>()
            while (pos < src.length && src[pos] != '|' && src[pos] != ')') parts += quantified()
            return when (parts.size) { 0 -> Empty; 1 -> parts[0]; else -> Cat(parts) }
        }

        private fun quantified(): Node {
            val atom = atom()
            if (pos >= src.length) return atom
            var min: Int; var max: Int
            when (src[pos]) {
                '*' -> { min = 0; max = INF; pos++ }
                '+' -> { min = 1; max = INF; pos++ }
                '?' -> { min = 0; max = 1; pos++ }
                '{' -> {
                    val close = src.indexOf('}', pos)
                    val body = if (close > pos) src.substring(pos + 1, close) else return atom
                    val m = BRACES.matchEntire(body) ?: return atom
                    min = m.groupValues[1].toIntOrNull()?.takeIf { it <= MAX_COUNT } ?: throw SyntaxError("count too large")
                    max = when { !body.contains(',') -> min; m.groupValues[2].isEmpty() -> INF; else -> m.groupValues[2].toIntOrNull()?.takeIf { it <= MAX_COUNT } ?: throw SyntaxError("count too large") }
                    if (max != INF && max < min) throw SyntaxError("bad range")
                    pos = close + 1
                }
                else -> return atom
            }
            if (atom is LineStart || atom is LineEnd || atom is WordBoundary || atom is Look) throw SyntaxError("nothing to repeat")
            var greedy = true
            if (pos < src.length && src[pos] == '?') { greedy = false; pos++ }
            return Repeat(atom, min, max, greedy)
        }

        private fun atom(): Node {
            val c = src[pos++]
            return when (c) {
                '.' -> AnyChar
                '^' -> LineStart
                '$' -> LineEnd
                '(' -> group()
                '[' -> set()
                '\\' -> escape(inSet = false)
                '*', '+', '?' -> throw SyntaxError("nothing to repeat")
                else -> Lit(c)
            }
        }

        private fun group(): Node {
            if (src.startsWith("?:", pos)) { pos += 2; return closeGroup(null) { Group(-1, it) } }
            if (src.startsWith("?=", pos)) { pos += 2; return closeGroup(null) { Look(it, true) } }
            if (src.startsWith("?!", pos)) { pos += 2; return closeGroup(null) { Look(it, false) } }
            if (src.startsWith("?", pos)) throw SyntaxError("unsupported group")
            val index = ++groups
            if (index > MAX_GROUPS) throw SyntaxError("too many groups")
            return closeGroup(index) { Group(index, it) }
        }

        private fun closeGroup(@Suppress("UNUSED_PARAMETER") index: Int?, make: (Node) -> Node): Node {
            val body = alt()
            if (pos >= src.length || src[pos] != ')') throw SyntaxError("missing )")
            pos++
            val node = make(body)
            return if (node is Group && node.index == -1) body else node
        }

        private fun set(): Node {
            var negated = false
            if (pos < src.length && src[pos] == '^') { negated = true; pos++ }
            val ranges = ArrayList<IntRange>(); val classes = ArrayList<Char>()
            var first = true
            while (true) {
                if (pos >= src.length) throw SyntaxError("missing ]")
                if (src[pos] == ']' && !first) { pos++; break }
                if (src[pos] == ']' && first) { pos++; break }     // "[]" matches nothing, "[^]" anything
                first = false
                val lo = setAtom(classes) ?: continue
                if (pos + 1 < src.length && src[pos] == '-' && src[pos + 1] != ']') {
                    pos++
                    val hi = setAtom(classes) ?: throw SyntaxError("bad range")
                    if (hi < lo) throw SyntaxError("bad range")
                    ranges += lo..hi
                } else ranges += lo..lo
            }
            return CharSet(ranges, classes, negated)
        }

        /** One class member; null when it was a class escape such as \d (added to [classes]). */
        private fun setAtom(classes: MutableList<Char>): Int? {
            val c = src[pos++]
            if (c != '\\') return c.code
            if (pos >= src.length) throw SyntaxError("bad escape")
            val e = src[pos]
            if (e in "dDwWsS") { pos++; classes += e; return null }
            return when (val n = escape(inSet = true)) { is Lit -> n.c.code; else -> throw SyntaxError("bad escape") }
        }

        private fun escape(inSet: Boolean): Node {
            if (pos >= src.length) throw SyntaxError("bad escape")
            val e = src[pos++]
            return when (e) {
                'd', 'D', 'w', 'W', 's', 'S' -> CharSet(emptyList(), listOf(e), false)
                'b' -> if (inSet) Lit('\b') else WordBoundary(false)
                'B' -> if (inSet) throw SyntaxError("bad escape") else WordBoundary(true)
                'n' -> Lit('\n'); 'r' -> Lit('\r'); 't' -> Lit('\t'); 'f' -> Lit('\u000c'); 'v' -> Lit('\u000b'); '0' -> Lit('\u0000')
                'x' -> Lit(hex(2).toChar())
                'u' -> Lit(hex(4).toChar())
                in '1'..'9' -> if (inSet) Lit(e) else BackRef(e - '0').also { if (e - '0' > MAX_GROUPS) throw SyntaxError("bad reference") }
                else -> if (e.isLetter() && e.code < ASCII) throw SyntaxError("bad escape") else Lit(e)
            }
        }

        private fun hex(n: Int): Int {
            if (pos + n > src.length) throw SyntaxError("bad escape")
            val v = src.substring(pos, pos + n).toIntOrNull(16) ?: throw SyntaxError("bad escape")
            pos += n
            return v
        }
    }

    companion object {
        /** A long single-character run is charged to the step budget once per this many characters, so it cannot be free. */
        private const val COUNT_STEP_MASK = 0xfff
        const val I = 1
        const val M = 2
        const val S = 4
        private const val INF = Int.MAX_VALUE
        private const val ASCII = 128
        private const val MAX_DEPTH = 40
        private const val MAX_COUNT = 100_000
        private const val MAX_GROUPS = 99
        private val BRACES = Regex("(\\d+)(?:,(\\d*))?")

        fun flagsOf(text: String?): Int {
            var f = 0
            for (c in text.orEmpty()) f = f or when (c) { 'i' -> I; 'm' -> M; 's' -> S; else -> throw SyntaxError("flag $c") }
            return f
        }

        fun compile(pattern: String, flags: Int): MiniRegex {
            val p = Parser(pattern)
            val root = p.parse()
            return MiniRegex(root, p.groups, flags)
        }
    }
}
