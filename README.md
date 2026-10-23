# NetProbe

NetProbe is an Android network scanner. It finds the devices on your network, identifies their services, and runs tools against them.

Install the APK from the [latest release](https://github.com/BL457/netprobe/releases/latest). The app checks for new versions itself and installs them with one tap. If NetProbe is useful to you, you can [buy me a coffee](https://ko-fi.com/blastdav).

## Writing your own tools

You can add tools of your own. A tool is one JavaScript file (ES5, run in a sandbox). Import it from a file or an https address, and it appears next to the built-in tools. This one lists the HTTP headers of a web server:

```javascript
tool = {
  id: "my-headers", name: "My headers", target: "port", network: "target",
  run: function (inputs, ctx) {
    var r = ctx.http.request({ url: "http://" + inputs.ip + ":" + inputs.port + "/" });
    Object.keys(r.headers).forEach(function (h) { ctx.finding("HEADER", h + ": " + r.headers[h]); });
    return { status: "done", summary: r.status + " " + r.statusText };
  }
};
```

The full guide, with every field and every `ctx` function, is in [docs/tools-scripting.md](docs/tools-scripting.md).

## Tool catalog

The [`catalog/`](catalog) folder holds community tools, starting with Wake on LAN. In the app, Tools > menu > "Update tools from GitHub" (also in Settings > Tools) fetches the catalog index when you tap it, shows which tools are new or updated and what access they ask for, and installs them with one tap. Nothing is fetched until you ask, and a tool you remove from the catalog stays on your phone. To add a tool, see [catalog/README.md](catalog/README.md).
