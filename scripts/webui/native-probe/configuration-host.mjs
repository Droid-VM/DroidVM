import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyHostCapabilities({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (name) => page.locator(`[data-config-path="${name}"]`);
  const choose = (name) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name, exact: true })
      .click();
  const panel = () =>
    page
      .locator(`fieldset[data-category="${category}"]`)
      .getByRole("region", { name: "宿主支持信息", exact: true });
  let category = "configBasic";
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  assert.equal(
    await page.evaluate(
      async () =>
        (await fetch("/api/v1/host/devices", { credentials: "omit" })).status,
    ),
    401,
  );
  const observation = await page.evaluate(async () =>
    (await fetch("/api/v1/host/devices")).json(),
  );
  assert.deepEqual(Object.keys(observation).sort(), [
    "context",
    "devices",
    "systemBlit",
  ]);
  assert.equal(observation.devices.kvm, true);
  assert.equal(observation.devices.gunyah, false);
  assert.equal(observation.devices.geniezone, null);
  assert.equal(JSON.stringify(observation).includes("/private/"), false);
  assert.equal(observation.systemBlit.private, undefined);
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  Object.assign(row, {
    backend: "crosvm",
    hypervisor: "gunyah",
    protected_vm: "protected_normal",
    gpu_backend: "gpu_virglrenderer",
    gpu_api: "drm2kgsl",
    display_blit_provider: "system",
    screens: {
      "gpu-0": {
        enabled: true,
        exporter: "native",
        transport_cap: "gpu",
        width: 1280,
        height: 720,
      },
    },
    host_extension: { counter: parse("18446744073709551615") },
  });
  delete row.gpu_mode;
  delete row.gpu_provider;
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await expect(
    panel().getByText(
      "未检测到所选虚拟化方式的设备节点；已有配置保留，请确认宿主是否支持。",
      { exact: true },
    ),
  ).toBeVisible();
  await panel().locator("summary").click();
  await expect(panel().getByText("已检测到", { exact: true })).toBeVisible();
  await expect(panel().getByText("未检测到", { exact: true })).toBeVisible();
  await expect(panel().getByText("未知", { exact: true })).toBeVisible();
  await choose("图形");
  category = "configGraphics";
  await panel().locator("summary").click();
  await expect(
    panel().getByText("未检测到 KGSL 设备，无法确认 DRM2KGSL 渲染可用。", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    panel().getByText(
      "系统 Vulkan 缺少画面拷贝所需扩展，将回退到 CPU 拷贝。所选设置保留。",
      { exact: true },
    ),
  ).toBeVisible();
  await expect(
    panel().getByText("VK_EXT_image_drm_format_modifier", { exact: true }),
  ).toBeVisible();
  await expect(field("display_blit_provider")).toHaveValue("system");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await panel().scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "host-capabilities-320.png") });
  const pattern = "**/api/v1/host/devices";
  let mode = "pending";
  await page.route(pattern, async (route) => {
    if (mode === "error")
      return route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ error: { code: "unavailable" } }),
      });
    const body = {
      ...observation,
      devices: {
        kvm: null,
        gunyah: null,
        geniezone: null,
        kgsl: null,
        turnip: null,
        systemVulkan: null,
      },
      systemBlit: {
        state: mode,
        missingExtensions: null,
        checkedAtMs: mode === "pending" ? null : 1792339200000,
      },
    };
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await panel().getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(
    panel().getByText("正在检测系统驱动扩展，可继续编辑。", { exact: true }),
  ).toBeVisible();
  await expect(
    panel().getByText("未检测到 KGSL 设备，无法确认 DRM2KGSL 渲染可用。", {
      exact: true,
    }),
  ).toHaveCount(0);
  mode = "unknown";
  await panel().getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(
    panel().getByText("暂时无法确认扩展支持，不将未知结果当作不支持。", {
      exact: true,
    }),
  ).toBeVisible();
  mode = "error";
  await panel().getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(
    panel().getByText("宿主观察暂不可用，请稍后刷新。", { exact: true }),
  ).toBeVisible();
  await expect(field("display_blit_provider")).toHaveValue("system");
  await page.unroute(pattern);
  await panel().getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(
    panel().getByText("VK_EXT_image_drm_format_modifier", { exact: true }),
  ).toBeVisible();
  await button("编辑 JSON").click();
  const preserved = parse(await raw.inputValue());
  assert.equal(preserved.gpu_api, "drm2kgsl");
  assert.equal(preserved.gpu_mode, undefined);
  assert.equal(preserved.gpu_provider, undefined);
  assert.equal(preserved.hypervisor, "gunyah");
  assert.equal(preserved.display_blit_provider, "system");
  await button("取消").click();
  await field("gpu_mode").selectOption("vulkan");
  await expect(
    panel().getByText("未检测到 Turnip 驱动文件，无法确认所选驱动可用。", {
      exact: true,
    }),
  ).toBeVisible();
  await field("gpu_provider").selectOption("vk_system");
  await expect(
    panel().getByText("未检测到 Turnip 驱动文件，无法确认所选驱动可用。", {
      exact: true,
    }),
  ).toHaveCount(0);
  await field("display_blit_provider").selectOption("off");
  await expect(
    panel().getByText(
      "系统 Vulkan 缺少画面拷贝所需扩展，将回退到 CPU 拷贝。所选设置保留。",
      { exact: true },
    ),
  ).toHaveCount(0);
  await button("保存配置").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存配置", exact: true })
    .click();
  await expect(
    page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
  ).toBeVisible({ timeout: 20000 });
  const part = await post("vm-configuration/read", {
    snapshotId: null,
    offset: 0,
  });
  try {
    assert.equal(part.next, null);
    const saved = parse(
      Buffer.concat(
        part.items
          .filter((item) => item.type === "chunk")
          .map((item) => Buffer.from(item.data, "base64")),
      ).toString(),
    ).vms[0];
    assert.equal(saved.hypervisor, "gunyah");
    assert.equal(saved.gpu_provider, "vk_system");
    assert.equal(saved.display_blit_provider, "off");
    assert.equal(
      saved.host_extension.counter.toString(),
      "18446744073709551615",
    );
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(
    join(output, "host-capabilities.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "authenticated-host-observation-and-whitelist",
          "presence-absence-unknown-distinct",
          "selected-device-and-driver-hints",
          "display-copy-extensions-not-renderer-requirements",
          "pending-unknown-error-recovery",
          "refresh-preserves-legacy-configuration",
          "configuration-http-save-and-extension-preservation",
          "mobile-320",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native host capability checks passed; ${output}`);
}
