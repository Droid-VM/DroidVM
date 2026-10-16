# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare native VM runtime planning (NIC, boot plan, crosvm and QEMU argv) to the production Java code run as root."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid

PACKAGE = "cn.classfun.droidvm"
DATA = "/data/data/" + PACKAGE
LBX = DATA + "/bin/lbx"


def network(bridge_type="linux", mode="l3", vlans=None):
    value = {"schema": 2, "id": str(uuid.uuid4()), "name": "n-" + bridge_type + mode, "bridge_name": "brnic",
             "bridge_type": bridge_type, "uplink_mode": mode}
    if mode == "l3":
        value["l3"] = {"vlans": vlans if vlans is not None else []}
    else:
        value["l2"] = {"uplink": "wlan0"}
    return value


def nic_fixtures():
    vlans = [{"vlan_id": 0, "ipv4": {"cidr": "10.20.0.1/24", "snat": True, "dhcp": {"enabled": True}},
              "ipv6": {"cidr": "fd00:20::1/64", "snat": True, "dhcp": {"enabled": True}}},
             {"vlan_id": 10, "ipv4": {"cidr": "10.21.0.1/24", "snat": False, "dhcp": {"enabled": True}}},
             {"vlan_id": 20, "ipv6": {"source": "dhcp_pd", "dhcp": {"enabled": True}}},
             {"vlan_id": 30, "ipv4": {"cidr": "10.23.0.1/30", "snat": True, "dhcp": {"enabled": True}},
              "ipv6": {"cidr": "fd00:23::1/126", "dhcp": {"enabled": True}}},
             {"vlan_id": 40, "ipv4": {"snat": True, "dhcp": {"enabled": True}}}]
    networks = {"linux": network("linux", "l3", vlans), "gvisor": network("gvisor", "l3", vlans),
                "l2": network("linux", "l2"), "empty": network("linux", "l3", [])}
    mac = "02:11:22:33:44:55"
    forwards = [{"proto": "tcp", "host": "8080", "guest": "80"}, {"proto": "any", "host": " 10-12 ", "guest": "20-22"}]
    bad_forwards = [[{"proto": "sctp", "host": "1", "guest": "1"}], [{"host": "0", "guest": "1"}], [{"host": "70000", "guest": "1"}],
                    [{"host": "x", "guest": "1"}], [{"host": "5-1", "guest": "5-1"}], [{"host": "1-3", "guest": "1-2"}],
                    [{"host": "٣", "guest": "3"}], [{"host": "99999999999", "guest": "1"}], [{"proto": None, "host": "1", "guest": "1"}],
                    ["bad", {"host": "1-", "guest": "1"}], [{"host": "-5", "guest": "1"}]]
    nics = [{}, {"mac_address": mac}, {"mac_address": "bad"}, {"mac_address": "02:11:22:33:44:5G"}, {"mac_security": True},
            {"mac_security": True, "mac_address": mac}, {"mac_address": 5}, {"mac_address": ""}]
    for vlan in (None, -1, 0, 5, 10, 20, 30, 40, 4095, 4096, "5", 4294967306, 2.0):
        nics.append({"mac_address": mac, "vlan_id": vlan} if vlan is not None else {"mac_address": mac})
    for vlan in (None, 10, 20, 30, 40, 7):
        base = {"mac_address": mac} if vlan is None else {"mac_address": mac, "vlan_id": vlan}
        for lease in ({"enabled": True}, {"enabled": True, "offset": 5}, {"enabled": True, "offset": 0}, {"enabled": True, "offset": 254},
                      {"enabled": True, "offset": 255}, {"enabled": True, "offset": 2}, {"enabled": True, "offset": "5"},
                      {"enabled": "true", "offset": 5}, {"enabled": True, "offset": 5, "forwards": forwards},
                      {"enabled": False, "offset": 5, "forwards": forwards}, "bad"):
            nics.append(dict(base, dhcp4_lease=copy.deepcopy(lease)))
            nics.append(dict(base, dhcp6_lease=copy.deepcopy(lease)))
        for broken in bad_forwards:
            nics.append(dict(base, dhcp4_lease={"enabled": True, "offset": 5, "forwards": broken}))
            nics.append(dict(base, dhcp6_lease={"enabled": True, "offset": 5, "forwards": broken}))
    nics.append({"dhcp4_lease": {"enabled": True, "offset": 5}})
    nics.append({"mac_address": mac, "dhcp4_lease": {"enabled": True, "offset": 5, "forwards": {}}, "dhcp6_lease": {"enabled": True, "offset": 9223372036854775807}})
    tests = []
    for name, net in networks.items():
        for index, nic in enumerate(nics):
            tests.append({"name": f"nic_{name}_{index}", "op": "nic", "network": net, "nic": nic})
    return tests


def boot_fixtures():
    edk2 = DATA + "/usr/share/droidvm/edk2-gunyah.fd"
    configs = [{}, {"boot": {"protocol": "uefi"}}, {"boot": {"protocol": "UEFI", "uefi": {"firmware": "/fw", "vars": "/v", "vars_enabled": False}}},
               {"boot": {"protocol": "uefi", "uefi": "bad"}}, {"boot": {"protocol": "linux"}},
               {"boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": "/k", "initrd": "/i", "cmdline": "c"}}},
               {"boot": {"protocol": "linux", "linux": {"kernel": edk2}}}, {"boot": {"protocol": "Linux", "linux": {"source": "MANUAL", "kernel": 5}}},
               {"boot": {"protocol": "bogus"}}, {"boot": "bad"}, {"kernel": "/legacy", "use_uefi": False, "cmdline": "x"},
               {"boot": {"protocol": "linux", "linux": {"source": "image"}}},
               {"boot": {"protocol": "linux", "linux": {"source": "image", "image": {"disk": 3}}}, "disks": [{"path": ""}, "bad", {"path": "/data/local/tmp/droidvm-missing-image.img"}]},
               {"boot": {"protocol": "linux", "linux": {"source": "image", "image": {"cmdline": "override"}}}}]
    tests = []
    for index, config in enumerate(configs):
        config = dict(config, id=str(uuid.uuid4()), name=f"boot {index}")
        for override in (None, "@droidvm:builtin-kernel", "entry-x"):
            tests.append({"name": f"boot_{index}_{override}", "op": "boot", "config": config, "override": override})
    builtin = {"id": str(uuid.uuid4()), "name": "builtin", "disks": [{"path": "/data/local/tmp/droidvm-missing-image.img"}],
               "boot": {"protocol": "linux", "linux": {"image": {"cmdline": "root=/dev/vdb", "disk": 0}}}}
    tests.append({"name": "boot_builtin_override_cmdline", "op": "boot", "config": builtin, "override": "@droidvm:builtin-kernel"})
    return tests


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--suite", choices=("nic", "boot", "crosvm", "qemu", "all"), default="all")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-oracle-device" / nonce
    output.mkdir(parents=True)
    directory = f"/data/local/tmp/droidvm-vm-oracle-{nonce}"
    adb = ["adb", "-s", args.serial]
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    probe = root / "build/migration/standalone-arm64-v8a/droidvm_config_probe"
    report = {"apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "native_sha256": hashlib.sha256(probe.read_bytes()).hexdigest()}

    def shell(command, timeout=180):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)

    samples = []
    if args.suite in ("nic", "all"):
        samples += nic_fixtures()
    if args.suite in ("boot", "all"):
        samples += boot_fixtures()
    if args.suite in ("crosvm", "all"):
        import vm_crosvm_fixtures
        samples += vm_crosvm_fixtures.fixtures()
    if args.suite in ("qemu", "all"):
        import vm_qemu_fixtures
        samples += vm_qemu_fixtures.fixtures()
    (output / "fixtures.json").write_text(json.dumps(samples, ensure_ascii=False, indent=1), encoding="utf-8")
    passed = False
    # The Java code raises udmabuf limits and caches the app groups as the legacy
    # daemon does on a VM start; put both back afterwards.
    saved = shell("for p in /sys/module/udmabuf*/parameters/size_limit_mb; do [ -e $p ] && echo $p=$(cat $p); done; "
                  f"cat {DATA}/run/app-gids 2>/dev/null").stdout.splitlines()
    try:
        assert shell(f"mkdir {directory}").returncode == 0
        for local, name in ((apk, "app.apk"), (test_apk, "test.apk"), (probe, "probe"), (output / "fixtures.json", "fixtures.json")):
            subprocess.run(adb + ["push", str(local), directory + "/" + name], check=True, capture_output=True, timeout=180)
        java = shell(f"cd {directory} && CLASSPATH={directory}/app.apk:{directory}/test.apk app_process64 / "
                     f"cn.classfun.droidvm.migration.RootOracle {directory}/fixtures.json {directory}/java.json", timeout=300)
        (output / "oracle.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
        assert java.returncode == 0, java.stdout + java.stderr
        java_rows = json.loads(shell(f"cat {directory}/java.json").stdout)
        native = shell(f"chmod 700 {directory}/probe && {directory}/probe {directory}/fixtures.json")
        assert native.returncode == 0, native.stderr
        native_rows = json.loads(native.stdout)
        for name, rows in (("java.json", java_rows), ("native.json", native_rows)):
            (output / name).write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
        assert len(java_rows) == len(native_rows) == len(samples)
        differences = []
        for sample, old, new in zip(samples, java_rows, native_rows):
            if old["status"] != new["status"]:
                differences.append({"name": sample["name"], "java": old, "native": new})
                continue
            if old["status"] == "ok":
                strip = {k: v for k, v in old.items() if k not in ("name", "status")}
                if strip != {k: v for k, v in new.items() if k not in ("name", "status")}:
                    differences.append({"name": sample["name"], "java": old, "native": new})
        report.update(cases=len(samples), differences=differences[:50], difference_count=len(differences),
                      java_errors=sum(1 for row in java_rows if row["status"] == "error"))
        passed = not differences
        assert passed, json.dumps(differences[:5], ensure_ascii=False, indent=1)
    finally:
        for line in saved:
            if line.startswith("/sys/") and "=" in line:
                path, value = line.split("=", 1)
                shell(f"echo {shlex.quote(value)} > {shlex.quote(path)}")
        gids = [line for line in saved if not line.startswith("/sys/")]
        if gids:
            shell(f"printf %s {shlex.quote(gids[0])} > {DATA}/run/app-gids")
        report["restored"] = saved
        report["passed"] = passed
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        print("Results:", output)
        if passed:
            shell(f"rm -r {directory}")
    print("PASS: native VM planning matches the production Java code run as root,", report["cases"], "cases")


if __name__ == "__main__":
    main()
