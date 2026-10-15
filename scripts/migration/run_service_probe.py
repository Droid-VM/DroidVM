# SPDX-License-Identifier: GPL-3.0-or-later
"""Run standalone native service contracts without sockets, APKs or production resources."""
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
    parser.add_argument("--binary", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvm_task_contract")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/service-device" / nonce
    output.mkdir(parents=True)
    directory = "/data/local/tmp/droidvm-service-" + nonce
    executable = directory + "/contract"
    adb = ["adb", "-s", args.serial]

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=60, **kwargs)

    report = {"binary": str(args.binary), "sha256": hashlib.sha256(args.binary.read_bytes()).hexdigest(),
              "serial": args.serial, "fingerprint": shell(["getprop", "ro.build.fingerprint"], check=True).stdout.strip()}
    try:
        shell(["mkdir", directory], check=True)
        subprocess.run(adb + ["push", str(args.binary), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        result = shell(["timeout", "45", executable])
        (output / "stdout.txt").write_text(result.stdout, encoding="utf-8")
        (output / "stderr.txt").write_text(result.stderr, encoding="utf-8")
        report["exit_code"] = result.returncode
        report["contracts"] = json.loads(result.stdout)
        report["passed"] = result.returncode == 0 and report["contracts"].get("passed") is True
        if not report["passed"]:
            raise RuntimeError("Native service contracts failed; see " + str(output))
    finally:
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        shell(["rm", "-f", executable])
        shell(["rmdir", directory])
        print("Results:", output)
    print("PASS: standalone native service contracts")


if __name__ == "__main__":
    main()
