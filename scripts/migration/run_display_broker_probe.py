# SPDX-License-Identifier: GPL-3.0-or-later
"""display_attach: the native daemon broadcasts its display broker to the app and serves it."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shlex
import socket
import subprocess
import threading
import time
import uuid

from run_daemon_probe import frame, receive, request

PRODUCTION = "/data/data/cn.classfun.droidvm"
APP = "cn.classfun.droidvm.migration.test"


def main():
    root = Path(__file__).resolve().parents[2]
    build = root / "build/migration/standalone-arm64-v8a"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=build / "droidvm_keyboard_fixture")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/display-broker-device" / nonce
    output.mkdir(parents=True)
    data = f"/data/local/tmp/dv{nonce[:6]}"  # input socket paths must fit sun_path
    binary_dir = f"/data/local/tmp/droidvm-broker-{nonce}"
    adb = ["adb", "-s", args.serial]
    report = {"checks": []}
    running_vm, stopped_vm = str(uuid.uuid4()), str(uuid.uuid4())
    name = "db" + nonce[:6]
    daemon = test = None
    pid = local = None

    def shell(argv):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, capture_output=True, timeout=60)

    def root_shell(command):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, capture_output=True, timeout=60)

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        while True:
            result = receive(sock)
            if result.get("type") == "event":
                continue
            assert result["request_id"] == value["request_id"], result
            return result

    def state(sock, vm):
        return exchange(sock, "vm_status", vm_id=vm).get("state")

    def vmm_pids():
        out = root_shell(f"for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in *crosvm*--name\\ {name}*) echo ${{p#/proc/}};; esac; done").stdout
        return out.split()

    def pool_want():
        return root_shell("cat /sys/module/gh_hugepage_reserve/parameters/pool_want").stdout.strip()

    log_path = output / "daemon.txt"
    try:
        report["pool_want_before"] = pool_want()
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        uid = shell(["stat", "-c", "%u", f"/data/user/0/{APP}"]).stdout.strip()
        assert root_shell(f"mkdir -p {data}/files {data}/bin && chown -R {uid}:{uid} {data} && chmod 700 {data}").returncode == 0
        assert root_shell(f"ln -s {PRODUCTION}/usr {data}/usr && ln -s {PRODUCTION}/lib {data}/lib && ln -s {PRODUCTION}/bin/lbx {data}/bin/lbx && chown -h {uid}:{uid} {data}/usr {data}/lib {data}/bin/lbx").returncode == 0
        assert root_shell(f"mkdir {binary_dir}").returncode == 0
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60, capture_output=True)
        root_shell(f"chmod 755 {binary_dir}/droidvmd")
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"]).stdout.strip().removeprefix("package:")
        assert apk.endswith(".apk"), "production APK path"
        # No keyboard exists by this name; the broadcast goes to the test package.
        command = [binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk, "--test-keyboard-name", "droidvm-no-keyboard-" + nonce[:8],
                   "--test-display-package", APP]
        log = log_path.open("w", encoding="utf-8")
        daemon = subprocess.Popen(adb + ["shell", shlex.join(command)], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        ready = None
        while time.monotonic() < deadline and not ready:
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", log_path.read_text(encoding="utf-8"))
            time.sleep(0.05)
        check(ready is not None, "test daemon ready")
        pid, port = ready.groups()
        token = root_shell(f"cat {data}/run/droidvmd-token.txt").stdout.strip()
        local = int(subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True).stdout.strip())
        with socket.create_connection(("127.0.0.1", local), timeout=60) as sock:
            exchange(sock, "auth", token=token)
            cap = exchange(sock, "capabilities")
            check(cap.get("native_display_binder") is True and "display_attach" in cap["commands"], "capabilities advertise display_attach")
            check(exchange(sock, "display_attach")["message"] == "missing nonce", "display_attach without a nonce")
            base = {"backend": "crosvm", "hypervisor": "auto", "memory_mb": 512, "cpu_count": 1, "hugepages": True, "networks": [], "disks": [],
                    "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": True, "exporter": "native", "input_enabled": True,
                                                                         "width": 320, "height": 240}},  # CrosvmDisplayContract size
                    "boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": PRODUCTION + "/usr/share/droidvm/vmlinuz",
                                                            "initrd": PRODUCTION + "/usr/share/droidvm/initramfs.img", "cmdline": "console=ttyS0 earlycon"}}}
            check(exchange(sock, "vm_create", config=dict(base, id=running_vm, name=name))["success"], "create native-display VM")
            check(exchange(sock, "vm_create", config=dict(base, id=stopped_vm, name=name + "s"))["success"], "create stopped VM")
            check(exchange(sock, "vm_start", vm_id=running_vm)["success"], "start native-display VM")
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline and state(sock, running_vm) != "running":
                time.sleep(0.25)
            check(state(sock, running_vm) == "running", "VM reaches RUNNING")
            instrument = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "broker_nonce", nonce, "-e", "running_vm", running_vm,
                                "-e", "stopped_vm", stopped_vm, "-e", "class", "cn.classfun.droidvm.migration.DaemonDisplayBrokerTest",
                                f"{APP}/androidx.test.runner.AndroidJUnitRunner"]
            test = subprocess.Popen(instrument, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
            lines = queue.Queue()
            threading.Thread(target=lambda: ([lines.put(line) for line in test.stdout], lines.put(None)), daemon=True).start()
            transcript = []
            deadline = time.monotonic() + 120
            with (output / "instrumentation.txt").open("w", encoding="utf-8") as out:
                while time.monotonic() < deadline:
                    try:
                        line = lines.get(timeout=1)
                    except queue.Empty:
                        continue
                    if line is None:
                        break
                    transcript.append(line); out.write(line); out.flush()
                    if line.strip() == f"INSTRUMENTATION_STATUS: broker_ready={nonce}":
                        check(exchange(sock, "display_attach", nonce=nonce)["success"], "display_attach accepted")
                    elif line.strip() == "INSTRUMENTATION_STATUS: token_grab=armed":
                        status = exchange(sock, "kbd_status")
                        report["status_armed"] = status
                        check(status["armed"] is True and status.get("vm_id") == running_vm and status["held"] == [], "token grab armed for the VM")
                    elif line.strip() == "INSTRUMENTATION_STATUS: token_grab=dead":
                        check(exchange(sock, "kbd_status")["armed"] is False, "token death releases the grab")
            test.wait(timeout=10)
            text = "".join(transcript)
            check(test.returncode == 0 and "OK (1 test)" in text, "Java broker contract passed")
            check("INSTRUMENTATION_STATUS: real_display_frame_received=true" in text, "real crosvm display binder through the broker")
            check(exchange(sock, "vm_stop", vm_id=running_vm)["success"], "stop VM")
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and state(sock, running_vm) != "stopped":
                time.sleep(0.25)
            check(not vmm_pids(), "no VMM left")
        daemon_log = root_shell(f"cat {data}/cache/daemon.log").stdout
        (output / "daemon.log").write_text(daemon_log, encoding="utf-8")
        check(f"broadcast native-display binder (nonce={nonce})" in daemon_log, "broadcast logged")
        root_shell(f"kill -15 {pid}")
        daemon.wait(timeout=60)
        check(pool_want() == report["pool_want_before"], "reserve target restored")
        report["passed"] = True
    finally:
        if test and test.poll() is None:
            test.kill()
        if pid and daemon and daemon.poll() is None:
            root_shell(f"kill -15 {pid}")
            try:
                daemon.wait(timeout=60)
            except subprocess.TimeoutExpired:
                daemon.kill()
        if local:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        report["leftover_vmm"] = vmm_pids()
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed") and not report["leftover_vmm"]:
            root_shell(f"rm -r {data} {binary_dir}")
    print("PASS: native display broker,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
