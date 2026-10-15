# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright DroidVM contributors
# Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
"""Compare a native audio snapshot with AudioManager on one selected device."""
import argparse
import json
from pathlib import Path
import subprocess
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--probe", type=Path, default=root / "build/migration/standalone-arm64-v8a/platform/android/probe/droidvm_platform_probe")
    parser.add_argument("--apk", type=Path, default=root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk")
    parser.add_argument("--output", type=Path, default=root / "build/migration/audio-device")
    args = parser.parse_args()
    for path in (args.probe, args.apk):
        if not path.is_file():
            parser.error(f"Build the artifacts first: {path}")
    nonce = uuid.uuid4().hex
    output = args.output / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    remote = f"/data/local/tmp/droidvm-audio-probe-{nonce}"

    def capture(arguments, name, timeout=30):
        result = subprocess.run(adb + arguments, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        (output / name).write_text(result.stdout, encoding="utf-8")
        (output / (name + ".stderr.txt")).write_text(result.stderr, encoding="utf-8")
        result.check_returncode()
        return result.stdout

    def snapshot(name):
        result = json.loads(capture(["shell", remote, "--audio-snapshot"], name))
        if result.get("audio_enumeration_passed") is not True or result.get("art_or_jvm_loaded") is not False:
            raise RuntimeError(f"Native enumeration/runtime check failed; see {output}")
        return result

    try:
        capture(["install", "-r", "-t", str(args.apk)], "install.txt", timeout=60)
        capture(["push", str(args.probe), remote], "push.txt", timeout=60)
        capture(["shell", "chmod", "755", remote], "chmod.txt")
        before = snapshot("native-before.json")
        baseline = capture(["shell", "am", "instrument", "--no-hidden-api-checks", "-w", "-r",
                            "-e", "audio_snapshot", "true", "-e", "class",
                            "cn.classfun.droidvm.migration.AudioSnapshotTest",
                            "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"],
                           "java-baseline.txt")
        if "OK (1 test)" not in baseline:
            raise RuntimeError(f"Java baseline failed; see {output}")
        prefix = "INSTRUMENTATION_STATUS: audio_devices_json="
        lines = [line[len(prefix):] for line in baseline.splitlines() if line.startswith(prefix)]
        if len(lines) != 1:
            raise RuntimeError(f"Missing Java snapshot; see {output}")
        java = json.loads(lines[0])
        after = snapshot("native-after.json")
        if before["generation"] != after["generation"] or before["devices"] != after["devices"]:
            raise RuntimeError(f"Devices changed during comparison; rerun after the change settles. See {output}")
        fields = ("id", "input", "internal_type", "address", "sample_rates", "channel_counts")

        def comparable(devices):
            rows = [{key: device[key] for key in fields} for device in devices]
            ids = [row["id"] for row in rows]
            if len(ids) != len(set(ids)):
                raise RuntimeError("Duplicate audio port IDs")
            return sorted(rows, key=lambda row: row["id"])

        native_rows = comparable(before["devices"])
        java_rows = comparable(java)
        if not native_rows or native_rows != java_rows:
            raise RuntimeError(f"Native and AudioManager device snapshots differ; see {output}")
        summary = {"snapshot_matches": True, "device_count": len(native_rows),
                   "fields": fields, "p1_complete": False,
                   "hotplug_tested": False, "service_restart_tested": False}
        (output / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"PASS: {len(native_rows)} audio devices match AudioManager; native process loads no ART/JVM. Results: {output}")
    finally:
        subprocess.run(adb + ["shell", "rm", "-f", remote], timeout=10, check=False)


if __name__ == "__main__":
    main()
