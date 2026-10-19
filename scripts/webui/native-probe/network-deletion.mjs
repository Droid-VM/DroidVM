import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

// Real HTTP/session/tasks and browser. Fixture persistence and recovery remain
// in-memory substitutes; this does not test native journal/fsync/kernel cleanup.
export async function verifyNetworkDeletion({
  page,
  post,
  fixture,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const url = (path) => new URL(path, page.url()).href;
  const get = async (path) => {
    const response = await page.request.get(url(`/api/v1/${path}`));
    assert.equal(response.status(), 200);
    return response.json();
  };
  const snapshot = async (kind) => {
    let part = await post(`${kind}/read`, { snapshotId: null, offset: 0 });
    const snapshotId = part.snapshotId,
      chunks = [];
    let metadata;
    try {
      for (;;) {
        for (const item of part.items) {
          if (item.type === "metadata") metadata = item;
          if (item.type === "chunk")
            chunks.push(Buffer.from(item.data, "base64"));
        }
        if (part.next === null) break;
        part = await post(`${kind}/read`, { snapshotId, offset: part.next });
      }
      return {
        document: parse(Buffer.concat(chunks).toString("utf8")),
        revision: metadata?.revision,
      };
    } finally {
      await post(`${kind}/release`, { snapshotId });
    }
  };
  const stage = async (kind, document, revision) => {
    const bytes = Buffer.from(stringify(document));
    const { uploadId } = await post(`${kind}/begin`, {
      bytes: bytes.length,
      ...(revision ? { revision } : {}),
    });
    for (let offset = 0; offset < bytes.length; offset += 48 * 1024) {
      const chunk = bytes.subarray(offset, offset + 48 * 1024);
      const appended = await post(`${kind}/append`, {
        uploadId,
        offset,
        data: chunk.toString("base64"),
      });
      assert.equal(appended.received, offset + chunk.length);
    }
    return uploadId;
  };
  const wait = async (kind, requestId, state = "succeeded") => {
    let result;
    await expect(async () => {
      result = await post(`${kind}/status`, { requestId });
      assert.equal(result.task?.state, state);
    }).toPass({ timeout: 10000 });
    return result;
  };
  const commit = async (kind, uploadId, requestId, state = "succeeded") => {
    const accepted = await post(`${kind}/commit`, { uploadId, requestId }, 202);
    const result = await wait(kind, requestId, state);
    assert.equal(result.task.id, accepted.task.id);
    return result;
  };
  await page.goto(url("/#/resources/network"));
  const session = await get("session");
  assert(session.server.capabilities.includes("networks.deletion.v1"));
  assert(session.server.capabilities.includes("configuration.recovery.v1"));
  for (const endpoint of ["network-deletion/begin", "configuration-recovery"]) {
    assert.equal(
      await page.evaluate(
        async (path) =>
          (
            await fetch(path, {
              method: "POST",
              credentials: "omit",
              headers: { "Content-Type": "application/json" },
              body: "{}",
            })
          ).status,
        `/api/v1/${endpoint}`,
      ),
      401,
    );
    assert.equal(
      (
        await page.request.post(url(`/api/v1/${endpoint}`), { data: {} })
      ).status(),
      403,
    );
  }
  await post(
    "network-deletion/begin",
    { revision: "oversize", bytes: 9 * 1024 * 1024 + 1 },
    400,
  );
  const initial = (await snapshot("network-configuration")).document
    .networks[0];
  const auxiliary = parse(stringify(initial));
  auxiliary.id = "deletion-auxiliary";
  auxiliary.name = "辅助删除网络";
  const retained = parse(stringify(initial));
  retained.id = "deletion-retained";
  retained.name = "保留的网络";
  await commit(
    "network-configuration",
    await stage("network-configuration", {
      operations: [
        { expected: null, config: auxiliary },
        { expected: null, config: retained },
      ],
    }),
    "deletion-create-auxiliary",
  );
  let vm = await snapshot("vm-configuration");
  const auxUpload = await stage(
    "network-deletion",
    { expected: auxiliary },
    vm.revision,
  );
  const auxiliaryResult = await commit(
    "network-deletion",
    auxUpload,
    "deletion-auxiliary",
  );
  assert.equal(auxiliaryResult.version.networkId, auxiliary.id);
  assert.equal(auxiliaryResult.version.persistence, "persisted");
  assert.notEqual(auxiliaryResult.version.vmRevision, vm.revision);
  const duplicate = await post(
    "network-deletion/commit",
    { uploadId: auxUpload, requestId: "deletion-auxiliary" },
    202,
  );
  assert.equal(duplicate.task.id, auxiliaryResult.task.id);
  assert.equal((await fixture.command("network-deletion-stats")).deletions, 1);

  vm = await snapshot("vm-configuration");
  vm.document.vms[0].networks = [
    { network_id: initial.id, mac_address: "02:00:00:00:00:01", vlan_id: 10 },
    {
      network_id: retained.id,
      mac_address: "02:00:00:00:00:02",
      extension: { keep: "unrelated NIC" },
    },
    { network_id: initial.id, mac_address: "02:00:00:00:00:03", vlan_id: 20 },
  ];
  await commit(
    "vm-configuration",
    await stage("vm-configuration", vm.document, vm.revision),
    "deletion-save-nics",
  );
  vm = await snapshot("vm-configuration");
  const preserved = stringify(vm.document.vms[0].networks[1]);
  const staleUpload = await stage(
    "network-deletion",
    { expected: initial },
    vm.revision,
  );
  await fixture.command("config-touch");
  const stale = await commit(
    "network-deletion",
    staleUpload,
    "deletion-stale-vms",
    "failed",
  );
  assert.equal(stale.task.errorCode, "CONFLICT");
  assert.equal(stale.version, null);
  vm = await snapshot("vm-configuration");
  const wrong = parse(stringify(initial));
  wrong.name = "过期的网络名称";
  const conflict = await commit(
    "network-deletion",
    await stage("network-deletion", { expected: wrong }, vm.revision),
    "deletion-stale-network",
    "failed",
  );
  assert.equal(conflict.task.errorCode, "CONFLICT");
  assert.equal((await fixture.command("network-deletion-stats")).deletions, 1);
  assert.equal(
    (await snapshot("vm-configuration")).document.vms[0].networks.length,
    3,
  );

  const before = await get(`networks/${initial.id}`);
  assert.equal(before.attachments.length, 2);
  await post(
    "network-operations",
    {
      networkId: initial.id,
      requestId: "deletion-start-network",
      action: "start",
      expected: {
        generation: before.runtime.generation,
        state: before.runtime.state,
        configurationRevision: before.configurationRevision,
      },
    },
    202,
  );
  await expect(async () =>
    assert.equal(
      (await get(`networks/${initial.id}`)).runtime.state,
      "RUNNING",
    ),
  ).toPass({ timeout: 10000 });
  const browserDeletes = [],
    browserRecoveries = [];
  page.on("request", (request) => {
    if (request.method() !== "POST") return;
    if (request.url().endsWith("/network-deletion/commit"))
      browserDeletes.push(request.postDataJSON());
    if (request.url().endsWith("/configuration-recovery"))
      browserRecoveries.push(request.postDataJSON());
  });
  await page.reload();
  await page.getByTestId("network-delete-open").first().click();
  const dialog = page.getByTestId("network-delete-dialog");
  const status = page.getByTestId("network-delete-status");
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId("network-delete-attachments")).toContainText(
    "原生 HTTP 虚拟机",
  );
  await expect(page.getByTestId("network-delete-attachments")).toContainText(
    "02:00:00:00:00:03",
  );
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  assert.equal(
    await dialog.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
    true,
  );
  await page.screenshot({
    path: join(output, "network-deletion-confirm-320.png"),
  });
  await fixture.command("network-deletion-uncertain");
  await page.route("**/api/v1/network-deletion/commit", async (route) => {
    await route.fetch();
    await route.abort();
  });
  await page.getByTestId("network-delete-confirm").click();
  await expect(async () => {
    const reconcile = page.getByTestId("network-delete-reconcile");
    if ((await reconcile.isVisible()) && (await reconcile.isEnabled()))
      await reconcile.click();
    await expect(status).toContainText("COMMIT_UNCERTAIN");
  }).toPass({ timeout: 12000 });
  await page.unroute("**/api/v1/network-deletion/commit");
  assert.equal(browserDeletes.length, 1);
  const failed = await wait(
    "network-deletion",
    browserDeletes[0].requestId,
    "failed",
  );
  assert.equal(failed.task.errorCode, "COMMIT_UNCERTAIN");
  const repeated = await post(
    "network-deletion/commit",
    browserDeletes[0],
    202,
  );
  assert.equal(repeated.task.id, failed.task.id);
  const pending = await fixture.command("network-deletion-stats");
  assert.equal(pending.deletions, 2);
  assert.equal(pending.recoveryPending, true);
  const confirm = page.getByTestId("network-delete-confirm");
  if (await confirm.count()) await expect(confirm).toBeDisabled();
  await page.getByTestId("configuration-recovery-open").click();
  await page.route("**/api/v1/configuration-recovery", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    await route.fetch();
    await route.abort();
  });
  await page.getByTestId("configuration-recovery-confirm").click();
  await expect(async () => {
    const reconcile = page.getByTestId("configuration-recovery-reconcile");
    if ((await reconcile.isVisible()) && (await reconcile.isEnabled()))
      await reconcile.click();
    assert.equal(browserRecoveries.length, 1);
    const lookup = await get(
      `configuration-recovery/${browserRecoveries[0].requestId}`,
    );
    assert.equal(lookup.task?.state, "succeeded");
    await expect(
      page.getByTestId("configuration-recovery-status"),
    ).toHaveAttribute("data-task-state", "succeeded");
    await expect(
      page.getByTestId("network-delete-observation"),
    ).toHaveAttribute("data-observation", "absent");
  }).toPass({ timeout: 12000 });
  await page.unroute("**/api/v1/configuration-recovery");
  const recoveryDuplicate = await post(
    "configuration-recovery",
    browserRecoveries[0],
    202,
  );
  assert.equal(recoveryDuplicate.task.state, "succeeded");
  const after = await fixture.command("network-deletion-stats");
  assert.equal(after.deletions, 2);
  assert.equal(after.recoveries, 1);
  assert.equal(after.recoveryPending, false);
  assert.equal(after.runningNetworks, 0);
  await expect(status).toHaveAttribute("data-task-state", "failed");
  const networks = (await snapshot("network-configuration")).document.networks;
  assert(!networks.some((row) => row.id === initial.id));
  assert.equal(
    stringify(networks.find((row) => row.id === retained.id)),
    stringify(retained),
  );
  const saved = (await snapshot("vm-configuration")).document;
  assert.equal(saved.vms[0].networks.length, 1);
  assert.equal(stringify(saved.vms[0].networks[0]), preserved);
  assert.equal(saved.extension.counter.toString(), "18446744073709551615");
  assert.equal(browserDeletes.length, 1);
  assert.equal(browserRecoveries.length, 1);
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({
    path: join(output, "network-deletion-recovered-320.png"),
  });
  writeFileSync(
    join(output, "network-deletion.json"),
    JSON.stringify(
      {
        passed: true,
        browserDeletes: browserDeletes.length,
        browserRecoveries: browserRecoveries.length,
        cases: [
          "permissions and 9MiB bound",
          "conditional VM/network conflicts",
          "successful persisted receipt",
          "duplicate task reuse",
          "saved VM references preview",
          "reference removal preserves unrelated NIC and uint64",
          "running network stopped by coordinated deletion",
          "lost deletion receipt without replay",
          "COMMIT_UNCERTAIN requires explicit recovery",
          "lost recovery receipt without replay",
          "320px layout",
        ],
        scope:
          "Real HTTP/session/staged uploads/tasks/projections and browser; atomic deletion, network cleanup and configuration recovery are in-memory substitutes. No filesystem transaction or kernel networking tested.",
      },
      null,
      2,
    ),
  );
}
