# Task 10 report

Status: DONE. Commit b31ef04 "Add SSDP search, UPnP description parsing and MAC extraction".

Implemented Ssdp.kt (SsdpParser, MacExtract, SsdpSearch) and UpnpDescription.kt (UpnpDevice, SAX UpnpDescriptionParser, UpnpFetcher) per brief, plus 4 fixtures and SsdpTest / UpnpDescriptionTest.

TDD: RED = :app:compileDebugUnitTestKotlin failed (unresolved SsdpParser/MacExtract/UpnpDescriptionParser), BUILD FAILED. GREEN = SsdpTest 3/3, UpnpDescriptionTest 4/4, BUILD SUCCESSFUL. Full suite 67 tests, 0 failures (57 + 10). assembleDebug BUILD SUCCESSFUL.

Deviation: brief's UpnpFetcher.fetch was an expression body containing `return null`, which Kotlin rejects ("Returns are prohibited for functions with an expression body"). Changed to a block body `{ return try { ... } }`; behaviour identical. (Indentation of that function is slightly off.)

Fixtures: garbage is exactly 300 bytes written by a python script (prefix text plus binary bytes, no CR/LF so git EOL conversion cannot alter it). Other fixtures are LF; git warned of LF->CRLF on commit for text files (harmless, parsers tolerate both). tv fixture also has modelNumber/UDN/embedded device.

Concerns: SsdpSearch unit-untested (device, Task 22). No em-dashes, no attribution.

## Fix round 1

Changes in UpnpDescription.kt: fetch now rethrows CancellationException ahead of the generic catch (dead InterruptedException branch removed); handler overrides resolveEntity to return an empty InputSource; read loop has an overall deadline of timeoutMs; fetch re-indented.
Tests added to UpnpDescriptionTest: `external entities are never resolved`, `fetch refuses a location on another host` (server flag stays unset), `fetch caps the body at maxBytes` (completes under 3 s), `fetch does not follow redirects` (302 gives null). Loopback ServerSocket on 127.0.0.1, daemon thread.
Commands: `./gradlew :app:testDebugUnitTest --tests '*UpnpDescriptionTest*' --tests '*SsdpTest*'` -> BUILD SUCCESSFUL; per-test: all 11 testcases pass (UpnpDescriptionTest 8, SsdpTest 3), 0 failures. Then `./gradlew :app:testDebugUnitTest :app:assembleDebug` -> BUILD SUCCESSFUL, full suite 71 tests, 0 failures.
Note: tests were first written green-after-change (the new tests were not run RED first against the old fetcher).

## Fix round 2

Production change: `UpnpDescriptionParser.parse(xml)` now delegates to `internal fun parse(xml, allowDoctype: Boolean)`; the public path still passes false (DOCTYPE refused).
Tests rewritten in UpnpDescriptionTest:
1. entities: DOCTYPE parse with allowDoctype=true, entity SYSTEM URL = loopback ServerSocket; asserts no connection, friendlyName null/empty, and that the default path returns null.
2. host: URL on the listening 127.0.0.1 port, allowedHost 127.0.0.2; asserts null and no connection.
3. cap: closing tags arrive after 200 KB inside <manufacturer>; maxBytes 4096 gives null, maxBytes 300_000 gives friendlyName "Big".
4. redirect: 302 to a second loopback server serving a valid description; asserts null and second server never hit.

Mutation checks (guard broken temporarily, then restored from backup; run with `./gradlew :app:testDebugUnitTest --tests '*UpnpDescriptionTest*'`):
- host check removed: FAILS `fetch refuses a location on another host`.
- cap removed (loop unbounded, read size buf.size): FAILS `fetch caps the body at maxBytes`.
- instanceFollowRedirects = true: FAILS `fetch does not follow redirects`.
- resolveEntity returning null only: PASSES (the external-general-entities=false feature also blocks). external-general-entities feature removed only: PASSES (resolveEntity blocks). BOTH removed: FAILS `external entities are never resolved`. So the entity test is discriminating for the pair; the two guards are deliberately redundant defence in depth and neither alone is observable.

Final: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL, 71 tests, 0 failures.
