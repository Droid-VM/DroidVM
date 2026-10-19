import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyUsbResources({ page, post, output, expect }) {
  const url = new URL("/api/v1/usb", page.url()).href;
  const anonymous = await page.context().browser().newContext();
  assert.equal((await anonymous.request.get(url)).status(), 401);
  await anonymous.close();
  const result = await page.request.get(url);
  assert.equal(result.status(), 200);
  const data = await result.json();
  assert.equal(data.available, true);
  assert.equal(data.items.length, 3);
  assert.deepEqual(
    data.items.map((device) => device.id),
    ["1-1.2#3", "1-1.3#4", "2-1#7"],
  );
  assert.equal(data.items[0].node, undefined);
  assert.equal(data.items[1].auto_rule, undefined);
  assert.equal(data.items[1].owner.vmId, "native-http-vm");
  assert.equal(data.items[2].state, "claimed");
  assert.equal(data.items[2].owner, null);
  assert.equal(data.targets.length, 1);
  assert.equal(data.targets[0].revision, "9007199254740993");
  const observation = (device) => ({
    busNumber: device.busNumber,
    deviceNumber: device.deviceNumber,
    vendorId: device.vendorId,
    productId: device.productId,
    state: device.state,
    locked: device.locked,
    owner: device.owner
      ? {
          vmId: device.owner.vmId,
          controllerId: device.owner.controllerId,
          port: device.owner.port,
        }
      : null,
  });
  const request = {
    requestId: "usb-attach-fixture",
    deviceId: data.items[0].id,
    expected: observation(data.items[0]),
    target: {
      kind: "vm",
      vmId: data.targets[0].vmId,
      instanceId: data.targets[0].instanceId,
      revision: data.targets[0].revision,
      controllerId: data.targets[0].controllerId,
    },
  };
  assert.equal(
    (
      await page.request.post(
        new URL("/api/v1/usb-operations", page.url()).href,
        { data: request },
      )
    ).status(),
    403,
  );
  const lookup = async (id) =>
    (
      await page.request.get(
        new URL(`/api/v1/usb-operations/${id}`, page.url()).href,
      )
    ).json();
  const wait = async (id, state) => {
    await expect.poll(async () => (await lookup(id)).task?.state).toBe(state);
    return (await lookup(id)).task;
  };
  const submitted = await post("usb-operations", request, 202);
  await wait(request.requestId, "succeeded");
  const replay = await post("usb-operations", request, 202);
  assert.equal(replay.task.id, submitted.task.id);
  const duplicate = await post(
    "usb-operations",
    { ...request, target: { kind: "host" } },
    409,
  );
  assert.equal(duplicate.error.code, "conflict");
  await post(
    "usb-operations",
    { ...request, requestId: "usb-stale-device" },
    202,
  );
  assert.equal(
    (await wait("usb-stale-device", "failed")).errorCode,
    "CONFLICT",
  );
  const currentDevice = async () =>
    (await (await page.request.get(url)).json()).items[0];
  let current = await currentDevice();
  assert.equal(current.owner.vmId, request.target.vmId);
  await post(
    "usb-operations",
    {
      ...request,
      requestId: "usb-stale-vm",
      expected: observation(current),
      target: { ...request.target, revision: "9007199254740994" },
    },
    202,
  );
  assert.equal((await wait("usb-stale-vm", "failed")).errorCode, "CONFLICT");
  assert.equal((await currentDevice()).owner.vmId, request.target.vmId);
  for (const target of ["idle", "host"]) {
    current = await currentDevice();
    const id = `usb-${target}-fixture`;
    await post(
      "usb-operations",
      {
        ...request,
        requestId: id,
        expected: observation(current),
        target: { kind: target },
      },
      202,
    );
    await wait(id, "succeeded");
    current = await currentDevice();
    assert.equal(current.state, target);
    assert.equal(current.owner, null);
    assert.equal(current.locked, true);
  }
  await post(
    "usb-operations",
    { ...request, requestId: "usb-invalid", deviceId: "1-1.2#99" },
    400,
  );
  assert.equal((await lookup("usb-not-found")).task, null);
  assert.equal(
    (
      await page.request.get(
        new URL("/api/v1/usb-operations/fixture-stop-edit", page.url()).href,
      )
    ).status(),
    409,
  );
  await page.goto(new URL("/#/resources/usb", page.url()).href);
  const list = page.getByRole("region", { name: "USB 设备", exact: true });
  const cards = list.locator("[data-usb-id]");
  await expect(cards).toHaveCount(3);
  const first = list.locator('[data-usb-id="1-1.2#3"]');
  const change = () =>
    first.getByRole("button", { name: "更改 USB 归属", exact: true });
  const dialog = page.getByRole("dialog", {
    name: "更改 USB 归属",
    exact: true,
  });
  const status = list.locator('[data-usb-operation="1-1.2#3"]');
  const queryTask = () =>
    list.getByRole("button", { name: "查询当前状态", exact: true }).click();
  const submittedByUi = [];
  let loseReceipt = false,
    hideLookup = false;
  await page.route("**/api/v1/usb-operations", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    submittedByUi.push(route.request().postDataJSON());
    const response = await route.fetch();
    assert.equal(response.status(), 202);
    if (loseReceipt) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await page.route("**/api/v1/usb-operations/*", async (route) => {
    if (hideLookup)
      await route.fulfill({ json: { context: data.context, task: null } });
    else await route.continue();
  });
  const confirmTarget = async (target) => {
    const previous = submittedByUi.length;
    await change().click();
    await dialog
      .getByRole("combobox", { name: "目标", exact: true })
      .selectOption(target);
    await dialog.getByRole("button", { name: "确认", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect.poll(() => submittedByUi.length).toBe(previous + 1);
  };
  const settleUi = async (state = "succeeded") => {
    await expect.poll(() => submittedByUi.at(-1)?.requestId).toBeTruthy();
    await wait(submittedByUi.at(-1).requestId, state);
    await queryTask();
    await expect(change()).toBeEnabled();
  };
  await change().click();
  await dialog
    .getByRole("combobox", { name: "目标", exact: true })
    .selectOption("vm:usb-fixture-vm");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "usb-target-320.png") });
  await dialog.getByRole("button", { name: "确认", exact: true }).click();
  await settleUi();
  assert.equal(submittedByUi[0].target.revision, "9007199254740993");
  assert.equal(submittedByUi[0].expected.owner, null);
  await expect(first).toContainText("USB 测试虚拟机");
  await expect(status).toContainText("已完成");

  loseReceipt = hideLookup = true;
  await confirmTarget("host");
  await expect(status).toContainText("操作结果待核实");
  await queryTask();
  await expect(change()).toBeDisabled();
  await page.goto(new URL("/#/tasks", page.url()).href);
  await page.goto(new URL("/#/resources/usb", page.url()).href);
  await expect(cards).toHaveCount(3);
  await expect(change()).toBeDisabled();
  assert.equal(submittedByUi.length, 2);
  loseReceipt = hideLookup = false;
  await settleUi();
  await expect(first).toContainText("宿主使用");
  assert.equal(submittedByUi.length, 2);
  assert.equal(submittedByUi[1].expected.owner.vmName, undefined);
  await confirmTarget("idle");
  await settleUi();
  await expect(first).toContainText("空闲");

  // Keep the dialog's old observation while another client changes the device.
  await change().click();
  await dialog
    .getByRole("combobox", { name: "目标", exact: true })
    .selectOption("vm:usb-fixture-vm");
  current = await currentDevice();
  await post(
    "usb-operations",
    {
      ...request,
      requestId: "usb-other-client",
      expected: observation(current),
      target: { kind: "host" },
    },
    202,
  );
  await wait("usb-other-client", "succeeded");
  await dialog.getByRole("button", { name: "确认", exact: true }).click();
  await expect.poll(() => submittedByUi.length).toBe(4);
  await settleUi("failed");
  assert.equal(submittedByUi[3].expected.state, "idle");
  await expect(status).toContainText("CONFLICT");
  assert.equal((await currentDevice()).state, "host");
  await page.unroute("**/api/v1/usb-operations");
  await page.unroute("**/api/v1/usb-operations/*");
  await expect(list.locator('[data-usb-id="1-1.3#4"]')).toContainText(
    "测试虚拟机",
  );
  await expect(list.locator('[data-usb-id="1-1.3#4"]')).toContainText(
    "自动连接已锁定",
  );
  await expect(list.locator('[data-usb-id="2-1#7"]')).toContainText(
    "设备已被占用，但 DroidVM 没有记录其归属。",
  );
  const search = list.getByRole("searchbox");
  await search.fill("same-serial");
  await expect(cards).toHaveCount(2);
  await search.fill("测试虚拟机");
  await expect(cards).toHaveCount(1);
  await cards.first().scrollIntoViewIfNeeded();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "usb-320.png") });
  await search.fill("");
  let response = {
    ...data,
    items: data.items.filter((device) => device.id !== "1-1.3#4"),
  };
  await page.route("**/api/v1/usb", (route) =>
    route.fulfill({ json: response }),
  );
  const refresh = list.getByRole("button", { name: "刷新列表", exact: true });
  await refresh.click();
  await expect(cards).toHaveCount(2);
  await expect(list.locator('[data-usb-id="1-1.3#4"]')).toHaveCount(0);
  response = { ...data, available: false, items: [] };
  await refresh.click();
  await expect(cards).toHaveCount(0);
  await expect(
    list.getByText("未发现宿主 USB 设备。", { exact: true }),
  ).toHaveCount(0);
  response = { ...data, items: [] };
  await refresh.click();
  await expect(
    list.getByText("未发现宿主 USB 设备。", { exact: true }),
  ).toBeVisible();
  await page.unroute("**/api/v1/usb");
  await refresh.click();
  await expect(cards).toHaveCount(3);
  writeFileSync(
    join(output, "usb.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "authenticated-native-catalogue-whitelist",
          "same-serial-distinct-devices",
          "owner-vs-unknown-userspace-claim",
          "search-and-lock-status",
          "unplug-refresh-removes-owner",
          "unavailable-vs-empty",
          "mobile-320-no-overflow",
          "conditional-target-tasks-and-csrf",
          "same-request-dedup-and-different-request-conflict",
          "stale-device-and-vm-observation-rejected",
          "host-idle-vm-roundtrip-and-readonly-lookup",
          "ui-target-selection-confirmation-and-task-readback",
          "ui-lost-receipt-no-replay-across-navigation",
          "ui-frozen-observation-rejects-other-client-change",
          "target-dialog-mobile-320",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native USB resource checks passed; ${output}`);
}
