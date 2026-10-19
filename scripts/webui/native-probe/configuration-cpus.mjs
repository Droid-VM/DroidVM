import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationCpus({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (name) => page.locator(`[data-config-path="${name}"]`);
  const affinity = page.locator('[data-basic-path="cpu_affinity"]');
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const choose = (name) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name, exact: true })
      .click();
  const dialog = page.getByRole("dialog");
  const core = (id) =>
    dialog.getByRole("checkbox", { name: `CPU${id}`, exact: true });
  const confirm = () =>
    dialog.getByRole("button", { name: "确认", exact: true }).click();
  const preview = page.getByRole("region", {
    name: "按绑定推导的拓扑参考",
    exact: true,
  });
  const advanced = (key) => page.locator(`[data-basic-path="${key}"]`);
  const auto = page.getByRole("checkbox", {
    name: "根据核心绑定自动推导 CPU 拓扑",
    exact: true,
  });
  async function saveAndRead() {
    await button("保存配置").click();
    await dialog.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(
      page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
    ).toBeVisible({ timeout: 20000 });
    const part = await post("vm-configuration/read", {
      snapshotId: null,
      offset: 0,
    });
    try {
      assert.equal(part.next, null);
      return parse(
        Buffer.concat(
          part.items
            .filter((item) => item.type === "chunk")
            .map((item) => Buffer.from(item.data, "base64")),
        ).toString(),
      ).vms[0];
    } finally {
      await post("vm-configuration/release", { snapshotId: part.snapshotId });
    }
  }
  assert.equal(
    await page.evaluate(
      async () =>
        (await fetch("/api/v1/host/cpus", { credentials: "omit" })).status,
    ),
    401,
  );
  const catalogue = await page.evaluate(async () =>
    (await fetch("/api/v1/host/cpus")).json(),
  );
  assert.deepEqual(
    catalogue.items.map((item) => item.id),
    [0, 1, 4, 7, 9],
  );
  assert.equal(catalogue.items[3].online, false);
  assert.equal(catalogue.items[4].maxFrequencyKHz, null);
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  Object.assign(row, {
    cpu_count: 4,
    cpu_affinity: "0=0,99:1=4",
    cpu_topology_auto: true,
    cpu_capacity: "1=700",
    cpu_clusters: "",
    backend: "crosvm",
    hypervisor: "kvm",
    protected_vm: "protected_normal",
    screens: {
      "gpu-0": {
        enabled: true,
        exporter: "native",
        transport_cap: "gpu",
        width: 1280,
        height: 720,
      },
    },
    gpu_cgroup_enabled: true,
    gpu_cgroup_path: "/dev/cpuset/gpuworker",
    gpu_cgroup_cpus: "99",
    gpu_rt_prio: "97",
    cpu_extension: { counter: parse("18446744073709551615") },
  });
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await expect(
    preview.getByRole("row", { name: "0 512", exact: true }),
  ).toBeVisible();
  await expect(
    preview.getByRole("row", { name: "1 896", exact: true }),
  ).toBeVisible();
  await expect(
    preview.getByText("集群 1：vCPU 0,2-3", { exact: true }),
  ).toBeVisible();
  await expect(
    preview.getByText("集群 2：vCPU 1", { exact: true }),
  ).toBeVisible();
  await button("刷新拓扑参考").click();
  await expect(affinity).toHaveValue("0=0,99:1=4");
  await expect(advanced("cpu_capacity")).toHaveValue("1=700");
  await expect(advanced("cpu_clusters")).toHaveValue("");
  await auto.uncheck();
  await expect(advanced("cpu_capacity")).toHaveValue("1=700");
  await expect(advanced("cpu_clusters")).toHaveValue("0,2-3;1");
  await advanced("cpu_clusters").fill("");
  await advanced("cpu_clusters").blur();
  await button("填入空白手动拓扑字段").click();
  await expect(advanced("cpu_clusters")).toHaveValue("0,2-3;1");
  await expect(advanced("cpu_capacity")).toHaveValue("1=700");
  await auto.check();
  await field("backend").selectOption("qemu");
  await expect(
    preview.getByText(
      "当前拓扑设置仅由 crosvm 使用；其他后端保留配置，暂不展示生效预览。",
      { exact: true },
    ),
  ).toBeVisible();
  await expect(preview.getByRole("table")).toHaveCount(0);
  await field("backend").selectOption("crosvm");
  await expect(preview.getByRole("table")).toBeVisible();
  await preview.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "cpu-topology-320.png") });
  await page
    .locator("summary")
    .filter({ hasText: "逐个选择 vCPU 绑定核心" })
    .click();
  await button("选择 vCPU0 核心").click();
  await expect(core(0)).toBeChecked();
  await expect(
    dialog.getByText("目录中未找到的核心（保留）：99", { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText("离线 · 容量 1024", { exact: true }),
  ).toBeVisible();
  await core(4).check();
  await dialog.getByRole("button", { name: "刷新列表", exact: true }).click();
  await expect(core(4)).toBeChecked();
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await expect(affinity).toHaveValue("0=0,99:1=4");
  await button("选择 vCPU0 核心").click();
  await expect(core(0)).toBeChecked();
  await core(4).check();
  await confirm();
  await expect(affinity).toHaveValue("1=4:0=0,4,99");
  await button("选择 vCPU1 核心").click();
  await dialog.getByRole("button", { name: "仅大核", exact: true }).click();
  await expect(core(4)).toBeChecked();
  await expect(core(7)).toBeChecked();
  await expect(core(9)).not.toBeChecked();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "cpu-picker-320.png") });
  await confirm();
  await choose("图形");
  await button("选择 GPU 工作线程核心").click();
  await expect(core(0)).not.toBeChecked();
  await core(1).check();
  await confirm();
  await expect(field("gpu_cgroup_cpus")).toHaveValue("1,99");
  await button("选择 GPU 工作线程核心").click();
  await dialog.getByRole("button", { name: "清空绑定", exact: true }).click();
  await confirm();
  await expect(field("gpu_cgroup_cpus")).toHaveValue("");
  await expect(field("gpu_rt_prio")).toHaveValue("");
  await button("选择 GPU 工作线程核心").click();
  await expect(core(1)).toBeVisible();
  await core(1).check();
  await confirm();
  const vm = await saveAndRead();
  assert.equal(vm.cpu_affinity, "0=0,4,99:1=4,7");
  assert.equal(vm.gpu_cgroup_cpus, "1");
  assert.equal(vm.gpu_rt_prio, "");
  assert.equal(vm.cpu_capacity, "1=700");
  assert.equal(vm.cpu_clusters, "0,2-3;1");
  assert.equal(vm.cpu_extension.counter.toString(), "18446744073709551615");
  await page.reload();
  await expect(page.getByLabel("名称", { exact: true })).toBeEnabled();
  await expect(affinity).toHaveValue("0=0,4,99:1=4,7");
  await page
    .locator("summary")
    .filter({ hasText: "逐个选择 vCPU 绑定核心" })
    .click();
  await button("一核一个 vCPU").click();
  await expect(core(0)).toBeChecked();
  await expect(core(4)).toBeChecked();
  await dialog.getByRole("button", { name: "清空绑定", exact: true }).click();
  await expect(
    dialog.getByRole("button", { name: "确认", exact: true }),
  ).toBeDisabled();
  await dialog.getByRole("button", { name: "仅大核", exact: true }).click();
  await confirm();
  await expect(dialog).toHaveAccessibleName("应用一对一核心映射");
  await expect(dialog.getByText(/vCPU 数量将从 4 改为 2/)).toBeVisible();
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await expect(field("cpu_count")).toHaveValue("4");
  await expect(affinity).toHaveValue("0=0,4,99:1=4,7");
  await expect(advanced("cpu_capacity")).toHaveValue("1=700");
  await button("一核一个 vCPU").click();
  // Confirming an unchanged selection still explicitly converts the mapping.
  await confirm();
  await expect(dialog).toHaveAccessibleName("应用一对一核心映射");
  await page.screenshot({ path: join(output, "cpu-one-to-one-320.png") });
  await confirm();
  await expect(field("cpu_count")).toHaveValue("2");
  await expect(affinity).toHaveValue("0=0:1=4");
  await expect(auto).toBeChecked();
  await expect(advanced("cpu_capacity")).toHaveValue("");
  await expect(advanced("cpu_clusters")).toHaveValue("");
  const simple = await saveAndRead();
  assert.equal(simple.cpu_count.toString(), "2");
  assert.equal(simple.cpu_affinity, "0=0:1=4");
  assert.equal(simple.cpu_topology_auto, true);
  assert.equal(simple.cpu_capacity, "");
  assert.equal(simple.cpu_clusters, "");
  assert.equal(simple.cpu_extension.counter.toString(), "18446744073709551615");
  writeFileSync(
    join(output, "cpus.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "authenticated-cpu-catalogue",
          "offline-and-unknown-fields",
          "cancel-refresh-preserve-selection",
          "missing-core-preserved",
          "app-big-core-grouping",
          "shared-gpu-picker-and-rt-normalization",
          "http-save-reload-and-uint64-preservation",
          "minimum-capacity-and-unbound-cluster-reference",
          "refresh-and-auto-mode-do-not-persist-derived-values",
          "manual-seed-preserves-existing-fields",
          "qemu-preview-does-not-claim-topology-support",
          "one-to-one-count-and-topology-confirmation-cancel",
          "one-to-one-http-roundtrip",
          "mobile-320",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native CPU configuration checks passed; ${output}`);
}
