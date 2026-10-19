import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

// Invoked by verify-configuration --copy-only: share the authenticated native
// fixture and browser lifecycle without re-running the category/attachment suite.
export async function verifyConfigurationCopy({
  page,
  context,
  base,
  post,
  fixture,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const name = page.getByLabel("名称", { exact: true });
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const choose = (label) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name: label, exact: true })
      .click();
  async function rawDraft() {
    await button("编辑 JSON").click();
    const text = await raw.inputValue();
    await button("取消").click();
    return parse(text);
  }
  async function confirm(label) {
    await button(label).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: label, exact: true })
      .click();
  }
  async function snapshot() {
    let snapshotId;
    const chunks = [];
    try {
      let part = await post("vm-configuration/read", {
        snapshotId: null,
        offset: 0,
      });
      snapshotId = part.snapshotId;
      for (;;) {
        for (const item of part.items)
          if (item.type === "chunk")
            chunks.push(Buffer.from(item.data, "base64"));
        if (part.next === null) break;
        part = await post("vm-configuration/read", {
          snapshotId,
          offset: part.next,
        });
      }
    } finally {
      if (snapshotId) await post("vm-configuration/release", { snapshotId });
    }
    return parse(Buffer.concat(chunks).toString());
  }
  await button("编辑 JSON").click();
  const original = parse(await raw.inputValue());
  Object.assign(original, {
    auto_up: true,
    boot: {
      protocol: "linux",
      uefi: { vars: "/fixture/source-vars" },
      linux: {
        source: "image",
        image: { disk: 0, entry: "pinned-entry", wait: 2 },
      },
    },
    disks: [
      {
        path: "/fixture/registered.qcow2",
        bus: "virtio",
        readonly: false,
        extension: "disk-extension",
      },
    ],
    networks: [
      {
        network_id: "fixture-network",
        mac_address: "02:00:00:00:00:01",
        dhcp4_lease: {
          enabled: true,
          offset: 64,
          forwards: [{ proto: "tcp", host: "2222", guest: "22" }],
        },
      },
    ],
    screens: {
      simplefb: { enabled: true, exporter: "vnc", vnc: { port: 5909 } },
    },
  });
  await raw.fill(stringify(original));
  await button("应用到草稿").click();
  await confirm("保存配置");
  await expect(
    page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
  ).toBeVisible();
  const before = await snapshot();
  await button("关闭").click();
  await page.getByRole("link", { name: "复制配置", exact: true }).click();
  await expect(name).toHaveValue("原生 HTTP 虚拟机 副本");
  await expect(name).toBeEnabled();
  assert.equal(await button("删除虚拟机配置").count(), 0);
  let draft = await rawDraft();
  const initialId = draft.id;
  assert.match(
    initialId,
    /^[a-f\d]{8}-[a-f\d]{4}-4[a-f\d]{3}-[89ab][a-f\d]{3}-[a-f\d]{12}$/,
  );
  assert.equal(draft.disks[0].path, "");
  assert.equal(draft.disks[0].extension, "disk-extension");
  assert.equal(draft.auto_up, false);
  assert.equal(draft.boot.uefi.vars, "");
  assert.equal(draft.boot.linux.image.entry, undefined);
  assert.notEqual(
    draft.networks[0].mac_address,
    original.networks[0].mac_address,
  );
  assert.equal(draft.networks[0].dhcp4_lease.enabled, false);
  assert.equal(draft.networks[0].dhcp4_lease.forwards, undefined);
  assert.equal(draft.screens.simplefb.enabled, false);
  assert(stringify(draft).includes("9007199254740993"));
  await button("关闭").click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await confirm("重新读取");
  draft = await rawDraft();
  assert.notEqual(draft.id, initialId);
  const target = draft.id;
  await button("保存配置").click();
  await expect(page.getByRole("dialog")).toHaveCount(0); // Detached boot disk is required.
  await choose("存储");
  await button("更换资源").click();
  await page
    .locator(".resource-picker select")
    .selectOption("fixture-copy-disk");
  await page
    .locator(".resource-picker")
    .getByRole("button", { name: "使用此磁盘", exact: true })
    .click();
  await choose("基本");
  await name.fill("副本保存验证");
  await name.blur();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "copy-320.png"), fullPage: true });
  let commits = 0;
  await page.route("**/api/v1/vm-configuration/commit", async (route) => {
    commits++;
    await route.fetch();
    await route.abort("connectionfailed");
  });
  await confirm("保存配置");
  await expect(
    page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
  ).toBeVisible();
  assert.equal(commits, 1);
  await page.unroute("**/api/v1/vm-configuration/commit");
  const saved = await snapshot();
  assert.equal(saved.vms.length, 2);
  assert.equal(stringify(saved.vms[0]), stringify(before.vms[0]));
  assert.equal(saved.extension.counter.toString(), "18446744073709551615");
  const copy = saved.vms.find((vm) => vm.id === target);
  assert.equal(copy.name, "副本保存验证");
  assert.equal(copy.disks[0].path, "/fixture/copy.qcow2");
  assert.equal(copy.opaque.counter.toString(), "9007199254740993");
  await expect(page).toHaveURL(`${base}/#/vms/${target}/edit`);
  await confirm("重新读取");
  await expect(name).toBeEnabled();
  assert.equal((await rawDraft()).id, target); // Saved copy reloads as an existing VM.
  await button("关闭").click();
  await expect(page).toHaveURL(`${base}/#/vms/${target}`);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole("link", { name: "复制配置", exact: true }).click();
  await expect(name).toHaveValue("副本保存验证 副本");
  await fixture.command("config-touch");
  await name.fill("保留副本冲突草稿");
  await name.blur();
  await confirm("保存配置");
  await expect(
    page.getByText("请先核实配置再继续编辑", { exact: true }),
  ).toBeVisible();
  await expect(name).toHaveValue("保留副本冲突草稿");
  assert.equal((await snapshot()).vms.length, 2);
  const inventory = await (
    await context.request.get(`${base}/api/v1/vms`)
  ).json();
  assert.equal(inventory.items.find((vm) => vm.id === target).state, "STOPPED");
  writeFileSync(
    join(output, "copy.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "copy-entry-mobile-and-desktop",
          "fresh-id-and-mac-exclusive-settings-reset",
          "writable-boot-disk-requires-replacement",
          "copy-reload-and-leave-guard",
          "lost-copy-commit-reconciled-once",
          "saved-copy-readback-original-unchanged",
          "uint64-and-extensions-preserved",
          "saved-copy-reloads-same-target",
          "copy-conflict-keeps-draft",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native configuration copy checks passed; ${output}`);
}
