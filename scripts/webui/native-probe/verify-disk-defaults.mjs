// Real native shared-default service + production UI; storage and image operations use fixtures.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { startFixture } from "./server-fixture.mjs";
const require = createRequire(
  new URL("../../../webui/package.json", import.meta.url),
);
const { chromium, expect } = require("@playwright/test");
const { values } = parseArgs({
  options: {
    exe: { type: "string" },
    output: { type: "string", default: "build/webui-native" },
  },
});
assert(values.exe, "--exe is required");
mkdirSync(values.output, { recursive: true });
const output = mkdtempSync(
  join(resolve(values.output), "native-disk-defaults-"),
);
const fixture = await startFixture(values.exe);
let browser;
try {
  browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({
    locale: "zh-CN",
    viewport: { width: 320, height: 568 },
  });
  const errors = [],
    writes = [],
    diskWrites = [],
    lookups = [],
    checks = [];
  let defaultReads = 0;
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (
      request.method() === "POST" &&
      path === "/api/v1/disk-default-operations"
    )
      writes.push(request.postDataJSON());
    if (request.method() === "POST" && path === "/api/v1/disk-operations")
      diskWrites.push(request.postDataJSON());
    if (
      request.method() === "GET" &&
      path.startsWith("/api/v1/disk-default-operations/")
    )
      lookups.push(path.split("/").at(-1));
    if (request.method() === "GET" && path === "/api/v1/host/disk-defaults")
      defaultReads++;
  });
  const ui = (key) => page.getByTestId(`disk-defaults-${key}`);
  const button = (name) => page.getByRole("button", { name, exact: true });
  const dialog = () => page.getByRole("dialog");
  const get = async (path) => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const state = () => get("host/disk-defaults");
  const stats = () => fixture.command("disk-defaults-stats");
  const terminal = async (id, path = "disk-default-operations") => {
    let task;
    await expect(async () => {
      task = (await get(`${path}/${encodeURIComponent(id)}`)).task;
      assert(task && ["succeeded", "failed", "cancelled"].includes(task.state));
    }).toPass({ timeout: 10000 });
    assert.equal(task.requestId, id);
    return task;
  };
  const refresh = async () => {
    await expect(ui("refresh")).toBeEnabled();
    const response = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/host/disk-defaults") &&
        response.status() === 200,
    );
    await ui("refresh").click();
    await response;
    await expect(ui("refresh")).toBeEnabled();
  };
  const screenshot = async (name) => {
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
    if (await dialog().count())
      assert(
        await dialog()
          .last()
          .evaluate((el) => el.scrollWidth <= el.clientWidth),
      );
    await page.evaluate(() => document.activeElement?.blur());
    await page.screenshot({
      path: join(output, `${name}-320.png`),
      fullPage: true,
    });
  };
  const confirm = async () => {
    const before = writes.length;
    await ui("confirm").click();
    await expect(async () => assert.equal(writes.length, before + 1)).toPass();
    return writes.at(-1);
  };
  await page.goto(`${fixture.origin}/#/more`);
  await page
    .locator("#pairing-token")
    .fill((await fixture.command("pair")).pairingToken);
  await button("登录").click();
  await ui("link").click();
  await expect(ui("compression")).toBeEnabled();
  const session = await get("session");
  assert(session.server.capabilities.includes("host.disk-defaults.v1"));
  const context = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(
      `${fixture.origin}/api/v1/disk-default-operations`,
      {
        headers: {
          Origin: fixture.origin,
          "X-DroidVM-CSRF": session.csrfToken,
          ...headers,
        },
        data: { context, ...data },
      },
    );
    assert.equal(response.status(), status);
    return response.json();
  };
  const initial = await state();
  assert.equal(initial.initialized, false);
  assert.deepEqual(initial.values, {
    optimizeCompression: "ask",
    qemuImgCpus: [],
  });
  assert.equal((await stats()).effects, 0);
  assert.equal(
    (await fetch(`${fixture.origin}/api/v1/host/disk-defaults`)).status,
    401,
  );
  const guard = {
    requestId: "disk-default-guard",
    expected: initial.expected,
    changes: { qemuImgCpus: [2, 3] },
  };
  await post(guard, 403, { "X-DroidVM-CSRF": "" });
  await post({ ...guard, context: { ...context, daemonEpoch: "old" } }, 409);
  for (const qemuImgCpus of [[2, 2], [-1], [1024], [1.5], ["2"]])
    await post({ ...guard, changes: { qemuImgCpus } }, 400);
  await post({ ...guard, changes: { optimizeCompression: "zstd" } }, 400);
  await post({ ...guard, changes: { command: "anything" } }, 400);
  await post({ ...guard, changes: {} }, 400);
  assert.equal((await stats()).effects, 0);
  const allCpus = Array.from({ length: 1024 }, (_, id) => id);
  const maximum = {
    ...guard,
    requestId: "disk-default-maximum",
    changes: { qemuImgCpus: [...allCpus].reverse() },
  };
  assert(Buffer.byteLength(JSON.stringify({ context, ...maximum })) > 4096);
  await post(maximum);
  assert.equal((await terminal(maximum.requestId)).state, "succeeded");
  assert.deepEqual((await state()).values.qemuImgCpus, allCpus);
  await fixture.command("disk-defaults-external");
  const external = await state(),
    externalStats = await stats();
  assert.deepEqual(external.values, {
    optimizeCompression: "none",
    qemuImgCpus: [2, 3],
  });
  await post(guard);
  assert.equal((await terminal(guard.requestId)).errorCode, "CONFLICT");
  assert.equal((await stats()).effects, externalStats.effects);
  await fixture.command("disk-defaults-legacy");
  assert.deepEqual((await state()).values, external.values);
  assert.deepEqual((await state()).expected, external.expected);
  assert.equal((await stats()).effects, externalStats.effects);
  await refresh();
  await screenshot("defaults");
  checks.push(
    "read-defaults-without-write",
    "auth-csrf-context-and-whitelist",
    "1024-unique-cpus-over-4k-body-round-trip",
    "stale-cas-and-legacy-reconnect-have-no-effect",
  );

  await ui("cpu-picker").getByRole("button").click();
  await dialog().getByRole("button", { name: "清空绑定", exact: true }).click();
  await dialog().getByRole("button", { name: "确认", exact: true }).click();
  await expect(ui("cpu-value")).toContainText("不绑定");
  await ui("compression").selectOption("ask");
  await fixture.command("disk-defaults-external");
  await refresh();
  await expect(ui("conflict")).toBeVisible();
  await expect(ui("cpu-value")).toContainText("不绑定");
  await expect(ui("compression")).toHaveValue("ask");
  await expect(ui("review")).toBeDisabled();
  await ui("rebase").click();
  await ui("review").click();
  await screenshot("review");
  let allowLookup = false;
  await page.route("**/api/v1/disk-default-operations/*", (route) =>
    allowLookup ? route.continue() : route.abort(),
  );
  await page.route(
    "**/api/v1/disk-default-operations",
    async (route) => {
      assert.equal((await route.fetch()).status(), 202);
      await route.abort();
    },
    { times: 1 },
  );
  const lost = await confirm(),
    lostTask = await terminal(lost.requestId);
  assert.equal(lostTask.state, "succeeded");
  assert.deepEqual(lost.changes, {
    optimizeCompression: "ask",
    qemuImgCpus: [],
  });
  const sent = writes.length,
    savedStats = await stats();
  await expect(ui("query")).toBeEnabled();
  allowLookup = true;
  await ui("query").click();
  await expect(ui("compression")).toBeEnabled();
  assert.equal(writes.length, sent);
  assert(lookups.includes(lost.requestId));
  assert(lookups.every((id) => id === lost.requestId));
  assert.equal((await stats()).effects, savedStats.effects);
  checks.push(
    "real-cpu-picker-clears-binding",
    "array-draft-survives-cas-and-needs-new-review",
    "lost-save-only-queries-original-request",
  );

  await fixture.command("disk-defaults-external");
  await page.goto(`${fixture.origin}/#/resources/disk`);
  await expect(button("新建磁盘")).toBeEnabled();
  await page.route("**/api/v1/host/disk-defaults", (route) => route.abort(), {
    times: 1,
  });
  await button("新建磁盘").click();
  await expect(dialog().locator(".notice")).toBeVisible();
  await expect(dialog().getByLabel("宿主目录", { exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await button("新建磁盘").click();
  const cpuInput = () =>
    dialog().getByLabel("操作使用的 CPU 核心", { exact: true });
  await dialog().getByLabel("宿主目录", { exact: true }).fill("/fixture");
  await dialog()
    .getByLabel("文件名", { exact: true })
    .fill("shared-default-retry.qcow2");
  await dialog().getByText("高级选项", { exact: true }).click();
  await expect(cpuInput()).toHaveValue("2-3");
  await cpuInput().fill("7");
  await post({
    requestId: "disk-default-change-open",
    expected: (await state()).expected,
    changes: { qemuImgCpus: [0, 1] },
  });
  await terminal("disk-default-change-open");
  assert.deepEqual((await state()).values.qemuImgCpus, [0, 1]);
  await expect(cpuInput()).toHaveValue("7");
  await screenshot("disk-edited");
  await cpuInput().fill("");
  const runRejectedDisk = async () => {
    await dialog()
      .getByRole("button", { name: "核对操作与影响范围", exact: true })
      .click();
    await dialog()
      .getByLabel("我已核对参数和影响范围", { exact: true })
      .check();
    await page.route(
      "**/api/v1/disk-operations",
      (route) =>
        route.continue({
          postData: JSON.stringify({
            ...route.request().postDataJSON(),
            vmRevision: "stale",
          }),
        }),
      { times: 1 },
    );
    const before = diskWrites.length;
    await dialog()
      .getByRole("button", { name: "执行操作", exact: true })
      .click();
    await expect(dialog()).toHaveCount(0);
    await expect(async () =>
      assert.equal(diskWrites.length, before + 1),
    ).toPass();
    assert.equal(
      (await terminal(diskWrites.at(-1).requestId, "disk-operations")).state,
      "failed",
    );
    await expect(async () => {
      await page
        .locator(".disk-status")
        .getByRole("button", { name: "查询当前状态", exact: true })
        .click();
      await expect(button("调整后重试")).toBeEnabled();
    }).toPass({ timeout: 10000 });
  };
  await runRejectedDisk();
  assert(!("cpus" in diskWrites.at(-1).operation));
  assert.equal(diskWrites.at(-1).operation.compress, "none");
  await fixture.command("disk-defaults-external");
  const readsBeforeRetry = defaultReads;
  await button("调整后重试").click();
  await dialog().getByText("高级选项", { exact: true }).click();
  await expect(cpuInput()).toHaveValue("");
  assert.equal(defaultReads, readsBeforeRetry);
  await cpuInput().fill("7");
  await runRejectedDisk();
  assert.deepEqual(diskWrites.at(-1).operation.cpus, [7]);
  await button("调整后重试").click();
  await dialog().getByText("高级选项", { exact: true }).click();
  await expect(cpuInput()).toHaveValue("7");
  assert.equal(defaultReads, readsBeforeRetry);
  await screenshot("retry-retains-cpus");
  await page.keyboard.press("Escape");
  checks.push(
    "defaults-read-failure-blocks-form-preparation",
    "new-disk-form-prefills-saved-cpus-once",
    "saved-default-changes-do-not-replace-edits",
    "explicit-unbound-omits-cpus",
    "retry-keeps-explicit-or-unbound-cpus-without-rereading-defaults",
  );

  await page.goto(`${fixture.origin}/#/settings/disks`);
  await expect(ui("compression")).toBeEnabled();
  await refresh();
  await fixture.command("disk-defaults-mode installed-uncertain");
  await ui("compression").selectOption("ask");
  await ui("review").click();
  const uncertain = await confirm(),
    uncertainTask = await terminal(uncertain.requestId);
  assert.equal(uncertainTask.state, "failed");
  assert.equal(uncertainTask.diskDefaultResult.uncertain, true);
  await expect(ui("uncertain")).toBeVisible();
  await expect(ui("compression")).toBeDisabled();
  await expect(ui("review")).toBeDisabled();
  await screenshot("uncertain");
  checks.push(
    "uncertain-save-blocks-further-editing",
    "320px-no-horizontal-overflow",
  );
  assert.deepEqual(errors, []);
  writeFileSync(
    join(output, "results.json"),
    JSON.stringify(
      {
        passed: true,
        width: 320,
        checks,
        uiPosts: writes.length,
        diskPosts: diskWrites.length,
        fixture: await stats(),
        execution:
          "Production UI and real native HTTP/shared-default service with injected in-memory persistence. Disk operation admission uses the existing synthetic fixture; deliberately stale disk revisions perform no image mutations. No real Android migration, CPU scheduling, disk compression or durability is claimed.",
      },
      null,
      2,
    ),
  );
  console.log(`PASS Disk defaults HTTP and phone UI: ${output}`);
} finally {
  await browser?.close();
  await fixture.close();
}
