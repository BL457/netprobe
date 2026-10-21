package com.northwalessolutions.netprobe.ui.wordlists

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.BackRow
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlCheck
import com.northwalessolutions.netprobe.wordlists.UrlImportRules
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import com.northwalessolutions.netprobe.wordlists.WordlistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class WordlistsActions(val onBack: () -> Unit = {}, val onImport: () -> Unit = {}, val onDelete: (WordlistMeta) -> Unit = {},
                            val onImportUrl: (URL) -> Unit = {}, val onCancelUrl: () -> Unit = {})

/** The snackbar text for an import (3B spec 9, 3C spec 5.3 and 7.2). */
object WordlistImport {
    fun message(result: ImportResult): String = when (result) {
        is ImportResult.Imported -> WordlistCopy.imported(result.meta.name, result.meta.entries, result.skipped)
        ImportResult.TooLarge -> WordlistCopy.TOO_LARGE
        ImportResult.TooMany -> WordlistCopy.TOO_MANY
        ImportResult.Empty -> WordlistCopy.EMPTY_FILE
        ImportResult.Unreadable -> WordlistCopy.UNREADABLE
    }
    fun message(result: UrlImportResult): String = when (result) {
        is UrlImportResult.Stored -> message(result.result)          // the store's own messages, reused as they are
        is UrlImportResult.Failed -> failure(result.reason)
        UrlImportResult.Cancelled -> WordlistCopy.CANCELLED
    }
    fun failure(f: FetchFailure): String = when (f) {
        FetchFailure.UnknownHost -> WordlistCopy.FETCH_HOST
        FetchFailure.NoAnswer -> WordlistCopy.FETCH_NO_ANSWER
        FetchFailure.Tls -> WordlistCopy.FETCH_TLS
        FetchFailure.Connection -> WordlistCopy.FETCH_CONNECTION
        is FetchFailure.Status -> WordlistCopy.fetchStatus(f.code)
        is FetchFailure.NotText -> WordlistCopy.fetchNotText(f.type)
        FetchFailure.Insecure -> WordlistCopy.FETCH_INSECURE
        FetchFailure.LocalTarget -> WordlistCopy.FETCH_LOCAL
        FetchFailure.TooManyRedirects -> WordlistCopy.FETCH_REDIRECTS
    }
}

private val SECTION_PAD = 10.dp
private val ROW_VERTICAL = 8.dp
private val ROW_END = 6.dp
private val DELETE_PAD = 12.dp
private val PILL_ROW_PAD = 6.dp
private val PILL_GAP = 8.dp
private val NO_LISTS: StateFlow<List<WordlistMeta>> = MutableStateFlow(emptyList())
private const val PICKER_MIME = "text/plain"

/**
 * The store, resolved off the main thread: its first access scans the built-in resources, so it never happens during composition.
 * Null until it is ready.
 */
@Composable
internal fun rememberWordlistStore(container: AppContainer): State<WordlistStore?> =
    produceState<WordlistStore?>(null, container) { value = withContext(Dispatchers.IO) { container.wordlistStore } }

/** The wordlist metadata, empty until the store has loaded. */
@Composable
internal fun rememberWordlists(container: AppContainer): State<List<WordlistMeta>> {
    val store by rememberWordlistStore(container)
    return (store?.lists ?: NO_LISTS).collectAsStateWithLifecycle()
}

/**
 * The Wordlists screen (3B spec 8.6, 3C spec 5.1), stateless: a pinned top block (back row, title, the two import pills and the URL
 * Cancel) and a scrolling list of built-in then imported lists. Delete confirms in an AlertDialog. No import control is inside the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordlistsContent(lists: List<WordlistMeta>, importing: Boolean, message: String?, onMessageShown: () -> Unit, actions: WordlistsActions,
                     formatDate: (Long) -> String = RelativeTime::defaultDate, loading: Boolean = false, urlState: UrlImportState = UrlImportState.Idle) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    var confirm by remember { mutableStateOf<WordlistMeta?>(null) }
    var askUrl by rememberSaveable { mutableStateOf(false) }
    val fetching = urlState as? UrlImportState.Fetching
    val busy = importing || fetching != null || loading
    Scaffold(containerColor = NetProbeColors.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
            BackRow(WordlistCopy.BACK, actions.onBack)
            Text(WordlistCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.padding(horizontal = Dimens.gutter))
            // A FlowRow, so Cancel wraps to a second line at font scale 1.3 instead of clipping; still pinned.
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = PILL_ROW_PAD), horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
                ActionPill(if (importing) WordlistCopy.IMPORTING else WordlistCopy.IMPORT, enabled = !busy, onClick = actions.onImport, tag = "importWordlist")
                ActionPill(fetching?.let { WordlistCopy.fetching(it.read, it.total) } ?: WordlistCopy.IMPORT_URL, enabled = !busy, onClick = { askUrl = true }, tag = "importUrl")
                if (fetching != null) TextButton(onClick = actions.onCancelUrl, modifier = Modifier.heightIn(min = Dimens.touch).testTag("cancelUrl")) {
                    Text(WordlistCopy.CANCEL, style = NetProbeText.action, color = NetProbeColors.signal)
                }
            }
        }
    }) { pad ->
        val builtIn = lists.filter { it.builtIn }; val imported = lists.filter { !it.builtIn }
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("wordlistList")) {
            if (loading) item(key = "loading") {
                Text(WordlistCopy.LOADING, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = SECTION_PAD).testTag("wordlistsLoading"))
            } else {
            item(key = "builtInHeader") { SectionHeader(WordlistCopy.BUILT_IN) }
            items(builtIn, key = { it.id }) { WordlistRow(it.name, WordlistCopy.entriesBuiltIn(it.entries), null) }
            item(key = "importedHeader") { SectionHeader(WordlistCopy.IMPORTED) }
            }
            if (!loading && imported.isEmpty()) item(key = "empty") {
                Text(WordlistCopy.NO_IMPORTED, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = SECTION_PAD))
            }
            items(imported, key = { it.id }) { meta ->
                WordlistRow(meta.name, WordlistCopy.entriesImported(meta.entries, formatDate(meta.importedAt ?: 0L)), meta.id) { confirm = meta }
            }
        }
    }
    confirm?.let { meta ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = NetProbeColors.surface,
            title = { Text(WordlistCopy.deletePrompt(meta.name), style = NetProbeText.rowName, color = NetProbeColors.text) },
            confirmButton = { TextButton({ confirm = null; actions.onDelete(meta) }, modifier = Modifier.testTag("confirmDelete")) { Text(WordlistCopy.DELETE, color = NetProbeColors.signal) } },
            dismissButton = { TextButton({ confirm = null }) { Text(WordlistCopy.CANCEL, color = NetProbeColors.textSecondary) } })
    }
    if (askUrl) UrlDialog(onDismiss = { askUrl = false }) { url -> askUrl = false; actions.onImportUrl(url) }
}

/** "Import from URL" (3C spec 5.1): one field, validated as typed; the error shows once the field is not blank. */
@Composable
private fun UrlDialog(onDismiss: () -> Unit, onImport: (URL) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val check = UrlImportRules.validate(text)
    val ok = check as? UrlCheck.Ok
    val error = (check as? UrlCheck.Invalid)?.error?.takeIf { text.isNotBlank() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = NetProbeColors.surface,
        title = { Text(WordlistCopy.IMPORT_URL, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("urlField"), label = { Text(WordlistCopy.URL_LABEL) },
                placeholder = { Text(WordlistCopy.URL_PLACEHOLDER) }, singleLine = true, isError = error != null,
                supportingText = error?.let { e -> { Text(e, color = NetProbeColors.error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), textStyle = NetProbeText.data,
                colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        },
        confirmButton = {
            TextButton({ ok?.let { onImport(it.url) } }, enabled = ok != null, modifier = Modifier.testTag("urlImport")) {
                Text(WordlistCopy.URL_IMPORT, color = if (ok != null) NetProbeColors.signal else NetProbeColors.border)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(WordlistCopy.CANCEL, color = NetProbeColors.textSecondary) } })
}

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = SECTION_PAD)) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(title, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = SECTION_PAD, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
private fun WordlistRow(name: String, line: String, deleteId: String?, onDelete: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.gutter, end = ROW_END, top = ROW_VERTICAL, bottom = ROW_VERTICAL),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NetProbeText.secondary, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        if (deleteId != null) {
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = WordlistCopy.deleteDescription(name) }.testTag("delete:$deleteId"), contentAlignment = Alignment.Center) {
                Text(WordlistCopy.DELETE, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = DELETE_PAD))
            }
        }
    }
}

/** Route "wordlists": SAF import (ACTION_OPEN_DOCUMENT, text/plain, no permission), import on Dispatchers.IO, delete through the store. */
@Composable
fun WordlistsScreen(container: AppContainer, onBack: () -> Unit) {
    val store by rememberWordlistStore(container)
    val lists by rememberWordlists(container)
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val visible = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { visible.set(false) } }
    val urlImport = container.urlImport
    val urlState by urlImport.state.collectAsStateWithLifecycle()
    val urlMessage by urlImport.message.collectAsStateWithLifecycle()
    // While attached, the URL result goes to this screen's snackbar; once the screen is gone it goes to AppMessages (Home notice line).
    DisposableEffect(urlImport) { urlImport.attach(); onDispose { urlImport.detach() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = store
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        importing = true
        // On the app scope: leaving the screen mid-import must not cancel it; the result then goes to AppMessages (Home notice line).
        container.appScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { target.import(displayName(ctx, uri), it) } ?: ImportResult.Unreadable
                }
            } catch (ce: CancellationException) { throw ce } catch (_: Exception) { ImportResult.Unreadable }
            val text = WordlistImport.message(result)
            if (visible.get()) { importing = false; message = text } else container.appMessages.post(text)
        }
    }
    WordlistsContent(lists, importing, message ?: urlMessage, { if (message != null) message = null else urlImport.consumeMessage() },
        WordlistsActions(onBack = onBack, onImport = { launcher.launch(arrayOf(PICKER_MIME)) },
            onDelete = { meta -> scope.launch { store?.delete(meta.id) } },
            onImportUrl = { url -> urlImport.start(url) }, onCancelUrl = { urlImport.cancel() }),
        loading = store == null, urlState = urlState)
}

private fun displayName(ctx: Context, uri: Uri): String? =
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
