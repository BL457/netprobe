# Task 2 report

Status: DONE_WITH_CONCERNS

Implemented HomeCopy, RadarModes (RadarMode, RadarSummary, RadarModes, ScanSummaryText, RadarSummaries, RadarText), RelativeTime, ProgressLine, HeaderRules in ui/home, verbatim from the brief. Six test classes added.

TDD
- RED: `compileDebugUnitTestKotlin` failed (Compilation error, BUILD FAILED) before main sources existed.
- GREEN: ui.home.* BUILD SUCCESSFUL. RadarModesTest 5/0 fail, RelativeTimeTest 5/0, ScanSummaryTextTest 6/0, ProgressLineTest 3/0, HeaderRulesTest 3/0, CopyTableTest 3/0.
- Full `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL; 250 tests, 0 failures.

Concern: the brief's CopyTableTest used single-backslash regex escapes ("\d", "\.", "\(") inside normal Kotlin strings, which are illegal escapes. I doubled the backslashes (\d etc.) in the template list only; regex semantics unchanged. Brief should be fixed for later tasks that append templates (Task 16).
