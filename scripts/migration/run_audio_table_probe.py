# SPDX-License-Identifier: GPL-3.0-or-later
"""The native daemon publishes run/audio_devices like the Java HostAudioTable."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

PRODUCTION = "/data/data/cn.classfun.droidvm"


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/audio-table-device" / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/audio-{nonce[:8]}"
    binary_dir = f"/data/local/tmp/droidvm-audio-table-{nonce}"
    report = {"checks": []}
    process = None

    def shell(command):
        return subprocess.run(adb + ["shell", command], text=True, capture_output=True, timeout=60)

    def root_shell(command):
        return shell("su -c " + shlex.quote(command))

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    log_path = output / "daemon.txt"
    try:
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        assert shell(shlex.join(["run-as", app, "mkdir", "-p", data + "/files", data + "/bin"])).returncode == 0
        assert root_shell(f"mkdir {binary_dir}").returncode == 0
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60, capture_output=True)
        root_shell(f"chmod 755 {binary_dir}/droidvmd")
        apk = shell("pm path cn.classfun.droidvm").stdout.strip().removeprefix("package:")
        assert apk.endswith(".apk"), "production APK path"
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(f"{binary_dir}/droidvmd --data-dir {data} --apk {apk}")], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            ready = None
            while time.monotonic() < deadline and not ready:
                ready = re.search(r"droidvmd ready pid=(\d+)", log_path.read_text(encoding="utf-8"))
                time.sleep(0.1)
            check(ready is not None, "daemon ready")
            pid = ready.group(1)
            table = ""
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not table:
                table = root_shell(f"cat {data}/run/audio_devices 2>/dev/null").stdout
                time.sleep(0.25)
            (output / "audio_devices").write_text(table, encoding="utf-8")
            check(table and all(len(line.split("\t")) == 6 for line in table.splitlines()), "six-column table published")
            mode = root_shell(f"stat -c %a {data}/run/audio_devices").stdout.strip()
            check(mode == "644", "table readable by the unprivileged audio backend")
            production = root_shell(f"cat {PRODUCTION}/run/audio_devices 2>/dev/null").stdout
            report["production_present"] = bool(production)
            if production:
                (output / "production_audio_devices").write_text(production, encoding="utf-8")
                check(table == production, "identical to the Java daemon's table on this host")
            maps = root_shell(f"cat /proc/{pid}/maps").stdout
            check("libart.so" not in maps and "libandroid_runtime" not in maps, "no ART/JVM in the daemon")
            check(not root_shell(f"ls {data}/run").stdout.count(".audio_devices."), "no temporary table left")
            root_shell(f"kill -15 {pid}")
            process.wait(timeout=60)
            check("stopped exit=143" in root_shell(f"cat {data}/cache/daemon.log").stdout, "daemon stops cleanly with the audio watcher")
        report["passed"] = True
    finally:
        if process and process.poll() is None:
            process.kill()
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            root_shell(f"rm -r {data} {binary_dir}")
    print("PASS: native host audio table,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
