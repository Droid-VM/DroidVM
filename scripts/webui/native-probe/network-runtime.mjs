import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyNetworkRuntime({ page, post, output, expect }) {
  const { NetworkDetail, HostNetworkCatalog } = await import(
    "../../../webui/src/generated/validators.js"
  );
  const url = (path) => new URL(path, page.url()).href;
  const get = async (path) => {
    const response = await page.request.get(url(path));
    assert.equal(response.status(), 200);
    return response.json();
  };
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (path) => page.locator(`[id="network-${path}"]`);
  const writes = [];
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().endsWith("/network-operations")
    )
      writes.push(request.postDataJSON());
  });
  await page.goto(url("/#/resources/network"));
  assert.equal(
    await page.evaluate(
      async () =>
        (await fetch("/api/v1/host/network", { credentials: "omit" })).status,
    ),
    401,
  );
  assert.equal(
    (
      await page.request.post(url("/api/v1/network-operations"), { data: {} })
    ).status(),
    403,
  );
  const host = await get("/api/v1/host/network");
  assert(HostNetworkCatalog(host));
  assert(!JSON.stringify(host).includes("private"));
  const before = await get("/api/v1/networks/fixture-network");
  assert(NetworkDetail(before));
  assert.equal(before.liveState, "inactive");
  assert.equal(before.addresses, null);
  await button("网络详情").click();
  await expect(page.getByText("已停止", { exact: true })).toBeVisible();
  await button("启动网络").click();
  await expect(
    page.getByRole("dialog").filter({ hasText: "确认对" }),
  ).toBeVisible();
  await button("确认").click();
  await expect(page.getByText("运行中", { exact: true })).toBeVisible({
    timeout: 12000,
  });
  const running = await get("/api/v1/networks/fixture-network");
  assert(NetworkDetail(running));
  assert.equal(running.runtime.state, "RUNNING");
  assert.equal(running.leases[0].expires, "9007199254740993");
  assert.equal(running.observationComplete, false);
  assert(!JSON.stringify(running).includes("/private/"));
  const duplicate = await post("network-operations", writes[0], 202);
  assert.equal(duplicate.task.requestId, writes[0].requestId);
  assert.equal(
    (await get("/api/v1/networks/fixture-network")).runtime.generation,
    running.runtime.generation,
  );
  const stale = await post(
    "network-operations",
    { ...writes[0], requestId: "network-stale-check" },
    202,
  );
  await expect(async () => {
    const result = await get("/api/v1/network-operations/network-stale-check");
    assert.equal(result.task.state, "failed");
    assert.equal(result.task.errorCode, "CONFLICT");
  }).toPass({ timeout: 6000 });
  assert(stale.task.id);
  await page.screenshot({ path: join(output, "network-runtime-320.png") });
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  // Fetch the response to execute exactly once, then lose it at the browser.
  await page.route("**/api/v1/network-operations", async (route) => {
    await route.fetch();
    await route.abort();
  });
  await button("停止网络").click();
  await button("确认").click();
  await expect(page.getByText("已停止", { exact: true })).toBeVisible({
    timeout: 12000,
  });
  await page.unroute("**/api/v1/network-operations");
  assert.equal(writes.length, 2);
  const stopped = await get("/api/v1/networks/fixture-network");
  assert.equal(
    stopped.runtime.generation,
    (BigInt(running.runtime.generation) + 2n).toString(),
  );
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await button("编辑网络").click();
  await field("uplink_mode").selectOption("l2");
  await field("l2.uplink").fill("custom0");
  await expect(field("l2.uplink")).toHaveValue("custom0");
  const suggestions = page.locator(".uplink-suggestions");
  await expect(suggestions).toBeVisible();
  await suggestions
    .getByRole("combobox", { name: "选择宿主上行建议" })
    .selectOption("WiFi");
  await expect(field("l2.uplink")).toHaveValue("WiFi");
  await expect(field("l2.pseudo_bridge")).toBeChecked();
  await expect(
    suggestions.getByText("IPv4 · 192.0.2.10", { exact: true }),
  ).toBeVisible();
  await field("l2.uplink").fill("custom1");
  await suggestions
    .getByRole("button", { name: "刷新列表", exact: true })
    .click();
  await expect(field("l2.uplink")).toHaveValue("custom1");
  await page.screenshot({ path: join(output, "network-uplinks-320.png") });
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  writeFileSync(
    join(output, "network-runtime.json"),
    JSON.stringify(
      {
        passed: true,
        browserWrites: writes.length,
        cases: [
          "permissions",
          "bounded host projection",
          "inactive versus observed detail",
          "exact lease expiry",
          "private field filtering",
          "conditional start/stop",
          "duplicate request task reuse",
          "stale generation rejection",
          "lost receipt without replay",
          "custom uplink preserved during discovery",
          "explicit WiFi selection enables pseudo bridge",
          "320px layout",
        ],
        scope:
          "Real HTTP, session, task and projection adapters; synthetic runtime and persistence, no kernel network actions.",
      },
      null,
      2,
    ),
  );
}
