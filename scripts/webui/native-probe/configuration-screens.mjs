import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationScreens({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (path) => page.locator(`[data-config-path="${path}"]`);
  const group = (id) =>
    page.locator(".config-section").filter({
      has: page.getByRole("heading", {
        name: id === "gpu-0" ? "virtio-gpu 屏幕" : "SimpleFB 屏幕",
        exact: true,
      }),
    });
  const toggle = (id) =>
    group(id).getByRole("checkbox", { name: "启用屏幕", exact: true });
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const choose = (label) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name: label, exact: true })
      .click();
  async function change(path, value) {
    await field(path).fill(value);
    await field(path).blur();
  }
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  row.backend = "crosvm";
  row.hypervisor = "kvm";
  row.screens = {
    "gpu-0": {
      enabled: true,
      exporter: "native",
      transport_cap: "gpu",
      width: 1280,
      height: 720,
      refresh_rate: 120,
      dpi_h: 160,
      dpi_v: 160,
      vnc: {
        host: "127.0.0.1",
        port: 5900,
        password_auth: true,
        password: "keep-me",
      },
    },
    simplefb: {
      enabled: true,
      exporter: "vnc",
      width: 1280,
      height: 720,
      poll_hz: 60,
      vnc: { host: "::1", port: 5900 },
    },
    future: { counter: parse("18446744073709551615") },
  };
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await field("backend").selectOption("qemu");
  await field("hypervisor").selectOption("soft");
  await button("保存配置").click();
  await expect(field("screens.gpu-0.exporter")).toBeFocused();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    field("screens.gpu-0.transport_cap").locator('option[value="zero"]'),
  ).toHaveJSProperty("disabled", true);
  await field("screens.gpu-0.exporter").selectOption("vnc");
  await field("screens.gpu-0.transport_cap").selectOption("gpu-hw");
  await choose("启动");
  await button("保存配置").click();
  await expect(field("screens.simplefb.vnc.port")).toBeFocused();
  await change("screens.simplefb.vnc.port", "");
  await change("screens.gpu-0.vnc.host", "http://127.0.0.1");
  await button("保存配置").click();
  await expect(field("screens.gpu-0.vnc.host")).toBeFocused();
  await change("screens.gpu-0.vnc.host", "::ffff:192.0.2.1");
  await change("screens.gpu-0.width", "319");
  await button("保存配置").click();
  await expect(field("screens.gpu-0.width")).toBeFocused();
  await change("screens.gpu-0.width", "1920");
  await toggle("gpu-0").uncheck();
  await expect(field("screens.gpu-0.width")).toBeDisabled();
  await expect(field("screens.gpu-0.vnc.password")).toBeDisabled();
  await toggle("gpu-0").check();
  await expect(field("screens.gpu-0.width")).toHaveValue("1920");
  await expect(field("screens.gpu-0.vnc.password")).toHaveValue("keep-me");
  await group("gpu-0")
    .getByRole("checkbox", { name: "启用 VNC 密码认证", exact: true })
    .uncheck();
  await expect(field("screens.gpu-0.vnc.password")).toBeDisabled();
  await change("screens.gpu-0.vnc.port", "");
  await choose("基本");
  await choose("图形");
  await expect(field("screens.gpu-0.vnc.port")).toHaveValue("");
  await expect(field("screens.gpu-0.transport_cap")).toHaveValue("gpu-hw");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await group("gpu-0").getByRole("heading").scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "screens-320.png") });
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
    const screens = saved.vms[0].screens;
    assert.equal(screens["gpu-0"].width.toString(), "1920");
    assert.equal(screens["gpu-0"].transport_cap, "gpu-hw");
    assert.equal(screens["gpu-0"].vnc.host, "::ffff:192.0.2.1");
    assert.equal(screens["gpu-0"].vnc.port.toString(), "-1");
    assert.equal(screens.simplefb.vnc.port.toString(), "-1");
    assert.equal(screens["gpu-0"].vnc.password, "keep-me");
    assert.equal(screens["gpu-0"].vnc.password_auth, false);
    assert.equal(screens.future.counter.toString(), "18446744073709551615");
    assert.equal(saved.extension.counter.toString(), "18446744073709551615");
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  await button("重新读取").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "重新读取", exact: true })
    .click();
  await expect(field("screens.gpu-0.vnc.port")).toHaveValue("");
  await expect(field("screens.gpu-0.transport_cap")).toHaveValue("gpu-hw");
  writeFileSync(
    join(output, "screens.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "qemu-native-display-rejected",
          "transport-options",
          "duplicate-vnc-port-focus",
          "literal-address-validation",
          "geometry-bounds",
          "disabled-values-preserved",
          "automatic-ports-roundtrip",
          "unknown-screen-uint64-preserved",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native configuration screen checks passed; ${output}`);
}
