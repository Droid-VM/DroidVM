# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify native ownership transition against the production Java write gate."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import shlex
import subprocess
import threading
import time
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--binary", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvm_vm_config_contract")
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-ownership" / nonce
    output.mkdir(parents=True)
    package = "cn.classfun.droidvm.migration.test"
    directory = f"/data/user/0/{package}/cache/ownership-{nonce}"
    executable = f"/data/local/tmp/droidvm-ownership-{nonce}"
    adb = ["adb", "-s", args.serial]
    apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    report = {"binary_sha256": hashlib.sha256(args.binary.read_bytes()).hexdigest(),
              "daemon_sha256": hashlib.sha256(args.daemon.read_bytes()).hexdigest(),
              "test_apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "checks": []}
    java = None
    lines, transcript = queue.Queue(), []

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=40, **kwargs)

    def instrument(method):
        return ["am", "instrument", "-w", "-r", "-e", "class",
                "cn.classfun.droidvm.migration.VmOwnershipTest#" + method, "-e", "directory", directory,
                package + "/androidx.test.runner.AndroidJUnitRunner"]

    def drain():
        for line in java.stdout:
            transcript.append(line)
            lines.put(line)
        lines.put(None)

    try:
        subprocess.run(adb + ["install", "-r", "-t", str(apk)], check=True, timeout=60)
        shell(["run-as", package, "mkdir", directory], check=True)
        subprocess.run(adb + ["push", str(args.binary), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        subprocess.run(adb + ["push", str(args.daemon), executable + "-daemon"], check=True, timeout=30)
        shell(["chmod", "755", executable + "-daemon"], check=True)
        java = subprocess.Popen(adb + ["shell", shlex.join(instrument("holdLegacyWrite"))], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Java ownership fixture exited before readiness")
            if "ownership_ready=true" in line:
                break
        else:
            raise TimeoutError("Java ownership fixture readiness")
        busy = shell([executable, "--ownership", "claim", directory])
        (output / "busy.txt").write_text(busy.stdout + busy.stderr, encoding="utf-8")
        assert busy.returncode == 1 and "lock VM configuration" in busy.stderr
        assert shell(["stat", "-c", "%s", directory + "/vms.config-owner"], check=True).stdout.strip() == "0"
        report["checks"].append("Java write lock excludes native takeover without installing marker")
        shell(["touch", directory + "/release"], check=True)
        assert java.wait(timeout=20) == 0
        reader.join(timeout=2)
        assert "OK (1 test)" in "".join(transcript)
        shell([executable, "--ownership", "claim", directory], check=True)
        assert shell(["cat", directory + "/vms.json"], check=True).stdout == "legacy committed"
        report["checks"].append("native takes over only after legacy save has completed")
        legacy = shell([executable, "--ownership", "legacy", directory])
        assert legacy.returncode == 1 and "legacy startup refused" in legacy.stderr
        report["checks"].append("persistent marker rejects legacy native startup after claimant exits")
        rejected = shell(instrument("rejectsPersistentNativeOwnership"))
        (output / "java-rejected.txt").write_text(rejected.stdout + rejected.stderr, encoding="utf-8")
        assert rejected.returncode == 0 and "OK (1 test)" in rejected.stdout
        report["checks"].append("production Java gate refuses writes and rollback even with no native process")
        shell([executable, "--ownership", "claim", directory], check=True)
        assert shell(["cat", directory + "/vms.config-owner"], check=True).stdout == "native-v1\n"
        report["checks"].append("native repairs partial ownership marker left by interrupted transition")
        legacy_data = directory + "/legacy-start"
        shell(["run-as", package, "mkdir", "-p", legacy_data + "/files"], check=True)
        shell(["cp", directory + "/vms.config-owner", legacy_data + "/files/vms.config-owner"], check=True)
        refused = shell([executable + "-daemon", "--data-dir", legacy_data, "--apk", "/missing.apk", "--force"])
        (output / "legacy-main.txt").write_text(refused.stdout + refused.stderr, encoding="utf-8")
        assert refused.returncode == 1 and "legacy startup refused" in refused.stderr
        assert shell(["test", "-e", legacy_data + "/run/droidvmd.lock"]).returncode != 0
        report["checks"].append("production native legacy entry refuses before force instance takeover")
        report["passed"] = True
    except Exception as error:
        report["error"] = repr(error)
        raise
    finally:
        stopped = True
        if java is not None and java.poll() is None:
            shell(["touch", directory + "/release"])
            try:
                java.wait(timeout=20)
            except subprocess.TimeoutExpired:
                stopped = False
        (output / "java-writer.txt").write_text("".join(transcript), encoding="utf-8")
        report["cleanup_complete"] = stopped
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        if stopped:
            assert directory == f"/data/user/0/{package}/cache/ownership-{nonce}" and len(nonce) == 32
            shell(["rm", "-rf", "--", directory], check=True)
            shell(["rm", "-f", "--", executable], check=True)
            shell(["rm", "-f", "--", executable + "-daemon"], check=True)
        print("Results:", output)
    print("PASS: VM ownership transition and Java/native lock interoperability")


if __name__ == "__main__":
    main()
