package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.tools.ChoiceOption
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

/**
 * The directory brute-force tool's declared inputs (tools platform spec D8) and the bridge to [DirBruteOptions], the typed bundle the
 * tool runs on. The input names are the historical optionsJson field names (wordlist is written as wordlistId, wordlistName and
 * wordlistEntries), so history and export still read old and new runs alike.
 */
object DirBruteInputs {
    const val WORDLIST = "wordlist"
    const val EXTENSIONS = "extensions"
    const val METHOD = "method"
    const val CONCURRENCY = "concurrency"
    const val TIMEOUT_S = "timeoutS"
    const val STATUS_CODES = "statusCodes"
    const val BASELINE = "baseline"
    const val METHOD_USED = "methodUsed"

    /** The codes the form offers; a run recorded from the older sheet may carry others, and the tool still honours them. */
    val STATUS_OPTIONS: List<Int> = listOf(200, 201, 202, 204, 206, 301, 302, 303, 307, 308, 400, 401, 403, 405, 429, 500, 502, 503)

    val specs: List<InputSpec> = listOf(
        InputSpec.Wordlist(WORDLIST, ToolCopy.WORDLIST, default = DirBruteOptions.DEFAULT_WORDLIST),
        InputSpec.TextList(EXTENSIONS, ToolCopy.EXTENSIONS, help = ToolCopy.EXTENSIONS_SUPPORT, required = false, placeholder = ToolCopy.EXTENSIONS_PLACEHOLDER,
            maxItems = DirBruteOptions.MAX_EXTENSIONS),
        InputSpec.Choice(METHOD, ToolCopy.METHOD, listOf(ChoiceOption(DirMethod.HEAD_THEN_GET.name, ToolCopy.HEAD_THEN_GET), ChoiceOption(DirMethod.GET_ONLY.name, ToolCopy.GET_ONLY)),
            default = DirMethod.HEAD_THEN_GET.name),
        InputSpec.Number(CONCURRENCY, ToolCopy.CONCURRENCY, default = DirBruteOptions().concurrency, min = DirBruteOptions.MIN_CONCURRENCY, max = DirBruteOptions.MAX_CONCURRENCY),
        InputSpec.Number(TIMEOUT_S, ToolCopy.TIMEOUT, default = DirBruteOptions().timeoutS, min = DirBruteOptions.MIN_TIMEOUT_S, max = DirBruteOptions.MAX_TIMEOUT_S),
        InputSpec.MultiChoice(STATUS_CODES, ToolCopy.REPORT_CODES, STATUS_OPTIONS.map { ChoiceOption(it.toString()) },
            default = DirBruteOptions().statusCodes.map { it.toString() }),
        InputSpec.Bool(BASELINE, ToolCopy.FILTER, help = ToolCopy.FILTER_SUPPORT, default = DirBruteOptions().baseline),
    )

    /** The typed inputs for [o], with the wordlist's display name and entry count as recorded in history. */
    fun of(o: DirBruteOptions, wordlistName: String = "", wordlistEntries: Int = 0): ToolInputs = ToolInputs(linkedMapOf(
        WORDLIST to WordlistRef(o.wordlistId, wordlistName, wordlistEntries),
        EXTENSIONS to o.extensions,
        METHOD to o.method.name,
        CONCURRENCY to o.concurrency,
        TIMEOUT_S to o.timeoutS,
        STATUS_CODES to o.statusCodes.map { it.toString() },
        BASELINE to o.baseline,
    ))

    /** The options a run uses: an absent or unreadable value takes its default, and the numbers are clamped as before. */
    fun options(inputs: ToolInputs): DirBruteOptions {
        val d = DirBruteOptions()
        val extensions = (DirBruteParse.extensions(inputs.list(EXTENSIONS).joinToString(",")) as? Parsed.Ok)?.value ?: emptyList()
        val codes = inputs.list(STATUS_CODES).mapNotNull { it.toIntOrNull() }.ifEmpty { d.statusCodes }
        return DirBruteOptions(
            wordlistId = if (inputs.has(WORDLIST)) inputs.wordlist(WORDLIST).id else d.wordlistId,
            extensions = extensions,
            method = DirMethod.entries.firstOrNull { it.name == inputs.choice(METHOD) } ?: d.method,
            concurrency = (inputs.values[CONCURRENCY] as? Int) ?: d.concurrency,
            timeoutS = (inputs.values[TIMEOUT_S] as? Int) ?: d.timeoutS,
            statusCodes = codes,
            baseline = if (inputs.has(BASELINE)) inputs.bool(BASELINE) else d.baseline,
        ).coerced()
    }

    /** The inputs as optionsJson records them: the historical field order, ints for the codes, and methodUsed beside method. */
    fun recorded(inputs: ToolInputs): ToolInputs {
        val o = options(inputs)
        val list = if (inputs.has(WORDLIST)) inputs.wordlist(WORDLIST) else WordlistRef(o.wordlistId)
        return ToolInputs(linkedMapOf(
            WORDLIST to list, EXTENSIONS to o.extensions, METHOD to o.method.name, METHOD_USED to o.method.name,
            CONCURRENCY to o.concurrency, TIMEOUT_S to o.timeoutS, STATUS_CODES to o.statusCodes, BASELINE to o.baseline,
        ))
    }

    /** Field errors for the rules the generic input types cannot hold: extension characters and count, and at least one status code. */
    fun check(inputs: ToolInputs): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        (DirBruteParse.extensions(inputs.list(EXTENSIONS).joinToString(",")) as? Parsed.Bad)?.let { out[EXTENSIONS] = message(it.error) }
        if (inputs.has(STATUS_CODES) && inputs.list(STATUS_CODES).isEmpty()) out[STATUS_CODES] = message(FieldError.CODE_EMPTY)
        return out
    }

    fun message(e: FieldError): String = when (e) {
        FieldError.EXT_CHARS -> ToolCopy.ERR_EXT_CHARS
        FieldError.EXT_TOO_MANY -> ToolCopy.ERR_EXT_TOO_MANY
        FieldError.CODE_RANGE -> ToolCopy.ERR_CODE_RANGE
        FieldError.CODE_EMPTY -> ToolCopy.ERR_CODE_EMPTY
    }
}
