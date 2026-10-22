# Phase 3E: Service hellos (nmap-style service detection) design

Date: 2026-10-21. Author: controller for the Director (light rigour, his ruling). Status: FINAL after Gruff's review (`2026-10-21-phase3e-gruff-review.md`, all 7 BLOCKING and 9 MINOR applied as the rulings in section 12).

## 1. Goal

Replace "the port number says what it is, the banner may correct it" with the method nmap uses for `-sV` (what the Director calls `-A`): send a short, ordered list of protocol hellos to every open TCP port whose banner has not settled it, match the replies against patterns that name the service, product, version and stack, and let one match narrow the next probes. Then go one tier further than nmap for the services modern stacks actually run on: HTTP and HTTPS. Both tiers are driven by data files authored in the repository, so a new stack is a new entry, not new code.

Not in scope: OS detection, UDP of any kind (SNMP stays the 3B tool), probes that change state, anything downloaded.

## 2. What exists today (Phase 3A ladder, `engine/serviceid`)

`ServiceIdentifier.identify(ip, port, evidence, mode)` picks a fixed step set by port number: passive read; `HEAD /`; a TLS peek (`TlsPeek`, leaf certificate CN and O); `GET /` for title and headers (`HttpTitleFetcher`); RTSP `OPTIONS`; an RDP X.224 request. `BannerParsers` knows SSH, the mail and FTP greetings, HTTP, RTSP and RDP. `ActiveProbeBudget` caps active steps per scan (64 port numbers, 60 s of summed step time); Quick is passive, Full and Custom are active; up to 8 identifies run in parallel. Results are a `ServiceInfo` stored on `PortEntity` (schema 4) and merged by `ServiceMerge`. `DeviceIdentifier` reads services and the 3B tool hints through `identify_rules.json`. The 3B tool `HttpFingerprintTool` names a product from `http_fingerprints.json` (31 rules over title, headers and body, with `negativeSample`).

The ladder is right in shape and wrong in reach: it only knows the ports it was told about, its probes are code, and it stops at the Server header.

## 3. Method (the nmap part)

`ServiceProbes` replaces the `when (port)` ladder inside `ServiceIdentifier`: `identify` keeps its signature and callers, the port table guess stays the floor, and the mDNS and UPnP confirmations stay as the final arbiters exactly as today. For each open TCP port in Full and Custom scans (Quick scans and Quick scan all stay passive: null probe only, as today):

1. **Null probe.** One connect, send nothing, read with today's `BannerReader` early exit (first newline, or `nullWaitMs` 1000 ms). The bytes, if any, are matched against every probe's matches that list `"null"` in `protocols` (SSH, FTP, SMTP, POP, IMAP, Telnet, MySQL, VNC, NATS, rsync, X11, git and anything else that speaks first), without further connects. A hard match ends the ladder. A port on the fragile list (section 6) ends here whatever happened. "Accepted then closed before any byte" is recorded as `tcpwrapped` (name "TCP", confidence PORT_TABLE floor), never as closed.
2. **TLS check.** If the null read gave nothing and the port is not `plainOnly` (the speak-first set 21, 22, 25, 110, 143, 587, 2222, the fragile list, and any port whose null reply was non-empty), do today's TLS peek: one handshake. On a handshake the certificate fields are kept, `TLS` becomes the floor name, and the only probes sent inside TLS afterwards are `http-head` and `http-get` (one more handshake, so at most two handshakes per port). STARTTLS is never attempted.
3. **Ordered probes.** Build the list: probes whose `ports` contain this port first (file order), then the probes with `safety: safe` and `rarity` 1 to 3 (the default intensity) by rising rarity, skipping those whose `tls` is `never` on a TLS port or `only` on a plain port. Send each on its own connection: write `send` (after variable substitution, section 4), read up to `maxBytes` for `waitMs` (hinted probes use their own `waitMs`; unhinted probes are capped at 400 ms), match. A **hard** match ends the ladder. A **soft** match keeps only the probes named in the match's `narrow` list for the rest of the ladder (nmap's behaviour, expressed by probe ids). A probe is sent at most once per port per scan; a refused or reset connect ends the ladder and keeps the best result so far (the port is never removed or lowered by the ladder).
4. **HTTP tier.** If the result's service is HTTP or HTTPS (by match, by the HEAD or GET step, or by a null reply starting `HTTP/1.`), run the HTTP stack rules of section 5 over the one `GET /` the ladder already made (its `HttpPeek` is kept, not refetched) and at most `maxPaths` (3) extra GETs chosen by the rules of section 5.
5. **Result.** The best `ServiceInfo` (section 7), with `probe` naming the probe or rule that settled it, `stack` naming the software, and the CPE only through the vetted path of section 7.

**Time model** (replaces "six probes and hope"): a port's whole ladder has a wall-clock cap `portWallMs` 6 s and a cap of `maxProbesPerPort` 6 connects (null and TLS included); a host has `hostWallMs` 20 s of ladder time across its ports; a scan keeps today's `ActiveProbeBudget` (60 s summed, 64 ports) but `entered` is keyed by host and port, not port number, so a second host is not refused for sharing port numbers with the first. When a cap is reached the port keeps the best result it has, which is never below the 3A result for that port (the 3A steps are the first probes in file order: null, tls, http-head, http-get, rtsp-options, rdp-x224), so budget starvation cannot make a port look worse than before 3E. Worst case per silent unknown port: 1.0 s null + 1.5 s TLS + 4 x (connect + 0.4 s) at most 6 s; a 20-port silent host is capped at 20 s; 8 hosts in parallel keep today's parallelism.

Settings gains one switch, "Service hellos" (default on), which applies to Full and Custom scans and to the manual tool's hosts only; off makes Full and Custom behave as 3A did (today's steps only). The caption says the hellos are read-only but may appear in the target's logs as a failed or anonymous connection.

## 4. Probe data: `res/raw/service_probes.json`

A list of probes. Strings use nmap's escape forms (`\r\n`, `\xNN`, `\0`) so published probe knowledge transfers by hand; the loader decodes them once and substitutes `{ip}`, `{port}` and `{rand}` (a per-run random 8-character token) in `send`.

```json
{
  "id": "redis-info",
  "service": "Redis",
  "protocols": ["tcp"],
  "send": "*1\r\n$4\r\nINFO\r\n",
  "ports": [6379],
  "rarity": 3,
  "safety": "safe",
  "tls": "never",
  "waitMs": 1500,
  "maxBytes": 2048,
  "parser": null,
  "matches": [
    {"regex": "^\\$\\d+\\r\\n# Server\\r\\nredis_version:([\\d.]+)", "service": "Redis", "product": "Redis", "version": "$1", "stack": "Redis", "role": "software"},
    {"regex": "^-NOAUTH ", "service": "Redis", "product": "Redis", "detail": "auth required", "role": "software"}
  ],
  "samples": ["$3122\r\n# Server\r\nredis_version:7.2.4\r\n"],
  "negativeSamples": ["HTTP/1.1 400 Bad Request\r\n"]
}
```

Fields: `id` unique; `service` the service the probe is for; `protocols` `tcp` and or `null` (`null` means the probe's matches are also tried on the null-probe reply; a probe with only `null` has an empty `send` and costs no connect); `send` the bytes (at most 512 after decoding; an empty `send` is only valid with `null`); `ports` the hint list (may be empty); `rarity` 1 to 9 (4 to 9 are reached only by a hint or a soft match); `safety` `safe` or `careful` (careful probes are only ever sent to their hinted ports); `tls` `never`, `also` or `only`; `waitMs` and `maxBytes` caps (`maxBytes` at most 2048: a reply is cut there before matching); `parser` null or the name of a code parser (`smb2-negotiate`, `mongodb-ismaster`, `modbus-device-id`), which turns a binary reply into a text line the matches then read, so every match stays a regex over text; `matches` in order, each with a `regex` (Kotlin regex over ISO-8859-1 text, applied with `MULTILINE` off so `^` is the start of the reply, `(?s)` allowed), the fields it sets (`service` required; `product`, `version`, `detail`, `stack` optional; `$1`..`$9` substituted, `$$` is a literal dollar), `role` `device` or `software` (default `software`), `soft: true` with a `narrow` list of probe ids for a soft match; `samples` replies that must hit one of the probe's matches and `negativeSamples` replies that must hit none, both used by the template test. Loader rules: a probe that fails validation is skipped with a logged reason and the file still loads; a regex is refused at load when it contains a nested quantifier over a group (`(…+)+`, `(…*)*`, `(…+)*`), the one cheap lint that stops the common ReDoS shapes; matching only ever sees at most 2048 bytes.

Initial set (about 30, authored from protocol knowledge, no downloads). Null-probe matchers (no send): ssh, ftp, smtp, pop3, imap, telnet, vnc-rfb, mysql, nats, rsync, x11, git-daemon, http-null (a reply starting `HTTP/1.`). Text hellos that may join the unhinted ladder (`safe`, rarity 1 to 3): tls (the peek, declared so the order is data), http-head, http-get, rtsp-options (`{ip}`, `{port}`), sip-options (TCP, `{ip}`, `{rand}` as the Call-ID), redis-info, memcached-version, zookeeper-ruok, docker-engine (HTTP `GET /version`, rarity 3). Hinted-only (`careful`, binary or authenticating): smb2-negotiate (445, `parser`), rdp-x224 (3389), postgresql-startup (5432: the SSLRequest answer `S` or `N`, then one StartupMessage as user `netprobe`; matches the `E`rror or `R` authentication reply, text fields), mongodb-ismaster (27017, `parser`), mqtt-connect (1883 and 8883 inside TLS, client id `netprobe-{rand}`, clean session, matches the CONNACK codes), amqp-header (5672, matches `AMQP` version reply or Connection.Start properties text), modbus-device-id (502, function 43 MEI 14, `parser`), ipp-head (631, today's HEAD only). Cut from 3E after Gruff's review: every UDP probe (DNS, SNMP, NTP, SIP over UDP, CoAP, BACnet), LDAP, Kafka, MS SQL, ADB, SOCKS, X11 beyond its null match, Oracle.

## 5. HTTP stack data: `res/raw/http_stacks.json` (absorbs `http_fingerprints.json`)

A list of rules over an HTTP exchange. The 31 existing rules convert one for one: the loader accepts the old `source` and `name` field names as aliases of `where` and `stack`, keeps `negativeSample`, and `HttpFingerprintTool` and `FingerprintRules` are pointed at the new file in the same task, so the 3B tool keeps its behaviour and gains every new rule.

```json
{
  "id": "home-assistant",
  "all": [
    {"where": "body", "pattern": "<title>Home Assistant</title>|homeassistant"}
  ],
  "stack": "Home Assistant",
  "product": "Home Assistant",
  "version": null,
  "role": "software",
  "cpeProduct": "Home Assistant",
  "paths": [{"path": "/api/", "status": 401, "body": "401: Unauthorized"}],
  "hard": true,
  "sample": "<title>Home Assistant</title>",
  "negativeSample": "<title>Home</title>"
}
```

`all` is a list of conditions that must all hold (AND); each has `where` (`title`, `server`, `poweredBy`, `header:<name>`, `cookie`, `body`, `status`, `favicon`) and `pattern` (a regex; `status` compares the number; `favicon` compares the hash). A single old-style `where`/`pattern` pair is accepted as a one-condition `all`. `version` may name a capture (`$1` of the first condition that captured). `role` `device` or `software`; only `device` rules may carry `vendor`, `model` and `type` (`HUB`, `NAS`, `CAMERA`, `PRINTER`, `ROUTER`, `MEDIA`, `SERVER`); the loader refuses `type` on a `software` rule. `cpeProduct` names an entry of `cpe_map.json` (section 7); a rule cannot carry a free CPE. `paths` is an allow-list of idempotent GETs the rule may spend to confirm itself (status plus body regex); `hard` marks a rule whose hit ends the stack pass (default true for rules with `paths` or a `favicon` condition, false for Server-header-only rules such as nginx, which stay as reasons). The favicon hash is Shodan's: MurmurHash3 x86 32-bit, seed 0, over the standard base64 of the body with a newline after every 76 characters, printed as a signed 32-bit integer; the body is capped at 64 KB, fetched once per port only when at least one rule needs it, with no redirects, and it does not count against `maxPaths`; a known-vector test pins the hash.

Path selection (the three extra GETs per port): rules whose header or body conditions already hit spend their paths first, in file order; path-only rules (no cheap condition) are tried only when the port is HTTP and no hard rule has hit, in file order, until `maxPaths` is spent. Every hit, hard or not, is kept as a reason; the first hard hit sets `stack`.

Initial set (about 45): the 31 existing rules converted, then Home Assistant, Pi-hole, Plex, Jellyfin, Emby, Proxmox, Synology DSM 7, QNAP, TrueNAS, Unraid, Portainer, Grafana, Prometheus, Traefik, Caddy, nginx, Apache, IIS, lighttpd, Express and Next.js, Werkzeug, Gunicorn, Uvicorn, Tomcat, Jetty, Spring Boot, ASP.NET, WordPress, Nextcloud, Gitea, GitLab, Jenkins, MinIO, OctoPrint, Frigate, Zigbee2MQTT, Node-RED, Uptime Kuma, Immich, Syncthing, Vaultwarden, the *arr family, qBittorrent, Docker Engine, Docker Registry, Elasticsearch and OpenSearch, Kubernetes API, UniFi OS and UniFi Network, Omada, OpenWrt LuCI, pfSense and OPNsense, FRITZ!Box, Hikvision, Dahua, Reolink, TP-Link, HP, Brother, Canon and Epson printers, Philips Hue, Shelly, Tasmota, ESPHome. `device` role: the cameras, printers, routers, Hue, Shelly, Tasmota, ESPHome, UniFi OS, FRITZ!Box, Synology, QNAP, TrueNAS. Everything else is `software` ("Runs ...").

## 6. Safety

Every hello is read-only in the sense that it changes no state on the target; several (PostgreSQL, MQTT, SMB2, AMQP, the HTTP paths that answer 401) may be logged by the target as an anonymous or failed connection, and the settings caption says so. Classes:

- `safe`: a text hello any implementation of the protocol answers or ignores (section 4 list). Sent to hinted ports and, by rarity 1 to 3, to the rest of the ladder.
- `careful`: hinted ports only; every binary or authenticating probe is `careful`, so no binary blob is ever sent to an unrelated listener.
- Fragile: raw print 9100 to 9107, LPD 515, Cisco SCCP 2000 and 2001, X11 6000 to 6063, IPP 631 beyond its HEAD, and the mail ports 25, 110, 143, 587 get the null probe only. Industrial ports (Modbus 502, BACnet 47808, EtherNet/IP 44818, Siemens S7 102) get no connect at all from the ladder except the one hinted `careful` probe that exists for them (Modbus only in 3E); their null read is skipped.

Per-port cap six connects and 6 s, per-host 20 s, per-scan budget as today; the ladder never runs on a host while a 3B tool holds it (`HostBusyRegistry.TOOL`), except for the Identify service tool's own run; a scan's Stop cancels the ladder mid-probe, including inside a TLS handshake (the socket is closed from the cancel path).

## 7. Results, storage, identity

`ServiceInfo` gains `stack: String?`, `stackRole: String?`, `cpe: String?` and `probe: String?`, defaulted so call sites compile. `PortEntity` gains `stack`, `stackRole`, `cpe` and `probe` as the last columns, defaulted: schema 5, `MIGRATION_4_5` additive, exported `5.json`, `Migration45Test` and `DirectorCopyMigration45Test`. `ServiceMerge`: when the fresh result's name is CONFIRMED and differs from the stored CONFIRMED name, the stored `product`, `version`, `detail`, `stack`, `stackRole`, `cpe` and `probe` are cleared before the merge (a service that changed is not haunted by its predecessor); otherwise the new fields merge like `product`. CPE: a match or rule may only name `cpeProduct`, an entry of `cpe_map.json`; `Cpe.forService` builds the CPE from that entry's template and the version, and the version passes `SAFE_VERSION` first, as today; a free CPE string in the data is a load error. The port row shows `stack` when set (otherwise product), then version; "Why this identity" lists `Port 8123: Home Assistant (home-assistant)`. `DeviceIdentifier` registers `stack` in `PORT_FIELDS` so `identify_rules.json` may match on it; `device` role hits from the stack data become generated rules of weight 60 setting vendor, model and type; `software` role hits feed only the `software` slot ("Runs Plex"). Ties at equal weight keep the first in file order, as today.

## 8. Manual tool: "Identify service"

A fifth tool, `ToolId.IDENTIFY`, label "Identify service", applicable to any port: any address and port the manual tools accept, same routing, no options sheet. It runs the full ladder with a fresh budget (1 port, 20 s wall) regardless of the settings switch (the switch governs scans, the tool is an explicit request), takes the host's `TOOL` claim as other tools do (the ladder's "not while a tool runs" check is skipped for the tool's own run), and reports one `FindingKind.PROBE` finding per probe sent (`redis-info: 312 bytes, Redis 7.2.4`), then one `FindingKind.STACK` finding per stack rule that hit, and the summary `"<service> <product> <version>, N probes"` or `"No service recognised, N probes"`. The result reaches the port row through a new `PortsRepository.applyIdentify(hostId, port, ServiceInfo)` that runs `ServiceMerge` exactly as a scan's consumer does. Two runs on the same host are refused by the existing busy rule.

## 9. Copy (in `ServiceCopy` and `ToolCopy`, template-tested)

Settings: "Service hellos", caption "Sends short protocol greetings to open ports during Full and Custom scans to name the service, product and version, like nmap's service detection. They change nothing on the device but may show in its logs as an anonymous connection." Tool: "Identify service", the summaries above, progress `"probe N of M"`. Port row: `"<stack or product> <version>"`. Why this identity line as in section 7.

## 10. Tests

JVM: the probe file loads and every probe passes validation (section 4 rules, a deliberately bad probe is skipped and the rest load); every `sample` hits one of its probe's matches and every `negativeSample` hits none (template test over the file); the same for `http_stacks.json`; ladder order (hinted first, then rarity 1 to 3 only, careful never off its hints, fragile null only, industrial no connect, `tls` filters, soft match narrowing by `narrow`, per-port and per-host caps, refused connect keeps the best result, 3A result never lowered); TLS wrap (only http-head and http-get inside TLS, at most two handshakes); budget accounting per step keyed by host and port; `ServiceMerge` clearing on a changed name; `Cpe` refusing a free CPE and passing `SAFE_VERSION`; migration 4 to 5; identity rules on `stack` and the device versus software roles; hostile replies (a 1 MB reply is cut at 2048 bytes, a slow reply ends at `waitMs`, a reply that never closes ends at the port wall cap) with the scripted transport; Stop during a handshake closes the socket; the favicon hash against a known vector; copy templates. Transport: a `ProbeTransport` interface (`connect`, `connectTls`, `write`, `read(maxBytes, waitMs)`, `close`, cancellation-aware) with the real `SocketTransport` and a scripted fake; no test touches the network. Device: `Migration45Test`, `DirectorCopyMigration45Test`, `ManualToolsContentTest` for the new tool, and `ServiceProbesDeviceTest` against a server socket in the test process that plays recorded replies (Redis, SSH, HTTP) so the real transport is exercised. On-device QA by the Director: a Full scan of the Pi-hole, the Synology, the gateway, the Hue bridge, the Nest devices, the printer and any ESP devices, recording what each port resolved to before and after, with the Settings switch as the rollback, and the fragile devices watched for hangs.

## 11. Assumptions and open items

A1 Quick scans and Quick scan all stay passive; the switch governs Full and Custom only. A2 Default intensity: unhinted ladder rarity 1 to 3. A3 The favicon hash follows Shodan's definition. A4 The 3B HTTP fingerprint tool keeps its name and reads `http_stacks.json`. A5 MurmurHash3 is written in-repo (about 40 lines), no dependency. Open: O1 whether the Identify service tool should also sit on the device page's port row as a one-tap action; O2 whether the stack name should replace the product in the Devices list subtitle.

## 12. Rulings on Gruff's review

1 Time model: per-port 6 s and 6 connects, per-host 20 s, scan budget keyed by host and port, unhinted waits 400 ms, starvation keeps the 3A result (section 3). 2 `plainOnly` defined; TLS tunnel only for the HTTP probes; at most two handshakes; no STARTTLS (section 3 step 2). 3 Binary and authenticating probes are `careful` and hinted-only; the wording is "changes nothing, may be logged"; MQTT client id random (sections 4, 6, 9). 4 UDP cut entirely (section 1). 5 Variables, `parser`, `protocols` with `null`, `$$`, `MULTILINE` off, 2048-byte cap, the nested-quantifier lint, skip-on-invalid (section 4). 6 CPE only through `cpeProduct` and the vetted map, version through `SAFE_VERSION` (section 7). 7 `role` device or software, `type` refused on software, `ServiceMerge` clears on a changed name, `stack` registered in `PORT_FIELDS` (sections 5, 7). 8 Null early exit kept, `tcpwrapped` defined, refused connect never lowers (section 3). 9 Soft matches narrow by probe ids; intensity rarity 1 to 3 (sections 3, 4). 10 `all`, `negativeSample`, `status`, `version`, `hard`, aliases for `source` and `name`, path selection (section 5). 11 Favicon hash defined with a vector test and caps (section 5). 12 Paths are an allow-list and the caption says logs (sections 5, 9). 13 Industrial ports get no connect except the hinted probe; mail ports listed consistently (section 6). 14 Quick stays passive; the switch applies to Full and Custom (section 3). 15 `ToolId.IDENTIFY`, `FindingKind.PROBE` and `STACK`, `PortsRepository.applyIdentify`, busy rule, tool ignores the switch (section 8). 16 Hostile-reply, Stop-in-handshake, stale-stack, careful-unreachable and negative-sample tests; LAN QA names the fragile devices and the rollback switch (section 10).

## 13. For the plan writer

`ServiceProbes` is a new class in `engine/serviceid` that `ServiceIdentifier` delegates its ladder to; the 3A steps become the first probes of the file (null, tls, http-head, http-get, rtsp-options, rdp-x224) so the old `when (port)` goes away and `BannerParsers` becomes the `parser` and match helpers. `ProbeTransport` as in section 10. Loader: `ServiceProbeRules.load(json)` and `HttpStackRules.load(json)`, each returning the valid entries plus a list of skipped ids with reasons; a bad regex skips its probe. `ActiveProbeBudget.tryEnter(hostId, port)`. New `ServiceCopy` object. `PortEntity` columns appended last. `Why this identity` reasons come from `probe` via `HostEvidenceBuilder`. `HttpPeek` from the ladder's GET is passed to the stack pass, never refetched.

## 14. Director rulings 2026-10-22

R1 Every port scan (Quick, Full, Custom, Quick scan all) is passive: the null read and the 3A-equivalent steps only, never the hello ladder, never the stack paths or favicon. The Settings "Service hellos" switch is removed, with its reset entry, copy and tests. Sections 3, 9 and A1 are superseded where they mention the switch.

R2 Service identification is on demand: a per-port action ("Identify" on the Device screen port row, running the Identify service tool) and a per-device action ("Identify all services" in the Device screen overflow, one tool run over every open port, 64 ports and 60 s wall, progress "port N of M", summary "<n> of <m> ports named" or "No services recognised") run the full ladder, the HTTP stack tier with its paths and favicon included.

R3 Device type marker: a Settings choice between the colour stripe and a small icon, with a palette of ten colours that are easy to tell apart.

R4 Long-press copy options for a device's IP, MAC, name and details, and for a port's address.

## 15. Director rulings 2026-10-22 (b)

R5 Vendor database, offline and user-updated. Settings "Vendor database" row (caption "Names the maker of a device from its MAC address. Downloaded from the IEEE registry on request, about 5 MB, kept on the phone.", status "Not downloaded" or "Updated <date>, N vendors") and a button "Update vendor database". The update fetches, with the wordlist URL fetcher (`fetchBytes`, same redirect, cancel and failure rules, 8 MB per file), the IEEE MA-L, MA-M and MA-S CSVs in that order, parses them, and replaces `files/oui/oui.bin` atomically (temp file, rename); a failure or a Stop leaves the old table. Progress "Downloading vendor database, N of 3" with a pinned Stop in the Settings top bar, an item on the Scans tab, result "Vendor database updated: N vendors" or the fetcher's failure text. `OuiStore` holds a sorted binary table (36, 28, 24 bit prefixes plus a vendor string table), loaded once on first lookup; lookup is longest prefix first; a locally administered address gives "Private address" and is never looked up; a multicast address gives null. Identity: `ouiVendor` comes from `OuiStore.lookup`; when the identity engine ends with no vendor it sets one with reason "MAC prefix: <vendor>" at weight 20 (the old weight 50 "oui" rule is no longer a rule input); "Private address" is shown, never matched. Nothing ships built in.

R6 Bug: the scan finished message came back on every Detail visit. A screen that shows an app message consumes it when it takes it (`AppMessageDelivery`), not when its snackbar ends; a message is still delivered to whichever screen is visible when it arrives (3D).

R7 The Device type marker default is Icon.
