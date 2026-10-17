# SPDX-License-Identifier: GPL-3.0-or-later
"""Run a real Gunyah crosvm VM through the native daemon's legacy vm_* commands."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import threading
import time
import urllib.parse
import uuid

from run_daemon_probe import frame, receive, request

PRODUCTION = "/data/data/cn.classfun.droidvm"


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    parser.add_argument("--display", action="store_true", help="Run the default native-display screen with input instead of the full lifecycle")
    parser.add_argument("--autostart", action="store_true", help="Auto-start an image-boot VM from vms.json; vm_bootscan and set_app_config")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-runtime-device" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/vmrt-{nonce[:8]}"
    if args.display:
        data = f"/data/local/tmp/dv{nonce[:6]}"  # input socket paths must fit sun_path
    binary_dir = f"/data/local/tmp/droidvm-vmrt-{nonce}"
    external = f"/storage/emulated/0/Android/data/{app}/files/vmrt-{nonce[:8]}"
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs, events = {"checks": []}, [], [], [], []
    active = None
    vm_id = str(uuid.uuid4())
    name = "rt" + nonce[:6]

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=60, **kwargs)

    def root_shell(command):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=60)

    def read(path):
        return shell(["cat", path], check=True).stdout.strip()

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    def start():
        nonlocal active
        log_path = output / f"daemon-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join([binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk])],
                                   stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 30
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
            time.sleep(0.05)
        raise TimeoutError("Daemon readiness")

    def stop(number=15):
        nonlocal active
        process, pid = active
        shell(["kill", f"-{number}", pid], check=True)
        assert process.wait(timeout=90) in (128 + number, -number, 255 if number == 9 else 128 + number) or number == 9
        active = None

    def connect(port, token):
        sock = socket.create_connection(("127.0.0.1", port), timeout=30)
        exchange(sock, "auth", token=token)
        return sock

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        while True:
            result = receive(sock)
            if result.get("type") == "event":
                events.append(result)
                continue
            assert result["request_id"] == value["request_id"], result
            return result

    def listen(port, token, stop_flag):
        sock = connect(port, token)
        sock.settimeout(0.5)
        while not stop_flag.is_set():
            try:
                message = receive(sock)
            except (socket.timeout, TimeoutError):
                continue
            except Exception:
                break
            if message.get("type") == "event":
                events.append(message)
        sock.close()

    def wait_state(sock, state, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = exchange(sock, "vm_status", vm_id=vm_id)
            if current.get("state") == state:
                return True
            time.sleep(0.25)
        return False

    def history(sock, stream):
        result = exchange(sock, "vm_console_history", vm_id=vm_id, stream=stream)
        return urllib.parse.unquote_to_bytes(result.get(stream, "")).decode("utf-8", "replace")

    def crosvm_argv():
        for pid in crosvm_pids():
            return root_shell(f"tr '\\0' '\\n' < /proc/{pid}/cmdline").stdout.splitlines()
        return []

    def crosvm_pids():
        out = root_shell(f"for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in *crosvm*--name\\ {name}\\ *) echo ${{p#/proc/}};; esac; done").stdout
        return [int(x) for x in out.split()]

    def pool_want():
        return root_shell("cat /sys/module/gh_hugepage_reserve/parameters/pool_want").stdout.strip()

    try:
        report["pool_want_before"] = pool_want()
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        if args.display:
            app_uid = shell(["stat", "-c", "%u", f"/data/user/0/{app}"], check=True).stdout.strip()
            assert root_shell(f"mkdir -p {data}/files {data}/bin && chown -R {app_uid}:{app_uid} {data} && chmod 700 {data}").returncode == 0
        else:
            shell(["run-as", app, "mkdir", "-p", data + "/files", data + "/bin"], check=True)
        uid = shell(["stat", "-c", "%u", data], check=True).stdout.strip()
        assert root_shell(f"ln -s {PRODUCTION}/usr {data}/usr && ln -s {PRODUCTION}/lib {data}/lib && ln -s {PRODUCTION}/bin/lbx {data}/bin/lbx && chown -h {uid}:{uid} {data}/usr {data}/lib {data}/bin/lbx").returncode == 0
        shell(["mkdir", binary_dir], check=True)
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60, capture_output=True)
        shell(["chmod", "755", binary_dir + "/droidvmd"], check=True)
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        config = {"id": vm_id, "name": name, "backend": "crosvm", "hypervisor": "auto", "memory_mb": 512, "cpu_count": 1,
                  "hugepages": True, "networks": [], "disks": [],
                  "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": False}},
                  "boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": PRODUCTION + "/usr/share/droidvm/vmlinuz",
                                                          "initrd": PRODUCTION + "/usr/share/droidvm/initramfs.img",
                                                          "cmdline": "console=ttyS0 earlycon"}}}
        if args.display:
            # New-VM default: simplefb exported natively with input.
            config["screens"] = {"gpu-0": {"enabled": False}, "simplefb": {"enabled": True, "exporter": "native", "input_enabled": True}}
            port, token = start()
            with connect(port, token) as sock:
                check(exchange(sock, "vm_create", config=config)["success"], "create native-display VM")
                check(exchange(sock, "vm_start", vm_id=vm_id)["success"], "start native-display VM")
                running = wait_state(sock, "running", 120)
                if not running:
                    report["stdio"] = history(sock, "stdio")[-3000:]
                check(running, "native-display VM reaches RUNNING")
                sockets = root_shell(f"ls {data}/run").stdout.split()
                report["sockets"] = sockets
                for tag in ("ms", "sfb_mt", "sfb_tab", "sfb_kbd"):
                    check(f"dvmin_{vm_id}_{tag}.sock" in sockets, "input socket " + tag)
                import base64
                event = base64.b64encode(bytes(24)).decode()
                delivered = False
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline and not delivered:
                    delivered = exchange(sock, "vm_input", vm_id=vm_id, screen="simplefb", channel=2, data=event).get("delivered")
                    time.sleep(0.25)
                check(delivered, "vm_input reaches crosvm's mouse device")
                check(exchange(sock, "vm_input", vm_id=vm_id, screen="simplefb", channel=1, data=event)["delivered"], "keyboard channel delivered")
                check(exchange(sock, "vm_input", vm_id=vm_id, screen="gpu-0", channel=0, data=event)["delivered"] is False, "no device on a disabled screen")
                check(exchange(sock, "vm_input", vm_id=str(uuid.uuid4()), channel=2, data=event)["message"].startswith("VM not found"), "vm_input unknown VM")
                check(exchange(sock, "vm_stop", vm_id=vm_id)["success"] and wait_state(sock, "stopped", 60), "stop native-display VM")
                check(not [s for s in root_shell(f"ls {data}/run").stdout.split() if s.startswith("dvmin_")], "input sockets removed")
            stop()
            report["passed"] = True
            print("PASS: native-display VM with the input bridge,", len(report["checks"]), "checks")
            return
        if args.autostart:
            # A guest disk on shared storage, booted through lbx like a real image VM.
            image = external + "/disk.img"
            script = (f"set -e; mkdir -p {external}/root/boot/grub; cp {PRODUCTION}/usr/share/droidvm/vmlinuz {external}/root/boot/vmlinuz-6.1.0-test; "
                      f"cp {PRODUCTION}/usr/share/droidvm/initramfs.img {external}/root/boot/initrd.img-6.1.0-test; "
                      f"printf 'set default=0\\nmenuentry \"Test Linux\" {{\\n linux /boot/vmlinuz-6.1.0-test root=/dev/vda console=ttyS0 earlycon\\n initrd /boot/initrd.img-6.1.0-test\\n}}\\n' > {external}/root/boot/grub/grub.cfg; "
                      f"mke2fs -q -t ext4 -d {external}/root {image} 96M; rm -r {external}/root")
            built = root_shell(script)
            assert built.returncode == 0, built.stderr
            auto = dict(config, auto_up=True, disks=[{"path": image, "readonly": True}],
                        boot={"protocol": "linux", "linux": {"source": "image", "image": {"disk": 0}}})
            idle_id = str(uuid.uuid4())
            idle = dict(config, id=idle_id, name=name + "-idle", auto_up=False)
            local = output / "vms.json"
            local.write_text(json.dumps({"vms": [idle, auto]}), encoding="utf-8")
            subprocess.run(adb + ["push", str(local), binary_dir + "/vms.json"], check=True, timeout=30, capture_output=True)
            assert root_shell(f"cp {binary_dir}/vms.json {data}/files/vms.json && chown {uid}:{uid} {data}/files/vms.json && chmod 600 {data}/files/vms.json").returncode == 0
            port, token = start()
            with connect(port, token) as sock:
                check(exchange(sock, "capabilities").get("vm_auto_up") is True, "capabilities advertise VM auto-start")
                check(wait_state(sock, "running", 120), "auto_up VM starts without vm_start")
                check(exchange(sock, "vm_status", vm_id=idle_id)["state"] == "stopped", "VM without auto_up stays stopped")
                argv = crosvm_argv()
                report["argv"] = argv
                media = image.replace("/storage/emulated/", "/data/media/", 1)
                disk = lambda path: any(a.split(",")[0] == path for a in argv)
                check(disk(media), "optimize_sdcard defaults on before the UI sends its config")
                boot_log = ""
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline and "Linux version" not in boot_log:
                    boot_log = history(sock, "serial1")
                    time.sleep(0.5)
                (output / "serial1.txt").write_text(boot_log, encoding="utf-8")
                check("Linux version" in boot_log and "root=/dev/vda" in boot_log, "guest boots the kernel and cmdline of the image entry")
                cached = root_shell(f"ls {data}/cache/boot/{vm_id}").stdout.split()
                report["boot_cache"] = cached
                check("initrd" in cached and len(cached) >= 3, "lbx extracted kernel and initrd into the boot cache")
                scan = exchange(sock, "vm_bootscan", image=image)
                report["bootscan"] = scan
                check(scan["success"] and [e["title"] for e in scan["entries"]] == ["Test Linux"] and scan["entries"][0]["default"] is True, "vm_bootscan lists the image entries")
                check(exchange(sock, "vm_bootscan")["message"] == "missing image", "vm_bootscan without an image")
                check(exchange(sock, "vm_bootscan", image=external + "/absent.img")["message"] == "internal error", "vm_bootscan of an unreadable image")
                check(exchange(sock, "set_app_config")["message"] == "internal error", "set_app_config without config")
                check(exchange(sock, "set_app_config", config=[1])["message"] == "internal error", "set_app_config with a non-object config")
                check(exchange(sock, "set_app_config", config={"optimize_sdcard": False, "usb_acm_enable": False})["success"], "set_app_config accepted")
                check(exchange(sock, "vm_stop", vm_id=vm_id)["success"] and wait_state(sock, "stopped", 60), "stop the auto-started VM")
                check(exchange(sock, "vm_start", vm_id=vm_id)["success"] and wait_state(sock, "running", 120), "restart after set_app_config")
                argv = crosvm_argv()
                check(disk(image) and not disk(media), "optimize_sdcard=false keeps the shared-storage path")
                check(exchange(sock, "vm_stop", vm_id=vm_id)["success"] and wait_state(sock, "stopped", 60) and not crosvm_pids(), "stop leaves no VMM")
            stop()
            port, token = start()
            with connect(port, token) as sock:
                check(wait_state(sock, "running", 120), "auto-start repeats on the next daemon start")
            # SIGTERM while the auto-started VM runs: stopAll takes it down with the daemon.
            stop()
            check(not crosvm_pids(), "daemon shutdown stops the auto-started VM")
            check(pool_want() == report["pool_want_before"], "reserve target restored")
            report["passed"] = True
            print("PASS: auto-started image-boot VM, vm_bootscan and set_app_config,", len(report["checks"]), "checks")
            return
        port, token = start()
        stop_flag = threading.Event()
        listener = threading.Thread(target=listen, args=(port, token, stop_flag), daemon=True)
        listener.start()
        with connect(port, token) as sock:
            cap = exchange(sock, "capabilities")
            check(cap.get("vm_runtime") is True and "vm_start" in cap["commands"], "capabilities advertise the crosvm runtime")
            check(exchange(sock, "vm_create", config=config)["vm_id"] == vm_id, "create runtime VM")
            check(exchange(sock, "vm_start", vm_id=vm_id, clear_logs_before_start=True)["success"], "vm_start accepted")
            check(not exchange(sock, "vm_start", vm_id=vm_id)["success"], "second start of a live VM refused")
            check(exchange(sock, "vm_modify", config=config)["message"] == "failed to modify VM", "modify refused while live")
            check(wait_state(sock, "running", 120), "VM reaches RUNNING")
            pids = crosvm_pids()
            check(len(pids) == 1 and exchange(sock, "vm_list")["data"][0]["pid"] == pids[0], "one crosvm process, pid published")
            report["pool_want_running"] = pool_want()
            check(int(report["pool_want_running"] or 0) >= 256, "huge-page reserve target covers the VM")
            boot_log = ""
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and "Linux version" not in boot_log:
                boot_log = history(sock, "serial1")
                time.sleep(0.5)
            (output / "serial1.txt").write_text(boot_log, encoding="utf-8")
            check("Linux version" in boot_log, "guest kernel prints to the app-console serial stream")
            check(exchange(sock, "vm_console_list", vm_id=vm_id)["data"] == ["stdout", "stderr", "stdio", "serial1"], "console stream list")
            info = exchange(sock, "vm_console_info", vm_id=vm_id, stream="serial1")["data"]
            check(info["type"] == "FDPipeConsoleStream" and info["readable"] and info["writable"] and info["ready"], "serial stream is a live pipe")
            check(exchange(sock, "vm_console_write", vm_id=vm_id, stream="serial1", data="\n")["success"], "write to the guest console")
            check(exchange(sock, "vm_console_write", vm_id=vm_id, stream="stdio", data="x")["message"] == "failed to write to stream", "stdio is read-only")
            check(exchange(sock, "vm_suspend", vm_id=vm_id)["success"] and exchange(sock, "vm_status", vm_id=vm_id)["state"] == "suspended", "suspend through the control socket")
            check(exchange(sock, "vm_resume", vm_id=vm_id)["success"] and exchange(sock, "vm_status", vm_id=vm_id)["state"] == "running", "resume through the control socket")
            check(exchange(sock, "vm_control", vm_id=vm_id, cmd="bogus")["message"] == "failed to send command", "unknown control command")
            first = crosvm_pids()
            check(exchange(sock, "vm_reboot", vm_id=vm_id)["success"], "reboot requested")
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and (crosvm_pids() in ([], first) or exchange(sock, "vm_status", vm_id=vm_id)["state"] != "running"):
                time.sleep(0.25)
            second = crosvm_pids()
            check(len(second) == 1 and second != first, "reboot relaunches a new VMM process")
            check(exchange(sock, "vm_stop", vm_id=vm_id)["success"], "stop requested")
            check(wait_state(sock, "stopped", 60) and not crosvm_pids(), "stop leaves no VMM")
            check(pool_want() == report["pool_want_before"], "reserve target returns after stop")
            exited = [e["data"] for e in events if e["data"].get("event") == "exited" and e["data"].get("vm_id") == vm_id]
            check(exited and exited[-1]["exit_code"] == 0 and exited[-1]["state"] == "stopped", "exited event after the user stop")
            states = [e["data"]["state"] for e in events if e["data"].get("event") == "state" and e["data"].get("vm_id") == vm_id]
            for wanted in ("starting", "running", "suspended", "rebooting", "stopping", "stopped"):
                check(wanted in states, "state event " + wanted)
            lifecycle = read(data + "/cache/daemon.log")
            for line in (f"VM {vm_id} ({name}): running pid=", f"VM {vm_id} ({name}): rebooting",
                         f"VM {vm_id} ({name}): stopping", f"VM {vm_id} ({name}): exited code=0"):
                check(line in lifecycle, "daemon log records " + line.split(": ", 1)[1].strip())
            outputs = [e for e in events if e["data"].get("event") == "output" and e["data"].get("stream") == "serial1"]
            check(outputs, "output events carry the serial stream")
            # Daemon death: the orphaned VMM is reclaimed by its helper identity.
            check(exchange(sock, "vm_start", vm_id=vm_id)["success"] and wait_state(sock, "running", 120), "restart for crash recovery")
        orphan = crosvm_pids()
        stop_flag.set()
        listener.join(timeout=5)
        stop(9)
        time.sleep(1)
        check(orphan and crosvm_pids() == orphan, "killed daemon orphaned its VMM")
        port, token = start()
        check(not crosvm_pids(), "startup reclaims the orphaned VMM")
        check(pool_want() == report["pool_want_before"], "startup drops the reserve target this daemon recorded")
        with connect(port, token) as sock:
            check(exchange(sock, "vm_list")["data"] == [], "restarted daemon does not adopt the VM")
        stop()
        log_text = read(data + "/cache/daemon.log")
        report["persistent_log"] = root_shell(f"ls {data}/cache").stdout.split()
        check(any(item.startswith(f"console_{vm_id}_serial1") for item in report["persistent_log"]), "persistent console log written")
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
        report["leftover_vmm"] = crosvm_pids()
        report["pool_want_after"] = pool_want()
        report["events"] = len(events)
        (output / "events.json").write_text(json.dumps(events[-400:], indent=1, ensure_ascii=False), encoding="utf-8")
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)
        if report.get("passed") and not report["leftover_vmm"]:
            root_shell(f"rm -r {data} {binary_dir}")
            if args.autostart:
                root_shell(f"rm -r {external}")
    assert report.get("passed"), report
    if args.display or args.autostart:
        return
    print("PASS: real crosvm VM lifecycle through the native daemon,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
