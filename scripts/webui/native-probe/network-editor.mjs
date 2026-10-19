import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

export async function verifyNetworkEditor({
  page,
  post,
  output,
  expect,
  require,
}) {
  const { parse, stringify } = require("lossless-json");
  const read = async () => {
    let page = await post("network-configuration/read", {
      snapshotId: null,
      offset: 0,
    });
    const id = page.snapshotId,
      chunks = [];
    try {
      for (;;) {
        for (const row of page.items)
          if (row.type === "chunk")
            chunks.push(Buffer.from(row.data, "base64"));
        if (page.next === null) break;
        page = await post("network-configuration/read", {
          snapshotId: id,
          offset: page.next,
        });
      }
      return parse(Buffer.concat(chunks).toString("utf8")).networks;
    } finally {
      await post("network-configuration/release", { snapshotId: id });
    }
  };
  const button = (name) => page.getByRole("button", { name, exact: true });
  const field = (path) => page.locator(`[id="network-${path}"]`);
  const saved = () =>
    page.getByText("保存成功，已重新读取当前网络配置。", { exact: true });
  const commits = [];
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      request.url().endsWith("/network-configuration/commit")
    )
      commits.push(request.postDataJSON());
  });
  const initial = (await read())[0];
  await page.goto(new URL("/#/resources/network", page.url()).href);
  await button("编辑网络").click();
  await expect(field("name")).toHaveValue("测试网络");
  await field("bridge_name").fill("webnet0");
  await field("name").fill("浏览器网络编辑");
  await field("l3.vlans.0.ipv6.dhcp.offset_end").fill("9007199254740996");
  await field("uplink_mode").selectOption("l2");
  await field("l2.uplink").fill("WiFi");
  await field("l2.pseudo_bridge").check();
  await field("bridge_type").selectOption("gvisor");
  await expect(
    page.getByText("L2 桥接仅支持 Linux 网桥。", { exact: true }),
  ).toBeVisible();
  await expect(button("保存配置")).toBeDisabled();
  await field("bridge_type").selectOption("linux");
  await field("uplink_mode").selectOption("l3");
  await expect(field("l3.vlans.0.ipv6.dhcp.offset_end")).toHaveValue(
    "9007199254740996",
  );
  await button("添加 VLAN").click();
  await expect(page.locator("[data-vlan]")).toHaveCount(4);
  await field("l3.vlans.3.vlan_id").fill("100");
  await field("l3.vlans.3.dns_servers").fill("1.1.1.1\n2001:4860:4860::8888");
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    true,
  );
  await page.screenshot({ path: join(output, "network-editor-320.png") });
  await button("保存配置").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "确认", exact: true })
    .click();
  await expect(saved()).toBeVisible({ timeout: 12000 });
  const edited = (await read()).find((row) => row.id === "fixture-network");
  assert.equal(edited.name, "浏览器网络编辑");
  assert.equal(
    edited.extension.counter.toString(),
    initial.extension.counter.toString(),
  );
  assert.equal(
    edited.l3.vlans[0].ipv6.dhcp.offset_end.toString(),
    "9007199254740996",
  );
  assert.equal(stringify(edited.l3.vlans[1]), stringify(initial.l3.vlans[1]));
  assert.equal(edited.l2.uplink, "WiFi");
  assert.deepEqual(edited.l3.vlans[3].dns_servers, [
    "1.1.1.1",
    "2001:4860:4860::8888",
  ]);
  await field("name").fill("丢回执后核实");
  await page.route("**/api/v1/network-configuration/commit", async (route) => {
    await route.fetch();
    await route.abort();
  });
  await button("保存配置").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "确认", exact: true })
    .click();
  await expect(saved()).toBeVisible({ timeout: 12000 });
  await page.unroute("**/api/v1/network-configuration/commit");
  assert.equal(commits.length, 2);
  assert.equal(
    (await read()).find((row) => row.id === "fixture-network").name,
    "丢回执后核实",
  );
  await button("关闭").click();
  await button("新建网络").click();
  await expect(field("name")).toHaveValue("net0");
  await field("name").fill("新增路由网络");
  await expect(field("l3.vlans.0.ipv6.dhcp.enabled")).not.toBeChecked();
  await field("l3.vlans.0.ipv6.source").selectOption("dhcp_pd");
  await expect(field("l3.vlans.0.ipv6.pd.uplink")).toHaveValue("WiFi");
  await field("l3.vlans.0.ipv6.pd.duid").fill("01:02:ff");
  await field("l3.vlans.0.ipv6.source").selectOption("static");
  await button("保存配置").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "确认", exact: true })
    .click();
  await expect(saved()).toBeVisible({ timeout: 12000 });
  const created = (await read()).find((row) => row.name === "新增路由网络");
  assert.match(
    created.id,
    /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/,
  );
  assert.equal(created.l3.vlans[0].ipv6.pd.duid, "01:02:ff");
  assert.equal(commits.length, 3);
  await field("name").fill("保留未保存的草稿");
  await button("关闭").click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "确认", exact: true })
    .click();
  await button("继续网络草稿 / 核实保存").click();
  await expect(field("name")).toHaveValue("保留未保存的草稿");
  assert.equal(commits.length, 3);
  await field("name").scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(output, "network-create-320.png") });
  writeFileSync(
    join(output, "network-editor.json"),
    JSON.stringify(
      {
        passed: true,
        viewport: 320,
        commits: commits.length,
        cases: [
          "network edit and VLAN addition",
          "L2/gVisor validation",
          "inactive parameters and unknown uint64 preserved",
          "exact DHCPv6 offsets",
          "HTTP save and readback",
          "lost receipt queried without resubmission",
          "App preset network creation and PD field preservation",
          "leave and resume draft",
          "mobile layout",
        ],
        scope:
          "Real HTTP, snapshots and tasks; in-memory configuration persistence substitute. No host network or file mutation.",
      },
      null,
      2,
    ),
  );
}
