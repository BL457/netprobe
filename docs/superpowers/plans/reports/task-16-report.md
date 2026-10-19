# Task 16 report

Implemented engine/identify (DeviceIdentity.kt, IdentifyRules.kt, DeviceIdentifier.kt), res/raw/identify_rules.json (58 rules), and test files DeviceIdentifierTest.kt + SyntheticEvidence.kt, all verbatim from the brief.

RED: first run failed at :app:compileDebugUnitTestKotlin (Rule, DeviceIdentifier etc. unresolved), BUILD FAILED.
Second RED (after adding code): 6 of 18 failed with IndexOutOfBoundsException (see deviation).
GREEN: DeviceIdentifierTest 18 tests, 0 failures, 0 skipped; BUILD SUCCESSFUL. Full unit suite BUILD SUCCESSFUL. assembleDebug BUILD SUCCESSFUL.
Commit: afd0412 "Add device identification rules engine and seed rules".

Deviation from brief code: regex rules with no capture group (e.g. InternetGatewayDevice) made `m.groups[1]` throw IndexOutOfBoundsException. Added private `group1Of(m)` returning null when `groups.size <= 1`, used in DeviceIdentifier.matchAny and the mdnsTxt branch. Brief code would otherwise fail 6 tests.

Self-review: no em-dashes; JSON backslashes verified (loader test compiles every regex and checks $1 samples). Concern: LF/CRLF git warnings only.

## Fix round 1
Commit 4abb37f "Tighten identification rules against false positives; strengthen precedence tests". Rule count now 59.

Rules: snmp-printers anchored to ^brand\b.*model-keyword with new long sample; host-android now `^(Pixel|Galaxy(?![-_ .]?Book)|OnePlus|Xiaomi|Redmi|POCO|android)([-_ .]|\d|$)` (the extra Galaxy lookahead is needed because the suggested regex alone still matches Galaxy-Book via the separator); netbios-present weight 40, no type; gateway and upnp-igd weight 95; title-plex `\bPlex\b`; realm-routers `ASUS(?!TOR)`; server-cameras `\b(...Axis(?!\w)...)\b`; new title-cameras (httpTitle, sample Reolink).
Code: canonicalVendor() (HP, Epson, Canon, Brother, Lexmark, Kyocera, Xerox); 80-char cap (MAX_VALUE_LEN) on substituted vendor/model/os and upnp values; blank upnp manufacturer/model treated as null.
Tests: upnp precedence test now has snmp RouterOS + ports 135,445 competing and asserts the reasons; windows test asserts a ports: reason; new "false positives are avoided" tests (android names, netbios+synology ports=NAS, gateway+_ipp=ROUTER, Plex/ASUSTOR, HP host/switch not printer, HP/Epson canonical), blank upnp, 80-char cap.
Commands: ./gradlew :app:testDebugUnitTest --tests '*DeviceIdentifierTest*' -> BUILD SUCCESSFUL; ./gradlew :app:testDebugUnitTest :app:assembleDebug -> BUILD SUCCESSFUL. DeviceIdentifierTest XML: tests=25 skipped=0 failures=0 errors=0 (differential every-rule test passes with 59 rules).
