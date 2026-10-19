# Task 22 fix report (D1-D5)

JVM: 198 tests, 0 failures (191 + 7 new). Device: DaoTest 7/7 on Pixel 7 Pro (Android 17) incl. new ensureAdoptsIdlessNetworkWhenLocationIsGranted.

## D1 off-subnet
- HostRow gains otherSubnet (offSubnet derived). Computed in the Home state combine via pure RowRules.row/otherSubnet against SubnetMath.sweepRange(env, maxPrefix).subnet, not in discover: no DB column or schema migration needed, and it stays correct if the network changes. (Deviation from the brief's wording, same behaviour.)
- Secondary line (rowSecondaryLine): "10.0.4.20 . Philips Hue, other subnet 10.0.4.0/24"; identity line still shown.
- MAC origin for the Hue: NOT the neighbour table. DiscoveryEngine filters NEIGH entries with range.subnet.contains(ip) (confirmed). The MAC ec:b5:fa:31:7a:a9 is self-reported: SSDP USN or UPnP description UDN (a version-1 UUID whose node field is the MAC; MacSource.UPNP_UDN, rank 2), reachable cross-subnet because SSDP/mDNS answers arrive from the Hue. mDNS instance name "317AA9" only has 6 hex digits so it is not the source. It is a device-claimed MAC, not link-layer verified; legitimate but weaker than NEIGH. Not changed. I did not read the stored macSource from the device DB, so this is from code analysis.
## D2 adoption
- NetworkDao.idlessByGateway + adopt; NetworkRepository.ensure adopts when env has ssid/bssid, no signature match, and exactly one row with ssid NULL and same gateway+prefix. Ssid-less env with a named-only history is not adopted (by design).
## D3 own phone
- RowRules.ownIdentity (vendor "This phone", PHONE, 100, "own address") applied in discover before setIdentity; row headline "This phone" when no custom name; identity line "Android device" (avoids "This phone . This phone").
## D4 Wi-Fi hint
- NetworkPanel first line "Wi-Fi (name needs location, see Settings)" when ssid null and transport Wi-Fi.
## D5 NAS
- DeviceIdentifier handles field upnpManufacturer; rules upnp-synology and upnp-qnap (weight 95, NAS); SyntheticEvidence and the loader field-list test updated; differential test passes; new Synology DS220+ test.

## Device verification
docs/qa/shots/qa-fix-home-top.png (hint line, CHNAS storage icon) and qa-fix-home.png (scrolled: "This phone", Hue other-subnet text). First Scan tap right after launch did nothing; second tap worked.

## Concerns
- Hue row's secondary line wraps to 2 lines (acceptable, maxLines 2).
- Pi-hole duplicate row (.234) and router model (D6) untouched, out of scope.
- Adoption is ambiguous if two id-less networks share gateway+prefix; then a new network is created.
