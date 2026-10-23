# Writing NetProbe tools (JavaScript)

A tool is one JavaScript file. NetProbe reads it once to learn what the tool needs (the `tool` object), draws the form from that, and calls
`tool.run(inputs, ctx)` each time the user taps Run. The file runs in a sandbox: it cannot reach Java, the file system, the clipboard or any
Android API. It can only use `ctx`, listed below.

## The language: ES5

The engine is Mozilla Rhino in interpreted mode. Write plain ES5 and your tool behaves the same on every Rhino version the app may ship.

- Available: `var`, functions, closures, `Object.keys`, `Array.prototype.forEach/map/filter/reduce/indexOf`, `JSON`, `Math`, `Date`, `String` methods
  (`split` and `replace` take plain strings only), `parseInt`, `parseFloat`, `encodeURIComponent` and friends, `try/catch/finally`.
- Not available: `let`, `const`, arrow functions, classes, template strings, `Promise`, `Map`, `Set`, `Symbol`, typed arrays.
- Removed on purpose: `eval`, `Function(...)`, `RegExp` and regex literals (use `ctx.regex`), `java`, `Packages`, `JavaAdapter`, `importClass`,
  `getClass`, `__proto__`, `__parent__`, `__defineGetter__` and its relatives.
- Limits: a string holds at most 1,000,000 characters and an array at most 100,000 elements; a larger result throws a `RangeError`. The script
  file is at most 256 KB. A run ends FAILED after 10 minutes, 20,000 network calls, 50 MB transferred, or 5,000 findings.

## The tool object

```javascript
tool = {
  id: "http-headers", name: "HTTP headers", version: "1.0", author: "BL457",
  description: "Fetches / and lists the response headers.",
  icon: "web", category: "web",            // icon: web, search, lock, folder, share, dns, terminal, fingerprint, network, wifi, info, bolt
  target: "port", network: "target",       // target: port, host or none; network: target, lan or any
  inputs: [
    { name: "path", type: "text", label: "Path", default: "/", required: false }
  ],                                       // ip, port and tls are implicit for a port tool and arrive as inputs.ip, inputs.port, inputs.tls
  run: function (inputs, ctx) {
    var r = ctx.http.request({ method: "GET", url: (inputs.tls ? "https" : "http") + "://" + inputs.ip + ":" + inputs.port + inputs.path, timeoutMs: 4000 });
    ctx.progress(1, 1, 0);
    Object.keys(r.headers).forEach(function (h) { ctx.finding("HEADER", h + ": " + r.headers[h]); });
    return { status: "done", summary: r.status + " " + r.statusText };
  }
};
```

- `id`: 3 to 40 characters of `a-z`, `0-9` and `-`. `name` up to 40 characters, `description` up to 200. `version` is free text up to 20 characters.
- `category`: `web`, `network`, `identity` or `other` (other lands under My tools).
- `target`: `port` (the user picks a device and a port), `host` (a device), `none` (no device). `ip` and `port` are filled in for you; a port tool also
  receives `inputs.tls`, true when the user chose HTTPS.
- `network` is how far the script may reach:
  - `target` (default): only the device the user picked. DNS lookups work for that device's own name only.
  - `lan`: any address inside the current network's subnet, except this phone, the network address, the broadcast address and multicast.
  - `any`: any unicast address, except loopback, multicast, broadcast, reserved ranges and this phone. The user is asked to confirm on every run.
  A call outside the scope throws an `Error`. IP addresses must be written in the canonical form (`192.168.1.10`); decimal, octal, hex and
  IPv4-mapped IPv6 spellings are refused, and every redirect hop is checked again.
- `inputs`: each has `name`, `type`, `label`, optional `help` (up to 200 characters), `required` (default true) and `default`. Types: `text`
  (`placeholder`), `number` (`min`, `max`), `bool`, `choice` (`options`: up to 12 strings or `{value, label}`), `multichoice`, `list` (comma separated
  text, you get an array), `wordlist`, `secret` (masked, never saved), `ip`, `port` and `tls`. The names `ip`, `port`, `tls` and `confirmAny` are
  reserved. Up to 24 inputs.
- `run(inputs, ctx)` returns `{status: "done" or "failed", summary}`. A thrown error ends the run FAILED with its message. Returning nothing means done.

## ctx

All calls are synchronous. Network calls wait for the run's pause and rate limit first; Stop closes every open socket and ends the script.

- `ctx.http.request({method, url, headers, body, timeoutMs, followRedirects})` returns `{status, statusText, headers, headerList, body, bodyBytes,
  bodyBytesTruncated, url, timeMs}`. `url` must be `http://` or `https://` with no user name. `body` is a string or an array of bytes. `headers` in the
  result is an object (repeated names are joined with `, `); `headerList` is an array of `{name, value}` in order. The body text is cut at 1 MB;
  `bodyBytes` carries at most the first 100,000 bytes. `timeoutMs` is 100 to 60,000 (default 5,000). `followRedirects` follows up to 5 hops.
- `ctx.http.many(requests, concurrency)` runs up to 16 requests at once and returns the results in order. A request that failed has `status` 0 and an
  `error` message. Each request counts as one call.
- `ctx.tcp.exchange({host, port, send, timeoutMs, readUntil, maxBytes, tls, sni})` returns `{bytes, text, closed, timeMs}`. `send` and `readUntil` are a
  string or bytes. `maxBytes` defaults to 4,096 and is at most 100,000.
- `ctx.tcp.connect({host, port, timeoutMs, tls, sni})` returns a handle with `send(data)`, `recv({maxBytes, timeoutMs, readUntil})` and `close()`. At most
  8 handles are open at once.
- `ctx.udp.exchange({host, port, send, timeoutMs, maxBytes})` returns `{bytes, text, timedOut, timeMs}`.
- `ctx.tls.certificate(host, port, sni)` returns `{protocol, cipherSuite, leaf, chain}`; each certificate has `subject`, `issuer`, `sans`, `notBefore`,
  `notAfter`, `keyAlgorithm`, `keyBits`, `signatureAlgorithm`, `serial`, `sha256`, `sha1`, `selfSigned`.
- `ctx.dns.lookup(name)` returns an array of address strings; `ctx.dns.reverse(ip)` returns a name or null. With `network: "target"` only the target's own
  name resolves; with `lan` only names that look local (one label, or ending in .local, .lan, .home, .internal, .localdomain, .home.arpa). A name is looked up
  once per call and the connection goes to the one address that passed the scope check.
- `ctx.bytes`: `fromHex`, `toHex`, `fromUtf8`, `toUtf8`, `concat(a, b, ...)`, `u16be(n)`, `u32be(n)`, `u16le(n)`, `u32le(n)` (each returns bytes) and
  `readU16be(bytes, offset)`, `readU32be`, `readU16le`, `readU32le`. Bytes are arrays of numbers 0 to 255.
- `ctx.regex`: `test(pattern, text, flags)`, `exec(pattern, text, flags)` (null or `{match, index, groups}`), `matchAll(pattern, text, flags, limit)`,
  `replace(pattern, text, replacement, flags)` (replaces every match; `$1` to `$9`, `$&` and `$$` work in the replacement) and `split(pattern, text, limit)`.
  `flags` is any of `i`, `m`, `s`. The engine understands the usual JavaScript syntax: classes, `\d \w \s \b`, groups, `(?:...)`, `(?=...)`, `(?!...)`, back
  references, greedy and lazy quantifiers; not named groups or look-behind. A pattern that takes more than 5,000,000 steps throws an `Error`.
- `ctx.wordlist(name, offset, limit)` returns lines of the wordlist chosen in input `name` (at most 100,000 per call); `inputs[name]` is
  `{id, name, entries}`.
- `ctx.progress(done, total, found)` (`total` may be null), `ctx.finding(kind, value, detail, url)`, `ctx.log(text)`, `ctx.sleep(ms)` (up to 60,000) and
  `ctx.cancelled()`.
- Findings: `kind` is one of the app's kinds (`PATH`, `STATUS`, `HEADER`, `COOKIE`, `TITLE`, `PROBE`, `CERT_SUBJECT` and the rest of the list in the
  results sheet, except `HINT`, which the app keeps for itself) or any string you like, which is shown as Other with your text. `value` and `detail` are cut at 2,048 characters; `url` must be
  `http` or `https`. All findings of one run together may hold 2 MB.
- `ctx.log` lines are kept only when the run fails.

## Examples

`http-headers.js`, `banner-grab.js` and `dns-lookup.js` ship with the app under Examples.
