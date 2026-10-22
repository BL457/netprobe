package com.northwalessolutions.netprobe.ui.detail

/** Detail strings added in Phase 3A (spec 7); older Detail wording stays in DetailText. Every string has a CopyTableTest template. */
object DetailCopy {
    fun runs(software: String) = "Runs $software"
    const val MORE_OPTIONS = "More options"
    const val EDIT_DEVICE = "Edit device"
    const val REMOVE_DEVICE = "Remove device"
    fun editDescription(name: String) = "Edit device, $name"
    const val TRY_AS = "Try as"
    fun tryAsDescription(ip: String, port: Int, scheme: String) = "Open $ip:$port as $scheme"
    const val SCAN_BIND_FAILED = "Scan skipped: could not bind to Wi-Fi"
    /**
     * The port row's second text (Phase 3E spec 7): the stack when one is named, else the product, then the version and the detail. When the
     * stack and the product are different things, the product keeps its own version beside the stack: "Home Assistant (nginx 1.25.3) 200 OK".
     */
    fun portVersion(stack: String?, product: String?, version: String?, detail: String?): String {
        val other = product != null && stack != null && !product.equals(stack, ignoreCase = true)
        val parts = if (stack == null || !other) listOfNotNull(stack ?: product, version, detail)
            else listOf(stack, "(" + listOfNotNull(product, version).joinToString(" ") + ")") + listOfNotNull(detail)
        return parts.joinToString(" ").trim()
    }
    fun identifying(ports: Int) = if (ports == 1) "identifying 1 port" else "identifying $ports ports"
    fun setByYou(parts: String) = "Set by you: $parts"
    private val NUMBER_WORDS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    /** The refusal when [n] port scans already run (3D spec 3.3). [n] is spelled as a word, so changing MAX_PORT_SCANS cannot leave "Two" behind. */
    fun portScansTooMany(n: Int): String {
        val word = NUMBER_WORDS.getOrNull(n) ?: n.toString()
        return if (n == 1) "$word port scan is already running, stop it in Scans" else "$word port scans are already running, stop one in Scans"
    }
}
