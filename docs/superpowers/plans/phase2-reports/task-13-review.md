# Task 13 review: GatewayProbe (ea934c6)

Verified: committed source is byte-identical to the working tree; `GatewayProbeTest` run: 8 tests, 0 failures, 0 skipped. No em-dashes, no build file changes (no new dependencies).

## Spec compliance
- ✅ Walk: enclosing /16 of the phone's RFC1918 address, 256 /24s minus any overlapping the scanned range (255 for a /24 scan), `.1`, `.254`, then the phone's gateway offset when not 1/254/0; ascending order.
- ✅ Seed list: the exact 20 addresses in spec order; skipped when inside the phone's /16 (plus inside `scanned`, harmless extra); non-RFC1918 phone keeps seeds, no walk. Seeds sharing a /24 merge into one unit.
- ✅ Ports 80 then 443, stop at first definitive answer (connect/refused = hit and ends the unit, unreachable = no, timeout = next port).
- ✅ TEST-NET: 192.0.2.1 and 198.51.100.1, port 80, `answered` = Connected or Refused, `of` = any. Unreachable and Timeout are not answers.
- ✅ RandomHighPort: 49152 until 65536, rejection-samples out HostProber.DEFAULT_PORTS + QuickPorts.list (62078 is the only one that matters); deterministic under a seeded Random.
- ✅ Concurrency: Semaphore(64) around each address (both ports) plus 64-wide default dispatcher; test uses delay (suspends) so the peak check genuinely exercises the semaphore.
- ✅ Cancellation: single `coroutineScope` (not supervisor), no catch in `answers`, so CancellationException and BindFailedException propagate and siblings are cancelled; Probe.connect closes the socket from its watcher. No leaked jobs. Hits are discarded on failure (by design).
- ✅ Progress/attempts: `done++` and `onProgress(done, size, attempts.get())` inside one lock; attempts is an atomic that only grows, so reads under the lock are monotonic and the last callback sees all increments. Initial (0, n, 0) call made.
- ✅ Signatures match brief and Tasks 15/20 (three-arg onProgress per controller ruling; production connect via Probe.connect with binder is Task 15's wiring).
- ✅ Hits ordered by unsigned `start()`.

## Task quality: Approved

Findings (no Critical, no Important):
- Minor 1 (GatewayProbeTest.kt, whole file): no test for cancellation or for BindFailedException propagating out of `run`. Both are correct by trace but unguarded; a regression (e.g. adding `runCatching` in `answers`) would pass all 8 tests.
- Minor 2 (GatewayProbeTest.kt progress test): asserts peak <= 64 but not that peak > 1, so a Semaphore(1) regression would pass. Also port order (80 before 443) is never asserted, only the set.
- Minor 3 (GatewayProbe.kt:~210): an exception thrown by `onProgress` inside the lock aborts the whole probe. Acceptable while Task 15's callback is a simple state update.
- Minor 4 (GatewayProbe.kt `GatewayTargets.of`): seeds also filtered by `scanned.contains`, which the spec does not state; no observable effect for RFC1918 phones.

RED evidence is a single compile-failure line, which is exactly what the brief predicts for new types; the tests are genuine behavioural assertions (hit rules, unreachable short-circuit, monotonic progress, exact attempt total, peak bound), not tautologies. Fake connect is deterministic; only a 2 ms coroutine delay, no wall-clock assertions.

Plan-text conflict: none beyond the already-ruled onProgress arity.
