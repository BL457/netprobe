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
