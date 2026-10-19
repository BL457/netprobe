# Task 12 report: SNMP probe (Ada)

Status: DONE. Commit c6b0ce9 "Add SNMP sysDescr probe with hand-written BER".

Implemented Snmp.kt (internal Ber, SnmpInfo, Snmp.buildGet/parseResponse/query) plus test-only SnmpTestSupport.kt and SnmpTest.kt, per brief.

Deviations from brief code (both mechanical):
- parseResponse and query converted from expression bodies to block bodies (`return null` inside expression body is rejected by Kotlin).
- query(): CancellationException rethrown ahead of the broad catch (project pattern); InterruptedException branch dropped.

TDD:
- RED: `:app:compileDebugUnitTestKotlin` failed (Snmp/Ber/SnmpInfo unresolved) before implementation.
- GREEN: SnmpTest 7 tests, 0 failures: long form lengths, get request encoding, parses a get response, parses a hand-written get response, garbage and truncation yield null, error status yields null, oid multi-byte subidentifiers.
- Full unit suite: 82 tests, 0 failures. assembleDebug BUILD SUCCESSFUL.

Self-review: no em-dashes; no shadowing; socket via use{}; timeout via soTimeout. Parser catches Exception broadly but returns null by design for garbage input.
Concerns: the Windows heredoc dropped one backslash in the test regex initially; fixed. UDP path is untested until Task 22 (on device). Reader.int() accumulates into Int (fine for SNMP request ids/status). Fixed request id 0x5a5a is not validated against response.
