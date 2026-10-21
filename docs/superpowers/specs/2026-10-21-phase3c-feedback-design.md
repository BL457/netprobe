# NetProbe Phase 3C Feedback Design (settings reset, Aggressive scan, multicast listening, manual tools, wordlist URL import)

Date: 2026-10-21. Author: Ada (Engineering Lead). Status: revised after Gruff's review (`docs/superpowers/plans/2026-10-21-phase3c-gruff-review.md`) and the Director's rulings on its five questions; the remaining open items are in section 10.
Baseline: branch `phase3b` at 40b88f0 (Phase 3A plus the 3B tools, 883 JVM tests). Both 3C plans start only after the 3B device QA passes.
Related specs: `2026-10-19-extended-discovery-design.md` (the "Phase 2 spec": candidate ranges, gateway probing, manual CIDRs, binding), `2026-10-19-radar-ui-design.md` (the "Radar spec": Settings layout, Home overflow, tokens, copy rules), `2026-10-20-phase3a-polish-design.md` (the "3A spec": Undo, `appScope`, schema 2), `2026-10-20-phase3b-followup-tools-design.md` (the "3B spec": `ToolRunner`, `HostBusyRegistry`, `WordlistStore`, `HttpClient`, results sheet, export, schema 3).

The Director's feedback, verbatim:

> Reset sliders in settings to default in a way that doesn't fill the settings page with loads of buttons
> Manual tools page for manually firing off tools without needing to use a scan
> Trust the subnet mask where appropriate as the baseline and scan further with a toggle option (Aggressive scan)
> Can you listen for multicast on wifi on android?
> Add manual ranges to scan
> Add a smart discovery (rfc1918)... using my gateway's IP, find other likely gateway IPs on similar ranges then scan those ranges
> Import wordlist from url

The Director's rulings on Gruff's questions (binding):

1. Wide masks: Aggressive scan sweeps the whole subnet the mask gives (a client /16 included), capped by the existing "Large networks" limit; with Aggressive scan off, the home /24 only, as today.
2. Manual ranges are always swept when set, even with Aggressive scan off.
3. Manual tools accept any address, public included; the Wi-Fi binding and the read-only tools are the guard.
4. Manual tools follow the route to the address: a target inside the current Wi-Fi network's ranges is bound to Wi-Fi; any other target goes out unbound so a VPN route works. Scans and radar discovery stay bound as before.
5. Keep the Smart discovery outward walk, bounded by the extra-ranges cap and the per-range cost.

## Purpose and decisions

Five pieces of work, none touching the scan engine's liveness rules: a quiet way back to default settings (section 1); the Phase 2 extended discovery renamed "Aggressive scan", with the mask rule, manual ranges that always sweep and a bounded outward walk (section 2); passive mDNS and SSDP listening between scans (section 3); a manual tools screen for any IPv4 address and port (section 4); wordlist import from a URL (section 5). Section 6 splits the work into two plans.

Standing rules carry over: Scan, Stop and Run controls are pinned outside any scroll container; no em-dashes; no disclaimers, warnings or authorisation prompts in UI copy; no new dependencies; no downloads; every user-facing string is in the copy table (7.2) and checked by `CopyTableTest` or `ToolCopyTableTest`.

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | One "Reset to defaults" item in a new Settings overflow menu; no per-section reset buttons | Director (brief) |
| D2 | Each slider and the "Maximum extra ranges" stepper shows a small tappable "Default N" marker only while its value differs from the default; a tap resets that one control | Ada (Director sees it on device, G19) |
| D3 | Reset covers every switch, slider, stepper and segmented control in Settings (14 values in plan 3C-1, 15 once 3C-2 adds `listenMulticast`); it keeps the NVD API key, manual ranges, wordlists, the vulnerability cache, the location permission and the brute-force sheet's remembered options | Ada, assumption A1 |
| D4 | Reset is undone from the Settings snackbar ("Undo", long duration), not through `UndoStore`; no confirmation dialog | Ada (`UndoStore` is for database deletes and its notice lives on Home) |
| D5 | "Extended discovery" is renamed "Aggressive scan" everywhere it is user-facing; keys and classes keep their names | Director |
| D6 | The Aggressive scan section moves to second place in Settings, directly under "Discovery". It lives in Settings only; Home's overflow gains no Aggressive item | Director ("Settings (not Home)"), G1 |
| D7 | "Probe for gateways" is relabelled "Smart discovery", with a subtitle in the Director's words | Director |
| D8 | Manual ranges are swept on every scan when the list is not empty, whatever Aggressive scan says; the editor is always enabled while a network is current and never depends on Smart discovery | Director (ruling 2), G17 |
| D9 | Smart discovery gains a bounded outward walk around answering seeds outside the phone's own /16 (2.5) | Director (ruling 5) |
| D10 | Passive multicast listening behind a new Settings switch "Listen between scans", default off; its own `MulticastLock`, held only while the switch is on, NetProbe is in the foreground and a Wi-Fi or Ethernet network is up | Director (brief) |
| D11 | Heard devices enter the inventory through `RadarCollector`'s guarded write with a new `upsertHeard`, limited to the network's known ranges, at most 32 new rows per listening session, tagged with the network they were heard on; listening sends nothing | Ada, G4, G5 |
| D12 | Manual tools: a "Run a tool" screen from the Home overflow ("Run a tool..."); any unicast IPv4 address except the reserved set in 4.2 | Director (entry point, ruling 3) |
| D13 | A manual target is a `hosts` row: the existing row for (network, IP) when there is one, else a new row flagged `manualOnly`, hidden from every device list and promoted to a normal row when discovery or listening later finds that IP; a row whose run never starts is deleted | Ada (7.1 justifies), G8 |
| D14 | Schema 3 to 4: two additive columns, `hosts.manualOnly INTEGER NOT NULL DEFAULT 0` and `tool_runs.route TEXT` (nullable) | Ada, ruling 4 |
| D15 | Wordlist URL import through `HttpURLConnection`, not the 3B `HttpClient` (5.2) | Ada |
| D16 | URL import: HTTPS or HTTP; up to 3 redirects to any host, never HTTPS to HTTP and never from a public host to a local IP literal; `text/*` except `text/html`, `application/octet-stream` or no type, with a NUL-byte check; the store's 10 MB and 200,000-entry caps; no credentials | Director (brief), G9 |
| D17 | The 3B pill "Import wordlist" becomes "Import file", beside the new "Import from URL" | Ada (two imports need two names) |
| D18 | SMB share listing moves from 3C to Phase 3D; nothing in it changes | Director (brief) |
| D19 | Mask rule: with Aggressive scan on, a mask wider than the Large networks limit sweeps the limit-sized block of the masked subnet that holds the phone, instead of the /24 (2.1) | Director (ruling 1), G2 |
| D20 | Manual tool route: a target inside the network's known ranges is bound to Wi-Fi or Ethernet; any other target runs unbound; the results sheet header names the route (4.5) | Director (ruling 4), replaces G3 |
| D21 | Each scan run (radar scan, Quick scan all, a Device-screen scan) reads Aggressive scan, the Large networks limit and the manual list once at its start; a change mid-run affects the next run only | G15 |
| D22 | Two plans: 3C-1 (reset, Aggressive scan, manual ranges, outward walk, URL import) and 3C-2 (schema 4, manual tools, listener), each with its own device QA | Director, G16 |

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

Covered values (15 after 3C-2): `mdns`, `ssdp`, `netbios`, `reverseDns`, `snmp`, `httpTitle`, `listenMulticast` (added by plan 3C-2, section 3), `sweepConcurrency`, `connectTimeoutMs`, `scanConcurrency`, `scanTimeoutMs`, `maxPrefix`, `extEnabled`, `extMax`, `extGatewayProbe`. Resetting `maxPrefix` changes how much of a wide client subnet the next scan sweeps (2.1); that is intended.

Not covered: `nvdApiKey` (a credential the Director typed), `extManual.<networkId>` (per-network data, not a setting; manual ranges keep sweeping after a reset, D8), the Wordlists row (data), "Clear vulnerability cache" (an action), the location permission (system state), and `DirBruteOptionsStore` (the brute-force sheet's last-used values; they live in the sheet, not in Settings, and are re-chosen at every run). The 3B Tools section holds only the Wordlists row, so it has nothing to reset.

### 1.2 Settings overflow

The Settings top bar becomes a `Row`: `BackToHome` at the start, and at the end a 48 dp `MoreVert` icon button in `textSecondary`, description "Settings menu", tag `settingsMenu`. Its `DropdownMenu` (`surface`, as `HomeMenu`) has one item, "Reset to defaults", tag `resetDefaults`, enabled only when `SettingsDefaults.differs(s)`.

Tapping it:

1. Captures `before = s`.
2. Calls `container.settings.update { SettingsDefaults.reset(it) }` (one `SharedPreferences` edit, so a kill mid-reset never leaves half the values reset).
3. Shows the Settings snackbar "Settings reset to defaults" with action "Undo", `SnackbarDuration.Long`.
4. On `SnackbarResult.ActionPerformed`: `update { before.copy(nvdApiKey = it.nvdApiKey) }` (a key typed after the reset survives the undo).

Undo restores the whole `before` snapshot: a slider changed in the seconds between the reset and the Undo tap is put back to its pre-reset value too. That is accepted and stated here so QA does not report it (G18). Leaving Settings dismisses the snackbar and ends the chance to undo. A reset during a scan affects the next run only (D21, 2.2).

### 1.3 Default markers

`StepSlider` gains `default: Int` and the Phase 2 `Stepper` gains the same. When `value != default`, the label row shows, before the value, a marker "Default 128" (unit appended as the value shows it: "Default 600 ms") in `caption` / `textSecondary`, inside a 48 dp target, description "Reset <label> to <value><unit>", tag `default:<label>`. A tap calls `onCommit(default)`. When the value equals the default the marker is absent and takes no space. Defaults come from `SettingsDefaults.DEFAULTS`. Segmented controls and switches get no marker; the overflow reset covers them. If the Director calls the markers clutter on the device (definition of done), the tap target is dropped and the caption kept; the overflow reset alone answers the brief.

## 2. Aggressive scan

### 2.1 Home range and the mask rule

New pure function in `netenv/Subnet.kt`, the one place the swept local range ("home range") is computed:

```kotlin
object HomeRange {
    /** Aggressive off: exactly SubnetMath.sweepRange. Aggressive on: a mask wider than the limit sweeps the limit-sized block holding the phone. */
    fun of(ip: Inet4Address, prefix: Int, maxPrefix: Int, aggressive: Boolean): SweepRange =
        if (!aggressive || prefix >= maxPrefix) SubnetMath.sweepRange(ip, prefix, maxPrefix)
        else SweepRange(SubnetMath.of(ip, maxPrefix), clamped = true, originalPrefix = prefix)
}
```

- Aggressive scan off: unchanged. A mask at or narrower than the limit is swept whole; a wider mask sweeps the phone's /24. Phase 2 check 1 and every MVP result stand.
- Aggressive scan on: a mask at or narrower than the limit is swept whole (as off); a wider mask sweeps the block of the limit's size that holds the phone. On a client `172.20.0.0/16` with the default limit /22 and the phone at `172.20.9.40`, the home range is `172.20.8.0/22` (1,022 addresses); with the limit at its widest, /20, it is `172.20.0.0/20`. The Large networks slider (/20 to /24, default /22) is the cap the Director named; its range is unchanged.
- The Director's home `10.0.3.0/24` is unaffected either way (prefix 24 is at or narrower than every limit).
- After a completed run whose home range was clamped, the notice is `HomeCopy.largeNetwork(prefix)` ("Scanned your /24 only") when Aggressive scan was off and `HomeCopy.largeNetworkPartial(prefix, sweptPrefix)` ("Scanned your /22 only") when it was on.

Every caller that today calls `SubnetMath.sweepRange` for the local range calls `HomeRange.of` with the run's snapshot (2.2): `DiscoveryEngine` (the local sweep, `GatewayTargets.of`'s `scanned`, `CandidateRanges.derive`'s `scanned`), `ScanBinding`, `HomeViewModel` row grouping (`RangeRules.groupFor`), the hero subtitle, and the manual tools route (4.5). The rest of a wide masked subnet beyond the limit block is not swept; Gruff's `RangeSource.MASK` proposal is superseded by ruling 1 (section 11).

### 2.2 Run snapshot (D21)

`data class RunSettings(val aggressive: Boolean, val maxPrefix: Int, val manual: List<Subnet>)` is read once when a radar scan, Quick scan all or a Device-screen scan starts, and that run uses only the snapshot: `ExtendedOptions`, `ScanBinding` and the home range. `RunMemory` keeps the last radar run's `RunSettings`, and `baseState` groups rows with it (falling back to the live settings when this network has no run in memory), so toggling Aggressive scan or resetting Settings mid-run never regroups the running list or changes a running scan's binding. The Settings switch stays enabled during scans, as today (ExtendedSection.kt line 62).

### 2.3 Settings section, in order

Settings order becomes: Discovery, Aggressive scan, Speed, Large networks, Permissions, Vulnerabilities, Tools. The Aggressive scan section:

1. Switch "Aggressive scan", subtitle "Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach." Default off.
2. Stepper "Maximum extra ranges" (1 to 16, default 4, gains the D2 marker). Now always enabled: it caps manual ranges with Aggressive scan off too.
3. Switch "Smart discovery", subtitle "Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s." Default off. Disabled while Aggressive scan is off, as today.
4. "Manual ranges for <network name>" editor: unchanged validation and cap marking (Phase 2 4.4; private ranges only, "Only private ranges are allowed", because `CandidateRanges.derive` drops any non-RFC1918 unit), enabled whenever a network is current. Beneath the title, `data` / `textMuted`: "Swept first on every scan, with Aggressive scan on or off". With no network, disabled with "Connect to a network to edit its ranges" (unchanged).

### 2.4 Manual ranges always swept (D8)

`ExtendedOptionsOf.from(run: RunSettings, s: SettingsState, known: Set<String>): ExtendedOptions` (pure, extracted from `HomeViewModel.discoverOn`):

- Aggressive on: `ExtendedOptions(enabled = true, maxRanges = extMax, gatewayProbe = extGatewayProbe, manual = run.manual, evidence = true, knownIps = known)`, as today.
- Aggressive off, manual list not empty: `ExtendedOptions(enabled = true, maxRanges = extMax, gatewayProbe = false, manual = run.manual, evidence = false, knownIps = known)`. `ExtendedOptions` gains `evidence: Boolean = true`; when false the engine passes an empty evidence list to `CandidateRanges.derive`, so only the typed ranges are swept.
- Aggressive off, manual list empty: `ExtendedOptions()`, exactly the MVP path (Phase 2 check 1).

Manual ranges are swept bound to the Wi-Fi or Ethernet `Network`, as extended ranges are: `ScanBinding.run(ip, env, home, bindOutside, binderFor, block)` takes the run's home range and `bindOutside = run.aggressive || run.manual.isNotEmpty()`; a target outside the home range is bound when `bindOutside`, and a missing binder is `BindFailedException` as today. With no manual ranges and Aggressive off, binding is the MVP's (unbound).

Display: `RunEnd.of` and `baseState` show the range summary and the per-range notices whenever the run's `ExtendedOptions.enabled` was true, not only when Aggressive scan was on. With Aggressive off and one manual range, the done summary reads "1 other range checked" and the expanded line "10.0.4.0/24  manual  swept, 3 found" (existing `HomeCopy.rangeLine` with `SOURCE_MANUAL`); the per-range notices are the existing ones ("10.0.4.0/24: nothing found", "10.0.4.0/24 skipped: every address answered", "Stopped at 10.0.4.0/24", "Other ranges skipped: could not bind to Wi-Fi", and `capDropped` when the list's /24 units exceed the cap). "Aggressive scan: no extra ranges to sweep" appears only when Aggressive scan was on. Row grouping reads manual ranges from the run's snapshot whatever the toggle.

User-facing renames (copy table): the Settings section title and switch label, `ExtendedCopy.TITLE`, `SUPPORT`, `PROBE_LABEL`, `PROBE_SUPPORT`, `HomeCopy.NO_EXTRA_RANGES`, `GATEWAY_FOUND_NONE`, `GATEWAY_ANSWERS_ALL`, `SOURCE_GATEWAY` and `capDropped` (which now names "Maximum extra ranges", G21). The Home progress line ("probing 10.0.0.0/16 for gateways") describes the action and is kept.

### 2.5 Smart discovery probe set, outward walk and stop conditions

First pass, restated from Phase 2 4.3 and checked against `GatewayTargets.of` and `GatewayProbe.run`. Phone `10.0.3.20/24`, gateway `10.0.3.1`, limit /22:

- Home range `10.0.3.0/24`; enclosing /16 `10.0.0.0/16`; gateway offset `.1`, so no extra offset (`takeUnless { it == LOW || it == HIGH }`).
- Every /24 of `10.0.0.0/16` except those overlapping the home range, at `.1` and `.254` (and the gateway's own last octet when it is neither): `10.0.0.1`, `10.0.1.1`, `10.0.2.1`, `10.0.4.1` ... `10.0.255.1`, and each `.254`. That is 255 units and 510 addresses. The Director's `10.0.0.1`, `10.0.1.1` and `10.0.2.1` are all in it.
- Seeds: `GatewayTargets.SEEDS` minus those inside the enclosing /16 or the home range, here 17: `10.1.1.1, 10.1.10.1, 10.10.0.1, 10.10.10.1, 172.16.0.1, 172.16.1.1, 172.16.16.1, 172.17.0.1, 172.31.0.1, 192.168.0.1, 192.168.1.1, 192.168.1.254, 192.168.2.1, 192.168.10.1, 192.168.88.1, 192.168.100.1, 192.168.178.1`.
- Ports 80 then 443, 400 ms, 64 in flight, TEST-NET pre-check first (2 attempts); a connect or refusal is an answer. Worst case for this network: 527 addresses x 2 ports + 2 = 1,056 attempts. Worst case on any network: 255 units x 3 offsets x 2 ports + 20 seeds x 2 + 2 = 1,572.

Outward walk (D9), in `GatewayProbe.run` after the first pass, deterministic:

1. Skipped entirely when the pre-check said "answers for everything" (the whole probe is skipped, as today), or when more than `WALK_MAX_SEEDS = 4` seeds answered (a bogon-reject firewall answering for everything private; note `ExtendedNote.GATEWAY_WALK_SKIPPED_MANY`, Home notice "Smart discovery: too many ranges answered, nearby ranges not checked").
2. Budget: `B = maxRanges.coerceIn(1, 16) - CandidateRanges.derive(home, manual, evidenceSoFar, firstPassHits, maxRanges).ranges.size`. No walk when `B <= 0`: the cap is already full, so walked hits could never be swept.
3. Directions: for each answering seed outside the phone's enclosing /16 (all answering seeds when the phone is not on RFC1918), in ascending address order, at `a.b.c.o`: "down" over `a.b.(c-1).o, a.b.(c-2).o ...` and "up" over `a.b.(c+1).o, a.b.(c+2).o ...`, within the seed's own /16 (third octet 0 to 255). Order list: seed 1 down, seed 1 up, seed 2 down, and so on. Only the seed's own last octet is probed.
4. Rounds `k = 1 .. OUTWARD_MAX_UNITS (16)`: each live direction contributes its unit at distance `k`. A unit already probed (first pass, a seed, or another direction) or overlapping the home range is skipped without counting as a miss. A direction ends at the octet boundary, after `OUTWARD_MISS_LIMIT = 2` consecutive misses, or at distance 16. A round's units are probed concurrently with the same ports, timeout, semaphore, `Probe.connect` with the bound `Network`, answer rule and `onAnswer`.
5. After each round, its hits join `hits` (source `GATEWAY_PROBE`). When the walk's hits reach `B`, no further round starts; hits of the last round beyond `B` stay in `hits` and are reported as truncated by `derive` ("N more ranges found, raise Maximum extra ranges in Settings").
6. `GatewayProbeProgress.total` grows by each round's unit count and stays monotonic; cancellation ends a round within one probe timeout.

Bounds: at most 4 seeds x 2 directions x 16 units x 2 ports = 256 attempts and 16 rounds x 800 ms = 12.8 s on top of the first pass, so Smart discovery costs at most about 1,850 attempts and 30 s. Each walked hit then costs one extra-range sweep (about 16 s, `ExtendedCopy.SECONDS_PER_RANGE`), at most `maxRanges` of them, which the stepper's line already states. No seed answers on the Director's LAN, so the walk adds nothing there.

Example: phone on `192.168.1.0/24`, cap 4, no manual or evidence ranges; seed `10.1.1.1` answers, so `B = 4 - 1 = 3`. Round 1: down `10.1.0.1` misses, up `10.1.2.1` hits. Round 2: down ends at the octet boundary; up `10.1.3.1` hits. Round 3: up `10.1.4.1` misses. Round 4: up `10.1.5.1` misses, the direction ends. Hits: `10.1.1.0/24`, `10.1.2.0/24`, `10.1.3.0/24`. Had the walk reached `10.1.10.1` (a seed) it would skip it without counting a miss.

## 3. Multicast listening (plan 3C-2)

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
- Otherwise: `listenLock.acquire()`, create a new heard queue and repeat filter for this collector instance, open the sockets for the plan, collect heard hosts into the writer (3.4); in `finally`, close the sockets, close the queue (discarding anything still in it) and `listenLock.release()`.

So the lock is acquired only while the switch is on, the app is in the foreground and a LAN is up, and is released on ON_STOP (foreground false cancels the collector), on the switch turning off (including a reset), on network loss, and on a network change (the collector restarts on the new env with a new, empty queue). Nothing runs in the background: no service, no wake lock.

The lock is its own object: `Locks` gains `listenLock`, a `WifiManager.MulticastLock` tagged `netprobe-listen`, `setReferenceCounted(false)`, acquire and release wrapped in `runCatching` as today. The scan's lock (`netprobe-mdns`) is separate, so a scan ending and calling `Locks.release()` never releases the listener's lock, and the listener stopping never releases the scan's.

### 3.3 Sockets and parsing

Both sockets: `MulticastSocket(null)`, `reuseAddress = true`, bound to the wildcard address on the group port, bound to the Wi-Fi or Ethernet `Network` through `networkEnvMonitor.binderFor(env)?.bind(socket)` before joining, and joined with `joinGroup(InetSocketAddress(group, port), iface)` where `iface = NetworkInterface.getByInetAddress(env.interfaceIp)` through an injectable lookup. A null `iface` means that socket does not open (never `joinGroup(addr, null)`, which could join on a VPN's tun device); with both sockets failing, the status line shows "Could not open the multicast ports" (G11). Reads use `CancellableIo.closing` so cancellation closes the socket at once. A socket that fails to open is skipped and reported in the status line; the other still runs. With no binder, nothing opens.

- mDNS: `224.0.0.251:5353`, buffer `MDNS_MAX_PACKET = 9000`. New pure `MdnsPacket.parse(bytes): List<MdnsRecord>`: responses only (QR bit set); answers and additionals; PTR, SRV, TXT, A and AAAA records. A record is emitted when a PTR names an instance whose SRV target has an A record in the same packet; the type is `MdnsType.normalise`d, TXT parsed as `DeviceInfoTxt` does (its `readName` and TXT rdata helpers move to a shared internal `DnsWire` object, behaviour unchanged). Records without a linked A record are ignored. At most `HEARD_MAX_PER_PACKET = 16` records are emitted per packet; the rest are dropped.
- SSDP: `239.255.255.250:1900`, buffer 4096. New `SsdpParser.parseNotify(from, text): SsdpResponse?`: first line `NOTIFY * HTTP/1.1`, `NTS: ssdp:alive` only (byebye and M-SEARCH requests return null), `st` taken from `NT`, plus `LOCATION`, `SERVER` and `USN`. The address is the packet source.

Emissions use the scan's shapes plus a new in-memory `Source.HEARD` (never stored, as `sources` is not a column), wrapped as `Heard(identityKey: String, host: DiscoveredHost)` where `identityKey` is the `NetworkSignature.identityKey` of the env the collector was started on (G5):

- mDNS: `DiscoveredHost(ip, mdnsName = instance, mdnsServiceTypes, mdnsTxt, mdnsInstanceNames, ipv6LinkLocal, sources = {MDNS, HEARD})`.
- SSDP: `DiscoveredHost(from, upnpServer = server, mac = USN MAC, macSource = UPNP_UDN, sources = {SSDP, HEARD})`.

Repeats: a `(ip, type or USN)` key heard within `HEARD_REPEAT_MS = 60_000` of its last write is dropped before the queue. The repeat map is an LRU of `HEARD_REPEAT_KEYS = 1_024` keys (a `LinkedHashMap` with `accessOrder = true` and `removeEldestEntry`), per collector instance.

### 3.4 Entry into the inventory

A single writer coroutine per collector instance reads that instance's `Channel(HEARD_QUEUE = 64, BufferOverflow.DROP_OLDEST)`. For each `Heard` it drops the emission when any of these hold:

1. `currentNetwork` is null, its id equals `forgottenNetworkId`, or its identity key differs from `heard.identityKey` (a packet heard on network A is never written while B is current, and nothing is written in the window before `currentNetwork` catches up with a new env).
2. `radarScanActive` (the scan's own mDNS and SSDP cover it) or `undo.pending != null`.
3. The address is not in the network's `KnownRanges` (below).
4. No row exists for `(networkId, ip)` and this session has already inserted `HEARD_NEW_MAX = 32` new rows; after that, heard hosts only update existing rows.
5. `(networkId, ip)` is in the session's `removedThisSession` set: a device removed with Remove device while listening is not re-added until the next scan or the next listening session (G22).

`KnownRanges.of(home: Subnet, manual: List<Subnet>, hostIps: List<String>): KnownRanges` (pure, `engine/discovery/KnownRanges.kt`) is the home range from the live settings, the network's manual ranges, and the /24 of every stored visible (not `manualOnly`) RFC1918 host on the network; `contains(ip)`. The writer recomputes it from `hostsForNetwork(networkId)` and the settings flows. UniFi mDNS reflection across known VLANs still works; a VLAN the inventory has never seen needs a scan first. The same type is used by the manual tools route (4.5).

Otherwise the writer calls a `RadarCollector` built with `upsert = hosts::upsertHeard`; `RadarCollector` gains that one parameter (default `hosts::upsertDiscovered`), so the heard write takes `hostWriteLock`, honours `RemovedHosts.skip` and runs the same identification and `IdentityWrite.sameDevice` rule as a radar emission.

`HostRepository.upsertHeard(networkId, h, runIdForNew)`, one transaction, the `applyScan` merge with one difference: `lastDiscoveryRun` is left as stored for an existing visible row; a new row, or a `manualOnly` row being promoted, gets `runIdForNew`, which the writer reads as the network's `lastRunAt ?: 0`. Effects: a newly heard device shows undimmed after a completed scan; a known device not found by the latest scan stays dimmed until a scan finds it; "Last scan" never moves because something was heard; on a network with no run yet a heard row has `lastDiscoveryRun = 0` and is shown undimmed (the dim rule needs a latest run).

### 3.5 Battery and permission

`CHANGE_WIFI_MULTICAST_STATE` is already in the manifest (a normal, install-time permission at targetSdk 30; no prompt). Holding the lock makes the Wi-Fi chip wake the CPU for every multicast frame on the network, which costs power on busy networks; that is why it is held only in the foreground with the switch on and is off by default. Measured on the device in 8.4 item 6.

## 4. Manual tools (plan 3C-2)

### 4.1 Entry and screen

`HomeMenu` order becomes: "Quick scan all", "Run a tool...", "Forget this network", "Settings". "Run a tool..." (tag `menuRunTool`) is always enabled and opens the new route `tools` in `NetProbeNav`: `ManualToolsScreen` with `ManualToolsViewModel`. The three full stops match the 3B "Open with..." and are pinned in `CopyTableTest`.

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

Pure `ManualTarget.check(ipText, portText, tool, home: Subnet?): ManualCheck` with `ip: Inet4Address?`, `port: Int?`, `ipError`, `portError`, `canRun`:

- IP: `RowRules.parseIpv4` (dotted quad, no name lookup) else "Enter an address like 10.0.3.20". Then rejected with "That address cannot be used" when it is in `0.0.0.0/8`, `127.0.0.0/8`, `224.0.0.0/4` (multicast), `240.0.0.0/4` (reserved, including `255.255.255.255`), or is the network or broadcast address of the home range. Every other address is accepted: RFC1918, CGNAT `100.64.0.0/10`, link-local `169.254.0.0/16`, a publicly numbered LAN and public internet hosts (ruling 3).
- Port: SNMP walk fixes 161 (`ToolApplicability.SNMP_PORT`), SMB session fixes 445; the other three need a port: "This tool needs a port" when blank, "Use a port from 1 to 65535" when out of range or not digits.
- Blank fields show no error until the first Run tap.

### 4.3 Target resolution and keying

On Run, inside `c.undo.committedBefore { }` (the Scan rule: a pending Remove or Forget is final before a new write):

1. The network is `c.currentNetwork.value`, else `c.networks.ensure(env, GatewayMac.lookup(env.gateway))`; a null result or the id in `forgottenNetworkId` ends with `ScanController.NO_NETWORK`.
2. Route: `ManualRoute.of(ip, known, env)` (4.5).
3. Directory brute-force opens the existing `DirBruteSheet` first, with `DirBruteSheetState(port, "http(s)://<ip>:<port>/", lists, c.dirBruteOptions.load(), busyReason)`; Cancel creates nothing. Its Run saves the options and continues at step 4. The other tools go straight to step 4 with `ToolOptions.None`.
4. `c.toolRunner.startManual(networkId, ip, port, tls, toolId, options, route)`: in one `hosts` transaction, `HostRepository.manualTarget(networkId, ip)` returns the existing row for `(networkId, ip)` (visible or `manualOnly`) or inserts a new `HostEntity` with `manualOnly = true`, `deviceType = UNKNOWN`, `status = UNMARKED`, `note = ""`, `lastDiscoveryRun = 0`, `firstSeen = lastSeen = now` and every scan column null; then `start(ToolTarget(hostId, ip, port, tls, serverName, route), toolId, options)`. When `start` returns anything other than `Started` and the row was inserted by this call, the row is deleted before the call returns (G8).
5. `ToolTarget.tls` per 4.1 item 5 (TLS certificate always true, SMB and SNMP false); `serverName` null for brute-force (3B 4.9) and otherwise `ToolApplicability.serverName(host)`, which is null for a fresh manual row.
6. `HostBusy`, `TooMany` and `NoNetwork` map to the 3B messages (`ToolsUiModel.busyMessage`, `ToolCopy.BUSY_TOO_MANY`, `ScanController.NO_NETWORK`) in the screen's snackbar.

At app start, `HostDao.deleteOrphanManual()` runs `DELETE FROM hosts WHERE manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs)` once, on `appScope`, under `hostWriteLock` (a process killed between insert and start leaves no row behind).

Keying: the runner's one-run-per-host rule is per host row, so per (network, IP): one run at a time on an IP, whatever the port, shared with Quick scan all and Device-screen scans through `HostBusyRegistry`. The 4-hosts ceiling, `RateBucket` at `MAX_REQUESTS_PER_SECOND = 100`, background pause, network-change stop and `onFinished` re-identification are untouched.

### 4.4 Results, history, export

Tapping a history row opens the existing `ToolResultsSheet` on that run, with the switcher over the same host's runs of the same tool (`ToolResultsModel.of`), "Open with...", "Copy" and "Share" as in 3B 8.5. Export is `ToolExport` unchanged. End-of-run messages come through `AppMessages` as today; the screen shows `appMessages.current` in its snackbar as the Device screen does. Pruning stays 10 runs per (host, tool, port).

### 4.5 Route (D20)

Pure `ManualRoute.of(ip: Inet4Address, known: KnownRanges, env: NetworkEnv): ToolRoute` with `enum class ToolRoute { WIFI, ETHERNET, OTHER }`:

- `known` is `KnownRanges.of(HomeRange.of(env.interfaceIp, env.prefixLength, s.maxPrefix, s.extEnabled).subnet, manual ranges for this network, stored visible host IPs)` (3.4), read at the Run tap.
- `known.contains(ip)`: `WIFI` or `ETHERNET` per `env`'s transport. The run is bound to that `Network` through `binderFor(env)`; no binder gives `NoNetwork`, never an unbound run.
- Otherwise `OTHER`: the run goes out unbound on the system's routing, so a VPN or Tailscale route to that address carries it, and a public address goes out the default route.

Only manual tool targets get this rule. Radar scans, Quick scan all, Device-screen scans and the listener keep their bindings (2.4, 3.3). `ToolTarget` gains `route: ToolRoute?` (null for Device-screen runs); when non-null the runner's `bind` uses it instead of `ScanBinding`. The route is stored in `tool_runs.route` (`"WIFI"`, `"ETHERNET"`, `"OTHER"`, null for Device-screen and pre-4 runs), and the results sheet header shows, under the existing title, "via Wi-Fi", "via Ethernet" or "via VPN or other route" in `data` / `textMuted`; nothing when null.

## 5. Wordlist URL import (plan 3C-1)

### 5.1 UI

The Wordlists pinned block keeps the back row and title; beneath the title, a row of two pills: "Import file" (the 3B pill, renamed, tag `importWordlist` unchanged) and "Import from URL" (tag `importUrl`). Both are disabled while either import runs. "Import from URL" opens an `AlertDialog` titled "Import from URL" with one `OutlinedTextField` "URL" (placeholder `https://example.com/wordlist.txt`, URI keyboard, inline error, tag `urlField`) and the buttons "Import" (`signal`, enabled when the URL validates) and "Cancel".

While fetching, the URL pill reads "Fetching 1.2 of 3.4 MB" (or "Fetching 1.2 MB" with no length), updated at most every `PROGRESS_STEP_BYTES = 65_536` bytes, and a "Cancel" text button (`signal`, 48 dp, tag `cancelUrl`) appears beside it in the pinned row. Cancel ends the fetch within a second, stores nothing and shows "Import cancelled". Leaving the screen does not cancel: the job runs on `appScope`, as file import does, and the result goes to `AppMessages` when the screen is gone.

### 5.2 Why HttpURLConnection

The 3B `HttpClient` is the wrong tool here: it takes an `Inet4Address` (no DNS names), uses a trust-all `TrustManager` (an internet fetch needs real certificate and hostname checks), holds the body in memory with a 64 KB cap and a `2 x timeout` deadline (a 10 MB list fits neither), and connects through `Probe.connect`, which is shaped for LAN probes and the Wi-Fi binding. `HttpURLConnection` from `URL.openConnection()` gives platform DNS, the system trust store and hostname verification, streaming, and the default network (the fetch is to the internet, not a probe, so it is not bound to Wi-Fi and may use cellular). A blocked read is ended from a cancelling coroutine with the `CancellableIo.closing` watcher given `Closeable { connection.disconnect() }`. No new dependency. Android's `HttpURLConnection` is OkHttp-backed and differs from the desktop JDK's in gzip, `disconnect()` and redirects, so those behaviours are proved on the device (8.2 item 7).

### 5.3 Rules

Pure `UrlImportRules` in `wordlists/`:

- `validate(text): UrlCheck`: trimmed; no scheme means `https://` is prepended; scheme `https` or `http` only; a host is required; user info (`user:pass@`) is rejected; no whitespace or control characters; at most `MAX_URL_CHARS = 2048`; the fragment is dropped. Errors per the copy table.
- `redirect(original: URL, from: URL, location: String?, hops: Int): RedirectDecision`: `Follow(url)` when `hops < MAX_REDIRECTS = 3`, `location` resolves against `from` to an `http` or `https` URL, the hop is not `https` to `http`, and the target host is not an IP literal in `127.0.0.0/8`, `169.254.0.0/16` or RFC1918 space unless `original`'s host is itself such a literal. Otherwise `TooMany` (a fourth hop), `Insecure` (a downgrade) or `LocalTarget` (a public server bouncing the phone into a LAN address). Any other host is followed, so GitHub `/raw/` and gist links (to `*.githubusercontent.com`) and Dropbox `?dl=1` links (to `dl.dropboxusercontent.com`) work.
- `contentType(header: String?): Boolean`: the media type before any `;`, trimmed and lowercased, is accepted when it is `text/*` other than `text/html`, is `application/octet-stream`, or the header is missing.
- `looksBinary(prefix: ByteArray): Boolean`: true when the first `BINARY_SNIFF_BYTES = 8_192` bytes of the decoded body hold a NUL byte.
- `displayName(url): String?`: the last path segment of the final URL, percent-decoded, else the host; `WordlistStore`'s `cleanName` then strips the extension, caps the length and falls back to "Imported list".

Fetch (`UrlWordlistFetcher`, on `Dispatchers.IO`):

1. `instanceFollowRedirects = false`, `useCaches = false`, `connectTimeout = URL_CONNECT_TIMEOUT_MS = 10_000`, `readTimeout = URL_READ_TIMEOUT_MS = 15_000`, headers `User-Agent: NetProbe/0.1` (`HttpClient.USER_AGENT`) and `Accept: text/plain, */*;q=0.5`. No `Authorization`, no `Cookie`; the app sets no `CookieHandler`, and a test asserts none is installed.
2. A 3xx goes through `redirect`; each allowed hop opens a new connection with the same settings.
3. Status other than 200: "Could not fetch: the server answered N".
4. `contentType` false: "Could not fetch: not a text file (<type>)".
5. `Content-Length` above `WordlistStore.MAX_IMPORT_BYTES`: `ImportResult.TooLarge` without reading the body.
6. The body stream is buffered; its first 8 KB go through `looksBinary` ("Could not fetch: not a text file", nothing stored); then the stream, wrapped in a counting stream for progress, goes to the existing `WordlistStore.import(displayName, stream)`, which enforces 10 MB as it reads (after transparent gzip decoding), 200,000 entries, empty lists, normalisation and naming. Its `ImportResult` messages are reused as they are (`WordlistImport.message`).
7. Failures before the body: `UnknownHostException` "Could not fetch: unknown host"; `SocketTimeoutException` "Could not fetch: no answer"; `SSLException` "Could not fetch: secure connection failed"; any other `IOException` "Could not fetch: connection failed". A failure mid-body is the store's `Unreadable`.

Result type: `sealed interface UrlImportResult { data class Stored(val result: ImportResult); data class Failed(val reason: FetchFailure); object Cancelled }`, with `FetchFailure` = `UnknownHost`, `NoAnswer`, `Tls`, `Connection`, `Status(code)`, `NotText(type: String?)`, `Insecure`, `LocalTarget`, `TooManyRedirects`. `WordlistImport` gains `message(UrlImportResult)`.

## 6. Scope and plans

Not in 3C: SMB share listing and read checks (Phase 3D, carrying the 3B section 15 requirements unchanged); UPnP description fetches from the listener (10.3); a "name to send" field for manual TLS and fingerprint runs; editing or renaming wordlists; sweeping a wide masked subnet beyond the Large networks limit (10.2).

Build constraints: as the 3B spec 2.3. No new dependencies, no Gradle changes, no downloads (mDNS and SSDP test packets are authored byte arrays in the test sources). `room-testing` is already declared.

Both plans start only after the 3B device QA passes: 3C-2 touches `ToolRunner`, the results sheet and `HostBusyRegistry`, which the 3B QA is still exercising. Each task is built, reviewed and JVM-green before the next.

Plan 3C-1 (no schema change):

1. Settings defaults, overflow reset with Undo, default markers (section 1, 14 values).
2. Aggressive scan copy and section move, `HomeRange` mask rule, `RunSettings` snapshot, `ScanBinding` signature (2.1 to 2.3).
3. Manual ranges always swept: `ExtendedOptionsOf`, `evidence` flag, binding, range summary and notices, stepper and editor always enabled (2.4).
4. Smart discovery outward walk and its note (2.5).
5. Wordlist URL import (section 5), including the device-run `UrlWordlistFetcherDeviceTest`.
6. Device QA 3C-1 (8.3) by Bugsy on the Pixel 7 Pro.

Plan 3C-2 (schema 4), after 3C-1's device QA:

1. Schema 3 to 4, `manualOnly` and `route`, DAO and repository changes, orphan sweep, `DirectorCopyMigration34Test` (7.1, 8.2 items 1 to 3).
2. Manual tools screen, validation, route, results header (section 4).
3. Multicast listener: gate, controller, lock, parsers, `KnownRanges`, heard writer, `upsertHeard`, Settings switch and status line; `listenMulticast` joins the reset (section 3).
4. Device QA 3C-2 (8.4) by Bugsy, with the full data-safety preamble.

## 7. Data model and copy

### 7.1 Schema 3 to 4 (plan 3C-2)

| Change | Detail |
|---|---|
| `HostEntity.manualOnly` | `@ColumnInfo(defaultValue = "0") val manualOnly: Boolean = false` |
| `ToolRunEntity.route` | `val route: String? = null` |
| `MIGRATION_3_4` | `ALTER TABLE hosts ADD COLUMN manualOnly INTEGER NOT NULL DEFAULT 0`; `ALTER TABLE tool_runs ADD COLUMN route TEXT`; additive, no row rewritten |
| `NetProbeDb` | `version = 4`; `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`; never destructive; `4.json` exported and committed |
| `HostDao.forNetwork` | gains `AND manualOnly = 0`, so the Home list, `HomeStats`, groups, search and Quick scan all never see manual rows |
| `HostDao.allForNetwork` | new, every row; used by `HomeViewModel` for `toolRunningOnNetwork` (Forget stays disabled while a tool holds a manual row) and for `ExtendedOptions.knownIps` (the canary never picks a manual target) |
| `HostDao.updateScan` | also sets `manualOnly = 0`: a scan finding the IP promotes the row, keeping its tool history |
| `HostDao.deleteOrphanManual` | `DELETE FROM hosts WHERE manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs)` |
| `HostRepository.manualTarget`, `upsertHeard` | 4.3 and 3.4 |
| `ToolDao.recentForNetwork(networkId, limit)` | `SELECT tool_runs.*, hosts.ip FROM tool_runs JOIN hosts ON hosts.id = tool_runs.hostId WHERE hosts.networkId = :networkId ORDER BY startedAt DESC, tool_runs.id DESC LIMIT :limit` into `data class NetworkRun(@Embedded val run: ToolRunEntity, val ip: String)` |
| `SharedPreferences` | new key `listenMulticast` |

Why a flagged host row and not the alternatives: `tool_runs.hostId` is a foreign key to `hosts`, and `ToolRunner`, `HostBusyRegistry`, pruning, hints, the results sheet and the 3B Remove snapshot are all keyed by host id. A flagged row reuses every one of them, and promotion on discovery keeps the history with the device. A separate `manual_targets` table would need a second foreign key or a nullable `hostId` in `tool_runs`, a second claim key in `HostBusyRegistry` and a rewrite of every tool query. One synthetic "manual" host per network would put every manual IP under one busy claim and one history, and the results sheet title would show the wrong IP. Forget this network deletes manual rows with the rest (`deleteHostsOfNetwork`); manual rows have no Device screen, so Remove device never meets them.

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
| `ExtendedCopy.SUPPORT` | Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach. |
| `ExtendedCopy.PROBE_LABEL` | Smart discovery (was Probe for gateways) |
| `ExtendedCopy.PROBE_SUPPORT` | Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s. |
| `ExtendedCopy.MANUAL_LINE` | Swept first on every scan, with Aggressive scan on or off |
| `HomeCopy.NO_EXTRA_RANGES` | Aggressive scan: no extra ranges to sweep |
| `HomeCopy.GATEWAY_FOUND_NONE` | Smart discovery found no other ranges |
| `HomeCopy.GATEWAY_ANSWERS_ALL` | Smart discovery skipped: every address answered |
| `HomeCopy.GATEWAY_WALK_SKIPPED` | Smart discovery: too many ranges answered, nearby ranges not checked |
| `HomeCopy.SOURCE_GATEWAY` | smart discovery |
| `HomeCopy.capDropped(n)` | 1 more range found, raise Maximum extra ranges in Settings / 3 more ranges found, raise Maximum extra ranges in Settings |
| `HomeCopy.largeNetworkPartial(prefix, swept)` | Large network (/16). Scanned your /22 only. Widen in Settings. |
| `HomeCopy.MENU_RUN_TOOL` | Run a tool... |
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
| `ManualCopy.ERR_ADDRESS` | That address cannot be used |
| `ManualCopy.ERR_PORT_NEEDED` | This tool needs a port |
| `ManualCopy.ERR_PORT_RANGE` | Use a port from 1 to 65535 |
| `ManualCopy.HISTORY` | Recent runs on this network |
| `ManualCopy.HISTORY_EMPTY` | No tools run on this network yet |
| `ManualCopy.historyRow(tool, ip, portLabel)` | HTTP fingerprint on 10.0.3.5:5000 |
| `ManualCopy.historyLine(summary, relative)` | Synology DSM, 6 findings, 2 min ago |
| `ManualCopy.historyDescription(tool, ip, portLabel)` | Show HTTP fingerprint results for 10.0.3.5:5000 |
| `ToolCopy.ROUTE_WIFI` | via Wi-Fi |
| `ToolCopy.ROUTE_ETHERNET` | via Ethernet |
| `ToolCopy.ROUTE_OTHER` | via VPN or other route |
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
| `WordlistCopy.fetchNotText(type)` | Could not fetch: not a text file (text/html) / Could not fetch: not a text file |
| `WordlistCopy.FETCH_INSECURE` | Could not fetch: redirected to an insecure address |
| `WordlistCopy.FETCH_LOCAL` | Could not fetch: redirected to a local address |
| `WordlistCopy.FETCH_REDIRECTS` | Could not fetch: too many redirects |

`fetching` uses one decimal place in MB of 1,048,576 bytes. `fetchNotText` without the brackets is used for the NUL-byte check. The Radar spec's Settings section list ("Extended discovery") is superseded by this table. Removed from the earlier draft: `HomeCopy.MENU_AGGRESSIVE`, `HomeCopy.aggressiveDescription`, `ExtendedCopy.MANUAL_ON`, `ExtendedCopy.MANUAL_OFF`, `ManualCopy.ERR_PRIVATE`, `WordlistCopy.FETCH_OTHER_SITE`; `CopyTableTest` asserts none of them exists.

## 8. Tests and verification

### 8.1 JVM

Plan 3C-1:

1. `SettingsDefaults`: `reset` returns `SettingsState()` with the current NVD key; `differs` is false at defaults and true after any one covered value changes; `AppSettings` defaults equal `SettingsState()` for every key (fake prefs); the reset is one edit; Undo of a reset restores `before` with a newer NVD key kept.
2. Markers: a pure `DefaultMarker.text(value, default, unit)` is null at the default and "Default 600 ms" otherwise; the description template.
3. `HomeRange.of`: for every prefix 8 to 30 and every limit 20 to 24, Aggressive off equals `SubnetMath.sweepRange`; Aggressive on with `/16` at `172.20.9.40` gives `172.20.8.0/22` (limit 22) and `172.20.0.0/20` (limit 20), clamped, original prefix 16; `/20` at limit 22 gives the /22 holding the phone; `/24` and `/23` at limit 22 are identical on and off.
4. `RunSettings` snapshot: a fake radar run started with Aggressive off keeps unbound binding, the /24 home range and its grouping after Aggressive is switched on and Settings reset mid-run; the next run uses the new values.
5. `ScanBinding.run`: `bindOutside` false is unbound for any target; true binds a target outside the home range and leaves one inside unbound; true with no binder throws `BindFailedException`; Aggressive off with one manual range gives `bindOutside` true.
6. `ExtendedOptionsOf.from`: Aggressive on with Smart discovery off carries the manual list, `gatewayProbe = false`, `evidence = true`; Aggressive off with a manual list gives `enabled = true`, `gatewayProbe = false`, `evidence = false`; Aggressive off with an empty list gives `ExtendedOptions()`.
7. Engine with fakes: Aggressive off, one manual range, an off-subnet DNS server: the manual range is swept, no evidence range is added, the gateway probe fake is never called, and the status ranges list the manual range with source `MANUAL`; Aggressive on, probe off, one manual range: the probe is never called and the range is swept; Aggressive off with no manual ranges is identical to the MVP case (Phase 2 12.2 regression).
8. `RunEnd.of` and `RangeSummaryText`: Aggressive off with a manual range gives "1 other range checked", the line "10.0.4.0/24  manual  swept, 3 found" and the nothing-found, phantom, stopped and bind-failed notices; `NO_EXTRA_RANGES` only with Aggressive on; `largeNetwork` vs `largeNetworkPartial` by the run's snapshot.
9. `GatewayTargets.of` for `10.0.3.20/24`, gateway `10.0.3.1`: 255 units; `10.0.0.1`, `10.0.1.1`, `10.0.2.1`, `10.0.4.1`, `10.0.255.254` present; no `10.0.3.x`; offsets {1, 254}; exactly the 17 seeds of 2.5; with an Aggressive home range of `10.0.0.0/22`, units `10.0.0.0/24` to `10.0.3.0/24` excluded.
10. Outward walk with a fake connect: the 2.5 example yields exactly `10.1.1.0/24`, `10.1.2.0/24`, `10.1.3.0/24` and probes exactly `10.1.0.1` and `10.1.2.1` to `10.1.5.1`, in rounds 1 to 4; walk order across two answering seeds is seed 1 down, seed 1 up, seed 2 down, seed 2 up within each round; with cap 2 and one first-pass hit (`B = 1`) the walk stops after the first round with a hit and any extra hit of that round is in `truncated`; with manual units filling the cap (`B = 0`) no walk unit is probed; five answering seeds give no walk and `GATEWAY_WALK_SKIPPED_MANY`; a direction ends after 2 consecutive misses, at distance 16 and at octet 0 or 255; already-probed units skipped without counting as misses; no walk for a seed inside the phone's /16; no seed answering gives the Phase 2 attempt count exactly; walk attempts never exceed 256; progress total monotonic; cancellation mid-walk returns within 1 s.
11. `UrlImportRules`: scheme prepending, http kept, ftp and javascript rejected, user info rejected, length cap, fragment dropped; `redirect` follows three hops to other hosts (`github.com` to `raw.githubusercontent.com`, `www.dropbox.com` to `dl.dropboxusercontent.com`), rejects a fourth (`TooMany`), rejects https to http (`Insecure`), allows http to https, rejects `example.com` to `192.168.1.1`, `10.0.0.5`, `127.0.0.1` and `169.254.1.1` (`LocalTarget`), allows `10.0.3.5` to `10.0.3.6`; `contentType` accepts `text/plain; charset=utf-8`, `TEXT/PLAIN`, `text/x-wordlist`, `application/octet-stream` and a missing header, rejects `text/html` and `application/zip`; `looksBinary` true with a NUL in the first 8 KB, false with one after it; `displayName` decoding, the final URL's segment after a redirect and the host fallback.
12. `UrlWordlistFetcher` against a local `ServerSocket` on 127.0.0.1 over HTTP (rules and wiring only; Android behaviour in 8.2 item 7): 200 `text/plain` imports and reports entries; 404, `text/html` and a NUL-byte body give their messages and store nothing; `Content-Length: 11000000` is rejected with nothing read beyond the headers; a redirect chain of three followed, a fourth rejected; the request carries no `Cookie` or `Authorization` header and `CookieHandler.getDefault()` is null; a body over 10 MB without `Content-Length` gives `TooLarge`.
13. Copy tables: `CopyTableTest` and `ToolCopyTableTest` include every row of 7.2 that the plan adds; the removed names are absent; "Run a tool..." uses three full stops; no string contains an em-dash.

Plan 3C-2:

14. `ListenGate.plan`: null for each of switch off, background, no LAN, both protocols off; the plan carries the mDNS and SSDP flags.
15. `ListenController` with a fake lock, fake socket factory, fake interface lookup and test dispatcher: launch with the switch off holds nothing; on acquires once; foreground false releases and closes sockets; switch off releases; env null releases; env change releases then re-acquires with a new empty queue; a Settings reset releases; the scan lock's release does not release the listen lock (two fakes); a null interface opens no socket for it and reports `NO_PORTS` when both fail.
16. `MdnsPacket.parse` on authored packets: a PTR+SRV+TXT+A announcement gives one record with normalised type, port, TXT and IPv4; AAAA link-local goes to `ipv6`; a query (QR clear) gives nothing; an A record without a service gives nothing; compression pointers; truncated and looping packets return empty without throwing; a packet with 40 linked instances emits 16.
17. `SsdpParser.parseNotify`: alive with LOCATION, SERVER, NT, USN; byebye null; M-SEARCH null; a 200 response null.
18. Heard writer: an address outside `KnownRanges` dropped; a new row inside the home range, a manual range and a known host's /24 kept; the 33rd new row in a session dropped while an update to an existing row still applies; a repeat within 60 s dropped, after it kept; the repeat map never holds more than 1,024 keys; dropped while `radarScanActive`, while an Undo is pending, with no current network, for the forgotten network and for a removed-this-session pair; queue overflow drops the oldest; env A heard and enqueued, env switched to B before `currentNetwork` changes: nothing written to A or B, and the old queue is discarded on restart.
19. `KnownRanges.of`: home range of any class, manual ranges, /24s of visible RFC1918 hosts; `manualOnly` hosts contribute nothing.
20. `ManualTarget.check`: every error string; SMB and SNMP fixed ports ignore the port field; `0.1.2.3`, `127.0.0.1`, `224.0.0.251`, `240.0.0.1`, `255.255.255.255` and the home range's network and broadcast addresses rejected; `8.8.8.8`, `100.64.1.1`, `169.254.10.10` and `10.20.0.5` accepted; blank fields silent until a Run tap.
21. `ManualRoute.of`: a home-range target and a known-/24 target give `WIFI` on Wi-Fi and `ETHERNET` on Ethernet and invoke the binder; with no binder, `NoNetwork`; `10.20.0.5` outside the known ranges and `8.8.8.8` give `OTHER` and the binder is never invoked; the result is the same with Aggressive on or off for a target outside every known range.
22. Manual start: `HostBusy`, `TooMany`, `NoNetwork` messages; `ToolTarget` port, `tls` and `route` per tool; `serverName` null for brute-force and for a fresh manual row; `undo.committedBefore` runs before `manualTarget`; a `TooMany` result deletes the row the call inserted and keeps a row that existed; a cancelled brute-force sheet creates no row.
23. Results header: `ToolCopy.ROUTE_*` per stored route, nothing for null.
24. Copy tables for the 3C-2 rows, as item 13.

### 8.2 Instrumented

1. Migration: schema 3 with a network, a host with a note and a tool run migrates through `MIGRATION_3_4` with `runMigrationsAndValidate(..., validateDroppedTables = true)`; old rows intact, `manualOnly = 0`, `route` null; the 1 to 4 chain; the real builder opens the result. Uses `MIGRATION_TEST_DB` only.
2. `DirectorCopyMigration34Test`: runs `MIGRATION_3_4` on `netprobe-migration-copy.db` (the Director's database pushed in 8.4 item 0) and asserts every network, host, mark, note, override, port and tool run of the copy is unchanged; skipped (assumption failure) when the copy is absent; its `@After` deletes the copy even on failure. Never touches `netprobe.db`.
3. DAO: a `manualOnly` row is absent from `forNetwork` and present in `allForNetwork`; `upsertDiscovered` on it promotes it and its tool runs remain; `upsertHeard` keeps an existing row's `lastDiscoveryRun` and sets `runIdForNew` on insert and on promotion; a heard row on a network with no run has `lastDiscoveryRun = 0` and maps to an undimmed `HostRow`; `deleteOrphanManual` removes a `manualOnly` row with no runs and keeps one with a run and every visible row; `recentForNetwork` orders, limits and excludes other networks; Forget's cascade removes manual rows and their runs.
4. Compose, Settings: the overflow item is disabled at defaults; after changing a slider the marker shows and tapping it restores the default; Reset to defaults then Undo restores every value; the Aggressive section sits under Discovery; the stepper and manual editor are enabled with Aggressive scan off and the editor shows "Swept first on every scan, with Aggressive scan on or off".
5. Compose, Home: the overflow lists "Quick scan all", "Run a tool...", "Forget this network", "Settings" in that order and nothing else.
6. Compose, manual tools: the Run pill is outside `manualHistory` (scrolling the list never moves it); errors show; SMB disables the port field; a fake runner run shows Stop and progress; a history row opens the results sheet with its route line.
7. `UrlWordlistFetcherDeviceTest` (on the phone, Android's `HttpURLConnection`, a `ServerSocket` on 127.0.0.1): a gzip-encoded body that decodes to over 10 MB stops at the cap with `TooLarge`; Cancel during a slow body returns `Cancelled` within 1 s and leaves no file; `Content-Length` above the cap reads nothing; progress reports a total with `Content-Length` and none with gzip or chunked encoding; a cross-host redirect is followed by the fetcher, not by the platform.
8. Compose, Wordlists: both pills pinned; the URL dialog validates; during a fake fetch the pill shows progress and Cancel is present; Cancel shows "Import cancelled".

Plan 3C-1 runs items 4, 7 and 8; plan 3C-2 runs items 1 to 3, 5 and 6.

### 8.3 Device checklist 3C-1 (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, by Bugsy, mandatory)

0. Data safety, before installing the 3C-1 build: record the base commit in `docs/superpowers/plans/phase3c-base.txt`; back up `databases/netprobe.db*` and `shared_prefs/netprobe.xml` with `adb exec-out run-as` to `K:/claude/netprobe_db_backup/phase3c-1/` (outside the repository) and record each file's MD5 on the PC and on the phone (they must match); build a rollback APK from the 3B head into `/k/claude/netprobe_rollback_<3B head>` from the local tree (no downloads). No schema change, so no migration test. Any restore uses `adb exec-in run-as ... sh -c "cat > <file>"` with an MD5 check, never `adb shell ... < file` (it truncates binary input on this PC, 3A finding F5).
1. Settings: change two sliders and two switches, Reset to defaults, Undo, Reset again; values persist across an app restart; then restore the Director's prefs from the step 0 backup (exec-in, MD5 checked) and confirm his tuning is back.
2. Aggressive scan off, manual list empty: a scan matches the Phase 2 check 1 baseline.
3. Aggressive scan off, manual `10.0.4.0/24`: the manual range is swept after the home subnet, bound to Wi-Fi; the summary shows "10.0.4.0/24  manual"; no evidence range and no gateway probe line. Then Aggressive on, Smart discovery off: the manual range is swept first, evidence ranges may follow, and no "probing ... for gateways" line appears.
4. Smart discovery on: `10.0.4.0/24` found as in Phase 2 check 3; the logged attempt count is within 10 percent of 1,056; no outward walk runs unless a seed answers (record which seeds answered).
5. Toggle Aggressive scan during a running scan: the running scan's grouping and progress do not change; the next scan uses the new value.
6. URL import: a GitHub `/raw/` link (redirects to `raw.githubusercontent.com`) over HTTPS, a `raw.githubusercontent.com` link, a plain HTTP URL on the LAN, a 404, a `.zip`, an HTML page, and Cancel mid-fetch of a large list.
7. A wide mask cannot be produced on the Director's LAN: record "NOT RUN, covered by 8.1 item 3" unless a client or guest network with a mask wider than /22 is available.

### 8.4 Device checklist 3C-2 (same phone and LAN, by Bugsy, mandatory)

0. Data safety, before installing the 3C-2 build: record the base in `phase3c-base.txt`; back up the schema 3 `databases/netprobe.db*` and `shared_prefs/netprobe.xml` to `K:/claude/netprobe_db_backup/phase3c-2/` with MD5 on PC and phone; build the rollback APK from the 3C-1 head; push `netprobe.db.bak` as `databases/netprobe-migration-copy.db` with `adb exec-in run-as ... sh -c "cat > ..."` and compare MD5 (stop on mismatch); run `DirectorCopyMigration34Test` (it must report 1 test, not an assumption failure) and confirm `netprobe.db*` sizes and times are unchanged; only then install the build, migrate in place, and check every mark, note, override, port and tool run against the backup; then back up the schema 4 database the same way.
1. Manual tools on hosts not yet in the inventory where possible: HTTP fingerprint of the Pi-hole by IP and port; TLS certificate of the UniFi gateway on 443; SMB session on CHNAS; SNMP walk on the router. Each shows results, history, Share and "via Wi-Fi"; a later radar scan shows any new manual target as a normal device with its tool history.
2. Manual tools: Stop ends a brute-force within a second; a second run on the same IP gives "A tool is already running on this device"; a cancelled brute-force sheet leaves no hidden row (row count from the pulled database copy before and after).
3. Manual tools route: a public target (`1.1.1.1:443`, TLS certificate) shows "via VPN or other route"; with a VPN app already installed on the phone (no installs), a target only the VPN routes shows the same and reaches its host; without one, record "NOT RUN, covered by 8.1 item 21".
4. Listening on, no scan since launch: within 10 minutes at least one device not yet in the inventory appears, or a known device's `lastSeen` updates; record what was heard and by which protocol. `dumpsys wifi` lists the `netprobe-listen` lock while Home is open, and not after Home is sent to the background.
5. Listening during a scan: no duplicate rows, the status line reads "Paused while a scan runs", and the scan's own lock is released at its end while `netprobe-listen` stays held. Wi-Fi off then on: the lock is released and re-acquired, nothing is written in between.
6. Battery: 15 minutes on Home with listening on, then off, screen on; record `dumpsys batterystats` Wi-Fi and CPU for each.
7. Network change mid-listen: on the guest SSID if the UniFi has one (a disposable network, and the only place destructive checks such as Forget this network run), confirm no guest device lands in the home network's inventory and none of the home devices lands in the guest network's; otherwise record "NOT RUN, covered by 8.1 item 18".

Definition of done, per plan: its 8.1 and 8.2 items pass, its 8.3 or 8.4 checklist is recorded with results (NOT RUN items named with the reason), and the Director has seen the screens it changes on the device (3C-1: Settings and Wordlists; 3C-2: Home menu, Run a tool and the results header).

## 9. Not as drawn, and deviations from earlier specs

| # | Earlier spec or brief | This spec | Why |
|---|---|---|---|
| 1 | 3B spec 15: "Phase 3C" is SMB share enumeration | That work is Phase 3D, unchanged | D18 |
| 2 | Brief: "the outward continuation rule from Phase 2" | Phase 2 has none; the whole-/16 walk covers the Director's example; 3C adds a bounded outward walk for answering seeds outside the /16 | 2.5, D9 |
| 3 | Phase 2 8.3: manual editor and stepper disabled while the master toggle is off; manual ranges swept only with it on | Editor and stepper always enabled; manual ranges swept on every scan | D8, ruling 2 |
| 4 | Phase 2 8.3: "Probe for gateways", subtitle ending "Noisy on networks you do not own." | "Smart discovery" with the Director's description and the true cost | D7; the copy rules allow no warnings |
| 5 | Radar spec 6: Settings order and content unchanged; section list in its copy table | Aggressive scan moves under Discovery; new switch in Discovery; Settings overflow | D6, D1, D10 |
| 6 | 3B spec 8.6: pill "Import wordlist" | "Import file" beside "Import from URL" | D17 |
| 7 | 3B spec 4.6 and decision table: `HttpURLConnection` rejected | Used for the URL import only; tools keep `HttpClient` | 5.2 |
| 8 | Phase 2 4.1: off-subnet mDNS and SSDP sightings stored whatever the toggle | Unchanged for scans; heard hosts are limited to the network's known ranges and 32 new rows per session | 3.4 |
| 9 | Phase 2 4.1 and MVP: a mask wider than the limit sweeps the /24 | Unchanged with Aggressive scan off; with it on, the limit-sized block | D19 |
| 10 | Phase 2 5.5: anything outside the swept range is bound to Wi-Fi when extended discovery is on | Unchanged for scans and the listener; manual tool targets outside the known ranges go unbound | D20, ruling 4 |

## 10. Assumptions and open items for the Director

Assumptions:

- A1. Reset keeps the NVD key, manual ranges and the brute-force sheet's last-used options (D3). Say if any should reset too.
- A2. Binding UDP 5353 and 1900 with `SO_REUSEADDR` works beside the system mDNS service on the Pixel 7 Pro; if not, the status line says so and 8.4 item 4 records it.
- A3. The UniFi Wi-Fi forwards multicast between clients (no multicast-to-unicast conversion or client isolation on that SSID); otherwise the listener hears little.
- A4. Android's `HttpURLConnection` decodes gzip transparently, so the 10 MB cap applies to the decoded text; proved by 8.2 item 7.
- A5. `raw.githubusercontent.com` serves `text/plain; charset=utf-8`; any `text/*` is accepted anyway.

Open items:

1. Review rigour for each plan: light, standard or heavy (standard suggested for both, with a security look at the URL fetch in 3C-1 and the listener and unbound manual route in 3C-2).
2. Ruling 1 reads "a client /16 included, capped by the Large networks limit": the widest limit is /20, so a /16 client gets at most its /20 (16 of 256 /24s). Sweeping a full /16 would need a wider limit stop (about 68 minutes at the measured 16 s per /24). Confirm the /20 ceiling is what you meant.
3. Should the listener fetch a heard device's UPnP description (one GET per new LOCATION) for names between scans? Off in this phase.
4. Should the listener also accept an mDNS A record whose name is a `.local` host name, with no service, as a name-only sighting (under the 3.4 limits)? Gruff's G14 suggestion; off in this spec.
5. Confirm the labels "Aggressive scan", "Smart discovery" and "Listen between scans", and the added "via Ethernet" route string (Ethernet-bound runs would otherwise read "via Wi-Fi").

## 11. Gruff review response

| Finding | Response |
|---|---|
| G1 BLOCKING, Home Aggressive item | Applied: D6, 4.1 menu order, 7.2 rows removed, 8.2 item 5. |
| G2 BLOCKING, mask not delivered | Applied per ruling 1: D19, 2.1 `HomeRange`, 8.1 item 3. The `RangeSource.MASK` sweep of the rest of the subnet is not applied: the ruling caps the sweep at the Large networks limit (open item 2). |
| G3 BLOCKING, VPN depends on toggle | Replaced by ruling 4: D20, 4.5, 8.1 item 21. The toggle no longer affects manual routing; known-range targets are always bound, others always unbound. |
| G4 BLOCKING, unbounded heard rows | Applied: 3.3 (16 per packet, 1,024-key LRU), 3.4 (`KnownRanges`, `HEARD_NEW_MAX = 32`), 8.1 items 16, 18, 19. |
| G5 BLOCKING, cross-network writes | Applied: 3.3 `Heard.identityKey`, 3.2 per-instance queue, 3.4 drop rule 1, 8.1 item 18. |
| G6 BLOCKING, data-safety steps | Applied: 8.3 items 0 and 1 (prefs restore), 8.4 item 0, 8.2 item 2 `DirectorCopyMigration34Test`; destructive checks only on a disposable network (8.4 item 7). |
| G7 SHOULD FIX, RFC1918-only tools | Superseded by ruling 3: any address except the reserved set (4.2); the RFC1918 limit on manual ranges is stated in 2.3. |
| G8 SHOULD FIX, orphan rows | Applied: 4.3 steps 3 and 4, orphan sweep, 7.1, 8.1 item 22, 8.2 item 3. |
| G9 SHOULD FIX, redirects and types | Applied: D16, 5.3, 8.1 items 11 and 12. |
| G10 SHOULD FIX, JVM vs Android HTTP | Applied: 8.2 item 7 `UrlWordlistFetcherDeviceTest`. |
| G11 SHOULD FIX, null interface | Applied: 3.3, 8.1 item 15. |
| G12 SHOULD FIX, walk on bogon-reject networks | Applied: 2.5 step 1 (`WALK_MAX_SEEDS = 4`), true cost recomputed (about 1,850 attempts, about 30 s). The "same latency band" stop is not applied: `Probe.Outcome` carries no latency, and the seed limit and the budget already bound the case. |
| G13 SHOULD FIX, QA item 2 wording | Applied: 8.3 item 3. |
| G14 SHOULD FIX, listener QA criteria | Applied: 8.4 item 4 (10 minutes, either outcome). The name-only sighting is not applied: open item 4. |
| G15 SHOULD FIX, mid-scan toggle | Applied: D21, 2.2 `RunSettings` snapshot, 8.1 item 4, 8.3 item 5. |
| G16 SHOULD FIX, two plans | Applied: D22, section 6. The outward walk sits in 3C-1 with the rest of Aggressive scan (no schema, no listener). |
| G17 SHOULD FIX, manual ranges gated | Applied per ruling 2: D8, 2.4, 8.1 items 6 to 8. |
| G18 MINOR, reset edges | Applied: 1.2 (Undo restores the whole snapshot), 1.1 (`maxPrefix` noted). |
| G19 MINOR, markers | Applied: 1.3 fallback; the Director sees them on device (definition of done). |
| G20 MINOR, no warning copy | Kept; the cost figure is now true (2.5). |
| G21 MINOR, cap notice | Applied: `capDropped` names "Maximum extra ranges" (7.2). |
| G22 MINOR, heard re-adds removed devices | Applied: 3.4 drop rule 5, 8.1 item 18. |
| G23 MINOR, `upsertHeard` with no run | Applied: 3.4, 8.2 item 3. |
| G24 MINOR, copy nits | Applied: three full stops kept to match "Open with...", pinned in 8.1 item 13; `ERR_PRIVATE` and the Aggressive menu rows removed. |
| G25 MINOR, second network or VPN | Applied: JVM fakes (8.1 items 18, 21), device checks with Wi-Fi off and on, the guest SSID and an already-installed VPN, NOT RUN recorded (8.3 item 7, 8.4 items 3 and 7). |
