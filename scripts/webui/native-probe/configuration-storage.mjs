import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationStorage({ page, post, output, expect, require }) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (key) => page.locator(`[data-config-path="${key}"]`);
  const choose = (label) => page.getByRole("group", { name: "配置分类", exact: true })
    .getByRole("button", { name: label, exact: true }).click();
  const change = async (key, value) => {
    await field(key).fill(value);
    await field(key).blur();
  };
  const backend = async (value) => {
    await choose("基本");
    await field("backend").selectOption(value);
    await choose("存储");
  };
  await button("编辑 JSON").click();
  const raw = page.getByRole("textbox", { name: "高级配置（JSON）", exact: true });
  const row = parse(await raw.inputValue());
  Object.assign(row, {
    backend: "crosvm", hypervisor: "kvm", protected_vm: "protected_normal", screens: {},
    shared_dirs: [
      { path: "/fixture/share", tag: "shared", type: "P9", dax: true, cache: "AUTO",
        extension: parse("18446744073709551615") },
      { path: "/fixture/other", tag: "shared" },
    ],
  });
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await choose("存储");
  await expect(field("shared_dirs.0.cache")).toHaveValue("auto");
  await expect(field("shared_dirs.0.timeout")).toHaveValue("5");
  await expect(field("shared_dirs.0.posix_acl")).toBeChecked();
  await expect(field("shared_dirs.0.root_access")).not.toBeChecked();
  await expect(field("shared_dirs.0.readonly")).toBeHidden();
  await button("编辑 JSON").click();
  assert.equal(stringify(parse(await raw.inputValue()).shared_dirs), stringify(row.shared_dirs));
  await button("取消").click();
  await choose("基本");
  await button("保存配置").click();
  await expect(field("shared_dirs.1.tag")).toBeFocused();
  await expect(field("shared_dirs.1.tag")).toHaveJSProperty("validationMessage", "此挂载标签已被另一个共享目录使用。");
  await change("shared_dirs.1.tag", "other");
  await change("shared_dirs.0.path", "/fixture/../bad");
  await button("保存配置").click();
  await expect(field("shared_dirs.0.path")).toBeFocused();
  await change("shared_dirs.0.path", "  /fixture/share  ");
  await expect(field("shared_dirs.0.path")).toHaveValue("/fixture/share");
  await field("shared_dirs.0.cache").selectOption("never");
  await field("shared_dirs.0.cache").selectOption("always");
  await field("shared_dirs.0.writeback").check();
  await field("shared_dirs.0.posix_acl").uncheck();
  await field("shared_dirs.0.root_access").check();
  await change("shared_dirs.0.timeout", "-1");
  await backend("qemu");
  await expect(field("shared_dirs.0.timeout")).toBeHidden();
  await expect(field("shared_dirs.0.root_access")).toBeHidden();
  await field("shared_dirs.0.readonly").check();
  await backend("crosvm");
  await button("保存配置").click();
  await expect(field("shared_dirs.0.timeout")).toBeFocused();
  await change("shared_dirs.0.timeout", "12");
  await expect(field("shared_dirs.0.writeback")).toBeChecked();
  await expect(field("shared_dirs.0.posix_acl")).not.toBeChecked();
  await expect(field("shared_dirs.0.root_access")).toBeChecked();
  await field("shared_dirs.0.cache").scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: join(output, "storage-crosvm-320.png") });
  await backend("qemu");
  await expect(field("shared_dirs.0.readonly")).toBeChecked();
  await change("shared_dirs.0.path", "/fixture/a,b");
  await button("保存配置").click();
  await expect(field("shared_dirs.0.path")).toBeFocused();
  await expect(field("shared_dirs.0.path")).toHaveJSProperty("validationMessage", "QEMU 的共享目录路径和挂载标签不能包含逗号。");
  await change("shared_dirs.0.path", "/fixture/share");
  await field("shared_dirs.0.cache").scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: join(output, "storage-qemu-320.png") });
  await button("保存配置").click();
  await page.getByRole("dialog").getByRole("button", { name: "保存配置", exact: true }).click();
  await expect(page.getByText("配置已保存，可关闭或重新读取", { exact: true })).toBeVisible();
  const part = await post("vm-configuration/read", { snapshotId: null, offset: 0 });
  try {
    assert.equal(part.next, null);
    const saved = parse(Buffer.concat(part.items.filter((item) => item.type === "chunk")
      .map((item) => Buffer.from(item.data, "base64"))).toString()).vms[0];
    const directory = saved.shared_dirs[0];
    assert.equal(saved.backend, "qemu");
    assert.equal(directory.cache, "always");
    assert.equal(directory.timeout.toString(), "12");
    assert.equal(directory.readonly, true);
    assert.equal(directory.writeback, true);
    assert.equal(directory.posix_acl, false);
    assert.equal(directory.root_access, true);
    assert.equal(directory.type, "P9");
    assert.equal(directory.dax, true);
    assert.equal(directory.extension.toString(), "18446744073709551615");
    assert.deepEqual(saved.shared_dirs[1], { path: "/fixture/other", tag: "other" });
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(join(output, "storage.json"), JSON.stringify({ passed: true, checks: [
    "native-defaults-displayed-without-writing", "duplicate-tags-and-path-validation",
    "backend-options-and-invalid-input-preserved", "qemu-comma-argument-rejected",
    "cache-and-permission-options-http-roundtrip", "legacy-and-uint64-preserved",
    "mobile-320-no-overflow",
  ] }, null, 2));
  console.log(`Native configuration storage checks passed; ${output}`);
}
