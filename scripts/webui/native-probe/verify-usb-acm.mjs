// Real native USB ACM service and production UI; the store and USB gadget are simulated.
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
const output = mkdtempSync(join(resolve(values.output), "native-usb-acm-"));
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
    if (request.method() === "POST" && path === "/api/v1/usb-acm-operations")
      writes.push(request.postDataJSON());
    if (
      request.method() === "GET" &&
      path.startsWith("/api/v1/usb-acm-operations/")
    )
      lookups.push(path.split("/").at(-1));
  });
  const ui = (key) => page.getByTestId(`usb-acm-${key}`);
  const get = async (path) => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const state = () => get("host/usb-acm");
  const stats = () => fixture.command("usb-acm-stats");
  const terminal = async (id) => {
    let task;
    await expect(async () => {
      task = (await get(`usb-acm-operations/${encodeURIComponent(id)}`)).task;
      assert(task && ["succeeded", "failed", "cancelled"].includes(task.state));
    }).toPass({ timeout: 10000 });
    assert.equal(task.kind, "usb_acm");
    assert.equal(task.resource, "usb-acm");
    assert.equal(task.requestId, id);
    return task;
  };
  const refresh = async () => {
    await expect(ui("refresh")).toBeEnabled();
    const response = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/v1/host/usb-acm") &&
        response.status() === 200,
    );
    await ui("refresh").click();
    await response;
    await expect(ui("refresh")).toBeEnabled();
  };
  const mode = async (name) => {
    await fixture.command(`usb-acm-mode ${name}`);
    await refresh();
  };
  const unchangedEffects = (actual, before) => {
    assert.equal(actual.saves, before.saves);
    assert.equal(actual.applies, before.applies);
  };
  const screenshot = async (name) => {
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
    await page.evaluate(() => document.activeElement?.blur());
    await page.screenshot({
      path: join(output, `${name}-320.png`),
      fullPage: true,
    });
  };
  const review = async (kind = "configure") => {
    await expect(ui(kind === "configure" ? "review" : "apply")).toBeEnabled();
    await ui(kind === "configure" ? "review" : "apply").click();
    await expect(ui("confirm")).toBeDisabled();
    await ui("confirm-disconnect").check();
    await expect(ui("confirm")).toBeEnabled();
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
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(ui("link")).toBeVisible();
  await ui("link").click();
  await expect(ui("page")).toBeVisible();
  await expect(ui("enabled")).toBeEnabled();
  const session = await get("session");
  assert(session.server.capabilities.includes("host.usb-acm.v1"));
  const context = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(
      `${fixture.origin}/api/v1/usb-acm-operations`,
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
  const initial = await state(),
    initialStats = await stats();
  assert.equal(initial.initialized, false);
  assert.equal(initialStats.saves, 0);
  assert.equal(initialStats.applies, 0);
  assert.equal(
    (await fetch(`${fixture.origin}/api/v1/host/usb-acm`)).status,
    401,
  );
  const guard = {
    requestId: "acm-guard",
    expected: initial.expected,
    operation: { kind: "configure", enabled: true, ports: 2 },
  };
  assert.equal(
    (
      await fetch(`${fixture.origin}/api/v1/usb-acm-operations`, {
        method: "POST",
        headers: { Origin: fixture.origin, "Content-Type": "application/json" },
        body: JSON.stringify({ context, ...guard }),
      })
    ).status,
    401,
  );
  await post(guard, 403, { "X-DroidVM-CSRF": "" });
  await post({ ...guard, context: { ...context, daemonEpoch: "old" } }, 409);
  for (const ports of [0, 7, 1.5, "2"])
    await post({ ...guard, operation: { ...guard.operation, ports } }, 400);
  await post(
    { ...guard, operation: { ...guard.operation, enabled: "true" } },
    400,
  );
  await post(
    { ...guard, operation: { ...guard.operation, command: "anything" } },
    400,
  );
  await post({ ...guard, operation: { kind: "apply", ports: 2 } }, 400);
  unchangedEffects(await stats(), initialStats);
  await screenshot("defaults");
  checks.push(
    "app-category-entry-and-read-only-defaults",
    "auth-csrf-context",
    "fixed-operations-boolean-and-port-range",
  );

  await fixture.command("usb-acm-external");
  const external = await state(),
    externalStats = await stats();
  assert.deepEqual(external.desired, { enabled: true, ports: 3 });
  await post(guard);
  const stale = await terminal(guard.requestId);
  assert.equal(stale.state, "failed");
  assert.equal(stale.errorCode, "CONFLICT");
  assert.equal(stale.usbAcmResult.saved, false);
  unchangedEffects(await stats(), externalStats);
  await fixture.command("usb-acm-legacy");
  assert.deepEqual((await state()).desired, external.desired);
  assert.deepEqual((await state()).expected, external.expected);
  unchangedEffects(await stats(), externalStats);
  await refresh();
  await mode("busy");
  await expect(ui("owners")).toContainText("fixture-acm-vm");
  await ui("ports").selectOption("2");
  await expect(ui("review")).toBeDisabled();
  const beforeBusy = await stats();
  await post({
    ...guard,
    requestId: "acm-busy",
    expected: (await state()).expected,
  });
  const busy = await terminal("acm-busy");
  assert.equal(busy.state, "failed");
  assert.equal(busy.usbAcmResult.saved, false);
  unchangedEffects(await stats(), beforeBusy);
  await screenshot("occupied");
  checks.push(
    "stale-cas-has-no-save-or-apply",
    "legacy-reconnect-keeps-authoritative-target",
    "active-vm-blocks-rebind-before-save",
  );

  await mode("ready");
  await fixture.command("usb-acm-external");
  await refresh();
  await expect(ui("conflict")).toBeVisible();
  await expect(ui("ports")).toHaveValue("2");
  await expect(ui("review")).toBeDisabled();
  await screenshot("conflict");
  await ui("rebase").click();
  await expect(ui("conflict")).toHaveCount(0);
  await review();
  await screenshot("review");
  let allowLookup = false;
  await page.route("**/api/v1/usb-acm-operations/*", (route) =>
    allowLookup ? route.continue() : route.abort(),
  );
  await page.route(
    "**/api/v1/usb-acm-operations",
    async (route) => {
      assert.equal((await route.fetch()).status(), 202);
      await route.abort();
    },
    { times: 1 },
  );
  const lost = await confirm(),
    lostTask = await terminal(lost.requestId);
  assert.equal(lostTask.state, "succeeded");
  assert.deepEqual(lost.operation, {
    kind: "configure",
    enabled: true,
    ports: 2,
  });
  const afterLost = await stats(),
    sent = writes.length;
  await expect(ui("query")).toBeEnabled();
  await expect(ui("enabled")).toBeDisabled();
  allowLookup = true;
  await ui("query").click();
  await expect(ui("enabled")).toBeEnabled();
  assert.equal(writes.length, sent);
  assert(lookups.includes(lost.requestId));
  assert(lookups.every((id) => id === lost.requestId));
  unchangedEffects(await stats(), afterLost);
  checks.push(
    "revision-conflict-preserves-target-and-requires-new-review",
    "disconnect-confirmation-required",
    "lost-receipt-only-queries-original-request",
  );

  await mode("apply-failed");
  await ui("ports").selectOption("4");
  await review();
  const failed = await confirm(),
    failedTask = await terminal(failed.requestId);
  assert.equal(failedTask.state, "failed");
  assert.equal(failedTask.usbAcmResult.saved, true);
  assert.equal(failedTask.usbAcmResult.applied, false);
  assert.equal(failedTask.usbAcmResult.uncertain, false);
  const partial = await state();
  assert.equal(partial.desired.ports, 4);
  assert.equal(partial.live.ports, 2);
  assert.equal(partial.live.applied, false);
  await expect(ui("apply")).toBeEnabled();
  await expect(ui("review")).toBeDisabled();
  await screenshot("saved-not-applied");
  const beforeApply = await stats(),
    beforeApplyPosts = writes.length;
  await mode("ready");
  assert.equal(writes.length, beforeApplyPosts);
  unchangedEffects(await stats(), beforeApply);
  await review("apply");
  const applied = await confirm(),
    appliedTask = await terminal(applied.requestId);
  assert.deepEqual(applied.operation, { kind: "apply" });
  assert.equal(appliedTask.state, "succeeded");
  assert.equal(appliedTask.usbAcmResult.applied, true);
  const afterApply = await stats();
  assert.equal(afterApply.saves, beforeApply.saves);
  assert.equal(afterApply.applies, beforeApply.applies + 1);
  await expect(ui("apply")).toHaveCount(0);
  checks.push(
    "saved-target-distinct-from-current-composition",
    "known-apply-failure-requires-explicit-reviewed-apply",
    "apply-does-not-save-target-again",
  );

  await mode("apply-unknown");
  await ui("ports").selectOption("5");
  await review();
  const unknown = await confirm(),
    unknownTask = await terminal(unknown.requestId);
  assert.equal(unknownTask.state, "failed");
  assert.equal(unknownTask.usbAcmResult.uncertain, true);
  await expect(ui("uncertain")).toBeVisible();
  await expect(ui("enabled")).toBeDisabled();
  await expect(ui("ports")).toBeDisabled();
  await expect(ui("review")).toBeDisabled();
  await expect(ui("apply")).toBeDisabled();
  await expect(ui("query")).toBeEnabled();
  const beforeQuery = await stats(),
    queryPosts = writes.length;
  await ui("query").click();
  await refresh();
  assert.equal(writes.length, queryPosts);
  unchangedEffects(await stats(), beforeQuery);
  await expect(ui("review")).toBeDisabled();
  await screenshot("uncertain");
  await fixture.command("usb-acm-mode ready");
  await page.reload();
  await expect(ui("uncertain")).toBeVisible();
  await expect(ui("enabled")).toBeDisabled();
  await expect(ui("review")).toBeDisabled();
  await post({
    requestId: "acm-unknown-after-reload",
    expected: (await state()).expected,
    operation: { kind: "apply" },
  });
  const blocked = await terminal("acm-unknown-after-reload");
  assert.equal(blocked.state, "failed");
  assert.equal(blocked.errorCode, "COMMIT_UNCERTAIN");
  unchangedEffects(await stats(), beforeQuery);
  checks.push(
    "unknown-application-blocks-new-writes",
    "reload-and-restored-observation-keep-unknown-lock",
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
          "Real native HTTP/UsbAcmService/TaskService and production UI with injected in-memory persistence and simulated ACM pool. No physical USB gadget, re-enumeration, Android App reconnect, disk durability or real VM serial attachment is claimed.",
      },
      null,
      2,
    ),
  );
  console.log(`PASS USB ACM HTTP and phone UI: ${output}`);
} finally {
  await browser?.close();
  await fixture.close();
}
