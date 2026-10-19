# Task 6 report: TCP connect port-scan engine

Status: DONE_WITH_CONCERNS (one deviation from the brief, see below).

Implemented Probe.kt, PortScanEngine.kt and PortScanEngineTest.kt exactly per brief, except one line in Probe.connect.

## TDD
RED (compile failure, engine absent): `PortScanEngineTest.kt:21:23 Unresolved reference 'PortScanEngine'`, `:22:22 Unresolved reference 'PortState'`.
First GREEN attempt with the brief's verbatim code: 4 pass, 1 FAIL:
`PortScanEngineTest > cancellation stops the flow quickly FAILED  AssertionError: cancel took 1716 ms`
Final GREEN (3 consecutive --rerun-tasks runs BUILD SUCCESSFUL). Per-test times from XML:
- open and closed ports are classified 0.005s
- progress counts every port once and counts opens 0.142s
- unroutable address ... FILTERED within budget 0.31s (2 ports, 300ms timeout, concurrency 2; real timeout path)
- cancellation stops the flow quickly 0.326s total, including the 300ms delay, so cancelAndJoin about 20ms
- results arrive progressively 0.05s
Full unit suite: 34 tests, 0 failures. assembleDebug BUILD SUCCESSFUL.

## Deviation and root cause
The brief's watcher `launch(start = UNDISPATCHED)` inherits the engine's limitedParallelism(64) dispatcher. On cancel, the watcher's finally block must be resumed on that dispatcher, but with 64 concurrent connects all 64 threads are blocked in Socket.connect, so the close() only ran when the 2000ms timeouts freed threads (1716ms measured). Fix: `launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED)`, so the watcher's cleanup runs on the cancelling thread immediately. Assertion unchanged.

## Files
- app/src/main/java/com/northwalessolutions/netprobe/engine/common/Probe.kt
- app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngine.kt
- app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngineTest.kt

## Self-review / concerns
- Unconfined watcher is correct for Tasks 7/8 too; Probe doc comment could mention it.
- Engine: `done/open/errors` are plain vars mutated under synchronized; fine. Unused Channel import from brief left as is.
- 192.0.2.1 on this machine timed out or failed fast; both FILTERED, test passes either way.
- Progress `errors` only counts unexpected exceptions, which Probe never throws (it maps all to Timeout), so it stays 0 in practice.
