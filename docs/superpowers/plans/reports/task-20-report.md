# Task 20 report: Application wiring

Status: DONE. Implemented RawResources.kt, engine/common/Locks.kt and the full AppContainer verbatim from the brief, plus RawResourcesTest.

TDD
- RED: `:app:testDebugUnitTest --tests '*RawResourcesTest*'` -> BUILD FAILED, `:app:compileDebugUnitTestKotlin` compilation error (RawResources unresolved).
- GREEN: BUILD SUCCESSFUL. Per-test: "lines trims drops blanks and comments" pass; "bundled mdns types file has the appendix B list" pass (tests=2 failures=0).
- Full: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 178 tests, 0 failures/errors.

Device (Pixel 7 Pro 28221FDH300AP9): install -r "Success"; am start OK; process alive (pid 2738) after 4s; `logcat -s AndroidRuntime:E` empty; FATAL EXCEPTION count 0. AppContainer is lazy and nothing in the UI touches it, so its initialisation is not exercised on device (as instructed, no UI change).

Files: AppContainer.kt (modified); data/RawResources.kt, engine/common/Locks.kt, test RawResourcesTest.kt (new).

Self-review: no shadowing; Locks swallows via runCatching by design (release safe when not held). Concern: lazy wiring (raw resource parsing, Room open, Locks) is unverified on device until Task 21 uses it.
