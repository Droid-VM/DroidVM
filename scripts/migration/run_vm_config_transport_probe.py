# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate VM config TCP and the production Java client in an isolated fixture."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
import shlex
import socket
import subprocess
import time
import uuid

from run_daemon_probe import frame, receive, request


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--coordinated", action="store_true", help="Exercise coordinated network deletion and recovery")
    mode.add_argument("--coordinated-java", action="store_true", help="Exercise production Java deletion client over TCP")
    parser.add_argument("--fixture", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvm_vm_config_fixture")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-config-transport" / nonce
    output.mkdir(parents=True)
    directory = "/data/local/tmp/droidvm-config-" + nonce
    executable = directory + "/fixture"
    adb = ["adb", "-s", args.serial]
    report = {"fixture_sha256": hashlib.sha256(args.fixture.read_bytes()).hexdigest(), "checks": []}
    processes, logs, forwards = [], [], []

    def shell(argv, timeout=30, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=timeout, **kwargs)

    def read(name):
        return shell(["cat", directory + "/" + name]).stdout.strip()

    def check(condition, label):
        assert condition, label
        report["checks"].append(label)

    def start(*options):
        log_path = output / f"fixture-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join([executable, directory, apk, *options])], stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Fixture exited before readiness")
            pid, port, token = read("droidvmd.pid"), read("droidvmd-port.txt"), read("droidvmd-token.txt")
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", log_path.read_text(encoding="utf-8", errors="replace"))
            if ready and (pid, port) == ready.groups() and len(token) == 32:
                forwarded = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True, timeout=10)
                local = int(forwarded.stdout.strip()); forwards.append(local)
                return process, pid, local, port, token
            time.sleep(0.025)
        raise TimeoutError("Fixture readiness")

    def stop(process, pid):
        assert shell(["readlink", f"/proc/{pid}/exe"], check=True).stdout.strip() == executable
        shell(["kill", "-15", pid], check=True)
        assert process.wait(timeout=15) == 143

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        reply = receive(sock)
        assert reply["type"] == "response" and reply["request_id"] == value["request_id"], reply
        return reply

    def connect(local, token=None):
        sock = socket.create_connection(("127.0.0.1", local), timeout=10)
        if token is not None:
            assert exchange(sock, "auth", token=token)["success"]
        return sock

    def snapshot(sock, command="vm_config_snapshot"):
        page = exchange(sock, command, max_bytes=131072)
        assert page["success"]
        first = page
        items = []
        try:
            while True:
                assert (page["epoch"], page["cursor"], page["snapshot_id"]) == (first["epoch"], first["cursor"], first["snapshot_id"])
                items.extend(page["items"])
                if page["next_offset"] is None:
                    break
                assert page["next_offset"] == len(items)
                page = exchange(sock, "resource_snapshot_page", snapshot_id=first["snapshot_id"], offset=len(items), max_bytes=131072)
                assert page["success"]
            data = bytearray()
            for item in items[1:]:
                assert item["type"] == "chunk" and item["offset"] == len(data)
                data.extend(base64.b64decode(item["data"], validate=True))
            assert len(items) == first["total"] and len(data) == items[0]["bytes"]
            return first, items[0], json.loads(data)
        finally:
            assert exchange(sock, "resource_snapshot_release", snapshot_id=first["snapshot_id"])["success"]

    try:
        report["fingerprint"] = shell(["getprop", "ro.build.fingerprint"], check=True).stdout.strip()
        report["coordinated"] = args.coordinated
        report["coordinated_java"] = args.coordinated_java
        if not args.coordinated:
            test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
            report["test_apk_sha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
            subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["mkdir", directory], check=True)
        subprocess.run(adb + ["push", str(args.fixture), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        assert apk.startswith("/") and "\n" not in apk
        if args.coordinated_java:
            for scenario in ("normal", "uncertain"):
                options = ["--coordinated"]
                if scenario == "uncertain":
                    options += ["--fail-once", "participant_synced"]
                process, pid, local, remote, token = start(*options)
                java = shell(["am", "instrument", "-w", "-r", "-e", "class",
                              "cn.classfun.droidvm.migration.NetworkConfigDeleteTcpTest",
                              "-e", "port", remote, "-e", "token", token, "-e", "deletion_mode", scenario,
                              "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120)
                (output / f"java-delete-{scenario}.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
                check(java.returncode == 0 and "OK (1 test)" in java.stdout,
                      f"production Java coordinated deletion TCP: {scenario}")
                with connect(local, token) as sock:
                    _, _, document = snapshot(sock)
                    _, _, network_document = snapshot(sock, "network_config_document")
                    check(network_document == {"networks": []} and
                          len(document["vms"]) == (2 if scenario == "normal" else 3) and
                          all(row["networks"] == [] and row["extension"] == 9007199254740993
                              for row in document["vms"]),
                          f"Python independently confirms Java deletion: {scenario}")
                check(json.loads(read("vms.json")) == document and
                      json.loads(read("networks.json"))["networks"] == [],
                      f"persistent files agree with Java/Python snapshots: {scenario}")
                maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
                check("libart.so" not in maps and "libjvm" not in maps, f"native fixture has no ART/JVM: {scenario}")
                stop(process, pid)
            report["passed"] = True
            return
        if args.coordinated:
            from coordinated_config_probe import run
            run(start, stop, connect, exchange, snapshot, check, shell, directory)
            report["passed"] = True
            return
        process, pid, local, remote, token = start()
        with connect(local) as sock:
            for command in ("vm_config_snapshot", "vm_config_status", "vm_config_apply", "vm_config_upload_begin"):
                assert exchange(sock, command)["error"]["code"] == "UNAUTHENTICATED"
        with connect(local, token) as sock:
            caps = exchange(sock, "capabilities")
            check(caps["service_api_minor"] == 16 and caps["vm_config_start"] == "revisioned-session-v1", "optional config and session start APIs advertised over TCP")
            first, metadata, document = snapshot(sock)
            check(document == {"vms": []}, "initial fixed document snapshot")
            begin = exchange(sock, "vm_config_upload_begin", revision=metadata["revision"], bytes=10)
            upload = begin["upload_id"]
            assert exchange(sock, "vm_config_upload_append", upload_id=upload, offset=0, data=base64.b64encode(b'{"vms').decode())["received"] == 5
        with connect(local, token) as sock:
            assert exchange(sock, "vm_config_upload_append", upload_id=upload, offset=5, data=base64.b64encode(b'":[]}').decode())["received"] == 10
            assert exchange(sock, "vm_config_upload_append", upload_id=upload, offset=5, data=base64.b64encode(b'":[]}').decode())["received"] == 10
            check(exchange(sock, "vm_config_upload_abort", upload_id=upload)["success"], "disconnected upload resumes and repeated chunk is idempotent")
            stale = exchange(sock, "vm_config_upload_begin", revision=metadata["revision"], bytes=10)["upload_id"]
            assert exchange(sock, "vm_config_upload_append", upload_id=stale, offset=0, data="eyJ2bXMiOltdfQ==")["success"]

        java = shell(["am", "instrument", "-w", "-r", "-e", "class", "cn.classfun.droidvm.migration.VmConfigClientTest",
                      "-e", "port", remote, "-e", "token", token,
                      "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120)
        (output / "java-tests.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
        check(java.returncode == 0 and "OK (3 tests)" in java.stdout, "production Java config client and editor session pass TCP and capability tests including >8 MiB VM")
        with connect(local, token) as sock:
            check(exchange(sock, "vm_config_upload_commit", upload_id=stale)["error"]["code"] == "CONFLICT", "stale upload cannot overwrite Java commit")
            view, state, document = snapshot(sock)
            check(document["lost_reply"] and document["future_root"]["integer"] == 9007199254740993, "Python agrees with Java after lost commit reply")
            pending = exchange(sock, "vm_config_upload_begin", revision=state["revision"], bytes=10)["upload_id"]
            before = shell(["sha256sum", directory + "/vms.json"], check=True).stdout.split()[0]
        maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
        check("libart.so" not in maps and "libjvm" not in maps, "fixture daemon has no ART/JVM")
        stop(process, pid)
        process, pid, local, remote, token = start()
        with connect(local, token) as sock:
            after_view, after_state, after_document = snapshot(sock)
            check(after_view["epoch"] != view["epoch"] and after_state["revision"] != state["revision"] and after_document == document, "restart preserves document and invalidates epoch/revision")
            assert exchange(sock, "vm_config_upload_commit", upload_id=pending)["error"]["code"] == "NOT_FOUND"
            assert exchange(sock, "vm_config_apply", revision=state["revision"], operations=[])["error"]["code"] == "CONFLICT"
            check(shell(["sha256sum", directory + "/vms.json"], check=True).stdout.split()[0] == before, "restart and stale operations do not rewrite persisted config")
        stop(process, pid)
        report["passed"] = True
    except Exception as error:
        report["error"] = repr(error)
        raise
    finally:
        stopped = True
        for process in processes:
            if process.poll() is None:
                candidate = read("droidvmd.pid")
                if candidate.isdecimal() and shell(["readlink", f"/proc/{candidate}/exe"]).stdout.strip() == executable:
                    shell(["kill", "-15", candidate])
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: stopped = False
        # An ADB subprocess can exit before its remote child; verify that child
        # separately before removing this run's files.
        candidate = read("droidvmd.pid")
        if candidate.isdecimal() and shell(["readlink", f"/proc/{candidate}/exe"]).stdout.strip() == executable:
            shell(["kill", "-15", candidate], check=True)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and shell(["readlink", f"/proc/{candidate}/exe"]).stdout.strip() == executable:
                time.sleep(0.05)
            stopped = stopped and shell(["readlink", f"/proc/{candidate}/exe"]).stdout.strip() != executable
        for log in logs: log.close()
        for port in forwards:
            subprocess.run(adb + ["forward", "--remove", "tcp:" + str(port)], capture_output=True, timeout=10)
        report["cleanup_complete"] = stopped
        if not stopped: report["passed"] = False
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        if stopped:
            assert directory == "/data/local/tmp/droidvm-config-" + nonce and len(nonce) == 32
            shell(["rm", "-rf", "--", directory], check=True)
        print("Results:", output)
        if not stopped: raise RuntimeError("Fixture is still running; retained isolated directory: " + directory)
    print("PASS: VM configuration TCP, Java client, disconnect, revision conflicts and restart")


if __name__ == "__main__":
    main()
