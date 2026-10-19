# Task 19 report: Vulnerability lookup (NVD)

Status: DONE_WITH_CONCERNS. Commit 125f95c "Add NVD vulnerability lookup with CPE mapping, rate limiting and cache".

## Implemented
Package engine/vulns: Cpe.kt, NvdClient.kt (Vuln, HttpGet, NvdParser, UrlHttpGet), RateLimiter.kt, VulnLookup.kt (VulnQuery, VulnResult, VulnCache, VulnLookup), plus res/raw/cpe_map.json and fixtures/nvd_response.json, and four test classes. Code is the brief's, with these deviations:
- VulnLookup.run: `catch (e: CancellationException) { throw e }` ahead of the broad catches (new test `cancellation propagates instead of becoming Offline`).
- UrlHttpGet: connection work wrapped in try/finally with `c.disconnect()`.
- Magic numbers (5, 50, 30_000, 24h) pulled into companion constants (NO_KEY_PERMITS, KEY_PERMITS, WINDOW_MS, DEFAULT_TTL_MS); defaults unchanged.

## TDD evidence
RED: with tests only, `:app:testDebugUnitTest --tests '*vulns*'` ended `Execution failed for task ':app:compileDebugUnitTestKotlin'. ... BUILD FAILED in 2s` (unresolved Cpe/NvdParser/etc.).
GREEN (focused): `BUILD SUCCESSFUL in 4s`; 13 testcases, 0 failures: CpeTest (service to cpe, map loads required products), NvdParserTest (3), RateLimiterTest (blocks the sixth call inside the window), VulnLookupTest (builds queries, fetches parses caches and sends api key header, cpe query with zero results retries once as a keyword search, keyword query is url encoded, offline with cache serves cache and says so, offline without cache..., cancellation propagates).
Full suite: 170 tests, 0 failures (157 + 13). `assembleDebug` BUILD SUCCESSFUL.

## Brief defects fixed in the tests (please note for Tasks 20/21)
1. Brief's test `offline with cache serves cache` seeded the fake cache under key `"kw"`, but the implementation keys the cache as `"ByKeyword:kw"` (class simple name + text). As written the test would fail. I changed the seed key to `"ByKeyword:kw"`. Task 20's real VulnCacheRepository adapter receives that composite key string as the `query` argument; it must treat it as opaque.
2. Test name for the 403/429/malformed test contained `;`, which Kotlin/JVM rejects ("Name contains illegal characters: ;"). Replaced with commas.

## Self-review
- No em-dashes in any new file (grepped). No real network in tests. Header apiKey only when non-blank. Two limiters selected by key presence.
- Limitation: RateLimiter holds its mutex while sleeping, so concurrent callers queue; acceptable (user-tap only).
- Note: the zero-result CPE retry uses its own cache key (ByKeyword:...), and the zero-result CPE response itself is cached under the ByCpe key, so a repeat tap within 24h re-does only the cached CPE read plus the cached keyword read (no network).
- Git warns LF will become CRLF on the new files (repo autocrlf); harmless.

## Concerns
- The `catch (e: IOException)` and the broad `Exception` catch both return "Offline" (as in the brief); non-IO failures are also labelled Offline.

## Fix round 1
Changes: NvdParser selects one metric (V31, else V30, else V2; empty array falls through) and reads score and severity from it (V2 severity at metric level). Policy when the selected metric lacks severity: "UNKNOWN" (never borrowed from another version). Cache keys now "cpe:"/"kw:" prefixes (test seed updated to "kw:kw"). Cpe.forService returns null unless version matches ^[A-Za-z0-9._~+-]+$. UrlHttpGet sets instanceFollowRedirects=false.
New tests: VulnLookupTest (no-key headers/host/resultsPerPage for null, "", blank; key only as exact apiKey header and absent from URL; 500 reason + stale attached for 429/500/malformed; TTL older hits network, 23h served from cache with no call), NvdParserTest (V30 only, no metrics, empty V31 -> V30, V31 score without severity vs V2 LOW -> UNKNOWN; new fixture nvd_fallbacks.json), CpeTest (version "1.2 beta" and "1.2:*" -> null).
Commands: `./gradlew :app:testDebugUnitTest --tests '*vulns*'` -> BUILD SUCCESSFUL, 19 vulns testcases, 0 failures (includes the 6 new: no key sends no headers..., key is sent only as the apiKey header..., http 500 fails with status..., cache older than ttl..., metric fallbacks and score paired with severity, version with unsafe characters yields no cpe). Then `./gradlew :app:testDebugUnitTest :app:assembleDebug` -> BUILD SUCCESSFUL; full suite 176 tests, 0 failures. No em-dashes.
