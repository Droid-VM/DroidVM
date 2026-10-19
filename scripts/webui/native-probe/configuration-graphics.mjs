import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationGraphics({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (key) => page.locator(`[data-config-path="${key}"]`);
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const choose = (label) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name: label, exact: true })
      .click();
  const change = async (key, text) => {
    await field(key).fill(text);
    await field(key).blur();
  };
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  Object.assign(row, {
    backend: "crosvm",
    hypervisor: "gunyah",
    protected_vm: "protected_without_firmware",
    screens: {
      "gpu-0": {
        enabled: true,
        exporter: "native",
        transport_cap: "gpu",
        width: 1280,
        height: 720,
      },
    },
    cpu_count: 2,
    cpu_affinity: "0=1",
    gpu_backend: "GPU_VIRGLRENDERER",
    gpu_api: "DRM2KGSL",
    gpu_udmabuf: true,
    gpu_dynamic_vram: true,
    gpu_guest_pool_mb: 1024,
    gpu_guest_prealloc_mb: 256,
    gpu_guest_step_mb: 64,
    gpu_guest_max_grants: 8,
    gpu_host_pool_mb: 64,
    gpu_drm2kgsl_pool_mb: 64,
    gpu_venus_pool_mb: 256,
    gpu_vram_quota_mb: 2048,
    gpu_vram_folio_threshold_kb: 1024,
    gpu_pool_blob_max_kb: 4096,
    gpu_cgroup_enabled: false,
    gpu_cgroup_path: "/dev/cpuset/gpuworker",
    gpu_cgroup_cpus: "",
    gpu_rt_prio: "",
    vpu_enabled: true,
    vpu_host_pool_mb: 256,
    vpu_guest_pool_mb: 128,
    future_gpu: { counter: parse("18446744073709551615") },
  });
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await choose("图形");
  await expect(field("gpu_mode")).toHaveValue("native");
  await expect(field("gpu_provider")).toHaveValue("drm2kgsl");
  await button("编辑 JSON").click();
  const unchanged = parse(await raw.inputValue());
  assert.equal(unchanged.gpu_mode, undefined);
  assert.equal(unchanged.gpu_api, "DRM2KGSL");
  await button("取消").click();
  await field("gpu_backend").selectOption("gpu_gfxstream");
  await expect(field("gpu_mode")).toHaveValue("vulkan");
  await field("gpu_provider").selectOption("vk_system");
  await field("gpu_udmabuf").uncheck();
  await expect(field("gpu_guest_pool_mb")).toBeHidden();
  await expect(field("gpu_vram_quota_mb")).toBeVisible();
  await change("gpu_vram_quota_mb", "3072");
  await field("gpu_udmabuf").check();
  await change("gpu_guest_prealloc_mb", "2048");
  await choose("基本");
  await button("保存配置").click();
  await expect(field("gpu_guest_prealloc_mb")).toBeFocused();
  await change("gpu_guest_prealloc_mb", "256");
  await change("gpu_guest_step_mb", "3");
  await button("保存配置").click();
  await expect(field("gpu_guest_step_mb")).toBeFocused();
  await change("gpu_guest_step_mb", "-1");
  await field("gpu_dynamic_vram").uncheck();
  await expect(field("gpu_guest_step_mb")).toBeHidden();
  await field("gpu_dynamic_vram").check();
  await expect(field("gpu_guest_step_mb")).toHaveValue("0");
  await expect(field("gpu_guest_prealloc_mb")).toHaveValue("1024");
  await change("gpu_guest_prealloc_mb", "256");
  await change("gpu_guest_step_mb", "64");
  await change("gpu_guest_max_grants", "8");
  await choose("基本");
  await field("protected_vm").selectOption("protected_normal");
  await choose("图形");
  await expect(field("gpu_guest_pool_mb")).toBeHidden();
  await expect(field("vpu_guest_pool_mb")).toBeHidden();
  await choose("基本");
  await field("protected_vm").selectOption("protected_without_firmware");
  await choose("图形");
  await expect(field("gpu_guest_prealloc_mb")).toHaveValue("256");
  await field("gpu_backend").selectOption("gpu_2d");
  await expect(field("gpu_mode")).toBeHidden();
  await field("gpu_backend").selectOption("gpu_virglrenderer");
  await expect(field("gpu_mode")).toHaveValue("native");
  await expect(
    field("gpu_mode").locator('option[value="opengl"]'),
  ).toHaveJSProperty("disabled", true);
  await field("gpu_mode").selectOption("vulkan");
  await expect(field("gpu_venus_pool_mb")).toBeVisible();
  await field("gpu_provider").selectOption("vk_system");
  await field("gpu_rt_prio").selectOption("97");
  await button("保存配置").click();
  await expect(field("gpu_rt_prio")).toBeFocused();
  await field("gpu_cgroup_enabled").check();
  await change("gpu_cgroup_path", "relative");
  await change("gpu_cgroup_cpus", "3-1");
  await choose("启动");
  await button("保存配置").click();
  await expect(field("gpu_cgroup_path")).toBeFocused();
  await change("gpu_cgroup_path", " /dev/cpuset/gpuworker ");
  await button("保存配置").click();
  await expect(field("gpu_cgroup_cpus")).toBeFocused();
  await change("gpu_cgroup_cpus", "1-2");
  await expect(
    page.getByText(
      "GPU 与 vCPU 使用了相同宿主核心；可以保存，但可能互相争用。",
      { exact: true },
    ),
  ).toBeVisible();
  await field("gpu_cgroup_enabled").uncheck();
  await expect(field("gpu_rt_prio")).toHaveValue("");
  await field("gpu_cgroup_enabled").check();
  await expect(field("gpu_cgroup_cpus")).toHaveValue("1-2");
  await field("gpu_rt_prio").selectOption("97");
  await field("display_blit_provider").selectOption("system");
  await field("vpu_enabled").uncheck();
  await expect(field("vpu_host_pool_mb")).toBeHidden();
  await field("vpu_enabled").check();
  await expect(field("vpu_guest_pool_mb")).toHaveValue("128");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page
    .getByRole("heading", { name: "GPU 渲染器与显存", exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "graphics-advanced-320.png") });
  await button("保存配置").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存配置", exact: true })
    .click();
  await expect(
    page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
  ).toBeVisible();
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
    );
    const vm = saved.vms[0];
    assert.equal(vm.gpu_backend, "gpu_virglrenderer");
    assert.equal(vm.gpu_mode, "vulkan");
    assert.equal(vm.gpu_provider, "vk_system");
    assert.equal(vm.gpu_api, "vulkan_system");
    assert.equal(vm.gpu_guest_prealloc_mb.toString(), "256");
    assert.equal(vm.gpu_guest_step_mb.toString(), "64");
    assert.equal(vm.gpu_guest_max_grants.toString(), "8");
    assert.equal(vm.gpu_vram_quota_mb.toString(), "3072");
    assert.equal(vm.gpu_cgroup_path, "/dev/cpuset/gpuworker");
    assert.equal(vm.gpu_cgroup_cpus, "1-2");
    assert.equal(vm.gpu_rt_prio, "97");
    assert.equal(vm.display_blit_provider, "system");
    assert.equal(vm.vpu_guest_pool_mb.toString(), "128");
    assert.equal(vm.future_gpu.counter.toString(), "18446744073709551615");
    assert.equal(saved.extension.counter.toString(), "18446744073709551615");
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(
    join(output, "graphics-advanced.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "legacy-read-without-migration",
          "renderer-mode-provider-and-native-api-sync",
          "allocation-and-protection-visibility",
          "growth-validation-and-off-normalization",
          "stale-invalid-input-cleared",
          "rt-cpuset-validation-and-unbind",
          "core-overlap-warning",
          "inactive-values-and-uint64-preserved",
          "http-save-readback",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(
    `Native configuration advanced graphics checks passed; ${output}`,
  );
}
