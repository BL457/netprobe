# Task 3 report: Subnet maths
Status: DONE. Commit b0682f1 "Add subnet maths with large-network clamp".
Files: app/src/main/java/com/northwalessolutions/netprobe/netenv/Subnet.kt, app/src/test/java/com/northwalessolutions/netprobe/netenv/SubnetTest.kt (both verbatim from brief).
RED: compileDebugUnitTestKotlin failed: "SubnetTest.kt:12:17 Unresolved reference 'SubnetMath'", "15:33 Unresolved reference 'size'", "16:51 Unresolved reference 'hostAddress'"; BUILD FAILED.
GREEN: SubnetTest 8 tests, 0 failures, 0 skipped. Full :app:testDebugUnitTest and :app:assembleDebug: BUILD SUCCESSFUL.
Self-review: matches brief; no em-dashes; no downloads. Notes: Subnet(prefix) is not validated in the constructor (only SubnetMath.of validates), size is Int (fine to /0 would overflow: 1 shl 32 = 1, not reachable via sweepRange since it clamps to /24 or maxPrefix). Git warned LF->CRLF only.
