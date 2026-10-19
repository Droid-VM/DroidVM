import assert from "node:assert/strict";
import { join } from "node:path";

export async function verifyUsbRulesEditor({
  page,
  fixture,
  output,
  expect,
  read,
  stage,
  call,
  wait,
  scope,
}) {
  const empty = {
    version: 1,
    enabled: false,
    exact: [],
    port: [],
    device: [],
    any: [],
  };
  const current = await read();
  await call(
    "commit",
    {
      uploadId: await stage(current.revision, empty),
      requestId: "rules-ui-seed",
    },
    202,
  );
  await wait("rules-ui-seed");
  await page.goto(new URL("/#/resources/usb", page.url()).href);
  await page
    .getByRole("link", { name: "USB 自动连接规则", exact: false })
    .click();
  const editor = page.locator(".rules-editor");
  const categories = editor.getByRole("group", {
    name: "USB 规则分类",
    exact: true,
  });
  const selectLayer = async (name) => {
    await categories
      .getByRole("button", { name: new RegExp(`^${name} ·`) })
      .click();
  };
  const add = editor.getByRole("button", { name: "添加规则", exact: true });
  const save = editor.getByRole("button", {
    name: "保存并应用规则",
    exact: true,
  });
  const confirm = async (title) => {
    await page
      .getByRole("dialog", { name: title, exact: true })
      .getByRole("button", { name: "确认", exact: true })
      .click();
  };
  const reload = async () => {
    await editor.getByRole("button", { name: "重新读取", exact: true }).click();
    await confirm("重新读取");
  };
  await expect(add).toBeEnabled();
  const previewPanel = editor.getByRole("region", {
    name: "规则匹配预览",
    exact: true,
  });
  const previewButton = previewPanel.getByRole("button", {
    name: "预览宿主规则",
    exact: true,
  });
  await previewButton.click();
  await expect(previewPanel.locator("[data-preview-device]")).toHaveCount(3);
  await expect(
    previewPanel.locator('[data-preview-device="1-1.2#3"]'),
  ).toContainText("规则已关闭，不自动分配设备");
  await expect(categories.getByRole("button")).toHaveText([
    "精确 · 0",
    "端口 · 0",
    "设备 · 0",
    "任意 · 0",
  ]);
  await add.click();
  await expect(previewButton).toBeDisabled();
  await expect(previewPanel.locator("[data-preview-device]")).toHaveCount(0);
  const exact = editor.locator('[data-rule="exact-0"]');
  await exact
    .getByRole("combobox", { name: "从已插入设备填写", exact: true })
    .selectOption("1-1.2#3");
  await expect(exact.locator("#usb-rule-exact-0-port")).toHaveValue("1.2");
  await exact.locator("#usb-rule-exact-0-id").fill("ABCD:1234:Case敏感");
  await exact
    .getByRole("combobox", { name: "目标", exact: true })
    .selectOption("vm");
  const vm = exact.getByRole("combobox", { name: "目标虚拟机", exact: true });
  await expect(
    vm.locator('option[value="no-usb-fixture-vm"]'),
  ).toHaveJSProperty("disabled", true);
  await vm.selectOption("stopped-fixture-vm");
  await expect(
    exact.getByRole("combobox", { name: "控制器 ID", exact: true }),
  ).toHaveValue("xhci-0");

  await page.route("**/api/v1/usb-rules/targets", (route) =>
    route.fulfill({ json: { context: scope, items: [] } }),
  );
  await editor
    .getByRole("button", { name: "刷新目标目录", exact: true })
    .click();
  await expect(vm.locator("option:checked")).toHaveText(
    "目录中不存在：stopped-fixture-vm",
  );
  await expect(
    exact.getByRole("combobox", { name: "控制器 ID", exact: true }),
  ).toHaveValue("xhci-0");
  await page.unroute("**/api/v1/usb-rules/targets");
  await editor
    .getByRole("button", { name: "刷新目标目录", exact: true })
    .click();
  await expect(vm.locator("option:checked")).toHaveText("已停止的规则虚拟机");

  await selectLayer("端口");
  await add.click();
  await editor.locator("#usb-rule-port-0-port").fill("bad");
  await selectLayer("设备");
  await add.click();
  await editor.locator("#usb-rule-device-0-id").fill("1234:abcd:Serial");
  await selectLayer("任意");
  await add.click();
  const any = editor.locator('[data-rule="any-0"]');
  await expect(
    any
      .getByRole("combobox", { name: "目标", exact: true })
      .locator('option[value="host"]'),
  ).toHaveCount(0);
  await expect(save).toBeDisabled();
  await editor
    .getByRole("button", { name: "定位 1 处字段错误", exact: true })
    .click();
  await expect(editor.locator("#usb-rule-port-0-port")).toBeFocused();
  await editor.locator("#usb-rule-port-0-port").fill("1.4");
  await add.click();
  await editor.locator("#usb-rule-port-1-port").fill("1.5");
  await editor
    .locator('[data-rule="port-1"]')
    .getByRole("button", { name: "上移规则", exact: true })
    .click();
  await expect(editor.locator("#usb-rule-port-0-port")).toHaveValue("1.5");
  await editor
    .locator('[data-rule="port-1"]')
    .getByRole("button", { name: "移除规则", exact: true })
    .click();
  await selectLayer("精确");
  await expect(exact.locator("#usb-rule-exact-0-id")).toHaveValue(
    "ABCD:1234:Case敏感",
  );
  await editor
    .getByRole("checkbox", { name: "启用 USB 自动连接规则", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "启用 USB 自动连接规则", exact: true }),
  ).toContainText("Android 只能使用规则交还宿主的设备");
  await confirm("启用 USB 自动连接规则");
  await expect(editor.getByRole("checkbox")).toBeChecked();
  assert(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  await page.screenshot({
    path: join(output, "usb-rules-editor-320.png"),
    fullPage: true,
  });

  const commits = [];
  let lostReceipt = true,
    hideLookup = true;
  await page.route("**/api/v1/usb-rules/commit", async (route) => {
    commits.push(route.request().postDataJSON());
    const response = await route.fetch();
    assert.equal(response.status(), 202);
    if (lostReceipt) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await page.route("**/api/v1/usb-rules/status", async (route) => {
    if (hideLookup)
      await route.fulfill({
        json: { context: scope, task: null, version: null },
      });
    else await route.continue();
  });
  const query = editor.getByRole("button", {
    name: "查询当前状态",
    exact: true,
  });
  await save.click();
  await confirm("保存并应用规则");
  await expect(editor).toContainText("保存结果尚未确认");
  await query.click();
  await expect(save).toBeDisabled();
  await expect(
    editor.getByRole("button", { name: "重新读取", exact: true }),
  ).toBeDisabled();
  assert.equal(commits.length, 1);
  await page
    .getByRole("main")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await page
    .getByRole("dialog", { name: "离开配置编辑", exact: true })
    .getByRole("button", { name: "离开", exact: true })
    .click();
  await page
    .getByRole("link", { name: "USB 自动连接规则", exact: false })
    .click();
  await expect(editor).toContainText("保存结果尚未确认");
  await expect(save).toBeDisabled();
  hideLookup = lostReceipt = false;
  await wait(commits[0].requestId);
  if (await query.isVisible()) await query.click();
  await expect(
    editor.getByText("规则已保存，并已重新读取宿主规则。", { exact: true }),
  ).toBeVisible({ timeout: 20000 });
  const saved = await read();
  assert.equal(saved.rules.exact[0].id, "abcd:1234:Case敏感");
  assert.equal(saved.rules.port[0].port, "1.5");
  assert.equal(saved.rules.device[0].id, "1234:abcd:Serial");
  assert.equal(saved.rules.any[0].target, "sink");
  assert.equal(saved.rules.enabled, true);
  assert.equal(commits.length, 1);

  await exact.locator("#usb-rule-exact-0-id").fill("1234:abcd:KeepDraft");
  await fixture.command("usb-rules-touch");
  await save.click();
  await confirm("保存并应用规则");
  await expect(editor).toContainText("请先核实配置再继续编辑");
  await expect(exact.locator("#usb-rule-exact-0-id")).toHaveValue(
    "1234:abcd:KeepDraft",
  );
  assert.equal(commits.length, 1);
  await reload();
  await expect(exact.locator("#usb-rule-exact-0-id")).toHaveValue(
    "abcd:1234:Case敏感",
  );
  await expect(exact.locator("#usb-rule-exact-0-id")).toBeEnabled();

  await exact.locator("#usb-rule-exact-0-id").fill("1234:abcd:Uncertain");
  await fixture.command("usb-rules-uncertain");
  await save.click();
  await confirm("保存并应用规则");
  await expect.poll(() => commits.length).toBe(2);
  await wait(commits[1].requestId, "failed");
  if (await query.isVisible()) await query.click();
  await expect(
    editor.getByText("请先核实配置再继续编辑", { exact: true }),
  ).toBeVisible({ timeout: 20000 });
  await expect(editor).toContainText("COMMIT_UNCERTAIN");
  await expect(exact.locator("#usb-rule-exact-0-id")).toHaveValue(
    "1234:abcd:Uncertain",
  );
  await reload();
  await expect(save).toBeEnabled();
  await save.click();
  await confirm("保存并应用规则");
  await expect.poll(() => commits.length).toBe(3);
  await wait(commits[2].requestId);
  if (await query.isVisible()) await query.click();
  await expect(
    editor.getByText("规则已保存，并已重新读取宿主规则。", { exact: true }),
  ).toBeVisible({ timeout: 20000 });
  assert.equal((await read()).persistence, "persisted");
  const savedRules = await read();
  const devicesBefore = await (
    await page.request.get(new URL("/api/v1/usb", page.url()).href)
  ).json();
  await previewButton.click();
  const firstPreview = previewPanel.locator('[data-preview-device="1-1.2#3"]');
  await expect(firstPreview).toContainText("命中：任意 · 规则 1");
  await expect(firstPreview).toContainText("当前可参与自动分配");
  await expect(
    previewPanel.locator('[data-preview-device="1-1.3#4"]'),
  ).toContainText("自动连接已锁定");
  await expect(
    previewPanel.locator('[data-preview-device="2-1#7"]'),
  ).toContainText("DroidVM 没有记录其归属");
  await firstPreview
    .getByRole("button", { name: "定位命中规则", exact: true })
    .click();
  await expect(editor.locator('[data-rule="any-0"]')).toBeFocused();
  const devicesAfter = await (
    await page.request.get(new URL("/api/v1/usb", page.url()).href)
  ).json();
  assert.deepEqual(devicesAfter, devicesBefore);
  assert.deepEqual(await read(), savedRules);
  await firstPreview.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "usb-rules-preview-320.png") });
  await fixture.command("usb-rules-touch");
  await previewButton.click();
  await expect(previewPanel).toContainText("宿主规则已经变化");
  await expect(previewPanel.locator("[data-preview-device]")).toHaveCount(0);
  assert.equal(commits.length, 3);
  assert(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  await page.unroute("**/api/v1/usb-rules/commit");
  await page.unroute("**/api/v1/usb-rules/status");
}
