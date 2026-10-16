# SPDX-License-Identifier: GPL-3.0-or-later
"""usb_* commands through the native daemon over a fake sysfs root (never the host's)."""
import argparse
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import time
import uuid

from run_daemon_probe import frame, receive, request

APP = "cn.classfun.droidvm.migration.test"


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvm_keyboard_fixture")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/usb-daemon-device" / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    data = f"/data/user/0/{APP}/cache/usbd-{nonce[:8]}"
    binary_dir = f"/data/local/tmp/droidvm-usbd-{nonce}"
    fake = binary_dir + "/sys"
    report = {"checks": []}
    daemon = pid = local = None

    def shell(command):
        return subprocess.run(adb + ["shell", command], text=True, capture_output=True, timeout=60)

    def root_shell(command):
        return shell("su -c " + shlex.quote(command))

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

    def start(tag):
        nonlocal daemon, pid, local
        log_path = output / f"daemon-{tag}.txt"
        log = log_path.open("w", encoding="utf-8")
        command = [binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk, "--test-keyboard-name", "droidvm-no-keyboard-" + nonce[:8],
                   "--test-usb-sys", fake]
        daemon = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(shlex.join(command))], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        ready = None
        while time.monotonic() < deadline and not ready:
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", log_path.read_text(encoding="utf-8"))
            time.sleep(0.05)
        assert ready, log_path.read_text(encoding="utf-8")
        pid, port = ready.groups()
        local = int(subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True).stdout.strip())
        sock = socket.create_connection(("127.0.0.1", local), timeout=60)
        exchange(sock, "auth", token=root_shell(f"cat {data}/run/droidvmd-token.txt").stdout.strip())
        return sock

    def stop():
        root_shell(f"kill -15 {pid}")
        daemon.wait(timeout=60)
        subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)

    try:
        real_autoprobe = root_shell("cat /sys/bus/usb/drivers_autoprobe").stdout.strip()
        report["real_autoprobe_before"] = real_autoprobe
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        assert shell(shlex.join(["run-as", APP, "mkdir", "-p", data + "/files", data + "/bin"])).returncode == 0
        # An empty bus: no devices, no drivers; the fake knobs start as the kernel's default.
        assert root_shell(f"mkdir -p {fake}/bus/usb/devices {fake}/bus/usb/drivers {fake}/dev/bus/usb && echo 1 > {fake}/bus/usb/drivers_autoprobe && touch {fake}/bus/usb/drivers_probe").returncode == 0
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60, capture_output=True)
        root_shell(f"chmod 755 {binary_dir}/droidvmd")
        apk = shell("pm path cn.classfun.droidvm").stdout.strip().removeprefix("package:")
        assert apk.endswith(".apk"), "production APK path"
        vm_id = str(uuid.uuid4())
        with start("0") as sock:
            cap = exchange(sock, "capabilities")
            check(cap.get("usb_passthrough") is True and all(name in cap["commands"] for name in (
                "usb_attach", "usb_detach", "usb_host_list", "usb_rules_get", "usb_rules_set", "usb_rules_test", "usb_set_target", "usb_vm_list")), "capabilities advertise USB")
            check(exchange(sock, "usb_host_list")["devices"] == [], "empty host list")
            rules = exchange(sock, "usb_rules_get")["rules"]
            report["default_rules"] = rules
            check(rules["enabled"] is True and rules["version"] == 1, "default rules")
            check(root_shell(f"cat {fake}/bus/usb/drivers_autoprobe").stdout.strip() == "0", "enabled rules turn the fake autoprobe off")
            check(exchange(sock, "usb_attach")["message"] == "missing vm_id", "attach without a VM")
            check(exchange(sock, "usb_attach", vm_id=vm_id)["message"] == f"VM not found: {vm_id}", "attach to an unknown VM")
            check(exchange(sock, "usb_detach", vm_id=vm_id)["message"] == f"VM not found: {vm_id}", "detach from an unknown VM")
            check(exchange(sock, "usb_vm_list")["message"] == "missing vm_id", "vm list without a VM")
            check(exchange(sock, "usb_set_target", device="1-1", target="cloud")["message"] == "unknown target: cloud", "unknown target")
            check(exchange(sock, "usb_set_target", device="1-1", target="host")["message"] == "no such USB device: 1-1", "set target of a missing device")
            check(exchange(sock, "usb_rules_set")["message"] == "missing rules", "rules set without rules")
            applied = exchange(sock, "usb_rules_set", rules={"version": 1, "enabled": False})
            check(applied["success"] and applied["applied"] == 0, "rules set applies to nothing")
            check(root_shell(f"cat {fake}/bus/usb/drivers_autoprobe").stdout.strip() == "1", "disabled rules give autoprobe back")
            tested = exchange(sock, "usb_rules_test")
            check(tested["success"] and tested["enabled"] is False and tested["devices"] == [], "rules test shape")
            check(exchange(sock, "vm_create", config={"id": vm_id, "name": "usb" + nonce[:6], "backend": "crosvm"})["success"], "create a VM")
            listed = exchange(sock, "usb_vm_list", vm_id=vm_id)
            check(listed["success"] and listed["vm_id"] == vm_id and listed["devices"] == [], "empty VM attachment list")
            check(exchange(sock, "usb_attach", vm_id=vm_id)["message"] == "missing device", "attach without a device")
            check(exchange(sock, "usb_attach", vm_id=vm_id, device="9-9")["message"] != "", "attach of a missing device is refused")
        stop()
        stored = root_shell(f"cat {data}/files/usb_rules.json").stdout
        report["stored_rules"] = stored
        check('"enabled":false' in stored.replace(" ", ""), "rules persisted in files/usb_rules.json")
        with start("1") as sock:
            check(exchange(sock, "usb_rules_get")["rules"]["enabled"] is False, "rules survive a restart")
        stop()
        log_text = root_shell(f"cat {data}/cache/daemon.log").stdout
        (output / "daemon.log").write_text(log_text, encoding="utf-8")
        check(root_shell("cat /sys/bus/usb/drivers_autoprobe").stdout.strip() == real_autoprobe, "the host's drivers_autoprobe is untouched")
        report["passed"] = True
    finally:
        if daemon and daemon.poll() is None and pid:
            root_shell(f"kill -15 {pid}")
            try:
                daemon.wait(timeout=60)
            except subprocess.TimeoutExpired:
                daemon.kill()
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            root_shell(f"rm -r {data} {binary_dir}")
    print("PASS: usb commands through the native daemon,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
