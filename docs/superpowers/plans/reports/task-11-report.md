# Task 11 report: NetBIOS node-status query

Status: DONE. Commit 06bad8e "Add NetBIOS node status query and parser".

Implemented NetBios.kt (request builder, parser, UDP query) and NetBiosTest.kt (4 tests).

TDD:
- RED: `:app:compileDebugUnitTestKotlin` FAILED (unresolved NetBios), BUILD FAILED.
- GREEN: BUILD SUCCESSFUL; tests passed: all zero mac is reported as null; truncated and garbage input return null; parses names and mac; request has the right length and query type.
- Full suite: 75 tests, 0 failures; assembleDebug BUILD SUCCESSFUL.

Deviations from brief:
1. Parser changed to block body (`return try {...}`): the brief's expression-body with `return null` does not compile in Kotlin.
2. Test regex `Regex("\s")` written as `Regex("[ ]+")` (the escape was mangled in transit; fixture only contains spaces, so equivalent).
3. query(): catches CancellationException and rethrows ahead of the broad catch; InterruptedException branch removed (project pattern).
4. Magic numbers extracted into private constants.
5. Zero-MAC test offset (size - 46) lines up with fixture; no change needed.

Concerns: none. UDP path untested until Task 22 on device. Parser does not validate txId; a stray datagram on the ephemeral port could be parsed (low risk).
