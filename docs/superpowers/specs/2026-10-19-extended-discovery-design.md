# NetProbe Extended Discovery Design (addendum to the MVP spec)

Date: 2026-10-19
Status: revised after Gruff review (`2026-10-19-extended-discovery-gruff-review.md`), for Director review
Project: `K:\claude\netprobe`, built on master at 98a71e7
Parent spec: `2026-10-19-netprobe-mvp-design.md` (this addendum amends its sections 5.1, 5.2, 5.7, 8 and 10, and pulls the roadmap item "Extended discovery across other RFC1918 ranges" forward)
Sibling spec: `2026-10-19-radar-ui-design.md` (the "Radar spec"). The Radar spec owns Home layout, group-header wording, progress-line wording and the done summary. This spec supplies data only (section 8).

## 1. Purpose

The Director wants to find devices that sit on ranges other than the phone's own, but are still reachable from it. Two real cases drive this:

- Weak segmentation on the Director's own networks: a Hue Bridge on `10.0.4.0/24` answers to a phone on another range because the router routes between them.
- Client-site assessments: a flat-looking guest or office Wi-Fi that can in fact reach server, camera or management ranges. Finding that is the point of the exercise.

Everything stays within the platform limits of the MVP spec section 4: ordinary TCP connects and UDP datagrams, no raw sockets, no root, no ICMP, no ARP injection.

Today the app already stores and labels off-subnet hosts (`RowRules.otherSubnet`) but only when mDNS or SSDP happens to reveal one; nothing ever sweeps their range. This addendum makes that deliberate: it turns each sighting into a sweep, adds an active way to find ranges, and lets the Director name ranges by hand per network. It also defends against networks that answer for every address (4.7), because without that defence routed sweeps would invent hosts.

Success: with the toggle on, on the Director's LAN (phone in `10.0.3.0/24`), the Hue Bridge at `10.0.4.20` is found by evidence-led expansion, gateway probing finds `10.0.4.0/24` (it lies inside the phone's enclosing `10.0.0.0/16`), the Home list groups hosts under one header per range, and Stop ends all of it within a second.

## 2. Scope

### 2.1 In this addendum

1. One master toggle, "Extended discovery", default off, gating every extended behaviour.
2. Three sources of candidate ranges: evidence-led, gateway probing, manual CIDR list (kept per network).
3. A total range cap (default 4 extra /24s, maximum 16) bounding the work.
4. Sequential sweeps of each candidate range using the existing prober, with the existing enrichment steps, protected by a phantom-range guard (4.7) and bound to the Wi-Fi or Ethernet `Network` (5.5).
5. Display-time grouping by range through a pure `RangeRules.groupFor`. No schema change (section 7).
6. Engine data for the Radar UI (phase, per-range progress, last-run range list) and a Settings section.

### 2.2 Not in this addendum

- Ranges swept as one unit larger than a /24 (candidates are /24 units; a manual CIDR from /20 to /23 is expanded into /24 units that each count against the cap).
- Anything outside RFC1918: public addresses, CGNAT `100.64.0.0/10` and link-local `169.254.0.0/16` are never candidates.
- IPv6 range discovery. IPv6 addresses are ignored for candidate derivation.
- Traceroute or hop discovery to find ranges (needs ICMP or TTL control; roadmap).
- Automatic port scanning of extended hosts. "Quick scan all" keeps its current meaning (hosts of the current network, which includes off-subnet rows; see 11.8).
- Persisting candidate ranges, probe hits or per-run range lists (the last run's list lives in the ViewModel only, 5.6).

### 2.3 Build constraints for every implementer task

No new dependencies, no downloads, no Gradle plugin or version changes. Everything this feature needs is already declared and cached: coroutines, Compose foundation, and the existing JVM test setup. The Compose BOM is `2025.06.00` (`gradle/libs.versions.toml`), which resolves foundation 1.8.2. Inspection of the cached `foundation-release.aar` shows `LazyListScope` carries no `ExperimentalFoundationApi` marker in 1.8.2 (the same scan does find the marker on other classes, for example `BasicTooltipState`), so `stickyHeader` is stable in the pinned BOM and no `@OptIn` is needed. Implementers rely on the compile result: add an opt-in only if the compiler asks for one. No schema export, `room-testing` or `androidTest` assets change is needed because there is no schema change.

## 3. Decisions

| Decision | Choice | Rejected alternative and why |
|---|---|---|
| Approaches | All three (evidence-led, gateway probe, manual) behind one toggle | Picking one: evidence-led finds nothing on a quiet range, probing is noisy, manual needs prior knowledge. Together they cover each other's gaps. |
| Master toggle | "Extended discovery", default off, persisted, global | On by default would send traffic to ranges the user did not choose, on any network the phone joins. Global is acceptable because the default is off. |
| Gateway probe default | Off (separate global toggle, only active when the master toggle is on) | On by default: about 1,600 connection attempts across a /16 look like a scan to a client IDS. Open item 13.1. |
| Candidate unit | /24 | Using each source's native prefix: a /22 manual entry would be one giant sweep and break per-range progress and cap arithmetic. |
| Cap | Default 4 extra /24s, user range 1 to 16, applied to the total of all three sources | A cap per source: harder to explain, and the total work is what matters. |
| Ordering under the cap | Manual first, then evidence, then gateway-probe hits | Probe hits first: they are the least certain and the noisiest. Manual is explicit intent; evidence is proof a host exists. |
| Sweep execution | After the local sweep, one range at a time, same prober and concurrency settings | Parallel ranges: multiplies in-flight sockets beyond the configured concurrency and breaks the load bound. |
| Liveness prober | Reuse `HostProber` and `Sweep`, with an optional bound `Network` and a phantom guard around each routed range | Reusing unchanged: "connect or refused means alive" is only safe on-link. Through a router, REJECT rules and captive portals answer for every address (4.7). |
| Socket routing | Extended-phase probes bind to the Wi-Fi or Ethernet `Network` (5.5) | Default routing: a VPN that routes RFC1918 would carry the probes to the far end. |
| MAC for off-link hosts | Not from the neighbour table (section 6) | Trusting any neighbour entry: it would attribute the router's MAC to remote hosts. |
| Manual list scope | Per network row: `extManual.<networkId>` in SharedPreferences | One global list: ranges typed for one site would be swept at every other site. |
| Grouping and off-subnet status | Derived at display time by `RangeRules.groupFor`; schema stays at version 1 | Stored `subnet` and `isOffSubnet` columns: stale whenever `maxPrefix` or the manual list changes, split groups, and need the project's first Room migration on the only real device. |
| Row and header text | Owned by the Radar spec (its 4.2, 4.3 and section 8) | Duplicating wording here: the two specs disagreed. |
| Persistence of ranges | Candidates are never stored, only hosts; gateway-probe hits are not remembered between runs | Caching probe results: stale, and hides segmentation changes, which is what the Director is testing. |

## 4. Candidate ranges

### 4.1 Common rules

A candidate is a `Subnet` (existing class in `netenv/Subnet.kt`). For every source:

- Only RFC1918 addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) qualify. Anything else is dropped silently.
- The candidate must not overlap the scanned local range (`SubnetMath.sweepRange(...).subnet`). Exclusion is by the range actually swept, not the interface's true subnet; see 11.2 for the clamped /16 case. This applies to manual entries too, and is re-checked at derivation time (4.5), not only when the entry is typed.
- Candidates are de-duplicated after normalising to network address and prefix.
- The final list is truncated to the cap after ordering.

### 4.2 Source (a): evidence-led

Any address the run sees outside the scanned local range that passes 4.1 contributes the /24 containing it. Evidence comes from:

- mDNS records (the engine already emits these for any resolved IP, including off-subnet),
- SSDP responses (`r.from`),
- neighbour table entries that fall inside the interface's true subnet (`SubnetMath.of(env.interfaceIp, env.prefixLength)`) but outside the swept range. This covers the clamped /16 case (11.2) and excludes hotspot and Wi-Fi Direct interfaces, whose addresses (for example `192.168.49.x`) are outside the true subnet. The JNI dump returns entries from every interface and carries no interface index, so the subnet test is the filter. The rare "second subnet on the same layer-2 segment" case is dropped; the manual list covers it. Entries are further filtered by the proxy-ARP rule in 6.2.
- RFC1918 DNS servers from `NetworkEnv.dnsServers` that sit outside the swept range. On client sites these are usually domain controllers on the server range, which is the range the assessment wants.

Evidence ranges are ordered by the number of distinct addresses seen in them (descending), then by address. Candidates are derived only after the `coroutineScope` holding the local sweep, neighbour dump, mDNS, SSDP and `deviceInfo` jobs has returned (all strategies joined), so evidence is complete and no resolve queue is still draining.

Evidence hosts (mDNS and SSDP sightings) are stored and shown whether or not the toggle is on (as today); the toggle only controls whether their /24 is then swept. Neighbour and DNS evidence only ever contributes ranges, never host rows.

### 4.3 Source (b): gateway probing

Active, and active only when both the master toggle and the gateway-probe toggle are on.

0. TEST-NET pre-check. Before any gateway probe, connect to `192.0.2.1` and `198.51.100.1` (RFC 5737 TEST-NET, never assigned) on port 80 and on one random high port (49152 to 65535, chosen per run). If any of these connects or is refused, the network answers for every address: skip the gateway probe, set the note `GATEWAY_PROBE_SKIPPED_ANSWERS_ALL`, and continue with manual and evidence candidates (each still subject to 4.7). The decision is a pure function (`AnswersForEverything.of(outcomes)`) fed by an engine call, so it is testable with fakes.
1. Take the phone's interface address. If it is not RFC1918, skip the /16 walk (the seed list in step 4 still runs).
2. Compute the enclosing /16: `10.a.0.0/16` for `10.a.x.y`, `172.b.0.0/16` for `172.b.x.y` (b in 16 to 31), `192.168.0.0/16` for `192.168.x.y`. For `10.x` the whole /8 is not walked; only the phone's own /16.
3. For each of the 256 /24s in that /16, except any overlapping the scanned local range, TCP-probe `.1` and `.254`, plus the phone's own gateway offset when it is neither (gateway `10.0.3.250` means `.250` is probed in every /24).
4. Seed list: also probe about 20 common gateway addresses in the other RFC1918 blocks, with the same rule, skipping any that lie inside the phone's own /16 (the walk already covers them): `10.0.0.1, 10.0.1.1, 10.1.1.1, 10.1.10.1, 10.10.0.1, 10.10.10.1, 10.0.0.254, 172.16.0.1, 172.16.1.1, 172.16.16.1, 172.17.0.1, 172.31.0.1, 192.168.0.1, 192.168.1.1, 192.168.1.254, 192.168.2.1, 192.168.10.1, 192.168.88.1, 192.168.100.1, 192.168.178.1`. This lets a guest Wi-Fi on `192.168.x` find server ranges on `10.x` or `172.16.x`. It costs under a second.
5. A /24 becomes a candidate when any probed address in it answers with a connect or `ECONNREFUSED` on a probe port. Timeouts and `EHOSTUNREACH` / `ENETUNREACH` mean no.
6. Probe ports, in order, stopping at the first definitive answer: 80 then 443. Two ports only: a gateway that firewalls both is almost always firewalled on 22 and 53 too, and every extra port multiplies the timeouts on dead addresses.
7. Per-connect timeout 400 ms. Concurrency 64 probes in flight, independent of the sweep concurrency setting, enforced by a `Semaphore` as in `Sweep` on a `limitedParallelism` view. Both values are named constants. Every connect goes through `Probe.connect` (not a new connect path) so the socket is closed on cancellation and Stop works, and it carries the bound `Network` (5.5).
8. Gateway-probe hits are ordered by address and come last under the cap. Each is still subject to 4.7 before being swept.

Probe budget and timing, stated honestly: almost every target is dead, and a dead target that is silently dropped costs both ports. An unrouted /24 inside the phone's /16 leaves via the default route and the ISP path silently drops it, so the typical run is close to the worst case. Worst case is 255 /24s x 3 addresses x 2 ports = 1,530 attempts, plus up to 40 for the seed list and 4 for the pre-check, about 1,600 in total (the figure of about 2,000 belongs to a four-port design that was rejected). With ports tried in sequence per target, 64 in flight and 400 ms each, that is about 13 s worst case, and typical is within a few seconds of it. These attempts also create NAT entries on the gateway and leave the site towards the ISP, which is why the probe is off by default. The probe runs after the neighbour dump and before the extended sweeps, and can be cancelled at any point.

The probe sends no payload and reads no banner: connect, observe the outcome, close.

### 4.4 Source (c): manual CIDR list

Entered in Settings, kept per network: key `extManual.<networkId>` in the existing SharedPreferences (newline-joined; no schema change). The editor works on the current network and shows its name; with no current network it is disabled. Validation is a pure function `CidrParser.parse(text): CidrResult`:

- Accepts `a.b.c.d/p`, with whitespace tolerated and one entry per line or comma separated. A bare address without a prefix is rejected ("Add a prefix, for example /24").
- Each octet 0 to 255 with no stray characters; prefix as digits only; no hostnames; no IPv6.
- The address is normalised to its network address (`10.0.4.20/24` is stored as `10.0.4.0/24`, with the inline note "Normalised to 10.0.4.0/24").
- Allowed prefix range /20 to /30. Larger than /20 is rejected ("Too large, use /20 or smaller"); a /20 is exactly 16 /24 units, the maximum cap. /31 and /32 are rejected ("Too small to sweep, use /30 or larger").
- Must be RFC1918 ("Only private ranges are allowed").
- A /20 to /23 entry expands into its /24 units, each counting one cap slot. A /25 to /30 entry is swept as given (one slot), never widened.
- An entry wholly inside the local scanned range is rejected inline ("This is your own network"). The same rule runs again at derivation time, so a list entered for a network and later overlapping a changed local range is dropped then, not swept.
- Exact repeats are collapsed on save with an inline note.

List order is the order used under the cap. Invalid strings on load are dropped.

### 4.5 Merging and the cap

Pure function (signature only):

```kotlin
object CandidateRanges {
    fun derive(
        scanned: Subnet,                 // the local range already swept
        manual: List<Subnet>,            // this network's validated list, in order
        evidence: List<Inet4Address>,    // off-range addresses seen this run (4.2)
        gatewayHits: List<Subnet>,       // /24s that answered the probe
        cap: Int,                        // coerced to 1..16
    ): CandidateResult
}
data class CandidateRange(val subnet: Subnet, val source: RangeSource)
enum class RangeSource { MANUAL, EVIDENCE, GATEWAY_PROBE }
data class CandidateResult(val ranges: List<CandidateRange>, val truncated: List<CandidateRange>) // truncated = dropped only by the cap
```

Algorithm: expand manual entries to units; append evidence /24s (ordered per 4.2); append gateway hits; walk the list and accept an item if it is RFC1918, does not overlap `scanned` (this includes manual items), and does not overlap an already accepted item; stop accepting at `cap`. An overlap always resolves in favour of the earlier (higher priority) item, so a manual `/28` inside an evidence `/24` keeps the `/28` and drops the `/24`. `truncated` lists valid items rejected only because the cap was full, so the engine can report their count and the done summary can list them.

### 4.6 Expected timings (Wi-Fi)

| Step | Typical | Worst |
|---|---|---|
| Local sweep (unchanged) | 10 to 20 s | as MVP |
| Candidate derivation | under 10 ms | under 10 ms |
| Gateway probe of a /16 | 10 to 13 s | about 13 s |
| One extra /24 sweep | 15 to 25 s | about 30 s |
| Default run (4 extra ranges, probe off) | local + about 1.5 min | local + about 2 min |
| Cap maximum (16 extra ranges) | local + 5 to 7 min | local + about 8 min |

A routed dead address may time out on all 12 liveness ports (7.2 s per host at 600 ms, 128 hosts in flight, so about two waves for a /24). Where the router replies with an ICMP unreachable the connect fails fast and the sweep is quicker. The per-range phantom checks (4.7) add one timeout per range. These are targets to be measured on the Pixel 7 Pro (12.4), not guarantees.

### 4.7 Phantom-range guard

The MVP liveness rule "connect or refused means alive" is safe on-link but not through a router. A REJECT firewall rule (ICMP port-unreachable or TCP reset, both of which surface as `ECONNREFUSED`) or a captive portal that redirects TCP 80, 443 and often 53 for every destination makes every address read as alive. Unguarded, each range would report 254 hosts, run 254 HTTP title fetches and write permanent rows. The guard has three layers, all applied to every extra range whatever its source:

1. Gateway pre-check: the TEST-NET check in 4.3 step 0.
2. Per-range canary: before sweeping a candidate, probe two addresses in it that are not already known in Room for that network (the lowest and highest such host addresses) on a random high port (49152 to 65535, chosen per run, not in any liveness or Quick list). If both answer (connect or refused), the range is marked `PHANTOM`, skipped, and nothing is stored. The canary is skipped for a range with fewer than four such addresses.
3. Sweep guard: if more than 90 percent of the first 64 probed addresses in an extra range come back alive, cancel the sweep, mark the range `PHANTOM`, and discard the hosts it produced. For this to be possible the engine holds each extra range's hosts: it buffers them per range and emits them only after the check passes at 64 probes (ranges with fewer than 64 hosts are buffered to the end, and the guard needs at least 16 probes to evaluate). Enrichment for held hosts runs only after release. Hosts already stored from mDNS or SSDP sightings are real evidence and are never discarded by this guard.

`PHANTOM` ranges appear in the last-run range list (5.6) so the Director sees "everything answers" as a finding, and they are never reported as swept with found hosts.

## 5. Engine changes

### 5.1 Options and status

```kotlin
data class ExtendedOptions(
    val enabled: Boolean = false,
    val maxRanges: Int = 4,                  // coerced to 1..16
    val gatewayProbe: Boolean = false,
    val manual: List<Subnet> = emptyList(),  // the current network's list (4.4)
)
data class DiscoveryOptions(/* existing fields */, val extended: ExtendedOptions = ExtendedOptions())

data class RangeProgress(
    val subnet: String, val source: RangeSource?,   // null = the local range
    val state: RangeState, val probed: Int, val total: Int, val found: Int,
)
enum class RangeState { PENDING, RUNNING, DONE, CANCELLED, PHANTOM, TRUNCATED }
enum class DiscoveryPhase { LOCAL, GATEWAY_PROBE, EXTENDED, DONE }
enum class ExtendedNote { GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, GATEWAY_PROBE_FOUND_NONE, NO_EXTRA_RANGES, SKIPPED_BIND_FAILED }
data class DiscoveryStatus(/* existing fields */, val phase: DiscoveryPhase = DiscoveryPhase.LOCAL,
                           val ranges: List<RangeProgress> = emptyList(), val truncatedRanges: Int = 0,
                           val notes: List<ExtendedNote> = emptyList())
```

The top-level `probed`, `total` and `found` keep their meaning and the existing monotonic `total = maxOf(t, it.total)` collector, so the current tests keep working. Per-range progress lives in `ranges[i].probed` and `ranges[i].total`, which are set per range and are not monotonic across ranges; a /28 after the local /24 reports 14, not 254. The UI progress line reads the active `RangeProgress` (section 8).

### 5.2 Flow

The existing `discover()` body is refactored so the sweep plus its enrichment is one function taking a `Subnet`, called once per range (local first, then each candidate). With the toggle off nothing changes.

```
discover(env, options)
  1. local range = sweepRange(...)                      [unchanged]
  2. start mDNS and SSDP concurrently                   [unchanged]
  3. sweep local range + enrichment                     [unchanged]
  4. neighbour dump: MACs for on-link hosts only (6.2)
  5. join: the coroutineScope of steps 2 to 4 returns; all strategies are finished
  6. if !extended.enabled: done
  7. bind check (5.5); if binding fails: note SKIPPED_BIND_FAILED, done
  8. optional gateway probe incl. TEST-NET pre-check    (phase GATEWAY_PROBE)
  9. CandidateRanges.derive(manual, evidence, probe hits)   [only now]
 10. for each candidate in order                        (phase EXTENDED)
       per-range canary (4.7); sweep with the same prober + enrichment, sweep guard (4.7);
       range is DONE only after its enrichment jobs finish
       new off-range evidence found during step 10 does not add ranges this run (bounded work)
 11. done
```

mDNS and SSDP run in parallel with step 3 only, bounded by their own windows as today. Hosts they reveal inside a swept extra range merge by IP.

New engine inputs, in the same function-typed style as the existing ones so tests can fake them:

```kotlin
val gatewayProbe: suspend (targets: GatewayTargets, timeoutMs: Int, concurrency: Int) -> List<Subnet>
val canaryProbe: suspend (ip: Inet4Address, port: Int) -> Outcome   // TEST-NET and per-range canaries
```

`GatewayTargets` carries the enclosing /16, the exclusion range, the phone's gateway offset and the seed list. Both run through `Probe.connect`.

`sweepFactory` is called once per range because `Sweep.progress` is per instance. The enrichment semaphore (32) is shared across ranges, so enrichment concurrency does not grow. Marking a range DONE only after enrichment means the next range's progress never starts while enrichment permits are still busy, and "Stop within a second" is measured against the right boundary.

### 5.3 Enrichment in extra ranges

NetBIOS, SNMP and HTTP title run for every live host exactly as for local hosts, subject to the same per-feature settings, with their sockets bound to the Wi-Fi network. Reverse DNS is skipped for extra-range hosts: the system resolver cannot be bound to a Network (getnameinfo takes no network id), so a PTR query would go out on the default network and a bypass-allowed VPN could attach foreign names to local hosts. A bound `DnsResolver.rawQuery` PTR lookup (API 29+) is a follow-up. Expect NetBIOS (UDP 137) to be filtered by most routers and SNMP to be selective; both fail softly through `softly {}` and cost up to one timeout (1 s) each per host. The HTTP title fetch is TCP and generally works through a router, so it is the most valuable enrichment off-subnet. `isGateway` is set only for the interface gateway, never for hosts in extra ranges (a candidate's `.1` is just a host; the identity rules will call it a router if the evidence supports it).

### 5.4 Cancellation

The whole thing is one coroutine scope under `discover()`. Stop, a network change, or the collector being cancelled cancels the gateway probe and the current range sweep through the existing structured-concurrency path; in-flight blocking connects are closed from the cancelling coroutine as in `HostProber` (the gateway probe uses the same `Probe.connect`, 4.3 step 7); the multicast and Wi-Fi locks are released by the ViewModel's existing `finally`. Unfinished ranges are marked `CANCELLED` in `RangeProgress`; partial results stay, except hosts still held by an unfinished phantom check, which are dropped.

### 5.5 Binding to the Wi-Fi network

`NetworkEnv` picks which network is described but does not route sockets. A VPN that routes RFC1918 (corporate VPN, Tailscale subnet routes, a WireGuard tunnel home) would carry routed probes to the far end, so on a client site the app could probe the Director's home or another client's network.

`Probe.connect` gains an optional parameter `network: Network? = null` (null in JVM tests and for the unchanged local sweep). For the gateway probe, the canaries and every extended sweep, the socket is bound with `network.bindSocket(socket)` before `connect`, where `network` is the Wi-Fi or Ethernet `Network` the env came from. The bind is tested once at step 7 of the flow on a throwaway socket, and any later bind failure ends the extended phase. If binding fails (for example a VPN that disallows bypass), the extended phase is skipped with note `SKIPPED_BIND_FAILED` ("Extended discovery skipped: a VPN is routing private ranges"). Partial results and the local run are unaffected.

### 5.6 Last-run range list

The ViewModel keeps the last run's `ranges` list (swept, empty with `found = 0`, `PHANTOM`, `TRUNCATED`, `CANCELLED`) and notes in memory, replaced at the start of the next extended run. Nothing is stored in Room or SharedPreferences. It feeds the done summary (section 8): for segmentation testing, "10.0.7.0/24 swept, nothing reachable" is a finding and must not vanish when the run ends. After process death the list is gone, as the Radar spec's summary detail already is.

## 6. Why MACs are limited off-subnet

### 6.1 The neighbour table

The kernel neighbour table maps IPs on the same layer-2 segment to MACs. For a host in another subnet the phone sends the packet to the router, so the only neighbour entry is for the router's address, carrying the router's MAC. There is never an entry for the remote host's own IP.

### 6.2 The rule

- The neighbour step assigns a MAC only to an IP that appears in the dump for itself, and only for IPs inside the interface's true subnet. A remote host that merely resolves via the gateway never receives the gateway's MAC.
- The gateway's MAC is never assigned to any IP other than the gateway's own.
- A MAC shared by more than two IPs is treated as proxy ARP (a router answering ARP for remote or unused addresses): no MAC is assigned from those entries and they do not count as evidence for 4.2.
- Neighbour entries count as evidence only when they fall inside the phone's true subnet and outside the swept range (4.2). Hosts inside the unswept part of a clamped /16 (11.2) are on-link and do appear in the dump.

### 6.3 Where MACs do come from in extra ranges

Only the secondary sources of the MVP spec section 4: UPnP UDN or USN (`MacSource.UPNP_UDN`), NetBIOS node-status (usually blocked through routers), and EUI-64 derivation from IPv6 link-local addresses learned over mDNS (`withDerivedMac`, which only works if an mDNS reflector passed the AAAA record). Many extra-range hosts will have no MAC and no OUI vendor. This is expected; the UI shows nothing for a missing MAC, as today. Identification then leans on names, UPnP, HTTP titles and port fingerprints, which the rules table already supports.

## 7. Data model

No schema change and no Room migration. `NetProbeDb` stays at version 1, `HostEntity` is unchanged, `upsertDiscovered` keeps its current signature, and annotations are untouched as in MVP spec 5.6. Off-subnet hosts discovered by a sweep live in the same network row, keyed by `(networkId, ip)`, which is safe because distinct RFC1918 addresses cannot collide within one network.

Grouping and off-subnet status are derived at display time from inputs the ViewModel already has (`NetworkEnv`, `maxPrefix`, this network's manual list, the last-run range list):

```kotlin
object RangeRules {
    // Returns (group label, isOffSubnet). scannedRanges[0] is the local scanned range; later items are ranges
    // swept in the last run. First match wins: local range -> (it, false); a swept or manual range containing ip
    // -> (that range, true); else (the /24 of ip, true). With no local range known the result is (/24 of ip, false).
    fun groupFor(ip: String, scannedRanges: List<Subnet>, manualRanges: List<Subnet>): Pair<String, Boolean>
}
```

`HostRow` loses `otherSubnet` and gains `subnet: String` and `offSubnet: Boolean`, both computed in `RowRules.row` (which already receives `env` and `maxPrefix`) through `RangeRules.groupFor`. `RowRules.otherSubnet` is removed. Because nothing is stored, a changed `maxPrefix` or manual list regroups immediately with no stale state. A manual range narrower than /24 (for example a /28) groups its hosts under that range; other hosts of the same /24 group under the /24. That is deliberate and visible.

## 8. UI

The Radar spec owns Home layout, group-header wording and styling (its sections 4.2 and 4.3), the scanning progress line (3.3), the done summary (3.4) and the copy table (section 8). This spec supplies only data and must not be read as specifying layout or strings for those. Where wording appears below it is Settings copy, which the Radar spec's section 6 only restyles.

### 8.1 Home list data

- The list is grouped by `HostRow.subnet` (section 7). Group order, header text, count placement, sticky behaviour and the accessibility heading are specified in Radar spec 4.2. `stickyHeader` is stable in the pinned Compose foundation 1.8.2 (2.3), so no opt-in is required there.
- Within a group the order is unchanged: gateway first, then by IP. The gateway can only be in the local group.
- Row secondary line follows Radar spec 4.3 (`rowDataLine`). Dimming of hosts not seen in the latest run is unchanged; off-subnet hosts from an earlier extended run appear dimmed when the toggle is off, which is accurate: they were not re-checked.
- Pinned controls stay outside the list per the standing rule; the Radar spec owns where Scan and Stop sit.

### 8.2 Progress and done data

The engine supplies `DiscoveryStatus.phase`, `ranges`, `truncatedRanges` and `notes` (5.1). The Radar progress line (Radar spec 3.3 and its copy table) is to use the agreed form for the extended sweep, "10.0.4.0/24 (2 of 4), probing 171 of 254", where "2 of 4" counts extra ranges only (the local sweep is not one of the 4) and "171 of 254" is the active range's own `probed` and `total`. The Radar spec's present strings for the extended sweep and gateway phase (its 3.3 and copy table) predate this and are for its owner to update; this spec does not restate them.

The done summary (Radar spec 3.4) is to draw on the last-run range list (5.6): a count of other ranges checked, expandable to one line per range with its source, state (swept, empty, `PHANTOM`, `TRUNCATED`, `CANCELLED`) and found count. Wording, expansion behaviour and the strings for the cap and for each `ExtendedNote` are Radar spec concerns (its 4.4 covers transient notes). Ranges are not shown as empty headers in the device list.

### 8.3 Settings: "Extended discovery" section

In this order:

1. Switch "Extended discovery" with supporting text "Sweep other ranges your phone can reach." Off by default, global. The remaining rows are disabled (visibly, not hidden) while it is off.
2. Stepper "Maximum extra ranges", 1 to 16, default 4, with a live line "At most N extra /24 networks (about M minutes)" using 16 s per range (measured on the Director's LAN, Task 20: 13.7 to 16.2 s per /24).
3. Switch "Probe for gateways" with supporting text "Checks common gateway addresses on every /24 in your /16 (about 1,600 connection attempts, about 13 s). Noisy on networks you do not own." Off by default, global.
4. "Manual ranges for <network name>" editor: the current network's list of CIDRs, each with a delete control, and an "Add range" text field with inline validation using the messages in 4.4. Invalid text shows its error under the field and Add stays disabled; normalisation notes show as helper text. Entries beyond the cap are marked "Beyond cap, will be skipped". With no current network the editor is disabled with "Connect to a network to edit its ranges".

Persistence follows the existing `Settings.kt` (SharedPreferences): keys `extEnabled`, `extMax` (coerced 1..16), `extGatewayProbe`, and `extManual.<networkId>` (newline-joined; invalid strings on load are dropped).

## 9. Data flow

1. Tap Scan: as MVP steps 1 and 2 (resolve the network row, build options). The options carry `ExtendedOptions` from Settings, with `manual` read from `extManual.<networkId>` for the resolved network.
2. Local discovery runs; each `DiscoveredHost` is upserted as today. Off-subnet hosts seen by mDNS or SSDP are stored immediately, toggle on or off.
3. If the toggle is on, after all strategies have joined the engine optionally gateway-probes, derives candidates and sweeps each in turn through the phantom guard. Every host that passes is upserted into the same network row.
4. Room flows push rows to Home; `RowRules.row` computes `subnet` with `RangeRules.groupFor`, and grouping happens in the `HomeViewModel` combine after the filter, so headers react to filter and search.
5. Stop, a network change or completion ends the run as today. The activity window holds `FLAG_KEEP_SCREEN_ON` while an extended run is active (section 10).

## 10. Safety and scope

- Extended behaviour of any kind (gateway probe, candidate sweeps) runs only when the master toggle is on. Storing an off-subnet host that mDNS or SSDP happened to reveal is not an extended sweep and keeps today's behaviour.
- The cap bounds the work: at most 16 extra /24s (about 4,000 host probes before the per-host port fan-out), and the gateway probe is about 1,600 attempts at most. Defaults are 4 ranges and no probing.
- Stop cancels the probe, the current sweep and enrichment together; locks are released by the existing `finally`.
- A network change cancels the whole run as today. Candidate lists exist only inside the engine call and are never carried across networks, and the manual list is keyed to its own network so it is never swept on another.
- Cellular is never swept: the existing `NetworkEnv` rule (Wi-Fi or Ethernet only) applies before discovery starts, and extended probes are bound to that network (5.5), so neither cellular nor a VPN carries them. The phone's own address is skipped in extra sweeps as in the local one.
- Phantom ranges are never stored (4.7).
- Screen: the scan job lives in `viewModelScope` with no foreground service, and a default extended run (1.5 to 2 minutes) outlasts a typical screen timeout. The activity sets `FLAG_KEEP_SCREEN_ON` on its window while an extended run is active (no permission, no new component) and clears it when the run ends or is stopped.
- Traffic is connect probes plus the existing enrichment fetches; no new payloads, and nothing goes to the internet.

## 11. Error handling and edge cases

- 11.1 Overlapping manual ranges: collapsed per 4.4 on save and per 4.5 on derive; the earlier item wins; no error, a helper note on save.
- 11.2 A /16 that is the local subnet (the phone is on a /16 and `maxPrefix` is 22, so the local sweep is clamped to the /24): the true subnet is the /16, the swept range is the /24. Candidate exclusion is by the swept range only, so the other 255 /24s of the /16 are eligible for evidence, gateway probing and manual entry. They are on-link, so the neighbour table can supply their MACs and counts as evidence for them (6.2). `sweepRange` clamps to the /24 whenever the prefix is below `maxPrefix`, and `maxPrefix` is coerced to 20 to 24, so widening `maxPrefix` never sweeps more of a /16; it only changes the swept range for /20 and /21 networks.
- 11.3 IPv6: AAAA records, IPv6 SSDP and IPv6 neighbour entries never produce candidates; `Inet4Address` is required at every step, as in the engine today.
- 11.4 Cellular: never swept (section 10). With no usable LAN, Scan is disabled as today.
- 11.5 Toggle on and an empty candidate list (no evidence, probe off, no manual): the run completes after the local sweep with note `NO_EXTRA_RANGES`.
- 11.6 Gateway probe finds nothing: note `GATEWAY_PROBE_FOUND_NONE`, and the run continues with manual and evidence candidates.
- 11.7 Manual entries beyond the cap are skipped and listed as `TRUNCATED`; manual order is preserved so the Director controls which survive.
- 11.8 "Quick scan all" iterates the stored hosts of the network, which includes off-subnet rows. This is consistent with "scan everything I know" but can be long with many extended hosts; Stop still works. Because phantom ranges are never stored (4.7), it cannot port-scan invented hosts. A "local only" option is open item 13.4. No dialog or prompt is added.
- 11.9 A swept range that returns no hosts: nothing is stored; `RangeProgress` records `found = 0`, the range appears in the last-run list as swept and empty (5.6), and the run moves on.
- 11.10 Per-probe exceptions are counted and swallowed inside the engine task, never propagating (MVP spec 8). A failing gateway probe ends that step only (`isolated {}`), leaving evidence and manual ranges to proceed.
- 11.11 DHCP renumbering: the `(networkId, ip)` key is per IP, so a changed IP is a new row (as today). Because grouping is derived at display time, a changed `maxPrefix` or manual list regroups existing rows with no rewrite.
- 11.12 Long runs: a 16-range run takes minutes; progress and Stop stay pinned, the screen stays on (section 10), and a network change mid-run keeps partial results with "Network changed, scan stopped".
- 11.13 Everything answers: a captive portal or REJECT-ruled network produces `PHANTOM` ranges and a skipped gateway probe, never stored rows (4.7).
- 11.14 VPN: if the extended probes cannot be bound to the Wi-Fi network, the extended phase is skipped with `SKIPPED_BIND_FAILED` and the local result stands (5.5).

## 12. Testing and verification

### 12.1 Unit tests (JVM)

- `CandidateRanges.derive`: local range excluded (including a manual entry overlapping it); non-RFC1918 and link-local dropped; /24 normalisation of evidence addresses; evidence ordering by count then address; de-duplication across sources; overlap resolved to the earlier source (manual /28 kept over evidence /24); ordering manual, evidence, probe; cap boundaries 1, 4 and 16 and a request above 16 coerced; `truncated` lists only cap drops; empty inputs give empty output; the clamped /16 case (the other /24s of the real /16 stay eligible).
- `CidrParser`: valid forms; whitespace, comma and newline separation; host-bit normalisation; every rejection message (bad octet, bad prefix, non-numeric, missing prefix, larger than /20, smaller than /30, non-RFC1918, IPv6, hostnames, own network); expansion of /20 to /23 into /24 units and /25 to /30 kept as given.
- Manual list keying: lists for two network ids stay separate; no current network yields an empty, disabled editor state; an entry overlapping a changed local range is dropped at derive time.
- Enclosing /16 derivation for 10.x, 172.16 to 172.31 and 192.168; non-RFC1918 and 172.15 / 172.32 skipped.
- Gateway targets: the phone's gateway offset is added when it is not `.1` or `.254`; the seed list excludes addresses inside the phone's /16; ports are 80 then 443 only.
- DNS-server evidence: RFC1918 DNS servers outside the swept range count; those inside it or non-RFC1918 do not.
- `AnswersForEverything.of` for TEST-NET outcomes.
- `RangeRules.groupFor`: local range, a swept extra, a manual range (including a /28 inside a /24), unswept off-subnet /24 fallback, and no local range known.
- Neighbour rules: an entry outside the true subnet is not evidence (hotspot or p2p range); an entry inside the true subnet and outside the swept range is evidence; the gateway's MAC is never assigned to another IP; a MAC shared by more than two IPs is ignored for evidence and MAC assignment.
- Progress and range-state helpers: per-range `probed` and `total`, monotonic top-level `total`, last-run list contents.

### 12.2 Engine tests with fakes

Using the existing function-typed constructor parameters and a fake prober:

- Toggle off: no gateway probe call, no extra sweeps, status identical to today (regression guard).
- Toggle on, manual range only: exactly that range is swept after the local one, hosts are emitted, enrichment runs for them, `isGateway` is false, and MAC is absent unless UPnP or NetBIOS supplies it.
- Evidence-led: a fake mDNS record for `10.0.4.20` causes `10.0.4.0/24` to be swept; the record's host merges with the sweep result by IP. A fake DNS server outside the range does the same.
- Candidate timing: derivation runs only after all strategies have joined (a slow fake mDNS resolve still contributes its range).
- Gateway probe: the fake receives the right targets; hits are swept after manual and evidence; with the probe toggle off it is never called.
- Refuse-everything prober (every address `Refused`): gateway probe skipped with `GATEWAY_PROBE_SKIPPED_ANSWERS_ALL`, every candidate range `PHANTOM`, zero hosts emitted or stored.
- Accept-everything prober (every address `Connected(80)`, a captive portal): the same result, plus the sweep guard discards a range that slips past the canary after 64 probes, and no enrichment runs for held hosts.
- Phantom guard does not discard hosts already emitted from mDNS or SSDP sightings.
- Cap: more candidates than the cap sweeps exactly `cap` ranges in the specified order, reports `truncatedRanges`, and marks the rest `TRUNCATED`.
- Binding: with a fake whose bind fails, the extended phase is skipped with `SKIPPED_BIND_FAILED` and the local result is intact.
- Neighbour dump: an off-link IP gets no MAC from the dump; an entry in the true subnet outside the swept range yields evidence and a MAC.
- Status: `ranges` states move PENDING, RUNNING, DONE in order, with DONE only after enrichment finishes; `phase` transitions; per-range `probed` and `total` are per range while top-level `total` stays monotonic.
- Cancellation: cancelling mid-extended-sweep or mid-probe stops promptly (under 1 s with fakes, and the gateway probe goes through `Probe.connect` so its sockets close) and marks the range `CANCELLED`.
- A throwing gateway probe or enrichment step does not stop later ranges.

### 12.3 Instrumented tests

- DAO and repository: the existing round trip still passes unchanged (no schema change).
- Compose: the grouped Home list renders sticky headers, local first; filters hide empty groups; the Settings Extended section rejects a bad CIDR inline, persists a good one for the current network only, and disables the manual editor with no network.
- Pinned controls stay put while the list scrolls (the Radar spec's tests 10.2 already cover this and are reused).

### 12.4 On-device checks on the Director's LAN (Pixel 7 Pro, mandatory)

1. Toggle off: run a scan; result and timing match the pre-change baseline; any off-subnet host revealed by mDNS (for example the Hue Bridge) appears under its own group with no sweep.
2. Toggle on, probe off, no manual ranges: re-scan. The Hue Bridge at `10.0.4.20` is found by evidence-led expansion, `10.0.4.0/24` is swept, and its other devices appear under that group. Record time and host count.
3. Toggle on, probe on: `10.0.4.0/24` is found by gateway probing (the phone is in `10.0.3.0/24`, so the enclosing `10.0.0.0/16` contains it). Record probe duration, attempt count and the hit list.
4. Manual range: add `10.0.4.0/24` for this network, exercise the inline messages with bad input, and check manual ranges sweep first. Switch to another network (or none) and check the list is not shown or swept there.
5. Cap: set the cap to 1 with several candidates; only the first (manual) range is swept and the rest appear as `TRUNCATED` in the done summary.
6. Stop mid-extended-sweep: the scan ends within about a second, partial hosts remain, and no Wi-Fi lock is left held (`dumpsys wifi`).
7. Switch Wi-Fi networks mid-run: the run cancels and no candidate state carries over.
8. Untouched phone: start a 4-range run, put the phone down without touching it, and check the run completes (the screen stayed on).
9. Phantom check: run on a captive-portal or REJECT-ruled network if one is available (for example a guest network); otherwise point a manual range at a /24 the UniFi rejects. Expected: the range shows `PHANTOM`, no rows are stored.
10. VPN: if the Director uses a VPN that routes private ranges, start it, run an extended scan, and check that probes do not reach the far end (extended phase skipped or bound to Wi-Fi).
11. Record per-range sweep times and the gateway probe duration against the targets in 4.6; correct the Settings time estimates if off by more than 50 percent.

Definition of done: all tests in 12.1 to 12.3 pass, the on-device checks are recorded with results, and the Director has checked that the grouped list and found ranges match his network.

## 13. Open items for the Director

1. Gateway probing default. Recommended off: about 1,600 connection attempts across a /16 look like a scan to a client IDS and are noisy on networks you do not own. Choose off, or on-by-default whenever the master toggle is on.
2. Master toggle default and persistence. Recommended off and remembered across launches. The alternative is off at every launch, as a safety posture for client sites.
3. Default cap 4 and maximum 16: right numbers? Sixteen ranges is 5 to 8 minutes.
4. "Quick scan all" with extended hosts present: include them (current plan, no prompt) or add a "local only" option?
5. Whether the manual list should allow /8 to /19 entries (rejected below /20 so each entry fits the cap).
6. Whether a swept-but-empty range should also show an empty group header in the list. Recommended no: the done summary's last-run range list (5.6) already records it.
7. Non-RFC1918 internal ranges (CGNAT `100.64.0.0/10`, or a client using public space internally) are never candidates. A manual override can be added later if needed.

Closed: the former item on the phone's range for on-device gateway check 3 is resolved. The Director's phone is in `10.0.3.0/24`, whose enclosing `10.0.0.0/16` contains `10.0.4.0/24`.
