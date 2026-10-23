package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RegExpProxy
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.WrapFactory
import org.mozilla.javascript.lc.type.TypeInfo

/**
 * The Rhino sandbox (spec D3, rulings G1 and G4). Defence in depth, strongest first:
 *  - the only objects a script can reach are Rhino's own safe standard objects plus `ctx`, which is built by hand from BaseFunction and
 *    NativeObject instances; no wrapped Java object ever enters the scope, because the WrapFactory refuses to create one;
 *  - the ClassShutter hides every Java class, `java`, `Packages`, `JavaAdapter`, `importClass` and friends do not exist;
 *  - `eval`, `Function(...)` and `RegExp` are replaced by functions that throw; `__proto__`, `__parent__` and the getter/setter lookup walks are gone;
 *  - string and array builtins refuse results beyond the caps (G4), regular expressions run only through ctx.regex with a step limit;
 *  - Rhino runs interpreted (no generated classes), with an instruction observer that raises a [ScriptAbort] (an Error script code cannot catch).
 */
internal object Sandbox {
    const val SESSION_KEY = "netprobe.session"
    /** How many interpreter instructions pass between two looks at the abort flag and the budgets. */
    const val INSTRUCTION_STEP = 10_000

    private val GLOBALS_TO_REMOVE = listOf(
        "java", "javax", "Packages", "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass", "org", "com", "edu", "net", "Continuation",
        "XML", "XMLList", "Namespace", "QName", "uneval", "Iterator", "StopIteration",
    )
    private val OBJECT_PROTO_TO_REMOVE = listOf("__defineGetter__", "__defineSetter__", "__lookupGetter__", "__lookupSetter__", "watch", "unwatch", "toSource")
    private val ARRAY_METHODS = listOf(
        "join", "concat", "push", "unshift", "splice", "slice", "fill", "map", "filter", "forEach", "reduce", "reduceRight", "indexOf", "lastIndexOf",
        "every", "some", "reverse", "sort", "toString", "toLocaleString", "copyWithin", "includes", "find", "findIndex", "flat", "flatMap",
    )
    private val GROWING_ARRAY_METHODS = setOf("push", "unshift")

    /** The factory every script thread enters its context through. */
    class Factory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1                       // interpreted: Dalvik cannot load generated classes, and the observer needs it
            cx.languageVersion = Context.VERSION_1_8        // ES5 contract (G13); no ES6-only syntax or builtins
            cx.setClassShutter { false }                    // no Java class is visible to scripts
            cx.wrapFactory = DenyWrapFactory
            cx.maximumInterpreterStackDepth = ScriptCaps.MAX_STACK_DEPTH
            cx.instructionObserverThreshold = INSTRUCTION_STEP
            ScriptRuntime.setRegExpProxy(cx, DenyRegExpProxy)
            return cx
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
            Context.FEATURE_ENHANCED_JAVA_ACCESS, Context.FEATURE_THREAD_SAFE_OBJECTS, Context.FEATURE_PARENT_PROTO_PROPERTIES,
            Context.FEATURE_LOCATION_INFORMATION_IN_ERROR, Context.FEATURE_V8_EXTENSIONS, Context.FEATURE_STRICT_MODE, Context.FEATURE_INTL_402 -> false
            else -> super.hasFeature(cx, featureIndex)
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            (cx.getThreadLocal(SESSION_KEY) as? RunSession)?.onInstructions(instructionCount)
        }
    }

    /** A top-level scope with the safe standard objects only, hardened. One per run. */
    fun newScope(cx: Context): ScriptableObject {
        val scope = cx.initSafeStandardObjects(null, false)
        for (name in GLOBALS_TO_REMOVE) forceDelete(scope, name)
        // Dynamic code: eval and every route to the Function constructor throw. The prototype is read first: Function.prototype is found
        // through the global "Function", which is about to be replaced (the thrower keeps the same prototype property so lookups still work).
        val functionProto = ScriptableObject.getFunctionPrototype(scope)
        val thrower = Thrower(scope, functionProto, ScriptCopy.DYNAMIC_CODE_REFUSED)
        define(scope, "eval", thrower)
        define(scope, "Function", thrower)
        if (functionProto is ScriptableObject) define(functionProto, "constructor", thrower)
        val objectProto = ScriptableObject.getObjectPrototype(scope)
        for (name in OBJECT_PROTO_TO_REMOVE) forceDelete(objectProto, name)
        guardStrings(scope)
        guardArrays(scope)
        return scope
    }

    private fun define(target: Scriptable, name: String, value: Any?) {
        forceDelete(target, name)
        ScriptableObject.defineProperty(target, name, value, ScriptableObject.DONTENUM or ScriptableObject.PERMANENT or ScriptableObject.READONLY)
    }

    private fun forceDelete(target: Scriptable, name: String) {
        if (!ScriptableObject.hasProperty(target, name)) return
        try { ScriptableObject.deleteProperty(target, name) } catch (_: Exception) { /* falls through to the check below */ }
        if (ScriptableObject.hasProperty(target, name)) {
            // Not deletable (permanent): shadow it with undefined so a script cannot reach the original.
            (target as? ScriptableObject)?.let { runCatching { it.defineProperty(name, org.mozilla.javascript.Undefined.instance, ScriptableObject.DONTENUM) } }
        }
    }

    private fun lengthOf(o: Scriptable?): Long {
        if (o == null) return 0
        val n = ScriptRuntime.toNumber(ScriptableObject.getProperty(o, "length"))
        return if (n.isNaN() || n < 0) 0 else n.toLong()
    }

    private fun tooLong(): Nothing = throw ScriptRuntime.constructError("RangeError", ScriptCopy.STRING_TOO_LONG)
    private fun tooMany(): Nothing = throw ScriptRuntime.constructError("RangeError", ScriptCopy.ARRAY_TOO_LONG)

    private fun textLength(v: Any?): Long = when (v) {
        is CharSequence -> v.length.toLong()
        null -> 4
        else -> 24
    }

    private fun guardStrings(scope: Scriptable) {
        val proto = ScriptableObject.getClassPrototype(scope, "String") ?: return
        wrapAll(proto, scope, listOf("repeat", "padStart", "padEnd", "concat", "replace", "replaceAll")) { name, thisObj, args ->
            val self = textLength(ScriptRuntime.toCharSequence(thisObj))
            val cap = ScriptCaps.MAX_STRING_CHARS.toLong()
            when (name) {
                "repeat" -> { val n = ScriptRuntime.toNumber(args.getOrNull(0) ?: 0); if (n.isNaN() || n < 0 || n * self > cap) tooLong() }
                "padStart", "padEnd" -> { val n = ScriptRuntime.toNumber(args.getOrNull(0) ?: 0); if (!n.isNaN() && n > cap) tooLong() }
                "concat" -> { var sum = self; for (a in args) sum += ScriptRuntime.toCharSequence(a).length.toLong(); if (sum > cap) tooLong() }
                "replace", "replaceAll" -> {
                    val search = ScriptRuntime.toCharSequence(args.getOrNull(0)).length.toLong()
                    val repl = args.getOrNull(1)?.let { if (it is Callable) cap else ScriptRuntime.toCharSequence(it).length.toLong() } ?: 0
                    val matches = if (search == 0L) self + 1 else self / search
                    if (self + matches * maxOf(0, repl - search) > cap) tooLong()
                }
            }
        }
    }

    private fun guardArrays(scope: Scriptable) {
        val proto = ScriptableObject.getArrayPrototype(scope) ?: return
        wrapAll(proto, scope, ARRAY_METHODS) { name, thisObj, args ->
            val len = lengthOf(thisObj)
            if (len > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
            if (name in GROWING_ARRAY_METHODS && len + args.size > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
            if (name == "concat") { var sum = len; for (a in args) sum += if (a is Scriptable && a.className == "Array") lengthOf(a) else 1; if (sum > ScriptCaps.MAX_ARRAY_LENGTH) tooMany() }
            if (name == "join" || name == "toString" || name == "toLocaleString") {
                val sepLen = if (name == "join" && args.isNotEmpty() && args[0] != null && args[0] !== org.mozilla.javascript.Undefined.instance) textLength(args[0]) else 1L
                var total = sepLen * len
                for (i in 0 until len.toInt()) {
                    total += textLength((thisObj as? Scriptable)?.get(i, thisObj)?.takeIf { it !== Scriptable.NOT_FOUND })
                    if (total > ScriptCaps.MAX_STRING_CHARS) tooLong()
                }
            }
        }
        // Array(n) and new Array(n): a length beyond the cap is refused before anything is created.
        val original = ScriptableObject.getProperty(scope, "Array") as? BaseFunction ?: return
        define(scope, "Array", ArrayCtor(scope, original, proto))
        if (proto is ScriptableObject) define(proto, "constructor", ScriptableObject.getProperty(scope, "Array"))
    }

    private fun wrapAll(proto: Scriptable, scope: Scriptable, names: List<String>, check: (String, Scriptable?, Array<Any?>) -> Unit) {
        for (name in names) {
            val original = ScriptableObject.getProperty(proto, name) as? Callable ?: continue
            val guarded = object : BaseFunction() {
                init { parentScope = scope; prototype = ScriptableObject.getFunctionPrototype(scope) }
                override fun getFunctionName(): String = name
                override fun call(cx: Context, s: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                    check(name, thisObj, args)
                    val result = original.call(cx, s, thisObj, args)
                    if (result is Scriptable && result.className == "Array" && lengthOf(result) > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
                    if (result is CharSequence && result.length > ScriptCaps.MAX_STRING_CHARS) tooLong()
                    return result
                }
            }
            if (proto is ScriptableObject) proto.defineProperty(name, guarded, ScriptableObject.DONTENUM)
        }
    }

    /** `Array` with a size check in front of the real constructor; everything else (isArray, from, of, instanceof) falls through to it. */
    private class ArrayCtor(scope: Scriptable, private val original: BaseFunction, arrayProto: Scriptable) : BaseFunction() {
        init {
            parentScope = scope
            prototype = original
            setImmunePrototypeProperty(arrayProto)
        }
        override fun getFunctionName(): String = "Array"
        override fun hasInstance(instance: Scriptable): Boolean = original.hasInstance(instance)
        private fun check(args: Array<Any?>) {
            if (args.size == 1 && args[0] is Number) {
                val n = (args[0] as Number).toDouble()
                if (!n.isNaN() && n > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
            }
            if (args.size > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
        }
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? { check(args); return original.call(cx, scope, thisObj, args) }
        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable { check(args); return original.construct(cx, scope, args) }
    }

    private class Thrower(scope: Scriptable, functionProto: Scriptable, private val message: String) : BaseFunction() {
        init { parentScope = scope; prototype = functionProto; setImmunePrototypeProperty(functionProto) }
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = throw ScriptRuntime.constructError("Error", message)
        override fun construct(cx: Context, scope: Scriptable, args: Array<Any?>): Scriptable = throw ScriptRuntime.constructError("Error", message)
    }

    /** G1: the engine never wraps a Java object for a script. If something tries, the run fails instead of exposing it. */
    object DenyWrapFactory : WrapFactory() {
        init { isJavaPrimitiveWrap = false }
        override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any?, staticType: Class<*>?): Scriptable = refuse()
        override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any?, staticType: TypeInfo?): Scriptable = refuse()
        override fun wrapNewObject(cx: Context, scope: Scriptable, obj: Any?): Scriptable = refuse()
        override fun wrapJavaClass(cx: Context, scope: Scriptable, javaClass: Class<*>?): Scriptable = refuse()
        private fun refuse(): Nothing = throw ScriptRuntime.constructError("Error", ScriptCopy.JAVA_ACCESS_REFUSED)
    }

    /**
     * No RegExp in scripts (G4): literals, `new RegExp`, and the regex forms of match/search/replace/split throw. The string forms of
     * split and replace stay, implemented here on plain strings, because Rhino routes every call through the proxy. Use ctx.regex instead.
     */
    object DenyRegExpProxy : RegExpProxy {
        private fun refuse(): Nothing = throw ScriptRuntime.constructError("Error", ScriptCopy.REGEXP_REFUSED)
        override fun register(scope: ScriptableObject, sealed: Boolean) {}
        override fun isRegExp(obj: Scriptable): Boolean = false
        override fun compileRegExp(cx: Context, source: String, flags: String?): Any? = refuse()
        override fun wrapRegExp(cx: Context, scope: Scriptable, compiled: Any?): Scriptable = refuse()
        override fun find_split(cx: Context, scope: Scriptable, target: String, separator: String, reObj: Scriptable?, ip: IntArray, matchlen: IntArray, matched: BooleanArray, parensp: Array<Array<String>>?): Int = refuse()

        override fun action(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>, actionType: Int): Any? {
            val target = ScriptRuntime.toString(thisObj)
            if (actionType != RegExpProxy.RA_REPLACE && actionType != RegExpProxy.RA_REPLACE_ALL) refuse()
            val search = args.getOrNull(0)
            if (search == null || search !is CharSequence) refuse()
            val needle = search.toString()
            val replacement = args.getOrNull(1)
            val text = if (replacement is Callable) refuse() else ScriptRuntime.toString(replacement)
            val all = actionType == RegExpProxy.RA_REPLACE_ALL
            if (needle.isEmpty()) {
                if (!all) return text + target
                val out = StringBuilder(text)
                for (ch in target) { out.append(ch).append(text); if (out.length > ScriptCaps.MAX_STRING_CHARS) tooLong() }
                return out.toString()
            }
            val out = StringBuilder()
            var from = 0
            while (true) {
                val at = target.indexOf(needle, from)
                if (at < 0) break
                out.append(target, from, at).append(text)
                from = at + needle.length
                if (out.length > ScriptCaps.MAX_STRING_CHARS) tooLong()
                if (!all) break
            }
            out.append(target, from, target.length)
            return out.toString()
        }

        override fun js_split(cx: Context, scope: Scriptable, target: String, args: Array<Any?>): Any? {
            val sepArg = args.getOrNull(0)
            if (sepArg == null || sepArg === org.mozilla.javascript.Undefined.instance) return cx.newArray(scope, arrayOf<Any?>(target))
            if (sepArg !is CharSequence) refuse()
            val sep = sepArg.toString()
            val limit = args.getOrNull(1)?.takeIf { it !== org.mozilla.javascript.Undefined.instance }?.let { ScriptRuntime.toUint32(it) } ?: Long.MAX_VALUE
            val parts = ArrayList<Any?>()
            if (sep.isEmpty()) {
                for (ch in target) { if (parts.size >= limit) break; if (parts.size >= ScriptCaps.MAX_ARRAY_LENGTH) tooMany(); parts += ch.toString() }
            } else {
                var from = 0
                while (parts.size < limit) {
                    val at = target.indexOf(sep, from)
                    if (at < 0) { parts += target.substring(from); break }
                    parts += target.substring(from, at)
                    from = at + sep.length
                    if (parts.size > ScriptCaps.MAX_ARRAY_LENGTH) tooMany()
                }
            }
            return cx.newArray(scope, parts.toTypedArray())
        }
    }
}
