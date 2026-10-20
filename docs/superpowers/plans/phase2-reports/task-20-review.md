# Task 20 review (Bugsy)

Spec compliance: PASS. Quality: PASS.

(a) SECONDS_PER_RANGE 25 -> 16 (ExtendedSection.kt:40). capLine uses ceil(n*16/60) (line 43): cap1=1 minute, cap3=48 s -> 1 minute, cap4=64 s -> 2 minutes, cap16=256 s -> 5 minutes. ExtendedCopyTest asserts exactly these (1,4,3,16); ExtendedSectionTest (androidTest) cap 3 now "about 1 minute". Consistent with spec 8.3 (16 s) and the record's on-device cap 4 / cap 3 values.
(b) Code diff is only those 3 files (5 lines); everything else is docs and evidence. The other doc change is one line in phase2-radar-ui-on-device.md.
(c) docs/qa/shots/phase2/ lists 136 files; all b* evidence files named in the record exist (b-range-summary.png, b02-progress-100.png, b05-cap.png, b05-settings-cap1.png, b11-settings-estimate.png, b11-timings.txt, etc.).
 - b02-progress-100.png: Stop state at 04:20, radar "12 found", progress "10.0.4.0/24 (1 of 1), probing 0 of 254", list shows Home subnet devices then "Other subnet 10.0.4.0/24".
 - b-range-summary.png: finished state, "33 devices, Scanned in 86 s, 14 on another subnet", notice "2 more ranges found, raise the cap in Settings", expanded "4 other ranges checked" listing 10.0.4.0 manual swept 4 found, 10.0.9.0 manual nothing reachable, 10.0.0.0 gateway probe 9 found, 10.0.2.0 1 found, 192.168.1.0 and 192.168.2.0 "not swept, scan limit reached".
(d) No em-dashes (0 in the diff), no attribution lines.
JVM suite :app:testDebugUnitTest --offline run: no failure output.

Findings
- Minor: b-range-summary.png shows 2 more ranges and 4 checked (cap 4 run), whereas b05 cites "5 more ranges" for the cap 1 run; different runs, not a conflict, but the record does not label which run b-range-summary is.
