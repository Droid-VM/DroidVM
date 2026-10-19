import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

export async function verifyConfigurationNetworks({
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
  // Seed a second saved VM through the real configuration API. Reloading the
  // editor must index its assignments from that same conditional-save baseline.
  const initial = await post("vm-configuration/read", {
    snapshotId: null,
    offset: 0,
  });
  assert.equal(initial.next, null);
  const document = parse(
    Buffer.concat(
      initial.items
        .filter((item) => item.type === "chunk")
        .map((item) => Buffer.from(item.data, "base64")),
    ).toString(),
  );
  await post("vm-configuration/release", { snapshotId: initial.snapshotId });
  const peer = {
    id: "peer-leases",
    name: "其他虚拟机",
    backend: "qemu",
    networks: [
      {
        network_id: "fixture-network",
        vlan_id: 4095,
        mac_address: "02:00:00:00:00:99",
        dhcp4_lease: { enabled: true, offset: 66 },
        dhcp6_lease: { enabled: true, offset: parse("9007199254740996") },
      },
    ],
  };
  document.vms.push(peer);
  const bytes = Buffer.from(stringify(document));
  const { uploadId } = await post("vm-configuration/begin", {
    revision: initial.items[0].revision,
    bytes: bytes.length,
  });
  await post("vm-configuration/append", {
    uploadId,
    offset: 0,
    data: bytes.toString("base64"),
  });
  const requestId = randomUUID();
  await post("vm-configuration/commit", { uploadId, requestId }, 202);
  await expect
    .poll(
      async () =>
        (await post("vm-configuration/status", { requestId })).task?.state,
    )
    .toBe("succeeded");
  await button("重新读取").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "重新读取", exact: true })
    .click();
  await expect(button("编辑 JSON")).toBeEnabled();
  const catalogueResponse = await page.request.get(
    new URL("/api/v1/networks", page.url()).href,
  );
  assert.equal(catalogueResponse.status(), 200);
  const catalogue = await catalogueResponse.json();
  const network = catalogue.items[0];
  assert.equal(network.private, undefined);
  assert.equal(network.vlans.length, 3);
  assert.deepEqual(network.vlans[0].ipv4, {
    cidr: "192.0.2.1/24",
    source: "static",
    dhcpEnabled: true,
    snatEnabled: true,
    poolStart: "128",
    poolEnd: "192",
  });
  assert.equal(network.vlans[0].ipv6.poolStart, "9007199254740994");
  assert.equal(network.vlans[0].ipv6.snatEnabled, false);
  assert.equal(network.vlans[1].ipv4.dhcpEnabled, false);
  assert.equal(network.vlans[2].ipv4.snatEnabled, false);
  await button("编辑 JSON").click();
  const row = parse(await raw.inputValue());
  row.networks = [
    {
      network_id: "fixture-network",
      mac_address: "02:00:00:00:00:01",
      vlan_id: 4095,
      dhcp4_lease: {
        enabled: true,
        offset: 64,
        forwards: [
          { proto: "tcp", host: "80", guest: "80" },
          {
            proto: "udp",
            host: "8000-8002",
            guest: "9000-9001",
            extension: parse("18446744073709551615"),
          },
          "future-rule",
        ],
      },
      dhcp6_lease: {
        enabled: true,
        offset: parse("9007199254740993"),
        forwards: [{ proto: "udp", host: "53", guest: "53" }],
      },
    },
    {
      network_id: "fixture-network",
      mac_address: "02:00:00:00:00:02",
      dhcp4_lease: {
        enabled: true,
        offset: 64,
        forwards: { future: parse("18446744073709551615") },
      },
    },
    {
      network_id: "fixture-network",
      mac_address: "02:00:00:00:00:03",
      dhcp4_lease: { enabled: false, extension: parse("18446744073709551615") },
    },
  ];
  await raw.fill(stringify(row));
  await button("应用到草稿").click();
  await choose("网络");
  await expect(
    page.getByRole("option", { name: "测试网络", exact: true }),
  ).toHaveCount(1);
  const prefix = "networks.0.dhcp4_lease.forwards";
  await change("networks.1.dhcp4_lease.offset", "65");
  await field("networks.2.dhcp4_lease.enabled").check();
  await expect(field("networks.2.dhcp4_lease.offset")).toHaveValue("67");
  await field("networks.2.dhcp4_lease.offset").scrollIntoViewIfNeeded();
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "network-allocation-320.png") });
  await change("networks.2.dhcp4_lease.offset", "66");
  await expect(field("networks.2.dhcp4_lease.offset")).toHaveJSProperty(
    "validationMessage",
    "其他虚拟机已配置使用此网络/VLAN 的该地址偏移，请选择其他偏移。",
  );
  await field("networks.2.dhcp4_lease.offset")
    .locator("..")
    .getByRole("button", { name: "选择可用偏移", exact: true })
    .click();
  await expect(field("networks.2.dhcp4_lease.offset")).toHaveValue("67");
  await expect(field("networks.2.dhcp4_lease.offset")).toHaveJSProperty(
    "validationMessage",
    "",
  );
  await field("networks.2.dhcp4_lease.enabled").uncheck();
  await field("networks.2.dhcp4_lease.enabled").check();
  await expect(field("networks.2.dhcp4_lease.offset")).toHaveValue("67");
  await section("networks.2")
    .getByRole("button", { name: "移除", exact: true })
    .click();
  await change("networks.1.dhcp4_lease.offset", "64");
  await change("networks.0.dhcp6_lease.offset", "9007199254740996");
  await expect(field("networks.0.dhcp6_lease.offset")).toHaveJSProperty(
    "validationMessage",
    "其他虚拟机已配置使用此网络/VLAN 的该地址偏移，请选择其他偏移。",
  );
  await field("networks.0.dhcp6_lease.offset")
    .locator("..")
    .getByRole("button", { name: "选择可用偏移", exact: true })
    .click();
  await expect(field("networks.0.dhcp6_lease.offset")).toHaveValue("64");
  await change("networks.0.dhcp6_lease.offset", "9007199254740993");
  await change("networks.0.vlan_id", "30");
  await expect(field("networks.0.dhcp4_lease.enabled")).toBeDisabled();
  await expect(field("networks.0.dhcp4_lease.enabled")).toBeVisible();
  await expect(section("networks.0")).toContainText("所选网络未配置此 VLAN");
  await change("networks.0.vlan_id", "10");
  await expect(field("networks.0.dhcp4_lease.enabled")).toBeDisabled();
  await expect(section("networks.0")).toContainText(
    "此 VLAN 未启用该地址族的 DHCP",
  );
  await change("networks.0.vlan_id", "20");
  await expect(field("networks.0.dhcp4_lease.enabled")).toBeEnabled();
  await expect(section(prefix)).toContainText("此 VLAN 未启用该地址族的 SNAT");
  await expect(field(prefix + ".0.host")).toHaveCount(0);
  await change("networks.0.vlan_id", "");
  await expect(field(prefix + ".0.host")).toHaveValue("80");
  for (const [offset, message] of [
    ["255", "地址偏移超出子网可用范围，不能使用网络地址或 IPv4 广播地址。"],
    ["1", "此地址偏移与 VLAN 网关冲突。"],
    ["128", "此地址偏移位于 DHCP 动态地址池内，请使用池外地址。"],
  ]) {
    await change("networks.0.dhcp4_lease.offset", offset);
    await expect(field("networks.0.dhcp4_lease.offset")).toHaveJSProperty(
      "validationMessage",
      message,
    );
  }
  await change("networks.0.dhcp4_lease.offset", "64");
  await change("networks.0.dhcp6_lease.offset", "9007199254740994");
  await expect(field("networks.0.dhcp6_lease.offset")).toHaveJSProperty(
    "validationMessage",
    "此地址偏移位于 DHCP 动态地址池内，请使用池外地址。",
  );
  await change("networks.0.dhcp6_lease.offset", "9007199254740993");
  await expect(section("networks.0.dhcp6_lease.forwards")).toContainText(
    "IPv6 转发需要启用 IPv6 SNAT 的 gVisor 网络",
  );
  await change("networks.0.mac_address", "invalid");
  await choose("基本");
  await button("保存配置").click();
  await expect(field("networks.0.mac_address")).toBeFocused();
  await change("networks.0.mac_address", "02:00:00:00:00:01");
  await expect(field("networks.0.mac_address")).toHaveJSProperty(
    "validationMessage",
    "",
  );
  await change("networks.0.vlan_id", "0");
  await expect(field("networks.0.vlan_id")).toHaveJSProperty(
    "validationMessage",
    "Linux 网桥不支持仅无标签端口，请使用 Trunk 或指定 VLAN。",
  );
  await button("保存配置").click();
  await expect(field("networks.0.vlan_id")).toBeFocused();
  await change("networks.0.vlan_id", "");
  await button("保存配置").click();
  await expect(field(prefix + ".1.guest")).toBeFocused();
  await section(prefix + ".0")
    .getByRole("button", { name: "移除此规则", exact: true })
    .click();
  await choose("启动");
  await button("保存配置").click();
  await expect(field(prefix + ".0.guest")).toBeFocused();
  await change(prefix + ".0.guest", "9000-9002");
  await field(prefix + ".0.proto").selectOption("any");
  await button("保存配置").click();
  await expect(field("networks.1.dhcp4_lease.offset")).toBeFocused();
  await change("networks.1.dhcp4_lease.offset", "65");
  await section(prefix)
    .getByRole("button", { name: "添加转发规则", exact: true })
    .click();
  await change(prefix + ".2.host", "65536");
  await change(prefix + ".2.guest", "80");
  await button("保存配置").click();
  await expect(field(prefix + ".2.host")).toBeFocused();
  await change(prefix + ".2.host", "8080");
  await change("networks.0.dhcp4_lease.offset", "0");
  await field("networks.0.dhcp4_lease.enabled").uncheck();
  await expect(field(prefix + ".0.host")).toHaveCount(0);
  await button("保存配置").click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "取消", exact: true })
    .click();
  await field("networks.0.dhcp4_lease.enabled").check();
  await button("保存配置").click();
  await expect(field("networks.0.dhcp4_lease.offset")).toBeFocused();
  await change("networks.0.dhcp4_lease.offset", "64");
  await expect(field(prefix + ".0.guest")).toHaveValue("9000-9002");
  await expect(section(prefix + ".1").locator("pre")).toHaveText(
    '"future-rule"',
  );
  await expect(
    section("networks.1.dhcp4_lease.forwards").locator("pre"),
  ).toContainText("18446744073709551615");
  // Deliver a real catalogue response after typing, before blur. Changing only
  // its name makes the Vue refresh observable without changing capabilities.
  await choose("基本");
  let releaseCatalogue, catalogueReady;
  const gate = new Promise((resolve) => {
    releaseCatalogue = resolve;
  });
  const ready = new Promise((resolve) => {
    catalogueReady = resolve;
  });
  await page.route(
    "**/api/v1/networks",
    async (route) => {
      const response = await route.fetch();
      const data = await response.json();
      data.items[0].name = "测试网络（刷新）";
      catalogueReady();
      await gate;
      await route.fulfill({ response, json: data });
    },
    { times: 1 },
  );
  await choose("网络");
  await ready;
  await field(prefix + ".2.host").fill("8081");
  releaseCatalogue();
  await expect(
    page.getByRole("option", { name: "测试网络（刷新）", exact: true }),
  ).toHaveCount(1);
  await expect(field(prefix + ".2.host")).toHaveValue("8081");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await section(prefix).scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "network-forwards-320.png") });
  await field(prefix + ".2.host").press("Enter");
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
    const [nic, second] = saved.vms[0].networks;
    assert.deepEqual(
      saved.vms.find((vm) => vm.id === "peer-leases"),
      parse(stringify(peer)),
    );
    assert.equal(nic.vlan_id, null);
    assert.equal(nic.dhcp4_lease.offset.toString(), "64");
    assert.equal(nic.dhcp4_lease.forwards.length, 3);
    assert.equal(nic.dhcp4_lease.forwards[0].proto, "any");
    assert.equal(nic.dhcp4_lease.forwards[0].guest, "9000-9002");
    assert.equal(
      nic.dhcp4_lease.forwards[0].extension.toString(),
      "18446744073709551615",
    );
    assert.equal(nic.dhcp4_lease.forwards[1], "future-rule");
    assert.deepEqual(nic.dhcp4_lease.forwards[2], {
      proto: "any",
      host: "8081",
      guest: "80",
    });
    assert.equal(nic.dhcp6_lease.offset.toString(), "9007199254740993");
    assert.deepEqual(nic.dhcp6_lease.forwards, [
      { proto: "udp", host: "53", guest: "53" },
    ]);
    assert.equal(second.dhcp4_lease.offset.toString(), "65");
    assert.equal(
      second.dhcp4_lease.forwards.future.toString(),
      "18446744073709551615",
    );
  } finally {
    await post("vm-configuration/release", { snapshotId: part.snapshotId });
  }
  writeFileSync(
    join(output, "network-forwards.json"),
    JSON.stringify(
      {
        passed: true,
        checks: [
          "cross-vm-offset-conflicts-from-saved-baseline",
          "first-enable-and-manual-offset-selection",
          "allocation-skips-current-draft-and-peer-assignments",
          "other-vm-assignments-preserved-on-save",
          "native-vlan-projection-and-exact-offsets",
          "vlan-dhcp-snat-availability-preserves-draft",
          "subnet-gateway-dynamic-pool-validation",
          "mac-and-known-linux-vlan-validation",
          "clear-vlan-to-trunk",
          "equal-port-ranges-and-hidden-error-focus",
          "rule-add-remove-and-error-index",
          "same-vm-lease-offset-conflict",
          "disabled-lease-preserves-rules-and-input",
          "linux-ipv6-forwards-inactive",
          "unknown-rule-and-container-preserved",
          "uint64-and-ipv6-offset-preserved",
          "http-save-readback",
          "mobile-320-no-overflow",
          "catalogue-refresh-preserves-live-input",
          "enter-commits-focused-input",
        ],
      },
      null,
      2,
    ),
  );
  console.log(
    `Native configuration network forwarding checks passed; ${output}`,
  );
}
