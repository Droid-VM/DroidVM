// Focused configuration flow against the real HTTP/journal/task adapters.
// Native persistence is an explicit in-memory fixture, never a device store.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { startFixture } from "./server-fixture.mjs";
import { verifyConfigurationCopy } from "./configuration-copy.mjs";
import { verifyConfigurationCreate } from "./configuration-create.mjs";
import { verifyConfigurationBasic } from "./configuration-basic.mjs";
import { verifyConfigurationCpus } from "./configuration-cpus.mjs";
import { verifyHostCapabilities } from "./configuration-host.mjs";
import { verifyDiskDetails } from "./resource-disks.mjs";
import { verifyDiskActions } from "./disk-actions.mjs";
import { verifyDiskRecovery } from "./disk-recovery.mjs";
import { verifyConfigurationScreens } from "./configuration-screens.mjs";
import { verifyConfigurationGraphics } from "./configuration-graphics.mjs";
import { verifyConfigurationNetworks } from "./configuration-networks.mjs";
import { verifyConfigurationPeripherals } from "./configuration-peripherals.mjs";
import { verifyConfigurationStorage } from "./configuration-storage.mjs";
import { verifyUsbResources } from "./resource-usb.mjs";
import { verifyUsbRules } from "./usb-rules.mjs";
import { verifyNetworkEditor } from "./network-editor.mjs";
import { verifyNetworkRuntime } from "./network-runtime.mjs";
import { verifyNetworkDeletion } from "./network-deletion.mjs";
import { verifyTerminal } from "./terminal.mjs";
const repo = fileURLToPath(new URL("../../../", import.meta.url));
const require = createRequire(join(repo, "webui/package.json"));
const { chromium, expect } = require("@playwright/test");
const { values } = parseArgs({
  options: {
    exe: { type: "string" },
    "categories-only": { type: "boolean", default: false },
    "copy-only": { type: "boolean", default: false },
    "create-only": { type: "boolean", default: false },
    "basic-only": { type: "boolean", default: false },
    "cpu-only": { type: "boolean", default: false },
    "host-only": { type: "boolean", default: false },
    "disks-only": { type: "boolean", default: false },
    "disk-actions-only": { type: "boolean", default: false },
    "disk-recovery-only": { type: "boolean", default: false },
    "screens-only": { type: "boolean", default: false },
    "graphics-only": { type: "boolean", default: false },
    "networks-only": { type: "boolean", default: false },
    "peripherals-only": { type: "boolean", default: false },
    "storage-only": { type: "boolean", default: false },
    "usb-only": { type: "boolean", default: false },
    "usb-rules-only": { type: "boolean", default: false },
    "network-editor-only": { type: "boolean", default: false },
    "network-runtime-only": { type: "boolean", default: false },
    "network-deletion-only": { type: "boolean", default: false },
    "terminal-only": { type: "boolean", default: false },
    output: { type: "string", default: join(repo, "build/webui-native") },
  },
});
assert(values.exe, "--exe is required");
mkdirSync(values.output, { recursive: true });
const output = mkdtempSync(
  join(resolve(values.output), "native-configuration-"),
);
const fixture = await startFixture(values.exe);
process.env.DROIDVM_DEV_API_TARGET = fixture.origin;
let vite, browser;
try {
  const { createServer } = await import(
    pathToFileURL(join(repo, "webui/node_modules/vite/dist/node/index.js"))
  );
  vite = await createServer({
    root: join(repo, "webui"),
    configFile: join(repo, "webui/vite.config.ts"),
    server: { host: "127.0.0.1", port: 5178, strictPort: true },
  });
  await vite.listen();
  browser = await chromium.launch({ channel: "msedge", headless: true });
  const context = await browser.newContext({
    locale: "zh-CN",
    viewport: { width: 320, height: 740 },
  });
  const page = await context.newPage(),
    errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const base = "http://127.0.0.1:5178";
  await page.goto(`${base}/#/vms/native-http-vm/edit`);
  await page
    .locator("#pairing-token")
    .fill((await fixture.command("pair")).pairingToken);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const name = page.getByLabel("名称", { exact: true });
  await expect(name).toHaveValue("原生 HTTP 虚拟机");
  await expect(name).toBeDisabled();
  const session = await (
    await context.request.get(`${base}/api/v1/session`)
  ).json();
  const scope = {
    daemonEpoch: session.server.daemonEpoch,
    sessionId: session.id,
  };
  const post = async (operation, body, status = 200) => {
    const response = await context.request.post(`${base}/api/v1/${operation}`, {
      headers: { "X-DroidVM-CSRF": session.csrfToken, Origin: base },
      data: { ...body, context: scope },
    });
    assert.equal(response.status(), status);
    return response.json();
  };
  const vms = await (await context.request.get(`${base}/api/v1/vms`)).json();
  const observed = vms.items[0].lifecycle;
  await post(
    "vm-operations",
    {
      requestId: "fixture-stop-edit",
      vmId: "native-http-vm",
      action: "stop",
      expected: {
        instanceId: observed.instanceId,
        revision: observed.revision,
        configRevision: observed.configRevision,
      },
    },
    202,
  );
  await expect(name).toBeEnabled();
  if (values["terminal-only"]) {
    await verifyTerminal({ page, post, fixture, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["network-deletion-only"]) {
    await verifyNetworkDeletion({
      page,
      post,
      fixture,
      output,
      expect,
      require,
    });
    assert.deepEqual(errors, []);
  } else if (values["network-runtime-only"]) {
    await verifyNetworkRuntime({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["network-editor-only"]) {
    await verifyNetworkEditor({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["disk-recovery-only"]) {
    await verifyDiskRecovery({ page, post, output, expect });
    assert.deepEqual(errors, []);
  } else if (values["disk-actions-only"]) {
    await verifyDiskActions({ page, post, output, expect });
    assert.deepEqual(errors, []);
  } else if (values["disks-only"]) {
    await verifyDiskDetails({ page, output, expect });
    assert.deepEqual(errors, []);
  } else if (values["host-only"]) {
    await verifyHostCapabilities({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["cpu-only"]) {
    await verifyConfigurationCpus({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["usb-rules-only"]) {
    await verifyUsbRules({ page, post, fixture, output, expect });
    assert.deepEqual(errors, []);
  } else if (values["usb-only"]) {
    await verifyUsbResources({ page, post, output, expect });
    assert.deepEqual(errors, []);
  } else if (values["storage-only"]) {
    await verifyConfigurationStorage({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["peripherals-only"]) {
    await verifyConfigurationPeripherals({
      page,
      post,
      output,
      expect,
      require,
    });
    assert.deepEqual(errors, []);
  } else if (values["networks-only"]) {
    await verifyConfigurationNetworks({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["graphics-only"]) {
    await verifyConfigurationGraphics({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["screens-only"]) {
    await verifyConfigurationScreens({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["basic-only"]) {
    await verifyConfigurationBasic({ page, post, output, expect, require });
    assert.deepEqual(errors, []);
  } else if (values["create-only"]) {
    await verifyConfigurationCreate({
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
    });
    assert.deepEqual(errors, []);
  } else if (values["copy-only"]) {
    await verifyConfigurationCopy({
      page,
      context,
      base,
      post,
      fixture,
      output,
      expect,
      require,
    });
    assert.deepEqual(errors, []);
  } else {
    const anonymous = await browser.newContext();
    assert.equal(
      (await anonymous.request.get(`${base}/api/v1/disks`)).status(),
      401,
    );
    await anonymous.close();
    const diskCatalog = await (
      await context.request.get(`${base}/api/v1/disks`)
    ).json();
    const networkCatalog = await (
      await context.request.get(`${base}/api/v1/networks`)
    ).json();
    assert.deepEqual(diskCatalog.context, scope);
    assert.equal(diskCatalog.items[0].selectable, true);
    assert.equal(diskCatalog.items[1].selectable, false);
    assert.equal(networkCatalog.items[0].id, "fixture-network");
    assert(
      !JSON.stringify([diskCatalog, networkCatalog]).includes('"private"'),
    );
    await page.goto(`${base}/#/resources/disk`);
    await expect(
      page.getByRole("heading", { name: "测试磁盘.qcow2", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("searchbox", { name: "搜索名称、ID 或路径", exact: true })
      .fill("registered");
    await expect(
      page.getByRole("heading", { name: "失效磁盘", exact: true }),
    ).toBeHidden();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: join(output, "resources-320.png"),
      fullPage: true,
    });
    await page.goto(`${base}/#/resources/network`);
    await expect(
      page.getByRole("heading", { name: "测试网络", exact: true }),
    ).toBeVisible();
    await page.goto(`${base}/#/vms/native-http-vm/edit`);
    await expect(name).toBeEnabled();
    const categories = page.getByRole("group", {
      name: "配置分类",
      exact: true,
    });
    const choose = async (label) => {
      const button = categories.getByRole("button", {
        name: label,
        exact: true,
      });
      await button.click();
      await expect(button).toHaveAttribute("aria-pressed", "true");
    };
    const raw = page.getByRole("textbox", {
      name: "高级配置（JSON）",
      exact: true,
    });
    const labels = ["基本", "启动", "存储", "网络", "图形", "外设"];
    await expect(categories.getByRole("button")).toHaveText(labels);
    await page.getByRole("button", { name: "编辑 JSON", exact: true }).click();
    const resources = {
      disks: [
        {
          path: "/fixture/disk.qcow2",
          bus: "VIRTIO",
          readonly: false,
          extension: "keep-disk",
        },
      ],
      shared_dirs: [
        {
          path: "/fixture/share",
          tag: "share",
          timeout: 5,
          extension: "keep-share",
        },
      ],
      networks: [
        {
          network_id: "fixture-net",
          mac_address: "02:00:00:00:00:01",
          extension: "keep-nic",
        },
      ],
      screens: {
        "gpu-0": {
          enabled: true,
          width: 640,
          height: 480,
          exporter: "NONE",
          extension: "keep-screen",
        },
      },
      peripherals: [
        {
          type: "XHCI_USB",
          id: "xhci-0",
          usb2_ports: 8,
          usb3_ports: 8,
          extension: "keep-device",
        },
      ],
      serial_ports: [
        { hardware: "SERIAL", num: 1, backend: "APP_CONSOLE", fixed: true },
      ],
    };
    const seed = await raw.inputValue();
    await raw.fill(
      seed.replace(/}\s*$/, `,${JSON.stringify(resources).slice(1)}`),
    );
    await page.getByRole("button", { name: "应用到草稿", exact: true }).click();
    await choose("存储");
    await page.locator('[data-config-path="disks.0.readonly"]').check();
    const tag = page.getByRole("textbox", { name: "挂载标签", exact: true });
    await tag.fill("shared-data");
    await tag.blur();
    await choose("网络");
    const mac = page.getByRole("textbox", { name: "MAC 地址", exact: true });
    await mac.fill("02:00:00:00:00:02");
    await mac.blur();
    await choose("图形");
    const width = page.locator('[data-config-path="screens.gpu-0.width"]');
    await width.fill("800");
    await width.blur();
    await page.screenshot({
      path: join(output, "graphics-320.png"),
      fullPage: true,
    });
    await choose("外设");
    const ports = page.getByRole("spinbutton", {
      name: "USB 2.0 端口数量",
      exact: true,
    });
    await ports.fill("4");
    await ports.blur();
    await page
      .getByRole("combobox", { name: "串口输出方式", exact: true })
      .selectOption("pty");
    const checkResources = (row) => {
      assert.equal(row.disks[0].readonly, true);
      assert.equal(row.shared_dirs[0].tag, "shared-data");
      assert.equal(row.networks[0].mac_address, "02:00:00:00:00:02");
      assert.equal(row.screens["gpu-0"].width, 800);
      assert.equal(row.peripherals[0].usb2_ports, 4);
      assert.equal(row.serial_ports[0].backend, "pty");
      assert.equal(row.serial_ports[0].fixed, true);
      assert.equal(row.disks[1].path, "/fixture/registered.qcow2");
      assert.equal(row.disks[1].readonly, true);
      assert.equal(row.networks[1].network_id, "fixture-network");
      assert.match(
        row.networks[1].mac_address,
        /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/,
      );
      assert.equal(
        parseInt(row.networks[1].mac_address.slice(0, 2), 16) & 3,
        2,
      );
      for (const key of ["disks", "shared_dirs", "networks", "peripherals"])
        assert.equal(row[key][0].extension, resources[key][0].extension);
      assert.equal(row.screens["gpu-0"].extension, "keep-screen");
    };
    await choose("存储");
    const diskSelect = page.getByRole("combobox", {
      name: "选择已登记磁盘",
      exact: true,
    });
    await expect(diskSelect).toBeEnabled();
    await expect(
      diskSelect.locator("option", { hasText: "失效磁盘" }),
    ).toHaveCount(0);
    await diskSelect.selectOption("fixture-disk");
    await page.getByRole("button", { name: "添加磁盘", exact: true }).click();
    await choose("网络");
    const networkSelect = page.getByRole("combobox", {
      name: "选择网络",
      exact: true,
    });
    await expect(networkSelect).toBeEnabled();
    await networkSelect.selectOption("fixture-network");
    await page.getByRole("button", { name: "添加网卡", exact: true }).click();
    await page.getByRole("button", { name: "编辑 JSON", exact: true }).click();
    checkResources(JSON.parse(await raw.inputValue()));
    await page.getByRole("button", { name: "取消", exact: true }).click();
    await choose("启动");
    await page
      .getByRole("combobox", { name: "启动协议", exact: true })
      .selectOption("linux");
    await page
      .getByRole("combobox", { name: "Linux 内核来源", exact: true })
      .selectOption("image");
    const bootDisk = page.getByRole("combobox", {
      name: "镜像启动磁盘",
      exact: true,
    });
    await bootDisk.selectOption("1");
    await choose("存储");
    const attachment = (key, index) =>
      page.locator(`[data-attachment="${key}.${index}"]`);
    await attachment("disks", 1)
      .getByRole("button", { name: "上移", exact: true })
      .click();
    await choose("启动");
    await expect(bootDisk).toHaveValue("0");
    await choose("存储");
    await attachment("disks", 0)
      .getByRole("button", { name: "下移", exact: true })
      .click();
    await attachment("disks", 0)
      .getByRole("button", { name: "更换资源", exact: true })
      .click();
    await expect(diskSelect).toBeEnabled();
    await diskSelect.selectOption("fixture-disk");
    await page.getByRole("button", { name: "使用此磁盘", exact: true }).click();
    await page
      .getByRole("button", { name: "添加共享目录", exact: true })
      .click();
    await attachment("shared_dirs", 1)
      .getByRole("textbox", { name: "宿主目录路径", exact: true })
      .fill("/fixture/extra");
    await attachment("shared_dirs", 1)
      .getByRole("textbox", { name: "挂载标签", exact: true })
      .fill("extra");
    await attachment("shared_dirs", 1)
      .getByRole("button", { name: "移除", exact: true })
      .click();
    await choose("网络");
    await attachment("networks", 0)
      .getByRole("button", { name: "更换资源", exact: true })
      .click();
    await expect(networkSelect).toBeEnabled();
    await networkSelect.selectOption("fixture-network");
    await page.getByRole("button", { name: "使用此网络", exact: true }).click();
    await page.getByRole("button", { name: "编辑 JSON", exact: true }).click();
    const attachedDraft = await raw.inputValue();
    const attached = JSON.parse(attachedDraft);
    checkResources(attached);
    assert.equal(attached.disks[0].path, "/fixture/registered.qcow2");
    assert.equal(attached.networks[0].network_id, "fixture-network");
    assert.equal(attached.boot.linux.image.disk, 1);
    await page.getByRole("button", { name: "取消", exact: true }).click();
    const vlan = attachment("networks", 1).getByRole("spinbutton", {
      name: "VLAN ID",
      exact: true,
    });
    await vlan.fill("9999");
    await vlan.blur();
    await attachment("networks", 0)
      .getByRole("button", { name: "移除", exact: true })
      .click();
    await expect(
      attachment("networks", 0).getByRole("spinbutton", {
        name: "VLAN ID",
        exact: true,
      }),
    ).toHaveValue("9999");
    await choose("基本");
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(
      attachment("networks", 0).getByRole("spinbutton", {
        name: "VLAN ID",
        exact: true,
      }),
    ).toBeFocused();
    await attachment("networks", 0)
      .getByRole("spinbutton", { name: "VLAN ID", exact: true })
      .fill("1");
    await attachment("networks", 0)
      .getByRole("spinbutton", { name: "VLAN ID", exact: true })
      .blur();
    await choose("存储");
    await attachment("disks", 1)
      .getByRole("button", { name: "移除", exact: true })
      .click();
    await attachment("disks", 0)
      .getByRole("button", { name: "移除", exact: true })
      .click();
    await page.getByRole("button", { name: "保存配置", exact: true }).click();
    await expect(bootDisk).toBeFocused();
    await expect(page.getByRole("dialog")).toBeHidden();
    await page.getByRole("button", { name: "编辑 JSON", exact: true }).click();
    await raw.fill(attachedDraft);
    await page.getByRole("button", { name: "应用到草稿", exact: true }).click();
    await choose("基本");
    {
      const save = page.getByRole("button", { name: "保存配置", exact: true });
      const cpu = page.getByRole("spinbutton", { name: "处理器", exact: true });
      const kernel = page.getByRole("textbox", {
        name: "内核路径",
        exact: true,
      });
      await name.fill("分类草稿");
      await name.blur();
      await choose("启动");
      await expect(name).toBeHidden();
      await kernel.fill("/draft/kernel");
      await kernel.blur();
      await choose("基本");
      await expect(name).toHaveValue("分类草稿");
      await expect(kernel).toBeHidden();
      await cpu.fill("0");
      await cpu.blur();
      await choose("外设");
      await page
        .getByRole("button", { name: "编辑 JSON", exact: true })
        .click();
      await raw.fill(`${await raw.inputValue()} `);
      const rawDraft = await raw.inputValue();
      await choose("启动");
      await expect(save).toBeDisabled();
      await expect(kernel).toBeDisabled();
      await choose("外设");
      await expect(raw).toHaveValue(rawDraft);
      await page.getByRole("button", { name: "取消", exact: true }).click();
      await choose("启动");
      await expect(kernel).toHaveValue("/draft/kernel");
      await save.click();
      await expect(
        categories.getByRole("button", { name: "基本", exact: true }),
      ).toHaveAttribute("aria-pressed", "true");
      await expect(cpu).toBeFocused();
      await expect(cpu).toHaveValue("0");
      await expect(page.getByRole("dialog")).toBeHidden();
      await cpu.fill("2");
      await cpu.blur();
      for (const label of labels) {
        await choose(label);
        assert.equal(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
          true,
        );
      }
      await save.click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await page
        .getByRole("dialog")
        .getByRole("button", { name: "取消", exact: true })
        .click();
      await choose("基本");
      await page.screenshot({
        path: join(output, "categories-320.png"),
        fullPage: true,
      });
      assert.deepEqual(errors, []);
      writeFileSync(
        join(
          output,
          values["categories-only"] ? "result.json" : "categories.json",
        ),
        JSON.stringify(
          {
            passed: true,
            checks: [
              "category-draft-preservation",
              "raw-draft-save-gate-across-categories",
              "hidden-invalid-field-focus",
              "category-mobile-320",
              "valid-cross-category-save-confirmation",
              "app-six-category-order",
              "storage-network-graphics-peripheral-fields-preserve-extensions",
              "authenticated-resource-catalogues-and-whitelist",
              "resource-list-search-mobile",
              "registered-disk-and-network-attachment",
              "attachment-replace-remove-reorder-and-shared-directory",
              "boot-index-follows-slot-and-empty-image-boot-is-blocked",
              "invalid-input-follows-row-removal",
            ],
          },
          null,
          2,
        ),
      );
      console.log(`Configuration category checks passed; ${output}`);
    }
    if (!values["categories-only"]) {
      await page
        .getByRole("button", { name: "编辑 JSON", exact: true })
        .click();
      const draft = await raw.inputValue();
      assert(draft.includes("9007199254740993"));
      await raw.fill(
        draft.replace(
          /}\s*$/,
          `,"largeNote":${JSON.stringify("x".repeat(60_000))}}`,
        ),
      );
      await page
        .getByRole("button", { name: "应用到草稿", exact: true })
        .click();
      await choose("基本");
      await name.fill("保留扩展的修改");
      await name.blur();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: join(output, "editor-320.png"),
        fullPage: true,
      });
      let commits = 0;
      await page.route("**/api/v1/vm-configuration/commit", async (route) => {
        commits++;
        await route.fetch(); // Accepted by the real TaskService; discard only the reply.
        await route.abort("connectionfailed");
      });
      await page.getByRole("button", { name: "保存配置", exact: true }).click();
      await page
        .getByRole("dialog")
        .getByRole("button", { name: "保存配置", exact: true })
        .click();
      await expect(
        page.getByText("配置已保存，可关闭或重新读取", { exact: true }),
      ).toBeVisible();
      assert.equal(commits, 1);
      let snapshotId,
        outputBytes = [];
      try {
        let pageValue = await post("vm-configuration/read", {
          snapshotId: null,
          offset: 0,
        });
        snapshotId = pageValue.snapshotId;
        for (;;) {
          for (const item of pageValue.items)
            if (item.type === "chunk")
              outputBytes.push(Buffer.from(item.data, "base64"));
          if (pageValue.next === null) break;
          pageValue = await post("vm-configuration/read", {
            snapshotId,
            offset: pageValue.next,
          });
        }
      } finally {
        if (snapshotId) await post("vm-configuration/release", { snapshotId });
      }
      const saved = Buffer.concat(outputBytes).toString();
      assert(
        saved.includes("18446744073709551615") &&
          saved.includes("9007199254740993"),
      );
      assert.equal(JSON.parse(saved).vms[0].name, "保留扩展的修改");
      checkResources(JSON.parse(saved).vms[0]);
      assert.equal(JSON.parse(saved).vms[0].largeNote.length, 60_000);
      await page.unroute("**/api/v1/vm-configuration/commit");
      await page.getByRole("button", { name: "重新读取", exact: true }).click();
      await page
        .getByRole("dialog")
        .getByRole("button", { name: "重新读取", exact: true })
        .click();
      await expect(name).toBeEnabled();
      await fixture.command("config-touch");
      await name.fill("保留冲突草稿");
      await name.blur();
      await page.getByRole("button", { name: "保存配置", exact: true }).click();
      await page
        .getByRole("dialog")
        .getByRole("button", { name: "保存配置", exact: true })
        .click();
      await expect(
        page.getByText("请先核实配置再继续编辑", { exact: true }),
      ).toBeVisible();
      await expect(name).toHaveValue("保留冲突草稿");
      assert.deepEqual(errors, []);
      writeFileSync(
        join(output, "result.json"),
        JSON.stringify(
          {
            passed: true,
            checks: [
              "stopped-edit-gate",
              "mobile-320",
              "48k-chunk-upload",
              "unknown-and-uint64-preservation",
              "lost-commit-reply-read-only-reconcile",
              "conditional-conflict-keeps-draft",
            ],
          },
          null,
          2,
        ),
      );
      console.log(`Native VM configuration integration passed; ${output}`);
    }
  }
} finally {
  await browser?.close();
  await vite?.close();
  await fixture.close();
  writeFileSync(join(output, "service.log"), fixture.log());
}
