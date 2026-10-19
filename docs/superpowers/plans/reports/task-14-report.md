# Task 14 report
Implemented Eui64.kt (block body, constants, literal guard before InetAddress.getByName) and ReverseDns.kt.
TDD: RED = compileDebugUnitTestKotlin failed (unresolved Eui64/ReverseDns). First GREEN attempt with the brief's ReverseDns: ReverseDnsTest FAILED (4.6s > 2.5s bound) because canonicalHostName ignores interrupts and runInterruptible waits for the blocking call, so withTimeoutOrNull could not cut it off.
Fix: lookup runs canonicalHostName via runInterruptible in a detached SupervisorJob+IO scope (companion), awaited under withTimeoutOrNull; pending cancelled on timeout; CancellationException rethrown.
Final: Eui64Test 2/2, ReverseDnsTest 1/1 (1.6s), full suite 96 tests 0 failures, assembleDebug OK.
Concern: a timed-out lookup keeps its IO thread until the resolver returns (unavoidable with blocking API); Task 15 should bound concurrency.

## Fix round 1
Changes: lookup now wraps the timed await in try/finally with pending.cancel(); scope uses Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOOKUPS = 8); injectable resolver seam `resolve: (Inet4Address) -> String`; Eui64 guard requires first char hex or ':' and catches Exception. ReverseDnsTest moved to its own file with 4 new deterministic tests; Eui64Test gained uppercase-hex, ".:" and ".a:" cases.
Commands: ./gradlew :app:testDebugUnitTest --tests '*Eui64Test*' --tests '*ReverseDnsTest*' (BUILD SUCCESSFUL in 6s); ./gradlew :app:testDebugUnitTest :app:assembleDebug (BUILD SUCCESSFUL in 9s, 47 actionable tasks: 6 executed, 41 up-to-date).
Per-test results (all passed; suite total 101 tests, 0 failures):
  Eui64Test: derives mac from eui64 link local 0.007s; privacy addresses and non link local return null 0.0s; uppercase hex works and malformed literals return null 0.0s
  ReverseDnsTest: interrupt-ignoring resolver returns null within timeout 0.308s; loopback resolves or returns null without throwing and respects timeout 1.505s; resolver returning the ip string yields null 0.002s; resolver returning a name yields it 0.002s; caller cancellation propagates promptly 0.212s
