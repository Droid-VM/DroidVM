# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the native daemon in an isolated directory, never production runtime files."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import queue
import shlex
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid


def frame(value, nul=False):
    body = json.dumps(value, ensure_ascii=False).encode() + (b"\0" if nul else b"")
    return struct.pack("<I", len(body)) + body


def exact(sock, size):
    value = b""
    while len(value) < size:
        part = sock.recv(size - len(value))
        if not part:
            raise EOFError("Incomplete frame")
        value += part
    return value


def receive(sock):
    size, = struct.unpack("<I", exact(sock, 4))
    assert 0 < size <= 8 << 20
    return json.loads(exact(sock, size).removesuffix(b"\0"))


def request(command, **fields):
    return {"type": "request", "request_id": str(uuid.uuid4()), "command": command, **fields}


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    parser.add_argument("--app-metadata", type=Path, help="AGP output-metadata.json for a packaged daemon")
    parser.add_argument("--launcher", type=Path, help="Optional existing double-fork launcher to verify")
    parser.add_argument("--hash-apk", type=Path, help="Copy this APK into the isolated directory and verify its hash without installing it")
    parser.add_argument("--data-layout", action="store_true", help="Verify app-owned run/cache layout and bounded native log entry")
    parser.add_argument("--metrics", action="store_true", help="Sample the isolated daemon's resources and ping latency")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/daemon-device" / nonce
    output.mkdir(parents=True)
    directory = "/data/user/0/cn.classfun.droidvm.migration.test/cache/native-" + nonce
    data_directory = directory
    if args.data_layout:
        directory += "/run"
    daemon_log = data_directory + "/cache/daemon.log"
    binary_directory = "/data/local/tmp/droidvm-runtime-" + nonce
    adb = ["adb", "-s", args.serial]
    executable = binary_directory + "/droidvmd"
    test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    daemon = args.daemon
    expected_version = {"version": "development", "version_code": 0, "pkg_name": "cn.classfun.droidvm", "build_type": "development"}
    if args.app_metadata:
        metadata = json.loads(args.app_metadata.read_text(encoding="utf-8"))
        expected_version = {"version": metadata["elements"][0]["versionName"],
                            "version_code": metadata["elements"][0]["versionCode"],
                            "pkg_name": metadata["applicationId"], "build_type": metadata["variantName"]}
    cpp_client = root / "build/migration/app-client-arm64/ipc_client_contract"
    runner = "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"
    processes, logs, forwards = [], [], []
    report = {}
    detached_pid = None

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8",
                              errors="replace", timeout=40, **kwargs)

    def read(name):
        return shell(["cat", directory + "/" + name], capture_output=True).stdout.strip()

    def instrument(method, extras):
        command = ["am", "instrument", "-w", "-r", "-e", "class",
                   "cn.classfun.droidvm.migration.NativeDaemonContractTest#" + method]
        for key, value in extras.items():
            command += ["-e", key, str(value)]
        return command + [runner]

    def start(force=False):
        log = (output / f"daemon-{len(processes)}.txt").open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join(command + (["--force"] if force else []))],
                                   stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def ready(process, previous=None):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Native daemon exited before readiness")
            pid, port, token = read("droidvmd.pid"), read("droidvmd-port.txt"), read("droidvmd-token.txt")
            if pid.isdecimal() and port.isdecimal() and len(token) == 32 and pid != previous:
                return pid, int(port), token
            time.sleep(0.05)
        raise TimeoutError("Native daemon readiness timeout")

    def connect():
        sock = socket.create_connection(("127.0.0.1", local_port), timeout=5)
        return sock

    try:
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["run-as", "cn.classfun.droidvm.migration.test", "mkdir", "-p", data_directory], check=True)
        shell(["mkdir", binary_directory], check=True)
        subprocess.run(adb + ["push", str(daemon), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        if args.launcher:
            subprocess.run(adb + ["push", str(args.launcher), binary_directory + "/launcher"], check=True, timeout=30)
            shell(["chmod", "755", binary_directory + "/launcher"], check=True)
        subprocess.run(adb + ["push", str(cpp_client), binary_directory + "/client"], check=True, timeout=30)
        shell(["chmod", "755", binary_directory + "/client"], check=True)
        subprocess.run(adb + ["push", str(root / "app/src/androidTest/assets/migration/protocol-frames.json"),
                               binary_directory + "/frames.json"], check=True, timeout=10)
        if args.hash_apk:
            apk = binary_directory + "/source.apk"
            subprocess.run(adb + ["push", str(args.hash_apk), apk], check=True, timeout=120)
            with args.hash_apk.open("rb") as source:
                expected_hash = hashlib.file_digest(source, "sha256").hexdigest()
        else:
            apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True, capture_output=True).stdout.strip()
            assert apk.startswith("package:") and "\n" not in apk
            apk = apk.removeprefix("package:")
            expected_hash = shell(["sha256sum", apk], check=True, capture_output=True).stdout.split()[0]
        command = [executable, "--data-dir" if args.data_layout else "--run-dir", data_directory, "--apk", apk]
        if args.data_layout:
            # First startup creates run/cache itself, then fails before discovery.
            failed = shell(command[:-1] + [binary_directory + "/absent.apk"], capture_output=True)
            assert failed.returncode == 1 and "open APK" in failed.stderr
            assert shell(["test", "-e", directory + "/droidvmd-port.txt"]).returncode != 0
            log_text = shell(["run-as", "cn.classfun.droidvm.migration.test", "cat", daemon_log], check=True, capture_output=True).stdout
            assert "ERROR" in log_text and "open APK" in log_text
            report["startup_failure_logged_without_discovery"] = True

        # Converse interop: Java owns the same inode first; native must refuse.
        java_command = instrument("javaOwnsLock", {"lock_path": directory + "/droidvmd.lock"})
        java = subprocess.Popen(adb + ["shell", shlex.join(java_command)], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        lines = queue.Queue()
        def read_java():
            for line in java.stdout:
                lines.put(line)
            lines.put(None)
        threading.Thread(target=read_java, daemon=True).start()
        java_lines = []
        try:
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
                if line is None:
                    raise RuntimeError("Java lock fixture failed")
                java_lines.append(line)
                if "INSTRUMENTATION_STATUS: java_lock_ready=true" in line:
                    break
            else:
                raise TimeoutError("Java lock not ready")
            duplicate = shell(command, capture_output=True)
            assert duplicate.returncode == 1 and "another daemon" in duplicate.stderr
            shell(["touch", directory + "/release-java-lock"], check=True)
            java.wait(timeout=10)
            while True:
                line = lines.get(timeout=2)
                if line is None:
                    break
                java_lines.append(line)
            assert "OK (1 test)" in "".join(java_lines)
            report["java_lock_blocks_native"] = True
        finally:
            if java.poll() is None:
                java.kill(); java.wait(timeout=5)
            (output / "java-lock.txt").write_text("".join(java_lines), encoding="utf-8")

        first = start()
        pid, port, token = ready(first)
        if args.data_layout:
            uid = shell(["stat", "-c", "%u", data_directory], check=True, capture_output=True).stdout.strip()
            for name in ("droidvmd.lock", "droidvmd.pid", "droidvmd-token.txt", "droidvmd-port.txt"):
                info = shell(["stat", "-c", "%u:%g:%a", directory + "/" + name], check=True, capture_output=True).stdout.strip()
                assert info == f"{uid}:{uid}:600", (name, info)
                shell(["run-as", "cn.classfun.droidvm.migration.test", "cat", directory + "/" + name], check=True, capture_output=True)
            for path, mode in ((directory, "700"), (data_directory + "/cache", "700"), (daemon_log, "600")):
                assert shell(["stat", "-c", "%u:%g:%a", path], check=True, capture_output=True).stdout.strip() == f"{uid}:{uid}:{mode}"
            assert shell(["getenforce"], check=True, capture_output=True).stdout.strip() == "Enforcing"
            assert shell(["cat", f"/proc/{pid}/comm"], check=True, capture_output=True).stdout.strip() == "droidvmd"
            report["app_uid_files_readable_selinux_enforcing"] = True
        forward = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + str(port)], check=True,
                                 capture_output=True, text=True, timeout=10)
        local_port = int(forward.stdout.strip())
        forwards.append(local_port)
        assert shell(["stat", "-c", "%a", directory + "/droidvmd-token.txt"], capture_output=True).stdout.strip() == "600"
        java = shell(instrument("javaProtocolAndNativeLock", {"lock_path": directory + "/droidvmd.lock",
                     "port": port, "apk_hash": expected_hash}), capture_output=True)
        (output / "java-protocol.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
        assert java.returncode == 0 and "OK (1 test)" in java.stdout
        report["native_lock_blocks_java"] = report["java_protocol_passed"] = True
        shell(["mv", directory + "/droidvmd-token.txt", directory + "/token.saved"], check=True)
        try:
            with connect() as sock:
                sock.sendall(frame(request("auth", token=token)))
                assert receive(sock)["message"] == "Token file not found"
                shell(["sh", "-c", 'umask 077; printf short > "$1"', "migration-token", directory + "/droidvmd-token.txt"], check=True)
                sock.sendall(frame(request("auth", token=token)))
                assert receive(sock)["message"] == "Invalid token file"
                sock.sendall(frame(request("auth")))
                assert receive(sock)["message"] == "internal error"
        finally:
            shell(["mv", directory + "/token.saved", directory + "/droidvmd-token.txt"], check=True)
        report["legacy_auth_file_errors"] = True
        cpp = shell([binary_directory + "/client", directory, binary_directory + "/frames.json", expected_hash], capture_output=True)
        (output / "cpp-client.txt").write_text(cpp.stdout + cpp.stderr, encoding="utf-8")
        assert cpp.returncode == 0 and "PASS:" in cpp.stdout
        report["existing_cpp_client_and_golden_frames"] = True
        with connect() as sock:
            commands = [request("capabilities"), request("auth", token="wrong"), request("auth", token=token),
                        request("capabilities"), request("version"), request("ping", request="PING"), request("ping"), request("不存在-迁移")]
            payload = b"".join(frame(value, index % 2 == 0) for index, value in enumerate(commands))
            # Split the header, then coalesce complete requests in the same write.
            sock.sendall(payload[:1]); sock.sendall(payload[1:3]); sock.sendall(payload[3:])
            responses = [receive(sock) for _ in commands]
            assert [value["request_id"] for value in responses] == [value["request_id"] for value in commands]
            assert responses[0]["message"] == "client is not authorized to perform this action"
            assert responses[1]["message"] == "Token mismatch"
            assert responses[2]["success"] and responses[3]["production_ready"] is False
            assert responses[4]["daemon_hash"] == expected_hash
            assert {key: responses[4][key] for key in expected_version} == expected_version
            report["version"] = expected_version
            assert responses[5]["response"] == "PONG" and responses[6]["response"] == "HELLO"
            assert responses[7]["message"] == "command handler 不存在-迁移 not found"
            report["framing_auth_version_passed"] = True
            assert "task_subscribe" in responses[3]["commands"]
            sock.sendall(frame(request("task_snapshot")))
            snapshot = receive(sock)
            # The network service publishes its initial immutable view into the
            # same journal, even when there are no task records or networks.
            has_network_events = responses[3].get("network_config_events") == "invalidation-v1"
            assert snapshot["success"] and snapshot["items"] == []
            assert snapshot["cursor"] == ("1" if has_network_events else "0"), snapshot
            assert snapshot["epoch"] == responses[3]["epoch"]
            if has_network_events:
                sock.sendall(frame(request("network_config_snapshot")))
                network_snapshot = receive(sock)
                assert network_snapshot["success"] and network_snapshot["items"] == []
                assert (network_snapshot["epoch"], network_snapshot["cursor"]) == (snapshot["epoch"], snapshot["cursor"])
                sock.sendall(frame(request("resource_snapshot_release", snapshot_id=network_snapshot["snapshot_id"])))
                assert receive(sock)["success"]
            task_commands = [request("task_subscribe", epoch=snapshot["epoch"], cursor=snapshot["cursor"]),
                             request("ping", request="PING"), request("task_unsubscribe"),
                             request("task_snapshot_release", snapshot_id=snapshot["snapshot_id"]),
                             request("task_get", epoch=snapshot["epoch"], task_id=str(uuid.uuid4()))]
            sock.sendall(b"".join(frame(value) for value in task_commands))
            task_responses = [receive(sock) for _ in task_commands]
            assert [value["request_id"] for value in task_responses] == [value["request_id"] for value in task_commands]
            assert all(value["success"] for value in task_responses[:4])
            assert task_responses[-1]["error"]["code"] == "TASK_NOT_FOUND"
            report["production_entry_task_services_shared_epoch"] = True
        with connect() as sock:
            boundary = json.dumps(request("ping", request="PING")).encode()
            boundary += b" " * ((8 << 20) - len(boundary))
            sock.sendall(struct.pack("<I", len(boundary)) + boundary)
            assert receive(sock)["response"] == "PONG"
            # A malformed JSON frame does not desynchronize subsequent frames.
            sock.sendall(struct.pack("<I", 1) + b"{" + frame(request("ping")))
            assert receive(sock)["response"] == "HELLO"
        report["maximum_frame_and_json_recovery"] = True
        for size in (0, (8 << 20) + 1, 0xffffffff):
            with connect() as sock:
                sock.sendall(struct.pack("<I", size))
                assert sock.recv(1) == b""
        with connect() as sock:
            sock.sendall(b"\x20\0")
        with connect() as sock:
            sock.sendall(struct.pack("<I", 100) + b"{")
        def pings(worker):
            with connect() as sock:
                batch = [request("ping", request="PING", worker=worker) for _ in range(100)]
                sock.sendall(b"".join(frame(value) for value in batch))
                assert {receive(sock)["request_id"] for _ in batch} == {value["request_id"] for value in batch}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(pings, range(8)))
        report["invalid_frames_and_concurrent_clients"] = True
        assert "PASS: slow client disconnected" in cpp.stdout
        report["slow_client_isolation"] = True
        maps = shell(["cat", f"/proc/{pid}/maps"], check=True, capture_output=True).stdout
        assert "/libart.so" not in maps and "/libjvm.so" not in maps
        report["art_or_jvm_loaded"] = False
        if args.metrics:
            subprocess.run([sys.executable, str(root / "scripts/migration/sample_daemon_metrics.py"),
                            "--serial", args.serial, "--run-dir", directory,
                            "--label", "isolated-native"], check=True, timeout=120)
            report["resource_metrics_sampled"] = True
        original = {name: read(name) for name in ("droidvmd.pid", "droidvmd-port.txt", "droidvmd-token.txt")}
        log_before = shell(["cat", daemon_log], check=True, capture_output=True).stdout if args.data_layout else None
        duplicate = shell(command, capture_output=True)
        assert duplicate.returncode == 1 and original == {name: read(name) for name in original}
        if args.data_layout:
            assert shell(["cat", daemon_log], check=True, capture_output=True).stdout == log_before
            report["duplicate_preserves_log"] = True
        # A forged PID in the lock file must not change the replacement target.
        shell(["sh", "-c", 'printf 1 > "$1"', "migration-lock", directory + "/droidvmd.lock"], check=True)
        replacement = start(True)
        new_pid, new_port, new_token = ready(replacement, pid)
        assert first.wait(timeout=5) == 130 and new_token != token
        report["duplicate_and_force_passed"] = True
        # SIGKILL releases the kernel lock even when discovery files remain.
        shell(["kill", "-9", new_pid], check=True)
        replacement.wait(timeout=5)
        recovered = start()
        final_pid, _, _ = ready(recovered, new_pid)
        shell(["kill", "-15", final_pid], check=True)
        assert recovered.wait(timeout=5) == 143
        assert all(shell(["test", "-e", directory + "/" + name]).returncode != 0 for name in original)
        assert shell(["test", "-f", directory + "/droidvmd.lock"]).returncode == 0
        report["sigkill_recovery_and_term_cleanup"] = True
        if args.data_layout:
            log_text = shell(["run-as", "cn.classfun.droidvm.migration.test", "cat", daemon_log], check=True, capture_output=True).stdout
            assert "stopped exit=130" in log_text and "stopped exit=143" in log_text
            assert all(secret not in log_text for secret in (token, new_token))
            (output / "native-daemon.log").write_text(log_text, encoding="utf-8")
            report["native_lifecycle_log_without_tokens"] = True
            # Attack only paths in this stopped, isolated test instance. Reject
            # aliases before truncating/chowning their target; restore each path.
            for path in (directory, data_directory + "/cache", daemon_log, directory + "/droidvmd.lock"):
                saved = path + ".saved"
                shell(["mv", path, saved], check=True)
                try:
                    shell(["ln", "-s", saved, path], check=True)
                    rejected = shell(command, capture_output=True)
                    assert rejected.returncode == 1, (path, rejected.stdout, rejected.stderr)
                finally:
                    shell(["rm", "-f", path], check=True)
                    shell(["mv", saved, path], check=True)
            for path in (daemon_log, directory + "/droidvmd.lock"):
                saved = path + ".saved"
                original_text = shell(["cat", path], check=True, capture_output=True).stdout
                original_mode = shell(["stat", "-c", "%u:%g:%a", path], check=True, capture_output=True).stdout
                shell(["ln", path, saved], check=True)
                try:
                    rejected = shell(command, capture_output=True)
                    assert rejected.returncode == 1, (path, rejected.stdout, rejected.stderr)
                    assert shell(["cat", saved], check=True, capture_output=True).stdout == original_text
                    assert shell(["stat", "-c", "%u:%g:%a", saved], check=True, capture_output=True).stdout == original_mode
                finally:
                    shell(["rm", "-f", saved], check=True)
            shell(["mv", daemon_log, daemon_log + ".saved"], check=True)
            try:
                shell(["mkfifo", daemon_log], check=True)
                rejected = shell(command, capture_output=True)
                assert rejected.returncode == 1
            finally:
                shell(["rm", "-f", daemon_log], check=True)
                shell(["mv", daemon_log + ".saved", daemon_log], check=True)
            report["directory_file_symlinks_hardlinks_fifo_rejected"] = True
        if args.launcher:
            launch_command = shlex.join([binary_directory + "/launcher"] + command)
            launch_command += " >" + shlex.quote(binary_directory + "/detached.log") + " 2>&1 </dev/null"
            launched = shell(["sh", "-c", launch_command], capture_output=True)
            assert launched.returncode == 0
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                candidate, candidate_port = read("droidvmd.pid"), read("droidvmd-port.txt")
                if candidate.isdecimal() and candidate_port.isdecimal():
                    detached_pid = candidate
                    break
                time.sleep(0.05)
            assert detached_pid is not None, "Double-fork launcher did not start native daemon"
            forwarded = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + candidate_port], check=True,
                                       capture_output=True, text=True, timeout=10)
            detached_port = int(forwarded.stdout.strip())
            forwards.append(detached_port)
            with socket.create_connection(("127.0.0.1", detached_port), timeout=5) as sock:
                sock.sendall(frame(request("ping", request="PING")))
                assert receive(sock)["response"] == "PONG"
            shell(["kill", "-15", detached_pid], check=True)
            deadline = time.monotonic() + 5
            while shell(["test", "-e", directory + "/droidvmd.pid"]).returncode == 0 and time.monotonic() < deadline:
                time.sleep(0.05)
            assert shell(["test", "-e", directory + "/droidvmd.pid"]).returncode != 0
            detached_pid = None
            report["existing_double_fork_launcher"] = True
        report["passed"] = True
    finally:
        if detached_pid is not None:
            identity = shell(["readlink", f"/proc/{detached_pid}/exe"], capture_output=True).stdout.strip()
            if identity == executable:
                shell(["kill", "-15", detached_pid], capture_output=True)
        for process in processes:
            if process.poll() is None:
                # This PID comes from our dedicated runtime file, and the held
                # adb shell handle belongs to the exact executable invocation.
                current = read("droidvmd.pid")
                identity = shell(["readlink", f"/proc/{current}/exe"], capture_output=True).stdout.strip() if current.isdecimal() else ""
                if current.isdecimal() and identity == executable:
                    shell(["kill", "-15", current], capture_output=True)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
        for log in logs:
            log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Results: {output}")
        if report.get("passed"):
            shell(["rm", "-f", executable, binary_directory + "/client", binary_directory + "/frames.json",
                   binary_directory + "/launcher", binary_directory + "/detached.log",
                   binary_directory + "/source.apk",
                   directory + "/droidvmd.lock", directory + "/release-java-lock",
                   # A data-layout daemon keeps its (empty) helper identity journal.
                   directory + "/network-runtime.json", directory + "/network-runtime.json.bak",
                   # HostAudioTable keeps the last published table, as the Java daemon does.
                   directory + "/audio_devices"], check=True)
            shell(["rmdir", directory], check=True)
            if args.data_layout:
                shell(["rm", "-f", daemon_log], check=True)
                shell(["rmdir", data_directory + "/cache", data_directory + "/files", data_directory], check=True)
            shell(["rmdir", binary_directory], check=True)
    print("PASS: native daemon protocol, Java lock interoperability, lifecycle and recovery")


if __name__ == "__main__":
    main()
