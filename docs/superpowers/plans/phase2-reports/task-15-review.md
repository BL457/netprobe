# Task 15 review: extended phase in DiscoveryEngine (25de21d..1de0df7)

Reviewer: Bugsy (QA). Commit 1de0df7 on `phase2`.

## Verdicts

- **Spec compliance: ✅ Compliant.** The engine and test file match the brief's Step 3 and Step 1 code exactly (I diffed both against the brief: the only differences are Ada's added test and its `BindFailedException` import). Every controller ruling is applied.
- **Task quality: Needs fixes (one Important item, I1: reverse DNS for extra-range hosts).** Everything else is approved. The fix is a one-line guard plus a spec note. Two Minor items are worth folding in while the file is open.

## How this was verified (runtime, not just reading)

1. Full suite with `--rerun` (the first run was UP-TO-DATE, so I forced a real run): 351 tests, 0 failures, 0 errors. `DiscoveryEngineExtendedTest` 20/20, `DiscoveryEngineTest` 20/20, unchanged by the diff.
2. Mutation testing in a scratch clone (`--offline`, repo untouched), 13 mutants against the two engine suites:

| Mutant | Killed by |
|---|---|
| M1 `isolated` no longer rethrows `BindFailedException` | **survived** (see m1) |
| M2 sample counted in completion order, not address order | busy real range test |
| M3 `finally` no longer marks RUNNING/PENDING as CANCELLED | stop test, bind-mid-phase test |
| M4 TEST-NET pre-check result ignored | refuse-everything test |
| M5 extra-range enrichment launched in the outer channelFlow scope | ranges-move test, bind-mid-phase test |
| M6 no holding (release every live host at once) | accept-everything, stop, mDNS-never-discarded |
| M7 canary ignores known addresses | canaries test |
| M8 ranges swept without `BoundNetwork` | manual-range test |
| M9 `softly` no longer rethrows `BindFailedException` (Ada's check) | bind-mid-phase test (confirmed) |
| M10 `sampleSize = SAMPLE` (no `minOf`) | survived: equivalent, the post-loop fallback judges small ranges at the end |
| M11 PHANTOM keeps `released.get()` as found | survived: equivalent, nothing is released before a PHANTOM verdict |
| M12 pre-check runs with the gateway toggle off | 4 tests |
| M13 no up-front bind check | bind-failure test |

3. Eight extra probe tests (scratch only, not committed), all passing on 1de0df7:
   - reverse DNS for `10.0.4.7` (extra range) runs with `currentBinder() != null` in context, and for `10.0.3.5` (local) with null. This confirms the I1 guard works as one line.
   - a gateway probe that throws `BindFailedException` gives notes `[SKIPPED_BIND_FAILED]`, no extra sweep, phase DONE. This closes M1's gap in behaviour, not in the committed suite.
   - an all-alive /26 is PHANTOM with no hosts emitted. A /27 that is half alive is DONE with 15 hosts.
   - /24 boundary: 58 of the first 64 alive gives PHANTOM, 57 of 64 gives DONE with found 57.
   - a sample address whose probe threw (no result): the sample never completes, the post-loop judges 63/63, the range is PHANTOM and nothing is emitted.
   - Stop while the gateway probe is suspended: `cancelAndJoin` within 1 s, phase DONE, ranges `[DONE]`, no extra sweep.
   - Stop during the local sweep: range 0 becomes CANCELLED, no notes, and the extended phase never starts.
   - the same /24 named by manual, mDNS evidence and the gateway probe, plus a manual and a probe hit equal to the local /24: swept once, source MANUAL, and the local range is never re-swept.

## Spec compliance, per requirement

| Requirement | |
|---|---|
| Constructor: eleven MVP params unchanged, plus `gatewayProbe` (three-arg progress, binder), `canaryProbe` (binder), `random`, all defaulted | ✅ |
| `discover(env, options, binder: SocketBinder? = null)`, `status`, `production`, `CANARY_TIMEOUT_MS = 600` | ✅ |
| Gateway emitted first; mDNS, SSDP, local sweep, enrichment and neighbour dump in one `coroutineScope` | ✅ |
| Toggle off: nothing else happens (no bind check, no probe, no canary) | ✅ |
| Bind check on a throwaway socket on IO; a null binder or a failing bind gives `SKIPPED_BIND_FAILED` | ✅ |
| Mid-phase `BindFailedException` gives `SKIPPED_BIND_FAILED`, the current and later ranges become CANCELLED, released hosts stay, local result unchanged | ✅ |
| DNS evidence; mDNS and SSDP sightings outside the local range recorded as evidence | ✅ (neighbour evidence is Task 18, accepted) |
| TEST-NET pre-check (2 addresses x port 80 + random high port) before the gateway probe, only when the probe toggle is on | ✅ |
| `GATEWAY_PROBE_SKIPPED_ANSWERS_ALL`, `GATEWAY_PROBE_FOUND_NONE` (only when the probe ran), `NO_EXTRA_RANGES` | ✅ |
| `CandidateRanges.derive` only after all local strategies have joined; cap coerced 1..16; truncated reported | ✅ |
| Ranges sequential inside one `withContext(BoundNetwork(b))` | ✅ |
| Canary on lowest/highest unknown addresses, random high port, skipped below 4 unknown | ✅ |
| Sweep guard judges the first 64 addresses in address order; every live host held until the verdict; PHANTOM stops the sweep and discards held hosts; ranges under 64 judged at the end; min 16 probes | ✅ |
| Enrichment for held hosts only after release; DONE only after the range's enrichment jobs finish | ✅ |
| Extra-range hosts: reverse DNS, NetBIOS, SNMP, HTTP title only; NetBIOS/SNMP/HTTP bound through `currentBinder()`; TlsPeek, DeviceInfoTxt and UpnpDescription never reached from `sweepExtra` | ✅ |
| `isGateway` never set for extra-range hosts; no MAC from the sweep | ✅ |
| Per-range `RangeProgress` (probed/total per range); top-level probed/total stay local; phase LOCAL, GATEWAY_PROBE, EXTENDED, DONE | ✅ |
| `finally`: RUNNING/PENDING become CANCELLED, phase becomes DONE, on every exit path | ✅ |
| Gateway progress `GatewayProbeProgress(label, done, total, attempts)` written via `update` | ✅ |
| Production wiring: gateway probe and canary through `Probe.connect(binder = b)`, canary on `Dispatchers.IO` | ✅ |
| `DiscoveryEngineTest` unchanged and passing | ✅ |

## Focus area findings

**1. MVP local sweep unchanged: ✅.** I traced it line by line against 25de21d.
- Merge-by-IP and MacSource ranks still live in `DiscoveredHost.merge` and `withDerivedMac`, which the diff does not touch.
- `isolated` and `softly` keep their old behaviour for the local phase. The new `BindFailedException` rethrow cannot fire there: no `BoundNetwork` is in context and the binder argument is not passed, so `Probe.connect`, `NetBios.query`, `Snmp.query` and `HttpTitleFetcher` all see a null binder.
- Reverse DNS is still the detached, bounded `ReverseDns` scope.
- Progress is still updated by the same collector, the conflated-flush runs before `statusJob.cancel()`, and the neighbour dump still runs on IO.
- `enrichHost` is the old inline block moved into a function, with the same semaphore.
- The only additions are `noteEvidence` calls (a cheap synchronized set insert), the range-0 status writes and the outer `try/finally`. `found.size` is now also read under the lock, which is a small improvement.

**2. Cancellation: ✅.**
- The range `coroutineScope` owns the sweep and every enrichment `launch`, so Stop cancels both.
- `PhantomStop` is thrown from the collector, inside that scope, before anything is released, so there are no orphan children.
- The `finally` is non-suspending, so it runs under cancellation.
- Stop during the gateway probe and during the local sweep both complete within 1 s (probe tests above).
- See m3 for a pre-existing blocking-enricher delay on device.

**3. Bind canary and SKIPPED_BIND_FAILED: ✅.** I audited every catch site on the extended path:
- `bindWorks` catches everything by design.
- The canary and pre-check go through `softly`, which rethrows.
- The gateway probe goes through `isolated`, which rethrows. `GatewayProbe.run` and `answers` have no catch.
- `Sweep.runAll` rethrows. `HostProber` has no catch. `Probe.connect` binds before its `try`.
- `NetBios.query` and `Snmp.query` rethrow.
- `HttpTitleFetcher.fetch` swallows everything. That is harmless: `Network.openConnection` binds or fails and never falls back to the default route, so a failure there simply ends that title fetch.

So the `softly` and `isolated` rethrows are the only engine-side sites needed. Ada's added test is real: M9 kills it. The `isolated` rethrow has no committed test (m1).

**4. Phantom sample: ✅.** The sample is the first 64 entries of the phone-excluded, address-ordered list. Membership is decided by index, not by arrival order (M2 is killed by the busy-range test). PHANTOM discards the held list and reports found 0. The 64 boundary (58/64 PHANTOM, 57/64 DONE), the under-64 cases (/26, /27, /28) and the "a sample probe threw" fallback all behave as spec 4.7 and the Task 14 full-result contract require.

**5. Evidence and candidate order: ✅.** Order of steps: bind check, then DNS evidence, then (toggle on) phase GATEWAY_PROBE, pre-check and probe, then derive. Evidence order inside `derive` sorts by count then address, so arrival order in the `LinkedHashSet` does not matter. Cap and max are applied in `derive`. Overlaps with the local range are dropped, which covers manual entries and probe hits. Duplicates across sources collapse to the earliest source (probe test above).

**6. Reverse DNS: see I1.**

**7. Tests: ✅, with m2.**
- No real sockets: `OkBinder.openConnection` is never called because `httpTitle` is faked.
- The 20 cases cover the brief's 19 plus ruling 2. No tautologies; every mutant that could change behaviour was killed except M1.
- Real-time waits are coroutine `delay`s under `runBlocking` plus the brief's polling loop. There are no `Thread.sleep` calls.

**8. Hygiene: ✅.**
- No em-dashes in either file, no new dependencies, no build changes.
- No main-thread blocking: everything that blocks is on IO or the sweep's `limitedParallelism`. `derive` and list building are trivial.
- Names and signatures match what Tasks 16, 18 and 20 consume: `discover(env, options, binder)`, `DiscoveryStatus.phase/ranges/truncatedRanges/notes/gatewayProbe`, `RangeState`, `ExtendedNote`, and `noteEvidence`, `emit`, `sweepExtra` and `release` as Task 18's anchors.

## Findings

### Critical
None.

### Important

**I1. Reverse DNS for extra-range hosts uses the process default network, not the bound Wi-Fi network.** Location: `DiscoveryEngine.kt:95`, which goes into `ReverseDns.kt` (`canonicalHostName`).

- **Failure scenario.** The Director is at a client site with a split-tunnel VPN that allows bypass and routes RFC1918 (for example Tailscale subnet routes or WireGuard home). The bind check passes and the sweeps are correctly bound to Wi-Fi. But PTR lookups for each live `10.0.4.x` go to the VPN's resolver, which is the threat model spec 5.5 exists for. The far side's DNS can answer with names from the Director's home or another client's network. Those names are then attached to this site's hosts and persisted in Room, which is cross-site data contamination as well as a query leak.
- **Second scenario.** Wi-Fi with no internet validation, so cellular is the default network. PTR queries for private addresses go to the carrier's DNS.
- **Why this is not a spec deviation.** The brief and spec 5.3 say reverse DNS runs for extra-range hosts "exactly as for local hosts", so this is a gap in the spec, not in Ada's implementation.

**Recommendation:**

1. **Binding is not a one-liner.** `Network.getByName` and `getAllByName` cannot bind a PTR lookup. On an IP literal they just parse the address. The resulting `InetAddress.getCanonicalHostName()` goes through libcore `Inet6AddressImpl.getHostByAddr0`, which calls `Libcore.os.getnameinfo(addr, NI_NAMEREQD)` with no netId (checked in AOSP libcore). Only forward lookups (`android_getaddrinfo`) take a netId.
2. **Skipping is a one-liner, and that is what I recommend now.** At line 95, use `if (options.reverseDns && currentBinder() == null)`. The probe test above proves `currentBinder()` is non-null exactly for extra-range hosts. Record the deviation in spec 5.3: "reverse DNS skipped off-subnet: system PTR resolution cannot be bound to the Wi-Fi network".
3. **Optional follow-up, not in this task.** A network-bound PTR via `android.net.DnsResolver.rawQuery(network, "<reversed>.in-addr.arpa", TYPE_PTR, ...)`, available from API 29 (minSdk is 26, so skip below 29), plus a small answer parser. That would also fix the same pre-existing exposure for the local MVP range.
4. **Test.** Add one engine test that asserts the `reverseDns` fake is not called for a `10.0.4.x` host.

### Minor

**m1. No committed test covers the `isolated` BindFailedException rethrow (mutant M1 survives).** Location: `DiscoveryEngine.kt:39`, at the gateway probe call on line 173. Failure scenario: someone later simplifies `isolated`. A gateway probe that hits a bind failure (VPN switched on between the bind check and the probe) is then swallowed. With no manual or evidence candidates, the run reports `NO_EXTRA_RANGES` instead of `SKIPPED_BIND_FAILED`, and no test fails. Fix: in `engine()`, let `gatewayThrows` take a throwable and add a case asserting `[SKIPPED_BIND_FAILED]` with no extra sweep. My scratch test of exactly this passes on 1de0df7.

**m2. The busy-range test takes 6.9 s of wall-clock time.** Location: `DiscoveryEngineExtendedTest.kt:141`. The `delayFor` of 1 ms for each of the 194 dead hosts runs as real-time `delay` under `runBlocking`, and Windows timer granularity stretches it. The `completionOrder` argument alone already puts live hosts first, so `delayFor` can be dropped and the test still kills M2. This is brief text, not Ada's.

**m3. On device, Stop may lag by up to about 4 s while an HTTP title fetch is in flight for a slow routed host.** Location: `DiscoveryEngine.kt:99` into `HttpTitle.kt:69` (connect and read timeouts of 2000 ms each); NetBIOS and SNMP have a 1 s receive. `runInterruptible` cannot unblock `HttpURLConnection` or `DatagramSocket.receive` on Android, and the range `coroutineScope` waits for those children. The UI flips at once (`ScanController.stop`), but the `finally` (CANCELLED states) and the ViewModel's lock release lag behind. The pattern is pre-existing from the MVP; routed hosts make it more likely. The FakeSweep stop test cannot see it, so Task 20 check 6 must time the `dumpsys wifi` lock release, not just the button.

**m4. Two random high ports per run instead of one.** Location: `DiscoveryEngine.kt:164` and `:186`. Spec 4.3 says "one random high port ... chosen per run", and the `RandomHighPort` KDoc says one port for the pre-check and the canaries. With the probe on, the pre-check and the canaries use different ports. This is harmless; the fix is to pick once at the top of `extendedPhase` and pass it to both.

**m5. The "other ranges" label breaks Task 16's copy template.** Location: `DiscoveryEngine.kt:171` and `:268`. When the interface address is not RFC1918 (for example CGNAT `100.64.x`), the gateway probe still walks the seed list. The progress line then reads "probing other ranges for gateways, 3 of 20  5 s", which does not match Task 16's template `probing \S+ for gateways, ...` because of the space. CopyTableTest will not catch it because it only enumerates fixtures with "10.0.0.0/16". Fix: either one-token copy or a Task 16 template change.

Not findings (checked and accepted): top-level `found` counts extra-range hosts too. That matches the MVP meaning ("distinct hosts emitted") and the radar count.

## Plan-text conflicts

1. **Task 18 test count.** Task 18 Step 4 (`task-18-brief.md:164`) expects "`DiscoveryEngineExtendedTest` 21 passed". With Ada's added ruling-2 test it will be 22. Update the brief, or the Task 18 implementer will think a test is missing.
2. **Task 16 template vs "other ranges".** Task 16's copy template `probing \S+ for gateways` versus the engine's "other ranges" label (m5).
3. **Spec 5.3 reverse DNS.** Spec 5.3 ("Reverse DNS ... run for every live host exactly as for local hosts") conflicts in intent with 5.5 (all extended-phase traffic bound to Wi-Fi). It needs a recorded decision (I1).
4. **Brief Step 4 test count.** Brief Step 4 says 19 passed; actual is 20 (ruling-2 test). This is expected and documented in the report.

## Re-review, round 1 (1de0df7..ccd21d9)

**Verdict: Approved. I1 is fixed; there are no new issues.**

How I verified it:
- **Full JVM suite at ccd21d9 (`--rerun`):** 352 tests, 0 failures. `DiscoveryEngineExtendedTest` 21/21. `DiscoveryEngineTest` 20/20 and unchanged.
- **Mutation testing** in a scratch clone of ccd21d9, repo untouched:

| Mutant | Result |
|---|---|
| R1: guard removed (`if (options.reverseDns)`) | Killed by the new reverse DNS test only. It is not a tautology: it fails exactly when the unbound lookup runs for 10.0.4.7, and its netbios control proves 10.0.4.7 was released and enriched. |
| R2: guard inverted (`!= null`) | Killed by the new test and by 4 `DiscoveryEngineTest` cases. The local sweep's reverse DNS is still pinned by the MVP suite. |
| R3: sample by completion order | Still killed by the busy-range test under `runTest`. |
| R4: canaries on port 80 instead of `highPort` | Killed by the canaries test. |

Checks:
- **I1 fix (`DiscoveryEngine.kt:97`).** `currentBinder()` is non-null only inside `withContext(BoundNetwork(b))`, which is only the extra ranges. Local hosts take the same path as before. Spec 5.3 now records the skip, the reason (getnameinfo takes no network id) and the `DnsResolver.rawQuery` follow-up.
- **Single port (`DiscoveryEngine.kt:162`).** It is drawn once, after the bind check, before evidence. The pre-check still uses port 80 plus the high port on both TEST-NET addresses, and every canary uses the same high port, as spec 4.3 and 4.7 require. A bind-check failure still returns before any draw. The outcomes and the `AnswersForEverything` / `canaryPhantom` decisions are unchanged.
- **Virtual-time test.** It keeps the same `completionOrder` and the same `delayFor` values; only the clock is virtual (`kotlinx-coroutines-test` was already a `testImplementation`, so there is no new dependency). The engine's IO hops (`bindWorks`) still run on real IO, which `runTest` tolerates. Runtime went from 6.9 s to 0.07 s.
- **Hygiene.** No em-dashes, and no edits outside the three files.
