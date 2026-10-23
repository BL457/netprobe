package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.ui.tools.CatalogCopy
import com.northwalessolutions.netprobe.ui.tools.ImportCopy
import com.northwalessolutions.netprobe.update.FetchError
import com.northwalessolutions.netprobe.update.UpdateConfig
import com.northwalessolutions.netprobe.update.UpdateFetch
import com.northwalessolutions.netprobe.update.UpdateFetcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.MalformedURLException
import java.net.URL

/**
 * One tool the catalog would install. [oldVersion] is null for a new tool; for an update it is the installed version, [scopeFrom] the
 * installed scope (null when the installed copy is broken). [widened] is true when the new version reaches further, [broadcastNew] when it
 * newly declares broadcast, and [replacesLocal] when the installed copy was imported by hand rather than installed from the catalog.
 */
data class CatalogItem(
    val entry: CatalogEntry, val plan: ImportPlan, val oldVersion: String?, val scopeFrom: NetworkScope?,
    val widened: Boolean, val broadcastNew: Boolean, val replacesLocal: Boolean,
) {
    val descriptor: ToolDescriptor get() = plan.descriptor
    val isNew: Boolean get() = oldVersion == null
    /** The extra yes that G9 asks for: a wider scope, or a broadcast permission the installed copy did not have. */
    val needsYes: Boolean get() = widened || broadcastNew
}

data class CatalogFailure(val name: String, val reason: String)

/** What "Update tools from GitHub" found: nothing is installed until [CatalogUpdater.apply]. */
data class CatalogPlan(val new: List<CatalogItem>, val updated: List<CatalogItem>, val failed: List<CatalogFailure>, val unchanged: Int) {
    val installable: Int get() = new.size + updated.size
    val needsYes: Boolean get() = updated.any { it.needsYes }
}

sealed interface CatalogOutcome {
    data class Ready(val plan: CatalogPlan) : CatalogOutcome
    /** The index itself could not be fetched or read: [reason] is a Copy string. */
    data class Failed(val reason: String) : CatalogOutcome
}

data class CatalogApplied(val installed: List<String>, val failed: List<CatalogFailure>)

/**
 * "Update tools from GitHub" (spec section 11). [check] fetches the index, compares it with the store by key and sha256, fetches only the
 * scripts that are new or different, and runs each through the normal import pipeline (sandbox evaluation, descriptor parse, downgrade
 * refusal); [apply] then saves every New and Updated tool. Nothing is fetched until [check] is called, which only an explicit tap does.
 * A tool that was removed from the catalog is never touched: only index lines are visited.
 */
class CatalogUpdater(
    private val importer: ScriptImporter,
    private val store: ScriptToolStore,
    private val fetcher: UpdateFetcher,
    private val indexUrl: String = UpdateConfig.CATALOG_INDEX_URL,
    private val fetchBudgetMs: Long = ScriptImporter.URL_BUDGET_MS,
) {
    private sealed interface Fetched {
        class Bytes(val bytes: ByteArray) : Fetched
        class Error(val reason: String) : Fetched
    }

    /** [onProgress] is called with (checked, total) once the index is read and after each line is dealt with. */
    suspend fun check(onProgress: (Int, Int) -> Unit = { _, _ -> }): CatalogOutcome {
        val index = when (val got = fetch(indexUrl, UpdateConfig.CATALOG_INDEX_MAX_BYTES)) {
            is Fetched.Error -> return CatalogOutcome.Failed(got.reason)
            is Fetched.Bytes -> ScriptBytes.decode(got.bytes)?.let(CatalogIndex::parse) ?: CatalogIndexResult.Unreadable
        }
        val parsed = index as? CatalogIndexResult.Parsed ?: return CatalogOutcome.Failed(CatalogCopy.INDEX_UNREADABLE)
        val base = indexUrl.substringBeforeLast('/') + "/"
        val newTools = ArrayList<CatalogItem>()
        val updated = ArrayList<CatalogItem>()
        val failed = ArrayList<CatalogFailure>()
        var unchanged = 0
        parsed.rejected.forEach { failed += CatalogFailure(it.label, it.reason) }
        onProgress(0, parsed.entries.size)
        parsed.entries.forEachIndexed { position, entry ->
            if (isUnchanged(entry)) unchanged++
            else when (val result = read(entry, base)) {
                is Read.Item -> if (result.item.isNew) newTools += result.item else updated += result.item
                is Read.Failure -> failed += CatalogFailure(entry.name, result.reason)
            }
            onProgress(position + 1, parsed.entries.size)
        }
        return CatalogOutcome.Ready(CatalogPlan(newTools, updated, failed, unchanged))
    }

    /** Installed with this exact script and loadable: the catalog has nothing to say about it. A broken copy is repaired instead. */
    private fun isUnchanged(entry: CatalogEntry): Boolean {
        val installed = store.entry(entry.key) ?: return false
        return installed.sha256.equals(entry.sha256, ignoreCase = true) && store.moduleOf(entry.key) != null
    }

    private sealed interface Read {
        class Item(val item: CatalogItem) : Read
        class Failure(val reason: String) : Read
    }

    private suspend fun read(entry: CatalogEntry, base: String): Read {
        val url = base + entry.file
        val bytes = when (val got = fetch(url, ScriptBytes.MAX_IMPORT_BYTES.toLong())) {
            is Fetched.Error -> return Read.Failure(got.reason)
            is Fetched.Bytes -> got.bytes
        }
        // The index and the script are two files: if they disagree (a stale cache, a half-merged change) nothing from this line is installed.
        if (!ScriptBytes.sha256(bytes).equals(entry.sha256, ignoreCase = true)) return Read.Failure(CatalogCopy.SCRIPT_MISMATCH)
        val plan = when (val outcome = importer.plan(bytes, url, entry.file, fromCatalog = true)) {
            is ImportOutcome.Failed -> return Read.Failure(outcome.errors.firstOrNull() ?: ImportCopy.UNEXPECTED)
            is ImportOutcome.Ready -> outcome.plan
        }
        if (plan.descriptor.key != entry.key) return Read.Failure(CatalogCopy.ID_MISMATCH)
        val replace = plan.replace ?: return Read.Item(CatalogItem(entry, plan, null, null, widened = false, broadcastNew = false, replacesLocal = false))
        val oldBroadcast = store.moduleOf(entry.key)?.descriptor?.broadcast ?: false
        val installed = store.entry(entry.key)
        return Read.Item(CatalogItem(
            entry, plan, replace.oldVersion, replace.scopeFrom, replace.widened, broadcastNew = plan.descriptor.broadcast && !oldBroadcast,
            replacesLocal = installed?.catalog != true,
        ))
    }

    private suspend fun fetch(url: String, cap: Long): Fetched {
        val parsed = try { URL(url) } catch (_: MalformedURLException) { return Fetched.Error(ImportCopy.FETCH_REFUSED) }
        val out = ByteArrayOutputStream()
        val result = try {
            withTimeout(fetchBudgetMs) { fetcher.fetch(parsed, cap, out) }
        } catch (_: TimeoutCancellationException) { return Fetched.Error(ImportCopy.FETCH_TIMEOUT) }
        return when (result) {
            is UpdateFetch.Ok -> Fetched.Bytes(out.toByteArray())
            is UpdateFetch.Failed -> Fetched.Error(fetchError(result.error))
        }
    }

    private fun fetchError(e: FetchError): String = when (e) {
        FetchError.REFUSED -> ImportCopy.FETCH_REFUSED
        FetchError.TOO_MANY_REDIRECTS -> ImportCopy.FETCH_REDIRECTS
        FetchError.TOO_LARGE -> ImportCopy.TOO_LARGE
        FetchError.STATUS -> ImportCopy.FETCH_STATUS
        FetchError.CONNECTION -> ImportCopy.FETCH_CONNECTION
    }

    /**
     * Saves every New and Updated tool of [plan], one by one, through the importer's own confirm (which checks that the installed copy is
     * still the one the plan was made against). A tool that needs the extra yes is saved only with [acknowledged]. One failure does not stop the rest.
     */
    suspend fun apply(plan: CatalogPlan, acknowledged: Boolean): CatalogApplied {
        val installed = ArrayList<String>()
        val failed = ArrayList<CatalogFailure>()
        for (item in plan.new + plan.updated) {
            if (item.needsYes && !acknowledged) { failed += CatalogFailure(item.descriptor.name, ImportCopy.WIDENING_NEEDS_YES); continue }
            when (val saved = importer.confirm(item.plan, acknowledgedWidening = acknowledged)) {
                is ScriptImporter.ConfirmResult.Saved -> installed += saved.name
                is ScriptImporter.ConfirmResult.Refused -> failed += CatalogFailure(item.descriptor.name, saved.reason)
            }
        }
        return CatalogApplied(installed, failed)
    }
}
