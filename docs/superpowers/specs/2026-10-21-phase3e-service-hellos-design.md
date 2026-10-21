# Phase 3E: Service hellos (nmap-style service detection) design

Date: 2026-10-21. Author: controller for the Director (light rigour, his ruling). Status: DRAFT for Gruff.

## 1. Goal

Replace "the port number says what it is, the banner may correct it" with the method nmap uses for `-sV` (what the Director calls `-A`): send a short, ordered list of protocol hellos to every open port whose banner has not settled it, match the replies against patterns that name the service, product, version and stack, and let one match narrow the next probes. Then go one tier further than nmap for the services modern stacks actually run on: HTTP and HTTPS. Both tiers are driven by data files we author in the repository, so a new stack is a new entry, not new code.

Not in scope: OS detection, UDP port scanning, writing probes (every hello is read-only), anything downloaded.

## 2. What exists today (Phase 3A ladder, `engine/serviceid`)

`ServiceIdentifier.identify(ip, port, evidence, mode)` picks a fixed step set by port number: passive read; `HEAD /`; a TLS peek (`TlsPeek`, leaf certificate CN and O); `GET /` for title and headers (`HttpTitleFetcher`); RTSP `OPTIONS`; an RDP X.224 request. `BannerParsers` knows SSH, the mail and FTP greetings, HTTP, RTSP and RDP. `ActiveProbeBudget` caps active steps per scan (64 ports, 60 s); Quick is passive, Full and Custom are active. Results are a `ServiceInfo` (name, product, version, detail, confidence, six evidence fields) stored on `PortEntity` (schema 4) and merged by `ServiceMerge`. `DeviceIdentifier` reads services and the 3B tool hints through `identify_rules.json`. The 3B tool `HttpFingerprintTool` names a product from `http_fingerprints.json` (31 rules over title, headers and body).

The ladder is right in shape and wrong in reach: it only knows the ports it was told about, its probes are code, and it stops at the Server header.

## 3. Method (the nmap part)

For each open TCP port, `ServiceProbes.identify(ip, port, mode, evidence)`:

1. **Null probe.** Connect, send nothing, read for `nullWaitMs` (default 1000). Any bytes are matched against every probe's matches whose `protocol` lists `null` (SSH, FTP, SMTP, POP, IMAP, Telnet, MySQL, VNC, NATS, rsync, X11 and anything else that speaks first). A hard match ends the ladder. A port on the fragile list (section 6) ends here whatever happened.
2. **TLS check.** If the null read gave nothing and the port is not in `plainOnly`, do today's TLS peek. On a handshake, every later probe in this ladder is sent inside TLS (nmap's "ssl tunnel" retry), the certificate fields are kept, and `TLS` becomes the floor name.
3. **Ordered probes.** Build the list: probes whose `ports` contain this port first (in file order), then every other probe with `safety: safe` by rising `rarity` (1 common to 9 rare), skipping probes whose `tls` says `never` when the port is TLS and `only` when it is not. Send each: connect (or TLS connect), write `send`, read up to `maxBytes` for `waitMs`, match. A **hard** match ends the ladder. A **soft** match keeps only probes whose `service` equals the soft service for the rest of the ladder (nmap's behaviour). A probe is sent at most once per port per scan; a refused connect ends the ladder (the port closed under us).
4. **HTTP tier.** If the result's service is HTTP or HTTPS (by match, by the existing HEAD/GET steps, or by a null-probe `HTTP/1.` reply), run the HTTP stack rules of section 5 over one `GET /` (already fetched by the 3A step, reused) and at most `maxPaths` (3) extra well-known paths that the matching rules ask for.
5. **Result.** The best `ServiceInfo` (section 7), with `probe` naming the probe that settled it and `stack` naming the software, plus the CPE for the vulnerability lookup when the match carries one.

Budget: every active step is `ActiveProbeBudget.step`, exactly as the 3A ladder; the per-port cap is `maxProbesPerPort` (default 6) so a silent port costs at most six connects, and the per-scan caps stay 64 ports and 60 s. Quick scans stay passive except for the null probe and the port-hinted probes marked `safety: safe` and `quick: true` (cheap single-packet hellos such as SNMP, DNS, HTTP HEAD), under a Quick budget of 16 ports and 15 s. Settings gains one switch, "Service hellos" (default on; off makes every scan behave as Quick does today). UDP: the DNS, SNMP, NTP, SIP and CoAP probes also run over UDP on their standard ports when the host answered TCP on nothing else of interest, only in Full and Custom scans (UDP sends are single datagrams and count as one step).

## 4. Probe data: `res/raw/service_probes.json`

A list of probes. Strings use nmap's escape forms (`\r\n`, `\xNN`, `\0`) so published probe knowledge transfers by hand; a loader decodes them once.

```json
{
  "id": "redis-info",
  "service": "Redis",
  "protocol": ["tcp"],
  "send": "*1\r\n$4\r\nINFO\r\n",
  "ports": [6379],
  "rarity": 3,
  "safety": "safe",
  "quick": false,
  "tls": "never",
  "waitMs": 1500,
  "maxBytes": 4096,
  "matches": [
    {"regex": "^\\$\\d+\\r\\n# Server\\r\\nredis_version:([\\d.]+)", "service": "Redis", "product": "Redis", "version": "$1", "stack": "Redis", "cpe": "cpe:2.3:a:redis:redis:$1"},
    {"regex": "^-NOAUTH ", "service": "Redis", "product": "Redis", "detail": "auth required"}
  ],
  "samples": ["$3122\r\n# Server\r\nredis_version:7.2.4\r\n"]
}
```

Fields: `id` unique; `service` the service this probe is for (used by soft matches); `protocol` `tcp`, `udp` or both (`null` is the null probe, listed once); `send` the bytes; `ports` the hint list (may be empty); `rarity` 1 to 9; `safety` `safe` or `careful` (careful probes are only sent to their hinted ports, never to the rest of the ladder); `quick` whether a Quick scan may send it to a hinted port; `tls` `never`, `also` or `only`; `waitMs` and `maxBytes` caps; `matches` in order, each with a `regex` (Kotlin regex over ISO-8859-1 text of the reply, `(?s)` allowed, binary via `\xNN`), the fields it sets (`service` required, `product`, `version`, `detail`, `stack`, `cpe` optional, `$1`.. substituted), and `soft: true` for a soft match; `samples` one or more replies that must hit a match, used by the template test. A probe with no `matches` is invalid; a `send` over 512 bytes is invalid.

Initial set (about 35, authored from protocol knowledge, no downloads): null; http-get (`GET / HTTP/1.0`), http-head (today's); tls (the peek, declared so the order is data); ssh, ftp, smtp, pop3, imap, telnet, vnc-rfb, mysql, nats, rsync, x11, git (null-probe matches); dns-version (TCP and UDP, `version.bind` TXT CH); snmp-sysdescr (UDP, community `public`, v2c GET of sysDescr and sysObjectID); ntp-version (UDP mode 6 readvar); smb-negotiate (SMB2 NEGOTIATE, dialects and the OS from the 3B SMB probe's parser); rdp-x224 (today's); postgresql-startup (SSLRequest then StartupMessage with user `netprobe`, matches `E`rror or `R` auth, `S`/`N` for the SSL answer); redis-info; mongodb-ismaster (OP_MSG `{isMaster:1}`, matches `maxWireVersion` and `version`); memcached-version; mqtt-connect (CONNECT with client id `netprobe`, matches CONNACK codes); amqp-header (`AMQP\0\0\9\1`, matches the Connection.Start method and server properties); sip-options (TCP and UDP); rtsp-options (today's); ldap-rootdse (anonymous bind then a base search for `supportedLDAPVersion`, `vendorName`, `vendorVersion`); modbus-device-id (function 43 MEI 14 read device identification, hinted only, careful); kafka-apiversions; zookeeper-ruok; adb-cnxn (`CNXN`, matches the device banner); mssql-prelogin; socks5 (version 5 no-auth hello); coap-core (UDP, GET `/.well-known/core`); bacnet-whois (UDP, hinted only, careful); nats, ipp (today's HEAD), docker-version, elasticsearch-root and kubernetes-version are HTTP paths and live in section 5.

## 5. HTTP stack data: `res/raw/http_stacks.json` (absorbs `http_fingerprints.json`)

A list of rules over an HTTP exchange. The 31 existing rules convert one for one (`source` becomes `where`), so the 3B HTTP fingerprint tool keeps its behaviour and gains every new rule.

```json
{
  "id": "home-assistant",
  "where": "body",
  "pattern": "<title>Home Assistant</title>|homeassistant",
  "stack": "Home Assistant",
  "product": "Home Assistant",
  "cpe": "cpe:2.3:a:home-assistant:home-assistant:*",
  "paths": ["/api/"],
  "pathMatch": {"status": 401, "body": "\"message\": ?\"401: Unauthorized\""},
  "type": "HUB",
  "sample": "<title>Home Assistant</title>"
}
```

`where` is one of `title`, `server`, `poweredBy`, `header:<name>`, `cookie`, `body`, `favicon` (the MurmurHash3 of `/favicon.ico`, as the Shodan and httpx tools publish it) or `path` (the rule is decided by its `paths` request alone). `pattern` is a regex; `$1` may feed `version`. `paths` lists at most three extra GETs the rule may spend to confirm itself, each with a `pathMatch` on status and body; a rule with `paths` is confirmed only when a path matches. `type` optionally sets the device type the identity engine may adopt (`HUB`, `NAS`, `CAMERA`, `PRINTER`, `ROUTER`, `MEDIA`, `SERVER`); `vendor` and `model` may be set the same way. Rules run in file order; the first hard hit wins the `stack`, every hit is kept as a reason.

Initial set (about 45): the 31 existing rules, then Home Assistant, Pi-hole (`/admin/` title and the FTL `X-Pi-hole` header), Plex (`X-Plex-Protocol`, `/identity`), Jellyfin and Emby (`/System/Info/Public`), Proxmox (`/api2/json/version` 401 with `PVEAuthCookie`), Synology DSM 7 (`SYNO.Core`, `/webapi/entry.cgi`), QNAP, TrueNAS, Unraid, Portainer (`/api/status`), Grafana (`grafana_session`, `/api/health`), Prometheus (`/-/healthy`), Traefik and Caddy (Server), nginx, Apache, IIS, lighttpd, Express and Next.js (`X-Powered-By`), Werkzeug and Gunicorn and Uvicorn (Server), Tomcat and Jetty, Spring Boot (`Whitelabel Error Page`), ASP.NET, WordPress (`wp-content`), Nextcloud (`/status.php`), Gitea and GitLab, Jenkins (`X-Jenkins`), MinIO (`/minio/health/live`), OctoPrint, Frigate, Zigbee2MQTT, Node-RED, Uptime Kuma, Immich (`/api/server-info/ping`), Syncthing (`/rest/noauth/health`), Vaultwarden, the *arr family (`/api/v3/system/status` 401), qBittorrent, Docker Engine (`/version`), Docker Registry (`/v2/`), Elasticsearch and OpenSearch (`/` JSON `tagline`), Kubernetes API (`/version` 401 or 403 JSON), UniFi OS and UniFi Network, Omada, OpenWrt LuCI, pfSense and OPNsense, FRITZ!Box, Hikvision, Dahua, Reolink, TP-Link camera and router pages, HP, Brother, Canon and Epson printer pages, Philips Hue (`/api/config` JSON `bridgeid`), Shelly (`/shelly`), Tasmota and ESPHome.

## 6. Safety

Every hello is read-only: no writes, no authentication with real credentials, no requests that change state. Three classes:

- `safe`: a single hello that any implementation of the protocol answers or ignores. Sent to hinted ports and, by rarity, to the rest of the ladder.
- `careful`: hinted ports only (Modbus device id, BACnet who-is, LDAP search, MQTT CONNECT, AMQP, PostgreSQL startup, MongoDB).
- The fragile list gets the null probe only and nothing else, as today: raw print 9100 to 9107, LPD 515, Cisco SCCP 2000 and 2001, X11 6000 to 6063, the mail ports that greet first (25, 110, 143), IPP 631 beyond HEAD, and every industrial port except its own careful probe (Modbus 502, BACnet 47808, EtherNet/IP 44818, S7 102).

Per-port cap six probes, per-scan budget unchanged, probes never run on a host while a 3B tool runs on it, and a scan's Stop cancels the ladder mid-probe as it cancels everything else.

## 7. Results, storage, identity

`ServiceInfo` gains `stack: String?`, `cpe: String?` and `probe: String?` (the probe or rule id that settled the name), all defaulted so call sites compile. `PortEntity` gains the same three columns: schema 5, `MIGRATION_4_5` additive, exported schema `5.json`, a `DirectorCopyMigration45Test` on a pushed copy. `ServiceMerge` treats `stack`, `cpe` and `probe` like `product`: new facts win, null never erases, and a probe-settled name outranks a port-table guess. `Cpe.forService` prefers the match's `cpe` over the map. The port row shows `stack` when set (otherwise product), then version; "Why this identity" lists `Port 8123: Home Assistant (http-stacks home-assistant)`. `identify_rules.json` gains the field `stack` (regex over the stacks of all ports) so a stack can set vendor, model, type or software; the http_stacks `type`, `vendor` and `model` fields are applied as rules of weight 60 generated at load, so the two files cannot disagree. Quick scan all and the radar's identify pass use the same engine with their current modes.

## 8. Manual tool: "Identify service"

A fifth tool on the Tools tab (3C-2 `ToolId.IDENTIFY`, label "Identify service"): any address and port the manual tools accept, same routing, no options sheet. It runs the full ladder (section 3) with a fresh budget of 1 port and 20 s regardless of settings, lists every probe sent with its reply length and the match it produced, and ends with the summary `"<service> <product> <version>, N probes"` or `"No service recognised, N probes"`. Results sheet rows: one per probe (`redis-info: 312 bytes, Redis 7.2.4`), then the stack rules that hit. Share exports the same lines. The run writes its result to the port row as a scan would.

## 9. Copy (all in `ServiceCopy` and `ToolCopy`, template-tested)

Settings: "Service hellos", caption "Sends short protocol greetings to open ports to name the service, product and version, like nmap's service detection. Read-only; off makes every scan passive." Tool: "Identify service", summaries above, progress `"probe N of M"`. Port row: `"<stack or product> <version>"`. Why this identity line as in section 7. Messages reuse the tool framework's.

## 10. Tests

JVM: the probe file loads, every probe is valid (section 4 rules) and every `sample` hits one of its own matches (template test over the file); the http_stacks file loads and every `sample` hits its rule; ladder order (hinted first, then rarity, careful never off its hints, fragile ports null only, `tls` filters, soft match narrowing, per-port cap, refused connect ends); TLS wrap (probes re-sent through the TLS socket after a handshake); budget accounting per step; UDP probes only in active modes; `ServiceMerge` and `Cpe` with the new fields; migration 4 to 5; identity rules on `stack`; copy templates. Fake sockets throughout (a `ProbeTransport` interface with a real `SocketTransport` and a scripted fake); no test touches the network. Device: `Migration45Test`, `DirectorCopyMigration45Test`, `ManualToolsContentTest` for the new tool, and one `ServiceProbesDeviceTest` against a local server socket in the test process that plays three recorded replies (Redis, SSH, HTTP) so the real transport is exercised. On-device QA by the Director against his LAN (Pi-hole, Synology, UniFi, Hue, Nest devices, the printer) with the record naming what each port resolved to before and after.

## 11. Assumptions and open items

A1 Quick scans may send the small `quick` set; the Director can set the switch off if he wants Quick passive. A2 UDP hellos in Full and Custom scans only. A3 `careful` classes as in section 6. A4 The 3B HTTP fingerprint tool keeps its name and reads `http_stacks.json`. A5 MurmurHash3 for the favicon hash is written in-repo (about 40 lines), no dependency. Open: O1 whether the Identify service tool should also be offered on the device page's port row ("Identify") as a one-tap action; O2 whether the stack name should replace the product on the Devices list row subtitle; O3 the Quick budget numbers.
