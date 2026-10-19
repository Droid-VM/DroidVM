import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyDiskActions({ page, post, output, expect }) {
  await page.goto(new URL("/#/resources/disk", page.url()).href);
  const button = (name) => page.getByRole("button", { name, exact: true });
  const dialog = () => page.getByRole("dialog");
  const posts = [];
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().endsWith("/disk-operations")
    )
      posts.push(request.postDataJSON());
  });
  const review = async () => {
    await dialog()
      .getByRole("button", { name: "核对操作与影响范围", exact: true })
      .click();
    await expect(
      dialog().getByRole("button", { name: "执行操作", exact: true }),
    ).toBeDisabled();
  };
  const run = async () => {
    await dialog()
      .getByLabel("我已核对参数和影响范围", { exact: true })
      .check();
    await dialog()
      .getByRole("button", { name: "执行操作", exact: true })
      .click();
    await expect(dialog()).toHaveCount(0);
  };
  const settle = async () => {
    await expect(async () => {
      await button("查询当前状态").click();
      await expect(button("新建磁盘")).toBeEnabled();
    }).toPass({ timeout: 10000 });
  };
  const open = async (name, action) => {
    await page
      .locator("article")
      .filter({ has: page.getByRole("heading", { name, exact: true }) })
      .getByRole("button", { name: "磁盘详情", exact: true })
      .click();
    await dialog().getByRole("button", { name: action, exact: true }).click();
  };
  await button("新建磁盘").click();
  await dialog().getByLabel("宿主目录", { exact: true }).fill("/fixture");
  await dialog().getByLabel("文件名", { exact: true }).fill("精确容量.qcow2");
  await dialog()
    .getByRole("combobox", { name: /^容量单位/ })
    .selectOption("B");
  await dialog()
    .getByLabel("目标容量", { exact: true })
    .fill("9007199254740993");
  await dialog().getByText("高级选项", { exact: true }).click();
  await dialog()
    .getByLabel("操作使用的 CPU 核心", { exact: true })
    .fill("0-1,7");
  await review();
  await expect(
    dialog().getByText("9,007,199,254,740,993 字节", { exact: true }),
  ).toBeVisible();
  await dialog().getByRole("button", { name: "返回修改", exact: true }).click();
  assert.equal(posts.length, 0);
  await expect(dialog().getByLabel("目标容量", { exact: true })).toHaveValue(
    "9007199254740993",
  );
  await review();
  assert.equal(
    await dialog().evaluate((el) => el.scrollWidth <= el.clientWidth),
    true,
  );
  await page.screenshot({ path: join(output, "disk-create-review-320.png") });
  await run();
  await settle();
  assert.equal(posts[0].operation.size, "9007199254740993");
  assert.deepEqual(posts[0].operation.cpus, [0, 1, 7]);
  const result = await (
    await page.request.get(
      new URL(`/api/v1/disk-operations/${posts[0].requestId}`, page.url()).href,
    )
  ).json();
  assert(result.task.diskId && result.task.diskId !== "new");
  await button("查看结果磁盘").click();
  await expect(
    dialog().getByText("精确容量.qcow2", { exact: true }).first(),
  ).toBeVisible();
  await dialog().getByRole("button", { name: "调整容量", exact: true }).click();
  await dialog()
    .getByRole("combobox", { name: /^容量单位/ })
    .selectOption("GiB");
  await dialog().getByLabel("目标容量", { exact: true }).fill("4");
  await expect(
    dialog().getByRole("button", { name: "核对操作与影响范围", exact: true }),
  ).toBeDisabled();
  await dialog().getByLabel("允许缩小容量", { exact: true }).check();
  await review();
  await expect(dialog().getByText(/缩小可能永久截断数据/)).toBeVisible();
  await page.route("**/api/v1/disk-operations", async (route) => {
    await route.fetch();
    await route.abort();
  });
  await run();
  await expect(
    page
      .locator(".disk-status")
      .getByText("操作结果待核实，将继续查询。不会自动重发操作。", {
        exact: true,
      }),
  ).toBeVisible();
  await page.unroute("**/api/v1/disk-operations");
  await settle();
  assert.equal(posts.length, 2);
  assert.equal(posts[1].operation.size, "4294967296");

  await open("精确容量.qcow2", "转换格式");
  await dialog()
    .getByRole("combobox", { name: /^实际格式/ })
    .selectOption("raw");
  await dialog().getByLabel("同时更改文件位置", { exact: true }).check();
  await dialog()
    .getByLabel("宿主目录", { exact: true })
    .fill("/fixture/converted");
  await dialog().getByLabel("文件名", { exact: true }).fill("保留参数.raw");
  await review();
  await page.route("**/api/v1/disk-operations", (route) =>
    route.continue({
      postData: JSON.stringify({
        ...route.request().postDataJSON(),
        vmRevision: "stale",
      }),
    }),
  );
  await run();
  await settle();
  await page.unroute("**/api/v1/disk-operations");
  await button("调整后重试").click();
  await expect(dialog().getByLabel("文件名", { exact: true })).toHaveValue(
    "保留参数.raw",
  );
  await expect(
    dialog().getByRole("combobox", { name: /^实际格式/ }),
  ).toHaveValue("raw");
  await review();
  await run();
  await settle();

  // Register a synthetic two-level overlay chain through the real HTTP adapter.
  const overlay = async (parent, name) => {
    const requestId = name.replaceAll(".", "-");
    const task = await post(
      "disk-operations",
      {
        requestId,
        revision: "fixture-disk-revision",
        vmRevision: "fixture-reference-revision",
        operation: {
          action: "create",
          backingId: parent,
          folder: "/fixture",
          name,
          format: "qcow2",
        },
      },
      202,
    );
    let id = task.task.diskId;
    await expect(async () => {
      const receipt = await (
        await page.request.get(
          new URL(`/api/v1/disk-operations/${requestId}`, page.url()).href,
        )
      ).json();
      id = receipt.task.diskId;
      assert(id);
    }).toPass({ timeout: 5000 });
    return id;
  };
  const child = await overlay("fixture-disk", "child.qcow2");
  await overlay(child, "grandchild.qcow2");
  await button("刷新列表").click();
  await open("child.qcow2", "合并到父盘");
  await review();
  await expect(
    dialog().getByText("涉及磁盘（3）", { exact: true }),
  ).toBeVisible();
  await expect(dialog().getByText(/父盘内容会改变/)).toBeVisible();
  await dialog().getByRole("button", { name: "关闭", exact: true }).click();
  await open("grandchild.qcow2", "重置覆盖盘");
  await review();
  await expect(dialog().getByText(/丢弃覆盖盘中的全部更改/)).toBeVisible();
  await dialog().getByRole("button", { name: "关闭", exact: true }).click();
  await open("测试磁盘.qcow2", "删除磁盘");
  await review();
  await expect(
    dialog().getByText("涉及磁盘（3）", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog().getByText("只删除登记和挂载引用，保留镜像文件。", { exact: true }),
  ).toBeVisible();
  await expect(dialog().getByText(/原生 HTTP 虚拟机 · 磁盘槽 1/)).toBeVisible();
  await dialog().getByRole("button", { name: "返回修改", exact: true }).click();
  await dialog().getByLabel("同时删除镜像文件", { exact: true }).check();
  await review();
  await expect(dialog().getByText(/此操作不可撤销/)).toBeVisible();
  assert.equal(
    await dialog().evaluate((el) => el.scrollWidth <= el.clientWidth),
    true,
  );
  await page.screenshot({ path: join(output, "disk-delete-review-320.png") });
  await run();
  await settle();
  assert.equal(posts.at(-1).operation.deleteFiles, true);
  assert.equal(posts.length, 5);
  writeFileSync(
    join(output, "disk-actions.json"),
    JSON.stringify(
      {
        passed: true,
        browserPosts: posts.length,
        exactCapacity: true,
        receiptLossQueriesOnly: true,
        conflictRetainsParameters: true,
        resultId: result.task.diskId,
        completeSubtree: true,
        mobileWidth: 320,
        execution: "in-memory observation fixture; no image file writes",
      },
      null,
      2,
    ),
  );
}
