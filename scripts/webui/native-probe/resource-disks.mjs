import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyDiskDetails({ page, output, expect }) {
  const url = new URL("/api/v1/disks/fixture-disk", page.url()).href;
  assert.equal(
    await page.evaluate(
      async () =>
        (await fetch("/api/v1/disks/fixture-disk", { credentials: "omit" }))
          .status,
    ),
    401,
  );
  const response = await page.request.get(url);
  assert.equal(response.status(), 200);
  const data = await response.json();
  assert.equal(data.inspection.virtualBytes, "18446744073709551615");
  assert.equal(data.inspection.format, "qcow2");
  assert.equal(JSON.stringify(data).includes("/private/"), false);
  assert.equal(data.attachments[0].vmId, "native-http-vm");
  assert.equal(
    (
      await page.request.get(new URL("/api/v1/disks/missing", url).href)
    ).status(),
    404,
  );
  assert.equal(
    (
      await page.request.get(
        new URL("/api/v1/disks/fixture-disk/extra", url).href,
      )
    ).status(),
    400,
  );
  await page.goto(new URL("/#/resources/disk", page.url()).href);
  const card = page.locator("article").filter({
    has: page.getByRole("heading", { name: "测试磁盘.qcow2", exact: true }),
  });
  await card.getByRole("button", { name: "磁盘详情", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "磁盘详情", exact: true });
  await expect(
    dialog.getByText("镜像信息已读取", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText("18,446,744,073,709,551,615 字节", { exact: true }),
  ).toBeVisible();
  await expect(dialog.getByText("直接挂载此盘", { exact: true })).toBeVisible();
  await expect(
    dialog.getByText("磁盘槽 1 · 只读挂载", { exact: true }),
  ).toBeVisible();
  for (const [state, label] of [
    ["busy", "磁盘被占用或文件已变化，暂时无法读取镜像信息"],
    ["unavailable", "暂时无法读取镜像信息"],
    ["invalid-chain", "登记路径或父盘链无效"],
  ]) {
    const copy = structuredClone(data);
    copy.inspection = Object.fromEntries(
      Object.keys(copy.inspection).map((key) => [
        key,
        key === "state" ? state : null,
      ]),
    );
    await page.route("**/api/v1/disks/fixture-disk", (route) =>
      route.fulfill({ json: copy }),
    );
    await dialog.getByRole("button", { name: "刷新列表", exact: true }).click();
    await expect(dialog.getByText(label, { exact: true })).toBeVisible();
    await expect(
      dialog.getByText("18,446,744,073,709,551,615 字节", { exact: true }),
    ).toHaveCount(0);
    await page.unroute("**/api/v1/disks/fixture-disk");
  }
  await page.route("**/api/v1/disks/fixture-disk", (route) =>
    route.fulfill({ status: 503, json: { error: { code: "unavailable" } } }),
  );
  await dialog.getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(dialog.locator("h3")).toHaveCount(0);
  await page.unroute("**/api/v1/disks/fixture-disk");
  await dialog.getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(
    dialog.getByText("镜像信息已读取", { exact: true }),
  ).toBeVisible();
  assert.equal(
    await dialog.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
    true,
  );
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({
    path: join(output, "disk-details-320.png"),
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  const broken = page.locator("article").filter({
    has: page.getByRole("heading", { name: "失效磁盘", exact: true }),
  });
  await broken.getByRole("button", { name: "磁盘详情", exact: true }).click();
  await expect(
    dialog.getByText("登记路径或父盘链无效", { exact: true }),
  ).toBeVisible();
  await expect(dialog.getByText("测试磁盘.qcow2", { exact: true })).toHaveCount(
    0,
  );
  writeFileSync(
    join(output, "disk-details.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "authenticated-detail-and-whitelist",
          "exact-uint64-image-size",
          "vm-configuration-reference",
          "missing-and-invalid-id",
          "busy-unavailable-invalid-chain",
          "failure-clears-stale-metadata",
          "selection-and-close",
          "mobile-320",
        ],
      },
      null,
      2,
    ),
  );
}
