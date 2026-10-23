package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Undefined
import java.net.InetAddress

/**
 * Copies between Kotlin plain data and JavaScript values (G1). Nothing that leaves the host is a Java object: strings, numbers, booleans,
 * NativeObject and NativeArray only. Nothing a script passes in is kept as a Rhino object: it is copied to String, Double, Boolean, List or
 * Map, with depth, node and size limits so a hostile structure cannot cost more than it is allowed to.
 */
internal object JsBridge {
    fun toJs(cx: Context, scope: Scriptable, v: Any?): Any? = when (v) {
        null -> null
        Unit -> Undefined.instance
        is String, is Boolean, is Int -> v
        is Long -> v.toDouble()
        is Double -> v
        is Float -> v.toDouble()
        is Number -> v.toDouble()
        is ByteArray -> cx.newArray(scope, Array<Any?>(v.size) { v[it].toInt() and 0xff })
        is List<*> -> cx.newArray(scope, Array<Any?>(v.size) { toJs(cx, scope, v[it]) })
        is Map<*, *> -> cx.newObject(scope).also { o -> for ((k, x) in v) ScriptableObject.putProperty(o, k.toString(), toJs(cx, scope, x)) }
        is WordlistRef -> toJs(cx, scope, mapOf("id" to v.id, "name" to v.name, "entries" to v.entries))
        is InetAddress -> v.hostAddress
        is Scriptable -> v
        else -> throw IllegalStateException("not a plain value: ${v.javaClass.name}")
    }

    private class Budget(var nodes: Int = 0)

    /** A script value as plain Kotlin data. Functions are refused unless [skipFunctions], which drops them (for the `tool` object). */
    fun fromJs(v: Any?, skipFunctions: Boolean = false): Any? = convert(v, 0, Budget(), skipFunctions)

    private fun convert(v: Any?, depth: Int, budget: Budget, skipFunctions: Boolean): Any? {
        if (++budget.nodes > ScriptCaps.MAX_CONVERT_NODES) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE)
        if (depth > ScriptCaps.MAX_CONVERT_DEPTH) throw ScriptHostError(ScriptCopy.VALUE_TOO_DEEP)
        return when (v) {
            null, Undefined.instance -> null
            is CharSequence -> string(v)
            is Boolean -> v
            is Number -> v.toDouble()
            is Callable -> if (skipFunctions) FUNCTION_MARKER else throw ScriptHostError(ScriptCopy.FUNCTION_NOT_ALLOWED)
            is Scriptable -> {
                if (v.className == "Array") {
                    val len = ScriptRuntime.toNumber(ScriptableObject.getProperty(v, "length")).toLong()
                    if (len > ScriptCaps.MAX_ARRAY_LENGTH) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE)
                    val out = ArrayList<Any?>(len.toInt())
                    for (i in 0 until len.toInt()) out += convert(v.get(i, v).takeIf { it !== Scriptable.NOT_FOUND }, depth + 1, budget, skipFunctions)
                    out
                } else {
                    val ids = v.ids
                    if (ids.size > ScriptCaps.MAX_OBJECT_KEYS) throw ScriptHostError(ScriptCopy.VALUE_TOO_LARGE)
                    val out = LinkedHashMap<String, Any?>()
                    for (id in ids) {
                        if (id !is String) continue
                        val x = v.get(id, v)
                        if (x === Scriptable.NOT_FOUND) continue
                        val c = convert(x, depth + 1, budget, skipFunctions)
                        if (c === FUNCTION_MARKER) out[id] = FUNCTION_MARKER else out[id] = c
                    }
                    out
                }
            }
            else -> throw ScriptHostError(ScriptCopy.VALUE_NOT_PLAIN)
        }
    }

    /** Marks a function-valued property in the converted `tool` object; callers test for it and never see a Rhino object. */
    val FUNCTION_MARKER = Any()

    /** The characters of a script string, refused before they are copied when longer than the string cap. */
    fun string(v: CharSequence): String {
        if (v.length > ScriptCaps.MAX_STRING_CHARS) throw ScriptHostError(ScriptCopy.STRING_TOO_LONG)
        return v.toString()
    }

    /** True for the string kinds a script can hold, including Rhino's lazy concatenation. */
    fun isString(v: Any?): Boolean = v is String || v is ConsString
}

/**
 * A host function (G1): a BaseFunction written by hand, so the script never meets a wrapped Java object. [body] gets the raw script
 * arguments and returns Kotlin plain data. Host errors become JS Errors with only a message; a [ScriptAbort] passes through untouched so
 * script code cannot catch it.
 */
internal class HostFn(private val fnName: String, private val arity: Int, scope: Scriptable, private val body: (Array<Any?>) -> Any?) : BaseFunction() {
    init {
        parentScope = scope
        prototype = ScriptableObject.getFunctionPrototype(scope)
    }
    override fun getFunctionName(): String = fnName
    override fun getArity(): Int = arity
    override fun getLength(): Int = arity

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
        try {
            return JsBridge.toJs(cx, scope, body(args))
        } catch (e: ScriptAbort) {
            throw e
        } catch (e: RhinoException) {
            throw e
        } catch (e: ScriptHostError) {
            throw ScriptRuntime.constructError("Error", e.message ?: ScriptCopy.HOST_FAILED)
        } catch (e: java.io.IOException) {
            throw ScriptRuntime.constructError("Error", ScriptCopy.ioFailed(e.javaClass.simpleName, e.message))
        } catch (e: Exception) {
            throw ScriptRuntime.constructError("Error", ScriptCopy.HOST_FAILED)
        }
    }

    override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable =
        throw ScriptRuntime.constructError("TypeError", ScriptCopy.NOT_A_CONSTRUCTOR)
}

/** Typed reading of a host function's arguments and option objects. Every failure is a [ScriptHostError] the script can catch. */
internal class Opts(private val map: Map<String, Any?>, private val what: String) {
    fun has(key: String) = map[key] != null
    fun string(key: String, default: String? = null): String {
        val v = map[key] ?: return default ?: throw ScriptHostError(ScriptCopy.missingOption(what, key))
        return v as? String ?: throw ScriptHostError(ScriptCopy.wrongType(what, key, "string"))
    }
    fun optString(key: String): String? = if (map[key] == null) null else string(key)
    fun int(key: String, default: Int, min: Int, max: Int): Int {
        val v = map[key] ?: return default
        val n = (v as? Double) ?: throw ScriptHostError(ScriptCopy.wrongType(what, key, "number"))
        if (n.isNaN()) throw ScriptHostError(ScriptCopy.wrongType(what, key, "number"))
        return n.coerceIn(min.toDouble(), max.toDouble()).toInt()
    }
    fun requiredInt(key: String, min: Int, max: Int): Int {
        val v = map[key] ?: throw ScriptHostError(ScriptCopy.missingOption(what, key))
        val n = (v as? Double) ?: throw ScriptHostError(ScriptCopy.wrongType(what, key, "number"))
        if (n.isNaN() || n < min || n > max || n != Math.floor(n)) throw ScriptHostError(ScriptCopy.outOfRange(what, key, min, max))
        return n.toInt()
    }
    fun bool(key: String, default: Boolean): Boolean {
        val v = map[key] ?: return default
        return v as? Boolean ?: throw ScriptHostError(ScriptCopy.wrongType(what, key, "boolean"))
    }
    fun bytes(key: String): ByteArray? = map[key]?.let { Bytes.fromValue(it, what, key) }
    fun raw(key: String): Any? = map[key]
    @Suppress("UNCHECKED_CAST")
    fun map(key: String): Map<String, Any?>? = map[key]?.let { it as? Map<String, Any?> ?: throw ScriptHostError(ScriptCopy.wrongType(what, key, "object")) }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun of(arg: Any?, what: String): Opts {
            val m = JsBridge.fromJs(arg) as? Map<String, Any?> ?: throw ScriptHostError(ScriptCopy.optionsExpected(what))
            return Opts(m, what)
        }
    }
}
