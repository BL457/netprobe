package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import java.nio.ByteOrder

/**
 * Builds the `ctx` object (spec section 4, G1). Every member is a [HostFn] or a NativeObject made here; nothing is a wrapped Java object.
 * Each function checks the run is still alive, runs inside session.hostCall (the watchdog's stall clock), and returns plain data. The
 * objects and their properties are read-only and sealed, so a script cannot replace `ctx.http.request` for later code in the same run.
 */
internal object HostApi {
    private const val LOCKED = ScriptableObject.READONLY or ScriptableObject.PERMANENT
    private const val MAX_FINDING_KIND = 64
    private const val PORT_MAX = 65_535

    fun build(cx: Context, scope: Scriptable, s: RunSession): Scriptable {
        val net = HostNet(s, s.host.transport, s.host.gate)
        val regex = HostRegex(s.limits.regexSteps) { s.ensureAlive() }
        val ctx = cx.newObject(scope)

        fun group(name: String): Scriptable = cx.newObject(scope).also { ScriptableObject.defineProperty(ctx, name, it, LOCKED) }
        fun fn(target: Scriptable, name: String, arity: Int, body: (Array<Any?>) -> Any?) {
            val f = HostFn("$name", arity, scope) { args -> s.ensureAlive(); s.hostCall { body(args) } }
            ScriptableObject.defineProperty(target, name, f, LOCKED)
        }
        fun text(args: Array<Any?>, i: Int, what: String): String {
            val v = args.getOrNull(i)
            return if (v is CharSequence) JsBridge.string(v) else throw ScriptHostError(ScriptCopy.wrongArgument(what, i + 1, "string"))
        }
        fun bytesArg(args: Array<Any?>, i: Int, what: String): ByteArray = Bytes.fromValue(JsBridge.fromJs(args.getOrNull(i)), what, "argument ${i + 1}")
        fun number(args: Array<Any?>, i: Int, what: String): Double {
            val v = args.getOrNull(i)
            return if (v is Number) v.toDouble() else throw ScriptHostError(ScriptCopy.wrongArgument(what, i + 1, "number"))
        }

        // http
        val http = group("http")
        fn(http, "request", 1) { a -> net.request(Opts.of(a.getOrNull(0), "ctx.http.request")) }
        fn(http, "many", 2) { a ->
            @Suppress("UNCHECKED_CAST")
            val list = JsBridge.fromJs(a.getOrNull(0)) as? List<Any?> ?: throw ScriptHostError(ScriptCopy.wrongArgument("ctx.http.many", 1, "array"))
            @Suppress("UNCHECKED_CAST")
            val opts = list.map { Opts(it as? Map<String, Any?> ?: throw ScriptHostError(ScriptCopy.optionsExpected("ctx.http.many")), "ctx.http.many") }
            net.many(opts, (a.getOrNull(1) as? Number)?.toInt() ?: 1)
        }

        // tcp
        val tcp = group("tcp")
        fn(tcp, "exchange", 1) { a -> net.tcpExchange(Opts.of(a.getOrNull(0), "ctx.tcp.exchange")) }
        fn(tcp, "connect", 1) { a -> handleObject(cx, scope, s, net.tcpConnect(Opts.of(a.getOrNull(0), "ctx.tcp.connect"))) }

        // udp, tls, dns
        val udp = group("udp")
        fn(udp, "exchange", 1) { a -> net.udpExchange(Opts.of(a.getOrNull(0), "ctx.udp.exchange")) }
        fn(udp, "send", 1) { a -> net.udpSend(Opts.of(a.getOrNull(0), "ctx.udp.send")); Unit }
        fn(group("tls"), "certificate", 3) { a ->
            val port = number(a, 1, "ctx.tls.certificate")
            if (port < 1 || port > PORT_MAX || port != Math.floor(port)) throw ScriptHostError(ScriptCopy.outOfRange("ctx.tls.certificate", "port", 1, PORT_MAX))
            net.certificate(text(a, 0, "ctx.tls.certificate"), port.toInt(), (a.getOrNull(2) as? CharSequence)?.let { JsBridge.string(it) })
        }
        val dns = group("dns")
        fn(dns, "lookup", 1) { a -> net.lookup(text(a, 0, "ctx.dns.lookup")) }
        fn(dns, "reverse", 1) { a -> net.reverse(text(a, 0, "ctx.dns.reverse")) }

        // bytes
        val bytes = group("bytes")
        fn(bytes, "fromHex", 1) { a -> Bytes.fromHex(text(a, 0, "ctx.bytes.fromHex")) }
        fn(bytes, "toHex", 1) { a -> Bytes.toHex(bytesArg(a, 0, "ctx.bytes.toHex")) }
        fn(bytes, "fromUtf8", 1) { a -> text(a, 0, "ctx.bytes.fromUtf8").toByteArray(Charsets.UTF_8) }
        fn(bytes, "toUtf8", 1) { a -> Bytes.toUtf8(bytesArg(a, 0, "ctx.bytes.toUtf8")) }
        fn(bytes, "base64", 1) { a -> Bytes.toBase64(bytesArg(a, 0, "ctx.bytes.base64")) }
        fn(bytes, "fromBase64", 1) { a -> Bytes.fromBase64(text(a, 0, "ctx.bytes.fromBase64")) }
        fn(bytes, "md5", 1) { a -> Bytes.digestHex("MD5", bytesArg(a, 0, "ctx.bytes.md5")) }
        fn(bytes, "sha1", 1) { a -> Bytes.digestHex("SHA-1", bytesArg(a, 0, "ctx.bytes.sha1")) }
        fn(bytes, "sha256", 1) { a -> Bytes.digestHex("SHA-256", bytesArg(a, 0, "ctx.bytes.sha256")) }
        fn(bytes, "concat", 2) { a ->
            val out = java.io.ByteArrayOutputStream()
            for (i in a.indices) { out.write(bytesArg(a, i, "ctx.bytes.concat")); if (out.size() > ScriptCaps.MAX_ARRAY_LENGTH) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE) }
            out.toByteArray()
        }
        fn(bytes, "u16be", 1) { a -> Bytes.uint(number(a, 0, "ctx.bytes.u16be"), 2, ByteOrder.BIG_ENDIAN) }
        fn(bytes, "u32be", 1) { a -> Bytes.uint(number(a, 0, "ctx.bytes.u32be"), 4, ByteOrder.BIG_ENDIAN) }
        fn(bytes, "u16le", 1) { a -> Bytes.uint(number(a, 0, "ctx.bytes.u16le"), 2, ByteOrder.LITTLE_ENDIAN) }
        fn(bytes, "u32le", 1) { a -> Bytes.uint(number(a, 0, "ctx.bytes.u32le"), 4, ByteOrder.LITTLE_ENDIAN) }
        fun reader(name: String, width: Int, order: ByteOrder) = fn(bytes, name, 2) { a ->
            Bytes.readUint(bytesArg(a, 0, "ctx.bytes.$name"), (a.getOrNull(1) as? Number)?.toInt() ?: 0, width, order)
        }
        reader("readU16be", 2, ByteOrder.BIG_ENDIAN); reader("readU32be", 4, ByteOrder.BIG_ENDIAN)
        reader("readU16le", 2, ByteOrder.LITTLE_ENDIAN); reader("readU32le", 4, ByteOrder.LITTLE_ENDIAN)

        // regex
        val re = group("regex")
        fun flags(a: Array<Any?>, i: Int): String? = (a.getOrNull(i) as? CharSequence)?.let { JsBridge.string(it) }
        fn(re, "test", 3) { a -> regex.test(text(a, 0, "ctx.regex.test"), text(a, 1, "ctx.regex.test"), flags(a, 2)) }
        fn(re, "exec", 3) { a -> regex.exec(text(a, 0, "ctx.regex.exec"), text(a, 1, "ctx.regex.exec"), flags(a, 2)) }
        fn(re, "matchAll", 4) { a ->
            val limit = ((a.getOrNull(3) as? Number)?.toInt() ?: DEFAULT_MATCHES).coerceIn(1, ScriptCaps.MAX_ARRAY_LENGTH)
            regex.matchAll(text(a, 0, "ctx.regex.matchAll"), text(a, 1, "ctx.regex.matchAll"), flags(a, 2), limit)
        }
        fn(re, "replace", 4) { a -> regex.replace(text(a, 0, "ctx.regex.replace"), text(a, 1, "ctx.regex.replace"), text(a, 2, "ctx.regex.replace"), flags(a, 3)) }
        fn(re, "split", 3) { a ->
            val limit = ((a.getOrNull(2) as? Number)?.toInt() ?: ScriptCaps.MAX_ARRAY_LENGTH).coerceIn(1, ScriptCaps.MAX_ARRAY_LENGTH)
            regex.split(text(a, 0, "ctx.regex.split"), text(a, 1, "ctx.regex.split"), limit)
        }

        // top-level members
        fn(ctx, "wordlist", 3) { a ->
            val name = text(a, 0, "ctx.wordlist")
            val ref = s.inputs[name] as? WordlistRef ?: throw ScriptHostError(ScriptCopy.noWordlistInput(name))
            val offset = ((a.getOrNull(1) as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            val limit = ((a.getOrNull(2) as? Number)?.toInt() ?: ScriptCaps.MAX_ARRAY_LENGTH).coerceIn(1, ScriptCaps.MAX_ARRAY_LENGTH)
            s.blocking { s.host.wordlist(ref, offset, limit) }.take(limit)
        }
        fn(ctx, "devices", 0) { _ ->
            if (s.host.network == NetworkScope.TARGET) throw ScriptHostError(ScriptCopy.DEVICES_NEED_LAN)
            s.chargeCalls(1); s.gateWait()
            s.blocking { s.host.devices() }.take(ScriptDevices.MAX_DEVICES).map { d ->
                mapOf("ip" to d.ip, "mac" to d.mac, "name" to d.name, "vendor" to d.vendor,
                    "ports" to d.ports.map { p -> mapOf("port" to p.port.toDouble(), "service" to p.service, "tls" to p.tls) })
            }
        }
        fn(ctx, "progress", 3) { a ->
            fun count(v: Any?): Int = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() }?.coerceIn(0.0, Int.MAX_VALUE.toDouble())?.toInt() ?: 0
            val total = (a.getOrNull(1) as? Number)?.let { count(it) }
            s.host.progress.update(count(a.getOrNull(0)), total, count(a.getOrNull(2)))
            Unit
        }
        fn(ctx, "finding", 4) { a ->
            val kind = text(a, 0, "ctx.finding").take(MAX_FINDING_KIND)
            val value = a.getOrNull(1).let { if (it is CharSequence) JsBridge.string(it) else ScriptRuntime.toString(it) }
            fun opt(i: Int): String? = a.getOrNull(i)?.takeIf { it !== Undefined.instance }?.let { if (it is CharSequence) JsBridge.string(it) else ScriptRuntime.toString(it) }
            s.finding(kind, value, opt(2), opt(3))
            Unit
        }
        fn(ctx, "log", 1) { a -> s.log(a.getOrNull(0).let { if (it is CharSequence) it.toString().take(ScriptCaps.MAX_LOG_LINE_CHARS * 2) else ScriptRuntime.toString(it) }); Unit }
        fn(ctx, "sleep", 1) { a ->
            val ms = ((a.getOrNull(0) as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, ScriptCaps.MAX_SLEEP_MS.toDouble()).toLong()
            s.sleep(ms)
            Unit
        }
        // cancelled() never throws: it lets a polite script leave its loop. A budget that was hit also reads as cancelled.
        ScriptableObject.defineProperty(ctx, "cancelled", HostFn("cancelled", 0, scope) { _ ->
            try { s.ensureAlive(); false } catch (_: ScriptAbort) { true }
        }, LOCKED)

        for (name in listOf("http", "tcp", "udp", "tls", "dns", "bytes", "regex")) (ScriptableObject.getProperty(ctx, name) as ScriptableObject).sealObject()
        (ctx as ScriptableObject).sealObject()
        return ctx
    }

    private const val DEFAULT_MATCHES = 1_000

    /** A connection handle as a script object with send, recv and close. */
    private fun handleObject(cx: Context, scope: Scriptable, s: RunSession, h: HostNet.Handle): Scriptable {
        val o = cx.newObject(scope)
        fun member(name: String, arity: Int, body: (Array<Any?>) -> Any?) {
            ScriptableObject.defineProperty(o, name, HostFn(name, arity, scope) { a -> s.ensureAlive(); s.hostCall { body(a) } }, LOCKED)
        }
        member("send", 1) { a -> h.send(Bytes.fromValue(JsBridge.fromJs(a.getOrNull(0)), "handle.send", "data")); Unit }
        member("recv", 1) { a -> h.recv(if (a.getOrNull(0) == null || a[0] === Undefined.instance) Opts(emptyMap(), "handle.recv") else Opts.of(a[0], "handle.recv")) }
        member("close", 0) { _ -> h.close(); Unit }
        (o as ScriptableObject).sealObject()
        return o
    }
}
