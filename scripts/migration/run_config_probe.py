# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare native configuration semantics to the actual Java classes in an APK."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid


def fixtures():
    base = {"schema": 2, "id": "12345678-1234-1234-ABCD-123456789ABC", "name": "网络🌏",
            "bridge_name": "brtest", "bridge_type": "linux", "uplink_mode": "l3",
            "unknown": {"integer": 9223372036854775807, "negative": -9223372036854775808,
                        "exact": 9007199254740993, "integral_float": 256.0, "fraction": 0.25,
                        "float_signed_boundary": 9223372036854775808.0,
                        "null": None, "array": [], "object": {}, "text": "中文\n\"\\"},
            "l3": {"vlans": [{"vlan_id": 0, "ipv4": {"cidr": "10.181.7.1/24", "snat": True,
                      "dhcp": {"enabled": True, "offset_start": 128, "offset_end": 192}},
                      "ipv6": {"cidr": "fd00::1/64", "slaac": {"enabled": True}}}]}}
    tests = [{"name": "current_unknown_unicode_int64", "op": "validate", "config": base}]

    def case(name, path, value, config=base, op="validate", remove=False):
        changed = copy.deepcopy(config)
        target = changed
        for key in path[:-1]:
            target = target[key]
        if remove:
            del target[path[-1]]
        else:
            target[path[-1]] = value
        tests.append({"name": name, "op": op, "config": changed})

    for key, values in {
        "schema": ["2", "2.9", 2.9, None, False, 3, 0, -1, 4294967298],
        "id": ["1-2-3-4-5", "000000001-2-3-4-5", "bad", None, "", "+1-2-3-4-5", "１２３４５６７８-１２３４-１２３４-ＡＢＣＤ-１２３４５６７８９ＡＢＣ"],
        "name": [None, "", " \t\r\n", 12, "名称", "\u0001\u001f"],
        "bridge_name": [None, "", "0bad", "name space", "brabcdefghijk", "br_012345678"],
        "bridge_type": [None, "gvisor", "future"], "uplink_mode": [None, "l2", "none", "future"],
        "l3": [None, False, [], {}], "stp": [None, "true", True], "auto_up": ["true", True]
    }.items():
        for i, value in enumerate(values):
            case(f"{key}_{i}", [key], value)
        if key in base:
            case(key + "_missing", [key], None, remove=True)
    vlan = ["l3", "vlans", 0]
    for i, value in enumerate([None, "1", 1.0, -1, 4094, 4095, 4294967296, 9223372036854775807]):
        case(f"vlan_id_{i}", vlan + ["vlan_id"], value)
    for i, value in enumerate([None, [], {}, [None, "ignored", {}], [{"vlan_id": 0}, {"vlan_id": 0}]]):
        case(f"vlans_{i}", ["l3", "vlans"], value)
    for family in ("ipv4", "ipv6"):
        for i, value in enumerate([None, {}, False, "ignored"]):
            case(f"{family}_section_{i}", vlan + [family], value)
    for i, cidr in enumerate([None, "", "bad", "10.1.2.0/24", "10.1.2.255/24", "10.1.2.1/31",
                              "010.001.002.001/+24", "10.1.2.1/0", "10.1.2.1/-1", "10.1.2.1/33", "٠١٠.١.٢.١/٢٤"]):
        case(f"ipv4_cidr_{i}", vlan + ["ipv4", "cidr"], cidr)
    for i, cidr in enumerate([None, "", "bad:ip", "fd00::1/127", "fd00::1/56", "[fd00::1]/64",
                              "fd00::1%wlan0/64", "::ffff:192.0.2.1/64", "::/0", "::1/129"]):
        case(f"ipv6_cidr_{i}", vlan + ["ipv6", "cidr"], cidr)
    for i, value in enumerate([0, -1, 1, 255, 9223372036854775807, None, "256", 128.0, 256.0, -0.0]):
        case(f"dhcp_start_{i}", vlan + ["ipv4", "dhcp", "offset_start"], value)
        case(f"dhcp_end_{i}", vlan + ["ipv4", "dhcp", "offset_end"], value)
    for i, value in enumerate([None, "bad", "02:11:22:33:44:55", ""]):
        case(f"mac_{i}", ["l3", "mac_address"], value)
    for i, value in enumerate([None, [], [1, "", "192.0.2.1/32"], ["bad"]]):
        case(f"secondary4_{i}", vlan + ["ipv4_secondary"], value)
    pd = copy.deepcopy(base)
    pd["l3"]["vlans"][0]["ipv6"] = {"source": "dhcp_pd", "pd": {"uplink": "WiFi", "route_table": 9007199254740993},
                                    "dhcp": {"enabled": True, "offset_start": 128, "offset_end": 192}}
    tests.append({"name": "pd", "op": "validate", "config": pd})
    case("pd_gvisor", ["bridge_type"], "gvisor", pd)
    case("pd_no_uplink", vlan + ["ipv6", "pd", "uplink"], "", pd)
    case("pd_bad_duid", vlan + ["ipv6", "pd", "duid"], "a:bb", pd)
    case("pd_good_duid", vlan + ["ipv6", "pd", "duid"], "00:01:AF", pd)
    case("pd_bad_offsets", vlan + ["ipv6", "dhcp", "offset_end"], -1, pd)
    l2 = copy.deepcopy(base)
    l2.update(uplink_mode="l2", l2={"uplink": "Ethernet", "pseudo_bridge": True})
    tests.append({"name": "l2", "op": "validate", "config": l2})
    case("l2_gvisor", ["bridge_type"], "gvisor", l2)
    case("l2_no_uplink", ["l2", "uplink"], None, l2)
    legacy = {"id": base["id"], "name": base["name"], "bridge_name": "legacy0", "stp": True, "auto_up": True,
              "mac_address": "02:00:00:00:00:01", "ipv4_addresses": [None, "", "10.1.2.1/24", 12, "192.0.2.1/24"],
              "ipv6_addresses": ["fd00::1/64", "fd01::1/64"], "nat": True, "dhcp_enabled": True,
              "dhcp_range_start": "10.1.2.100", "dhcp_range_end": "10.1.2.200",
              "future_extension": {"exact": 9007199254740993, "values": [None, [], {}, "未识别"]}}
    tests.append({"name": "legacy", "op": "migrate", "config": legacy})
    for key, values in {"schema": [None, -1, 1, 2, 3, "0"], "ipv4_addresses": [None, [], ["bad"], ["10.1.2.1/30"]],
                        "dhcp_range_start": ["bad", "10.0.0.1"], "dhcp_range_end": ["bad", "10.1.3.1"]}.items():
        for i, value in enumerate(values):
            case(f"legacy_{key}_{i}", [key], value, legacy, "migrate")
    for value in (0, 1, 4094):
        tests.append({"name": f"default_vlan_{value}", "op": "default_vlan", "vlan_id": value})
    helper = copy.deepcopy(base)
    helper["bridge_type"] = "gvisor"
    helper["l3"]["mac_address"] = "02:00:00:12:34:56"
    for families in range(4):
        for flags in range(16):
            changed = copy.deepcopy(helper)
            entry = changed["l3"]["vlans"][0]
            entry["ipv4"]["cidr"] = "192.0.2.17/24" if families & 1 else None
            entry["ipv6"]["cidr"] = "2001:db8:1234:5678::abcd/64" if families & 2 else None
            entry["ipv4"]["snat"] = bool(flags & 1)
            entry["ipv6"]["snat"] = bool(flags & 2)
            entry["ipv4"]["dhcp"]["enabled"] = bool(flags & 4)
            entry["ipv6"]["dhcp"] = {"enabled": bool(flags & 8)}
            tests.append({"name": f"gvswitch_families_{families}_flags_{flags}", "op": "gvswitch", "config": changed})
    for index, entries in enumerate([
        [], None, [None, "ignored", {}],
        [{"vlan_id": 0}],
        [{"vlan_id": 4094, "ipv4": {"cidr": "192.0.2.1/24", "snat": True},
          "ipv6": {"cidr": "[2001:db8::42]/64", "dhcp": {"enabled": True, "offset_start": 1, "offset_end": 9223372036854775807}},
          "dns_servers": ["", None, "1.1.1.1", "2001:4860:4860::8888"]}],
        [{"vlan_id": 0, "ipv4": {"cidr": "+192.000.2.01/+24"}}, {"vlan_id": 257, "ipv6": {"cidr": "fe80::1%none/64", "slaac": {"enabled": True}}}],
        [{"ipv6": {"cidr": "fd00::1/126", "dhcp": {"enabled": True}}}],
        [{"ipv6": {"cidr": "fd00::1/126", "dhcp": {"enabled": True, "offset_start": 1, "offset_end": 3}}}],
        [{"ipv6": {"cidr": "fd00::1/0", "snat": True, "dhcp": {"enabled": True}}}],
        [{"ipv4": {"cidr": "192.0.2.1/0", "snat": True, "dhcp": {"enabled": True, "offset_start": 1, "offset_end": 4294967294}}}],
    ]):
        changed = copy.deepcopy(helper)
        changed["l3"]["vlans"] = entries
        tests.append({"name": f"gvswitch_special_{index}", "op": "gvswitch", "config": changed})
    return tests


EDK2 = "/data/data/cn.classfun.droidvm/usr/share/droidvm/edk2-gunyah.fd"


def vm_fixtures():
    base = {"id": "12345678-1234-1234-ABCD-123456789ABC", "name": "虚拟机🌏", "created_at": 1760000000000,
            "backend": "crosvm", "memory_mb": 512, "cpu_count": 1,
            "unknown": {"integer": 9223372036854775807, "negative": -9223372036854775808, "exact": 9007199254740993,
                        "integral_float": 256.0, "fraction": 0.25, "null": None, "array": [], "object": {}, "text": "中文\n\"\\"},
            "networks": [{"network_id": "net-a", "mac_address": "02:00:00:00:00:01"}]}
    tests = [{"name": "plain_unknown_unicode_int64", "op": "vm", "config": base}]

    def case(name, **fields):
        changed = copy.deepcopy(base)
        for key, value in fields.items():
            if value is ...:
                changed.pop(key, None)
            else:
                changed[key] = value
        tests.append({"name": name, "op": "vm", "config": changed})

    # EDK2 kernel and flat boot keys.
    case("edk2_kernel", kernel=EDK2)
    case("edk2_kernel_with_uefi_key", kernel=EDK2, use_uefi=False)
    case("edk2_kernel_null_uefi", kernel=EDK2, use_uefi=None)
    for index, value in enumerate([True, False, None, "yes", 0]):
        for kernel in ("", "/k", 7):
            for bios in ("", "/fw.fd", None):
                case(f"flat_boot_{index}_{kernel!r}_{bios!r}", use_uefi=value, kernel=kernel, bios=bios,
                     initrd="/i" if bios else "", cmdline="console=ttyS0" if kernel else 5)
    for index, boot in enumerate([None, {}, "text", {"protocol": "linux"}]):
        case(f"boot_present_{index}", boot=boot, kernel="/k", use_uefi=False)
    case("flat_only_cmdline", cmdline="quiet")
    case("flat_only_null_kernel", kernel=None)
    # VM-level port forwards fold onto the first matching NIC.
    nics = [{"network_id": "net-a", "dhcp4_lease": {"enabled": False, "offset": 70, "forwards": [{"proto": "udp", "host": "1", "guest": "2"}]}},
            {"network_id": "net-a"}, {"network_id": "net-b", "dhcp4_lease": "bad"}, {"network_id": ""}, "nic", None,
            {"network_id": "net-c", "dhcp4_lease": {"forwards": {}}}]
    forwards = [{"network_id": "net-a", "protocol": "tcp", "host_port": 8080, "guest_port": 80},
                {"network_id": "net-a", "enabled": False, "host_port": 1, "guest_port": 1},
                {"network_id": "net-a", "enabled": "no", "protocol": "", "host_port": "22", "guest_port": 22.0},
                {"network_id": "net-b", "protocol": 7, "host_port": 9223372036854775807, "guest_port": -1},
                {"network_id": "net-c", "host_port": 53, "guest_port": 53, "protocol": "udp", "host_ip": "0.0.0.0"},
                {"network_id": "missing", "host_port": 2, "guest_port": 2}, "bad", None, {"host_port": 3}]
    case("forwards_full", networks=nics, port_forwards=forwards)
    case("forwards_no_networks", networks=..., port_forwards=forwards)
    case("forwards_networks_object", networks={}, port_forwards=forwards)
    case("forwards_empty", networks=nics, port_forwards=[])
    case("forwards_object", networks=nics, port_forwards={"a": 1})
    case("forwards_null", networks=nics, port_forwards=None)
    # Legacy display bindings and geometry.
    for backend in ("virtio_gpu", "Virtio_Gpu", "simplefb", "SIMPLEFB", "none", "bogus", 3, None, ...):
        for flags in range(16):
            fields = {"display_enabled": bool(flags & 1), "native_display_enabled": bool(flags & 2),
                      "vnc_enabled": bool(flags & 4), "gpu_enabled": bool(flags & 8), "display_backend": backend}
            if flags == 5:
                fields.update(vnc_host="0.0.0.0", vnc_port=5901, vnc_password_auth=True, vnc_password="密码")
            if flags == 6:
                fields.update(vnc_host=5, vnc_port="5901", vnc_password_auth="yes", vnc_password=None)
            case(f"display_{backend!r}_{flags}", **fields)
    case("display_flags_non_boolean", display_enabled="true", display_backend="virtio_gpu", vnc_enabled=1, gpu_enabled="true")
    case("geometry_full", display_width=1920, display_height=1080, display_refresh_rate=60, display_dpi_h=320, display_dpi_v=240)
    case("geometry_partial_bad_types", display_width="1920", display_dpi_v=1.5)
    case("geometry_null_only", display_refresh_rate=None)
    screens = {"gpu-0": {"enabled": True, "exporter": "native"}, "simplefb": {"enabled": False}}
    case("screens_existing_with_geometry", screens=screens, display_width=800, gpu_enabled=False, display_enabled=True)
    case("screens_existing_gpu_accelerated", screens=screens, gpu_enabled=True)
    case("screens_existing_gpu_disabled_screen", screens={"gpu-0": {"enabled": "true"}}, gpu_enabled=False)
    case("screens_existing_gpu_null", screens=screens, gpu_enabled=None)
    case("screens_non_object_geometry", screens=7, display_height=600, gpu_enabled=False)
    case("screens_null", screens=None, display_width=640)
    case("screens_entry_bad", screens={"gpu-0": 5, "simplefb": None}, display_width=640, gpu_enabled=False)
    case("gpu_enabled_pre_screens_vnc", vnc_enabled=True, gpu_enabled=False)
    # Serial defaults.
    for index, ports in enumerate([..., None, {}, [], [None, 5, "x"],
                                   [{"hardware": "serial", "num": 1, "backend": "file", "path": "/p"}],
                                   [{"hardware": "sbsa", "num": 1, "console": True}],
                                   [{"hardware": "SERIAL", "num": 3}, {"num": 2, "console": "true"}],
                                   [{"hardware": "serıal", "num": 4}, {"hardware": "bogus", "num": 0}],
                                   [{"num": 4294967298}, {"num": 5}, {"num": -1}, {"num": "2"}, {"num": 2.0}],
                                   [{"hardware": "virtio_console", "num": 1, "backend": "app_console"},
                                    {"hardware": "serial", "num": 2, "backend": "APP_CONSOLE"},
                                    {"hardware": "serial", "num": 2, "backend": "app_console"}]]):
        case(f"serial_{index}", serial_ports=ports)
    # xHCI controller.
    for index, (usb, nxt, peripherals) in enumerate([
        (True, ..., ...), (False, ..., ...), ("true", ..., ...), (True, -1, ...), (True, 0, ...), (True, 5, ...), (True, "3", ...),
        (True, ..., None), (True, ..., {}), (True, ..., [{"type": "virtio_sound"}, 5, None]),
        (True, 2, [{"type": "xhci_usb"}]), (False, ..., [{"type": "XHCI_USB", "id": ""}, {"type": "xhci_usb", "id": "xhci-7"}]),
        (..., 1, [{"type": "xhci_usb", "id": "xhci-+3"}, {"type": "xhci_usb"}]),
        (..., ..., [{"type": "xhci_usb", "id": "xhci-٣"}, {"type": "xhci_usb", "id": 5}]),
        (..., ..., [{"type": "xhci_usb", "id": "xhci-9223372036854775807"}, {"type": "xhci_usb"}]),
        (True, -5, ...), (True, 9223372036854775807, [{"type": "xhci_usb", "id": "x"}]),
    ]):
        case(f"xhci_{index}", usb=usb, xhci_next=nxt, peripherals=peripherals)
    # Everything at once from an old config, and an already-current config.
    case("all_legacy", kernel="/k", use_uefi=False, cmdline="c", networks=nics, port_forwards=forwards,
         display_enabled=True, display_backend="virtio_gpu", vnc_enabled=True, gpu_enabled=False,
         display_width=1024, usb=True)
    return tests


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--suite", choices=("network", "vm"), default="network")
    args = parser.parse_args()
    vm = args.suite == "vm"
    nonce = uuid.uuid4().hex
    output = root / "build/migration/config-device" / nonce
    output.mkdir(parents=True)
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    binary = root / "build/migration/standalone-arm64-v8a/droidvm_config_probe"
    test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    app = "cn.classfun.droidvm.migration.test"
    directory = f"/data/user/0/{app}/cache/config-{nonce}"
    native_dir = f"/data/local/tmp/droidvm-config-{nonce}"
    adb = ["adb", "-s", args.serial]
    report = {"apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
              "native_sha256": hashlib.sha256(binary.read_bytes()).hexdigest()}

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=90, **kwargs)

    try:
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["run-as", app, "mkdir", directory], check=True)
        shell(["mkdir", native_dir], check=True)
        samples = vm_fixtures() if vm else fixtures()
        fixture_file = output / "fixtures.json"
        fixture_file.write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")
        for local, name in ((apk, "oracle.apk"), (binary, "probe"), (fixture_file, "fixtures.json")):
            subprocess.run(adb + ["push", str(local), native_dir + "/" + name], check=True, timeout=120)
        shell(["chmod", "755", native_dir + "/probe"], check=True)
        uid = shell(["stat", "-c", "%u", directory], check=True).stdout.strip()
        for name in ("oracle.apk", "fixtures.json"):
            shell(["cp", native_dir + "/" + name, directory + "/" + name], check=True)
            shell(["chown", uid + ":" + uid, directory + "/" + name], check=True)
            shell(["chmod", "444", directory + "/" + name], check=True)
        oracle = "VmConfigOracleTest" if vm else "NetworkConfigOracleTest"
        command = ["am", "instrument", "-w", "-r", "-e", "class", "cn.classfun.droidvm.migration." + oracle,
                   "-e", "oracle_apk", directory + "/oracle.apk", "-e", "fixtures", directory + "/fixtures.json",
                   "-e", "report", directory + "/java.json", app + "/androidx.test.runner.AndroidJUnitRunner"]
        result = shell(command)
        (output / "instrumentation.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        assert result.returncode == 0 and "OK (1 test)" in result.stdout, result.stdout + result.stderr
        java = json.loads(shell(["cat", directory + "/java.json"], check=True).stdout)
        result = shell([native_dir + "/probe", native_dir + "/fixtures.json"], check=True)
        native = json.loads(result.stdout)
        for name, data in (("java.json", java), ("native.json", native)):
            (output / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        differences = []

        def contains(actual, expected):
            if isinstance(expected, dict):
                return isinstance(actual, dict) and all(key in actual and contains(actual[key], val) for key, val in expected.items())
            if isinstance(expected, list):
                return isinstance(actual, list) and len(actual) == len(expected) and all(contains(a, b) for a, b in zip(actual, expected))
            return type(actual) is type(expected) and actual == expected

        assert len(java) == len(native) == len(samples)
        for sample, old, new in zip(samples, java, native):
            compatible = old["status"] == new["status"]
            if vm:
                # Exact: every field, value and JSON type of both loads.
                compatible = compatible and (old["status"] != "ok" or (contains(new, old) and contains(old, new)))
            elif old["status"] == "ok" and compatible:
                # Legacy migration now keeps unknown/old fields instead of discarding
                # them. Every Java-produced field and effective value must match.
                compatible = contains(new, old) and (sample["op"] == "migrate" or contains(old, new))
                if sample["op"] == "migrate" and "future_extension" in sample["config"]:
                    compatible &= new["config"].get("future_extension") == sample["config"]["future_extension"]
            elif old["status"] == "error" and compatible and sample["name"] not in ("id_2", "id_3", "id_4", "id_missing"):
                compatible = old.get("message") == new.get("message")
            if not compatible:
                differences.append({"name": sample["name"], "java": old, "native": new})
        report.update(cases=len(samples), differences=differences, passed=not differences)
        assert not differences, json.dumps(differences, ensure_ascii=False, indent=2)
    finally:
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            shell(["rm", "-f", directory + "/oracle.apk", directory + "/fixtures.json", directory + "/java.json"], check=True)
            # ART may compile/profile the dynamically loaded APK beside it.
            shell(["rm", "-f", directory + "/oat/arm64/oracle.odex", directory + "/oat/arm64/oracle.vdex",
                   directory + "/oat/oracle.apk.cur.prof"], check=True)
            shell(["rmdir", directory + "/oat/arm64", directory + "/oat"])
            shell(["rmdir", directory], check=True)
            shell(["rm", "-f", native_dir + "/oracle.apk", native_dir + "/fixtures.json", native_dir + "/probe"], check=True)
            shell(["rmdir", native_dir], check=True)
    print("PASS: actual Java APK and native configuration semantics", report["cases"], "cases")


if __name__ == "__main__":
    main()
