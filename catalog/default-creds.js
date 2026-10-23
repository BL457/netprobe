// Default credentials: tries well-known factory default logins against a device's web admin page.
// A port tool: the app asks for a device and a port, and gives the script inputs.ip, inputs.port and inputs.tls.
// Read-only: it stops at the first request that proves a login works, and never changes anything on the device.
tool = {
  id: "default-creds", name: "Default credentials", version: "1.0", author: "NetProbe",
  description: "Tries well-known factory default logins against a device's web admin page.",
  icon: "lock", category: "web",
  target: "port", network: "target",
  inputs: [
    { name: "scheme", type: "choice", label: "Scheme", options: ["http", "https"], required: false,
      help: "Left blank, this follows the port's own HTTP or HTTPS choice." },
    { name: "path", type: "text", label: "Path", default: "/", required: false, placeholder: "/" },
    { name: "pairs", type: "list", label: "Extra user:password pairs", required: false, placeholder: "admin:letmein, root:toor",
      help: "Tried before the built-in defaults." },
    { name: "maxAttempts", type: "number", label: "Maximum attempts", default: 12, min: 1, max: 50,
      help: "A lockout guard: at most this many logins are tried in one run." },
    { name: "stopAtFirst", type: "bool", label: "Stop at first success", default: true },
    { name: "formLogin", type: "bool", label: "Also try HTML form logins", default: true }
  ],
  run: function (inputs, ctx) {
    // ---- small helpers the sandbox does not provide ----
    var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    function base64(text) {
      var bytes = ctx.bytes.fromUtf8(text);
      var out = "";
      for (var i = 0; i < bytes.length; i += 3) {
        var b0 = bytes[i], b1 = (i + 1 < bytes.length) ? bytes[i + 1] : 0, b2 = (i + 2 < bytes.length) ? bytes[i + 2] : 0;
        var triple = (b0 << 16) | (b1 << 8) | b2;
        out += B64.charAt((triple >> 18) & 0x3F);
        out += B64.charAt((triple >> 12) & 0x3F);
        out += (i + 1 < bytes.length) ? B64.charAt((triple >> 6) & 0x3F) : "=";
        out += (i + 2 < bytes.length) ? B64.charAt(triple & 0x3F) : "=";
      }
      return out;
    }
    function header(resp, name) {
      var keys = Object.keys(resp.headers);
      for (var i = 0; i < keys.length; i++) { if (keys[i].toLowerCase() === name) { return resp.headers[keys[i]]; } }
      return null;
    }
    function hasSetCookie(resp) {
      for (var i = 0; i < resp.headerList.length; i++) { if (resp.headerList[i].name.toLowerCase() === "set-cookie") { return true; } }
      return false;
    }
    function attrValue(tag, attr) {
      var m = ctx.regex.exec(attr + "\\s*=\\s*[\"']([^\"']*)[\"']", tag, "i");
      return m ? m.groups[0] : null;
    }
    // Compares a Location header against the login path, coping with an absolute or a relative URL.
    function samePath(loc, loginPath) {
      var p = loc;
      var abs = ctx.regex.exec("^https?://[^/]+(/[^?#]*)?", loc, "i");
      if (abs) { p = abs.groups[0] || "/"; }
      var cut = p.indexOf("?"); if (cut >= 0) { p = p.substring(0, cut); }
      cut = p.indexOf("#"); if (cut >= 0) { p = p.substring(0, cut); }
      return p === loginPath;
    }

    // ---- inputs ----
    var scheme = inputs.scheme || (inputs.tls ? "https" : "http");
    var path = inputs.path || "/";
    if (path.charAt(0) !== "/") { path = "/" + path; }
    var base = scheme + "://" + inputs.ip + ":" + inputs.port;
    var url = base + path;
    var maxAttempts = inputs.maxAttempts || 12;
    var stopAtFirst = inputs.stopAtFirst !== false;
    var formLogin = inputs.formLogin !== false;

    // ---- vendor default tables, well-known factory defaults only ----
    var FINGERPRINTS = [
      { name: "NETGEAR", creds: [["admin", "password"], ["admin", "1234"]],
        test: function (s) { return ctx.regex.test("netgear", s.all, "i"); } },
      { name: "TP-Link", creds: [["admin", "admin"]],
        test: function (s) { return ctx.regex.test("tp-link|tplink", s.all, "i"); } },
      { name: "D-Link", creds: [["admin", ""], ["admin", "admin"]],
        test: function (s) { return ctx.regex.test("d-link|dlink", s.all, "i"); } },
      { name: "Linksys", creds: [["admin", "admin"], ["", "admin"]],
        test: function (s) { return ctx.regex.test("linksys", s.all, "i"); } },
      { name: "Zyxel", creds: [["admin", "1234"]],
        test: function (s) { return ctx.regex.test("zyxel", s.all, "i"); } },
      { name: "MikroTik RouterOS", creds: [["admin", ""]],
        test: function (s) { return ctx.regex.test("mikrotik|routeros", s.all, "i"); } },
      { name: "Ubiquiti", creds: [["ubnt", "ubnt"]],
        test: function (s) { return ctx.regex.test("ubiquiti|ubnt", s.all, "i"); } },
      { name: "Hikvision", creds: [["admin", "12345"], ["admin", "admin"]],
        test: function (s) { return ctx.regex.test("hikvision", s.all, "i"); } },
      { name: "Dahua", creds: [["admin", "admin"]],
        test: function (s) { return ctx.regex.test("dahua", s.all, "i"); } },
      { name: "Axis", creds: [["root", "pass"]],
        test: function (s) { return ctx.regex.test("axis", s.all, "i"); } },
      { name: "Synology", creds: [["admin", "admin"]],
        test: function (s) { return ctx.regex.test("synology|diskstation", s.all, "i"); } },
      { name: "QNAP", creds: [["admin", "admin"]],
        test: function (s) { return ctx.regex.test("qnap", s.all, "i"); } },
      { name: "HP printer", creds: [["admin", "admin"], ["admin", ""]],
        test: function (s) { return ctx.regex.test("hp jetdirect|hp laserjet|hp officejet", s.all, "i"); } }
    ];
    var GENERIC_CREDS = [["admin", "admin"], ["admin", "password"], ["admin", ""], ["root", "root"], ["admin", "12345"], ["", ""]];

    // ---- one GET to read the challenge ----
    var r0 = ctx.http.request({ method: "GET", url: url, timeoutMs: 5000 });
    ctx.progress(0, 1, 0);
    if (ctx.cancelled()) { return { status: "done", summary: "Stopped" }; }

    var wwwAuth = header(r0, "www-authenticate");
    var titleMatch = ctx.regex.exec("<title>([^<]*)</title>", r0.body, "i");
    var title = titleMatch ? titleMatch.groups[0] : "";
    var serverHeader = header(r0, "server") || "";
    var realm = "";
    var authStyle = "unknown";

    if (wwwAuth) {
      var realmMatch = ctx.regex.exec("realm\\s*=\\s*\"([^\"]*)\"", wwwAuth, "i");
      realm = realmMatch ? realmMatch.groups[0] : "";
      var isBasic = ctx.regex.test("basic", wwwAuth, "i");
      var isDigest = ctx.regex.test("digest", wwwAuth, "i");
      if (isBasic) { authStyle = "basic"; }
      else if (isDigest) { authStyle = "digest"; }
    }

    // look for an HTML login form regardless of status, unless a basic/digest challenge already explains the 401
    var form = null;
    if (authStyle === "unknown") {
      var body = r0.body.length > 60000 ? r0.body.substring(0, 60000) : r0.body;
      var forms = ctx.regex.matchAll("<form\\b[^>]*>[\\s\\S]*?</form>", body, "i", 10);
      for (var fi = 0; fi < forms.length && !form; fi++) {
        var block = forms[fi].match;
        if (ctx.regex.test("type\\s*=\\s*[\"']password[\"']", block, "i")) { form = block; }
      }
      if (form) {
        authStyle = "form";
      } else if (r0.status >= 200 && r0.status < 300) {
        authStyle = "open";
      }
    }

    var all = serverHeader + " " + title + " " + realm;
    var fingerprint = null;
    for (var fp = 0; fp < FINGERPRINTS.length; fp++) {
      if (FINGERPRINTS[fp].test({ all: all })) { fingerprint = FINGERPRINTS[fp]; break; }
    }

    // ---- nothing to try ----
    if (authStyle === "open") {
      ctx.finding("PATH", path, "Responded " + r0.status + " with no authentication challenge found", url);
      ctx.progress(1, 1, 0);
      return { status: "done", summary: "No login challenge found; page answered " + r0.status };
    }
    if (authStyle === "digest") {
      ctx.finding("OTHER", "Digest authentication, realm \"" + realm + "\"", "Not tried: the sandbox has no MD5 to compute a Digest response", url);
      ctx.progress(1, 1, 0);
      return { status: "done", summary: "Digest authentication realm \"" + realm + "\"; not tried" };
    }
    if (authStyle === "form" && !formLogin) {
      ctx.finding("OTHER", "HTML login form found", "Not tried: \"Also try HTML form logins\" is off", url);
      ctx.progress(1, 1, 0);
      return { status: "done", summary: "Login form found; not tried (form logins disabled)" };
    }
    if (authStyle === "unknown") {
      ctx.finding("OTHER", "No recognisable login challenge", "Status " + r0.status + ", no WWW-Authenticate header and no password field found", url);
      ctx.progress(1, 1, 0);
      return { status: "done", summary: "Could not tell how this page logs in" };
    }

    // ---- form details, if that is the style ----
    var formAction = path, userField = null, passField = null, hidden = [];
    if (authStyle === "form") {
      var action = attrValue(form, "action");
      if (action) { formAction = action; }
      var pwdTag = ctx.regex.exec("<input\\b[^>]*type\\s*=\\s*[\"']password[\"'][^>]*>", form, "i");
      if (pwdTag) { passField = attrValue(pwdTag.match, "name"); }
      var inputTags = ctx.regex.matchAll("<input\\b[^>]*>", form, "i", 50);
      for (var it = 0; it < inputTags.length; it++) {
        var tag = inputTags[it].match;
        var tType = (attrValue(tag, "type") || "text").toLowerCase();
        var tName = attrValue(tag, "name");
        if (!tName) { continue; }
        if (tType === "password") { continue; }
        if (tType === "hidden") { hidden.push([tName, attrValue(tag, "value") || ""]); continue; }
        if (!userField && (tType === "text" || tType === "email")) { userField = tName; }
      }
      if (!passField || !userField) {
        ctx.finding("OTHER", "Login form found but its fields could not be read", "", url);
        ctx.progress(1, 1, 0);
        return { status: "done", summary: "Login form found but its fields could not be read" };
      }
      if (formAction.indexOf("http://") !== 0 && formAction.indexOf("https://") !== 0) {
        if (formAction.charAt(0) !== "/") { formAction = path.substring(0, path.lastIndexOf("/") + 1) + formAction; }
        formAction = base + formAction;
      }
    }

    // ---- build the candidate list: the user's pairs first, then the vendor table or the generic one ----
    var candidates = [];
    var seen = {};
    function add(user, pass) {
      var key = user + "\u0000" + pass;
      if (seen[key]) { return; }
      seen[key] = true;
      candidates.push([user, pass]);
    }
    if (inputs.pairs) {
      for (var p = 0; p < inputs.pairs.length; p++) {
        var raw = inputs.pairs[p];
        var sep = raw.indexOf(":");
        if (sep < 0) { continue; }
        add(raw.substring(0, sep), raw.substring(sep + 1));
      }
    }
    var table = fingerprint ? fingerprint.creds : GENERIC_CREDS;
    for (var g = 0; g < table.length; g++) { add(table[g][0], table[g][1]); }
    if (candidates.length > maxAttempts) { candidates.length = maxAttempts; }

    // ---- try each candidate ----
    var findingsMade = 0;
    var attempts = 0;
    var lockedOut = false;
    for (var i = 0; i < candidates.length && attempts < maxAttempts && !ctx.cancelled(); i++) {
      var user = candidates[i][0], pass = candidates[i][1];
      var resp;
      if (authStyle === "basic") {
        resp = ctx.http.request({ method: "GET", url: url, headers: { "Authorization": "Basic " + base64(user + ":" + pass) }, timeoutMs: 5000 });
      } else {
        var body = encodeURIComponent(userField) + "=" + encodeURIComponent(user) + "&" + encodeURIComponent(passField) + "=" + encodeURIComponent(pass);
        for (var h = 0; h < hidden.length; h++) { body += "&" + encodeURIComponent(hidden[h][0]) + "=" + encodeURIComponent(hidden[h][1]); }
        resp = ctx.http.request({ method: "POST", url: formAction, headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: body, timeoutMs: 5000 });
      }
      attempts++;
      ctx.progress(attempts, candidates.length, findingsMade);

      if (resp.status === 429 || ctx.regex.test("locked|too many (attempts|requests)|exceeded|try again later", resp.body || "", "i")) {
        ctx.finding("OTHER", "The device started answering with a lockout", "Status " + resp.status + " after " + attempts + " attempt(s)", url);
        lockedOut = true;
        break;
      }

      var success = false;
      if (resp.status >= 200 && resp.status < 300) {
        success = true;
      } else if (resp.status >= 300 && resp.status < 400) {
        var loc = header(resp, "location");
        if (hasSetCookie(resp) && (!loc || !samePath(loc, path))) { success = true; }
      }
      if (success) {
        var style = authStyle === "basic" ? "HTTP Basic authentication" : "HTML form login";
        ctx.finding("CREDENTIAL", user + " / " + pass, style + " at " + url, url);
        findingsMade++;
        if (stopAtFirst) { break; }
      }
    }

    ctx.progress(attempts, candidates.length, findingsMade);
    var fpNote = fingerprint ? " (" + fingerprint.name + " defaults)" : "";
    if (lockedOut) { return { status: "done", summary: findingsMade + " found, then locked out after " + attempts + " attempt(s)" + fpNote }; }
    if (findingsMade === 0) { return { status: "done", summary: "No default credentials worked in " + attempts + " attempt(s)" + fpNote }; }
    return { status: "done", summary: findingsMade + " working credential(s) found in " + attempts + " attempt(s)" + fpNote };
  }
};
