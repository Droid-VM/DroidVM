// Real HTTP/TaskService boundary; the fixture never executes image mutations.
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { startFixture } from "./server-fixture.mjs";
import {
  DiskOperationRequest,
  TaskResponse,
  VmOperationLookup,
} from "../../../webui/src/generated/validators.js";

const { values } = parseArgs({
  options: {
    exe: { type: "string" },
    output: { type: "string", default: "build/webui-native" },
  },
});
assert(values.exe, "--exe is required");
mkdirSync(values.output, { recursive: true });
const output = mkdtempSync(
  join(resolve(values.output), "native-disk-operations-"),
);
const fixture = await startFixture(values.exe);
try {
  const request = (path, options = {}) =>
    fetch(fixture.origin + "/api/v1/" + path, {
      signal: AbortSignal.timeout(7000),
      ...options,
    });
  assert.equal((await request("disk-operations/unknown")).status, 401);
  const login = await request("session", {
    method: "POST",
    headers: { Origin: fixture.origin, "Content-Type": "application/json" },
    body: JSON.stringify({
      pairingToken: (await fixture.command("pair")).pairingToken,
    }),
  });
  assert.equal(login.status, 201);
  const cookie = login.headers.get("set-cookie").split(";")[0];
  const session = await login.json();
  assert(session.server.capabilities.includes("disks.operations.v1"));
  const context = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const headers = {
    Cookie: cookie,
    Origin: fixture.origin,
    "Content-Type": "application/json",
    "X-DroidVM-CSRF": session.csrfToken,
  };
  const read = async (id) => {
    const response = await request(`disk-operations/${id}`, {
      headers: { Cookie: cookie },
    });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert(VmOperationLookup(result));
    assert.deepEqual(result.context, context);
    return result.task;
  };
  const wait = async (id) => {
    const deadline = Date.now() + 7000;
    while (Date.now() < deadline) {
      const task = await read(id);
      if (task && ["succeeded", "failed", "cancelled"].includes(task.state))
        return task;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error("Disk task did not finish");
  };
  const body = (operation) => ({
    context,
    requestId: `disk-${operation.action}`,
    revision: "fixture-disk-revision",
    vmRevision: "fixture-reference-revision",
    operation,
  });
  const post = (data, extra = {}) =>
    request("disk-operations", {
      method: "POST",
      headers: { ...headers, ...extra },
      body: JSON.stringify(data),
    });
  const resize = body({
    action: "resize",
    diskId: "fixture-disk",
    size: "9223372036854775807",
    cpus: [0, 1023],
  });
  assert.equal((await post(resize, { "X-DroidVM-CSRF": "" })).status, 403);
  assert.equal(
    (await post({ ...resize, context: { ...context, daemonEpoch: "stale" } }))
      .status,
    409,
  );
  for (const operation of [
    resize.operation,
    {
      action: "create",
      folder: "/fixture",
      name: "new.qcow2",
      format: "qcow2",
      size: "9007199254740993",
      compress: "zstd",
    },
    {
      action: "clone",
      diskId: "fixture-disk",
      folder: "/" + "a".repeat(4090),
      name: "clone.qcow2",
    },
    {
      action: "convert",
      diskId: "fixture-disk",
      format: "qcow2",
      compress: "deflate",
      preallocate: false,
    },
    { action: "flatten", diskId: "fixture-disk", format: "raw" },
    { action: "commit", diskId: "fixture-disk" },
    { action: "reset", diskId: "fixture-disk" },
    { action: "delete", diskId: "fixture-disk", deleteFiles: true },
  ]) {
    const data = body(operation);
    assert(DiskOperationRequest(data));
    const response = await post(data);
    assert.equal(response.status, 202);
    const receipt = await response.json();
    assert(TaskResponse(receipt));
    assert.deepEqual(receipt.context, context);
    assert.equal(receipt.task.kind, "disk_operation");
    assert.equal(receipt.task.requestId, data.requestId);
    assert.equal(
      receipt.task.resource,
      operation.action === "create" ? "disk:new" : "disk:fixture-disk",
    );
    assert.equal((await wait(data.requestId)).state, "succeeded");
    const duplicate = await post(data);
    assert.equal(duplicate.status, 202);
    assert.equal((await duplicate.json()).task.id, receipt.task.id);
    assert.equal((await post({ ...data, vmRevision: "changed" })).status, 409);
  }
  const lost = { ...resize, requestId: "lost-receipt" };
  await post(lost); // Intentionally discard the submission receipt; query only.
  assert.equal((await wait(lost.requestId)).state, "succeeded");
  assert.equal(await read("never-admitted"), null);
  for (const field of ["revision", "vmRevision"]) {
    const data = { ...resize, requestId: `stale-${field}`, [field]: "changed" };
    assert.equal((await post(data)).status, 202);
    const task = await wait(data.requestId);
    assert.equal(task.state, "failed");
    assert.equal(task.errorCode, "CONFLICT");
  }
  for (const operation of [
    { ...resize.operation, size: "9223372036854775808" },
    { ...resize.operation, cpus: [0, 0] },
    { ...resize.operation, filename: "/arbitrary" },
    { action: "create", folder: "/fixture", name: "new", format: "raw" },
    {
      action: "clone",
      diskId: "fixture-disk",
      folder: "/fixture",
      name: "new",
      format: "raw",
    },
  ])
    assert.equal(
      (await post({ ...resize, requestId: "invalid", operation })).status,
      400,
    );
  assert.equal(
    (
      await request("disk-operations", {
        method: "POST",
        headers,
        body: " ".repeat(32769),
      })
    ).status,
    413,
  );
  writeFileSync(
    join(output, "result.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "permission-csrf-context",
          "eight-native-action-mappings",
          "int64-string-and-long-path",
          "task-contract-and-identity",
          "same-request-dedup-and-different-request-conflict",
          "lost-receipt-lookup-without-resubmit",
          "stale-disk-and-vm-revisions",
          "invalid-fields-cpus-and-size",
          "bounded-http-body",
        ],
        fixture:
          "No image operations or filesystem mutations are executed by this fixture.",
      },
      null,
      2,
    ),
  );
} finally {
  assert.equal(await fixture.close(), 0);
}
