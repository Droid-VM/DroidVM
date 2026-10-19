// Real HTTP/snapshots/tasks, in-memory configuration persistence substitute.
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { startFixture } from "./server-fixture.mjs";
import {
  NetworkConfigPage,
  NetworkConfigStatus,
  NetworkConfigAppend,
  ConfigUpload,
  ConfigEmpty,
  TaskResponse,
} from "../../../webui/src/generated/validators.js";
const require = createRequire(
  new URL("../../../webui/package.json", import.meta.url),
);
const { parse, stringify } = require("lossless-json");
const { values } = parseArgs({
  options: {
    exe: { type: "string" },
    output: { type: "string", default: "build/webui-native" },
  },
});
assert(values.exe, "--exe is required");
mkdirSync(values.output, { recursive: true });
const output = mkdtempSync(
  join(resolve(values.output), "native-network-configuration-"),
);
const fixture = await startFixture(values.exe);
try {
  const send = (path, body, headers = {}) =>
    fetch(fixture.origin + "/api/v1/" + path, {
      method: "POST",
      headers: {
        Origin: fixture.origin,
        "Content-Type": "application/json",
        ...headers,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(7000),
    });
  assert.equal(
    (await send("network-configuration/read", { snapshotId: null, offset: 0 }))
      .status,
    401,
  );
  const login = await send("session", {
    pairingToken: (await fixture.command("pair")).pairingToken,
  });
  assert.equal(login.status, 201);
  const cookie = login.headers.get("set-cookie").split(";")[0],
    session = await login.json();
  assert(session.server.capabilities.includes("networks.configuration.v1"));
  const context = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const headers = { Cookie: cookie, "X-DroidVM-CSRF": session.csrfToken };
  const post = async (op, input, valid, status = 200) => {
    const response = await send(
      "network-configuration/" + op,
      { ...input, context },
      headers,
    );
    assert.equal(response.status, status, `network ${op}`);
    const result = await response.json();
    if (valid) assert(valid(result), JSON.stringify(valid.errors));
    assert.deepEqual(result.context, context);
    return result;
  };
  assert.equal(
    (
      await send(
        "network-configuration/begin",
        { context, bytes: 10 },
        { Cookie: cookie },
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await send(
        "network-configuration/read",
        {
          context: { ...context, daemonEpoch: "old" },
          snapshotId: null,
          offset: 0,
        },
        headers,
      )
    ).status,
    409,
  );
  assert.equal(
    (
      await send(
        "network-configuration/begin",
        { context, bytes: 10, revision: "fake" },
        headers,
      )
    ).status,
    400,
  );
  const read = async (initial) => {
    let page =
      initial ??
      (await post("read", { snapshotId: null, offset: 0 }, NetworkConfigPage));
    const { snapshotId, total, cursor } = page;
    const chunks = [];
    let bytes,
      seen = 0,
      offset = 0;
    try {
      for (;;) {
        assert.equal(page.snapshotId, snapshotId);
        assert.equal(page.total, total);
        assert.deepEqual(page.cursor, cursor);
        for (const row of page.items) {
          if (seen++ === 0) {
            assert.equal(row.type, "metadata");
            bytes = row.bytes;
          } else {
            assert.equal(row.type, "chunk");
            assert.equal(row.offset, offset);
            const data = Buffer.from(row.data, "base64");
            assert.equal(data.toString("base64"), row.data);
            chunks.push(data);
            offset += data.length;
          }
        }
        if (page.next === null) break;
        assert.equal(page.next, seen);
        page = await post(
          "read",
          { snapshotId, offset: seen },
          NetworkConfigPage,
        );
      }
      assert.equal(seen, total);
      assert.equal(offset, bytes);
      return parse(
        new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)),
      );
    } finally {
      await post("release", { snapshotId }, ConfigEmpty);
    }
  };
  const stage = async (operations) => {
    const bytes = Buffer.from(stringify({ operations }));
    const { uploadId } = await post(
      "begin",
      { bytes: bytes.length },
      ConfigUpload,
    );
    for (let offset = 0; offset < bytes.length; offset += 48 * 1024) {
      const data = bytes.subarray(offset, offset + 48 * 1024);
      const result = await post(
        "append",
        { uploadId, offset, data: data.toString("base64") },
        NetworkConfigAppend,
      );
      assert.equal(result.received, offset + data.length);
    }
    return uploadId;
  };
  const wait = async (requestId) => {
    const deadline = Date.now() + 7000;
    while (Date.now() < deadline) {
      const value = await post("status", { requestId }, NetworkConfigStatus);
      if (
        value.task &&
        ["succeeded", "failed", "cancelled"].includes(value.task.state)
      )
        return value;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error("Network configuration task did not finish");
  };
  const original = (await read()).networks[0];
  assert.equal(original.extension.counter.toString(), "18446744073709551615");
  const changed = {
    ...original,
    name: "网络改名",
    extension: { ...original.extension, padding: "x".repeat(160000) },
  };
  const uploadId = await stage([{ expected: original, config: changed }]);
  // Deliberately discard the accepted receipt, then only query its request ID.
  const receipt = await send(
    "network-configuration/commit",
    { context, uploadId, requestId: "network-save" },
    headers,
  );
  assert.equal(receipt.status, 202);
  await receipt.body.cancel();
  const saved = await wait("network-save");
  assert.equal(saved.task.state, "succeeded");
  assert.equal(saved.version.updatedCount, 1);
  assert.equal(
    (
      await post(
        "commit",
        { uploadId, requestId: "network-save" },
        TaskResponse,
        202,
      )
    ).task.id,
    saved.task.id,
  );
  const fixed = await post(
    "read",
    { snapshotId: null, offset: 0 },
    NetworkConfigPage,
  );
  assert(fixed.next !== null, "large configuration must span pages");
  const second = { ...changed, name: "再次改名" };
  const nextUpload = await stage([{ expected: changed, config: second }]);
  await post(
    "commit",
    { uploadId: nextUpload, requestId: "network-second" },
    TaskResponse,
    202,
  );
  assert.equal((await wait("network-second")).task.state, "succeeded");
  const frozen = await read(fixed);
  assert.equal(frozen.networks[0].name, "网络改名");
  assert.equal(
    frozen.networks[0].extension.counter.toString(),
    "18446744073709551615",
  );
  assert.equal((await read()).networks[0].name, "再次改名");
  const added = { ...original, id: "new-network", name: "新增网络" };
  const staleUpload = await stage([
    { expected: null, config: added },
    { expected: original, config: changed },
  ]);
  await post(
    "commit",
    { uploadId: staleUpload, requestId: "network-stale" },
    TaskResponse,
    202,
  );
  const failed = await wait("network-stale");
  assert.equal(failed.task.state, "failed");
  assert.equal(failed.task.errorCode, "CONFLICT");
  assert.equal(failed.version, null);
  assert.equal(
    (await read()).networks.length,
    1,
    "failed batch must not retain a partial create",
  );
  const createUpload = await stage([{ expected: null, config: added }]);
  await post(
    "commit",
    { uploadId: createUpload, requestId: "network-create" },
    TaskResponse,
    202,
  );
  assert.equal((await wait("network-create")).version.updatedCount, 1);
  assert.equal((await read()).networks.length, 2);
  assert.equal(
    (await post("status", { requestId: "no-record" }, NetworkConfigStatus))
      .task,
    null,
  );
  const vm = await send(
    "vm-configuration/read",
    { context, snapshotId: null, offset: 0 },
    headers,
  );
  assert.equal(vm.status, 200);
  const other = await vm.json();
  assert.equal(
    (
      await send(
        "network-configuration/read",
        { context, snapshotId: other.snapshotId, offset: 1 },
        headers,
      )
    ).status,
    400,
  );
  assert.equal(
    (
      await send(
        "vm-configuration/release",
        { context, snapshotId: other.snapshotId },
        headers,
      )
    ).status,
    200,
  );
  const aborted = await post("begin", { bytes: 10 }, ConfigUpload);
  await post("abort", { uploadId: aborted.uploadId }, ConfigEmpty);
  assert.equal(
    (
      await send(
        "network-configuration/begin",
        { context, bytes: (17 << 20) + 1 },
        headers,
      )
    ).status,
    400,
  );
  writeFileSync(
    join(output, "result.json"),
    JSON.stringify(
      {
        passed: true,
        immutableChunkedSnapshot: true,
        uint64Preserved: true,
        createAndConditionalMerge: true,
        staleBatchAtomic: true,
        receiptLossOnlyQueried: true,
        duplicateReusesTask: true,
        foreignSnapshotRejected: true,
        nativePersistence: "in-memory fixture; host networking untouched",
      },
      null,
      2,
    ),
  );
  console.log(`Network configuration API passed; ${output}`);
} finally {
  await fixture.close();
  writeFileSync(join(output, "service.log"), fixture.log());
}
