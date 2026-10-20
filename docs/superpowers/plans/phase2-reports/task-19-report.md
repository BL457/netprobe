# Task 19 report: keep screen on during an extended run

Status: DONE_WITH_CONCERNS. Commit: "Keep the screen on while an extended discovery run is active" (on phase2, after 7e3511a).

Implemented exactly per brief: KeepScreenOn.kt (new), AppContainer.extendedRunActive, Nav.kt applies KeepScreenOn at the nav host, HomeViewModel.discover sets the flag (only if ext.enabled) before locks.acquire() and clears it as first line of finally (covers finish, Stop, failure, network-change cancel). Detail and Settings untouched. Dispose clears the flag when the host leaves composition.

Judgement call: the brief/spec scope the flag to EXTENDED runs only, not every scan. Followed the brief.

RED: compileDebugAndroidTestKotlin FAILED, KeepScreenOnTest.kt:24:38 Unresolved reference 'KeepScreenOn'.
GREEN: connectedDebugAndroidTest on Pixel 7 Pro: 1 test, BUILD SUCCESSFUL (leaveApksInstalledAfterRun=true accepted). :app:testDebugUnitTest :app:assembleDebug: BUILD SUCCESSFUL.

Device evidence: installed on 28221FDH300AP9. The Director's stored extEnabled=false, and I was told not to change settings, so an extended run could not be started. A plain scan (ext off) was started from the ring; during it dumpsys window showed only "Window #8 Window{... netprobe/...MainActivity}" with no KEEP_SCREEN_ON line, and dumpsys power "Wake Locks: size=0" (correct: flag only for extended runs). The flag-held-during-run/cleared-after path is proven by the instrumented test on the device (set, clear, clear-on-dispose), not by a live extended scan. The docs/qa/shots/phase2/t19 files were not produced.

Concerns: (1) live extended-run dumpsys evidence outstanding; needs the Director to enable extended discovery. (2) Flag set sits before locks.acquire() outside the try, per brief; acquire uses runCatching so cannot throw, only lazy Locks construction could, negligible. (3) Nav uses collectAsStateWithLifecycle, so it updates only while STARTED, which is fine since the flag matters only in foreground.
Files: AppContainer.kt, ui/KeepScreenOn.kt, ui/Nav.kt, ui/home/HomeViewModel.kt, androidTest/.../ui/KeepScreenOnTest.kt
