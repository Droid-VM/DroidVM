# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the native VM inventory through the existing vm_* TCP commands."""
import argparse
import hashlib
import json
from pathlib import Path
import re
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
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-inventory-device" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/vm-{nonce}"
    binary_dir = f"/data/local/tmp/droidvm-vm-{nonce}"
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs = {"checks": []}, [], [], []
    active = None

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=45, **kwargs)

    def read(path):
        return shell(["cat", path], check=True).stdout.strip()

    def seed(text):
        source = output / "seed.json"
        source.write_bytes(text.encode("utf-8"))
        subprocess.run(adb + ["push", str(source), binary_dir + "/seed.json"], check=True, timeout=20)
        shell(["cp", binary_dir + "/seed.json", data + "/files/vms.json"], check=True)
        shell(["chown", uid + ":" + uid, data + "/files/vms.json"], check=True)
        shell(["chmod", "600", data + "/files/vms.json"], check=True)

    def start():
        nonlocal active
        log_path = output / f"daemon-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join([binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk])],
                                   stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Daemon startup failed: " + log_path.read_text(encoding="utf-8"))
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", log_path.read_text(encoding="utf-8"))
            if ready:
                pid, port = ready.groups()
                token = read(data + "/run/droidvmd-token.txt")
                forward = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True, timeout=10)
                local = int(forward.stdout.strip()); forwards.append(local)
                active = (process, pid)
                return local, token
            time.sleep(0.025)
        raise TimeoutError("Daemon readiness")

    def stop():
        nonlocal active
        process, pid = active
        shell(["kill", "-15", pid], check=True)
        assert process.wait(timeout=45) == 128 + 15
        active = None

    def connect(port, token, auth=True):
        sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        if auth:
            assert exchange(sock, "auth", token=token)["success"]
        return sock

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        result = receive(sock)
        assert result["request_id"] == value["request_id"], result
        return result

    def check(condition, name):
        report["checks"].append(name)
        assert condition, name

    def file_hash():
        return shell(["sha256sum", data + "/files/vms.json"], check=True).stdout.split()[0]

    legacy_id = "ABCDEF01-2345-6789-ABCD-EF0123456789"
    legacy = {"id": legacy_id, "name": "旧版", "kernel": "/k", "use_uefi": False, "cmdline": "quiet",
              "display_enabled": True, "display_backend": "virtio_gpu", "vnc_enabled": True, "gpu_enabled": False,
              "usb": True, "networks": [{"network_id": "n1"}],
              "port_forwards": [{"network_id": "n1", "host_port": 2222, "guest_port": 22}],
              "future": {"integer": 9007199254740993, "text": "🌏"}}
    current_id = str(uuid.uuid4())
    current = {"id": current_id, "name": "current", "backend": "qemu", "agent_mode": True, "boot": {"protocol": "uefi"},
               "screens": {}, "serial_ports": [], "xhci_next": 0, "state": "RUNNING"}
    try:
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["run-as", app, "mkdir", "-p", data + "/files"], check=True)
        uid = shell(["stat", "-c", "%u", data], check=True).stdout.strip()
        shell(["mkdir", binary_dir], check=True)
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=30)
        shell(["chmod", "755", binary_dir + "/droidvmd"], check=True)
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        seed(json.dumps({"vms": [legacy, current], "root": {"kept": True}}, ensure_ascii=False, indent=4))
        before = file_hash()
        port, token = start()
        with connect(port, token, False) as sock:
            check(exchange(sock, "vm_list")["message"] == "client is not authorized to perform this action", "vm commands require auth")
        with connect(port, token) as sock:
            cap = exchange(sock, "capabilities")
            check(all(name in cap["commands"] for name in ("vm_create", "vm_modify", "vm_delete", "vm_list", "vm_get", "vm_exists", "vm_status"))
                  and cap["vm_inventory"] is True and cap["vm_runtime"] is True, "capabilities advertise inventory and runtime")
            rows = exchange(sock, "vm_list")["data"]
            check([row["id"] for row in rows] == [legacy_id, current_id], "file order and raw stored ids preserved")
            old = rows[0]
            check(old["boot"]["protocol"] == "linux" and old["boot"]["linux"]["cmdline"] == "quiet" and
                  old["screens"]["gpu-0"]["exporter"] == "vnc" and old["gpu_backend"] == "gpu_2d" and
                  old["peripherals"][0]["id"] == "xhci-0" and "port_forwards" not in old and
                  old["networks"][0]["dhcp4_lease"]["forwards"] == [{"proto": "tcp", "host": "2222", "guest": "22"}] and
                  old["future"] == legacy["future"], "loaded legacy VM is migrated in memory with unknown fields")
            check(old["state"] == "STOPPED" and old["pid"] == -1 and
                  old["streams"] == ["stdout", "stderr", "stdio", "serial1"], "crosvm info row state/pid/streams")
            check(rows[1]["state"] == "STOPPED" and rows[1]["streams"] == ["stdout", "stderr", "stdio", "uart", "agent"],
                  "persisted RUNNING state is never adopted; qemu agent streams")
            check(exchange(sock, "vm_get", vm_id=legacy_id.lower())["data"]["name"] == "旧版", "vm_get canonical id match")
            missing = str(uuid.uuid4())
            check(exchange(sock, "vm_get", vm_id=missing)["message"] == "VM not found: " + missing, "vm_get unknown message")
            check(exchange(sock, "vm_get", vm_id="bad")["message"] == "VM not found: bad", "vm_get invalid id message")
            check(exchange(sock, "vm_get")["message"] == "missing vm_id", "vm_get missing id")
            exists = exchange(sock, "vm_exists", vm_id=legacy_id)
            check(exists["vm_id"] == legacy_id and exists["exists"] is True, "vm_exists echoes raw id")
            check(exchange(sock, "vm_exists", vm_id=missing)["exists"] is False, "vm_exists false")
            status = exchange(sock, "vm_status", vm_id=current_id)
            check(status["state"] == "stopped" and status["vm_id"] == current_id, "vm_status lowercase")
            check(exchange(sock, "vm_status", vm_id=missing)["message"] == "VM not found: " + missing, "vm_status unknown")
            # create
            created_id = str(uuid.uuid4()).upper()
            created = {"id": created_id, "name": "created", "display_width": 800, "unknown": [1, None]}
            result = exchange(sock, "vm_create", config=created)
            check(result["success"] and result["vm_id"] == created_id.lower(), "vm_create returns canonical id")
            stored = exchange(sock, "vm_get", vm_id=created_id)["data"]
            check(stored["id"] == created_id.lower() and stored["screens"]["gpu-0"]["width"] == 800 and stored["unknown"] == [1, None],
                  "created config migrated with canonical id")
            check(exchange(sock, "vm_create", config=created)["message"] == "failed to create VM", "duplicate id rejected")
            check(exchange(sock, "vm_create")["message"] == "missing config", "missing config")
            for name, value in (("null", None), ("bad id", {"id": "bad", "name": "x"}), ("no name", {"id": str(uuid.uuid4())}),
                                ("duplicate name", {"id": str(uuid.uuid4()), "name": "current"})):
                check(exchange(sock, "vm_create", config=value)["message"] == "internal error", "create " + name + " internal error")
            # modify
            changed = dict(legacy, name="renamed", memory_mb=1024)
            check(exchange(sock, "vm_modify", config=changed)["vm_id"] == legacy_id.lower(), "vm_modify returns canonical id")
            ids = [row["id"] for row in exchange(sock, "vm_list")["data"]]
            check(ids == [current_id, created_id.lower(), legacy_id.lower()], "modified VM moves to the end")
            check(exchange(sock, "vm_modify", config={"id": missing, "name": "x"})["message"] == "failed to modify VM", "modify unknown")
            check(exchange(sock, "vm_modify", config=dict(changed, name="current"))["message"] == "internal error", "rename collision")
            check(exchange(sock, "vm_get", vm_id=legacy_id)["data"]["name"] == "renamed", "failed rename keeps the previous entry")
            check(exchange(sock, "vm_modify")["message"] == "missing config", "modify missing config")
            # delete
            check(exchange(sock, "vm_delete", vm_id=created_id)["existed"] is True, "delete existing")
            check(exchange(sock, "vm_delete", vm_id=created_id)["existed"] is False, "delete is idempotent")
            check(exchange(sock, "vm_delete", vm_id="bad")["existed"] is False, "delete invalid id is a no-op")
            check(exchange(sock, "vm_delete")["message"] == "missing vm_id", "delete missing id")
        stop()
        check(file_hash() == before, "daemon never writes vms.json")
        port, token = start()
        with connect(port, token) as sock:
            check([row["name"] for row in exchange(sock, "vm_list")["data"]] == ["旧版", "current"], "restart reloads app-owned file")
        stop()
        # Whole-file failures start empty, as DataStore.load.
        bad = {"duplicate name": json.dumps({"vms": [legacy, dict(current, name="旧版")]}, ensure_ascii=False),
               "duplicate key": '{"vms": [], "vms": []}',
               "invalid state": json.dumps({"vms": [dict(current, state="running")]}),
               "missing name": json.dumps({"vms": [{"id": current_id}]}),
               "truncated": json.dumps({"vms": [legacy]})[:40]}
        for name, text in bad.items():
            seed(text)
            port, token = start()
            with connect(port, token) as sock:
                check(exchange(sock, "vm_list")["data"] == [], "load failure starts empty: " + name)
            stop()
        log = read(data + "/cache/daemon.log")
        check(log.count("VM inventory not loaded") == len(bad), "load failures are logged")
        shell(["rm", data + "/files/vms.json"], check=True)
        port, token = start()
        with connect(port, token) as sock:
            check(exchange(sock, "vm_list")["data"] == [], "missing file starts empty")
        stop()
        check(read(data + "/cache/daemon.log").count("VM inventory not loaded") == len(bad), "missing file is not a warning")
        report["passed"] = True
    finally:
        if active is not None:
            try:
                stop()
            except Exception as error:
                report["cleanup_error"] = str(error)
                report["passed"] = False
        for process in processes:
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
        for log in logs:
            log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            shell(["rm", "-f", data + "/run/droidvmd.lock", data + "/cache/daemon.log", data + "/files/vms.json",
                   data + "/run/network-runtime.json", data + "/run/network-runtime.json.bak",
                   binary_dir + "/droidvmd", binary_dir + "/seed.json"], check=True)
            shell(["rmdir", data + "/run", data + "/cache", data + "/files", data, binary_dir], check=True)
    assert report.get("passed"), report
    print("PASS: native VM inventory through legacy TCP commands,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
