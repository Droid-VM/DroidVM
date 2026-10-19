import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationCreate({
  page,
  context,
  browser,
  base,
  post,
  fixture,
  output,
  expect,
  require,
  repo,
}) {
  const { parse, stringify } = require("lossless-json");
  const template = parse(
    readFileSync(
      join(repo, "app/src/main/assets/data/vm-defaults.json"),
      "utf8",
    ),
  );
  const session = await (
    await context.request.get(`${base}/api/v1/session`)
  ).json();
  assert(session.server.capabilities.includes("vm.defaults.v1"));
  const anonymous = await browser.newContext();
  assert.equal(
    (
      await anonymous.request.post(`${base}/api/v1/vm-configuration/defaults`, {
        headers: { Origin: base },
        data: {
          context: {
            daemonEpoch: session.server.daemonEpoch,
            sessionId: session.id,
          },
        },
      })
    ).status(),
    401,
  );
  await anonymous.close();
  assert.equal(
    (
      await context.request.post(`${base}/api/v1/vm-configuration/defaults`, {
        headers: { Origin: base },
        data: {
          context: {
            daemonEpoch: session.server.daemonEpoch,
            sessionId: session.id,
          },
        },
      })
    ).status(),
    403,
  );
  const one = parse((await post("vm-configuration/defaults", {})).json);
  const two = parse((await post("vm-configuration/defaults", {})).json);
  assert.notEqual(one.id, two.id);
  assert.equal(one.hypervisor, "gunyah");
  assert.equal(one.protected_vm, "pseudo_unprotected");
  assert.equal(one.prepare_lend_mthp, "single");
  assert.equal(
    one.boot.linux.kernel,
    "/fixture/data/usr/share/droidvm/vmlinuz",
  );
  assert.equal(
    one.boot.linux.initrd,
    "/fixture/data/usr/share/droidvm/initramfs.img",
  );
  for (const key of [
    "id",
    "name",
    "created_at",
    "hypervisor",
    "protected_vm",
    "prepare_lend_mthp",
  ])
    delete one[key];
  delete one.boot.linux.kernel;
  delete one.boot.linux.initrd;
  assert.deepEqual(one, template);
  async function snapshot() {
    const part = await post("vm-configuration/read", {
      snapshotId: null,
      offset: 0,
    });
    try {
      assert.equal(part.next, null); // This small fixture fits in one bounded page.
      return parse(
        Buffer.concat(
          part.items
            .filter((item) => item.type === "chunk")
            .map((item) => Buffer.from(item.data, "base64")),
        ).toString(),
      );
    } finally {
      await post("vm-configuration/release", { snapshotId: part.snapshotId });
    }
  }
  const before = await snapshot();
  assert.equal(before.vms.length, 1); // Reading defaults never persists a VM.
  const button = (name) => page.getByRole("button", { name, exact: true });
  async function confirm(label) {
    await button(label).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: label, exact: true })
      .click();
  }
  await page.goto(`${base}/#/vms`);
  await page
    .getByRole("button", { name: "新建虚拟机", exact: true })
    .filter({ visible: true })
    .click();
  const name = page.getByLabel("名称", { exact: true });
  await expect(name).toHaveValue("新虚拟机");
  await expect(name).toBeEnabled();
  await expect(button("删除虚拟机配置")).toHaveCount(0);
  await button("关闭").click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await button("编辑 JSON").click();
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const draft = parse(await raw.inputValue());
  const id = draft.id;
  assert.equal(draft.memory_mb.toString(), "512");
  assert.equal(draft.screens.simplefb.poll_hz.toString(), "60");
  assert.equal(draft.peripherals.length, 2);
  draft.extension = parse('{"counter":18446744073709551615}');
  await raw.fill(stringify(draft));
  await button("应用到草稿").click();
  await name.fill("网页新建验证");
  await name.blur();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({
    path: join(output, "create-320.png"),
    fullPage: true,
  });
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
  await expect(page).toHaveURL(`${base}/#/vms/${id}/edit`);
  const saved = await snapshot();
  assert.equal(saved.vms.length, 2);
  assert.equal(stringify(saved.vms[0]), stringify(before.vms[0]));
  const created = saved.vms.find((vm) => vm.id === id);
  assert.equal(created.name, "网页新建验证");
  assert.equal(created.extension.counter.toString(), "18446744073709551615");
  assert.equal(
    created.boot.linux.kernel,
    "/fixture/data/usr/share/droidvm/vmlinuz",
  );
  await page.reload();
  await expect(name).toHaveValue("网页新建验证");
  await expect(name).toBeEnabled();
  await button("关闭").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .click();
  await page.setViewportSize({ width: 1280, height: 900 });
  await page
    .getByRole("button", { name: "新建虚拟机", exact: true })
    .filter({ visible: true })
    .click();
  await expect(name).toHaveValue("新虚拟机");
  await fixture.command("config-touch");
  await name.fill("保留新建冲突草稿");
  await name.blur();
  await confirm("保存配置");
  await expect(
    page.getByText("请先核实配置再继续编辑", { exact: true }),
  ).toBeVisible();
  await expect(name).toHaveValue("保留新建冲突草稿");
  assert.equal((await snapshot()).vms.length, 2);
  for (const [locale, label, defaultName] of [
    ["en-US", "Name", "New virtual machine"],
    ["zh-TW", "名稱", "新虛擬機"],
  ]) {
    const localized = await browser.newContext({
      locale,
      viewport: { width: 320, height: 740 },
      storageState: await context.storageState(),
    });
    try {
      const tab = await localized.newPage();
      await tab.goto(`${base}/#/vms/new`);
      await expect(tab.getByLabel(label, { exact: true })).toHaveValue(
        defaultName,
      );
    } finally {
      await localized.close();
    }
  }
  writeFileSync(
    join(output, "create.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "defaults-auth-csrf-capability",
          "native-defaults-match-shared-app-template",
          "device-defaults-and-fresh-uuid-no-persistence",
          "mobile-and-desktop-create-entry",
          "new-draft-leave-guard",
          "lost-create-reply-no-replay",
          "create-readback-retains-original-and-uint64",
          "saved-create-route-refresh-same-vm",
          "create-conflict-keeps-draft",
          "browser-language-default-name",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native configuration creation checks passed; ${output}`);
}
