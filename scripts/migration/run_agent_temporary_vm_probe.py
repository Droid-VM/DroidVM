# SPDX-License-Identifier: GPL-3.0-or-later
"""Agent temporary VM (crosvm or QEMU/TCG) under --configuration-owner: create, boot to the rescue shell, delete, never persisted."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import time
import urllib.parse
import uuid

from run_daemon_probe import frame, receive, request

# AgentVM.buildVM per backend: (hypervisor, operation console stream, guest device).
BACKENDS = {"crosvm": ("auto", "serial1", "ttyS0"), "qemu": ("soft", "uart", "ttyAMA0")}


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    parser.add_argument("--backend", choices=BACKENDS, default="crosvm")
    args = parser.parse_args()
    hypervisor, stream, device = BACKENDS[args.backend]
    nonce = uuid.uuid4().hex
    output = root / "build/migration/agent-temporary-vm" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    # QEMU sockets live in <data>/run: keep the path within sun_path.
    data = f"/data/user/0/{app}/cache/a{nonce[:8]}"
    binary_dir = f"/data/local/tmp/droidvm-agent-{nonce}"
    images = data + "/files/images"
    production = "/data/data/cn.classfun.droidvm"
    adb = ["adb", "-s", args.serial]
    report, forwards = {"checks": [], "backend": args.backend}, []
    process, log, pid = None, None, None

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=45, **kwargs)

    def root_shell(command, **kwargs):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=45, **kwargs)

    def exchange(sock, verb, **fields):
        value = request(verb, **fields)
        sock.sendall(frame(value))
        while True:
            result = receive(sock)
            if result.get("request_id") == value["request_id"]:
                return result

    def check(condition, name):
        report["checks"].append(name)
        assert condition, name

    def console(sock, vm_id):
        history = exchange(sock, "vm_console_history", vm_id=vm_id, stream=stream)
        return urllib.parse.unquote(history.get(stream, ""), errors="replace")

    def wait_console(sock, vm_id, pattern, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            text = console(sock, vm_id)
            if re.search(pattern, text):
                return text
            state = exchange(sock, "vm_status", vm_id=vm_id).get("state")
            if state not in ("starting", "running"):
                history = exchange(sock, "vm_console_history", vm_id=vm_id)
                for name, value in history.items():
                    if isinstance(value, str) and name not in ("type", "request_id", "vm_id"):
                        (output / f"console-{name}.txt").write_text(urllib.parse.unquote(value, errors="replace"), encoding="utf-8")
                raise AssertionError(f"VM left running: {state}")
            time.sleep(0.5)
        raise TimeoutError(pattern)

    try:
        shell(["run-as", app, "mkdir", "-p", data + "/files"], check=True)
        uid = shell(["run-as", app, "id", "-u"], check=True).stdout.strip()
        shell(["mkdir", binary_dir], check=True)
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60)
        shell(["chmod", "755", binary_dir + "/droidvmd"], check=True)
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().splitlines()[0].removeprefix("package:")
        # The installed app's tools and agent kernel/initramfs, read-only.
        root_shell(f"echo '{{\"vms\":[]}}' > {data}/files/vms.json && mkdir -p {images} && ln -s {production}/usr {data}/usr && "
                   f"ln -s {production}/lib {data}/lib && mkdir -p {data}/bin && ln -s {production}/bin/lbx {data}/bin/lbx && "
                   f"chown -h {uid}:{uid} {data}/usr {data}/lib {data}/bin/lbx && "
                   f"chown -R {uid}:{uid} {data}/files && chmod 600 {data}/files/vms.json", check=True)

        log_path = output / "daemon.txt"
        log = log_path.open("w", encoding="utf-8")
        argv = [binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk, "--configuration-owner"]
        process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(shlex.join(argv))], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 20
        while True:
            assert process.poll() is None, log_path.read_text(encoding="utf-8")
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", log_path.read_text(encoding="utf-8"))
            if ready: break
            assert time.monotonic() < deadline, "daemon readiness"
            time.sleep(0.05)
        pid, port = ready.groups()
        token = root_shell(f"cat {data}/run/droidvmd-token.txt", check=True).stdout.strip()
        local = int(subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True,
                                   text=True, timeout=10).stdout.strip())
        forwards.append(local)

        with socket.create_connection(("127.0.0.1", local), timeout=30) as sock:
            assert exchange(sock, "auth", token=token)["success"]
            revision = exchange(sock, "disk_config_status")["revision"]
            started = exchange(sock, "disk_operation_start", disk_revision=revision, action="create",
                               folder=images, name="target.raw", format="raw", size=16 << 20)
            assert started.get("success"), started
            while True:
                task = exchange(sock, "task_get", epoch=started["epoch"], task_id=started["task_id"])["task"]
                if task["state"] in ("succeeded", "failed", "cancelled"): break
                time.sleep(0.05)
            check(task["state"] == "succeeded", "target disk registered")
            persisted = root_shell(f"cat {data}/files/vms.json", check=True).stdout

            # AgentVM.buildVM shape.
            vm_id = str(uuid.uuid4())
            config = {
                "id": vm_id, "name": "Agent-" + nonce[:8], "temporary": True, "agent_mode": False,
                "backend": args.backend, "hypervisor": hypervisor, "cpu_count": 1, "memory_mb": 320,
                "hugepages": False, "rng": False, "balloon": False, "audio_enabled": False,
                "boot": {"protocol": "linux", "linux": {"source": "manual",
                         "kernel": production + "/usr/share/droidvm/vmlinuz",
                         "initrd": production + "/usr/share/droidvm/initramfs.img",
                         "cmdline": f"console={device} rdinit=/bin/sh panic=-1"}},
                "disks": [{"path": images + "/target.raw", "bus": "virtio"}],
                "networks": [],
            }
            created = exchange(sock, "vm_create", config=config)
            check(created.get("success") and created.get("vm_id") == vm_id, f"owner accepts temporary vm_create: {created}")
            check(root_shell(f"cat {data}/files/vms.json", check=True).stdout == persisted, "temporary VM is not persisted")
            check(exchange(sock, "vm_create", config={**config, "id": str(uuid.uuid4()), "temporary": False})["success"] is False,
                  "a persistent vm_create is still refused")
            began = time.monotonic()
            begun = exchange(sock, "vm_start", vm_id=vm_id)
            check(begun.get("success"), f"temporary VM starts: {begun}")
            text = wait_console(sock, vm_id, r"~ #|Run /bin/sh as init process", 180)
            report["boot_seconds"] = round(time.monotonic() - began, 1)
            check(True, "guest reaches the rescue shell")
            exchange(sock, "vm_console_write", vm_id=vm_id, stream=stream, data="echo AGENT_$((40+2))_OK\n")
            text = wait_console(sock, vm_id, r"AGENT_42_OK", 30)
            check(True, "guest shell executes a command")
            (output / "console.txt").write_text(text, encoding="utf-8")
            exchange(sock, "vm_stop", vm_id=vm_id)
            deadline = time.monotonic() + 30
            while exchange(sock, "vm_status", vm_id=vm_id).get("state") != "stopped":
                assert time.monotonic() < deadline, "VM stops"
                time.sleep(0.2)
            deleted = exchange(sock, "vm_delete", vm_id=vm_id)
            check(deleted.get("success") and deleted.get("existed"), f"owner deletes the temporary VM: {deleted}")
            check(not any(vm.get("id") == vm_id for vm in exchange(sock, "vm_list")["data"])
                  and root_shell(f"cat {data}/files/vms.json", check=True).stdout == persisted, "nothing left behind")
        report["passed"] = True
    except BaseException as error:
        report["passed"] = False
        report["error"] = repr(error)
        raise
    finally:
        if pid:
            root_shell("kill -15 " + pid)
        if process:
            try: process.wait(timeout=30)
            except Exception: process.kill()
        if log: log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        assert data.endswith(nonce[:8]) and binary_dir.endswith(nonce)
        root_shell(f"rm -f {data}/usr {data}/lib {data}/bin/lbx")
        report["cleanup_complete"] = root_shell(f"rm -r {data} {binary_dir}").returncode == 0
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)


if __name__ == "__main__":
    main()
