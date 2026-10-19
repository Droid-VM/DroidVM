import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import * as contract from "../../../webui/src/generated/validators.js";
import { verifyUsbRulesEditor } from "./usb-rules-editor.mjs";

export async function verifyUsbRules({ page, post, fixture, output, expect }) {
  const session = await (
    await page.request.get(new URL("/api/v1/session", page.url()).href)
  ).json();
  assert(session.server.capabilities.includes("usb.rules.v1"));
  const scope = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const endpoint = (operation) =>
    new URL(`/api/v1/usb-rules/${operation}`, page.url()).href;
  const anonymous = await page.context().browser().newContext();
  assert.equal(
    (
      await anonymous.request.post(endpoint("read"), {
        headers: {
          Origin: new URL(page.url()).origin,
          "X-DroidVM-CSRF": session.csrfToken,
        },
        data: { context: scope, revision: null, offset: 0 },
      })
    ).status(),
    401,
  );
  await anonymous.close();
  assert.equal(
    (
      await page.request.post(endpoint("read"), {
        headers: { Origin: new URL(page.url()).origin },
        data: { context: scope, revision: null, offset: 0 },
      })
    ).status(),
    403,
  );
  assert.equal((await page.request.get(endpoint("read"))).status(), 405);
  const call = (operation, input, status = 200) =>
    post(`usb-rules/${operation}`, input, status);
  const validate = (name, value) =>
    assert(
      contract[name](value),
      `${name}: ${JSON.stringify(contract[name].errors)}`,
    );
  const read = async () => {
    let revision = null,
      offset = 0,
      persistence,
      total;
    const parts = [];
    for (;;) {
      const page = await call("read", { revision, offset });
      validate("UsbRulesPage", page);
      assert.deepEqual(page.context, scope);
      assert.equal(page.private, undefined);
      assert.equal(page.offset, offset);
      if (revision !== null) {
        assert.equal(page.revision, revision);
        assert.equal(page.persistence, persistence);
        assert.equal(page.total, total);
      }
      revision = page.revision;
      persistence = page.persistence;
      total = page.total;
      const bytes = Buffer.from(page.data, "base64");
      assert(bytes.length > 0 && bytes.length <= 48 * 1024);
      parts.push(bytes);
      offset += bytes.length;
      if (page.next === null) break;
      assert.equal(page.next, offset);
    }
    assert.equal(offset, total);
    const rules = JSON.parse(Buffer.concat(parts).toString("utf8"));
    validate("UsbRulesDocument", rules);
    return { revision, persistence, rules, parts: parts.length };
  };
  const initial = await read();
  assert.equal(initial.rules.enabled, false);
  assert(session.server.capabilities.includes("usb.rules.preview.v1"));
  await call("preview", {}, 400);
  await call("preview", { revision: null }, 400);
  await call("preview", { revision: initial.revision, extra: true }, 400);
  await call("preview", { revision: "stale" }, 409);
  const preview = await call("preview", { revision: initial.revision });
  validate("UsbRulesPreview", preview);
  assert.equal(preview.enabled, false);
  assert.equal(preview.items.length, 3);
  assert(preview.items.every((item) => !item.eligible && item.match === null));
  assert.equal(preview.items[0].device.node, undefined);
  assert.equal(preview.items[0].device.private, undefined);
  assert.deepEqual(await read(), initial);
  const targets = await call("targets", {});
  validate("UsbRuleTargets", targets);
  assert.equal(targets.items[0].vmId, "stopped-fixture-vm");
  assert.equal(targets.items[0].controllerId, "xhci-0");
  assert.equal(targets.items[1].controllerId, null);
  assert.equal(targets.items[0].private, undefined);
  await call("read", { revision: null, offset: 1 }, 400);
  await call("targets", { extra: true }, 400);
  await call("begin", { revision: initial.revision, bytes: 8388609 }, 400);
  await call("begin", { revision: "stale", bytes: 2 }, 409);
  const stage = async (revision, rules) => {
    const bytes = Buffer.from(JSON.stringify(rules));
    const begun = await call("begin", { revision, bytes: bytes.length });
    validate("ConfigUpload", begun);
    for (let offset = 0; offset < bytes.length; offset += 48 * 1024) {
      const part = bytes.subarray(offset, offset + 48 * 1024);
      const body = {
        uploadId: begun.uploadId,
        offset,
        data: part.toString("base64"),
      };
      const result = await call("append", body);
      validate("ConfigAppend", result);
      assert.equal(result.received, offset + part.length);
      assert.equal((await call("append", body)).received, result.received);
    }
    return begun.uploadId;
  };
  const abortId = await stage(initial.revision, initial.rules);
  validate("ConfigEmpty", await call("abort", { uploadId: abortId }));
  await call("append", { uploadId: abortId, offset: 0, data: "e30=" }, 404);
  const document = {
    ...initial.rules,
    exact: [
      {
        id: "1234:abcd:键盘",
        port: "1.2",
        target: "vm",
        vm: "stopped-fixture-vm",
        controller: "xhci-0",
      },
    ],
    port: [{ port: "1.3", target: "host" }],
    device: Array.from({ length: 2000 }, (_, i) => ({
      id: `1234:abcd:测试-${i}`,
      target: "sink",
    })),
    any: [{ target: "vm", vm: "stopped-fixture-vm" }],
  };
  const uploadId = await stage(initial.revision, document);
  const commit = { uploadId, requestId: "rules-save-fixture" };
  const accepted = await call("commit", commit, 202);
  validate("TaskResponse", accepted);
  assert.equal(accepted.task.kind, "usb_rules");
  assert.equal(accepted.task.resource, "usb-rules");
  const status = async (id) => {
    const value = await call("status", { requestId: id });
    validate("UsbRulesStatus", value);
    return value;
  };
  const wait = async (id, state = "succeeded") => {
    await expect.poll(async () => (await status(id)).task?.state).toBe(state);
    return status(id);
  };
  const saved = await wait(commit.requestId);
  assert.equal(saved.version.persistence, "persisted");
  assert.equal((await call("commit", commit, 202)).task.id, accepted.task.id);
  await call("commit", { ...commit, uploadId: `${uploadId}-other` }, 409);
  let current = await read();
  assert(current.parts > 1);
  assert.deepEqual(current.rules, document);
  assert.equal(current.revision, saved.version.revision);
  await call("read", { revision: initial.revision, offset: 0 }, 409);
  const staleUpload = await stage(current.revision, current.rules);
  await fixture.command("usb-rules-touch");
  await call("read", { revision: current.revision, offset: 49152 }, 409);
  await call(
    "commit",
    { uploadId: staleUpload, requestId: "rules-stale-fixture" },
    202,
  );
  const failed = await wait("rules-stale-fixture", "failed");
  assert.equal(failed.task.errorCode, "CONFLICT");
  assert.equal(failed.version, null);
  await call("append", { uploadId: staleUpload, offset: 0, data: "e30=" }, 404);
  current = await read();
  assert.deepEqual(current.rules, document);
  await fixture.command("usb-rules-uncertain");
  const uncertainId = await stage(current.revision, {
    ...current.rules,
    enabled: true,
  });
  await call(
    "commit",
    { uploadId: uncertainId, requestId: "rules-uncertain-fixture" },
    202,
  );
  const uncertain = await wait("rules-uncertain-fixture", "failed");
  assert.equal(uncertain.task.errorCode, "COMMIT_UNCERTAIN");
  assert.equal(uncertain.version, null);
  current = await read();
  assert.equal(current.rules.enabled, true);
  assert.equal(current.persistence, "uncertain");
  const repairId = await stage(current.revision, current.rules);
  await call(
    "commit",
    { uploadId: repairId, requestId: "rules-repair-fixture" },
    202,
  );
  await wait("rules-repair-fixture");
  assert.equal((await read()).persistence, "persisted");
  assert.equal((await status("rules-missing-fixture")).task, null);
  await call("status", { requestId: "fixture-stop-edit" }, 404);
  await verifyUsbRulesEditor({
    page,
    fixture,
    output,
    expect,
    read,
    stage,
    call,
    wait,
    scope,
  });
  writeFileSync(
    join(output, "usb-rules.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "rules-permissions-csrf-and-capability",
          "generated-contract-validation",
          "rule-target-whitelist-and-null-controller",
          "48k-upload-and-versioned-read",
          "duplicate-chunks-abort-and-consumption",
          "request-deduplication-and-conflict",
          "stale-page-and-commit-rejected",
          "uncertain-task-with-active-rules-readback",
          "explicit-fresh-repair",
          "missing-and-other-kind-lookup",
          "preview-capability-request-shape-stale-version-whitelist",
          "preview-disabled-draft-invalidation-and-matched-rule-navigation",
          "preview-locked-claimed-eligibility-with-unchanged-devices-and-rules",
          "four-app-rule-layers-and-hidden-error-location",
          "device-pick-target-refresh-retained-references",
          "add-remove-reorder-and-enabled-confirmation",
          "320px-editor-and-save-confirmation",
          "lost-receipt-no-replay-and-route-return",
          "stale-save-keeps-draft-before-explicit-reload",
          "uncertain-save-readback-and-explicit-repair",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native USB rules API checks passed; ${output}`);
}
