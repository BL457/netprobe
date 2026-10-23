package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.*
import java.net.Inet4Address
import java.net.InetAddress

object DirBruteTestSupport {
    class CollectingProgress : ToolProgress {
        val findings = mutableListOf<Finding>()
        var lastDone = 0; var lastTotal: Int? = null; var lastFound = 0; var lastErrors = 0
        override fun update(done: Int, total: Int?, found: Int, errors: Int) { lastDone = done; lastTotal = total; lastFound = found; lastErrors = errors }
        override suspend fun finding(f: Finding) { findings += f }
        val options = mutableMapOf<String, String>()
        /** The same optionsJson the runner would keep, so a test can check each value's JSON type. */
        var optionsJson = "{}"
        override suspend fun option(key: String, value: String) { options[key] = value; optionsJson = ToolOptionsJson.withOption(optionsJson, key, value) }
        override suspend fun option(key: String, value: Int) { options[key] = value.toString(); optionsJson = ToolOptionsJson.withOption(optionsJson, key, value) }
        fun paths() = findings.filter { it.kind == FindingKind.PATH }.map { it.value }
    }
    /** Never pauses, never rate-limits; [epoch] lets a test simulate a pause that began during a request. */
    class TestControl : RunControl {
        var epoch = 0
        override suspend fun gate() {}
        override val pauseEpoch: Int get() = epoch
        override fun excused(startEpoch: Int) = startEpoch != epoch
    }
    fun target() = ToolTarget(7, InetAddress.getByName("10.0.3.9") as Inet4Address, 5000, tls = false, serverName = null)
    fun resp(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        HttpResult.Ok(HttpResponse(status, "x", listOf("Content-Length" to body.length.toString()) + headers, body.toByteArray(), false, body.length.toLong()))
    fun sized(status: Int, size: Int) = HttpResult.Ok(HttpResponse(status, "x", listOf("Content-Length" to "$size"), ByteArray(size), false, size.toLong()))
    fun tool(exchange: Exchange, words: List<String>, count: Int = words.size): DirBruteforceTool = DirBruteforceTool(
        exchangeFor = { _, _ -> exchange },
        resolve = { LoadedWordlist("builtin:common-short", "common-short", count) { collect -> words.forEach { collect(it) } } },
        random = { "abcdef012345" }, clock = { 0L })
    fun options(o: DirBruteOptions) = DirBruteInputs.of(o, "common-short", 0)
}
