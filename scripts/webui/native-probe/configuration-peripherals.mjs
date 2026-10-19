import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyConfigurationPeripherals({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (key) => page.locator(`[data-config-path="${key}"]`);
  const section = (key) => page.locator(`[data-section="${key}"]`);
  const audio = (key) =>
    section(key).getByRole("combobox", { name: "宿主音频设备", exact: true });
  const choose = (label) =>
    page
      .getByRole("group", { name: "配置分类", exact: true })
      .getByRole("button", { name: label, exact: true })
      .click();
  const change = async (key, value) => {
    await field(key).fill(value);
    await field(key).blur();
  };
  const add = async (kind) => {
    await page
      .getByRole("combobox", { name: "添加设备", exact: true })
      .selectOption(kind);
    await button("添加设备").click();
  };
  await button("编辑 JSON").click();
  const raw = page.getByRole("textbox", {
    name: "高级配置（JSON）",
    exact: true,
  });
  const row = parse(await raw.inputValue());
  row.xhci_next = 5;
  row.peripherals = [
    { type: "xhci_usb", id: "xhci-0", usb2_ports: 8, usb3_ports: 8 },
    {
      type: "virtio_sound",
      endpoints: [
        { mode: "speaker", host_device: "SPEAKER|1", host_label: "手机扬声器" },
        { mode: "microphone", host_device: "DEFAULT|system default" },
      ],
    },
    {
      type: "virtio_sound",
      endpoints: [
        {
          mode: "speaker",
          host_device: "SPEAKER|1#usage=future_usage,content=music,future=keep",
          host_label: "手机扬声器",
          extension: parse("18446744073709551615"),
        },
      ],
    },
    { type: "future-device", extension: parse("18446744073709551615") },
  ];
  delete row.serial_ports;
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await choose("基本");
  await button("保存配置").click();
  await expect(field("peripherals.2.buffer")).toBeFocused();
  await expect(field("peripherals.2.buffer")).toHaveJSProperty(
    "validationMessage",
    "同一方向的多个端点绑定了相同宿主设备，请移除重复端点或改为跟随系统。",
  );
  await audio("peripherals.2.endpoints.0").selectOption(
    "DEFAULT|system default",
  );
  await expect(field("peripherals.2.buffer")).toHaveJSProperty(
    "validationMessage",
    "",
  );
  await expect(audio("peripherals.2.endpoints.0")).toHaveValue(
    "DEFAULT|system default",
  );
  const response = await page.request.get(
    new URL("/api/v1/host/audio", page.url()).href,
  );
  assert.equal(response.status(), 200);
  const catalogue = await response.json();
  assert.equal(catalogue.available, true);
  assert.equal(catalogue.items.length, 2);
  assert.equal(catalogue.fingerprint, undefined);
  assert.deepEqual(Object.keys(catalogue.items[0]).sort(), [
    "id",
    "input",
    "key",
    "kind",
    "name",
  ]);
  await expect(
    audio("peripherals.2.endpoints.0").locator(
      'option[value="BUILTIN_SPEAKER|"]',
    ),
  ).toHaveCount(1);
  await expect(
    audio("peripherals.2.endpoints.0").locator(
      'option[value="BUILTIN_MIC|bottom"]',
    ),
  ).toHaveCount(0);
  await expect(
    audio("peripherals.1.endpoints.1").locator(
      'option[value="BUILTIN_SPEAKER|"]',
    ),
  ).toHaveCount(0);
  await audio("peripherals.2.endpoints.0").selectOption("BUILTIN_SPEAKER|");
  await audio("peripherals.1.endpoints.1").selectOption("BUILTIN_MIC|bottom");
  const purpose = (key, name) =>
    section(key).getByRole("combobox", { name, exact: true });
  const speaker = "peripherals.2.endpoints.0";
  const microphone = "peripherals.1.endpoints.1";
  await expect(purpose(speaker, "用途")).toHaveValue("future_usage");
  await expect(purpose(speaker, "内容类型")).toHaveValue("music");
  await expect(purpose(speaker, "麦克风用途")).toHaveCount(0);
  await expect(purpose(microphone, "用途")).toHaveCount(0);
  await purpose(speaker, "内容类型").selectOption("speech");
  await expect(purpose(speaker, "用途")).toHaveValue("future_usage");
  await choose("基本");
  await choose("外设");
  await expect(purpose(speaker, "内容类型")).toHaveValue("speech");
  await purpose(speaker, "用途").selectOption("");
  await expect(purpose(speaker, "用途")).toHaveValue("");
  await purpose(speaker, "用途").selectOption("game");
  await purpose(microphone, "麦克风用途").selectOption("voice_recognition");
  await purpose(microphone, "麦克风用途").selectOption("");
  await expect(purpose(microphone, "麦克风用途")).toHaveValue("");
  await purpose(microphone, "麦克风用途").selectOption("voice_communication");
  await audio(microphone).selectOption("DEFAULT|system default");
  await expect(purpose(microphone, "麦克风用途")).toHaveValue("voice_communication");
  await audio(microphone).selectOption("BUILTIN_MIC|bottom");
  // Simulate unplug/service loss by changing a real catalogue response.
  await page.route("**/api/v1/host/audio", async (route) => {
    const upstream = await route.fetch(),
      data = await upstream.json();
    data.items = [];
    await route.fulfill({ response: upstream, json: data });
  });
  await button("刷新音频设备").click();
  await expect(audio("peripherals.2.endpoints.0")).toHaveValue(
    "BUILTIN_SPEAKER|",
  );
  await expect(audio("peripherals.2.endpoints.0")).toContainText("当前未连接");
  await page.unroute("**/api/v1/host/audio");
  await page.route("**/api/v1/host/audio", async (route) => {
    const upstream = await route.fetch(),
      data = await upstream.json();
    data.available = false;
    data.items = [];
    await route.fulfill({ response: upstream, json: data });
  });
  await button("刷新音频设备").click();
  await expect(
    page.getByText(
      "暂时无法读取手机音频设备。已有绑定会保留，也可选择跟随系统。",
      { exact: true },
    ),
  ).toBeVisible();
  await expect(audio("peripherals.2.endpoints.0")).toHaveValue(
    "BUILTIN_SPEAKER|",
  );
  await page.unroute("**/api/v1/host/audio");
  await button("刷新音频设备").click();
  await expect(
    audio("peripherals.2.endpoints.0").locator(
      'option[value="BUILTIN_SPEAKER|"]',
    ),
  ).toContainText("扬声器");
  await add("xhci_usb");
  await expect(field("peripherals.4.id")).toHaveValue("xhci-5");
  await section("peripherals.0")
    .getByRole("button", { name: "移除", exact: true })
    .click();
  await add("xhci_usb");
  await expect(field("peripherals.4.id")).toHaveValue("xhci-6");
  await change("peripherals.4.usb2_ports", "15");
  await expect(section("peripherals.2").locator("pre")).toContainText(
    "18446744073709551615",
  );
  for (let count = 0; count < 3; count++)
    await section("peripherals.0")
      .getByRole("button", { name: "添加播放端点", exact: true })
      .click();
  await expect(
    section("peripherals.0").getByRole("button", {
      name: "添加播放端点",
      exact: true,
    }),
  ).toBeDisabled();
  await section("peripherals.0.endpoints.4")
    .getByRole("button", { name: "移除", exact: true })
    .click();
  await expect(
    section("peripherals.0").getByRole("button", {
      name: "添加播放端点",
      exact: true,
    }),
  ).toBeEnabled();
  await field("peripherals.0.buffer").selectOption("safe");
  await audio("peripherals.1.endpoints.0").evaluate((element) =>
    element.scrollIntoView({ block: "center" }),
  );
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "peripherals-audio-320.png") });

  await button("补齐 COM1–4 固定串口").click();
  for (let index = 0; index < 4; index++)
    await expect(
      section(`serial_ports.${index}`).getByRole("button", {
        name: "移除",
        exact: true,
      }),
    ).toHaveCount(0);
  await add("sbsa");
  await expect(button("添加设备")).toBeDisabled();
  await field("serial_ports.4.console").check();
  await expect(field("serial_ports.0.console")).not.toBeChecked();
  await field("serial_ports.4.backend").selectOption("file");
  await choose("启动");
  await button("保存配置").click();
  await expect(field("serial_ports.4.path")).toBeFocused();
  await change("serial_ports.4.path", "/fixture/serial.log");
  await field("serial_ports.4.backend").selectOption("usb_acm");
  await expect(field("serial_ports.4.path")).toBeHidden();
  await change("serial_ports.4.usb_slot", "0");
  await add("virtio_console");
  await field("serial_ports.5.backend").selectOption("usb_acm");
  await button("保存配置").click();
  await expect(field("serial_ports.5.usb_slot")).toBeFocused();
  await change("serial_ports.5.usb_slot", "1");
  // Removing the preceding optional port keeps the remaining port's inputs and identity.
  await section("serial_ports.4")
    .getByRole("button", { name: "移除", exact: true })
    .click();
  await expect(field("serial_ports.4.usb_slot")).toHaveValue("1");
  await add("sbsa");
  await expect(field("serial_ports.5.num")).toHaveValue("1");
  await field("serial_ports.5.console").check();
  await field("serial_ports.5.backend").selectOption("pty");
  await expect(field("serial_ports.5.path")).toBeVisible();
  await expect(field("serial_ports.5.path")).not.toHaveAttribute("required");
  await change("serial_ports.5.path", "/fixture/pty");
  await field("serial_ports.5.backend").selectOption("sink");
  await field("serial_ports.5.backend").selectOption("pty");
  await expect(field("serial_ports.5.path")).toHaveValue("/fixture/pty");
  await field("serial_ports.5.backend").scrollIntoViewIfNeeded();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "peripherals-serial-320.png") });
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
    ).vms[0];
    assert.equal(saved.xhci_next.toString(), "7");
    assert.deepEqual(
      saved.peripherals
        .filter((item) => item.type === "xhci_usb")
        .map((item) => item.id),
      ["xhci-5", "xhci-6"],
    );
    assert.equal(saved.peripherals[4].usb2_ports.toString(), "15");
    assert.equal(saved.peripherals[0].buffer, "safe");
    assert.equal(saved.peripherals[0].endpoints.length, 4);
    assert.equal(
      saved.peripherals[0].endpoints[1].host_device,
      "BUILTIN_MIC|bottom#preset=voice_communication",
    );
    assert.equal(
      saved.peripherals[1].endpoints[0].host_device,
      "BUILTIN_SPEAKER|#future=keep,content=speech,usage=game",
    );
    assert.equal(
      saved.peripherals[1].endpoints[0].extension.toString(),
      "18446744073709551615",
    );
    assert.equal(
      saved.peripherals[2].extension.toString(),
      "18446744073709551615",
    );
    assert.equal(saved.serial_ports.length, 6);
    assert.equal(
      saved.serial_ports.filter((port) => port.console === true).length,
      1,
    );
    assert.equal(saved.serial_ports[5].console, true);
    assert.equal(saved.serial_ports[5].path, "/fixture/pty");
    assert.equal(saved.serial_ports[4].usb_slot.toString(), "1");
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(
    join(output, "peripherals.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "native-audio-whitelist-dedup-and-direction-filter",
          "audio-picker-disconnect-and-unavailable-preserve-binding",
          "audio-binding-preserves-purpose-and-unknown-suffix",
          "audio-purpose-direction-defaults-and-unknown-values",
          "audio-purpose-preserves-unrelated-attributes-on-http-save",
          "xhci-monotonic-ids-and-port-counts",
          "endpoint-add-remove-and-per-direction-limit",
          "duplicate-audio-host-blocks-save",
          "restore-default-routing-preserves-extensions",
          "fixed-serials-and-optional-hardware-limit",
          "single-console-selection",
          "required-path-and-duplicate-acm-slot",
          "inactive-values-and-index-remapping",
          "unknown-devices-and-uint64-preserved",
          "http-save-readback",
          "mobile-320-no-overflow",
        ],
      },
      null,
      2,
    ),
  );
  console.log(`Native configuration peripheral checks passed; ${output}`);
}
