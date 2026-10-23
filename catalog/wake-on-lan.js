// Wake on LAN: sends a magic packet so a sleeping device on your network wakes up.
// A host tool: the app asks for a device and gives the script inputs.ip and inputs.mac (the device's MAC address, or "" when it is unknown).
// It declares broadcast, so it also gets inputs.broadcast, the broadcast address of your network, and may send packets to it.
tool = {
  id: "wake-on-lan", name: "Wake on LAN", version: "1.0", author: "NetProbe",
  description: "Sends a magic packet to wake a sleeping device on your network.",
  icon: "bolt", category: "network",
  target: "host", network: "lan", broadcast: true,
  inputs: [
    { name: "mac", type: "text", label: "MAC address", placeholder: "aa:bb:cc:dd:ee:ff",
      help: "Filled in from the device when its MAC address is known." },
    { name: "udpPort", type: "number", label: "UDP port", default: 9, min: 1, max: 65535,
      help: "Wake on LAN normally uses port 9. Some devices listen on port 7." },
    { name: "repeat", type: "number", label: "Times to send", default: 3, min: 1, max: 10 }
  ],
  run: function (inputs, ctx) {
    var digits = ctx.regex.replace("[-:. ]", inputs.mac, "");
    if (!ctx.regex.test("^[0-9a-f]{12}$", digits, "i")) {
      throw new Error("The MAC address must be 12 hex digits, such as aa:bb:cc:dd:ee:ff");
    }
    if (!inputs.broadcast) {
      throw new Error("This network has no broadcast address");
    }
    var pairs = [];
    for (var p = 0; p < 12; p += 2) { pairs.push(digits.substring(p, p + 2).toLowerCase()); }
    var shown = pairs.join(":");

    // The magic packet: six bytes of 0xFF, then the MAC address 16 times.
    var mac = ctx.bytes.fromHex(digits);
    var packet = ctx.bytes.fromHex("ffffffffffff");
    for (var i = 0; i < 16; i++) { packet = ctx.bytes.concat(packet, mac); }

    var sent = 0;
    while (sent < inputs.repeat && !ctx.cancelled()) {
      ctx.udp.send({ host: inputs.broadcast, port: inputs.udpPort, send: packet });
      sent++;
      ctx.progress(sent, inputs.repeat, 0);
      if (sent < inputs.repeat) { ctx.sleep(100); }
    }
    var times = sent === 1 ? "1 time" : sent + " times";
    ctx.finding("wake", "Magic packet sent to " + shown + " (" + times + ")", "to " + inputs.broadcast + " port " + inputs.udpPort);
    ctx.progress(sent, inputs.repeat, 1);
    return { status: "done", summary: "Sent to " + shown };
  }
};
