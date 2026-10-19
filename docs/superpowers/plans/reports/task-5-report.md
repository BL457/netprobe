# Task 5 report
Status: DONE. Commit 2957ce4.
Implemented: ScanProfile.kt (QuickPorts 115, ScanProfile, ProfileParser), PortTable.kt, res/raw/ports.json (135 entries, unique ports, all 115 quick ports plus Appendix C), Gradle test resources srcDir for src/main/res/raw.
RED: compileDebugUnitTestKotlin FAILED (unresolved ScanProfile/PortTable). Then a compile error from escape collapse in the regex (fixed by using a raw string """^(\d+)(?:-(\d+))?$""").
GREEN: ScanProfileTest 5/5, PortTableTest 3/3 pass; full suite 29 tests, 0 failures (Smoke 1, Neighbour 5, PortTable 3, ScanProfile 5, NetworkEnvMapper 7, Subnet 8); assembleDebug BUILD SUCCESSFUL.
Self-review: regex hoisted to a constant, MAX_PORT constant added (no behaviour change). No em-dashes in ports.json. Names authored by hand.
Concerns: brief said "21 tests" prior; suite is now 29 (21+8). Ports 1025-1029 labelled "MS-RPC (dynamic)", 2001/1110/3986 etc. have generic names; low risk. CRLF warnings only.
