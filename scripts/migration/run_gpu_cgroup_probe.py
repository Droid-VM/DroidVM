# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare the crosvm GPU cpuset natively (prepareGpuCgroup) under a test-only cpuset path."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    adb = ["adb", "-s", args.serial]
    output = root / "build/migration/gpu-cgroup-device" / nonce
    output.mkdir(parents=True)
    remote = f"/data/local/tmp/droidvm-gpucg-{nonce}"
    test_path = f"/dev/cpuset/droidvm-test-{nonce[:8]}"
    nested = f"/dev/cpuset/droidvm-test-{nonce[:8]}n/inner"
    report = {"checks": []}

    def root_shell(command):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, capture_output=True, timeout=60)

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    gpu = {"gpu-0": {"enabled": True}}
    fixtures = [
        {"name": "disabled", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": False, "screens": gpu, "gpu_cgroup_cpus": "0-1", "gpu_cgroup_path": test_path}},
        {"name": "no gpu device", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": {"gpu-0": {"enabled": False}}, "gpu_cgroup_cpus": "0-1", "gpu_cgroup_path": test_path}},
        {"name": "relative", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": gpu, "gpu_cgroup_cpus": "0-1", "gpu_cgroup_path": " cpuset/x "}},
        {"name": "no cpus", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": gpu, "gpu_cgroup_cpus": "  ", "gpu_cgroup_path": test_path}},
        {"name": "valid", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": gpu, "gpu_cgroup_cpus": " 0-1 ", "gpu_cgroup_path": " " + test_path + " "}},
        {"name": "nested", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": gpu, "gpu_cgroup_cpus": "2", "gpu_cgroup_path": nested}},
        {"name": "bad cpus", "op": "gpu_cgroup", "config": {"gpu_cgroup_enabled": True, "screens": gpu, "gpu_cgroup_cpus": "999", "gpu_cgroup_path": test_path + "b"}},
    ]
    local = output / "fixtures.json"
    local.write_text(json.dumps(fixtures), encoding="utf-8")
    try:
        assert root_shell(f"mkdir {remote}").returncode == 0
        for path, name in ((local, "fixtures.json"), (root / "build/migration/standalone-arm64-v8a/droidvm_config_probe", "probe")):
            subprocess.run(adb + ["push", str(path), f"{remote}/{name}"], check=True, timeout=120, capture_output=True)
        root_shell(f"chmod 755 {remote}/probe")
        result = root_shell(f"{remote}/probe {remote}/fixtures.json")
        rows = {row["name"]: row for row in json.loads(result.stdout)}
        report["rows"] = rows
        check(rows["disabled"]["path"] is None and rows["disabled"]["warning"] == "", "disabled switch prepares nothing")
        check(rows["no gpu device"]["path"] is None and rows["no gpu device"]["warning"] == "", "no virtio-gpu device prepares nothing")
        check(rows["relative"]["warning"] == "gpu-cgroup-path is not an absolute path: 'cpuset/x'; skipping", "relative path refused")
        check(rows["no cpus"]["warning"] == "gpu_cgroup_cpus is empty; cannot set up cpuset, skipping", "empty cpus refused")
        check(rows["valid"]["path"] == test_path and rows["valid"]["warning"] == "", "valid cpuset prepared")
        files = root_shell(f"cat {test_path}/cpus {test_path}/mems /dev/cpuset/mems").stdout.split()
        report["files"] = files
        check(files[0] == "0-1" and files[1] == files[2], "cpus written and mems copied from the parent")
        check(rows["nested"]["path"] == nested and root_shell(f"cat {nested}/cpus").stdout.strip() == "2", "missing parents created like mkdir -p")
        check(rows["bad cpus"]["path"] is None and rows["bad cpus"]["warning"].startswith(f"Failed to set up gpuworker cpuset at {test_path}b (cpus=999)"), "rejected cpus soft-fail")
        report["passed"] = True
    finally:
        for path in (test_path, test_path + "b", nested, nested.rsplit("/", 1)[0]):
            root_shell(f"rmdir {path} 2>/dev/null")
        report["left"] = root_shell("ls -d /dev/cpuset/droidvm-test-* 2>/dev/null").stdout.split()
        root_shell(f"rm -rf {remote}")
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
    assert report.get("passed") and not report["left"], report
    print("PASS: native GPU cpuset preparation,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
