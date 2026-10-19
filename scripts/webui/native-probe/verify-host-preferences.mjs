// Real native HostPreferencesService + production UI; persistence is injected in memory.
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
  join(resolve(values.output), "native-host-preferences-"),
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
    lookups = [],
    checks = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (
      request.method() === "POST" &&
      path === "/api/v1/host-preference-operations"
    )
      writes.push(request.postDataJSON());
    if (
      request.method() === "GET" &&
      path.startsWith("/api/v1/host-preference-operations/")
    )
      lookups.push(path.split("/").at(-1));
  });
  const ui = (key) => page.getByTestId(`host-preferences-${key}`);
  const option = (key) => page.getByTestId(`host-preference-${key}`);
  const logOption = option("vm_clear_logs_before_start"),
    sdOption = option("optimize_sdcard"),
    hugeOption = option("vm_hugepage_auto_reserve");
  const get = async (path) => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const state = () => get("host/preferences");
  const stats = () => fixture.command("host-preferences-stats");
  const terminal = async (id) => {
    let task;
    await expect(async () => {
      task = (await get(`host-preference-operations/${encodeURIComponent(id)}`))
        .task;
      assert(task && ["succeeded", "failed", "cancelled"].includes(task.state));
    }).toPass({ timeout: 10000 });
    assert.equal(task.kind, "host_preferences");
    assert.equal(task.resource, "host-preferences");
    assert.equal(task.requestId, id);
    return task;
  };
  const refresh = async () => {
    await expect(ui("refresh")).toBeEnabled();
    const response = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/host/preferences") &&
        response.status() === 200,
    );
    await ui("refresh").click();
    await response;
    await expect(ui("refresh")).toBeEnabled();
  };
  const noOverflow = async () =>
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
  const screenshot = async (name) => {
    await noOverflow();
    await page.evaluate(() => document.activeElement?.blur());
    await page.screenshot({
      path: join(output, `${name}-320.png`),
      fullPage: true,
    });
  };
  const review = async () => {
    await expect(ui("review")).toBeEnabled();
    await ui("review").click();
    await expect(ui("confirm")).toBeEnabled();
  };
  const confirm = async () => {
    const before = writes.length;
    await ui("confirm").click();
    await expect(async () => assert.equal(writes.length, before + 1)).toPass();
    return writes.at(-1);
  };
  await page.goto(`${fixture.origin}/#/settings/vm`);
  await page
    .locator("#pairing-token")
    .fill((await fixture.command("pair")).pairingToken);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(ui("page")).toBeVisible();
  await expect(logOption).toBeEnabled();
  const session = await get("session");
  assert(session.server.capabilities.includes("host.preferences.v1"));
  const context = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(
      `${fixture.origin}/api/v1/host-preference-operations`,
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
  const defaults = {
    vm_hugepage_auto_reserve: true,
    optimize_sdcard: true,
    vm_clear_logs_before_start: false,
  };
  assert.equal(initial.initialized, false);
  assert.equal(initial.uncertain, false);
  assert.deepEqual(initial.values, defaults);
  assert.equal((await stats()).effects, 0);
  await expect(hugeOption).toBeChecked();
  await expect(sdOption).toBeChecked();
  await expect(logOption).not.toBeChecked();
  assert.equal(
    (await fetch(`${fixture.origin}/api/v1/host/preferences`)).status,
    401,
  );
  const guard = {
    requestId: "preferences-guard",
    expected: initial.expected,
    changes: { vm_clear_logs_before_start: true },
  };
  assert.equal(
    (
      await fetch(`${fixture.origin}/api/v1/host-preference-operations`, {
        method: "POST",
        headers: { Origin: fixture.origin, "Content-Type": "application/json" },
        body: JSON.stringify({ context, ...guard }),
      })
    ).status,
    401,
  );
  await post(guard, 403, { "X-DroidVM-CSRF": "" });
  await post({ ...guard, context: { ...context, daemonEpoch: "old" } }, 409);
  await post({ ...guard, changes: {} }, 400);
  await post({ ...guard, changes: { optimize_sdcard: "true" } }, 400);
  await post({ ...guard, changes: { arbitrary_command: true } }, 400);
  await post(
    { ...guard, changes: { ...defaults, arbitrary_command: true } },
    400,
  );
  await post({ ...guard, expected: { ...initial.expected, extra: true } }, 400);
  await post({ ...guard, extra: true }, 400);
  assert.equal((await stats()).effects, 0);
  checks.push(
    "read-defaults-without-writing",
    "auth-csrf-context",
    "strict-three-boolean-whitelist",
  );
  await screenshot("defaults");

  await fixture.command("host-preferences-external");
  const external = await state(),
    afterExternal = await stats();
  assert.equal(external.values.optimize_sdcard, false);
  assert.notEqual(external.expected.revision, initial.expected.revision);
  await post(guard);
  const stale = await terminal(guard.requestId);
  assert.equal(stale.state, "failed");
  assert.equal(stale.errorCode, "CONFLICT");
  assert.equal(stale.hostPreferenceResult.saved, false);
  assert.equal((await stats()).effects, afterExternal.effects);
  assert.deepEqual((await state()).values, external.values);
  await fixture.command("host-preferences-legacy");
  assert.deepEqual((await state()).values, external.values);
  assert.deepEqual((await state()).expected, external.expected);
  assert.equal((await stats()).effects, afterExternal.effects);
  checks.push(
    "stale-cas-does-not-write",
    "legacy-app-reconnect-cannot-overwrite-shared-values",
  );

  await post({
    requestId: "preferences-reset-sd",
    expected: external.expected,
    changes: { optimize_sdcard: true },
  });
  assert.equal((await terminal("preferences-reset-sd")).state, "succeeded");
  await refresh();
  await expect(sdOption).toBeChecked();
  await logOption.check();
  await review();
  const beforeConflict = writes.length;
  await fixture.command("host-preferences-external");
  // Close the review so the explicit refresh control is reachable on a phone.
  await page.keyboard.press("Escape");
  await refresh();
  await expect(ui("conflict")).toBeVisible();
  await expect(logOption).toBeChecked();
  await expect(ui("review")).toBeDisabled();
  assert.equal(writes.length, beforeConflict);
  await screenshot("conflict");
  await ui("rebase").click();
  await expect(sdOption).not.toBeChecked();
  await expect(logOption).toBeChecked();
  await expect(ui("conflict")).toHaveCount(0);
  await review();
  await screenshot("review");
  const expected = (await state()).expected;
  let allowLookup = false;
  // Lose the HTTP response after the real native service accepts exactly one save.
  await page.route("**/api/v1/host-preference-operations/*", (route) =>
    allowLookup ? route.continue() : route.abort(),
  );
  await page.route(
    "**/api/v1/host-preference-operations",
    async (route) => {
      assert.equal((await route.fetch()).status(), 202);
      await route.abort();
    },
    { times: 1 },
  );
  const save = await confirm();
  assert.deepEqual(save.expected, expected);
  assert.deepEqual(save.changes, { vm_clear_logs_before_start: true });
  const saved = await terminal(save.requestId);
  assert.equal(saved.state, "succeeded");
  assert.equal(saved.hostPreferenceResult.saved, true);
  const afterSave = await stats(),
    sent = writes.length;
  await expect(ui("query")).toBeEnabled();
  await expect(logOption).toBeDisabled();
  allowLookup = true;
  await ui("query").click();
  await expect(logOption).toBeEnabled();
  assert.equal(writes.length, sent);
  assert(lookups.includes(save.requestId));
  assert(lookups.every((id) => id === save.requestId));
  assert.equal((await stats()).effects, afterSave.effects);
  assert.equal((await state()).values.vm_clear_logs_before_start, true);
  await fixture.command("host-preferences-legacy");
  assert.equal((await state()).values.vm_clear_logs_before_start, true);
  assert.equal((await stats()).effects, afterSave.effects);
  await screenshot("saved");
  checks.push(
    "conflict-preserves-draft-and-merges-untouched-fields",
    "new-review-required-after-conflict",
    "lost-response-queries-original-request-without-replay",
  );

  await fixture.command("host-preferences-mode not-saved");
  const beforeFailure = await state();
  await hugeOption.uncheck();
  await review();
  const failedSave = await confirm(),
    failedTask = await terminal(failedSave.requestId);
  assert.equal(failedTask.state, "failed");
  assert.equal(failedTask.hostPreferenceResult.saved, false);
  assert.equal(failedTask.hostPreferenceResult.uncertain, false);
  await expect(ui("review")).toBeEnabled();
  assert.deepEqual((await state()).expected, beforeFailure.expected);
  assert.deepEqual((await state()).values, beforeFailure.values);
  await expect(hugeOption).not.toBeChecked();
  checks.push("definite-save-failure-retains-draft-and-native-values");

  await fixture.command("host-preferences-mode installed-uncertain");
  await review();
  const uncertainSave = await confirm(),
    uncertainTask = await terminal(uncertainSave.requestId);
  assert.equal(uncertainTask.state, "failed");
  assert.equal(uncertainTask.hostPreferenceResult.saved, false);
  assert.equal(uncertainTask.hostPreferenceResult.uncertain, true);
  assert.equal((await state()).uncertain, true);
  await expect(logOption).toBeDisabled();
  await expect(sdOption).toBeDisabled();
  await expect(hugeOption).toBeDisabled();
  await expect(ui("review")).toBeDisabled();
  await expect(ui("query")).toBeEnabled();
  const uncertainWrites = writes.length,
    uncertainStats = await stats();
  await ui("query").click();
  await refresh();
  await expect(ui("review")).toBeDisabled();
  assert.equal(writes.length, uncertainWrites);
  assert.equal((await stats()).effects, uncertainStats.effects);
  await screenshot("uncertain");
  checks.push(
    "installed-uncertain-is-not-success",
    "uncertain-ui-blocks-new-writes",
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
        fixture: await stats(),
        execution:
          "Production browser UI and real native HTTP/HostPreferencesService/TaskService with injected in-memory persistence. Persistence outcomes and legacy App sync are controlled by the fixture; no real Android App reconnect, disk durability or VM startup side effects are claimed.",
      },
      null,
      2,
    ),
  );
  console.log(`PASS Host preferences HTTP and phone UI: ${output}`);
} finally {
  await browser?.close();
  await fixture.close();
}
