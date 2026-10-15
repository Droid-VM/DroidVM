# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the native daemon in an isolated directory, never production runtime files."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import queue
import shlex
import socket
import struct
import subprocess
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
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/daemon-device" / nonce
    output.mkdir(parents=True)
    directory = "/data/user/0/cn.classfun.droidvm.migration.test/cache/native-" + nonce
    binary_directory = "/data/local/tmp/droidvm-runtime-" + nonce
    adb = ["adb", "-s", args.serial]
    executable = binary_directory + "/droidvmd"
    test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    daemon = root / "build/migration/standalone-arm64-v8a/droidvmd"
    cpp_client = root / "build/migration/app-client-arm64/ipc_client_contract"
    runner = "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"
    processes, logs, forwards = [], [], []
    report = {}

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
        shell(["run-as", "cn.classfun.droidvm.migration.test", "mkdir", "-p", directory], check=True)
        shell(["mkdir", binary_directory], check=True)
        subprocess.run(adb + ["push", str(daemon), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        subprocess.run(adb + ["push", str(cpp_client), binary_directory + "/client"], check=True, timeout=30)
        shell(["chmod", "755", binary_directory + "/client"], check=True)
        subprocess.run(adb + ["push", str(root / "app/src/androidTest/assets/migration/protocol-frames.json"),
                               binary_directory + "/frames.json"], check=True, timeout=10)
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True, capture_output=True).stdout.strip()
        assert apk.startswith("package:") and "\n" not in apk
        apk = apk.removeprefix("package:")
        expected_hash = shell(["sha256sum", apk], check=True, capture_output=True).stdout.split()[0]
        command = [executable, "--run-dir", directory, "--apk", apk]

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
            assert responses[4]["daemon_hash"] == expected_hash and responses[4]["version_code"] == 0
            assert responses[5]["response"] == "PONG" and responses[6]["response"] == "HELLO"
            assert responses[7]["message"] == "command handler 不存在-迁移 not found"
            report["framing_auth_version_passed"] = True
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
        original = {name: read(name) for name in ("droidvmd.pid", "droidvmd-port.txt", "droidvmd-token.txt")}
        duplicate = shell(command, capture_output=True)
        assert duplicate.returncode == 1 and original == {name: read(name) for name in original}
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
        report["passed"] = True
    finally:
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
                   directory + "/droidvmd.lock", directory + "/release-java-lock"], check=True)
            shell(["rmdir", directory], check=True)
            shell(["rmdir", binary_directory], check=True)
    print("PASS: native daemon protocol, Java lock interoperability, lifecycle and recovery")


if __name__ == "__main__":
    main()
