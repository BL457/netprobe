# Task 18 review (d39f93e) - Bugsy

Run: `./gradlew :app:testDebugUnitTest` BUILD SUCCESSFUL (all tasks up-to-date, i.e. the implementer's green run stands). Report: NeighbourRulesTest 6, Extended 23, DiscoveryEngineTest 20.

## MVP on-link MAC trace (focus)
Ordinary host 10.0.3.x, normal MAC, /24 phone: trueSubnet = SubnetMath.of(ip,24) == swept, so `onLink` keeps every local entry (NeighbourRules.kt:118). A unique non-gateway MAC has ipsPerMac=1 (<3) and mac != gatewayMac, so it is in `usable` and in `macs`. Engine DiscoveryEngine.kt:253-254 emits DiscoveredHost(mac, macSource=NEIGH, sources={NEIGH}), identical to the MVP emit (MacSource rank unchanged). Nothing drops local-/24 entries. The only MVP-visible changes are for MACs on 3+ IPs and the gateway MAC on another IP (rows kept, MAC dropped). Result: PASS. (Ipv4.parse replaces InetAddress.getByName; NeighbourParser's regex already guarantees dotted quads, so no behaviour change.)

## Spec compliance
- ✅ MACs only for IPs inside the true subnet (6.2 bullet 1); hotspot/P2P 192.168.49.x dropped.
- ✅ Gateway MAC never given to another IP (6.2 bullet 2), gateway keeps its own.
- ✅ MAC on 3+ IPs = proxy ARP: no MAC, no evidence (6.2 bullet 3; threshold matches "more than two").
- ✅ Evidence only from trusted entries in the true subnet and outside the swept range (6.2 bullet 4, 4.2); `noteEvidence` re-filters local; feeds CandidateRanges before derive (join precedes step 9).
- ✅ Neighbour entries never create rows outside the swept range (engine :253 filters `local.contains`).
- ✅ Extra-range live hosts take MAC from the same dump via neighMacs (6.3 / 11.2); no isGateway.
- ✅ Rules pure (no state, no IO); engine calls `apply` once per sweep, lookups per host are a map get under a lock.
- ✅ Keeping the row for proxy-ARP / gateway-MAC-on-other-IP entries: consistent with spec wording ("no MAC assigned", not "no host"; 4.2 forbids only off-swept rows) and with MVP, which already emitted these rows.
- ✅ Signature matches the brief plus ruled `onLinkIps`; constant PROXY_ARP_MIN_IPS = 3. No em-dashes, no new dependencies.

## Task quality: Approved

## Findings
Critical: none. Important: none.

Minor
1. NeighbourRules.kt:121 - the proxy-ARP count includes the gateway's own entry. A router that proxy-ARPs (3+ IPs on its MAC, including 10.0.3.1) loses its own MAC and OUI vendor, a regression from MVP for that network. Literal spec 6.2 bullet 3 supports it, but bullet 2 implies the gateway keeps its MAC. Suggest controller ruling: exempt `ip == gateway` from the proxy filter (and exclude the gateway from the count). No test covers it.
2. DiscoveryEngine.kt:251-254 - kept proxy-ARP rows inside the swept /24 are NEIGH-only, MAC-less rows with no liveness proof. If the router really answers ARP for unused addresses, these are ghost hosts (MVP had the same ghosts, but with the router's MAC, which made them recognisable). Judgement accepted by the brief; consider noting it in the open items.
3. NeighbourRules.kt:122 - `associate` is last-wins if the dump holds the same IP twice with different MACs (e.g. wlan0 and p2p0 both in the true subnet); non-deterministic MAC for that row. Rare.
4. Test gaps: no engine test for a proxy-ARP or gateway-MAC IP inside an extra range (release path reads neighMacs only for trusted MACs, untested); no rules test for a gateway entry that itself sits on a 3+ IP MAC; no rules test of the plain MVP case (single on-link entry in a /24 true==swept gets its MAC and no evidence). The existing DiscoveryEngineTest neighbour cases cover that at engine level.
5. DiscoveryEngineExtendedTest hotspot test: the `192.168.49.5 not in h.keys` assertion would also have passed before the change (local.contains already excluded it); the candidate-list assertion is the meaningful part.
6. NeighbourRulesTest test 4 name says "more than two ips" while the constant and spec text are "3 or more" (same thing); cosmetic.

Minor count: 6

## Plan-text conflicts
Brief's NeighbourRules snippet omitted onLinkIps (ruled). Brief says "Add the import Ipv4" to the engine; the engine does not use it, correctly not added. Brief step 4 says Extended 21 passed; now 23 (ruled).

## Re-review, round 1 (7e3511a)
Verdict: Approved. Minor finding 1 resolved.
- NeighbourRules.kt:121 predicate is now `ip == gateway || (ipsPerMac < 3 && mac != gatewayMac)`. The gateway's own IP is always usable. For every non-gateway IP the predicate is logically identical to before (gateway disjunct false, same two conditions), so the on-link MAC path (unique MAC, not gateway MAC) and the proxy filter for non-gateway IPs are unchanged.
- Gateway outside the swept range but in the true subnet still becomes evidence as before (previously only when under 3 IPs, now always); consistent.
- New test (NeighbourRulesTest, gateway + 10.0.3.50/.51 on the gateway MAC) fails under the old predicate (3 IPs dropped the gateway) and passes now: macs == {gateway only}, onLinkIps keeps all three rows. Meaningful.
- JVM suite: BUILD SUCCESSFUL (up-to-date, implementer's 7/7 stands). The radar-UI spec edit in the same range is unrelated and not part of this review.
- Residual Minor (unchanged): findings 2 to 6 stand; a gateway listed twice with different MACs would keep both (last wins in `associate`), rare.
