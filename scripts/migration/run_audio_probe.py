# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright DroidVM contributors
# Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
"""Compare a native audio snapshot with AudioManager on one selected device."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--probe", type=Path, default=root / "build/migration/standalone-arm64-v8a/platform/android/probe/droidvm_platform_probe")
    parser.add_argument("--apk", type=Path, default=root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk")
    parser.add_argument("--output", type=Path, default=root / "build/migration/audio-device")
    parser.add_argument("--watch-seconds", type=int, default=0,
                        help="Also verify atomic table publication after stopping the test UI (10..120 seconds)")
    parser.add_argument("--restart-audioserver", action="store_true",
                        help="Restart the system audio service once during the watch (briefly interrupts audio)")
    args = parser.parse_args()
    if args.watch_seconds and not 10 <= args.watch_seconds <= 120:
        parser.error("--watch-seconds must be 10..120")
    if args.restart_audioserver and args.watch_seconds < 20:
        parser.error("--restart-audioserver requires --watch-seconds >= 20")
    for path in (args.probe, args.apk):
        if not path.is_file():
            parser.error(f"Build the artifacts first: {path}")
    nonce = uuid.uuid4().hex
    output = args.output / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    remote = f"/data/local/tmp/droidvm-audio-probe-{nonce}"
    remote_directory = f"/data/local/tmp/droidvm-audio-watch-{nonce}"
    watcher = None
    native_pid = None

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
        fields = ("id", "input", "internal_type", "type", "address", "key", "kind", "selectable", "sample_rates", "channel_counts")

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
        table_prefix = "INSTRUMENTATION_STATUS: audio_table_json="
        tables = [json.loads(line[len(table_prefix):])["table"] for line in baseline.splitlines() if line.startswith(table_prefix)]
        if len(tables) != 1 or tables[0] != before["audio_table"] or tables[0] != after["audio_table"]:
            raise RuntimeError(f"Legacy and native audio tables differ; see {output}")
        summary = {"snapshot_matches": True, "device_count": len(native_rows),
                   "table_matches": True,
                   "fields": fields, "p1_complete": False,
                   "hotplug_tested": False, "service_restart_tested": False}
        if args.watch_seconds:
            capture(["shell", "mkdir", "-m", "755", remote_directory], "mkdir.txt")
            audio_pid_before = capture(["shell", "pidof", "audioserver"], "audioserver-before.txt").strip()
            watch_log = output / "watch.jsonl"
            samples = 0
            restarted = False
            ui_stopped = False
            started = time.monotonic()
            with watch_log.open("w", encoding="utf-8") as stdout, (output / "watch.stderr.txt").open("w", encoding="utf-8") as stderr:
                watcher = subprocess.Popen(adb + ["shell", remote, "--audio-watch", remote_directory, str(args.watch_seconds)],
                                           stdout=stdout, stderr=stderr)
                while watcher.poll() is None:
                    if time.monotonic() - started > args.watch_seconds + 15:
                        raise TimeoutError(f"Native audio watch exceeded its deadline; see {output}")
                    log = watch_log.read_text(encoding="utf-8")
                    complete_lines = log[:log.rfind("\n") + 1].splitlines()
                    events = [json.loads(line) for line in complete_lines]
                    published = [entry for entry in events if entry.get("event") == "audio_table_published"]
                    if published:
                        native_pid = published[0]["pid"]
                        if not ui_stopped:
                            capture(["shell", "am", "force-stop", "cn.classfun.droidvm.migration.test"], "stop-test-ui.txt")
                            ui_stopped = True
                        if args.restart_audioserver and not restarted and time.monotonic() - started >= 2:
                            capture(["shell", "setprop", "ctl.restart", "audioserver"], "restart-audioserver.txt")
                            restarted = True
                        table = capture(["shell", "cat", remote_directory + "/audio_devices"], "published-table.txt")
                        rows = table.splitlines()
                        if not table.endswith("\n") or len(rows) < 2 or any(len(row.split("\t")) != 6 for row in rows):
                            raise RuntimeError(f"Incomplete published audio table; see {output}")
                        keys = set()
                        for row in rows:
                            device_id, direction, key, rate, channels, kind = row.split("\t")
                            if direction not in ("in", "out") or (direction, key) in keys:
                                raise RuntimeError(f"Invalid or duplicate table row: {row}")
                            keys.add((direction, key))
                            for field in (device_id, rate, channels, kind):
                                if int(field) < 0:
                                    raise RuntimeError(f"Invalid table number: {row}")
                        if not {("in", "DEFAULT|system default"), ("out", "DEFAULT|system default")} <= keys:
                            raise RuntimeError("Missing default route rows")
                        samples += 1
                    time.sleep(0.25)
                if watcher.returncode != 0:
                    raise RuntimeError(f"Native watch exited {watcher.returncode}; see {output}")
            events = [json.loads(line) for line in watch_log.read_text(encoding="utf-8").splitlines()]
            final = events[-1]
            if final.get("exit_code") != 0 or final.get("successful_snapshots", 0) < 2 or samples < 2 or not ui_stopped:
                raise RuntimeError(f"Incomplete audio watch; see {output}")
            if final["last_snapshot"].get("art_or_jvm_loaded") is not False:
                raise RuntimeError("Audio watch loaded ART/JVM")
            current = snapshot("native-after-watch.json")
            published = capture(["shell", "cat", remote_directory + "/audio_devices"], "final-table.txt")
            if published != current["audio_table"] or published != final["last_snapshot"]["audio_table"]:
                raise RuntimeError(f"Final published table is stale; see {output}")
            if args.restart_audioserver:
                audio_pid_after = capture(["shell", "pidof", "audioserver"], "audioserver-after.txt").strip()
                if not restarted or audio_pid_before == audio_pid_after or final["service_instances_seen"] < 2:
                    raise RuntimeError(f"Audio restart/reconnect not observed; see {output}")
                summary["service_restart_tested"] = True
            summary.update({"atomic_table_samples": samples, "test_ui_stopped": True,
                            "watch_successful_snapshots": final["successful_snapshots"],
                            "service_instances_seen": final["service_instances_seen"]})
        (output / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"PASS: {len(native_rows)} audio devices match AudioManager; native process loads no ART/JVM. Results: {output}")
    finally:
        if watcher is not None and watcher.poll() is None:
            if native_pid is not None:
                # The executable path contains this run's random nonce. Check it
                # before signaling so PID reuse cannot stop an unrelated process.
                identity = subprocess.run(adb + ["shell", "readlink", f"/proc/{native_pid}/exe"],
                                          capture_output=True, text=True, timeout=10)
                if identity.stdout.strip() == remote:
                    subprocess.run(adb + ["shell", "kill", "-TERM", str(native_pid)], timeout=10, check=False)
            try:
                watcher.wait(timeout=5)
            except subprocess.TimeoutExpired:
                watcher.kill()
                watcher.wait(timeout=5)
        if args.watch_seconds:
            subprocess.run(adb + ["shell", "rm", "-f", remote_directory + "/audio_devices"], timeout=10, check=False)
            subprocess.run(adb + ["shell", "rmdir", remote_directory], timeout=10, check=False)
        subprocess.run(adb + ["shell", "rm", "-f", remote], timeout=10, check=False)


if __name__ == "__main__":
    main()
