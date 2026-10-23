// Banner grab: connects to the chosen port, optionally sends a line of text, and shows what the service answers.
// Shows raw TCP (ctx.tcp.exchange) and byte helpers (ctx.bytes).
tool = {
  id: "banner-grab", name: "Banner grab", version: "1.0", author: "NetProbe",
  description: "Connects to a port and shows what the service says first.",
  icon: "terminal", category: "network",
  target: "port", network: "target",
  inputs: [
    { name: "send", type: "text", label: "Send first", required: false, placeholder: "Leave empty to just listen",
      help: "A line of text sent right after connecting." },
    { name: "waitMs", type: "number", label: "Wait (ms)", default: 3000, min: 200, max: 15000, required: false }
  ],
  run: function (inputs, ctx) {
    var send = inputs.send ? inputs.send + "\r\n" : "";
    var r = ctx.tcp.exchange({
      host: inputs.ip, port: inputs.port, send: send,
      timeoutMs: inputs.waitMs || 3000, maxBytes: 2048, readUntil: "\n", tls: inputs.tls
    });
    ctx.progress(1, 1, 0);
    if (r.bytes.length === 0) {
      return { status: "done", summary: "Nothing received" };
    }
    var firstLine = r.text.split("\n")[0].replace("\r", "");
    ctx.finding("PROBE", firstLine, r.bytes.length + " bytes in " + r.timeMs + " ms");
    ctx.finding("PROBE", ctx.bytes.toHex(r.bytes.slice(0, 32)), "first bytes, hex");
    return { status: "done", summary: r.bytes.length + " bytes" };
  }
};
