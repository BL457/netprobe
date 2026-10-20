# Task 13 report: GatewayProbe

Status: DONE. Files: engine/discovery/GatewayProbe.kt (new), test GatewayProbeTest.kt (new, 8 tests).

Implemented verbatim from the brief, with one fix: the brief's code listing declared `onProgress: (Int, Int) -> Unit` but its interface, its call sites and the test use three arguments (done, units, attempts). I used the three-argument form `(Int, Int, Int) -> Unit = { _, _, _ -> }`.

RED: compileDebugUnitTestKotlin FAILED, `Unresolved reference 'GatewayTargets'` (and GatewayProbe, AnswersForEverything, RandomHighPort).
GREEN: GatewayProbeTest 8 tests, 0 failures, 0 skipped; `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.

Consumed names verified present: Rfc1918.enclosing16, slash24Units, overlaps, start (SubnetOps.kt), SubnetMath.of/toInt/toAddress, HostProber.DEFAULT_PORTS, QuickPorts.list, Probe.Outcome.

Self-review: no real sockets in tests; delays in tests are 2 ms coroutine delay inside a fake connect (not wall-clock assertions). Progress counter and callback share one lock. Exceptions (including BindFailedException from a production connect) are not caught in `answers`, so they propagate out of coroutineScope and cancel siblings. No em-dashes.
Concerns: (1) Connected outcomes from connect are assumed keepOpen=false (socket null) so nothing leaks. (2) Semaphore plus limitedParallelism both bound to 64; redundant but harmless with injected dispatchers. (3) Test with Dispatchers.Default relies on multi-thread timing only for the peak assertion, which is an upper bound, so not flaky.
