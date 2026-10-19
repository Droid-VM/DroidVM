import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationBasic({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (key) => page.locator(`[data-config-path="${key}"]`);
  const advanced = (key) => page.locator(`[data-basic-path="${key}"]`);
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const choose = (label) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name: label, exact: true })
      .click();
  async function change(key, text) {
    await advanced(key).fill(text);
    await advanced(key).blur();
  }
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  Object.assign(row, {
    backend: "crosvm",
    hypervisor: "gunyah",
    protected_vm: "pseudo_unprotected",
    prepare_lend_mthp: true,
    swiotlb_mb: 256,
    cpu_topology_auto: true,
    cpu_affinity: "",
    cpu_capacity: "",
    cpu_clusters: "",
    extra_options: [],
  });
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await expect(field("prepare_lend_mthp")).toHaveValue("chunked");
  await expect(field("swiotlb_mb")).toBeDisabled();
  await field("backend").selectOption("qemu");
  await expect(field("protected_vm")).toHaveValue("protected_normal");
  await field("hypervisor").selectOption("soft");
  await field("backend").selectOption("crosvm");
  await choose("存储");
  await button("保存配置").click();
  await expect(field("hypervisor")).toBeFocused();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await field("hypervisor").selectOption("gunyah");
  await field("protected_vm").selectOption("pseudo_unprotected");
  await field("hypervisor").selectOption("kvm");
  await expect(field("protected_vm")).toHaveValue("protected_normal");
  await field("hypervisor").selectOption("gunyah");
  await field("protected_vm").selectOption("protected_without_firmware");
  await expect(field("swiotlb_mb")).toBeEnabled();
  await field("protected_vm").selectOption("pseudo_unprotected");
  await field("prepare_lend_mthp").selectOption("single");
  await expect(advanced("cpu_capacity")).toBeDisabled();
  await change("cpu_affinity", "0=0-1:1=2");
  await page
    .getByRole("checkbox", {
      name: "根据核心绑定自动推导 CPU 拓扑",
      exact: true,
    })
    .uncheck();
  await expect(advanced("cpu_capacity")).toBeEnabled();
  await change("cpu_capacity", "0=512,1=1024");
  await change("cpu_clusters", "0-2;2-3");
  await choose("启动");
  await button("保存配置").click();
  await expect(advanced("cpu_clusters")).toBeFocused();
  await change("cpu_clusters", "0-1;2-3");
  await field("cpu_count").fill("1");
  await field("cpu_count").blur();
  await button("保存配置").click();
  await expect(advanced("cpu_affinity")).toBeFocused();
  await field("cpu_count").fill("4");
  await field("cpu_count").blur();
  await change("environment_variables", "INVALID");
  await choose("网络");
  await button("保存配置").click();
  await expect(advanced("environment_variables")).toBeFocused();
  await change("environment_variables", " NAME=a=b\nNAME=\nWITH SPACE =value ");
  await change("extra_options", " --label\n two words \n\n--switch ");
  const notes =
    "# 测试备注\n<script>plain text</script>\n保留 **Markdown** 与换行";
  await change("notes", notes);
  await choose("图形");
  await choose("基本");
  await expect(advanced("notes")).toHaveValue(notes);
  // Unknown array entries remain read-only in this form and are preserved in JSON.
  await button("编辑 JSON").click();
  let draft = parse(await raw.inputValue());
  draft.extra_options = [{ future: parse("18446744073709551615") }];
  await raw.fill(stringify(draft));
  await button("应用到草稿").click();
  await expect(advanced("extra_options")).toHaveAttribute("readonly", "");
  await button("编辑 JSON").click();
  draft = parse(await raw.inputValue());
  assert.equal(
    draft.extra_options[0].future.toString(),
    "18446744073709551615",
  );
  draft.extra_options = ["--label", "two words", "--switch"];
  await raw.fill(stringify(draft));
  await button("应用到草稿").click();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await advanced("cpu_affinity").scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "basic-320.png") });
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
    assert.equal(vm.protected_vm, "pseudo_unprotected");
    assert.equal(vm.prepare_lend_mthp, "single");
    assert.equal(vm.swiotlb_mb.toString(), "256");
    assert.equal(vm.cpu_affinity, "0=0-1:1=2");
    assert.equal(vm.cpu_capacity, "0=512,1=1024");
    assert.equal(vm.cpu_clusters, "0-1;2-3");
    assert.equal(vm.cpu_topology_auto, false);
    assert.deepEqual(vm.extra_options, ["--label", "two words", "--switch"]);
    assert.deepEqual(vm.environment_variables, [
      "NAME=a=b",
      "NAME=",
      "WITH SPACE =value",
    ]);
    assert.equal(vm.notes, notes);
    assert.equal(vm.opaque.counter.toString(), "9007199254740993");
    assert.equal(saved.extension.counter.toString(), "18446744073709551615");
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(
    join(output, "basic.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "backend-hypervisor-and-protection-rules",
          "legacy-mthp-boolean-display",
          "inactive-swiotlb-value-preserved",
          "manual-topology-and-vcpu-count-validation",
          "hidden-invalid-textarea-focus",
          "line-arrays-and-notes-roundtrip",
          "unknown-array-format-readonly-and-preserved",
          "uint64-extensions-preserved",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native configuration basic checks passed; ${output}`);
}
