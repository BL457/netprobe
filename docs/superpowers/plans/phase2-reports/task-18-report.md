# Task 18 report

Implemented NeighbourRules (Outcome has macs, evidence, onLinkIps) and wired it into the DiscoveryEngine neighbour block and sweepExtra release, as the brief specifies. Added neighMacs map; extra-range live hosts take MAC/Source.NEIGH from it.

RED: compileDebugUnitTestKotlin FAILED, "Unresolved reference 'NeighbourRules'" (NeighbourRulesTest.kt:19, plus 'macs'/'evidence' unresolved).
GREEN: NeighbourRulesTest 6/6, DiscoveryEngineExtendedTest 23/23, DiscoveryEngineTest 20/20; `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL.

Judgement calls:
- Brief's NeighbourRules snippet lacked onLinkIps; added per the Interfaces/rules text.
- Did not add the unused Ipv4 import to DiscoveryEngine (the engine does not use it).
- Gateway-MAC-on-other-IP and proxy-ARP entries are excluded from both macs and evidence (brief's code); their IPs stay in onLinkIps so rows survive.
- Committed only the four task files; the working tree has an unrelated modified docs/superpowers/specs/2026-10-19-radar-ui-design.md, left untouched. No attribution lines (Director's standing rule).

Files: engine/discovery/NeighbourRules.kt (new), DiscoveryEngine.kt, NeighbourRulesTest.kt (new), DiscoveryEngineExtendedTest.kt.
Concern: none blocking; neighbour dump behaviour on device not re-run (JVM fakes only).

## Fix round 1
The gateway's own IP is now exempt from the proxy-ARP filter (NeighbourRules.kt), so a proxy-ARPing router keeps its MAC; other IPs with the gateway MAC are still filtered. Added a NeighbourRulesTest case (7 tests). Full suite and assembleDebug green.
