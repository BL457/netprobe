// DNS lookup: resolves a name to addresses, or an IPv4 address back to a name.
// No device is involved (target none). It can ask for any DNS name, so it declares network "any": the app asks you to confirm each run.
tool = {
  id: "dns-lookup", name: "DNS lookup", version: "1.0", author: "NetProbe",
  description: "Looks up a name or an address with the system resolver.",
  icon: "dns", category: "network",
  target: "none", network: "any",
  inputs: [
    { name: "name", type: "text", label: "Name or address", placeholder: "example.com or 192.168.1.1" }
  ],
  run: function (inputs, ctx) {
    var query = inputs.name.trim();
    if (query.length === 0) {
      return { status: "failed", summary: "Nothing to look up" };
    }
    // Regular expressions are not built in; ctx.regex is the safe replacement.
    if (ctx.regex.test("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$", query, "")) {
      var host = ctx.dns.reverse(query);
      ctx.progress(1, 1, host === null ? 0 : 1);
      if (host === null) {
        return { status: "done", summary: "No name found" };
      }
      ctx.finding("name", host, query);
      return { status: "done", summary: host };
    }
    var addresses = ctx.dns.lookup(query);
    ctx.progress(1, 1, addresses.length);
    addresses.forEach(function (address) {
      ctx.finding("address", address, query);
    });
    return { status: "done", summary: addresses.length + (addresses.length === 1 ? " address" : " addresses") };
  }
};
