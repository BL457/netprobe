# NetProbe Phase 3C Feedback Design (settings reset, Aggressive scan, multicast listening, manual tools, wordlist URL import)

Date: 2026-10-21. Author: Ada (Engineering Lead). Status: draft for Gruff's review and the Director's confirmation of the items in section 10.
Baseline: branch `phase3b` at 40b88f0 (Phase 3A plus the 3B tools, 883 JVM tests). 3C starts only after the 3B device QA passes.
Related specs: `2026-10-19-extended-discovery-design.md` (the "Phase 2 spec": candidate ranges, gateway probing, manual CIDRs, binding), `2026-10-19-radar-ui-design.md` (the "Radar spec": Settings layout, Home overflow, tokens, copy rules), `2026-10-20-phase3a-polish-design.md` (the "3A spec": Undo, `appScope`, schema 2), `2026-10-20-phase3b-followup-tools-design.md` (the "3B spec": `ToolRunner`, `HostBusyRegistry`, `WordlistStore`, `HttpClient`, results sheet, export, schema 3).

The Director's feedback, verbatim:

> Reset sliders in settings to default in a way that doesn't fill the settings page with loads of buttons
> Manual tools page for manually firing off tools without needing to use a scan
> Trust the subnet mask where appropriate as the baseline and scan further with a toggle option (Aggressive scan)
> Can you listen for multicast on wifi on android?
> Add manual ranges to scan
> Add a smart discovery (rfc1918)... using my gateway's IP, find other likely gateway IPs on similar ranges then scan those ranges
> Import wordlist from url

## Purpose and decisions

Six pieces of work, all small, none touching the scan engine's liveness rules: a quiet way back to default settings (section 1); the Phase 2 extended discovery renamed and surfaced as "Aggressive scan" (section 2); passive mDNS and SSDP listening between scans (section 3); a manual tools screen for any private IP and port (section 4); wordlist import from a URL (section 5); scope and plan order (section 6).

Standing rules carry over: Scan, Stop and Run controls are pinned outside any scroll container; no em-dashes; no disclaimers, warnings or authorisation prompts in UI copy; no new dependencies; no downloads; every user-facing string is in the copy table (7.2) and checked by `CopyTableTest` or `ToolCopyTableTest`.

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | One "Reset to defaults" item in a new Settings overflow menu; no per-section reset buttons | Director (brief) |
| D2 | Each slider and the "Maximum extra ranges" stepper shows a small tappable "Default N" marker only while its value differs from the default; a tap resets that one control | Ada |
| D3 | Reset covers every switch, slider, stepper and segmented control in Settings (15 values); it keeps the NVD API key, manual ranges, wordlists, the vulnerability cache, the location permission and the brute-force sheet's remembered options | Ada, assumption A1 |
| D4 | Reset is undone from the Settings snackbar ("Undo", long duration), not through `UndoStore`; no confirmation dialog | Ada (`UndoStore` is for database deletes and its notice lives on Home) |
| D5 | "Extended discovery" is renamed "Aggressive scan" everywhere it is user-facing; keys, classes and behaviour are unchanged | Director |
| D6 | The Aggressive scan section moves to second place in Settings, directly under "Discovery", and the Home overflow gains a checkable "Aggressive scan" item bound to the same setting | Ada ("make it discoverable") |
| D7 | "Probe for gateways" is relabelled "Smart discovery", with the Director's own description as its subtitle | Director's words, Ada (label) |
| D8 | The manual ranges editor is editable while Aggressive scan is off (ranges are saved but swept only when it is on) and never depends on Smart discovery | Director ("works without gateway probing"), Ada (editable while off) |
| D9 | Smart discovery gains a bounded outward walk around seed hits outside the phone's own /16 (2.4) | Ada; Phase 2 has no such rule (section 9 row 2) |
| D10 | Passive multicast listening behind a new Settings switch "Listen between scans", default off; its own `MulticastLock`, held only while the switch is on, NetProbe is in the foreground and a Wi-Fi or Ethernet network is up | Director (brief) |
| D11 | Heard devices enter the inventory through `RadarCollector`'s guarded write with a new `upsertHeard`, never by a direct DAO call; listening sends nothing | Ada |
| D12 | Manual tools: a "Run a tool" screen from the Home overflow ("Run a tool..."), RFC1918 targets only | Director (entry point), Ada (RFC1918), open item 10.5 |
| D13 | A manual target is a `hosts` row: the existing row for (network, IP) when there is one, else a new row flagged `manualOnly`, hidden from every device list and promoted to a normal row when discovery or listening later finds that IP | Ada (7.1 justifies) |
| D14 | Schema 3 to 4: one column, `hosts.manualOnly INTEGER NOT NULL DEFAULT 0` | Ada |
| D15 | Wordlist URL import through `HttpURLConnection`, not the 3B `HttpClient` (5.2) | Ada |
| D16 | URL import: HTTPS or HTTP, `text/plain` only, at most one redirect and only to the same host and port without a downgrade to HTTP, the store's 10 MB and 200,000-entry caps, no credentials | Director (brief), Ada (same-host rule) |
| D17 | The 3B pill "Import wordlist" becomes "Import file", beside the new "Import from URL" | Ada (two imports need two names) |
| D18 | SMB share listing moves from 3C to Phase 3D; nothing in it changes | Director (brief) |

## 1. Settings reset

### 1.1 Defaults

`SettingsState()` (no arguments) is the single source of defaults. `AppSettings.load()` is changed to read every default from a `SettingsState()` instance instead of repeating literals (`prefs.getBoolean("mdns", d.mdns)` and so on), so the two can never drift. New pure object in `data/Settings.kt`:

```kotlin
object SettingsDefaults {
    val DEFAULTS = SettingsState()
    /** Every covered value back to its default; the NVD key is kept (D3). */
    fun reset(current: SettingsState): SettingsState = DEFAULTS.copy(nvdApiKey = current.nvdApiKey)
    fun differs(current: SettingsState): Boolean = reset(current) != current
}
```

Covered values (15): `mdns`, `ssdp`, `netbios`, `reverseDns`, `snmp`, `httpTitle`, `listenMulticast` (new, section 3), `sweepConcurrency`, `connectTimeoutMs`, `scanConcurrency`, `scanTimeoutMs`, `maxPrefix`, `extEnabled`, `extMax`, `extGatewayProbe`.

Not covered: `nvdApiKey` (a credential the Director typed), `extManual.<networkId>` (per-network data, not a setting), the Wordlists row (data), "Clear vulnerability cache" (an action), the location permission (system state), and `DirBruteOptionsStore` (the brute-force sheet's last-used values; they live in the sheet, not in Settings, and are re-chosen at every run). The 3B Tools section holds only the Wordlists row, so it has nothing to reset.

### 1.2 Settings overflow

The Settings top bar becomes a `Row`: `BackToHome` at the start, and at the end a 48 dp `MoreVert` icon button in `textSecondary`, description "Settings menu", tag `settingsMenu`. Its `DropdownMenu` (`surface`, as `HomeMenu`) has one item, "Reset to defaults", tag `resetDefaults`, enabled only when `SettingsDefaults.differs(s)`.

Tapping it:

1. Captures `before = s`.
2. Calls `container.settings.update { SettingsDefaults.reset(it) }` (one `SharedPreferences` edit, so a kill mid-reset never leaves half the values reset).
3. Shows the Settings snackbar "Settings reset to defaults" with action "Undo", `SnackbarDuration.Long`.
4. On `SnackbarResult.ActionPerformed`: `update { before.copy(nvdApiKey = it.nvdApiKey) }` (a key typed after the reset survives the undo).

Leaving Settings dismisses the snackbar and ends the chance to undo. A reset during a scan behaves as toggling the same values by hand does today: the running scan keeps the options it started with.

### 1.3 Default markers

`StepSlider` gains `default: Int` and the Phase 2 `Stepper` gains the same. When `value != default`, the label row shows, before the value, a marker "Default 128" (unit appended as the value shows it: "Default 600 ms") in `caption` / `textSecondary`, inside a 48 dp target, description "Reset <label> to <value><unit>", tag `default:<label>`. A tap calls `onCommit(default)`. When the value equals the default the marker is absent and takes no space. Defaults come from `SettingsDefaults.DEFAULTS`. Segmented controls and switches get no marker; the overflow reset covers them.

## 2. Aggressive scan

### 2.1 Baseline and toggle

The baseline is unchanged: the home subnet (`SubnetMath.sweepRange(ip, prefix, maxPrefix).subnet`, which trusts the mask up to the "Large networks" limit and clamps anything larger to the phone's /24) is always swept. Everything beyond it stays behind `extEnabled`, now shown as "Aggressive scan". No key, class, enum or engine behaviour is renamed or changed; Phase 2 QA results stand.

User-facing renames (copy table): the Settings section title and switch label, `ExtendedCopy.TITLE`, `ExtendedCopy.SUPPORT`, `ExtendedCopy.PROBE_LABEL`, `ExtendedCopy.PROBE_SUPPORT`, `HomeCopy.NO_EXTRA_RANGES`, `HomeCopy.GATEWAY_FOUND_NONE`, `HomeCopy.GATEWAY_ANSWERS_ALL` and `HomeCopy.SOURCE_GATEWAY`. The Home progress line wording ("probing 10.0.0.0/16 for gateways") describes the action and is kept.

### 2.2 Discoverability

- Settings order becomes: Discovery, Aggressive scan, Speed, Large networks, Permissions, Vulnerabilities, Tools.
- `HomeMenu` gains, after "Run a tool...", a `DropdownMenuItem` "Aggressive scan" with a trailing `Check` icon (`signal`) when on and none when off; description "Aggressive scan, on" or "Aggressive scan, off"; tag `menuAggressive`. A tap toggles `extEnabled` and closes the menu. It is disabled while a radar scan or Quick scan all runs (`ScanBinding` and the range grouping read `extEnabled` mid-run).

### 2.3 Settings section, in order

1. Switch "Aggressive scan", subtitle "Your own subnet is always scanned. Turn on to scan further, into other private ranges your phone can reach." Default off.
2. Stepper "Maximum extra ranges" (unchanged, gains the D2 marker, default 4). Disabled while off, as today.
3. Switch "Smart discovery", subtitle "Uses your gateway's IP to find other likely gateways on similar private ranges, then scans those ranges. Up to about 1,600 connection attempts, about 13 s." Default off. Disabled while Aggressive scan is off, as today.
4. "Manual ranges for <network name>" editor: unchanged validation and cap marking (Phase 2 4.4), now enabled whenever a network is current, whatever the switches say. Beneath the title, `data` / `textMuted`: "Swept first, with or without Smart discovery" when Aggressive scan is on, "Saved now, swept when Aggressive scan is on" when it is off. With no network, disabled with "Connect to a network to edit its ranges" (unchanged).

Manual ranges without Smart discovery already work: `HomeViewModel.discoverOn` builds `ExtendedOptions(manual = c.settings.manual.get(net.id))` from `extEnabled` alone, and `CandidateRanges.derive` takes manual units first whatever `gatewayProbe` says. 3C locks that in with tests (8.1 items 5 and 6). `HomeViewModel.baseState` keeps reading manual ranges only when `extEnabled` (toggle off is the MVP path).

### 2.4 Smart discovery probe set, with the Director's example

Restated from Phase 2 4.3 and checked against `GatewayTargets.of` and `GatewayProbe.run`. Phone `10.0.3.20/24`, gateway `10.0.3.1`, `maxPrefix` 22:

- Swept local range `10.0.3.0/24`; enclosing /16 `10.0.0.0/16`; gateway offset `.1`, so no extra offset (`takeUnless { it == LOW || it == HIGH }`).
- Walk: every /24 of `10.0.0.0/16` except `10.0.3.0/24`, at `.1` and `.254`: `10.0.0.1`, `10.0.1.1`, `10.0.2.1`, `10.0.4.1` ... `10.0.255.1`, and each `.254`. That is 255 units and 510 addresses. The Director's `10.0.0.1`, `10.0.1.1` and `10.0.2.1` are all in it, and so is every other /24 of his /16: the walk is not "outward from a hit", it is the whole /16, which is a superset.
- Seeds: `GatewayTargets.SEEDS` minus those inside `10.0.0.0/16` (`10.0.0.1`, `10.0.1.1`, `10.0.0.254`), leaving 17: `10.1.1.1, 10.1.10.1, 10.10.0.1, 10.10.10.1, 172.16.0.1, 172.16.1.1, 172.16.16.1, 172.17.0.1, 172.31.0.1, 192.168.0.1, 192.168.1.1, 192.168.1.254, 192.168.2.1, 192.168.10.1, 192.168.88.1, 192.168.100.1, 192.168.178.1`.
- Ports 80 then 443, 400 ms, 64 in flight, TEST-NET pre-check first; worst case for this network (527 addresses x 2 ports + 4) is 1,058 attempts.

Phase 2 has no rule for continuing outward from a hit; outside the phone's /16 only the seed addresses are probed. 3C adds one (D9), in `GatewayProbe.run` after the first pass:

- For each seed that answered and lies outside the phone's enclosing /16, at `a.b.c.o`: walk `a.b.(c-1).o`, `a.b.(c-2).o` ... and `a.b.(c+1).o`, `a.b.(c+2).o` ... within the seed's own /16 (third octet 0 to 255).
- Each direction stops after `OUTWARD_MISS_LIMIT = 2` consecutive misses or `OUTWARD_MAX_UNITS = 16` probed units. A unit already probed (by the walk, a seed or another outward walk) is skipped and not counted as a miss. Units overlapping the swept range are skipped.
- Same ports, timeout, semaphore, `Probe.connect` with the bound `Network`, answer rule and `onAnswer`; hits join `hits` with source `GATEWAY_PROBE`, ordered by address, under the cap and the phantom guard exactly as other hits. `GatewayProbeProgress.total` grows as units are added and stays monotonic.
- Worst case per answering seed is 32 addresses (64 attempts); no extra attempts when no seed answers, which is the Director's home case.

Example: a phone on `192.168.1.0/24` where `10.1.1.1`, `10.1.2.1` and `10.1.3.1` answer. Downward: `10.1.0.1` misses, then the octet runs out. Upward: `10.1.2.1` hits, `10.1.3.1` hits, `10.1.4.1` and `10.1.5.1` miss, stop. Hits: `10.1.1.0/24`, `10.1.2.0/24`, `10.1.3.0/24`.

## 3. Multicast listening

### 3.1 What and when

New `SettingsState.listenMulticast: Boolean = false`, key `listenMulticast`. Settings "Discovery" gains, after "HTTP page titles", the switch "Listen between scans" with subtitle "While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP." When on, a status line follows in `data` / `textMuted` (copy table): listening on both, mDNS only, SSDP only, could not open the ports, no network, paused while a scan runs, or "mDNS and SSDP are off above" when both of those switches are off.

The listener hears and never sends: no queries, no M-SEARCH, no description fetch. A heard device gets names and UPnP details at the next scan.

### 3.2 Gate and lifecycle

Pure function, `engine/discovery/ListenGate.kt`:

```kotlin
data class ListenPlan(val mdns: Boolean, val ssdp: Boolean)
object ListenGate {
    /** Null means: hold no lock, open no socket. */
    fun plan(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean): ListenPlan? =
        if (!listen || !foreground || !hasLan || (!mdns && !ssdp)) null else ListenPlan(mdns, ssdp)
}
```

`ListenController` (new, in `AppContainer`, on `appScope`) combines `settings.state`, `foreground` (set by `MainActivity`'s `onStart` and `onStop`, as for tool pauses) and `networkEnvMonitor.env`, maps to `(plan, env)`, applies `distinctUntilChanged`, and uses `collectLatest`:

- `plan == null`: nothing held.
- Otherwise: `listenLock.acquire()`, open the sockets for the plan, collect heard hosts into the writer (3.4); in `finally`, close the sockets and `listenLock.release()`.

So the lock is acquired only while the switch is on, the app is in the foreground and a LAN is up, and is released on ON_STOP (foreground false cancels the collector), on the switch turning off (including a reset), on network loss, and on a network change (the collector restarts on the new env). Nothing runs in the background: no service, no wake lock.

The lock is its own object: `Locks` gains `listenLock`, a `WifiManager.MulticastLock` tagged `netprobe-listen`, `setReferenceCounted(false)`, acquire and release wrapped in `runCatching` as today. The scan's lock (`netprobe-mdns`) is separate, so a scan ending and calling `Locks.release()` never releases the listener's lock, and the listener stopping never releases the scan's. Android keeps multicast reception on while any lock is held.

### 3.3 Sockets and parsing

Both sockets: `MulticastSocket(null)`, `reuseAddress = true`, bound to the wildcard address on the group port, bound to the Wi-Fi or Ethernet `Network` through `networkEnvMonitor.binderFor(env)?.bind(socket)` before joining, and joined with `joinGroup(InetSocketAddress(group, port), NetworkInterface.getByInetAddress(env.interfaceIp))`. Reads use `CancellableIo.closing` so cancellation closes the socket at once. A socket that fails to open is skipped and reported in the status line; the other still runs. With no binder (no Wi-Fi or Ethernet `Network`), nothing opens.

- mDNS: `224.0.0.251:5353`, buffer `MDNS_MAX_PACKET = 9000`. New pure `MdnsPacket.parse(bytes): List<MdnsRecord>`: responses only (QR bit set); answers and additionals; PTR, SRV, TXT, A and AAAA records. A record is emitted when a PTR names an instance whose SRV target has an A record in the same packet; the type is `MdnsType.normalise`d, TXT parsed as `DeviceInfoTxt` does (its `readName` and TXT rdata helpers move to a shared internal `DnsWire` object, behaviour unchanged). Records without a linked A record are ignored (no follow-up query is sent).
- SSDP: `239.255.255.250:1900`, buffer 4096. New `SsdpParser.parseNotify(from, text): SsdpResponse?`: first line `NOTIFY * HTTP/1.1`, `NTS: ssdp:alive` only (byebye and M-SEARCH requests return null), `st` taken from `NT`, plus `LOCATION`, `SERVER` and `USN`.
- Only RFC1918 IPv4 addresses (`Rfc1918.contains`) become hosts: the A record's address for mDNS, the packet source for SSDP.

Emissions use the scan's shapes plus a new in-memory `Source.HEARD` (never stored, as `sources` is not a column):

- mDNS: `DiscoveredHost(ip, mdnsName = instance, mdnsServiceTypes, mdnsTxt, mdnsInstanceNames, ipv6LinkLocal, sources = {MDNS, HEARD})`.
- SSDP: `DiscoveredHost(from, upnpServer = server, mac = USN MAC, macSource = UPNP_UDN, sources = {SSDP, HEARD})`.

Repeats: a `(ip, type or USN)` key heard within `HEARD_REPEAT_MS = 60_000` of its last write is dropped before the queue.

### 3.4 Entry into the inventory

A single writer coroutine reads a `Channel(HEARD_QUEUE = 64, BufferOverflow.DROP_OLDEST)`. For each heard host it drops the emission when any of these hold: `radarScanActive` (the scan's own mDNS and SSDP cover it, and a heard write must not race its run id), `undo.pending != null` (a pending Remove device would collide with the restored row's unique `(networkId, ip)`), `currentNetwork` is null, or its id equals `forgottenNetworkId`. Otherwise it calls a `RadarCollector` built with `upsert = hosts::upsertHeard`; `RadarCollector` gains that one parameter (default `hosts::upsertDiscovered`), so the heard write takes `hostWriteLock`, honours `RemovedHosts.skip` and runs the same identification and `IdentityWrite.sameDevice` rule as a radar emission.

`HostRepository.upsertHeard(networkId, h, runIdForNew)`, one transaction, the `applyScan` merge with one difference: `lastDiscoveryRun` is left as stored for an existing visible row; a new row, or a `manualOnly` row being promoted, gets `runIdForNew`, which the writer reads as the network's `lastRunAt ?: 0`. Effects: a newly heard device shows undimmed after a completed scan; a known device not found by the latest scan stays dimmed until a scan finds it; "Last scan" (`HomeStats.lastScanAt`, the maximum `lastDiscoveryRun`) never moves because something was heard.

### 3.5 Battery and permission

`CHANGE_WIFI_MULTICAST_STATE` is already in the manifest (a normal, install-time permission at targetSdk 30; no prompt). Holding the lock makes the Wi-Fi chip wake the CPU for every multicast frame on the network, which costs power on busy networks; that is why it is held only in the foreground with the switch on and is off by default. Measured on the device in 8.3 item 7.

## 4. Manual tools

### 4.1 Entry and screen

`HomeMenu` order becomes: "Quick scan all", "Run a tool...", "Aggressive scan", "Forget this network", "Settings". "Run a tool..." (tag `menuRunTool`) is always enabled and opens the new route `tools` in `NetProbeNav`: `ManualToolsScreen` with `ManualToolsViewModel`.

A pinned top `Column` (status-bar padding, background colour, never scrolls):

1. `BackToHome` row.
2. Title "Run a tool" in `title`.
3. One row: `OutlinedTextField` "IP address" (placeholder `10.0.3.20`, weight 0.65, tag `manualIp`) and "Port" (placeholder `443`, digits keyboard, weight 0.35, tag `manualPort`). Errors show as the field's supporting text.
4. A 48 dp row "Tool: <tool name>" with a chevron, tag `manualTool`, opening a `DropdownMenu` of the five tools in `ToolId` order. Default: HTTP fingerprint.
5. An option row that depends on the tool: for Directory brute-force and HTTP fingerprint, a `SwitchRow` "Use HTTPS" (default on for ports in `ServiceIdentifier.TLS`, else off; when the target is a known host with a stored service on that port, `ToolApplicability.targetTls` decides); for TLS certificate nothing; for SMB session the line "Uses port 445"; for SNMP walk "Uses UDP 161". The port field is disabled for SMB and SNMP.
6. A known-device line when the IP matches a visible host on this network: "Known device: <display name>, results also show on its page".
7. The Run pill (`ActionPill`, "Run", tag `manualRun`), which becomes "Stop" (description "Stop tool run") while the run this screen started is active, with the 3B progress text (`ToolsUiModel.bar(active)`) beneath it. Fields and the tool row are disabled while it runs.

Below the pinned block, a `LazyColumn` (tag `manualHistory`): header "Recent runs on this network", then up to `RECENT_RUNS = 20` rows from `ToolDao.recentForNetwork`, each "<tool> on <ip>:<port label>" in `secondary` / `text` and "<summary>, <relative time>" in `data` / `textMuted`, 48 dp minimum, description "Show <tool> results for <ip>:<port label>". Empty: "No tools run on this network yet". With no LAN the fields are disabled and the line "Connect to Wi-Fi or Ethernet to run a tool" replaces the known-device line.

### 4.2 Validation

Pure `ManualTarget.check(ipText, portText, tool): ManualCheck` with `ip: Inet4Address?`, `port: Int?`, `ipError`, `portError`, `canRun`:

- IP: `RowRules.parseIpv4` (dotted quad, no name lookup) else "Enter an address like 10.0.3.20"; then `Rfc1918.contains` else "Only private addresses are allowed".
- Port: SNMP walk fixes 161 (`ToolApplicability.SNMP_PORT`), SMB session fixes 445; the other three need a port: "This tool needs a port" when blank, "Use a port from 1 to 65535" when out of range or not digits.
- Blank fields show no error until the first Run tap.

### 4.3 Target resolution and keying

On Run, inside `c.undo.committedBefore { }` (the Scan rule: a pending Remove or Forget is final before a new write):

1. The network is `c.currentNetwork.value`, else `c.networks.ensure(env, GatewayMac.lookup(env.gateway))`; a null result or the id in `forgottenNetworkId` ends with `ScanController.NO_NETWORK`.
2. `hostId = c.hosts.manualTarget(networkId, ip)`: in one transaction, the existing row for `(networkId, ip)` (visible or `manualOnly`), else a new `HostEntity` with `manualOnly = true`, `deviceType = UNKNOWN`, `status = UNMARKED`, `note = ""`, `lastDiscoveryRun = 0`, `firstSeen = lastSeen = now` and every scan column null.
3. `ToolTarget(hostId, ip, port, tls, serverName)`: `tls` per 4.1 item 5 (TLS certificate always true, SMB and SNMP false); `serverName` null for brute-force (3B 4.9) and otherwise `ToolApplicability.serverName(host)`, which is null for a fresh manual row.
4. Directory brute-force opens the existing `DirBruteSheet` with `DirBruteSheetState(port, "http(s)://<ip>:<port>/", lists, c.dirBruteOptions.load(), busyReason)`; its Run saves the options and starts the run as on the Device screen. The other tools start at once with `ToolOptions.None`.
5. `c.toolRunner.start(target, toolId, options)`; `HostBusy`, `TooMany` and `NoNetwork` map to the 3B messages (`ToolsUiModel.busyMessage`, `ToolCopy.BUSY_TOO_MANY`, `ScanController.NO_NETWORK`) in the screen's snackbar.

Keying: the runner's one-run-per-host rule is per host row, so per (network, IP): one run at a time on an IP, whatever the port, shared with Quick scan all and Device-screen scans through `HostBusyRegistry`. The 4-hosts ceiling, `RateBucket` at `MAX_REQUESTS_PER_SECOND = 100`, background pause, network-change stop and `onFinished` re-identification are untouched. Binding is unchanged: the runner's `bind` is `ScanBinding.run(ip, env, maxPrefix, extEnabled, binderFor)`, so a target outside the swept range is bound to Wi-Fi when Aggressive scan is on and unbound when it is off, as for Device-screen scans today.

### 4.4 Results, history, export

Tapping a history row opens the existing `ToolResultsSheet` on that run, with the switcher over the same host's runs of the same tool (`ToolResultsModel.of`), "Open with...", "Copy" and "Share" as in 3B 8.5. Export is `ToolExport` unchanged. End-of-run messages come through `AppMessages` as today; the screen shows `appMessages.current` in its snackbar as the Device screen does. Pruning stays 10 runs per (host, tool, port).

## 5. Wordlist URL import

### 5.1 UI

The Wordlists pinned block keeps the back row and title; beneath the title, a row of two pills: "Import file" (the 3B pill, renamed, tag `importWordlist` unchanged) and "Import from URL" (tag `importUrl`). Both are disabled while either import runs. "Import from URL" opens an `AlertDialog` titled "Import from URL" with one `OutlinedTextField` "URL" (placeholder `https://example.com/wordlist.txt`, URI keyboard, inline error, tag `urlField`) and the buttons "Import" (`signal`, enabled when the URL validates) and "Cancel".

While fetching, the URL pill reads "Fetching 1.2 of 3.4 MB" (or "Fetching 1.2 MB" with no length), updated at most every `PROGRESS_STEP_BYTES = 65_536` bytes, and a "Cancel" text button (`signal`, 48 dp, tag `cancelUrl`) appears beside it in the pinned row. Cancel ends the fetch within a second, stores nothing and shows "Import cancelled". Leaving the screen does not cancel: the job runs on `appScope`, as file import does, and the result goes to `AppMessages` when the screen is gone.

### 5.2 Why HttpURLConnection

The 3B `HttpClient` is the wrong tool here: it takes an `Inet4Address` (no DNS names), uses a trust-all `TrustManager` (an internet fetch needs real certificate and hostname checks), holds the body in memory with a 64 KB cap and a `2 x timeout` deadline (a 10 MB list fits neither), and connects through `Probe.connect`, which is shaped for LAN probes and the Wi-Fi binding. `HttpURLConnection` from `URL.openConnection()` gives platform DNS, the system trust store and hostname verification, streaming, and the default network (the fetch is to the internet, not a probe, so it is not bound to Wi-Fi and may use cellular). The 3B objection, that a blocked read cannot be ended from a cancelling coroutine, is met with the `CancellableIo.closing` watcher given `Closeable { connection.disconnect() }`: `disconnect()` closes the socket and the blocked read throws. No new dependency.

### 5.3 Rules

Pure `UrlImportRules` in `wordlists/`:

- `validate(text): UrlCheck`: trimmed; no scheme means `https://` is prepended; scheme `https` or `http` only; a host is required; user info (`user:pass@`) is rejected; no whitespace or control characters; at most `MAX_URL_CHARS = 2048`; the fragment is dropped. Errors per the copy table.
- `redirect(from: URL, location: String?, hops: Int): RedirectDecision`: follow only when `hops == 0`, the resolved `Location` has the same host (case-insensitive) and port, and the scheme is not `https` to `http`. Otherwise `TooMany` (a second hop) or `OtherSite` (another host or port, or a downgrade). `MAX_REDIRECTS = 1`.
- `contentType(header: String?): Boolean`: the media type before any `;`, trimmed and lowercased, must be `text/plain`.
- `displayName(url): String?`: the last path segment, percent-decoded, else the host; `WordlistStore`'s `cleanName` then strips the extension, caps the length and falls back to "Imported list".

Fetch (`UrlWordlistFetcher`, on `Dispatchers.IO`):

1. `instanceFollowRedirects = false`, `useCaches = false`, `connectTimeout = URL_CONNECT_TIMEOUT_MS = 10_000`, `readTimeout = URL_READ_TIMEOUT_MS = 15_000`, headers `User-Agent: NetProbe/0.1` (`HttpClient.USER_AGENT`) and `Accept: text/plain`. No `Authorization`, no `Cookie`; the app sets no `CookieHandler`, and a test asserts none is installed.
2. A 3xx goes through `redirect`; the one allowed hop opens a new connection with the same settings.
3. Status other than 200: "Could not fetch: the server answered N".
4. `contentType` false: "Could not fetch: not a text file (<type>)", or without the brackets when the header is missing.
5. `Content-Length` above `WordlistStore.MAX_IMPORT_BYTES`: `ImportResult.TooLarge` without reading the body.
6. The body stream, wrapped in a counting stream for progress, goes to the existing `WordlistStore.import(displayName, stream)`, which enforces 10 MB as it reads (after transparent gzip decoding, so a compressed bomb stops at 10 MB too), 200,000 entries, empty lists, normalisation and naming. Its `ImportResult` messages are reused as they are (`WordlistImport.message`).
7. Failures before the body: `UnknownHostException` "Could not fetch: unknown host"; `SocketTimeoutException` "Could not fetch: no answer"; `SSLException` "Could not fetch: secure connection failed"; any other `IOException` "Could not fetch: connection failed". A failure mid-body is the store's `Unreadable`.

Result type: `sealed interface UrlImportResult { data class Stored(val result: ImportResult); data class Failed(val reason: FetchFailure); object Cancelled }`, with `FetchFailure` = `UnknownHost`, `NoAnswer`, `Tls`, `Connection`, `Status(code)`, `NotText(type)`, `OtherSite`, `TooManyRedirects`. `WordlistImport` gains `message(UrlImportResult)`.

## 6. Scope and ordering

In: sections 1 to 5. Not in: SMB share listing and read checks (now Phase 3D, carrying the 3B section 15 requirements unchanged); UPnP description fetches from the listener (10.4); public-address manual targets (10.5); a "name to send" field for manual TLS and fingerprint runs; editing or renaming wordlists.

Build constraints: as the 3B spec 2.3. No new dependencies, no Gradle changes, no downloads (mDNS and SSDP test packets are authored byte arrays in the test sources). `room-testing` is already declared.

Plan order, one plan, each task built, reviewed and JVM-green before the next:

1. Settings defaults, overflow reset with Undo, default markers.
2. Aggressive scan copy, section move, Home overflow toggle, manual editor while off, Smart discovery outward walk.
3. Schema 3 to 4, `manualOnly`, DAO and repository changes, manual tools screen.
4. Multicast listener: gate, controller, lock, parsers, `upsertHeard`, Settings switch and status line.
5. Wordlist URL import.
6. Device QA (8.3) by Bugsy on the Pixel 7 Pro before handoff.

## 7. Data model and copy

### 7.1 Schema 3 to 4

| Change | Detail |
|---|---|
| `HostEntity.manualOnly` | `@ColumnInfo(defaultValue = "0") val manualOnly: Boolean = false` |
| `MIGRATION_3_4` | `ALTER TABLE hosts ADD COLUMN manualOnly INTEGER NOT NULL DEFAULT 0`; additive, no row rewritten |
| `NetProbeDb` | `version = 4`; `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`; never destructive; `4.json` exported and committed |
| `HostDao.forNetwork` | gains `AND manualOnly = 0`, so the Home list, `HomeStats`, groups, search and Quick scan all never see manual rows |
| `HostDao.allForNetwork` | new, every row; used by `HomeViewModel` for `toolRunningOnNetwork` (Forget stays disabled while a tool holds a manual row) and for `ExtendedOptions.knownIps` (the canary never picks a manual target) |
| `HostDao.updateScan` | also sets `manualOnly = 0`: a scan finding the IP promotes the row, keeping its tool history |
| `HostRepository.manualTarget`, `upsertHeard` | 4.3 and 3.4 |
| `ToolDao.recentForNetwork(networkId, limit)` | `SELECT tool_runs.*, hosts.ip FROM tool_runs JOIN hosts ON hosts.id = tool_runs.hostId WHERE hosts.networkId = :networkId ORDER BY startedAt DESC, tool_runs.id DESC LIMIT :limit` into `data class NetworkRun(@Embedded val run: ToolRunEntity, val ip: String)` |
| `SharedPreferences` | new key `listenMulticast` |

Why a flagged host row and not the alternatives: `tool_runs.hostId` is a foreign key to `hosts`, and `ToolRunner`, `HostBusyRegistry`, pruning, hints, the results sheet and the 3B Remove snapshot are all keyed by host id. A flagged row reuses every one of them, and promotion on discovery keeps the history with the device. A separate `manual_targets` table would need a second foreign key or a nullable `hostId` in `tool_runs`, a second claim key in `HostBusyRegistry` and a rewrite of every tool query. One synthetic "manual" host per network would put every manual IP under one busy claim (one manual run at a time on the whole network) and one history, and the results sheet title would show the wrong IP. Forget this network deletes manual rows with the rest (`deleteHostsOfNetwork`); manual rows have no Device screen, so Remove device never meets them.

### 7.2 Copy table (every new or changed string)

| Owner and name | Text |
|---|---|
| `SettingsCopy.MENU` (description) | Settings menu |
| `SettingsCopy.RESET` | Reset to defaults |
| `SettingsCopy.RESET_DONE` | Settings reset to defaults |
| Snackbar action | Undo (`HomeCopy.UNDO`, reused) |
| `SettingsCopy.defaultMarker(value, unit)` | Default 128 / Default 600 ms |
| `SettingsCopy.resetDescription(label, value, unit)` | Reset Sweep concurrency to 128 |
| Section title, `ExtendedCopy.TITLE` | Aggressive scan (was Extended discovery) |
| `ExtendedCopy.SUPPORT` | Your own subnet is always scanned. Turn on to scan further, into other private ranges your phone can reach. |
| `ExtendedCopy.PROBE_LABEL` | Smart discovery (was Probe for gateways) |
| `ExtendedCopy.PROBE_SUPPORT` | Uses your gateway's IP to find other likely gateways on similar private ranges, then scans those ranges. Up to about 1,600 connection attempts, about 13 s. |
| `ExtendedCopy.MANUAL_ON` | Swept first, with or without Smart discovery |
| `ExtendedCopy.MANUAL_OFF` | Saved now, swept when Aggressive scan is on |
| `HomeCopy.NO_EXTRA_RANGES` | Aggressive scan: no extra ranges to sweep |
| `HomeCopy.GATEWAY_FOUND_NONE` | Smart discovery found no other ranges |
| `HomeCopy.GATEWAY_ANSWERS_ALL` | Smart discovery skipped: every address answered |
| `HomeCopy.SOURCE_GATEWAY` | smart discovery |
| `HomeCopy.MENU_RUN_TOOL` | Run a tool... |
| `HomeCopy.MENU_AGGRESSIVE` | Aggressive scan |
| `HomeCopy.aggressiveDescription(on)` | Aggressive scan, on / Aggressive scan, off |
| `ListenCopy.LABEL` | Listen between scans |
| `ListenCopy.SUPPORT` | While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP. |
| `ListenCopy.BOTH` | Listening for mDNS and SSDP |
| `ListenCopy.MDNS_ONLY` | Listening for mDNS only |
| `ListenCopy.SSDP_ONLY` | Listening for SSDP only |
| `ListenCopy.NO_PORTS` | Could not open the multicast ports |
| `ListenCopy.NO_NETWORK` | Not listening: connect to Wi-Fi or Ethernet |
| `ListenCopy.PAUSED_SCAN` | Paused while a scan runs |
| `ListenCopy.BOTH_OFF` | mDNS and SSDP are off above |
| `ManualCopy.TITLE` | Run a tool |
| `ManualCopy.IP` | IP address |
| `ManualCopy.PORT` | Port |
| `ManualCopy.toolRow(name)` | Tool: HTTP fingerprint |
| `ManualCopy.USE_HTTPS` | Use HTTPS |
| `ManualCopy.FIXED_SMB` | Uses port 445 |
| `ManualCopy.FIXED_SNMP` | Uses UDP 161 |
| `ManualCopy.knownDevice(name)` | Known device: CHNAS, results also show on its page |
| `ManualCopy.NO_LAN` | Connect to Wi-Fi or Ethernet to run a tool |
| `ManualCopy.ERR_IP` | Enter an address like 10.0.3.20 |
| `ManualCopy.ERR_PRIVATE` | Only private addresses are allowed |
| `ManualCopy.ERR_PORT_NEEDED` | This tool needs a port |
| `ManualCopy.ERR_PORT_RANGE` | Use a port from 1 to 65535 |
| `ManualCopy.HISTORY` | Recent runs on this network |
| `ManualCopy.HISTORY_EMPTY` | No tools run on this network yet |
| `ManualCopy.historyRow(tool, ip, portLabel)` | HTTP fingerprint on 10.0.3.5:5000 |
| `ManualCopy.historyLine(summary, relative)` | Synology DSM, 6 findings, 2 min ago |
| `ManualCopy.historyDescription(tool, ip, portLabel)` | Show HTTP fingerprint results for 10.0.3.5:5000 |
| Run and Stop | Run (`ToolCopy.RUN`), Stop (`HomeCopy.STOP`), Stop tool run (`ToolCopy.STOP_DESCRIPTION`), all reused |
| `WordlistCopy.IMPORT` | Import file (was Import wordlist) |
| `WordlistCopy.IMPORT_URL` | Import from URL (pill and dialog title) |
| `WordlistCopy.URL_LABEL` | URL |
| `WordlistCopy.URL_PLACEHOLDER` | https://example.com/wordlist.txt |
| `WordlistCopy.URL_IMPORT` | Import |
| `WordlistCopy.ERR_URL` | Enter a web address starting with https:// or http:// |
| `WordlistCopy.ERR_URL_USER` | Remove the user name and password from the address |
| `WordlistCopy.ERR_URL_LONG` | That address is too long |
| `WordlistCopy.fetching(read, total)` | Fetching 1.2 of 3.4 MB / Fetching 1.2 MB |
| `WordlistCopy.CANCELLED` | Import cancelled |
| `WordlistCopy.FETCH_HOST` | Could not fetch: unknown host |
| `WordlistCopy.FETCH_NO_ANSWER` | Could not fetch: no answer |
| `WordlistCopy.FETCH_TLS` | Could not fetch: secure connection failed |
| `WordlistCopy.FETCH_CONNECTION` | Could not fetch: connection failed |
| `WordlistCopy.fetchStatus(code)` | Could not fetch: the server answered 404 |
| `WordlistCopy.fetchNotText(type)` | Could not fetch: not a text file (application/octet-stream) / Could not fetch: not a text file |
| `WordlistCopy.FETCH_OTHER_SITE` | Could not fetch: redirected to another site |
| `WordlistCopy.FETCH_REDIRECTS` | Could not fetch: too many redirects |

`fetching` uses one decimal place in MB of 1,048,576 bytes. The Radar spec's Settings section list ("Extended discovery") is superseded by this table.

## 8. Tests and verification

### 8.1 JVM

1. `SettingsDefaults`: `reset` returns `SettingsState()` with the current NVD key; `differs` is false at defaults and true after any one of the 15 values changes; `AppSettings` defaults equal `SettingsState()` for every key (fake prefs); the reset is one edit.
2. Markers: a pure `DefaultMarker.text(value, default, unit)` is null at the default and "Default 600 ms" otherwise; the description template.
3. `ListenGate.plan`: null for each of switch off, background, no LAN, both protocols off; the plan carries the mDNS and SSDP flags.
4. `ListenController` with a fake lock, fake socket factory and test dispatcher: launch with the switch off holds nothing; on acquires once; foreground false releases and closes sockets; switch off releases; env null releases; env change releases then re-acquires; a Settings reset releases; the scan lock's release does not release the listen lock (two fakes).
5. `HomeViewModel` options builder (extracted as a pure `ExtendedOptionsOf.from(settings, manual, known)`): Aggressive on with Smart discovery off carries the manual list and `gatewayProbe = false`; Aggressive off gives `ExtendedOptions()`.
6. Engine with fakes: Aggressive on, probe off, one manual range: the gateway probe fake is never called and the range is swept (extends the Phase 2 12.2 case); the toggle-off regression case is unchanged.
7. `GatewayTargets.of` for `10.0.3.20/24`, gateway `10.0.3.1`: 255 units; `10.0.0.1`, `10.0.1.1`, `10.0.2.1`, `10.0.4.1`, `10.0.255.254` present; no `10.0.3.x`; offsets {1, 254}; exactly the 17 seeds of 2.4.
8. Outward walk: the 2.4 example yields exactly `10.1.1.0/24`, `10.1.2.0/24`, `10.1.3.0/24` and probes exactly `10.1.0.1` and `10.1.2.1` to `10.1.5.1`; the 16-unit limit; already-probed units skipped without counting as misses; no walk for a seed inside the phone's /16; no walk when no seed answers (attempt count identical to Phase 2); progress total monotonic; cancellation mid-walk under 1 s.
9. `MdnsPacket.parse` on authored packets: a PTR+SRV+TXT+A announcement gives one record with normalised type, port, TXT and IPv4; AAAA link-local goes to `ipv6`; a query (QR clear) gives nothing; an A record without a service gives nothing; compression pointers; truncated and looping packets return empty without throwing.
10. `SsdpParser.parseNotify`: alive with LOCATION, SERVER, NT, USN; byebye null; M-SEARCH null; a 200 response null.
11. Heard filter: non-RFC1918 dropped; a repeat within 60 s dropped, after it kept; dropped while `radarScanActive`, while an Undo is pending, with no current network, and for the forgotten network; queue overflow drops the oldest.
12. `ManualTarget.check`: every error string; SMB and SNMP fixed ports ignore the port field; public, loopback and multicast addresses rejected; blank fields silent until a Run tap.
13. Manual start mapping: `HostBusy`, `TooMany`, `NoNetwork` messages; `ToolTarget` port and `tls` per tool; `serverName` null for brute-force and for a fresh manual row; `undo.committedBefore` runs before `manualTarget`.
14. `UrlImportRules`: scheme prepending, http kept, ftp and javascript rejected, user info rejected, length cap, fragment dropped; `redirect` allows one same-host same-port hop, rejects a second hop, another host, another port and an https to http downgrade, allows http to https on the same host and port; `contentType` accepts `text/plain; charset=utf-8` and `TEXT/PLAIN`, rejects missing, `text/html` and `application/octet-stream`; `displayName` decoding and the host fallback.
15. `UrlWordlistFetcher` against a local `ServerSocket` on 127.0.0.1 over HTTP: 200 `text/plain` imports and reports entries; 404, octet-stream and a missing type give their messages; `Content-Length: 11000000` is rejected with nothing read beyond the headers; one same-host redirect followed, a second rejected; cancellation during a slow body returns `Cancelled` within 1 s and leaves no file in the wordlist directory; the request carries no `Cookie` or `Authorization` header and `CookieHandler.getDefault()` is null; a body over 10 MB without `Content-Length` gives `TooLarge`.
16. Copy tables: `CopyTableTest` and `ToolCopyTableTest` include every row of 7.2; no string contains an em-dash.

### 8.2 Instrumented

1. Migration: schema 3 with a network, a host with a note and a tool run migrates through `MIGRATION_3_4` with `runMigrationsAndValidate(..., validateDroppedTables = true)`; old rows intact and `manualOnly = 0`; the 1 to 4 chain; the real builder opens the result. Uses `MIGRATION_TEST_DB` only.
2. DAO: a `manualOnly` row is absent from `forNetwork` and present in `allForNetwork`; `upsertDiscovered` on it promotes it and its tool runs remain; `upsertHeard` keeps an existing row's `lastDiscoveryRun` and sets `runIdForNew` on insert and on promotion; `recentForNetwork` orders, limits and excludes other networks; Forget's cascade removes manual rows and their runs.
3. Compose, Settings: the overflow item is disabled at defaults; after changing a slider the marker shows and tapping it restores the default; Reset to defaults then Undo restores every value; the Aggressive section sits under Discovery; the manual editor accepts a range with Aggressive scan off and shows the "Saved now" line.
4. Compose, Home: the overflow lists the five items in order; "Aggressive scan" toggles and shows its check; it is disabled during a scan.
5. Compose, manual tools: the Run pill is outside `manualHistory` (scrolling the list never moves it); errors show; SMB disables the port field; a fake runner run shows Stop and progress; a history row opens the results sheet.
6. Compose, Wordlists: both pills pinned; the URL dialog validates; during a fake fetch the pill shows progress and Cancel is present; Cancel shows "Import cancelled".

### 8.3 Device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, by Bugsy, mandatory)

1. Settings: change two sliders and two switches, Reset to defaults, Undo, Reset again; values persist across an app restart.
2. Aggressive scan off: a scan matches the Phase 2 check 1 baseline. On, with Smart discovery off and a manual `10.0.4.0/24`: only that range is swept after the home subnet.
3. Smart discovery on: `10.0.4.0/24` found as in Phase 2 check 3; the logged attempt count is within 10 percent of 1,058; no outward walk runs unless a seed answers.
4. The Home overflow toggle and the Settings switch stay in step.
5. Listening on, no scan since launch: within two minutes the Hue Bridge, a Chromecast or the Sonos appears from announcements alone; `dumpsys wifi` lists the `netprobe-listen` lock while Home is open, and not after Home is sent to the background.
6. Listening during a scan: no duplicate rows, the status line reads "Paused while a scan runs", and the scan's own lock is released at its end while `netprobe-listen` stays held.
7. Battery: 15 minutes on Home with listening on, then off, screen on; record `dumpsys batterystats` Wi-Fi and CPU for each.
8. Manual tools: HTTP fingerprint of the Pi-hole by IP and port on a fresh install with no scan; TLS certificate of the UniFi gateway on 443; SMB session on CHNAS; SNMP walk on the router. Each shows results, history and Share; a later radar scan shows the fresh manual targets as normal devices with their tool history.
9. Manual tools: Stop ends a brute-force within a second; a second run on the same IP gives "A tool is already running on this device".
10. URL import of a raw GitHub text file over HTTPS, a plain HTTP URL on the LAN, a 404, a `.zip`, and Cancel mid-fetch of a large list.

Definition of done: 8.1 and 8.2 pass, 8.3 is recorded with results, and the Director has seen the Settings, Home menu, Run a tool and Wordlists screens on the device.

## 9. Not as drawn, and deviations from earlier specs

| # | Earlier spec or brief | This spec | Why |
|---|---|---|---|
| 1 | 3B spec 15: "Phase 3C" is SMB share enumeration | That work is Phase 3D, unchanged | D18 |
| 2 | Brief: "the outward continuation rule from Phase 2" | Phase 2 has none; the whole-/16 walk covers the Director's example; 3C adds a bounded outward walk for seed hits outside the /16 | 2.4, D9 |
| 3 | Phase 2 8.3: manual editor disabled while the master toggle is off | Editable whenever a network is current; sweeping still needs Aggressive scan | D8 |
| 4 | Phase 2 8.3: "Probe for gateways", subtitle ending "Noisy on networks you do not own." | "Smart discovery" with the Director's description and the cost | D7; the copy rules allow no warnings |
| 5 | Radar spec 6: Settings order and content unchanged; section list in its copy table | Aggressive scan moves under Discovery; new switch in Discovery; Settings overflow | D6, D1, D10 |
| 6 | 3B spec 8.6: pill "Import wordlist" | "Import file" beside "Import from URL" | D17 |
| 7 | 3B spec 4.6 and decision table: `HttpURLConnection` rejected | Used for the URL import only; tools keep `HttpClient` | 5.2 |
| 8 | Phase 2 4.1: off-subnet mDNS and SSDP sightings stored whatever the toggle | Unchanged for scans; heard hosts are limited to RFC1918 | 3.3 |

## 10. Assumptions and open items for the Director

Assumptions:

- A1. Reset keeps the NVD key, manual ranges and the brute-force sheet's last-used options (D3). Say if any should reset too.
- A2. Binding UDP 5353 and 1900 with `SO_REUSEADDR` works beside the system mDNS service on the Pixel 7 Pro; if not, the status line says so and 8.3 item 5 records it.
- A3. The UniFi Wi-Fi forwards multicast between clients (no multicast-to-unicast conversion or client isolation on that SSID); otherwise the listener hears little.
- A4. Android's `HttpURLConnection` decodes gzip transparently, so the 10 MB cap applies to the decoded text.
- A5. `raw.githubusercontent.com` serves `text/plain; charset=utf-8`.

Open items:

1. Review rigour for this plan: light, standard or heavy (standard suggested, with a security look at the URL fetch and the listener).
2. Confirm the Smart discovery outward walk (D9) and its limits (2 misses, 16 units), or drop it.
3. GitHub links of the form `github.com/<user>/<repo>/raw/...` redirect to `raw.githubusercontent.com`, another host, and are rejected under D16. Keep the rule (paste the `raw.githubusercontent.com` URL), or allow that one cross-host hop.
4. Should the listener fetch a heard device's UPnP description (one GET per new LOCATION) for names between scans? Off in this phase.
5. Manual tools accept RFC1918 targets only. Allow public addresses (internet hosts through the default route)?
6. Confirm the labels "Aggressive scan", "Smart discovery" and "Listen between scans".
7. Should manual ranges also sweep with Aggressive scan off? Currently no: Aggressive scan is the one gate for anything beyond the home subnet.
