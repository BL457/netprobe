// Web gallery: takes a picture of the web page on every device on your network that serves one.
// A tool with no target: it walks the devices NetProbe has already found (ctx.devices) and opens each web port in a hidden web view
// (ctx.web.snapshot). Run a port scan first so the devices have their open ports. Read-only: it only loads the pages.
tool = {
  id: "web-gallery", name: "Web gallery", version: "1.0", author: "NetProbe",
  description: "Takes a picture of the web page of every device on your network.",
  icon: "web", category: "web",
  target: "none", network: "lan",
  inputs: [
    { name: "ports", type: "list", label: "Ports", required: false,
      default: "80,443,8080,8443,8000,8008,8081,8096,8123,9000,5000,5001,32400",
      help: "Web ports to look at, separated by commas." },
    { name: "onlyOpen", type: "bool", label: "Only ports found open", default: true,
      help: "On: only ports that a port scan found open. Off: try every port in the list on every device, which is slow." },
    { name: "timeoutS", type: "number", label: "Seconds per page", default: 10, min: 3, max: 30 },
    { name: "maxPages", type: "number", label: "Most pages", default: 60, min: 1, max: 200,
      help: "The run stops after trying this many pages." }
  ],
  run: function (inputs, ctx) {
    var DEFAULT_PORTS = "80,443,8080,8443,8000,8008,8081,8096,8123,9000,5000,5001,32400";
    var HTTPS_PORTS = [443, 8443, 5001];
    var TIME_LIMIT_MS = 9 * 60 * 1000;      // leave the run a minute inside its 10 minute budget
    var started = new Date().getTime();

    function parsePorts(list) {
      var out = [];
      for (var i = 0; i < list.length; i++) {
        var text = String(list[i]);
        if (!ctx.regex.test("^[0-9]{1,5}$", text)) { continue; }
        var n = parseInt(text, 10);
        if (n >= 1 && n <= 65535 && out.indexOf(n) < 0) { out.push(n); }
      }
      return out;
    }
    var wanted = parsePorts(inputs.ports && inputs.ports.length ? inputs.ports : DEFAULT_PORTS.split(","));
    var onlyOpen = inputs.onlyOpen !== false;
    var maxPages = inputs.maxPages || 60;
    var timeoutMs = (inputs.timeoutS || 10) * 1000;

    // The pages to try, in device order: a device and a port at a time.
    var devices = ctx.devices();
    var plan = [];
    for (var d = 0; d < devices.length && plan.length < maxPages; d++) {
      var dev = devices[d];
      if (!dev.ip) { continue; }
      var open = {};
      for (var k = 0; k < dev.ports.length; k++) { open[dev.ports[k].port] = dev.ports[k]; }
      var candidates = [];
      if (onlyOpen) {
        for (var a = 0; a < dev.ports.length; a++) { if (wanted.indexOf(dev.ports[a].port) >= 0) { candidates.push(dev.ports[a].port); } }
      } else {
        candidates = wanted;
      }
      for (var c = 0; c < candidates.length && plan.length < maxPages; c++) {
        var port = candidates[c];
        var record = open[port];
        var https = (record && record.tls) || HTTPS_PORTS.indexOf(port) >= 0;
        var defaultPort = https ? 443 : 80;
        var where = dev.ip + (port === defaultPort ? "" : ":" + port);
        plan.push({ device: dev, port: port, url: (https ? "https://" : "http://") + where + "/", where: where });
      }
    }

    var pages = 0, tried = 0, shownDevices = {}, deviceCount = 0, stoppedBecause = null;
    ctx.progress(0, plan.length, 0);
    for (var p = 0; p < plan.length; p++) {
      if (ctx.cancelled()) { break; }
      if (new Date().getTime() - started > TIME_LIMIT_MS) { stoppedBecause = "time limit"; break; }
      var item = plan[p];
      tried++;
      try {
        var shot = ctx.web.snapshot({ url: item.url, timeoutMs: timeoutMs });
        var name = item.device.name && item.device.name !== item.device.ip ? item.device.name + "  " + item.where : item.where;
        var detail = shot.title ? shot.title : "No title";
        if (shot.status >= 400) { detail += "  HTTP " + shot.status; }
        var link = shot.finalUrl && shot.finalUrl.indexOf("http") === 0 ? shot.finalUrl : item.url;
        ctx.finding("page", name, detail, link, { image: shot.image });
        pages++;
        if (!shownDevices[item.device.ip]) { shownDevices[item.device.ip] = true; deviceCount++; }
      } catch (e) {
        var message = String(e.message);
        if (message === "Page snapshots are not available") { return { status: "failed", summary: message }; }
        ctx.log(item.url + ": " + message);
        // A limit of the run itself (200 pictures or 20 MB): nothing more can be kept.
        if (message.indexOf("Page snapshots can use at most") === 0 || message.indexOf("At most 200 page snapshots") === 0) { stoppedBecause = message; break; }
      }
      ctx.progress(tried, plan.length, pages);
    }
    var summary = pages + (pages === 1 ? " page" : " pages") + " from " + deviceCount + (deviceCount === 1 ? " device" : " devices");
    if (stoppedBecause) { summary += ", stopped: " + stoppedBecause; }
    return { status: "done", summary: summary };
  }
};
