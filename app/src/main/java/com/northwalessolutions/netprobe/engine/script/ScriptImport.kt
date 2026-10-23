package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolSource
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.ui.tools.ScriptCopy
import com.northwalessolutions.netprobe.update.FetchError
import com.northwalessolutions.netprobe.update.UpdateFetch
import com.northwalessolutions.netprobe.update.UpdateFetcher
import com.northwalessolutions.netprobe.update.UpdateRedirects
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL

/** What a replace changes (G9): shown beside the new tool so the Director sees what he is accepting. */
data class ReplacePlan(
    val oldName: String, val oldVersion: String, val newVersion: String,
    val scopeFrom: NetworkScope?, val scopeTo: NetworkScope,
    /** The new scope reaches further than the old one (target, then lan, then any); the sheet asks for an explicit yes. */
    val widened: Boolean,
    val shaFrom: String, val shaTo: String,
    /** "label (type)" per input; empty lists when the old inputs are unknown (a broken entry). */
    val inputsAdded: List<String>, val inputsRemoved: List<String>, val inputsChanged: List<String>,
    val inputsKnown: Boolean,
) {
    val identical: Boolean get() = shaFrom.equals(shaTo, ignoreCase = true)
}

/** A script read, evaluated and described, waiting for the Director's yes. [replace] is set when the key is already installed. */
data class ImportPlan(
    val descriptor: ToolDescriptor, val source: String, val sha256: String, val sourceUrl: String?, val fileName: String?,
    val replace: ReplacePlan?,
) {
    /** A first import of a tool for any address is its own callout; a replace only needs the extra yes when the scope widens. */
    val needsWideningAck: Boolean get() = replace?.widened == true
}

sealed interface ImportOutcome {
    data class Ready(val plan: ImportPlan) : ImportOutcome
    /** Every problem found, each a Copy string. */
    data class Failed(val errors: List<String>) : ImportOutcome
}

/** The input kinds as the sheet words them ("text", "number" and so on); the same names a script uses in its description. */
object InputKinds {
    fun of(spec: InputSpec): String = when (spec) {
        is InputSpec.Ip -> "ip"
        is InputSpec.Port -> "port"
        is InputSpec.Text -> "text"
        is InputSpec.Number -> "number"
        is InputSpec.Bool -> "bool"
        is InputSpec.Choice -> "choice"
        is InputSpec.Wordlist -> "wordlist"
        is InputSpec.TextList -> "list"
        is InputSpec.MultiChoice -> "multichoice"
        is InputSpec.Secret -> "secret"
        is InputSpec.Tls -> "tls"
    }

    fun line(spec: InputSpec): String = "${spec.label} (${of(spec)})"
}

/** Version order and the replace diff, as pure functions. */
object ReplaceRules {
    private val TOKEN = Regex("[0-9]+|[A-Za-z]+")

    /**
     * Compares two version texts piece by piece: runs of digits as numbers, runs of letters as lower-case text. A missing piece counts as 0,
     * so 1.0 equals 1.0.0; a letter piece sorts before a missing one and before a number, so 1.0-beta is older than 1.0 and than 1.0.1.
     */
    fun compareVersions(a: String, b: String): Int {
        val x = TOKEN.findAll(a).map { it.value }.toList()
        val y = TOKEN.findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)
            val q = y.getOrNull(i)
            val c = comparePiece(p, q)
            if (c != 0) return c
        }
        return 0
    }

    private fun comparePiece(p: String?, q: String?): Int {
        val pn = p?.toBigIntegerOrNull()
        val qn = q?.toBigIntegerOrNull()
        return when {
            p == null && q == null -> 0
            p == null -> if (qn != null) (if (qn.signum() == 0) 0 else -1) else 1
            q == null -> if (pn != null) (if (pn.signum() == 0) 0 else 1) else -1
            pn != null && qn != null -> pn.compareTo(qn)
            pn != null -> 1
            qn != null -> -1
            else -> p.lowercase().compareTo(q.lowercase()).coerceIn(-1, 1)
        }
    }

    /** True when installing [candidate] over [installed] goes backwards. Equal versions are not a downgrade (the sha line shows a change). */
    fun isDowngrade(installed: String, candidate: String): Boolean = compareVersions(candidate, installed) < 0

    fun widens(from: NetworkScope?, to: NetworkScope): Boolean = from != null && to.ordinal > from.ordinal

    /** [oldInputs] is null when the installed tool could not be read (broken), so only scope, version and sha are compared. */
    fun diff(oldName: String, oldVersion: String, oldScope: NetworkScope?, oldSha: String, oldInputs: List<InputSpec>?, now: ToolDescriptor, newSha: String): ReplacePlan {
        val before = oldInputs.orEmpty().associateBy { it.name }
        val after = now.inputs.associateBy { it.name }
        val added = if (oldInputs == null) emptyList() else now.inputs.filter { it.name !in before }.map(InputKinds::line)
        val removed = oldInputs?.filter { it.name !in after }?.map(InputKinds::line).orEmpty()
        val changed = if (oldInputs == null) emptyList() else now.inputs.filter { before[it.name]?.let { old -> old != it } == true }.map(InputKinds::line)
        return ReplacePlan(oldName, oldVersion, now.version, oldScope, now.network, widens(oldScope, now.network), oldSha, newSha, added, removed, changed, oldInputs != null)
    }
}

/**
 * Reads a script from a file or an https address, runs it through the sandbox only far enough to read its `tool` description (2 s, no ctx),
 * and builds the plan the confirmation sheet shows (spec D10, section 8, G9). Nothing is saved until [confirm]; nothing is fetched again
 * later; the script runs only because a tool page started it.
 */
class ScriptImporter(
    private val engine: ScriptEngine,
    private val store: ScriptToolStore,
    private val fetcher: UpdateFetcher,
    private val urlBudgetMs: Long = URL_BUDGET_MS,
) {
    /** A file: [open] gives the stream (null when it cannot be opened), read to at most 1 MB. */
    suspend fun fromFile(fileName: String?, open: () -> InputStream?): ImportOutcome {
        val read = withContext(Dispatchers.IO) {
            try {
                open()?.use { ScriptBytes.readCapped(it) }
            } catch (_: java.io.IOException) { null } catch (_: SecurityException) { null }
        } ?: return ImportOutcome.Failed(listOf(ImportCopy.FILE_UNOPENED))
        return when (read) {
            ScriptBytes.Read.TooLarge -> ImportOutcome.Failed(listOf(ImportCopy.TOO_LARGE))
            is ScriptBytes.Read.Bytes -> plan(read.bytes, null, fileName?.take(MAX_NAME_CHARS))
        }
    }

    /** An https address, fetched now (the user tapped Fetch). Every hop must be https; 1 MB; 20 s for the whole fetch. */
    suspend fun fromUrl(text: String): ImportOutcome {
        val url = parseUrl(text) ?: return ImportOutcome.Failed(listOf(if (looksLikeUrl(text)) ImportCopy.URL_NOT_HTTPS else ImportCopy.URL_BAD))
        val out = ByteArrayOutputStream()
        val result = try {
            withTimeout(urlBudgetMs) { fetcher.fetch(url, ScriptBytes.MAX_IMPORT_BYTES.toLong(), out) }
        } catch (_: TimeoutCancellationException) { return ImportOutcome.Failed(listOf(ImportCopy.FETCH_TIMEOUT)) }
        return when (result) {
            is UpdateFetch.Failed -> ImportOutcome.Failed(listOf(fetchError(result.error)))
            is UpdateFetch.Ok -> plan(out.toByteArray(), url.toString(), null)
        }
    }

    /** Decodes, evaluates and describes [bytes], and compares them with an installed tool of the same key. */
    suspend fun plan(bytes: ByteArray, sourceUrl: String?, fileName: String?): ImportOutcome {
        if (bytes.isEmpty()) return ImportOutcome.Failed(listOf(ImportCopy.EMPTY))
        val source = ScriptBytes.decode(bytes) ?: return ImportOutcome.Failed(listOf(ImportCopy.FILE_NOT_TEXT))
        if (source.toByteArray(Charsets.UTF_8).size > ScriptCaps.MAX_SOURCE_BYTES) return ImportOutcome.Failed(listOf(ScriptCopy.SOURCE_TOO_LARGE))
        val loaded = when (val evaluated = engine.evaluate(source)) {
            is ScriptEvaluation.Failed -> return ImportOutcome.Failed(listOf(evaluated.message))
            is ScriptEvaluation.Loaded -> evaluated
        }
        val sha = ScriptBytes.sha256(source)
        val descriptor = when (val parsed = ScriptDescriptorParser.parse(loaded.tool, loaded.hasRun, ToolKeys.USER_PREFIX, null, ToolSource.Script(java.io.File(""), sha))) {
            is ParsedTool.Invalid -> return ImportOutcome.Failed(parsed.errors)
            is ParsedTool.Valid -> parsed.descriptor.copy(source = ToolSource.Script(store.fileFor(parsed.descriptor.key) ?: java.io.File(""), sha))
        }
        val replace = replacePlan(descriptor, sha)
        if (replace is Replace.Refused) return ImportOutcome.Failed(listOf(replace.reason))
        return ImportOutcome.Ready(ImportPlan(descriptor, source, sha, sourceUrl, fileName, (replace as? Replace.Plan)?.plan))
    }

    /**
     * Saves a reviewed plan. A replace is checked again against what is installed now: if the installed tool changed while the sheet was open,
     * nothing is saved and the caller reviews again. A scope widening is saved only with [acknowledgedWidening].
     */
    suspend fun confirm(plan: ImportPlan, acknowledgedWidening: Boolean): ConfirmResult {
        val now = replacePlan(plan.descriptor, plan.sha256)
        if (now is Replace.Refused) return ConfirmResult.Refused(now.reason)
        if ((now as? Replace.Plan)?.plan != plan.replace) return ConfirmResult.Refused(ImportCopy.STALE)
        if (plan.needsWideningAck && !acknowledgedWidening) return ConfirmResult.Refused(ImportCopy.WIDENING_NEEDS_YES)
        return when (val saved = store.save(plan.descriptor, plan.source, plan.sourceUrl, plan.fileName)) {
            is SaveResult.Saved -> ConfirmResult.Saved(plan.descriptor.name, saved.replaced)
            is SaveResult.Failed -> ConfirmResult.Refused(saved.reason)
        }
    }

    sealed interface ConfirmResult {
        data class Saved(val name: String, val replaced: Boolean) : ConfirmResult
        data class Refused(val reason: String) : ConfirmResult
    }

    private sealed interface Replace {
        data object None : Replace
        data class Plan(val plan: ReplacePlan) : Replace
        data class Refused(val reason: String) : Replace
    }

    private fun replacePlan(descriptor: ToolDescriptor, sha: String): Replace {
        val installed = store.entry(descriptor.key) ?: return Replace.None
        val module = store.moduleOf(descriptor.key)
        val old = module?.descriptor
        val oldVersion = old?.version ?: installed.version
        // A broken entry is being repaired: its recorded version does not block the new file.
        if (module != null && ReplaceRules.isDowngrade(oldVersion, descriptor.version)) return Replace.Refused(ImportCopy.downgrade(oldVersion, descriptor.version))
        val diff = ReplaceRules.diff(old?.name ?: installed.name, oldVersion, old?.network ?: ScriptToolStore.scopeOf(installed.scope), installed.sha256, old?.inputs, descriptor, sha)
        return Replace.Plan(diff)
    }

    private fun parseUrl(text: String): URL? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val url = try { URL(trimmed.substringBefore('#')) } catch (_: MalformedURLException) { return null }
        return url.takeIf { UpdateRedirects.allowedStart(it) }
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.length <= MAX_URL_CHARS && t.none { it.isWhitespace() || it.isISOControl() } && try { URL(t); true } catch (_: MalformedURLException) { false }
    }

    private fun fetchError(e: FetchError): String = when (e) {
        FetchError.REFUSED -> ImportCopy.FETCH_REFUSED
        FetchError.TOO_MANY_REDIRECTS -> ImportCopy.FETCH_REDIRECTS
        FetchError.TOO_LARGE -> ImportCopy.TOO_LARGE
        FetchError.STATUS -> ImportCopy.FETCH_STATUS
        FetchError.CONNECTION -> ImportCopy.FETCH_CONNECTION
    }

    companion object {
        /** The whole fetch, redirects included (B4 brief). */
        const val URL_BUDGET_MS = 20_000L
        private const val MAX_URL_CHARS = 2_048
        private const val MAX_NAME_CHARS = 80
    }
}
