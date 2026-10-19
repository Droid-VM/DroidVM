// Real HTTP/WS/session/channel with a synthetic echo source, never a guest VM.
import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { deadline } from "./server-fixture.mjs";

export async function verifyTerminal({ page, post, fixture, output, expect, require }) {
  const WebSocket = require("ws");
  const base = new URL(page.url()).origin;
  const get = async (path) => {
    const response = await page.request.get(`${base}/api/v1/${path}`);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const session = await get("session");
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id };
  const cookie = (await page.context().cookies(base)).map((item) => `${item.name}=${item.value}`).join("; ");
  const lifecycle = (await get("vms")).items.find((vm) => vm.id === "native-http-vm").lifecycle;
  await post("vm-operations", {
    requestId: "fixture-terminal-start", vmId: "native-http-vm", action: "start",
    expected: { instanceId: lifecycle.instanceId, revision: lifecycle.revision, configRevision: lifecycle.configRevision },
  }, 202);
  await expect(async () => assert.deepEqual((await get("vm-terminals/native-http-vm")).streams, ["uart"]))
    .toPass({ timeout: 8000 });
  assert.equal(await page.evaluate(async () => (await fetch("/api/v1/vm-terminals/native-http-vm", { credentials: "omit" })).status), 401);
  await fixture.command("log-fill");
  await fixture.command("terminal-output");
  const catalog = await get("vm-terminals/native-http-vm");
  assert.equal(catalog.resizeSupported, false);
  const frame = (overrides = {}) => ({
    type: "open", vmId: "native-http-vm", stream: "uart", expected: catalog.expected,
    csrfToken: session.csrfToken, context, cursor: null, ...overrides,
  });
  const sockets = [];
  const connect = (headers = {}, path = "/api/v1/terminal", protocol = "droidvm.terminal.v1") => {
    const socket = new WebSocket(`${fixture.origin.replace("http:", "ws:")}${path}`, protocol, {
      headers: { Host: new URL(base).host, Origin: base, Cookie: cookie, ...headers },
    });
    sockets.push(socket);
    const state = { socket, messages: [], closed: false, status: null };
    state.opened = deadline(new Promise((resolve, reject) => {
      socket.once("open", resolve);
      socket.once("unexpected-response", (_request, response) => { state.status = response.statusCode; response.resume(); reject(new Error("Upgrade rejected")); });
      socket.once("error", reject);
    }));
    // Rejection is also observable through status, without unhandled promises.
    state.opened.catch(() => {});
    socket.on("message", (data) => state.messages.push(JSON.parse(data.toString())));
    socket.on("close", () => { state.closed = true; });
    socket.on("error", () => {});
    state.send = (value) => socket.send(JSON.stringify(value));
    state.next = async (type) => {
      await expect.poll(() => state.messages.some((item) => item.type === type), { timeout: 6000 }).toBe(true);
      return state.messages.splice(state.messages.findIndex((item) => item.type === type), 1)[0];
    };
    return state;
  };
  try {
    const anonymous = connect({ Cookie: "" });
    await assert.rejects(anonymous.opened);
    if (anonymous.status !== null) assert.equal(anonymous.status, 401);
    assert.notEqual(anonymous.socket.readyState, WebSocket.OPEN);
    const foreign = connect({ Origin: "http://untrusted.invalid" });
    await assert.rejects(foreign.opened);
    if (foreign.status !== null) assert.equal(foreign.status, 403);
    assert.notEqual(foreign.socket.readyState, WebSocket.OPEN);
    for (const denied of [{ csrfToken: "invalid" }, { stream: "agent" }]) {
      const connection = connect(); await connection.opened; connection.send(frame(denied));
      await expect.poll(() => connection.closed).toBe(true);
      assert(!connection.messages.some((item) => item.type === "ready"));
    }
    const raw = connect(); await raw.opened; raw.send(frame());
    await raw.next("ready");
    const initial = await raw.next("output");
    assert.equal(Buffer.from(initial.data, "base64").length, 64 << 10);
    assert(Buffer.from(initial.data, "base64").includes(Buffer.from("\x1b[31m红色终端\x1b[0m\r\n中文输出")));
    const bytes = Buffer.from([0, 255, 195, 169, 27, 91, 65]);
    raw.send({ type: "input", sequence: "1", data: bytes.toString("base64") });
    assert.equal((await raw.next("ack")).accepted, bytes.length);
    await fixture.command("log pending-output-marker");
    // Until the client acknowledges rendering, the server retains only one
    // output cursor and must not emit a second chunk even while input arrives.
    await new Promise((resolve) => setTimeout(resolve, 350));
    assert.equal(raw.messages.filter((item) => item.type === "output").length, 0);
    raw.send({ type: "ackOutput", generation: initial.generation, offset: initial.next });
    const echoed = await raw.next("output");
    const echoedBytes = Buffer.from(echoed.data, "base64");
    assert(echoedBytes.includes(bytes) && echoedBytes.includes(Buffer.from("pending-output-marker")));
    raw.send({ type: "ackOutput", generation: echoed.generation, offset: echoed.next });
    await fixture.command("terminal-partial");
    const partial = { type: "input", sequence: "2", data: Buffer.from("abcdef").toString("base64") };
    raw.send(partial); assert.equal((await raw.next("ack")).accepted, 2);
    const beforeReplay = await fixture.command("terminal-stats");
    assert.equal(beforeReplay.writes, 2);
    assert.deepEqual(Buffer.from(beforeReplay.input, "base64"), Buffer.concat([bytes, Buffer.from("ab")]));
    raw.send(partial); await expect.poll(() => raw.closed).toBe(true);
    assert.equal((await fixture.command("terminal-stats")).writes, beforeReplay.writes);
    const changing = connect(); await changing.opened; changing.send(frame()); await changing.next("ready");
    await fixture.command("terminal-change");
    await expect.poll(() => changing.closed, { timeout: 6000 }).toBe(true);
  } finally {
    for (const socket of sockets) if (socket.readyState !== WebSocket.CLOSED) socket.terminate();
  }

  await fixture.command("log-clear");
  await fixture.command("terminal-output");
  let dropAcknowledgement = false;
  await page.routeWebSocket("**/api/v1/terminal", (socket) => {
    const server = socket.connectToServer();
    server.onMessage((message) => {
      const parsed = JSON.parse(typeof message === "string" ? message : message.toString());
      if (dropAcknowledgement && parsed.type === "ack") {
        dropAcknowledgement = false;
        socket.close({ code: 1011, reason: "fixture lost acknowledgement" });
        server.close();
      } else socket.send(message);
    });
  });
  await page.goto(`${base}/#/vms/native-http-vm/terminal`);
  const connectButton = page.getByTestId("terminal-connect");
  await expect(connectButton).toBeEnabled();
  await connectButton.click();
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "connected");
  await expect(page.getByTestId("terminal-output")).toContainText("中文输出");
  const paste = async (text) => {
    const textarea = page.locator(".xterm-helper-textarea");
    await textarea.focus();
    await textarea.evaluate((element, value) => {
      const data = new DataTransfer(); data.setData("text/plain", value);
      element.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
    }, text);
  };
  const beforePartial = await fixture.command("terminal-stats");
  await fixture.command("terminal-partial");
  await paste("partial-ui");
  await expect(page.getByTestId("terminal-input-uncertain")).toBeVisible();
  await expect(page.getByTestId("terminal-input-discarded")).toBeVisible();
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "disconnected");
  const afterPartial = await fixture.command("terminal-stats");
  assert.equal(afterPartial.writes, beforePartial.writes + 1);
  assert.equal(afterPartial.accepted, beforePartial.accepted + 2);
  await connectButton.click();
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "connected");
  assert.equal((await fixture.command("terminal-stats")).writes, afterPartial.writes);
  // Lose only the input receipt after native execution. Explicit reconnection
  // may restore output, but must never replay that input or its partial suffix.
  dropAcknowledgement = true;
  await paste("lost-ack-ui");
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "disconnected");
  await expect(page.getByTestId("terminal-input-uncertain")).toBeVisible();
  const afterLost = await fixture.command("terminal-stats");
  assert.equal(afterLost.writes, afterPartial.writes + 1);
  await expect(connectButton).toBeEnabled(); await connectButton.click();
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "connected");
  await expect(page.getByTestId("terminal-output")).toContainText("lost-ack-ui");
  assert.equal((await fixture.command("terminal-stats")).writes, afterLost.writes);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: join(output, "terminal-320.png") });
  const beforeReplace = await get("vm-terminals/native-http-vm");
  await fixture.command("terminal-replace");
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "disconnected");
  assert.notEqual((await get("vm-terminals/native-http-vm")).expected.instanceId, beforeReplace.expected.instanceId);
  // A small real-events regression shares the listener with the new terminal
  // protocol. Revocation must close both existing authenticated channels.
  await connectButton.click();
  await expect(page.getByTestId("terminal-status")).toHaveAttribute("data-state", "connected");
  const events = connect({}, "/api/v1/events", "droidvm.events.v1");
  const revokedTerminal = connect();
  try {
    await events.opened;
    events.send({ type: "subscribe", context, csrfToken: session.csrfToken, cursor: null });
    await events.next("ready");
    await fixture.command("vm-event");
    assert((await events.next("invalidate")).resources.includes("vms"));
    await revokedTerminal.opened;
    revokedTerminal.send(frame({ expected: (await get("vm-terminals/native-http-vm")).expected }));
    await revokedTerminal.next("ready");
    const beforeRevoke = await fixture.command("terminal-stats");
    await fixture.command(`revoke ${session.id}`);
    await expect.poll(() => revokedTerminal.closed && events.closed, { timeout: 6000 }).toBe(true);
    await expect(page.locator("#pairing-token")).toBeVisible();
    assert.equal((await fixture.command("terminal-stats")).writes, beforeRevoke.writes);
  } finally {
    for (const connection of [events, revokedTerminal])
      if (connection.socket.readyState !== WebSocket.CLOSED) connection.socket.terminate();
  }
  writeFileSync(join(output, "terminal.json"), JSON.stringify({
    passed: true, width: 320, authenticationAndOrigin: true, csrfAndAgentRejected: true,
    rawBytesAndUtf8Ansi: true, outputAcknowledgementBackpressure: true,
    partialAcceptedWithoutReplay: true, lostInputReceiptWithoutReplay: true,
    lifecycleAndInstanceChangesClose: true, eventsProtocolRegression: true, revocationClosesBothProtocols: true,
    rawOutputChunkBytes: 64 << 10, nativeWrites: afterLost.writes,
    execution: "synthetic echo source; real HTTP/WS/auth/channel/frontend; no guest process",
  }, null, 2));
}
