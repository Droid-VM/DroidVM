# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright DroidVM contributors
# Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
"""Run the paired native/Java Binder fixture on one explicitly selected device."""
import argparse
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--display-service", help="Optional real isolated crosvm display service to exercise")
    parser.add_argument("--input-directory", help="Isolated display test directory for real guest input")
    parser.add_argument("--probe", type=Path, default=root / "build/migration/standalone-arm64-v8a/platform/android/probe/droidvm_platform_probe")
    parser.add_argument("--apk", type=Path, default=root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk")
    parser.add_argument("--output", type=Path, default=root / "build/migration/binder-device")
    args = parser.parse_args()
    if args.display_service and any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-." for c in args.display_service):
        parser.error("Display service must contain only letters, digits, underscore, dot or hyphen")
    if args.input_directory:
        prefix = "/data/local/tmp/droidvm-display-"
        suffix = args.input_directory.removeprefix(prefix)
        if not args.display_service or not args.input_directory.startswith(prefix) or len(suffix) != 32 or any(c not in "0123456789abcdef" for c in suffix):
            parser.error("Input requires --display-service and a display directory with a 32-digit hex nonce")
    for path in (args.probe, args.apk):
        if not path.is_file():
            parser.error(f"Build the test artifacts first: {path}")
    nonce = uuid.uuid4().hex
    output = args.output / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    remote = f"/data/local/tmp/droidvm-binder-probe-{nonce}"
    test = native = None
    try:
        subprocess.run(adb + ["install", "-r", "-t", str(args.apk)], check=True, timeout=60)
        subprocess.run(adb + ["push", str(args.probe), remote], check=True, timeout=60)
        subprocess.run(adb + ["shell", "chmod", "755", remote], check=True, timeout=10)
        command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "probe_nonce", nonce,
                         "-e", "class", "cn.classfun.droidvm.migration.NativeBinderContractTest",
                         "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"]
        if args.display_service:
            command[-1:-1] = ["-e", "display_service", args.display_service]
        if args.input_directory:
            command[-1:-1] = ["-e", "input_directory", args.input_directory]
        test = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
        lines = queue.Queue()

        def read_test():
            for line in test.stdout:
                lines.put(line)
            lines.put(None)

        threading.Thread(target=read_test, daemon=True).start()
        log = []
        deadline = time.monotonic() + 80
        with (output / "native.json").open("w", encoding="utf-8") as native_out, \
                (output / "native.stderr.txt").open("w", encoding="utf-8") as native_err, \
                (output / "instrumentation.txt").open("w", encoding="utf-8") as test_out:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("Paired Binder test exceeded 80 seconds")
                try:
                    line = lines.get(timeout=min(remaining, 1))
                except queue.Empty:
                    continue
                if line is None:
                    break
                log.append(line)
                test_out.write(line)
                test_out.flush()
                print(line, end="", flush=True)
                if line.strip() == f"INSTRUMENTATION_STATUS: probe_ready={nonce}" and native is None:
                    native_args = ["shell", remote, "--broadcast-self-test", nonce]
                    if args.display_service:
                        native_args.append(args.display_service)
                    if args.input_directory:
                        native_args.append(args.input_directory)
                    native = subprocess.Popen(adb + native_args,
                                              stdout=native_out, stderr=native_err)
            test.wait(timeout=5)
            if native is not None:
                native.wait(timeout=40)
        if native is None:
            raise RuntimeError(f"Java preflight failed before starting native probe; see {output}")
        if test.returncode or native.returncode or "OK (1 test)" not in "".join(log):
            raise RuntimeError(f"Binder test failed; see {output}")
        report = json.loads((output / "native.json").read_text(encoding="utf-8"))
        for key in ("input_checked", "echo_sent", "client_death_received", "cross_process_tested"):
            if report.get(key) is not True:
                raise RuntimeError(f"Missing native check {key}; see {output}")
        if args.display_service and report.get("real_display_returned") is not True:
            raise RuntimeError(f"Real crosvm display Binder not returned; see {output}")
        if args.display_service:
            for key in ("real_display_frame_received", "real_display_transactions_passed"):
                if f"INSTRUMENTATION_STATUS: {key}=true" not in "".join(log):
                    raise RuntimeError(f"Missing Java display check {key}; see {output}")
        if args.input_directory:
            routes = report.get("real_input_routes", [])
            if len(routes) != 7 or any(route["bytes_written"] <= 0 for route in routes) or "INSTRUMENTATION_STATUS: real_input_submitted=true" not in "".join(log):
                raise RuntimeError(f"Real input routes were not all exercised; see {output}")
        print(f"PASS: native broadcast, Java/NDK calls, callbacks and both death notifications. Results: {output}")
    finally:
        for process in (test, native):
            if process is not None and process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        subprocess.run(adb + ["shell", "rm", "-f", remote], timeout=10, check=False)


if __name__ == "__main__":
    main()
