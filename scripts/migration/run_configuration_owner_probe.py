# SPDX-License-Identifier: GPL-3.0-or-later
"""Production droidvmd with --configuration-owner in an isolated test-app data directory."""
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
    output = root / "build/migration/configuration-owner" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/owner-{nonce}"
    binary_dir = f"/data/local/tmp/droidvm-owner-{nonce}"
    images = data + "/files/images"
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs = {"checks": []}, [], [], []
    active = None

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=45, **kwargs)

    def root_shell(command, **kwargs):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=45, **kwargs)

    def read(path):
        return root_shell("cat " + shlex.quote(path), check=True).stdout.strip()

    def command(owner=True):
        argv = [binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk]
        return argv + (["--configuration-owner"] if owner else [])

    def start(owner=True):
        nonlocal active
        log_path = output / f"daemon-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(shlex.join(command(owner)))],
                                   stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 20
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
                return local, token, pid
            time.sleep(0.025)
        raise TimeoutError("Daemon readiness")

    def stop():
        nonlocal active
        process, pid = active
        root_shell("kill -15 " + pid, check=True)
        assert process.wait(timeout=45) == 128 + 15
        active = None

    def connect(port, token):
        sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        assert exchange(sock, "auth", token=token)["success"]
        return sock

    def exchange(sock, verb, **fields):
        value = request(verb, **fields)
        sock.sendall(frame(value))
        result = receive(sock)
        assert result["request_id"] == value["request_id"], result
        return result

    def check(condition, name):
        report["checks"].append(name)
        assert condition, name

    def finish(sock, started):
        assert started.get("success"), started
        deadline = time.monotonic() + 30
        while True:
            task = exchange(sock, "task_get", epoch=started["epoch"], task_id=started["task_id"])["task"]
            if task["state"] in ("succeeded", "failed", "cancelled"):
                return task
            assert time.monotonic() < deadline, "disk operation completes"
            time.sleep(.02)

    vm_id = str(uuid.uuid4())
    seeded = {"vms": [{"id": vm_id, "name": "接管 VM", "backend": "crosvm", "boot": {"protocol": "uefi"},
                       "disks": [], "future": {"integer": 9007199254740993}}], "root_extension": True}
    try:
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["run-as", app, "mkdir", "-p", data + "/files"], check=True)
        uid = shell(["run-as", app, "id", "-u"], check=True).stdout.strip()
        shell(["mkdir", binary_dir], check=True)
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=30)
        shell(["chmod", "755", binary_dir + "/droidvmd"], check=True)
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        source = output / "vms.json"
        source.write_text(json.dumps(seeded, ensure_ascii=False), encoding="utf-8")
        subprocess.run(adb + ["push", str(source), binary_dir + "/vms.json"], check=True, timeout=20)
        installed = "/data/data/cn.classfun.droidvm/usr/bin/qemu-img"
        root_shell(f"cp {binary_dir}/vms.json {data}/files/vms.json && mkdir -p {data}/usr/bin {images} && "
                   f"cp {installed} {data}/usr/bin/qemu-img && chown -R {uid}:{uid} {data}/files {data}/usr && "
                   f"chmod 600 {data}/files/vms.json", check=True)

        port, token, pid = start()
        with connect(port, token) as sock:
            caps = exchange(sock, "capabilities")
            check(caps["service_api_minor"] >= 25 and caps["configuration_vm_disks"] == "revisioned-chunks-v1"
                  and caps["disk_operations"] == "revisioned-tasks-v1" and caps.get("vm_config_start") == "revisioned-session-v1",
                  "owner mode advertises coordinated configuration, disk operations and configured start")
            listed = exchange(sock, "vm_list")
            check(listed["success"] and any(vm.get("id") == vm_id for vm in listed["data"]), "coordinator loads the seeded VM inventory")
            check(exchange(sock, "vm_config_status")["count"] == 1 and exchange(sock, "disk_config_status")["count"] == 0,
                  "VM and disk registries are served natively")
            revision = exchange(sock, "disk_config_status")["revision"]
            task = finish(sock, exchange(sock, "disk_operation_start", disk_revision=revision, action="create",
                                         folder=images, name="owner.raw", format="raw", size=1048576))
            disk_id = task.get("result", {}).get("disk_id")
            check(task["state"] == "succeeded" and "owner.raw" in root_shell("ls " + images).stdout,
                  "disk create runs through the production entry point")
        maps = root_shell(f"cat /proc/{pid}/maps", check=True).stdout
        check("libart.so" not in maps and "libjvm" not in maps, "owner daemon has no ART/JVM")
        stop()
        rows = json.loads(read(data + "/files/disks.json"))["disks"]
        vms = json.loads(read(data + "/files/vms.json"))
        check(any(row["id"] == disk_id for row in rows) and vms["vms"][0]["future"]["integer"] == 9007199254740993
              and vms.get("root_extension") is True, "registries persist with extensions after SIGTERM")

        refused = root_shell(shlex.join(command(owner=False)))
        check(refused.returncode != 0, "ownership is one-way: legacy-mode start is refused")
        (output / "legacy-refusal.txt").write_text(refused.stdout + refused.stderr, encoding="utf-8")

        port, token, pid = start()
        with connect(port, token) as sock:
            revision = exchange(sock, "disk_config_status")["revision"]
            task = finish(sock, exchange(sock, "disk_operation_start", disk_revision=revision, disk_id=disk_id,
                                         action="delete", delete_files=True))
            check(task["state"] == "succeeded" and "owner.raw" not in root_shell("ls " + images).stdout
                  and exchange(sock, "disk_operation_recover")["pending"] == 0, "restart keeps disk ownership and operations")
        stop()
        report["passed"] = True
    except BaseException as error:
        report["passed"] = False
        report["error"] = repr(error)
        raise
    finally:
        if active:
            try: root_shell("kill -9 " + active[1])
            except Exception: pass
        for process in processes:
            try: process.wait(timeout=10)
            except Exception: process.kill()
        for log in logs: log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        assert data.endswith(nonce) and binary_dir.endswith(nonce)
        cleaned = root_shell(f"rm -r {data} {binary_dir}")
        report["cleanup_complete"] = cleaned.returncode == 0
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)


if __name__ == "__main__":
    main()
