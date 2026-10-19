# Task 7 report

Implemented HostProber.kt and Sweep.kt verbatim from the brief (Sweep is open class, open fun run). Tests in HostProberTest.kt and SweepTest.kt.

RED: compileDebugUnitTestKotlin failed (classes missing), BUILD FAILED.
First GREEN attempt: 5 tests, 1 failed: "open port means alive and is recorded" expected [port] but was [] (HostProberTest.kt:18).
Cause: brief test listed ports as (freePort(), open); the brief's own rule stops at first Refused, so the open port was never probed. Test bug, not code bug. Fix: reordered to listOf(open, freePort()). Implementation unchanged.
GREEN: focused run BUILD SUCCESSFUL; full unit suite BUILD SUCCESSFUL (39 tests = 34 + 5); assembleDebug BUILD SUCCESSFUL.
Timings: all timeouts 0.715s (2 ports x 300ms), refused 0.007s, open 0.005s, stops-early 0.004s, sweep 0.34s.
Commit: "Add TCP liveness prober and subnet sweep".

Self-review / concerns:
- Sweep emits via channelFlow; _progress is shared state per Sweep instance, so concurrent run() calls on one instance would interleave progress.
- Unreachable on first port short-circuits as not alive (per brief).
- Empty catch of Exception is deliberate per brief, but silent.
