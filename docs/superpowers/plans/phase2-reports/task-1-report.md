# Task 1 report (Ada): Status NEEDS_CONTEXT (code done and green, device screenshots blocked, NOT committed)

## Implemented
Tokens.kt, Motion.kt, Theme.kt (replaced), colors.xml, themes.xml (dark), MainActivity (enableEdgeToEdge, dark bars), manifest portrait lock
(`screenOrientation=portrait`, `configChanges=screenSize`), plus 4 unit tests, the Contrast helper and the instrumented TypographyTest. All verbatim from the brief.

## TDD
RED unit: `Task :app:compileDebugUnitTestKotlin FAILED`, `Unresolved reference 'NetProbeColors'` (ContrastTest.kt:9, :10) and others.
RED androidTest: `Task :app:compileDebugAndroidTestKotlin FAILED`, errors in TypographyTest.kt:19 (NetProbeText unresolved, destructuring cannot infer).
GREEN unit (theme.*): BUILD SUCCESSFUL; ContrastTest 5/5, EdgeColorTest 3/3, ReducedMotionTest 1/1, 0 failures.
GREEN instrumented: "Starting 1 tests on Pixel 7 Pro - 17 / Finished 1 tests", BUILD SUCCESSFUL, tests=1 failures=0 (tnum present in Plex file).
Full: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; 225 unit tests, 0 failures.
No SDK install or licence prompts appeared.

## Install / screenshots
`installDebug` succeeded; `am start -W` Status ok (WaitTime 3022); no AndroidRuntime crash from the app (only the adb `content` helper process logs).
BLOCKED: the Pixel is locked (mWakefulness=Dozing, keyguard showing, deviceLocked=1, secure lock). Both screencaps came out solid black, so I deleted them
and did not commit them. I did not try to bypass the lock. Rotation settings were restored (accelerometer_rotation stays 0, its original value).
Needed: Director unlocks the phone, then rerun Step 5 (adb steps) and review the shots, then commit
`git add -A && git commit -m "Add Radar theme tokens, Plex typography, reduced motion, dark window and portrait lock"`.

## Self-review
Diff matches the brief verbatim. No em-dashes, no shadowing, no magic numbers beyond the brief's constants. Visual check (window colour, bars, Plex) not yet possible.
Note: the brief's `tr -d ''` is a stripped `\r`; I used `tr -d '\r'`.

## Outcome (coordinator ruling)
Committed 35e8b8b "Add Radar theme tokens, Plex typography, reduced motion, dark window and portrait lock" (code only, no screenshots).
Lock check: `dumpsys window policy | grep showing=` gave showing=true. Screenshots pending: phone locked. Status DONE_WITH_CONCERNS.

## Screenshots (phone unlocked)
Reinstalled, captured t01-cold-start.png and t01-rotation-locked.png (303758 bytes each), rotation settings restored (accelerometer_rotation 0), no FATAL in logcat.
Both show: dark navy window, dark status and nav bars with light icons, IBM Plex rendering, portrait kept. Existing header text draws under the status bar (expected until Tasks 6 to 8).
Committed as "Add Task 1 screenshots". Status DONE.
