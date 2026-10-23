# NetProbe tools platform design (2026-10-23)

Director brief 2026-10-23: the Tools tab "doesn't feel great to work with". Make it tile based, make it modular so people can make and import their own tools, show inputs per tool (IP and Port are not always relevant), and rewrite the current tools in the modular format. Rulings from the discussion: user tools are scripts (JavaScript), the script declares its inputs and their types and the app renders them; import from a file or a URL; results show on the tool page and in Scans history. Work happens on branch `tools-platform`; nothing is published until the Director has tested it.

Lean workflow: this spec is the plan. One Gruff pass (sandbox and network safety), then sonnet batches.

## 1. Goals and non-goals
- One tool model for built-in and imported tools: a descriptor (id, name, description, icon, category, inputs, target kind) plus a run function.
- Tools tab: a grid of tiles, then a tool page whose form is generated from the descriptor, Run pinned at the bottom, progress and findings on the same page, the run also in Scans.
- Imported tools are JavaScript files run by an embedded engine inside a sandbox with a small host API (network, bytes, regex, findings).
- Existing tools keep working from the device page and keep their history (same tool keys in the database).
- Non-goals: a tool marketplace, editing scripts inside the app, running tools in the background service, tools that modify the device.

## 2. Decisions
- D1 Engine: Mozilla Rhino (`org.mozilla:rhino`), pure JVM, interpreted mode on Android. Try 1.9.1 first; if it does not run on the device (class file version or missing java.lang.invoke support), fall back to 1.7.15, which is proven on Android. This is the one new dependency, approved by the Director on 2026-10-23. The engine sits behind a `ScriptEngine` interface so Lua could be added later.
- D2 Script contract: a tool file is a JavaScript program that assigns a `tool` object (section 4). The host reads the descriptor once at import (and at app start for installed tools) and calls `tool.run(inputs, ctx)` per run.
- D3 Sandbox: `Context.initSafeStandardObjects`, a `ClassShutter` that denies every Java class, no `java`/`Packages`/`importClass` globals, no `eval` of host strings, instruction-count observer every 10k instructions to honour Stop and a wall-clock budget, script size cap 256 KB, string results capped at 1 MB. Scripts run on the tool dispatcher thread, one Rhino Context per run, never on the main thread.
- D4 Network scope: a script may only open connections to the run's target host unless its descriptor declares `network: "lan"` (any private address on the current network) or `network: "any"`. The scope is shown on the tile and at import ("This tool can contact any address"). Host API calls outside the scope throw.
- D5 Tool identity: the `ToolId` enum is replaced by a string key. Built-in keys are unchanged (`dir-bruteforce`, `http-fingerprint`, `tls-certificate`, `smb-session`, `snmp-walk`, `identify-service`, `identify-all-services`), so history rows keep resolving. Imported tool keys are `user:<slug>` where the slug comes from the descriptor id, validated `[a-z0-9-]{3,40}`; a clash with a built-in key is refused at import.
- D6 Targets: a descriptor declares `target: "port" | "host" | "none"`. Port tools get a device picker plus a port picker (open ports of that device, or typed); host tools get the device picker; none-target tools get neither. Runs without a host have `hostId = null` (schema 6). The host-busy claim applies only when there is a host; `MAX_ACTIVE_RUNS` applies to all.
- D7 Inputs: types `ip`, `port`, `text`, `number`, `bool`, `choice`, `wordlist`, `tls`. Each has `name`, `label`, optional `help`, `required` (default true), `default`, and type-specific fields (`min` and `max` for number, `options` for choice, `placeholder` for text). The `ip` and `port` inputs are implicit for `target: "port"` (both) and `target: "host"` (ip only), rendered as pickers; a tool may add more inputs of any type. `wordlist` renders the existing wordlist chooser and gives the script an array of lines.
- D8 Built-ins stay Kotlin. They are rewritten as modules against the new `ToolModule` interface with real descriptors (inputs replace the one-off DirBrute sheet: wordlist, extensions, method, concurrency, timeout, status codes, baseline become declared inputs with the same defaults). SMB, SNMP and TLS parsing are not worth re-expressing in JavaScript. Three bundled example scripts ship in `assets/tools/` so the format has working references: `http-headers.js` (port tool), `banner-grab.js` (port tool, raw TCP), `dns-lookup.js` (none-target, text input). They appear under an "Examples" category and can be copied out of the app from the tool's About page.
- D9 Storage: imported scripts live in `files/tools/<slug>.js` with `files/tools/index.json` (slug, name, version, source URL or file name, imported at, network scope, sha256). The script text is re-read at app start; a file that no longer parses is listed as broken, not loaded, and can be removed.
- D10 Import: Settings > Tools > "Import tool" and the Tools tab overflow both offer "From file" (system picker) and "From URL" (https only, fetched when the user taps Import, 256 KB cap, reuses the updater fetcher). After the fetch the app shows the descriptor (name, version, author, description, inputs, network scope) and asks to confirm before saving. Re-importing the same slug replaces the file after a confirmation.
- D11 Findings: `ctx.finding(kind, value, detail, url)` with kind one of the existing `FindingKind` names, or any string (stored as OTHER with the script's kind in `extra.kind`). The existing results sheet rows render them.
- D12 No new permissions. The file picker uses the Storage Access Framework. URL import and script network calls use the existing INTERNET permission and the manual-route binding used by manual tools today.

## 3. Tool model (Kotlin)
```kotlin
data class ToolDescriptor(val key: String, val name: String, val description: String, val icon: ToolIcon, val category: ToolCategory,
    val target: TargetKind, val inputs: List<InputSpec>, val network: NetworkScope, val version: String, val author: String?, val source: ToolSource)
enum class ToolCategory { WEB, NETWORK, IDENTITY, EXAMPLES, MINE }
enum class TargetKind { PORT, HOST, NONE }
enum class NetworkScope { TARGET, LAN, ANY }
sealed interface ToolSource { object BuiltIn : ToolSource; data class Script(val file: File, val sha256: String) : ToolSource }
sealed interface InputSpec { val name: String; val label: String; val help: String?; val required: Boolean /* plus per-type fields */ }
data class ToolInputs(val values: Map<String, Any?>)   // validated, typed values keyed by input name; ip as Inet4Address, port as Int, wordlist as WordlistRef
interface ToolModule {
    val descriptor: ToolDescriptor
    fun applies(port: Int, service: ServiceInfo): Boolean          // device page per-port buttons; NONE and HOST tools return false
    fun appliesToHost(host: HostEntity): Boolean
    suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult
}
class ToolRegistry(builtIns: List<ToolModule>, scripts: ScriptToolStore) { val all: StateFlow<List<ToolModule>>; fun byKey(key: String): ToolModule? }
```
- `ToolRunner` takes `ToolRegistry` instead of `Map<ToolId, Tool>` and a nullable `ToolTarget`. `optionsJson` becomes the JSON of `ToolInputs` (values by name; wordlist stored as id, name and entry count as today). `ToolOptions` is removed; the dir-brute options sheet is replaced by the generated form, and the form remembers the last inputs per tool key (this replaces `DirBruteOptionsStore`).
- `ToolApplicability` keeps its port rules but answers for keys; `toolsFor` returns the registry's PORT tools whose `applies` is true, built-ins first.

## 4. Script contract (JavaScript)
```javascript
tool = {
  id: "http-headers", name: "HTTP headers", version: "1.0", author: "BL457",
  description: "Fetches / and lists the response headers.",
  icon: "web", category: "web",            // icon from the fixed icon set (section 6); category web, network, identity or other
  target: "port", network: "target",       // target: port, host or none; network: target, lan or any
  inputs: [
    { name: "path", type: "text", label: "Path", default: "/", required: false }
  ],                                       // ip, port and tls are implicit for a port tool and arrive as inputs.ip, inputs.port, inputs.tls
  run: function (inputs, ctx) {
    var r = ctx.http.request({ method: "GET", url: (inputs.tls ? "https" : "http") + "://" + inputs.ip + ":" + inputs.port + inputs.path, timeoutMs: 4000 });
    ctx.progress(1, 1, 0);
    Object.keys(r.headers).forEach(function (h) { ctx.finding("HEADER", h + ": " + r.headers[h]); });
    return { status: "done", summary: r.status + " " + r.statusText };
  }
};
```
Host API (`ctx`), all synchronous from the script's point of view; each call runs `control.gate()` first and honours cancellation:
- `ctx.http.request({method, url, headers, body, timeoutMs, followRedirects})` returns `{status, statusText, headers, body, bodyBytes, url, timeMs}`; body capped at 1 MB. `ctx.http.many(requests, concurrency)` runs up to 16 at once and returns results in order; this is how a brute-force stays fast without threads in the script.
- `ctx.tcp.exchange({host, port, send, timeoutMs, readUntil or maxBytes, tls, sni})` returns `{bytes, text, closed, timeMs}`; `ctx.tcp.connect` returns a handle with `send`, `recv`, `close` for multi-step protocols.
- `ctx.udp.exchange({host, port, send, timeoutMs, maxBytes})`.
- `ctx.tls.certificate(host, port, sni)` returns the parsed leaf and chain (subject, issuer, SANs, validity, key, signature, fingerprints) using the existing TLS tool parser.
- `ctx.dns.lookup(name)` and `ctx.dns.reverse(ip)`.
- `ctx.bytes`: `fromHex`, `toHex`, `fromUtf8`, `toUtf8`, `concat`, `u16be`, `u32be`, `u16le`, `u32le` and matching readers, so SMB-style and SNMP-style packets can be built.
- `ctx.wordlist(name)` resolves a `wordlist` input to an array; `ctx.progress(done, total or null, found)`; `ctx.finding(kind, value, detail, url)`; `ctx.log(text)` (kept with the run only when the tool fails, otherwise discarded); `ctx.sleep(ms)`; `ctx.cancelled()`.
- `run` returns `{status: "done" or "failed", summary}`; a thrown error ends the run FAILED with the error message as the summary. Returning nothing means done, with the finding count as the summary.

## 5. UI
- Tools tab root: title row, overflow (Import tool, Wordlists), then a grid of tiles (2 columns on a phone), grouped by category with headers; a tile shows icon, name, one line, and a small badge for network scope ANY and for MINE. Long-press a tile: About, Remove (MINE only), Copy source (scripts).
- Tool page: name and description, the generated form (device picker with search over discovered devices, port picker listing that device's open ports with service names plus a typed port, then the tool's own inputs in declared order), Run pinned at the bottom outside the scroll, replaced by progress and Stop while running (the pinned-controls rule). Below the form: the live progress line, then findings as they arrive using the results sheet rows, then "Previous runs" for this tool. Back returns to the grid.
- The device page keeps its per-port tool buttons and the host-level Identify all services; they open the tool page with the device and port prefilled, or run directly when the tool has no further inputs (today's behaviour).
- Scans tab unchanged apart from tool names coming from the registry.
- Settings: a "Tools" group with Import tool and the My tools list (name, version, scope, Remove).

## 6. Icons
Fixed named set mapped to Material icons, chosen by me and shown to the Director in the hand-over: `web` (Language), `search` (Search), `lock` (Lock), `folder` (FolderOpen), `share` (Share), `dns` (Dns), `terminal` (Terminal), `fingerprint` (Fingerprint), `network` (Lan), `wifi` (Wifi), `info` (Info), `bolt` (Bolt). Unknown icon names fall back to `terminal`.

## 7. Data
- Schema 6: `tool_runs.hostId` becomes nullable; `MIGRATION_5_6` recreates the table (SQLite cannot drop NOT NULL), preserving rows and the foreign key with ON DELETE CASCADE; the findings table is untouched. Exported schema 6.json committed.
- `files/tools/index.json` as in D9. Last-used inputs per tool key in the `netprobe` SharedPreferences as JSON.

## 8. Safety
- Scripts cannot reach Java, the file system, the clipboard or Android APIs: only `ctx`.
- Network scope (D4) checked on every host API call against the run target and the current network's known ranges; DNS names in `http.request` are resolved first and the resolved address is checked.
- Budgets per run: wall clock 10 minutes, 20,000 host API calls, response bodies 1 MB each, 50 MB total transferred, findings 5,000. Exceeding a budget ends the run FAILED with a clear summary.
- Import shows the descriptor and scope before saving; nothing executes at import beyond evaluating the file to read `tool` (that evaluation runs inside the same sandbox with a 2 s budget and no `ctx`).
- Imported scripts are never fetched or updated automatically.

## 9. Batches (sonnet, gate per batch, no per-task reviews)
- B1 Model and runner: ToolDescriptor, InputSpec, ToolInputs, ToolModule, ToolRegistry; schema 6; ToolRunner on the registry with nullable targets; built-ins rewritten as modules (dir-brute inputs replace ToolOptions.DirBrute; options JSON keeps the same field names so history and export still read); ToolApplicability by key; all existing tests green or updated.
- B2 Engine: Rhino dependency with the 1.9.1 then 1.7.15 rule, `ScriptEngine` interface, sandbox, instruction observer, host API, `ScriptToolModule`, descriptor parsing and validation, budgets, network scope, three example scripts loaded from assets, JVM tests with a fake transport for every host API call and for every refusal.
- B3 UI: grid, tool page with generated form and pickers, pinned Run and Stop, inline progress and findings, previous runs; device page and Scans wiring; Copy objects and template tests.
- B4 Import and management: ScriptToolStore (files, index, broken entries), import from file and URL with the confirmation sheet, My tools in Settings, long-press actions, last-used inputs.
- B5 Device pass by the Director on a release-signed test build; fixes; CHANGELOG section; merge to master and publish only on his word.

## 10. Rulings on the Gruff review (2026-10-23, review file 2026-10-23-tools-platform-gruff-review.md)
- G1 (B2) The sandbox is the host object design, not the ClassShutter alone: ctx and everything it returns are plain Scriptable or BaseFunction objects built by hand; a WrapFactory whose wrapAsJavaObject throws; javaPrimitiveWrap off; sealed shared scope; globals java, Packages, JavaAdapter, importClass, importPackage, getClass removed. A hostile test suite ships with the engine: getClass, constructor.constructor, Function("return this")(), __defineGetter__ walks, JavaAdapter, Packages, javaException on caught errors.
- G2 (B2) DNS under TARGET scope resolves only the target host's own name; ctx.dns.lookup of anything else throws. Every connection is made to the exact InetAddress that passed the scope check (no second resolution); redirects re-run the full check per hop; non-canonical IP literals (decimal, octal, hex, v4-mapped v6) are rejected.
- G3 (B2) LAN scope means inside the current NetworkEnv subnet only, minus loopback, multicast, broadcast and the network address; IPv6 and v4-mapped forms are normalised before the check. ANY scope asks for confirmation on every run, not only at import.
- G4 (B2) Native denial of service: string length capped at 1 MB and array length at 100,000 by overriding the builtins; RegExp replaced by ctx.regex with a step limit; a watchdog thread abandons a run whose wall clock or instruction budget is exceeded even if the script is inside a native call; the budget error is a Rhino Error subclass that script try/finally cannot swallow.
- G5 (B2) Scripts run on their own dispatcher (not the shared tool dispatcher); http.many uses its own bounded pool; cancellation closes every socket opened by the run; blocking calls are tied to the run Job.
- G6 (B1) ToolRunner is keyed by runId; host-busy, onFinished and per-host stop apply only when hostId is non-null.
- G7 (B1) Nullable hostId: LEFT JOIN everywhere, "No device" label, null-safe export; tool_runs gains toolName (display-name snapshot) so removed tools still read in history.
- G8 (B1) MIGRATION_5_6 copies tool_runs and tool_findings into new tables, drops the child first, then the parent, then renames; a migration test seeds findings and asserts the count.
- G9 (B4) Import shows the source (view source), diffs scope, inputs and sha256 on replace and re-asks consent when the scope widens, rejects version downgrades, labels author as unverified, requires https on every redirect hop.
- G10 (B1) Input type secret: masked, never persisted, never exported, never remembered. SNMP community becomes a secret input with default public. Also list and multichoice types (dir-brute extensions and status codes).
- G11 (B2) Findings: url must be http or https; value and detail capped at 2 KB; total findings bytes per run capped at 2 MB; inserts rate-limited.
- G12 (B1) Built-ins are rewritten one at a time with a golden test on the dir-brute optionsJson; tests are never weakened.
- G13 (B2) Rhino spike checklist: artifact exists on Maven Central; debug and minified release builds; API 26 emulator is not available, so the Pixel 7 Pro plus a JVM run; interpreted mode forced; R8 keep rules; no javax.script or java.beans references; APK size and cold-start delta; hostile suite from G1 and the G4 cases. The script contract and examples are pinned to an ES5 subset so the 1.7.15 fallback changes nothing visible.
- G14 (B3) Form rules: ip, port and tls are reserved input names; remembered values are dropped for inputs whose type changed or that no longer exist; validation on every edit with inline errors and Run disabled until valid; label 40 chars, help 200 chars, at most 12 options; Back during a run keeps the run going and the Scans tab owns it.

## 11. Tool catalog on GitHub (2026-10-23, after the 0.4.0 release)

Goal: a folder in the repo that holds community tools, which the app can pull in bulk ("Update tools from GitHub"), including updating tools already installed. The Director will accept pull requests into that folder.

- Repo folder `catalog/`: one `<id>.js` per tool (the same script format as the guide), `index.json` listing every tool (`id`, `file`, `name`, `version`, `sha256`, `network`, `description`, `broadcast`), and `catalog/README.md` explaining how to contribute (write the script with the guide, run `tools/make-catalog-index.ps1`, open a pull request). `tools/make-catalog-index.ps1` evaluates nothing; it reads the `tool = {...}` literal with a small parser for the listed fields (or the contributor fills them) and hashes the file. A unit test checks that every script in `catalog/` evaluates in the sandbox, parses, and matches the index entry (name, version, sha256, network).
- Catalog URL: `UpdateConfig.CATALOG_INDEX_URL = https://raw.githubusercontent.com/BL457/netprobe/master/catalog/index.json`; scripts are fetched from `https://raw.githubusercontent.com/BL457/netprobe/master/catalog/<file>`. Fetched with `HttpsUpdateFetcher` (https only on every hop, 1 MB cap each).
- Action "Update tools from GitHub" in the Tools overflow and in Settings > Tools. Flow: fetch the index; for every entry whose `user:<id>` is not installed, or is installed with a different sha256, fetch the script and run the normal import pipeline (sandbox evaluation, descriptor parse); then show one review sheet with three lists: New (name, version, scope, broadcast), Updated (name, old version to new version, scope change called out in the warning colour when it widens, broadcast newly declared called out), Failed (name, reason: downgrade refused, parse error, fetch error). Unchanged tools are a single count line. One Install button applies every New and Updated entry; Cancel applies none. Tools installed this way carry `ToolSource.Catalog(file, sha256)`; a locally imported tool with the same id is listed under Updated as "replaces your copy". A tool removed from the catalog stays on the phone.
- Nothing is fetched automatically, ever: only the explicit action.
- Broadcast: a descriptor may declare `broadcast: true`. Only then may `ctx.udp.exchange` (and `ctx.udp.send`, added here: fire-and-forget, no reply wait) target the current subnet's directed broadcast address, under `network: "lan"` only; 255.255.255.255 stays refused. The review sheet and the About sheet show "Can send broadcast packets on your network". At most 50 broadcast datagrams per run, 1 KB each. Every other rule of the scope guard stays.
- Host target tools receive `inputs.mac` (lower-case, colon separated) when the chosen device has a known MAC, else an empty string; add `mac` to `ToolTarget` and reserve the input name. The guide documents it.
- First catalog entry: `catalog/wake-on-lan.js`: target host, network lan, broadcast true, icon bolt, category network; inputs: `mac` text (prefilled from the device via inputs.mac, editable; validated as 12 hex digits with optional separators), `port` number default 9 (range 1 to 65535), `repeat` number default 3 (1 to 10). Builds the magic packet (6 x 0xFF then the MAC 16 times) with ctx.bytes, sends it `repeat` times to the directed broadcast address via `ctx.udp.send`, then one finding "Magic packet sent to <mac> (<n> times)" and status done. Written by following `docs/tools-scripting.md` as a user would; where the guide is unclear, fix the guide.
- Docs: the guide gains `broadcast`, `inputs.mac` and `ctx.udp.send`; `docs/release.md` gains the catalog step; the README mentions the catalog and the Update tools action.

## 12. Host API additions for the web screenshot gallery (2026-10-23, for 0.4.3)

Goal: the web screenshot gallery ships as a catalog script, not a built-in. Two things a script cannot do today are added to the host API, and findings gain an image form.

- `ctx.devices()`: for tools with `network: "lan"` (or `"any"`), returns the discovered devices of the current network as plain objects `{ ip, mac, name, vendor, ports: [{ port, service, tls }] }`, newest scan first, at most 1,024 entries; under `network: "target"` it throws. Tools with `target: "none"` use it to walk the network. `ctx.devices()` counts as one host call.
- `ctx.web.snapshot({ url, timeoutMs, width })`: renders the URL in an off-screen Android WebView (JavaScript on, cookies off, no file or content URLs, same scope checks as `ctx.http`, every redirect hop checked, 15 s default timeout, 1024 px width default) and returns `{ status, title, finalUrl, image }` where `image` is an opaque image id the script passes to a finding. The WebView runs on the main thread via the app, one render at a time, bitmaps scaled to at most 640 px wide and stored as JPEG under `files/snapshots/<runId>/<n>.jpg`, at most 200 per run and 20 MB per run; a run's images are deleted when its history row is deleted (Clear history, Remove tool keeps them). Rendering counts as a host call and transfers against the 50 MB budget. Snapshot requests through the WebView carry the app's normal user agent.
- Findings: `ctx.finding(kind, value, detail, url, extra)` accepts `extra.image = <image id>`; the results list shows such findings as a thumbnail row (value as caption, url opens the page), tapping opens a full-size viewer with Copy URL and Open in browser. Export writes the image path. History keeps the thumbnails.
- `ctx.bytes.base64(bytes|string)`, `ctx.bytes.fromBase64(text)`, `ctx.bytes.md5(bytes|string)`, `ctx.bytes.sha1`, `ctx.bytes.sha256` (hex strings): closes the Basic and Digest auth gap found by the default-creds tool. Guide note: a `list` input default is a comma-separated string.
- Catalog script `catalog/web-gallery.js`: target none, network lan, category web, icon web. Inputs: `ports` list (default "80,443,8080,8443,8000,8008,8081,8096,8123,9000,5000,5001,32400"), `onlyOpen` bool (default true: only ports the scan found open), `timeoutS` number (default 10, 3 to 30), `maxPages` number (default 60, 1 to 200). Walks `ctx.devices()`, for each device and matching port builds http or https URL (tls from the port record, else 443/8443/5001 as https), snapshots it, emits one image finding per page with the title as detail; progress per page; summary "N pages from M devices".
- Tests: host API unit tests with a fake renderer (scope refusal per hop, per-run caps, file cleanup on history delete, image id cannot escape the run), descriptor and script tests for the gallery with a fake transport and fake renderer, Copy template tests, a results-list model test for the thumbnail row. The WebView itself is exercised only on the device pass.
