# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify native network tools/ownership or helper control APIs on isolated resources."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--netbox", default="/data/user/0/cn.classfun.droidvm/bin/netbox")
    parser.add_argument("--control-tools", help="Run real gvswitch/bridgedhcp HTTP contracts using this device bin directory")
    args = parser.parse_args()
    if args.binary is None:
        target = "droidvm_control_http_contract" if args.control_tools else "droidvm_network_backend_contract"
        args.binary = root / "build/migration/standalone-arm64-v8a" / target
    nonce = uuid.uuid4().hex
    output = root / "build/migration" / ("network-control-device" if args.control_tools else "network-backend-device") / nonce
    output.mkdir(parents=True)
    directory = "/data/local/tmp/droidvm-network-backend-" + nonce
    adb = ["adb", "-s", args.serial]

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=120, **kwargs)

    report = {"serial": args.serial, "directory": directory, "binary": str(args.binary),
              "sha256": hashlib.sha256(args.binary.read_bytes()).hexdigest(), "passed": False}
    try:
        report["fingerprint"] = shell(["getprop", "ro.build.fingerprint"], check=True).stdout.strip()
        report["selinux"] = shell(["getenforce"], check=True).stdout.strip()
        report["netbox_sha256"] = shell(["sha256sum", args.netbox], check=True).stdout.strip()
        if args.control_tools:
            report["helper_sha256"] = {
                name: shell(["sha256sum", args.control_tools + "/" + name], check=True).stdout.strip()
                for name in ("netbox", "gvswitch", "bridgedhcp")
            }
        before = json.loads(shell([args.netbox, "link-list"], check=True).stdout)
        (output / "links-before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
        shell(["mkdir", "-m", "700", directory], check=True)
        subprocess.run(adb + ["push", str(args.binary), directory + "/contract"], check=True, timeout=30)
        shell(["chmod", "700", directory + "/contract"], check=True)
        result = shell([directory + "/contract", args.control_tools or args.netbox, directory])
        (output / "stdout.txt").write_text(result.stdout, encoding="utf-8")
        (output / "stderr.txt").write_text(result.stderr, encoding="utf-8")
        report["exit_code"] = result.returncode
        report["contracts"] = json.loads(result.stdout)
        after = json.loads(shell([args.netbox, "link-list"], check=True).stdout)
        (output / "links-after.json").write_text(json.dumps(after, indent=2), encoding="utf-8")
        report["same_interface_names"] = {r["ifname"] for r in before} == {r["ifname"] for r in after}
        report["passed"] = result.returncode == 0 and report["contracts"].get("passed") is True and report["same_interface_names"]
        if not report["passed"]:
            subprocess.run(adb + ["pull", directory, str(output / "retained-device-state")], timeout=30, check=False)
            raise RuntimeError("Network backend contract failed; retain journal at " + directory)
        shell(["rm", "-f", directory + "/contract"], check=True)
        shell(["rmdir", directory], check=True)
    finally:
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
    print("PASS: real helper control APIs" if args.control_tools else "PASS: real network tools, owned links and crash recovery boundaries")


if __name__ == "__main__":
    main()
