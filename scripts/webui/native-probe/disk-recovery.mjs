import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyDiskRecovery({ page, post, output, expect }) {
  await page.goto(new URL("/#/resources/disk", page.url()).href);
  const region = page.getByRole("region", {
    name: "磁盘中断恢复",
    exact: true,
  });
  const button = (name) => page.getByRole("button", { name, exact: true });
  const dialog = () => page.getByRole("dialog");
  const writes = [],
    recoveries = [];
  page.on("request", (request) => {
    if (request.method() !== "POST") return;
    if (request.url().endsWith("/disk-operations"))
      writes.push(request.postDataJSON());
    if (request.url().endsWith("/disk-recovery"))
      recoveries.push(request.postDataJSON());
  });
  await expect(
    region.getByText("当前日志记录：2 条", { exact: true }),
  ).toBeVisible();
  assert.equal(
    await page.evaluate(
      async () =>
        (await fetch("/api/v1/disk-recovery", { credentials: "omit" })).status,
    ),
    401,
  );
  const missingCsrf = await page.request.post(
    new URL("/api/v1/disk-recovery", page.url()).href,
    { data: {} },
  );
  assert.equal(missingCsrf.status(), 403);
  await button("新建磁盘").click();
  await dialog().getByLabel("宿主目录", { exact: true }).fill("/fixture");
  await dialog()
    .getByLabel("文件名", { exact: true })
    .fill("fixture-uncertain.qcow2");
  await dialog()
    .getByRole("button", { name: "核对操作与影响范围", exact: true })
    .click();
  await dialog().getByLabel("我已核对参数和影响范围", { exact: true }).check();
  await dialog().getByRole("button", { name: "执行操作", exact: true }).click();
  await expect(async () => {
    await button("查询当前状态").click();
    await expect(
      page.locator(".disk-status").getByText(/COMMIT_UNCERTAIN/),
    ).toBeVisible();
  }).toPass({ timeout: 10000 });
  await expect(button("新建磁盘")).toBeDisabled();
  await region.getByRole("button", { name: "尝试恢复", exact: true }).click();
  await expect(dialog().getByText(/不会重新提交原来的操作/)).toBeVisible();
  await dialog().getByRole("button", { name: "取消", exact: true }).click();
  assert.equal(recoveries.length, 0);
  await region.getByRole("button", { name: "尝试恢复", exact: true }).click();
  assert.equal(
    await dialog().evaluate((el) => el.scrollWidth <= el.clientWidth),
    true,
  );
  await page.screenshot({
    path: join(output, "disk-recovery-confirm-320.png"),
  });
  await page.route("**/api/v1/disk-recovery", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    await route.fetch();
    await route.abort();
  });
  await dialog().getByRole("button", { name: "尝试恢复", exact: true }).click();
  await expect(
    region.getByText("操作结果待核实，将继续查询。不会自动重发操作。", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(button("新建磁盘")).toBeDisabled();
  await page.unroute("**/api/v1/disk-recovery");
  await region
    .getByRole("button", { name: "查询恢复状态", exact: true })
    .click();
  await expect(
    region.getByText(/本次恢复检查结束时剩余 1 条记录/),
  ).toBeVisible();
  await expect(button("新建磁盘")).toBeDisabled();
  assert.equal(recoveries.length, 1);
  const duplicate = await post(
    "disk-recovery",
    { requestId: recoveries[0].requestId },
    202,
  );
  assert.equal(duplicate.task.pendingRecords, 1);
  const count = await (
    await page.request.get(new URL("/api/v1/disk-recovery", page.url()).href)
  ).json();
  assert.equal(count.pendingRecords, 1);
  const wrongKind = await page.request.get(
    new URL(`/api/v1/disk-recovery/${writes[0].requestId}`, page.url()).href,
  );
  assert.equal(wrongKind.status(), 409);
  await region.getByRole("button", { name: "尝试恢复", exact: true }).click();
  await dialog().getByRole("button", { name: "尝试恢复", exact: true }).click();
  await expect(async () => {
    await region
      .getByRole("button", { name: "查询恢复状态", exact: true })
      .click();
    await expect(button("新建磁盘")).toBeEnabled();
  }).toPass({ timeout: 10000 });
  await expect(
    region.getByText(/本次恢复检查结束时剩余 0 条记录/),
  ).toBeVisible();
  await expect(
    region.getByText("当前日志记录：0 条", { exact: true }),
  ).toBeVisible();
  assert.equal(writes.length, 1);
  assert.equal(recoveries.length, 2);
  await region.scrollIntoViewIfNeeded();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "disk-recovery-result-320.png") });
  writeFileSync(
    join(output, "disk-recovery.json"),
    JSON.stringify(
      {
        passed: true,
        originalWrites: writes.length,
        recoveryAttempts: recoveries.length,
        duplicateDoesNotReplay: true,
        unknownQueriesOnly: true,
        partialRecoveryStaysLocked: true,
        zeroRecordsRefreshUnlocks: true,
        width: 320,
        execution: "synthetic journal counter; no filesystem recovery",
      },
      null,
      2,
    ),
  );
}
