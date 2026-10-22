package com.northwalessolutions.netprobe.oui

/**
 * The IEEE registry CSV: Registry, Assignment, Organization Name, Organization Address, with a header line first. Fields may be quoted,
 * hold commas and doubled quotes. The assignment's hex length gives the prefix size: 6 digits is 24 bits (MA-L), 7 is 28 (MA-M), 9 is 36 (MA-S).
 */
object OuiCsv {
    /** Adds every well-formed record of [text] to [builder] and returns how many were added. The first line is the header and is skipped. */
    fun parseInto(text: String, builder: OuiTable.Builder): Int {
        var added = 0
        var first = true
        for (fields in records(text)) {
            if (first) { first = false; continue }
            if (fields.size < MIN_FIELDS) continue
            val assignment = fields[ASSIGNMENT].trim()
            val name = fields[ORGANISATION].trim()
            val bits = when (assignment.length) { 6 -> 24; 7 -> 28; 9 -> 36; else -> continue }
            val prefix = assignment.toLongOrNull(HEX) ?: continue
            if (name.isEmpty()) continue
            builder.add(bits, prefix, name)
            added++
        }
        return added
    }

    /** CSV records as field lists; a quoted field may contain commas, doubled quotes and line breaks. */
    internal fun records(text: String): Sequence<List<String>> = sequence {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' -> quoted = true
                c == ',' -> { fields += field.toString(); field.setLength(0) }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    fields += field.toString(); field.setLength(0)
                    if (fields.size > 1 || fields[0].isNotEmpty()) yield(fields.toList())
                    fields.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) { fields += field.toString(); yield(fields.toList()) }
    }

    private const val HEX = 16
    private const val ASSIGNMENT = 1
    private const val ORGANISATION = 2
    private const val MIN_FIELDS = 3
}
