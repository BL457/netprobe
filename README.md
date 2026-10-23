# NetProbe

NetProbe is an Android network scanner. It finds the devices on your network, identifies their services, and runs tools against them.

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
