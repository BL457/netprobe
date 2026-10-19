# Neighbour spike (spec section 11 item 6)

Status: PASS

- Device: Pixel 7 Pro (28221FDH300AP9), Android 17 (SDK 37)
- Phone Wi-Fi: wlan1 10.0.3.242/24 (subnet 10.0.3.0/24)
- Entries with MAC: 6 before sweep, 11 after sweep (sweep added 5 hosts, so the sweep populated the table)
- avc: denied netlink lines: none. errno 13: none.
- Gradle: `Starting 1 tests on Pixel 7 Pro - 17` / `Finished 1 tests on Pixel 7 Pro - 17` / `BUILD SUCCESSFUL in 33s`
- Time taken: about 34 s wall clock for the Gradle run
- Fixture edit: NeighbourSpikeTest `prefixes` now `listOf("10.0.3.", "192.168.1.", "192.168.0.", "10.0.0.")` (phone's /24 added first; others kept)

Run: `./gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.NeighbourSpikeTest`
then `adb -s 28221FDH300AP9 logcat -d | grep -E "NeighbourSpike|avc: denied.*netlink"`.

## Logcat

```
10-19 19:16:31.280 I TestRunner: started: neighbourDumpReturnsMacsAfterSweep(com.northwalessolutions.netprobe.NeighbourSpikeTest)
10-19 19:16:31.318 I NeighbourSpike: before sweep: Success([NeighbourEntry(ip=10.0.3.1, mac=e0:63:da:26:f1:37, state=REACHABLE), NeighbourEntry(ip=10.0.3.248, mac=14:ae:85:71:01:e2, state=STALE), NeighbourEntry(ip=10.0.3.170, mac=e4:5f:01:e4:22:dc, state=DELAY), NeighbourEntry(ip=10.0.3.217, mac=20:df:b9:1b:bb:ea, state=STALE), NeighbourEntry(ip=10.0.3.196, mac=1c:f2:9a:0a:14:66, state=STALE), NeighbourEntry(ip=10.0.3.7, mac=f0:72:ea:d8:db:8c, state=STALE)])
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.175 70:ee:50:74:55:f4 REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.1 e0:63:da:26:f1:37 REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.248 14:ae:85:71:01:e2 DELAY
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.234 e4:5f:01:e4:22:dc REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.191 42:71:f7:95:a9:d1 REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.59 00:11:32:cf:4d:c5 REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.223 60:a5:e2:dc:0c:b2 REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.170 e4:5f:01:e4:22:dc REACHABLE
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.217 20:df:b9:1b:bb:ea DELAY
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.196 1c:f2:9a:0a:14:66 DELAY
10-19 19:16:34.381 I NeighbourSpike: 10.0.3.7 f0:72:ea:d8:db:8c DELAY
10-19 19:16:34.381 I NeighbourSpike: entries with mac: 11
10-19 19:16:34.381 I TestRunner: finished: neighbourDumpReturnsMacsAfterSweep(com.northwalessolutions.netprobe.NeighbourSpikeTest)
```

Observation: 10.0.3.170 and 10.0.3.234 share MAC e4:5f:01:e4:22:dc (one device, two IPs); dedupe by MAC downstream if counting devices.
