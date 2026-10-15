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
    return tests


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
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
        samples = fixtures()
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
        command = ["am", "instrument", "-w", "-r", "-e", "class", "cn.classfun.droidvm.migration.NetworkConfigOracleTest",
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
            if old["status"] == "ok" and compatible:
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
