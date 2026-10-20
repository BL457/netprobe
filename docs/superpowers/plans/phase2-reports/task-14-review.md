# Task 14 review (ea934c6..25de21d)

## Spec compliance
- ✅ ExtendedOptions defaults: enabled=false, maxRanges=4, gatewayProbe=false, manual empty, knownIps empty; DiscoveryOptions.extended default. Max 16 is enforced by CandidateRanges (MIN_CAP/MAX_CAP coerceIn), comment accurate.
- ✅ Status types (RangeProgress, RangeState, DiscoveryPhase, ExtendedNote, GatewayProbeProgress with attempts=0, DiscoveryStatus extra fields incl. truncatedRanges, notes, gatewayProbe) match brief names/signatures exactly. Per-range outcome, 3-arg gateway progress, skipped reasons (GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, SKIPPED_BIND_FAILED), truncated-by-cap (TRUNCATED + truncatedRanges) all carried.
- ✅ PhantomGuard constants (64/16/0.9/4) and decide/canaryPhantom match brief. Thresholds: >90% strict; 58/64 PHANTOM, 57/64 RELEASE. I checked double `alive > 0.9*probed` against exact integer `alive*10 > 9*probed` for every probed 16..64 and alive 0..probed: no mismatch (so exactly-at-threshold, e.g. 18/20, is RELEASE).
- ✅ Range <64: HOLD until finished, then judged; <16 probes always RELEASE; 30/30 finished PHANTOM; 14/14 RELEASE.
- ✅ Two-stage (sample then full sweep): decide is count-based and stateless; the address-ordered sample and the phantom discard/status are Task 15's job (task-15-brief lines 24, 367-398 index results by address and count only index<sampleSize). Task 14 gives it what it needs; nothing here is random.
- ✅ runAll: emits every result, run() = runAll().filter{alive}; concurrency bounded by Semaphore + limitedParallelism; CancellationException and BindFailedException rethrown (propagate through coroutineScope/channelFlow); other exceptions swallowed.
- ✅ No em-dashes in any changed file; no new dependencies. Discovery tests and build: BUILD SUCCESSFUL (run by me).

## Task quality: Approved

## Findings
Critical: none. Important: none.
Minor:
1. Sweep.kt runAll KDoc says "one ordered stream"; results arrive in completion order, not address order. A Task 15 reader could assume address order and drop the index filter. Suggest "single stream" / note completion order.
2. PhantomGuardTest.kt: boundary coverage thin. No case for exactly 90% (e.g. decide(20,18,true) = RELEASE), probed=15 vs 16 with all alive (RELEASE vs PHANTOM), probed=0 finished, or probed>64 sample. Brief gave these tests so it is compliant, but a regression to `>=` or MIN_PROBES off-by-one would pass.
3. SweepTest runAll test does not cover BindFailedException propagation or the concurrency bound; it also relies on 192.0.2.1 being dead (pre-existing pattern, environment dependent, e.g. a captive network on a dev machine).
4. Sweep.kt: `finally` still counts a host whose probe threw BindFailedException into progress, contradicting the inline comment "do not count a dead host" (pre-existing; progress only, sweep ends anyway).
5. ALIVE_RATIO as Double works (verified) but integer compare would remove the reliance on float rounding; optional.

## Plan-text conflict
None. Spec 4.7 "first 64 probed addresses" is honoured by Task 15's brief design, not by Task 14's API; Task 15 review must verify it.
