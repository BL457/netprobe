# Task 19 review: keep the screen on during an extended run (7e3511a..2110cc3)

Reviewer: Bugsy. Unit suite: `:app:testDebugUnitTest` BUILD SUCCESSFUL (up to date). The instrumented test was not re-run (no adb); the report's device run is taken as given. Live dumpsys evidence is deferred to Task 20 (accepted).

## Spec compliance
- ✅ Window holds FLAG_KEEP_SCREEN_ON while an extended run is active (spec s10, 11.12): Nav.kt:16-17 collects `extendedRunActive` and KeepScreenOn.kt:22-26 adds the flag.
- ✅ Scoped to extended runs only: HomeViewModel.kt:184 `if (ext.enabled)`. Quick scan all and Detail port scans never touch it. grep shows no other addFlags/clearFlags/FLAG_KEEP in main (the two Intent addFlags in AndroidActionRunner are unrelated).
- ✅ Lives at the nav host, not Home, so opening a device mid-run keeps the screen on.
- ✅ Cleared on finish, Stop, failure, network change: HomeViewModel.kt:197 is the first line of `finally`, and nothing suspends before it. Stop and network change cancel the job, so the finally runs. A failing engine throws into the same finally.
- ✅ No stale-clear race: ScanController.start makes a new run `prev.join()` before `work`, so a cancelled run's finally cannot clear a newer run's flag.
- ✅ ViewModel cleared: viewModelScope cancellation runs the finally.
- ✅ Activity recreation: the container is app-scoped, so the StateFlow survives. The old window is cleared on dispose and the new one is set because the effect is keyed on `activity`.
- ✅ Composable leaving composition: onDispose clears.
- ✅ No Activity or Window leak: the VM and container hold only a Boolean StateFlow, and the Window is captured only in the effect while composed.
- ✅ No new dependencies (collectAsStateWithLifecycle was already used in the app per the brief), no permission, no em-dashes (grep on the three new or changed files: 0).
- ✅ Implementation matches the brief text.

## Task quality: Approved

## Findings
### Critical
None.
### Important
None.
### Minor
1. HomeViewModel.kt:184-185 (Ada's concern). The flag is set before `c.locks.acquire()`, which sits outside the try. `c.locks` is lazy, so if constructing `Locks` threw (for example `getSystemService(WIFI_SERVICE)` returning null makes the `as WifiManager` cast throw), the flag stays true in the app-scoped StateFlow. The screen would stay on for every Activity until process death, and every later scan would retry the same failure. Not required now: that failure is very unlikely (acquire and release are runCatching-wrapped), and the same throw would already break every scan. It is a free hardening, though. Move line 184 to the first line inside the `try` (the finally already resets it first), or set it after `acquire()`.
2. No JVM test covers the VM wiring (set only when `ext.enabled`, cleared on Stop or failure). KeepScreenOnTest covers only the composable. The scoping rule and the clear-on-failure paths rest on code reading plus Task 20 live checks 8 and the dumpsys check. Consider a small VM or ScanController-level test if one is cheap.
3. Nav.kt:16 uses collectAsStateWithLifecycle, so while STOPPED the composable does not see updates. If a run ends while the app is backgrounded, the window flag stays set until the next START. Harmless, because a stopped window cannot hold the screen on and it corrects within a frame of returning, but worth knowing when reading Task 20 dumpsys output.
4. KeepScreenOnTest.kt:44-48 mutates Compose state from the test thread without `rule.runOnUiThread` or `runOnIdle`. It works with the compose test rule's snapshot handling (brief text verbatim), but is slightly flaky-prone. The test is otherwise meaningful (set, clear, clear-on-dispose after an active period) and leaves no state, since the rule finishes the activity. It does not exercise `findActivity` through a ContextWrapper.

## Plan-text conflict
None. The report's "DONE_WITH_CONCERNS" concerns match the above: live evidence outstanding (Task 20) and the pre-acquire placement (Minor 1).
