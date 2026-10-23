# Writing your own NetProbe tools

## Overview

A NetProbe tool is one JavaScript file. You write it, import it into the app, and it appears next to the built-in tools.

How it works:

1. You import the file from your phone or from an https address.
2. NetProbe reads the `tool` object in the file. This tells it the tool's name, what it needs (a device, a port, some inputs) and how far on the network it may reach.
3. NetProbe draws the input form from that description.
4. When you tap Run, NetProbe calls `tool.run(inputs, ctx)`. Your code does the work and reports what it finds.

The script runs in a sandbox. It cannot touch Java, the file system, the clipboard or any Android API. It can only use `ctx`, the set of functions described in this guide. Every network call goes through `ctx`, and every call is checked against the network scope your tool declared.

The language is ES5 (plain, older JavaScript), run by the Mozilla Rhino engine. See "Language notes" for what is missing.

Three example tools ship with the app: `http-headers.js`, `banner-grab.js` and `dns-lookup.js`. They are listed under Examples in the app. Their source is in `app/src/main/assets/tools/`.

## Your first tool

This is `http-headers.js`, one of the bundled examples, in full.

```javascript
// HTTP headers: fetches a page from the chosen port and lists the response headers.
// A port tool: the app asks for a device and a port, and gives the script inputs.ip, inputs.port and inputs.tls.
tool = {
  id: "http-headers", name: "HTTP headers", version: "1.0", author: "NetProbe",
  description: "Fetches a page and lists the response headers.",
  icon: "web", category: "web",
  target: "port", network: "target",
  inputs: [
    { name: "path", type: "text", label: "Path", default: "/", required: false, placeholder: "/" }
  ],
  run: function (inputs, ctx) {
    var path = inputs.path || "/";
    if (path.charAt(0) !== "/") { path = "/" + path; }
    var scheme = inputs.tls ? "https" : "http";
    var r = ctx.http.request({ method: "GET", url: scheme + "://" + inputs.ip + ":" + inputs.port + path, timeoutMs: 4000 });
    ctx.progress(1, 1, 0);
    Object.keys(r.headers).forEach(function (h) {
      ctx.finding("HEADER", h + ": " + r.headers[h]);
    });
    return { status: "done", summary: r.status + " " + r.statusText };
  }
};
```

What each part does:

| Part | What it does |
| --- | --- |
| `tool = { ... }` | The one object the script must define. Assign it to the name `tool`. |
| `id`, `name`, `version`, `author`, `description` | Identity and the text shown in the app. |
| `icon`, `category` | How and where the tool is drawn in the tool list. |
| `target: "port"` | The user picks a device and a port. NetProbe fills in `inputs.ip`, `inputs.port` and `inputs.tls` for you. |
| `network: "target"` | The script may contact only the device the user picked. |
| `inputs: [ ... ]` | One extra form field: a text box called Path, with the default `/`. |
| `run: function (inputs, ctx)` | Your code. `inputs` holds the form values. `ctx` holds the API. |
| `ctx.http.request(...)` | Makes one HTTP request and returns the response. |
| `ctx.progress(1, 1, 0)` | Tells the app: 1 of 1 steps done, 0 found. |
| `ctx.finding("HEADER", ...)` | Records one result line. |
| `return { status, summary }` | Ends the run and sets the one-line summary. |

The file also runs once at import, only to read the `tool` object. At that point `ctx` does not exist, so keep everything that uses `ctx` inside `run`.

## The tool object

| Field | Required | Type and limits | Notes |
| --- | --- | --- | --- |
| `id` | Yes | Text, 3 to 40 characters from `a-z`, `0-9` and `-` | The tool's identity. Importing a file whose id is already installed replaces that tool. |
| `name` | Yes | Text, up to 40 characters | Shown in the app. |
| `target` | Yes | `"port"`, `"host"` or `"none"` | See "Targets and network scope". |
| `run` | Yes | Function `(inputs, ctx)` | A tool without it fails to import. |
| `description` | No | Text, up to 200 characters | Defaults to empty. |
| `version` | No | 1 to 20 letters, digits, `.`, `-` or `_` | Defaults to `"1"`. Decides whether a re-import is allowed. See "Importing and updating a tool". |
| `author` | No | Text, up to 40 characters | Shown on the review sheet, marked unverified. |
| `icon` | No | One of `web`, `search`, `lock`, `folder`, `share`, `dns`, `terminal`, `fingerprint`, `network`, `wifi`, `info`, `bolt` | A missing or unknown icon becomes `terminal`. |
| `category` | No | `"web"`, `"network"`, `"identity"` or `"other"` | Defaults to `"other"`, which lists the tool under My tools. Any other value is an error. |
| `network` | No | `"target"`, `"lan"` or `"any"` | Defaults to `"target"`. See "Targets and network scope". |
| `broadcast` | No | `true` or `false` | Defaults to `false`. Only a tool with `network: "lan"` may set it to `true`. It lets the tool send packets to your network's broadcast address. See "Broadcast". |
| `minVersionCode`, `minVersionName` | No | A whole number of 1 or more, and a version such as `"0.4.3"` | The oldest NetProbe the tool needs. For a catalog tool, `tools/make-catalog-index.ps1` copies them into `index.json`, and an older app lists the tool under "Needs a newer app" instead of installing it. Older apps ignore them, so start `run` with a check such as `if (!ctx.web) { return { status: "failed", summary: "Needs NetProbe 0.4.3 or newer" }; }`. |
| `inputs` | No | Array of up to 24 input objects | See "Inputs". |

A text field must be text and must not be blank. NetProbe lists every problem it finds in one go, so you can fix them together.

## Inputs

Each entry in `inputs` is an object. These fields work for every type.

| Field | Required | Limits | Notes |
| --- | --- | --- | --- |
| `name` | Yes | Starts with a letter, then letters, digits or `_`; up to 32 characters | You read the value as `inputs.name`. Names must be unique. `ip`, `port`, `tls` and `confirmAny` are reserved, so a port input needs another name such as `udpPort`. `mac` and `broadcast` are reserved in some tools, see "The target" and "Broadcast". |
| `type` | Yes | See the table below | An unknown type is an error. |
| `label` | Yes | Text, up to 40 characters | Shown above the field. |
| `help` | No | Text, up to 200 characters | Shown under the field. |
| `required` | No | `true` or `false` | Defaults to `true`. |
| `default` | No | Must fit the type | Pre-fills the field. |

Input types, their extra options, and what `run` receives:

| Type | Extra options | Form field | What the script receives |
| --- | --- | --- | --- |
| `text` | `placeholder` (up to 40 characters) | Text box | A string. |
| `secret` | none | Masked text box. Never saved, exported or remembered | A string. |
| `number` | `min`, `max` (whole numbers) | Number box | A number. The form refuses a value outside `min` and `max`. A `min` above `max`, or a default outside the range, is an error. |
| `bool` | none | Switch | `true` or `false`. Always present. |
| `tls` | none | Switch | `true` or `false`. Use it for extra switches; the name `tls` itself is reserved. |
| `choice` | `options` (1 to 12) | Pick one | The chosen option's `value`, as a string. |
| `multichoice` | `options` (1 to 12) | Pick several | An array of the chosen values, in option order. `default` is an array of values. |
| `list` | `placeholder`, `maxItems` | Comma separated text | An array of the trimmed, non-blank items. The form refuses more than `maxItems`. A `default` is a comma separated string such as `"80,443"`, not an array. |
| `wordlist` | none | Pick a saved wordlist | An object `{id, name, entries}`. Pass the input's name to `ctx.wordlist` to read lines. |
| `ip` | none | IPv4 address box | A string such as `"192.168.1.10"`. A `default` must be a valid IPv4 address. |
| `port` | none | Port box | A number from 1 to 65535. A `default` must be in that range. |

Rules for options (`choice` and `multichoice`): each option is a string, or an object `{ value: "...", label: "..." }`. A string is used as both value and label. Values and labels are at most 40 characters. A value cannot be empty, cannot contain a comma and must be unique. A `choice` default must be one of the values.

Numeric fields (`min`, `max`, `default`, `maxItems`) must be whole numbers, no larger than 1,000,000,000 in size.

An optional input that the user leaves empty, and that has no default, is missing from `inputs`. Test it before use: `var path = inputs.path || "/";`.

Example with several types:

```javascript
inputs: [
  { name: "mode", type: "choice", label: "Mode", options: ["quick", { value: "deep", label: "Deep scan" }], default: "quick" },
  { name: "ports", type: "list", label: "Ports", placeholder: "80, 443", maxItems: 20, required: false },
  { name: "retries", type: "number", label: "Retries", min: 0, max: 5, default: 1 },
  { name: "words", type: "wordlist", label: "Wordlist" }
]
```

## Targets and network scope

### The target

`target` says what the tool is run against.

| Value | The user picks | `inputs` also holds |
| --- | --- | --- |
| `"port"` | A device and one of its ports | `inputs.ip` (string), `inputs.port` (number), `inputs.tls` (boolean, true when the user chose HTTPS) |
| `"host"` | A device | `inputs.ip` (string) and `inputs.mac` (string, see "The device's MAC address") |
| `"none"` | Nothing | Only your own inputs |

A port or host tool is offered on the device page. A tool with target `"none"` is not; it is started from the tool list. A tool with network `"lan"` is offered on a device only while that device's address is inside the phone's current subnet.

### The device's MAC address

A host tool also gets `inputs.mac`: the chosen device's MAC address in lower case with colons (`aa:bb:cc:00:11:22`), or an empty string when the app does not know it. Port tools and tools with no target do not get `inputs.mac`.

The name `mac` is reserved, with one exception: a host tool may declare its own input called `mac`, of type `text`. The form then fills it in from the device when you choose one, and you can edit it. If the tool's `mac` input is optional and left empty, `inputs.mac` is the device's MAC address. Whatever you typed is what the script gets in `inputs.mac`, so check it yourself; the form cannot check the spelling of a MAC address.

```javascript
inputs: [ { name: "mac", type: "text", label: "MAC address", placeholder: "aa:bb:cc:dd:ee:ff" } ],
run: function (inputs, ctx) {
  var digits = ctx.regex.replace("[-:. ]", inputs.mac, "");     // drop the separators
  if (!ctx.regex.test("^[0-9a-f]{12}$", digits, "i")) { throw new Error("Not a MAC address"); }
  // ...
}
```

### The network scope

`network` says how far the script may reach. Every `ctx` call that touches the network is checked.

| Scope | The script may contact | Confirmation |
| --- | --- | --- |
| `"target"` (default) | Only the device the tool was run against. Its own name is accepted without a lookup. | None |
| `"lan"` | Any address inside the phone's current subnet, except this phone, the network address, the broadcast address, loopback and multicast. A tool that declares `broadcast` may also send datagrams to the broadcast address, see "Broadcast". | The review sheet shows a callout when you import it. |
| `"any"` | Any unicast address, except loopback, the unspecified address, multicast, broadcast, reserved space (first octet 0, or 240 and above), link-local IPv6 and this phone. | The app asks you to confirm every run. A run that starts without the confirmation fails. |

A `"lan"` tool needs a connected network. Without one the run fails with "This tool needs a connected network". A call outside the scope throws an `Error` that your script can catch. A `"target"` tool with target `"none"` has no device to contact, so every call fails; give it `"lan"` or `"any"`.

DNS is limited too, so it cannot carry data out:

- Under `"target"`, only the target device's own name can be looked up.
- Under `"lan"`, only local-looking names: a name with no dot, or one ending in `.local`, `.lan`, `.home`, `.internal`, `.localdomain`, `.home.arpa`, `.intranet` or `.private`.
- Under `"any"`, any name.

Address forms NetProbe refuses:

- An IPv4 address must be a dotted quad with decimal octets and no leading zeros: `192.168.1.10`. Decimal (`3232235786`), octal, hex, short forms and IPv4-mapped IPv6 are refused with "Write IP addresses in the usual form, such as 192.168.1.10".
- A host name must be letters, digits, `_` and `-` in dot-separated labels, up to 253 characters in all and 63 per label, with no trailing dot.
- A name is resolved once. The first answer that passes the scope is the single address the connection goes to. Nothing is resolved a second time.
- Every redirect hop of an HTTP request is checked again.

### Broadcast

Some protocols, such as Wake on LAN, send one packet to every device on the network instead of to one device. NetProbe allows this only when the tool says so in its description:

```javascript
target: "host", network: "lan", broadcast: true,
```

The rules:

- `broadcast: true` is allowed only with `network: "lan"`. Any other scope with `broadcast: true` is a description error: "A tool can declare broadcast only with the network set to lan".
- A tool that declared it receives `inputs.broadcast`: the directed broadcast address of the phone's current subnet, such as `192.168.1.255`, or an empty string when the network has none (a /31 or /32 network, or no network). The script cannot work the address out for itself, so use this input. The name `broadcast` is reserved for such tools.
- The tool may then send UDP datagrams to that address with `ctx.udp.send` or `ctx.udp.exchange`. Nothing else may use it: TCP, HTTP, TLS and DNS calls to the broadcast address are refused, and so is the broadcast address of any other network.
- `255.255.255.255` is always refused, in every scope.
- A run may send at most 50 datagrams to the broadcast address, and each may be at most 1 KB (1,024 bytes). The count is shared by `ctx.udp.send` and `ctx.udp.exchange`. Datagrams to a single device are not counted.
- A tool without the declaration that tries it gets "This tool did not declare broadcast, so it cannot send to the broadcast address".

The review sheet, the Update tools sheet and the tool's About page show "Can send broadcast packets on your network" for such a tool.

## The ctx API

All calls are synchronous: the script waits for each one. Network calls wait for the run's pause and rate limit first. Stop closes every open socket and ends the script.

Many calls take an options object. A missing required option throws "ctx.x needs the option y". A wrong type throws "ctx.x option y must be a number" (or string, boolean, object). Timeouts and sizes outside their range are clamped, not refused.

Bytes in `ctx` are arrays of whole numbers from 0 to 255. Wherever a call takes bytes, you may pass a string instead; it is used as UTF-8.

### http

```javascript
ctx.http.request({ method, url, headers, body, timeoutMs, followRedirects })  // returns a response
ctx.http.many(requests, concurrency)                                          // returns an array of responses
```

Options of `request`:

| Option | Default | Notes |
| --- | --- | --- |
| `url` | required | Must start with `http://` or `https://`. No user name, backslash, space or control character. Up to 8,192 characters. |
| `method` | `"GET"` | Letters only, up to 16. |
| `headers` | none | Object of name to string or number. Up to 64 headers, values up to 8,192 characters. `Content-Length`, `Transfer-Encoding` and `Connection` are not allowed. You may set `Host`. |
| `body` | none | String or bytes, up to 1 MB. |
| `timeoutMs` | 5000 | 100 to 60000. |
| `followRedirects` | `false` | Follows up to 5 hops. A 303, or a 301 or 302 after POST, changes the method to GET. Each hop is scope-checked and counts as a call. |

The response:

| Field | Meaning |
| --- | --- |
| `status` | Status code, a number. |
| `statusText` | Reason phrase. |
| `headers` | Object of name to value. Repeated names are joined with `, `. |
| `headerList` | Array of `{name, value}` in the order received. |
| `body` | Body as text. Bad UTF-8 becomes U+FFFD. |
| `bodyBytes` | The first 100,000 bytes of the body, as bytes. |
| `bodyBytesTruncated` | `true` if the body was cut at 1 MB, or `bodyBytes` was cut at 100,000. |
| `url` | The final URL, after redirects. |
| `timeMs` | Time taken. |

An HTTP error status such as 404 or 500 is a normal response, not an exception. Connection failures throw. For `https://`, NetProbe reads the server's certificate but does not check it, so self-signed devices work.

`many` takes an array of the same option objects. `concurrency` defaults to 1 and is at most 16. Results come back in order. A request that failed gives `{status: 0, error: "..."}`. If the scope refused any request, `many` throws after all have finished. Each request counts as one call.

```javascript
var r = ctx.http.request({ url: "http://" + inputs.ip + "/", headers: { "User-Agent": "netprobe" } });
ctx.log("status " + r.status);
```

### tcp

```javascript
ctx.tcp.exchange({ host, port, send, timeoutMs, readUntil, maxBytes, tls, sni })  // returns a reply
ctx.tcp.connect({ host, port, timeoutMs, tls, sni })                              // returns a handle
```

`host` and `port` are required. `send` and `readUntil` are a string or bytes. `maxBytes` defaults to 4096 and is clamped to 1 to 100,000. `timeoutMs` defaults to 5000 (100 to 60000). `tls` defaults to `false`. `sni` sets the server name for TLS.

`exchange` connects, sends `send`, reads until `readUntil` is seen, `maxBytes` arrive, the server closes or the timeout passes, then closes the connection. The reply:

| Field | Meaning |
| --- | --- |
| `bytes` | What was received, as bytes. |
| `text` | The same, as text. |
| `closed` | `true` if the server closed the connection. |
| `timeMs` | Time taken. |

```javascript
var r = ctx.tcp.exchange({ host: inputs.ip, port: inputs.port, send: "HELP\r\n", readUntil: "\n", timeoutMs: 3000 });
ctx.finding("PROBE", r.text);
```

`connect` returns a handle for a longer conversation. At most 8 handles may be open at once; one more throws "At most 8 connections can be open at once". Whatever is still open when the run ends is closed for you.

```javascript
var h = ctx.tcp.connect({ host: inputs.ip, port: 25 });                      // returns a handle
h.send("EHLO test\r\n");                                                      // string or bytes
var banner = h.recv({ maxBytes: 1024, timeoutMs: 2000, readUntil: "\n" });   // same reply shape as exchange
h.close();
```

Using a handle after `close()` throws "The connection is closed".

### udp

```javascript
ctx.udp.exchange({ host, port, send, timeoutMs, maxBytes })  // returns a reply
ctx.udp.send({ host, port, send })                           // sends one datagram, returns nothing
```

`exchange` sends one datagram and waits for one reply. `host` and `port` are required. `maxBytes` defaults to 4096. The reply is `{bytes, text, timedOut, timeMs}`. If nothing came back, `timedOut` is `true` and `bytes` is empty. No answer is not an error.

```javascript
var r = ctx.udp.exchange({ host: inputs.ip, port: 161, send: ctx.bytes.fromHex("30 00"), timeoutMs: 1500 });
if (!r.timedOut) { ctx.finding("PROBE", ctx.bytes.toHex(r.bytes)); }
```

`send` sends one datagram and returns at once, without waiting for an answer. It takes `host`, `port` and `send` (a string or bytes), and obeys the same scope rules as every other call. Use it for protocols that never answer, such as Wake on LAN. It counts as one network call, and a datagram to the broadcast address also counts against the broadcast limits (see "Broadcast").

```javascript
ctx.udp.send({ host: inputs.broadcast, port: 9, send: packet });   // a tool that declared broadcast
```

### tls

```javascript
ctx.tls.certificate(host, port, sni)  // returns a certificate report
```

Connects with TLS and reads the certificate. `sni` is optional. Left out, a host name is used as the server name and an address is not. The wait is 5 seconds. The port must be a whole number from 1 to 65535. The report is `{protocol, cipherSuite, leaf, chain}`. `leaf` is the server's own certificate and `chain` is an array of every certificate it sent. Each certificate has:

| Field | Meaning |
| --- | --- |
| `subject`, `issuer` | Distinguished names, as text. |
| `sans` | Subject alternative names. |
| `notBefore`, `notAfter` | Validity dates as milliseconds since 1970 (pass one to `new Date(n)`). |
| `keyAlgorithm`, `keyBits` | Public key type and size. |
| `signatureAlgorithm` | Signature type. |
| `serial` | Serial number. |
| `sha256`, `sha1` | Fingerprints. |
| `selfSigned` | `true` if the certificate signed itself. |

```javascript
var c = ctx.tls.certificate(inputs.ip, 443);
ctx.finding("CERT_SUBJECT", c.leaf.subject, c.leaf.issuer);
```

### dns

```javascript
ctx.dns.lookup(name)   // returns an array of address strings
ctx.dns.reverse(ip)    // returns a name, or null
```

`lookup` uses the system resolver. `reverse` needs an IP address and throws "Reverse lookup needs an IP address" for a name. Both obey the DNS rules in "Targets and network scope". A failed or timed-out lookup throws "The DNS lookup failed" or "The DNS lookup timed out".

```javascript
var addresses = ctx.dns.lookup("router.lan");
addresses.forEach(function (a) { ctx.finding("address", a, "router.lan"); });
```

### devices

```javascript
ctx.devices()  // an array of devices, newest scan first
```

Returns the devices NetProbe has discovered on the network the phone is on, as plain objects `{ip, mac, name, vendor, ports}`. `mac` is lower-case with colons, or an empty string when unknown. `name` is the name the app shows for the device (its IP address when it has none). `vendor` is an empty string when unknown. `ports` is an array of `{port, service, tls}` for the open ports found by a port scan: `service` is the service name the app recognised, `tls` is `true` when the app saw TLS on that port. The list holds at most 1,024 devices, ordered by when each was last seen, newest first. Devices that were only added by hand as a tool target are left out.

Only tools with `network: "lan"` or `network: "any"` may call it. Under `network: "target"` it throws "ctx.devices needs a tool with the network set to lan or any". A call counts as one host call. It reads what the app already knows; it sends nothing on the network. A device still has to be inside the tool's scope before the script contacts it, so a `"lan"` tool is the natural fit.

```javascript
// target: "none", network: "lan"
ctx.devices().forEach(function (d) {
  d.ports.forEach(function (p) {
    if (p.port === 80) { ctx.finding("address", d.name, d.ip + ":80"); }
  });
});
```

### web

```javascript
ctx.web.snapshot({ url, timeoutMs, width, settleMs })  // returns {status, httpStatus, title, finalUrl, image, error}
```

Opens the page in a hidden web view inside NetProbe, waits for it to load, and takes a picture of it. `url` is required and must be `http://` or `https://`. `timeoutMs` defaults to 15,000 and is clamped to 1,000 to 60,000. `width` is the width of the page in pixels as the page sees it; it defaults to 1,024 and is clamped to 320 to 2,048. The picture is scaled down to at most 640 pixels wide and saved as a JPEG.

`settleMs` is how long to wait after the page has loaded before the picture is taken, so scripts on the page can finish drawing. It defaults to 2,500 and is clamped to 0 to 10,000; it is cut short if the overall timeout would run out. The web view is as tall as 1,600 pixels. A picture that is one flat colour gets one more settle period, and if it is still flat the page counts as having drawn nothing.

The reply:

| Field | Meaning |
| --- | --- |
| `status`, `httpStatus` | The HTTP status of the page itself, 0 when it never answered. |
| `error` | Why there is no picture: "The page answered with HTTP 503", "The page could not be loaded: ..." or "The page drew nothing". Otherwise `null`. |
| `title` | The page title, up to 200 characters. |
| `finalUrl` | The address the page ended on after redirects. |
| `image` | An image id, or `null` when the page failed (an HTTP status of 400 or more, or a network error) or drew nothing. It is not a file name: pass it to `ctx.finding` as `extra.image` (see "finding"). A page with no picture writes nothing and counts against no limit. |

The page runs JavaScript. Cookies, local storage, pop-up windows, downloads, file and content addresses are all off. The page is fetched with the app's normal user agent, and certificate errors are accepted, as they are for `ctx.http`.

The same network scope applies as for `ctx.http`. The address is checked before the page is fetched, every redirect hop is checked again, and so is every image, script and other resource the page asks for. A page that redirects outside the scope throws, and the same error messages are used. A resource outside the scope is simply left out of the picture.

Limits: one picture is drawn at a time, a run may keep at most 200 pictures and 20 MB of them, and each picture counts against the run's transfer budget. A snapshot counts as one host call. Errors a script can catch include "The page took too long to load", "The page could not be loaded: ...", "Page snapshots can use at most 20 MB in one run" and "At most 200 page snapshots can be taken in one run".

The pictures are kept with the run in Scans and are deleted when the run is deleted. Removing a tool does not delete its earlier runs, so it keeps their pictures.

```javascript
// target: "none", network: "lan"
var shot = ctx.web.snapshot({ url: "http://192.168.1.20:8080/", timeoutMs: 10000 });
ctx.finding("page", shot.title, "HTTP " + shot.status, shot.finalUrl, { image: shot.image });
```

### bytes

```javascript
ctx.bytes.fromHex(text)          // bytes; spaces and ":" are ignored
ctx.bytes.toHex(bytes)           // lower-case hex string
ctx.bytes.fromUtf8(text)         // bytes
ctx.bytes.toUtf8(bytes)          // string; bad sequences become U+FFFD
ctx.bytes.concat(a, b, ...)      // bytes
ctx.bytes.u16be(n)               // number to 2 bytes, big-endian
ctx.bytes.u32be(n)               // number to 4 bytes, big-endian
ctx.bytes.u16le(n)               // number to 2 bytes, little-endian
ctx.bytes.u32le(n)               // number to 4 bytes, little-endian
ctx.bytes.readU16be(bytes, offset)   // bytes to number; also readU32be, readU16le, readU32le
ctx.bytes.base64(bytes)          // standard base64 text with padding
ctx.bytes.fromBase64(text)       // bytes; white space, missing padding and the URL-safe alphabet are accepted
ctx.bytes.md5(bytes)             // lower-case hex digest; also sha1 and sha256
```

`offset` defaults to 0. `base64`, `md5`, `sha1` and `sha256` take bytes or a string (used as UTF-8). Errors: "Not a valid hex string" (odd length or a non-hex character), "Not a valid base64 string", "The number must be a whole number from 0 to N" (a value that does not fit), "Read past the end of the bytes".

HTTP Basic authentication is a base64 header, and Digest authentication is built from `md5`:

```javascript
var basic = "Basic " + ctx.bytes.base64(user + ":" + pass);
var ha1 = ctx.bytes.md5(user + ":" + realm + ":" + pass);
```

```javascript
var packet = ctx.bytes.concat(ctx.bytes.u16be(1), ctx.bytes.fromHex("0100"), ctx.bytes.fromUtf8("hi"));
var id = ctx.bytes.readU16be(packet, 0);
```

### regex

Regular expression literals and `RegExp` do not exist in tool scripts. Use `ctx.regex`, which runs a step-limited engine.

```javascript
ctx.regex.test(pattern, text, flags)                  // true or false
ctx.regex.exec(pattern, text, flags)                  // null, or {match, index, groups}
ctx.regex.matchAll(pattern, text, flags, limit)       // array of {match, index, groups}; limit defaults to 1000
ctx.regex.replace(pattern, text, replacement, flags)  // string, every match replaced
ctx.regex.split(pattern, text, limit)                 // array of strings
```

`pattern` and `text` are strings. The pattern is at most 1,000 characters. `flags` is an optional string of `i` (ignore case), `m` (multiline) and `s` (dot matches newline); `split` takes none. `groups` is an array of the captured groups, with `null` for a group that did not take part. In `replace`, `$1` to `$9`, `$&` and `$$` work in the replacement.

Supported syntax: literals, `.`, classes such as `[a-z]` (put a `-` first or last to mean a hyphen, as in `[-:. ]`), `\d \w \s \b` and their negations, `^ $`, groups `( )`, non-capturing `(?: )`, lookahead `(?= )` and `(?! )`, alternation `|`, quantifiers `* + ? {n} {n,} {n,m}` with a lazy `?` form, and back references `\1` to `\9`. Named groups and look-behind are not supported.

A pattern that backtracks past 5,000,000 steps throws "The regular expression took too many steps". An invalid pattern throws "The regular expression is not valid". A bad flag throws "Unknown regex flag x, use i, m or s".

Double the backslashes in a JavaScript string: `"\\d+"`.

```javascript
var m = ctx.regex.exec("Server: ([^\\r\\n]+)", r.body, "i");
if (m) { ctx.finding("STACK", m.groups[0]); }
```

### wordlist

```javascript
ctx.wordlist(name, offset, limit)  // returns an array of lines
```

`name` is the name of a `wordlist` input. `offset` defaults to 0. `limit` defaults to 100,000 and is clamped to 1 to 100,000. If `name` is not a wordlist input, it throws "No wordlist input named x". Read big lists in pages.

```javascript
var offset = 0;
while (!ctx.cancelled()) {
  var words = ctx.wordlist("words", offset, 500);
  if (words.length === 0) { break; }
  offset += words.length;
  // use the words here
}
```

### progress

```javascript
ctx.progress(done, total, found)
```

Updates the progress shown in the app. `total` may be `null` when it is not known. Values are rounded down to whole numbers of 0 or more, and anything that is not a number counts as 0.

```javascript
ctx.progress(i, words.length, hits);
```

### finding

```javascript
ctx.finding(kind, value, detail, url, extra)
```

Records one result. `kind` is a string. `value` is the main text; a value that is not text is turned into text. `detail`, `url` and `extra` are optional. A `url` must start with `http://` or `https://`, or the call throws "A finding link must start with http:// or https://". See "Findings" for the kinds and how they look.

`extra` is an object. Only `extra.image` is used: the image id returned by `ctx.web.snapshot` in this run. The finding then shows the picture. An id the run was not given throws "The image was not made by ctx.web.snapshot in this run".

```javascript
ctx.finding("PATH", "/admin", "401", "http://" + inputs.ip + "/admin");
```

### log

```javascript
ctx.log(text)
```

Writes a line to the run's log. The log is for debugging. It is kept only when the run fails: the last 20 lines then appear in the results, labelled `log`. A line is cut at 500 characters, and only the last 200 lines are held.

### sleep

```javascript
ctx.sleep(ms)
```

Pauses the script for `ms` milliseconds, from 0 to 60,000. Stop still works while the script sleeps.

### cancelled

```javascript
ctx.cancelled()  // true or false
```

Returns `true` after the user taps Stop, and also after a budget has been hit. It never throws. Use it to leave a loop politely. If your script returns normally after `cancelled()` turned `true`, the run still ends as stopped or failed.

```javascript
for (var i = 0; i < 1000 && !ctx.cancelled(); i++) { /* work */ }
```

## Findings

A finding is one line in the results. It has a kind, a value, an optional detail and an optional link.

| Field | Limits | How it shows |
| --- | --- | --- |
| `kind` | Up to 64 characters | A built-in kind shows no label. Any other kind shows as a small label above the value. |
| `value` | Cut at 2,048 characters | The main line. Tap to expand, long press to copy. |
| `detail` | Cut at 2,048 characters | A muted line under the value. |
| `url` | Cut at 2,048 characters; must be http or https | An Open button that opens the link. |
| `extra.image` | An image id from `ctx.web.snapshot` in this run | The finding is shown as a thumbnail row: the picture on the left, the value as its caption, the detail under it. Tap it to see the picture full size, with Copy URL and Open in browser for the `url`. Share writes the picture's path after the url. The picture stays with the run in Scans. |

Built-in kinds (exact capitals, no label shown): `PATH`, `STATUS`, `HEADER`, `COOKIE`, `TITLE`, `GENERATOR`, `ROBOTS`, `SITEMAP`, `SECURITY_TXT`, `ERROR_PAGE`, `FRAMEWORK`, `CERT_SUBJECT`, `CERT_ISSUER`, `CERT_SAN`, `CERT_VALIDITY`, `CERT_KEY`, `CERT_SIGNATURE`, `CERT_FINGERPRINT`, `CERT_CHAIN`, `TLS_SESSION`, `FLAG`, `SMB_DIALECT`, `SMB_SIGNING`, `SMB_SESSION`, `SMB_HOST`, `SNMP_SYSTEM`, `SNMP_INTERFACE`, `SNMP_ADDRESS`, `PROBE`, `STACK` and `OTHER`.

Any other string, such as `"address"` or `"name"` in `dns-lookup.js`, is stored as an Other finding and shown with your text as its label. Kinds are case-sensitive: `"probe"` is a custom kind and `"PROBE"` is built in. The kind `HINT` is kept for the app's own use. If a script writes it, it is stored as an Other finding labelled `HINT`.

Findings are saved as they are written, so a run that later fails keeps the findings it made.

The run's summary is separate from the findings. If your script returns `{status, summary}`, the summary is shown (up to 300 characters). `status` is `"done"` or `"failed"`; any other value fails the run. If the script returns nothing, or no status, the run is done and the summary is "N findings".

## Limits and what happens when you hit them

Fixed limits of the language and the API:

| Limit | Value |
| --- | --- |
| Script file size | 256 KB |
| Length of any string | 1,000,000 characters |
| Length of any array | 100,000 elements |
| Bytes in one request body or `send` | 1 MB |
| HTTP body returned | 1 MB (`bodyBytes`: first 100,000 bytes) |
| Data passed from your script to `ctx` | Nested up to 8 levels, up to 2,000 keys per object, up to 200,000 values in all |
| Open `tcp.connect` handles | 8 |
| Page snapshots in one run | 200 pictures and 20 MB, each at most 640 pixels wide |
| Datagrams to the broadcast address in one run | 50, each at most 1 KB |
| HTTP redirects followed | 5 |
| `http.many` concurrency | 16 |
| Call timeouts | 100 ms to 60,000 ms |
| Function call depth | 300 |

Budgets for one run:

| Budget | Value | Message when exceeded |
| --- | --- | --- |
| Wall-clock time | 10 minutes | Script ran for more than 10 minutes |
| Network calls | 20,000 (each request of `http.many` and each redirect hop counts) | Script made more than 20,000 network calls |
| Data transferred | 50 MB, sent and received | Script transferred more than 50 MB |
| Findings | 5,000 | Script produced more than 5,000 findings |
| Finding size | 2 MB in all | Script findings are larger than 2 MB |
| Processing | About 4 billion interpreter instructions | Script used too much processing |
| Memory | The heap is 85% full | Script used too much memory |
| Silence | 30 seconds with no interpreter activity and no host call | Script stopped responding |

What happens:

- **A budget is hit.** The run ends at once as FAILED with the message above. The script cannot catch this, and `finally` blocks do not run. Open sockets are closed. Findings already saved stay. If the script is still polling, `ctx.cancelled()` returns `true`.
- **A call-level limit is hit.** String, array, size and argument limits throw an ordinary `Error` or `RangeError` that your script can catch.
- **Findings come too fast.** After a burst of 50, findings are limited to 200 a second. The script is slowed down, not failed.
- **Stop is tapped.** The run ends as Stopped and sockets are closed.
- **The script throws.** The run ends FAILED. The summary is the error message, with the line number added when it is known.
- **Too many scripts at once.** If 8 scripts are already running or stuck, a new one fails with "Too many scripts are running or stuck, try again later".
- **Reading the description.** Import evaluates your file for at most 2 seconds, with no `ctx`.

## Language notes

The engine runs ES5 only. Write plain, old-style JavaScript.

Available:

- `var`, functions, closures, `try`, `catch`, `finally`, `throw`.
- `Object.keys`, `Array.prototype.forEach`, `map`, `filter`, `reduce`, `indexOf`, `join` and the other ES5 array methods.
- `JSON`, `Math`, `Date`, `parseInt`, `parseFloat`, `encodeURIComponent` and the other standard globals.
- String methods. `split` and `replace` take plain strings only. `replace` takes a string replacement, not a function.

Not available:

- `let`, `const`, arrow functions, classes and template strings.
- `Promise`, `async` and `await`, `Map`, `Set`, `Symbol` and typed arrays.
- Regular expression literals (`/abc/`) and `new RegExp(...)`. Use `ctx.regex`. Using a pattern with `match`, `search`, `split` or `replace` throws "RegExp is not available in tools, use ctx.regex".
- `eval` and `Function(...)`. They throw "eval and Function are not available in tools".
- Java access. `java`, `Packages`, `JavaAdapter`, `importClass` and `getClass` do not exist, and no Java object ever reaches your script. Anything that tries throws "Java objects are not available in tools".
- `__proto__`, `__parent__`, `__defineGetter__`, `__defineSetter__`, `__lookupGetter__`, `__lookupSetter__`, `watch`, `unwatch` and `toSource`.
- Timers. There is no `setTimeout`. Use `ctx.sleep`.
- Passing a function to a `ctx` call. It throws "A function cannot be passed to the host".

The `ctx` object and its groups are read-only. You cannot replace `ctx.http.request`.

## Importing and updating a tool

### Import

Open Settings, then Tools, then Import tool. Choose one of two ways.

- **From file.** Pick a `.js` file on the phone. The file can be at most 1 MB and must be valid UTF-8 text. The script in it can be at most 256 KB.
- **From a web address.** Type an `https://` address and tap Fetch. The address must be https and name a public host. Plain http, addresses with a user name and local hosts are refused. Each redirect must also be https on a public host, with at most 5 redirects. The whole fetch must finish within 20 seconds. The file is fetched once, when you tap Fetch, and never again.

NetProbe then reads the description only. It does not run your tool. It shows a review sheet:

| Row | Meaning |
| --- | --- |
| Author | The `author` text, marked unverified, or "Not given (unverified)". Anyone can write any author. |
| Needs | What the tool is run against: a port, a device or nothing. |
| Network | How far it may reach. A `lan` tool shows "This tool can contact addresses on your network". An `any` tool shows "This tool can contact any address". |
| Broadcast | A tool that declared `broadcast` shows "Can send broadcast packets on your network". |
| Inputs | Each input as "label, type", with "optional" where it applies. |
| SHA-256 | The fingerprint of the script. |
| View source | Shows the full script, with its line and character counts. |

The sheet states: "Importing only reads the description. The script runs when you run the tool." Tap Import to save, or Cancel. Nothing is saved before that. Imported tools are listed under My tools, where you can also remove them. A removed tool's earlier runs stay in Scans.

### Replace and update

A tool's identity is its `id`. Importing a file whose `id` is already installed offers to replace that tool, with a Replace button. To update a tool, change the file, raise `version`, and import it again. The sheet shows what changes:

- "Replaces NAME 1.0 with 1.1".
- Whether the script is identical to the installed one.
- The network scope, unchanged or changed.
- The SHA-256 before and after.
- New, removed and changed inputs.

Version rules:

- A version older than the installed one is refused: "Version 1.0 is older than the installed version 1.1, so it was not imported". The same version is allowed, so you can re-import a fixed file.
- Versions are compared piece by piece. Runs of digits compare as numbers, so `1.10` is newer than `1.9`, and `1.0` equals `1.0.0`. Runs of letters compare as lower-case text and sort before a number or a missing piece, so `1.0-beta` is older than `1.0`.
- A broken installed tool (for example, its file went missing) does not block a new file.

Scope rules:

- If the new version reaches further than the installed one (the order is `target`, then `lan`, then `any`), the sheet says "This version reaches further than the installed one" and Replace works only after you tick "I allow it to contact ...".
- If the installed tool changes while you are reviewing, nothing is saved and you must review again.

### The catalog

The NetProbe repository holds a folder of community tools, `catalog/`, with an `index.json` that lists them. To get them, open Tools, tap the menu and choose "Update tools from GitHub" (the same action is in Settings, under Tools). NetProbe fetches the index only when you tap it; nothing is ever fetched in the background.

NetProbe compares the index with what is installed, by tool id and SHA-256, fetches only the scripts that are new or different, and reads each one the way an import does. One sheet then shows:

| List | What it holds |
| --- | --- |
| New | Tools you do not have: name, version, network scope, and "Can send broadcast packets on your network" where it applies. |
| Updated | Tools you have, with a different script: "name 1.0 to 1.1". A scope that reaches further, or a broadcast permission the installed copy lacked, is shown in the warning colour. A tool you imported yourself with the same id shows "Replaces your copy". |
| Failed | A tool that could not be read or may not be installed, with the reason: the server could not be reached, the script does not match the catalog, the description has errors, or the version is older than the installed one. |

The sheet also says how many tools are up to date. If any update widens a scope or newly declares broadcast, tick "I allow the changes marked above" to enable Install. Tap Install to save every New and Updated tool, or Cancel to save none. A tool removed from the catalog stays on your phone until you remove it.

To contribute a tool, see `catalog/README.md`: write the script with this guide, run `tools/make-catalog-index.ps1`, and open a pull request. `catalog/wake-on-lan.js` is a worked example: a host tool with `network: "lan"` and `broadcast: true`.

### Examples

The three bundled tools are installed under Examples with the keys `example:http-headers` and so on. A file you import with the same `id` is a separate tool, kept under My tools.

## Troubleshooting

### Import messages

| Message | What it means |
| --- | --- |
| The file could not be opened | The phone could not read the file you picked. |
| The file is larger than 1 MB | Too big to import. The script itself may be at most 256 KB. |
| The file is empty | There is nothing in the file. |
| The script file is not valid UTF-8 text | The file is not plain text. Save it as UTF-8. |
| The script is larger than 256 KB | The script is too large. |
| That is not a web address | The text you typed is not a URL. |
| The address must start with https:// and name a public host | The URL is http, has a user name or points to a local host. |
| The address, or a redirect, is not an https address on a public host | The address or a redirect is not allowed. |
| Too many redirects | More than 5 redirects. |
| The server did not return the file | The server answered with an error status. |
| Could not reach the server | No connection, or the connection failed. |
| The server took longer than 20 seconds | The fetch ran out of time. |
| The script does not define a tool object | The file never assigns `tool = { ... }`. |
| The tool needs a run function | `run` is missing or is not a function. |
| The script could not be read: ... | Reading the description failed, for example it ran for more than 2 seconds. |
| Something went wrong while reading the tool | An unexpected error. |
| Version X is older than the installed version Y, so it was not imported | Raise `version` to at least the installed one. |
| Confirm the wider network access to replace the tool | The replacement reaches further. Tick the confirmation box. |
| The installed tool changed while you were reviewing, review it again | Import the file again. |
| The tool could not be saved | The phone could not store it. |

A script error during import shows as the engine's own message with a line number. A syntax error, such as a `let` or an arrow function, appears this way.

### Description messages

All problems are listed together. Input messages name the input by its name or position.

| Message | Fix |
| --- | --- |
| The tool object has no id / name / target | Add the field. |
| The id must be 3 to 40 characters from a to z, 0 to 9 and - | Use lower case, digits and dashes. |
| The version must be 1 to 20 letters, digits, dots, dashes or underscores | Clean the version. |
| The name can be at most 40 characters | Shorten the field. The limit is in the message. |
| The name must be text | The field is blank or not a string. |
| The target must be port, host or none | Fix `target`. |
| The network must be target, lan or any | Fix `network`. |
| The category must be web, network, identity or other | Fix `category`. |
| The inputs must be a list | `inputs` must be an array. |
| A tool can have at most 24 inputs | Remove some. |
| Input N must be an object | Each entry must be `{ ... }`. |
| Input N needs a name that starts with a letter and uses letters, digits and _ | Rename the input. |
| Input x has an unknown type | Use a type from the table in "Inputs". |
| Input x needs a label | Add `label`. |
| Input x: the help can be at most 200 characters | Shorten it. The same form of message covers `label` and `placeholder`. |
| The default of input x does not fit its type | Fix `default`. |
| Input x needs 1 to 12 different options, as text or as value and label | Fix `options`. |
| Input x has a minimum above its maximum | Swap `min` and `max`. |
| Input x: the field has the wrong kind of value | Use the right kind, such as a whole number or `true`/`false`. |
| The input name x is reserved | Rename it. `ip`, `port`, `tls` and `confirmAny` are always reserved. `mac` is reserved except as a text input of a host tool, and `broadcast` is reserved in a tool that declares broadcast. |
| The input name x is used twice | Give each input its own name. |
| The broadcast field must be true or false | Write `broadcast: true` or leave it out. |
| A tool can declare broadcast only with the network set to lan | Set `network: "lan"` or remove `broadcast`. |

### Catalog messages

| Message | What it means |
| --- | --- |
| The catalog could not be read | The index is not valid JSON, has the wrong format number, or has no tools list. |
| Could not reach the server / The server did not return the file / The server took longer than 20 seconds | The index or a script could not be fetched. Tap Try again. |
| The script does not match the catalog, try again later | The script's SHA-256 differs from the index line, often because GitHub has not caught up yet. Nothing from that line is installed. |
| The script's id is not the one the catalog lists | The `id` inside the script is not the one in `index.json`. |
| Version X is older than the installed version Y, so it was not imported | The catalog has an older version than the one on the phone. |
| Line N, or a tool id, followed by a reason | A line of `index.json` was left out: a missing field, a bad id, file name, version, SHA-256 or network, or a broadcast tool whose network is not lan. |

### Run messages

A failed run shows "Failed: " and then one of these.

| Message | What it means |
| --- | --- |
| This tool can contact any address and needs your confirmation for each run | A network `"any"` tool was started without the confirmation. |
| The inputs are not valid: ... | A form value broke a rule. |
| This tool needs a connected network | A `"lan"` tool, and the phone has no network. |
| Stopped | You tapped Stop. |
| Script returned the status X, expected done or failed | The returned `status` was something else. |
| Script ran for more than 10 minutes | Wall-clock budget. |
| Script made more than 20,000 network calls | Call budget. |
| Script transferred more than 50 MB | Transfer budget. |
| Script produced more than 5,000 findings | Findings budget. |
| Script findings are larger than 2 MB | Finding size budget. |
| Script used too much processing | Instruction budget, usually an endless loop. |
| Script used too much memory | Memory budget. |
| Script stopped responding | The script was silent for 30 seconds. |
| Script nests too deeply | Functions call each other too deeply. |
| Script engine error: ... | An internal error. |

Any other message is your own script's error text, with "(line N)" added.

### Errors your script can catch

These are thrown as a normal `Error`, so `try` and `catch` work.

| Message | Cause |
| --- | --- |
| This tool can only contact the device it was run against | A `"target"` tool used another address. |
| This tool can only contact the device it is run against, and none was chosen | A `"target"` tool with target `"none"`. Use `lan` or `any`. |
| This tool can only contact addresses on the current network | A `"lan"` tool used an outside address. |
| This address cannot be contacted by a tool | Refused in every scope: loopback, multicast, broadcast, reserved space, this phone, or a network or broadcast address. |
| The name does not lead to an address this tool may contact | The name resolved only to addresses outside the scope. |
| This tool can only look up the name of the device it was run against | DNS under `"target"`. |
| This tool can only look up names on the local network | DNS under `"lan"`. |
| Write IP addresses in the usual form, such as 192.168.1.10 | A decimal, octal, hex, short or mapped address. |
| Not a valid host name or address | A malformed host. |
| The port must be a number from 1 to 65535 | A bad port. |
| This tool did not declare broadcast, so it cannot send to the broadcast address | Add `broadcast: true` (with `network: "lan"`) to the description. |
| The broadcast address can only be used with ctx.udp.send and ctx.udp.exchange | A TCP, HTTP, TLS or DNS call used the broadcast address. |
| At most 50 broadcast packets can be sent in one run | The broadcast limit. |
| A broadcast packet can be at most 1 KB | The datagram was over 1,024 bytes. |
| ctx.x needs the option y | A required option is missing. |
| ctx.x option y must be a number | An option has the wrong type. |
| ctx.x option y must be a whole number from A to B | A port or similar is out of range. |
| ctx.x expects an options object | You passed something other than `{ ... }`. |
| ctx.x argument N must be a string | A wrong positional argument. |
| ctx.x y must be a string or an array of numbers from 0 to 255 | A bad bytes value. |
| ctx.x y is larger than 1 MB | Too much data in one call. |
| The HTTP method must be letters only | A bad `method`. |
| The header x is not allowed | A forbidden or malformed header. |
| At most 64 headers can be sent | Too many headers. |
| The URL must start with http:// or https:// | A bad URL scheme. |
| Not a valid URL: ... | A malformed URL. |
| The URL host must not contain a user name, a backslash, spaces or control characters | A bad URL host. |
| The URL port must be a number from 1 to 65535 | A bad URL port. |
| The URL path must not contain spaces, backslashes or control characters | A bad URL path. |
| Too many redirects | More than 5 hops. |
| Network error: SocketTimeoutException, ... | An I/O failure such as a timeout or a refused connection. The class name says which. |
| The DNS lookup failed / The DNS lookup timed out | A DNS problem. |
| The connection is closed | You used a handle after `close()`. |
| At most 8 connections can be open at once | Too many handles. |
| Invalid string length: the limit is 1,000,000 characters | A string grew too big. |
| Invalid array length: the limit is 100,000 elements | An array grew too big. |
| The value is too large / The value is nested too deeply | Data passed to `ctx` is over the limits. |
| The value must be text, a number, true or false, an array or an object | An unsupported value was passed to `ctx`. |
| A function cannot be passed to the host | A function was passed to `ctx`. |
| The regular expression is not valid / The regular expression took too many steps | See "regex". |
| No wordlist input named x | See "wordlist". |
| Not a valid hex string / Not a valid base64 string | See "bytes". |
| The page took too long to load / The page could not be loaded: x / The page could not be shown | A page snapshot failed. See "web". |
| Page snapshots are not available | The app has no web view to draw with. |
| At most 200 page snapshots can be taken in one run / Page snapshots can use at most 20 MB in one run | The snapshot limits. |
| The snapshot could not be saved | The phone could not write the picture. |
| The image was not made by ctx.web.snapshot in this run | `extra.image` is not an id returned by `ctx.web.snapshot` in this run. |
| ctx.devices needs a tool with the network set to lan or any | See "devices". |
| A finding link must start with http:// or https:// | See "finding". |

### Tips

- Add `ctx.log` lines while you test. If the run fails, the last 20 appear in the results.
- Test on a device you own first, with `network: "target"`.
- Raise `version` each time you change the file, so the app lets you replace the old one.
- Input names `ip`, `port` and `tls` are taken by the target, so a tool that needs its own port uses a name such as `udpPort`.
