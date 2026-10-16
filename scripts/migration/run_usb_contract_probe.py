# SPDX-License-Identifier: GPL-3.0-or-later
"""Run the native USB passthrough host contract (droidvm_usb_contract) as root on a device.

The contract only touches a fake sysfs tree it builds in its own scratch
directory; the real /sys/bus/usb is never written.
"""
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
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--binary", type=Path)
    args = parser.parse_args()
    binary = args.binary or root / "build/migration" / ("standalone-" + args.abi) / "droidvm_usb_contract"
    nonce = uuid.uuid4().hex
    output = root / "build/migration/usb-contract-device" / nonce
    output.mkdir(parents=True)
    directory = "/data/local/tmp/droidvm-usb-contract-" + nonce
    adb = ["adb", "-s", args.serial]

    def shell(command, timeout=180):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=timeout)

    report = {"serial": args.serial, "directory": directory, "binary": str(binary),
              "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "passed": False}
    try:
        report["fingerprint"] = shell("getprop ro.build.fingerprint").stdout.strip()
        report["autoprobe_before"] = shell("cat /sys/bus/usb/drivers_autoprobe").stdout.strip()
        assert shell(f"mkdir -m 700 {directory}").returncode == 0
        subprocess.run(adb + ["push", str(binary), "/data/local/tmp/droidvm-usb-contract-" + nonce + ".bin"],
                       check=True, capture_output=True, timeout=60)
        assert shell(f"mv /data/local/tmp/droidvm-usb-contract-{nonce}.bin {directory}/contract && chmod 700 {directory}/contract").returncode == 0
        result = shell(f"cd {directory} && {directory}/contract {directory}", timeout=300)
        (output / "stdout.txt").write_text(result.stdout, encoding="utf-8")
        (output / "stderr.txt").write_text(result.stderr, encoding="utf-8")
        contracts = json.loads(result.stdout)
        report.update(exit_code=result.returncode, contracts=contracts)
        report["autoprobe_after"] = shell("cat /sys/bus/usb/drivers_autoprobe").stdout.strip()
        # The real gate must be exactly as it was: the contract owns only its tree.
        report["passed"] = (result.returncode == 0 and contracts.get("passed") is True
                            and report["autoprobe_before"] == report["autoprobe_after"])
        left = shell(f"ls {directory}").stdout.split()
        report["leftover_entries"] = [name for name in left if name != "contract"]
        if not report["passed"]:
            subprocess.run(adb + ["pull", directory, str(output / "retained-device-state")], timeout=60, check=False)
            raise RuntimeError("USB contract failed; device state retained at " + directory)
        shell(f"rm -rf {directory}")
    finally:
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
    print("PASS: native USB passthrough host contract,", len(report["contracts"].get("checks", [])), "checks")


if __name__ == "__main__":
    main()
