# Task 11 review (a01c236..679338f)

Test run: `./gradlew :app:testDebugUnitTest` BUILD SUCCESSFUL. XML results: Rfc1918Test 4, CidrParserTest 6, ManualRangeStoreTest 4, 0 failures/errors. No em-dashes in the touched files. No new dependencies. Subnet.kt untouched; Room untouched.

## Spec compliance
- ✅ Files: Ipv4.kt, SubnetOps.kt, Rfc1918.kt, CidrParser.kt created; Settings.kt modified; three test files present.
- ✅ Signatures: Ipv4.parse, Subnet.start/end/overlaps/containsSubnet/slash24Units, Rfc1918.BLOCKS/contains(ip)/contains(Subnet)/enclosing16, CidrEntry, CidrResult.subnets, CidrParser constants (MIN 20, MAX 30, seven ERR_*), parse/parseOne/subnetOf/units/merge, ManualRangeStore(read, write) with revision/get/set/forNetwork/key, SettingsState ext fields, AppSettings.manual and EXT_MAX_MIN/MAX/DEFAULT = 1/16/4. All match the brief and the diff is character-for-character the brief's code, so Tasks 12, 13, 17 can consume them.
- ✅ Key form `extManual.<networkId>`, newline-joined, via the existing "netprobe" SharedPreferences through read/write lambdas; empty list removes the key; no Room change.
- ✅ Per-network isolation (test asserts separate lists and the raw key); null network gives empty.
- ✅ Messages verbatim per brief; own-network rule matches spec 4.4 line 117 (wholly inside local); superset overlap is deferred to derive time per spec 4.5.
- ✅ extMax coerced 1..16 on load and update. Cap enforcement itself is not in this task (belongs to CandidateRanges).
- ✅ Existing SubnetMath/Subnet reused (SubnetMath.of, toInt); no duplicated maths.
- ✅ Edge maths checked by reading: start() unsigned mask, end() safe at prefix 0 and 32 (1L shl), /31 and /32 ranges correct, 172.16/12 and 192.168/16 boundaries by containsSubnet, /22 gives four /24s, a /24 or narrower (including /25) returns itself.
- ✅ Parser: rejects junk, out-of-range octets, hostnames, IPv6, missing prefix, empty/non-numeric/over-32 prefix, and stops at first error.

## Task quality: Approved

RED assessment: the tests reference Ipv4, Rfc1918, CidrParser, ManualRangeStore and the extension functions, none of which existed, so compilation fails and every test would have failed. That is a genuine RED, if a weak one (it proves nothing about assertion strength). The tests are not tautological: they pin exact strings, boundary addresses (172.15/172.32, 100.64, 169.254, 192.169), unsigned compare, order and drop-on-load.

## Findings
Critical: none. Important: none.

Minor (5):
1. Ipv4.kt:10-11 accepts leading zeros ("010.000.004.000/24" parses as 10.0.4.0/24) and, because `Char::isDigit` and `toInt` accept Unicode digits, non-ASCII digits (Arabic-Indic) too. The review focus asked for leading zeros to be rejected; the brief does not, and the code is verbatim, so this is a plan-text gap rather than an implementation slip. Scenario: a user types "010.0.4.0/24" expecting octal or a typo flag and silently gets 10.0.4.0. Harmless but unflagged. Fix in a later task: require `c in '0'..'9'` and reject length>1 with leading '0'.
2. CidrParser.kt:114 strips all internal whitespace, so "10. 0.4.0/24" and "10.0.4.0 /2 4" are accepted. The brief tests "10.0.4.0 / 24" only. Lenient, not unsafe.
3. Test gaps: no assertions for /31 and /32 end(), prefix 0, adjacent ranges across the 172.16/12 and 10/8 edges, TEST-NET rejection via Rfc1918, leading zeros, or duplicates inside `existing` in merge (merge does not collapse repeats already in `existing`). Brief tests are met; edges were verified here by reading only.
4. ManualRangeStoreTest bypasses the AppSettings.manual SharedPreferences adapter (Settings.kt:42), so the write lambda (remove vs putString, apply) is untested; it is a thin wrapper, but a bug there would pass the suite.
5. Settings.kt:5-9 import order is no longer alphabetical (`netenv` imports sit between kotlinx lines); cosmetic.

Plan-text conflict: none against the spec. The brief's own gap is Minor 1 (leading zeros not rejected although the reviewer focus list expects it).
