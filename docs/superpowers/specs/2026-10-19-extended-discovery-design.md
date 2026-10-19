# NetProbe Extended Discovery Design (addendum to the MVP spec)

Date: 2026-10-19
Status: draft for Director review
Project: `K:\claude\netprobe`, built on master at 98a71e7
Parent spec: `2026-10-19-netprobe-mvp-design.md` (this addendum amends its sections 5.1, 5.2, 5.6, 5.7, 8 and 10, and pulls the roadmap item "Extended discovery across other RFC1918 ranges" forward)

## 1. Purpose

The Director wants to find devices that sit on ranges other than the phone's own, but are still reachable from it. Two real cases drive this:

- Weak segmentation on the Director's own networks: a Hue Bridge on `10.0.4.0/24` answers to a phone on another range because the router routes between them.
- Client sites during authorised assessments: a flat-looking guest or office Wi-Fi that can in fact reach server, camera or management ranges. Finding that is the point of the exercise.

Everything stays within the platform limits of the MVP spec section 4: ordinary TCP connects and UDP datagrams, no raw sockets, no root, no ICMP, no ARP injection.

Today the app already stores and labels off-subnet hosts (`RowRules.otherSubnet`) but only when mDNS or SSDP happens to reveal one; nothing ever sweeps their range. This addendum makes that deliberate: it turns each sighting into a sweep, adds an active way to find ranges, and lets the Director name ranges by hand.

Success: with the toggle on, on the Director's LAN, the Hue Bridge at `10.0.4.20` is found by evidence-led expansion, gateway probing finds `10.0.4.0/24`, the Home list groups hosts under one header per range, and Stop ends all of it within a second.

## 2. Scope

### 2.1 In this addendum

1. One master toggle, "Extended discovery", default off, gating every extended behaviour.
2. Three sources of candidate ranges: evidence-led, gateway probing, manual CIDR list.
3. A total range cap (default 4 extra /24s, maximum 16) bounding the work.
4. Sequential sweeps of each candidate range using the existing prober, with the existing enrichment steps.
5. Room schema version 2 (the project's first migration): per-host `subnet` and `isOffSubnet`.
6. Home list grouped by subnet with sticky headers; per-range progress; a Settings section.

### 2.2 Not in this addendum

- Ranges swept as one unit larger than a /24 (candidates are /24 units; a larger manual CIDR is expanded into /24 units that each count against the cap).
- Anything outside RFC1918: public addresses, CGNAT `100.64.0.0/10` and link-local `169.254.0.0/16` are never candidates.
- IPv6 range discovery. IPv6 addresses are ignored for candidate derivation.
- Traceroute or hop discovery to find ranges (needs ICMP or TTL control; roadmap).
- Automatic port scanning of extended hosts. "Quick scan all" keeps its current meaning (hosts of the current network, which now includes off-subnet rows; see 11.8).

## 3. Decisions

| Decision | Choice | Rejected alternative and why |
|---|---|---|
| Approaches | All three (evidence-led, gateway probe, manual) behind one toggle | Picking one: evidence-led finds nothing on a quiet range, probing is noisy, manual needs prior knowledge. Together they cover each other's gaps. |
| Master toggle | "Extended discovery", default off, persisted | On by default would send traffic to ranges the user did not choose, on any network the phone joins. |
| Gateway probe default | Off (separate toggle, only active when the master toggle is on) | On by default: about 500 connection attempts across a /16 look like a scan to a client IDS. Open item 13.1. |
| Candidate unit | /24 | Using each source's native prefix: a /22 manual entry would be one giant sweep and break per-range progress and cap arithmetic. |
| Cap | Default 4 extra /24s, user range 1 to 16, applied to the total of all three sources | A cap per source: harder to explain, and the total work is what matters. |
| Ordering under the cap | Manual first, then evidence, then gateway-probe hits | Probe hits first: they are the least certain and the noisiest. Manual is explicit intent; evidence is proof a host exists. |
| Sweep execution | After the local sweep, one range at a time, same prober and concurrency settings | Parallel ranges: multiplies in-flight sockets beyond the configured concurrency and breaks the load bound. |
| Liveness prober | Reuse `HostProber` and `Sweep` unchanged | A separate prober for routed ranges: duplicated code; the connect-or-refused rule already works through a router. |
| MAC for off-link hosts | Not from the neighbour table (section 6) | Trusting any neighbour entry: it would attribute the router's MAC to remote hosts. |
| Storage | Same network row; `subnet` text and `isOffSubnet` columns on `hosts` | A separate table or network row per range: breaks annotation continuity and the `(networkId, ip)` unique index. |
| Row label | The group header carries the subnet; the row's "other subnet" suffix is removed | Keeping both: redundant text on every row. |
| Persistence of ranges | Candidates are never stored, only hosts and their `subnet`; gateway-probe hits are not remembered between runs | Caching probe results: stale, and hides segmentation changes, which is what the Director is testing. |

## 4. Candidate ranges

### 4.1 Common rules

A candidate is a `Subnet` (existing class in `netenv/Subnet.kt`). For every source:

- Only RFC1918 addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) qualify. Anything else is dropped silently.
- The candidate must not overlap the scanned local range (`SubnetMath.sweepRange(...).subnet`). Exclusion is by the range actually swept, not the interface's true subnet; see 11.2 for the clamped /16 case.
- Candidates are de-duplicated after normalising to network address and prefix.
- The final list is truncated to the cap after ordering.

### 4.2 Source (a): evidence-led

Any host the run sees whose IPv4 address lies outside the scanned local range and passes 4.1 contributes the /24 containing it. Evidence comes from:

- mDNS records (the engine already emits these for any resolved IP, including off-subnet),
- SSDP responses (`r.from`),
- neighbour table entries outside the local range. These are rare (they occur when the layer-2 segment carries a second subnet) and the existing engine discards them (`range.subnet.contains(ip)`); the new rule keeps them as evidence, and they may carry a MAC because they are on-link (6.2).

Evidence ranges are ordered by the number of distinct hosts seen in them (descending), then by address. They are collected until the local sweep and the neighbour dump finish, so mDNS (6 s window) and SSDP (about 4 s) have always completed first.

Evidence hosts are stored and shown whether or not the toggle is on (as today); the toggle only controls whether their /24 is then swept.

### 4.3 Source (b): gateway probing

Active, and active only when both the master toggle and the gateway-probe toggle are on.

1. Take the phone's interface address. If it is not RFC1918, skip this source.
2. Compute the enclosing /16: `10.a.0.0/16` for `10.a.x.y`, `172.b.0.0/16` for `172.b.x.y` (b in 16 to 31), `192.168.0.0/16` for `192.168.x.y`. For `10.x` the whole /8 is not walked; only the phone's own /16.
3. For each of the 256 /24s in that /16, except any overlapping the scanned local range, TCP-probe `.1` and `.254`.
4. A /24 becomes a candidate when either address answers with a connect or `ECONNREFUSED` on any probe port. Timeouts and `EHOSTUNREACH` / `ENETUNREACH` mean no.
5. Probe ports, in order, stopping at the first definitive answer: 80, 443, 22, 53. A short list on purpose: gateways almost always answer on one of these, and every extra port multiplies the timeouts on empty addresses.
6. Per-connect timeout 400 ms (below the 600 ms sweep default; a routed /24 with no gateway usually fails fast or not at all, and the false-negative cost is acceptable). Concurrency 64 probes in flight, independent of the sweep concurrency setting, enforced by a `Semaphore` as in `Sweep`. Both values are named constants.
7. Gateway-probe hits are ordered by address and come last under the cap.

Probe budget: at most 255 /24s x 2 addresses x 4 ports = 2040 connect attempts, normally about 510 to 600 because live gateways answer on the first port. At 64 concurrency and 400 ms the worst case is about 13 s; typical is 3 to 8 s. The probe runs after the local sweep and before the extended sweeps, and can be cancelled at any point.

The probe sends no payload and reads no banner: connect, observe the outcome, close.

### 4.4 Source (c): manual CIDR list

Entered in Settings. Validation is a pure function `CidrParser.parse(text): CidrResult`:

- Accepts `a.b.c.d/p`, with whitespace tolerated and one entry per line or comma separated. A bare address without a prefix is rejected ("Add a prefix, for example /24").
- Each octet 0 to 255 with no stray characters; prefix as digits only; no hostnames; no IPv6.
- The address is normalised to its network address (`10.0.4.20/24` is stored as `10.0.4.0/24`, with the inline note "Normalised to 10.0.4.0/24").
- Allowed prefix range /16 to /30. Larger than /16 is rejected ("Too large, use /16 or smaller"). /31 and /32 are rejected ("Too small to sweep, use /30 or larger").
- Must be RFC1918 ("Only private ranges are allowed").
- A /16 to /23 entry expands into its /24 units, each counting one cap slot. A /25 to /30 entry is swept as given (one slot), never widened.
- An entry wholly inside the local scanned range is rejected inline ("This is your own network").
- Exact repeats are collapsed on save with an inline note.

Manual entries are persisted as a string list. List order is the order used under the cap.

### 4.5 Merging and the cap

Pure function (signature only):

```kotlin
object CandidateRanges {
    fun derive(
        scanned: Subnet,                 // the local range already swept
        manual: List<Subnet>,            // validated, in list order
        evidence: List<Inet4Address>,    // off-range addresses seen this run
        gatewayHits: List<Subnet>,       // /24s that answered the probe
        cap: Int,                        // coerced to 1..16
    ): CandidateResult
}
data class CandidateRange(val subnet: Subnet, val source: RangeSource)
enum class RangeSource { MANUAL, EVIDENCE, GATEWAY_PROBE }
data class CandidateResult(val ranges: List<CandidateRange>, val truncated: Int) // truncated = dropped only by the cap
```

Algorithm: expand manual entries to units; append evidence /24s (ordered per 4.2); append gateway hits; walk the list and accept an item if it is RFC1918, does not overlap `scanned`, and does not overlap an already accepted item; stop accepting at `cap`. An overlap always resolves in favour of the earlier (higher priority) item, so a manual `/28` inside an evidence `/24` keeps the `/28` and drops the `/24`. `truncated` counts valid items rejected only because the cap was full, so the UI can say "2 more ranges found, raise the cap in Settings".

### 4.6 Expected timings (Wi-Fi)

| Step | Typical | Worst |
|---|---|---|
| Local sweep (unchanged) | 10 to 20 s | as MVP |
| Candidate derivation | under 10 ms | under 10 ms |
| Gateway probe of a /16 | 3 to 8 s | about 13 s |
| One extra /24 sweep | 15 to 25 s | about 30 s |
| Default run (4 extra ranges, probe off) | local + about 1.5 min | local + about 2 min |
| Cap maximum (16 extra ranges) | local + 5 to 7 min | local + about 8 min |

A routed dead address may time out on all 12 liveness ports (7.2 s per host at 600 ms, 128 hosts in flight, so about two waves for a /24). Where the router replies with an ICMP unreachable the connect fails fast and the sweep is quicker. These are targets to be measured on the Pixel 7 Pro (12.4), not guarantees.

## 5. Engine changes

### 5.1 Options and status

```kotlin
data class ExtendedOptions(
    val enabled: Boolean = false,
    val maxRanges: Int = 4,                  // coerced to 1..16
    val gatewayProbe: Boolean = false,
    val manual: List<Subnet> = emptyList(),
)
data class DiscoveryOptions(/* existing fields */, val extended: ExtendedOptions = ExtendedOptions())

data class RangeProgress(
    val subnet: String, val source: RangeSource?,   // null = the local range
    val state: RangeState, val probed: Int, val total: Int, val found: Int,
)
enum class RangeState { PENDING, RUNNING, DONE, CANCELLED }
enum class DiscoveryPhase { LOCAL, GATEWAY_PROBE, EXTENDED, DONE }
data class DiscoveryStatus(/* existing fields */, val phase: DiscoveryPhase = DiscoveryPhase.LOCAL,
                           val ranges: List<RangeProgress> = emptyList(), val truncatedRanges: Int = 0)
```

The existing `probed`, `total` and `found` keep their meaning ("the range currently being swept", and hosts seen this run), so the current header and tests keep working.

### 5.2 Flow

The existing `discover()` body is refactored so the sweep plus its enrichment is one function taking a `Subnet`, called once per range (local first, then each candidate). With the toggle off nothing changes.

```
discover(env, options)
  1. local range = sweepRange(...)                      [unchanged]
  2. start mDNS and SSDP concurrently                   [unchanged]
  3. sweep local range + enrichment                     [unchanged]
  4. neighbour dump: MACs for on-link hosts only; off-range on-link entries become evidence
  5. if !extended.enabled: done
  6. optional gateway probe                             (phase GATEWAY_PROBE)
  7. CandidateRanges.derive(manual, evidence so far, probe hits)
  8. for each candidate in order                        (phase EXTENDED)
       sweep the range with the same prober + enrichment
       new off-range evidence found during step 8 does not add ranges this run (bounded work)
  9. done
```

mDNS and SSDP run in parallel with step 3 only, bounded by their own windows as today. Hosts they reveal inside a swept extra range merge by IP.

One new engine input, in the same function-typed style as the existing ones so tests can fake it:

```kotlin
val gatewayProbe: suspend (enclosing: Subnet, exclude: Subnet, timeoutMs: Int, concurrency: Int) -> List<Subnet>
```

`sweepFactory` is called once per range because `Sweep.progress` is per instance. The enrichment semaphore (32) is shared across ranges, so enrichment concurrency does not grow.

### 5.3 Enrichment in extra ranges

Reverse DNS, NetBIOS, SNMP and HTTP title run for every live host exactly as for local hosts, subject to the same per-feature settings. Expect NetBIOS (UDP 137) to be filtered by most routers and SNMP to be selective; both fail softly through `softly {}` and cost up to one timeout (1 s) each per host. The HTTP title fetch is TCP and generally works through a router, so it is the most valuable enrichment off-subnet. `isGateway` is set only for the interface gateway, never for hosts in extra ranges (a candidate's `.1` is just a host; the identity rules will call it a router if the evidence supports it).

### 5.4 Cancellation

The whole thing is one coroutine scope under `discover()`. Stop, a network change, or the collector being cancelled cancels the gateway probe and the current range sweep through the existing structured-concurrency path; in-flight blocking connects are closed from the cancelling coroutine as in `HostProber`; the multicast and Wi-Fi locks are released by the ViewModel's existing `finally`. Unfinished ranges are marked `CANCELLED` in `RangeProgress`; partial results stay.

## 6. Why MACs are limited off-subnet

### 6.1 The neighbour table

The kernel neighbour table maps IPs on the same layer-2 segment to MACs. For a host in another subnet the phone sends the packet to the router, so the only neighbour entry is for the router's address, carrying the router's MAC. There is never an entry for the remote host's own IP.

### 6.2 The rule

The neighbour step assigns a MAC only to an IP that appears in the dump for itself. A remote host that merely resolves via the gateway never receives the gateway's MAC. Hosts inside the interface's true subnet (including the unswept part of a clamped /16, see 11.2) are on-link and do appear in the dump.

### 6.3 Where MACs do come from in extra ranges

Only the secondary sources of the MVP spec section 4: UPnP UDN or USN (`MacSource.UPNP_UDN`), NetBIOS node-status (usually blocked through routers), and EUI-64 derivation from IPv6 link-local addresses learned over mDNS (`withDerivedMac`, which only works if an mDNS reflector passed the AAAA record). Many extra-range hosts will have no MAC and no OUI vendor. This is expected; the UI shows nothing for a missing MAC, as today. Identification then leans on names, UPnP, HTTP titles and port fingerprints, which the rules table already supports.

## 7. Data model

### 7.1 Schema version 2

`hosts` gains two columns:

```kotlin
// HostEntity additions
val subnet: String = "",            // "192.168.1.0/24", "10.0.4.0/24"; "" only when unknown
val isOffSubnet: Boolean = false,   // true when the host is outside the network's scanned local subnet
```

`NetProbeDb` moves to `version = 2` with `exportSchema = true`; `2.json` is committed alongside the existing `1.json`. The `(networkId, ip)` unique index is unchanged: off-subnet hosts live in the same network row, keyed by IP, because distinct RFC1918 addresses cannot collide within one network.

`subnet` is the exact `Subnet.toString()` so grouping and equality are textual. Local hosts store the scanned local subnet (clamped or not, per the current setting). Off-subnet hosts store the swept range if they came from one, else their /24 (what evidence-led seeding would have used).

`DiscoveredHost` is unchanged. The ViewModel passes the range to the repository:

```kotlin
suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long, subnet: Subnet, offSubnet: Boolean): Long
```

`applyScan` writes both columns (they are scan facts, like `lastSeen`, so a later run overwrites them); annotations are never touched, per MVP spec 5.6.

A pure helper replaces `RowRules.otherSubnet`:

```kotlin
object RangeRules {
    // scanned contains ip -> (scanned, false); inside a swept extra -> (that range, true); else (/24 of ip, true)
    fun subnetFor(ip: Inet4Address, scanned: Subnet, sweptExtras: List<Subnet>): Pair<Subnet, Boolean>
}
```

### 7.2 Migration 1 to 2

Room auto-migration cannot backfill meaning, so this is a hand-written `Migration(1, 2)`:

1. `ALTER TABLE hosts ADD COLUMN subnet TEXT NOT NULL DEFAULT ''`
2. `ALTER TABLE hosts ADD COLUMN isOffSubnet INTEGER NOT NULL DEFAULT 0`
3. Backfill in Kotlin inside the migration, for each `networks` row that has a `gatewayIp`: compute the local subnet from `gatewayIp` and `prefixLength` (a prefix below 22 uses the /24 containing the gateway, matching the default `maxPrefix` of 22). For every host of that network: if its IP lies inside the local subnet set `subnet` to it and `isOffSubnet = 0`; otherwise set `subnet` to the host's /24 and `isOffSubnet = 1`. Rows whose IP does not parse stay at `''` and `0`.
4. Networks with no `gatewayIp`: their hosts stay at `''` and `0`; the UI treats `''` as the local group.

The column defaults make the migrated schema match what Room expects for version 2, so the exported `2.json` and the identity hash validate.

Self-healing: the next discovery run rewrites `subnet` and `isOffSubnet` for every host it sees, so any backfill imprecision (for example a user-set `maxPrefix` of 20 or 21) is overwritten within one scan. Hosts not seen again keep the backfilled value and show dimmed, as stale hosts do today.

`fallbackToDestructiveMigration` is not used: losing annotations is exactly what the MVP promises not to do.

### 7.3 Migration test

Instrumented test with `MigrationTestHelper` against the exported `1.json` and `2.json`:

- Build a v1 database with: one network (gateway 192.168.1.1, prefix 24) with hosts `192.168.1.5` and `192.168.1.20` (the second annotated INTERESTING with a note and custom name) and an off-subnet host `10.0.4.20`; a second network (gateway 10.0.0.1, prefix 16) with hosts `10.0.0.9` and `10.0.7.3`; a third network with a null gateway and one host. Add a port row for one host.
- Run `runMigrationsAndValidate(db, 2, true, MIGRATION_1_2)`.
- Assert: row counts unchanged; annotations, notes, custom names and statuses identical; `192.168.1.x` rows have `subnet = "192.168.1.0/24"` and `isOffSubnet = 0`; `10.0.4.20` has `"10.0.4.0/24"` and `1`; in the /16 network `10.0.0.9` is `"10.0.0.0/24"` and `0` while `10.0.7.3` is `"10.0.7.0/24"` and `1`; the null-gateway host is `''` and `0`; the `(networkId, ip)` unique index and the port row are intact; Room opens the migrated database at version 2.

The backfill's subnet computation is a pure function and is also covered by JVM tests (12.1).

## 8. UI

### 8.1 Home list

- The list is grouped by `subnet`. The local group is first (the group whose subnet equals the scanned subnet, or `''`), then the other groups ordered by network address ascending.
- Each group has a sticky header (`LazyColumn` `stickyHeader`, an experimental foundation API the project opts into locally) reading `10.0.4.0/24, 3 devices`. The local group's header reads `192.168.1.0/24 (this network), 14 devices`. Counts are post-filter; a group with no matching rows is not shown. The header is exposed to accessibility as a heading.
- Within a group the order is unchanged: gateway first, then by IP. The gateway can only be in the local group.
- `HostRow` loses `otherSubnet` (and `offSubnet`) and gains `subnet: String`. `rowSecondaryLine` drops the "other subnet" suffix, so every row's secondary line is `ip  ·  identity`.
- Dimming of hosts not seen in the latest run is unchanged. Off-subnet hosts from an earlier extended run appear dimmed when the toggle is off, which is accurate: they were not re-checked.
- The pinned header (network panel, Scan/Stop, progress) and the pinned filter row stay outside the list, per the standing rule. The Scan button is not moved or resized by this feature.

### 8.2 Progress text

The local sweep keeps its line ("Found 14, probing 203/254"). In the extended phases:

- Gateway probe: "probing 10.0.0.0/16 for gateways, 112 of 256".
- Extended sweep: "probing 10.0.4.0/24, 2 of 4 ranges", counting extra ranges only (the local sweep is not one of the 4). When finished: "Found 19".
- If the cap dropped ranges, a one-line note at the end of the run through the existing `extraMessage` slot: "2 more ranges found, raise the cap in Settings".
- The error count is appended as today.

### 8.3 Settings: "Extended discovery" section

In this order:

1. Switch "Extended discovery" with supporting text "Sweep other ranges your phone can reach." Off by default. The remaining rows are disabled (visibly, not hidden) while it is off.
2. Stepper "Maximum extra ranges", 1 to 16, default 4, with a live line "At most N extra /24 networks (about M minutes)" using 25 s per range.
3. Switch "Probe for gateways" with supporting text "Checks .1 and .254 on every /24 in your /16 (about 510 connection attempts). Noisy on networks you do not own." Off by default.
4. "Manual ranges" editor: the list of entered CIDRs, each with a delete control, and an "Add range" text field with inline validation using the messages in 4.4. Invalid text shows its error under the field and Add stays disabled; normalisation notes show as helper text. Entries beyond the cap are marked "Beyond cap, will be skipped".

Persistence follows the existing `Settings.kt` (SharedPreferences): new keys `extEnabled`, `extMax` (coerced 1..16), `extGatewayProbe`, `extManual` (newline-joined; invalid strings on load are dropped).

## 9. Data flow

1. Tap Scan: as MVP steps 1 and 2 (resolve the network row, build options). The options now carry `ExtendedOptions` from Settings.
2. Local discovery runs; each `DiscoveredHost` is upserted with `RangeRules.subnetFor(...)`. Off-subnet hosts seen by mDNS or SSDP are stored immediately with their /24, toggle on or off.
3. If the toggle is on, after the neighbour dump the engine optionally gateway-probes, derives candidates and sweeps each in turn. Every host found is upserted into the same network row with its range.
4. Room flows push rows to Home; grouping happens in the `HomeViewModel` combine after the filter, so headers react to filter and search.
5. Stop, a network change or completion ends the run as today.

## 10. Safety and scope

- Extended behaviour of any kind (gateway probe, candidate sweeps) runs only when the master toggle is on. Storing an off-subnet host that mDNS or SSDP happened to reveal is not an extended sweep and keeps today's behaviour.
- The cap bounds the work: at most 16 extra /24s (about 4,000 host probes before the per-host port fan-out), and the gateway probe is at most 2,040 attempts. Defaults are 4 ranges and no probing.
- Stop cancels the probe, the current sweep and enrichment together; locks are released by the existing `finally`.
- A network change cancels the whole run as today. Candidate lists exist only inside the engine call and are never carried across networks.
- Cellular is never swept: the existing `NetworkEnv` rule (Wi-Fi or Ethernet only) applies before discovery starts, so an extended run cannot begin without a usable LAN. The phone's own address is skipped in extra sweeps as in the local one.
- Traffic is connect probes plus the existing enrichment fetches; no new payloads, and nothing goes to the internet.

## 11. Error handling and edge cases

- 11.1 Overlapping manual ranges: collapsed per 4.4 on save and per 4.5 on derive; the earlier item wins; no error, a helper note on save.
- 11.2 A /16 that is the local subnet (the phone is on a /16 and `maxPrefix` is 22, so the local sweep is clamped to the /24): the true subnet is the /16, the swept range is the /24. Candidate exclusion is by the swept range only, so the other 255 /24s of the /16 are eligible for evidence, gateway probing and manual entry. They are on-link, so the neighbour table can supply their MACs (6.2). If the user widens `maxPrefix` so the swept range covers more of the /16 (down to /20), what lies inside is simply the local range.
- 11.3 IPv6: AAAA records, IPv6 SSDP and IPv6 neighbour entries never produce candidates; `Inet4Address` is required at every step, as in the engine today.
- 11.4 Cellular: never swept (section 10). With no usable LAN, Scan is disabled as today.
- 11.5 Toggle on and an empty candidate list (no evidence, probe off, no manual): the run completes after the local sweep with the note "Extended discovery: no extra ranges to sweep".
- 11.6 Gateway probe finds nothing: the note "Gateway probe found no other ranges", and the run continues with manual and evidence candidates.
- 11.7 Manual entries beyond the cap are skipped with the "raise the cap" note; manual order is preserved so the Director controls which survive.
- 11.8 "Quick scan all" iterates the stored hosts of the network, which now includes off-subnet rows. This is consistent with "scan everything I know" but can be long with many extended hosts; the confirmation text states the host count and Stop still works. A "local only" option is open item 13.4.
- 11.9 A swept range that returns no hosts: nothing is stored; `RangeProgress` records `found = 0` and the run moves on.
- 11.10 Per-probe exceptions are counted and swallowed inside the engine task, never propagating (MVP spec 8). A failing gateway probe ends that step only (`isolated {}`), leaving evidence and manual ranges to proceed.
- 11.11 Migration failure surfaces as a Room exception on open; there is no silent destructive fallback. The test in 7.3 gates release.
- 11.12 DHCP renumbering: the `(networkId, ip)` key is per IP, so a changed IP is a new row (as today); an unchanged IP whose range string changes (for example after widening `maxPrefix`) is rewritten.
- 11.13 Long runs: a 16-range run takes minutes; progress and Stop stay pinned, and a network change mid-run keeps partial results with "Network changed, scan stopped".

## 12. Testing and verification

### 12.1 Unit tests (JVM)

- `CandidateRanges.derive`: local range excluded; non-RFC1918 and link-local dropped; /24 normalisation of evidence addresses; evidence ordering by host count then address; de-duplication across sources; overlap resolved to the earlier source (manual /28 kept over evidence /24); ordering manual, evidence, probe; cap boundaries 1, 4 and 16 and a request above 16 coerced; `truncated` counts only cap drops; empty inputs give empty output; the clamped /16 case (the other /24s of the real /16 stay eligible).
- `CidrParser`: valid forms; whitespace, comma and newline separation; host-bit normalisation; every rejection message (bad octet, bad prefix, non-numeric, missing prefix, larger than /16, smaller than /30, non-RFC1918, IPv6, hostnames, own network); expansion of /16 to /23 into /24 units and /25 to /30 kept as given.
- Enclosing /16 derivation for 10.x, 172.16 to 172.31 and 192.168; non-RFC1918 and 172.15 / 172.32 skipped.
- `RangeRules.subnetFor` for local, swept-extra and unswept off-subnet addresses.
- Grouping and header text: local first, ascending order, post-filter counts, empty groups hidden, formats ("10.0.4.0/24, 3 devices", "1 device").
- Progress text formatter for each phase.
- Migration backfill function (7.2 step 3), table-driven, including the /16 and null-gateway cases.

### 12.2 Engine tests with fakes

Using the existing function-typed constructor parameters and a fake prober:

- Toggle off: no gateway probe call, no extra sweeps, status identical to today (regression guard).
- Toggle on, manual range only: exactly that range is swept after the local one, hosts are emitted, enrichment runs for them, `isGateway` is false, and MAC is absent unless UPnP or NetBIOS supplies it.
- Evidence-led: a fake mDNS record for `10.0.4.20` causes `10.0.4.0/24` to be swept; the record's host merges with the sweep result by IP.
- Gateway probe: the fake receives the right enclosing /16 and exclusion; hits are swept after manual and evidence; with the probe toggle off it is never called.
- Cap: more candidates than the cap sweeps exactly `cap` ranges in the specified order and reports `truncatedRanges`.
- Neighbour dump: an off-link IP gets no MAC from the dump; an on-link off-range entry yields evidence plus a MAC.
- Status: `ranges` states move PENDING, RUNNING, DONE in order; `phase` transitions; `probed` and `total` reset per range.
- Cancellation: cancelling mid-extended-sweep or mid-probe stops promptly (under 1 s with fakes) and marks the range CANCELLED.
- A throwing gateway probe or enrichment step does not stop later ranges.

### 12.3 Instrumented tests

- Room v1 to v2 migration test (7.3).
- DAO round trip with the new columns; `upsertDiscovered` writes `subnet` and `isOffSubnet` and never alters annotations.
- Compose: the grouped Home list renders sticky headers with counts, local first; filters hide empty groups; the Settings Extended section rejects a bad CIDR inline and persists a good one; the Scan button stays pinned while the list scrolls.

### 12.4 On-device checks on the Director's LAN (Pixel 7 Pro, mandatory)

1. Toggle off: run a scan; result and timing match the pre-change baseline; any off-subnet host revealed by mDNS (for example the Hue Bridge) appears under its own header with no sweep.
2. Toggle on, probe off, no manual ranges: re-scan. The Hue Bridge at `10.0.4.20` is found by evidence-led expansion, `10.0.4.0/24` is swept, and its other devices appear under that header. Record time and host count.
3. Toggle on, probe on: `10.0.4.0/24` is found by gateway probing. This check is valid only when the phone's address is in the same /16 as `10.0.4.x`; otherwise record the phone's range and use manual entry for this check. Record probe duration and the hit list.
4. Manual range: add `10.0.4.0/24`, exercise the inline messages with bad input, and confirm manual ranges sweep first.
5. Cap: set the cap to 1 with several candidates; only the first (manual) range is swept and the "raise the cap" note appears.
6. Stop mid-extended-sweep: the scan ends within about a second, partial hosts remain, and no Wi-Fi lock is left held (`dumpsys wifi`).
7. Switch Wi-Fi networks mid-run: the run cancels and no candidate state carries over.
8. Upgrade test: install the current build, add annotations, install this build over it without uninstalling; annotations survive, old off-subnet rows appear under the correct headers, and a re-scan refreshes them.
9. Record per-range sweep times against the targets in 4.6; correct the Settings time estimate if it is off by more than 50%.

Definition of done: all tests in 12.1 to 12.3 pass, the nine on-device checks are recorded with results, and the Director has confirmed the grouped list and found ranges match his network.

## 13. Open items for the Director

1. Gateway probing default. Recommended off: 510 to 2,040 connection attempts across a /16 look like a scan to a client IDS and are noisy on networks you do not own. Confirm, or choose on-by-default whenever the master toggle is on.
2. Master toggle default and persistence. Recommended off and remembered across launches. The alternative is off at every launch, as a safety posture for client sites.
3. Default cap 4 and maximum 16: right numbers? Sixteen ranges is 5 to 8 minutes.
4. "Quick scan all" with extended hosts present: include them (current plan) or add a "local only" option?
5. Whether the manual list should allow /8 to /15 entries (rejected below /16 to keep ranges and time estimates sane).
6. Whether a swept-but-empty range should show an empty header ("10.0.7.0/24, no devices") as evidence it was checked. Recommended no for now; a per-run summary in the export roadmap item is the better home.
7. The on-device gateway check assumes the phone sits in the same /16 as `10.0.4.x`. Confirm the phone's range on the Director's LAN, or accept that check 3 falls back to manual entry.
9. Non-RFC1918 internal ranges (CGNAT `100.64.0.0/10`, or a client using public space internally) are never candidates. A manual override can be added later if needed.
