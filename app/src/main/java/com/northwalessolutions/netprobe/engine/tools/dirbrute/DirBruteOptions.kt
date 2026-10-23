package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.serialization.Serializable

@Serializable enum class DirMethod { HEAD_THEN_GET, GET_ONLY }

@Serializable
data class DirBruteOptions(
    val wordlistId: String = DEFAULT_WORDLIST,
    val extensions: List<String> = emptyList(),
    val method: DirMethod = DirMethod.HEAD_THEN_GET,
    val concurrency: Int = 8,
    val timeoutS: Int = 3,
    val statusCodes: List<Int> = listOf(200, 204, 301, 302, 307, 401, 403),
    val baseline: Boolean = true,
    val recursive: Boolean = false,
    val maxDepth: Int = 2,
) {
    fun coerced(): DirBruteOptions = copy(concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY), timeoutS = timeoutS.coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S),
        maxDepth = maxDepth.coerceIn(MIN_DEPTH, MAX_DEPTH))
    companion object {
        const val DEFAULT_WORDLIST = "builtin:common-short"
        const val MIN_CONCURRENCY = 1; const val MAX_CONCURRENCY = 16; const val MIN_TIMEOUT_S = 1; const val MAX_TIMEOUT_S = 10
        const val MIN_DEPTH = 1; const val MAX_DEPTH = 4
        const val MAX_EXTENSIONS = 8; const val MIN_STATUS = 100; const val MAX_STATUS = 599
    }
}

/** Which text-field rule failed; the options sheet maps each to its ToolCopy message (spec 5.1, 9). */
enum class FieldError { EXT_CHARS, EXT_TOO_MANY, CODE_RANGE, CODE_EMPTY }
sealed interface Parsed<out T> { data class Ok<T>(val value: T) : Parsed<T>; data class Bad(val error: FieldError) : Parsed<Nothing> }

object DirBruteParse {
    private val EXT = Regex("^[A-Za-z0-9]{1,8}$")
    fun extensions(text: String): Parsed<List<String>> {
        val parts = text.split(',').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }
        if (parts.any { !EXT.matches(it) }) return Parsed.Bad(FieldError.EXT_CHARS)
        val distinct = parts.distinct()
        if (distinct.size > DirBruteOptions.MAX_EXTENSIONS) return Parsed.Bad(FieldError.EXT_TOO_MANY)
        return Parsed.Ok(distinct)
    }
    fun statusCodes(text: String): Parsed<List<Int>> {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Parsed.Bad(FieldError.CODE_EMPTY)
        val codes = parts.map { it.toIntOrNull() }
        if (codes.any { it == null || it !in DirBruteOptions.MIN_STATUS..DirBruteOptions.MAX_STATUS }) return Parsed.Bad(FieldError.CODE_RANGE)
        return Parsed.Ok(codes.filterNotNull().distinct())
    }
}
