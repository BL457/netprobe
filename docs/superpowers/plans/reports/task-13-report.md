# Task 13 report: HTTP title fetcher

Status: DONE. Commit b19a666 "Add HTTP title fetcher".

## Implemented
engine/discovery/HttpTitle.kt: HttpPeek, HttpTitleParser, HttpTitleFetcher(timeoutMs=2000, maxBytes=65536).fetch(ip, port, tls): HttpPeek?.
Deviations from the brief code (project patterns from Tasks 8/10):
- CancellationException rethrown ahead of broad catch; no InterruptedException branch.
- Expression body with return converted to block body (fetch delegates to private fetchBlocking, no unreachable-code suppress).
- Wall-clock deadline (now + timeoutMs) in the bounded read loop; disconnect() in a finally per hop.
- Magic numbers (title cap 120, buffer 8192, redirect/error status bounds) as constants; regexes hoisted to vals.
- Whitespace regex written as [ \t\r\n\u000B\u000C]+ (equivalent to \s ASCII set) because backslash-s was mangled by the file tooling; behaviour is identical for the tests.

## TDD evidence
RED: `compileDebugUnitTestKotlin` failed (HttpTitleParser/HttpTitleFetcher unresolved), BUILD FAILED.
GREEN tail: `BUILD SUCCESSFUL in 5s`; HttpTitleTest 5 tests, 0 failures: fetches title server and realm, closed port returns null, oversized body is capped and non utf8 bytes do not throw, title parsing, follows one same host redirect.
Full suite: 87 tests, 0 failures/errors (82 + 5). assembleDebug BUILD SUCCESSFUL.

## Files
- app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitle.kt
- app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitleTest.kt

## Self-review / concerns
- Trust-all TLS only used to read the page, commented as such.
- Deadline is checked between reads; a single read can still block up to readTimeout, so worst case is about 2x timeoutMs per hop (same as Task 10).
- Redirect hop compares host only (not port/scheme), as in the brief; a same-host http->https redirect would be followed.
- Git warns LF will become CRLF on these files (autocrlf); harmless.

## Fix round 1
Commit f019daf "Fix HTTP title entity decoding, keep headers on body timeout, strengthen tests".

Changes (HttpTitle.kt):
1. Entities now a linkedMapOf with &amp; decoded last.
2. Body read (stream open + loop) wrapped in try/catch IOException; whatever bytes arrived are kept and the peek (status, Server, realm) is still returned.
5. Body decoded as UTF-8 (malformed bytes become replacement characters, no throw).

Tests (HttpTitleTest.kt): entity decoding is single pass; non utf8 bytes do not throw (renamed, still asserts non-null); utf8 title is decoded not garbled; title before the cap is found and title after the cap is not (maxBytes 4096, late title at offset 5000 gives null, early gives "Early & ok"); body read timeout keeps status server and realm (401, partial body, stalled socket, timeoutMs 500); second redirect hop is not followed (third server hit count 0, status 302, title null); cross host redirect (127.0.0.2) is not followed (status 302, title null).

Guard check: old HttpTitle.kt (HEAD) run against the new tests: 11 tests, 3 failed: "entity decoding is single pass", "body read timeout keeps status server and realm", "utf8 title is decoded not garbled". The cap and the two negative-redirect tests pass against old code by design: they are regression guards for the existing cap and hop/host checks (they fail if the cap is removed or the hop/host condition is relaxed), not tests of new behaviour.

Commands: ./gradlew :app:testDebugUnitTest --tests '*HttpTitleTest*' -> BUILD SUCCESSFUL in 6s (11 tests); ./gradlew :app:testDebugUnitTest :app:assembleDebug -> BUILD SUCCESSFUL in 6s; full suite 93 tests, 0 failures/errors.
