# Neighbour spike (spec section 11 item 6)

Status: NOT RUN - phone not attached when Task 2 was implemented.

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.NeighbourSpikeTest`
then `adb -s 28221FDH300AP9 logcat -d | grep -E "NeighbourSpike|avc: denied.*netlink"`.

Record here: Android version, `entries with mac: N` (need N >= 1), any `avc: denied` lines.
Gate: errno 13 or avc denied means netlink source is dropped (spec section 11 item 6).
