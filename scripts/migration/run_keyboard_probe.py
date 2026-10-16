# SPDX-License-Identifier: GPL-3.0-or-later
"""Physical keyboard grab (kbd_*) on a uinput test keyboard and a real crosvm VM."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shlex
import socket
import struct
import subprocess
import threading
import time
import urllib.parse
import uuid

from run_daemon_probe import frame, receive, request

PRODUCTION = "/data/data/cn.classfun.droidvm"
EBUSY = 16
# Escape chord Ctrl+Alt: both downs happen while grabbed, so Android only ever
# sees their ups, which InputReader drops for keys it never saw down.
CTRL, ALT = 29, 56


def main():
    root = Path(__file__).resolve().parents[2]
    build = root / "build/migration/standalone-arm64-v8a"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=build / "droidvm_keyboard_fixture")
    parser.add_argument("--helper", type=Path, default=build / "droidvm_uinput_keyboard")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/keyboard-device" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/local/tmp/dv{nonce[:6]}"  # input socket paths must fit sun_path
    binary_dir = f"/data/local/tmp/droidvm-kbd-{nonce}"
    helper = binary_dir + "/droidvm_uinput_keyboard"
    keyboard = "droidvm-kbd-test-" + nonce[:8]
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs, devices = {"checks": []}, [], [], [], []
    active = None
    vm_id = str(uuid.uuid4())
    name = "kb" + nonce[:6]
    guest_name = "DroidVM Keyboard (simplefb)"

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=60, **kwargs)

    def root_shell(command):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=60)

    def read(path):
        return root_shell("cat " + shlex.quote(path)).stdout.strip()

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    class Keyboard:
        """The uinput test keyboard, driven over the helper's stdin."""
        def __init__(self):
            self.lines = queue.Queue()
            self.process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(shlex.join([helper, "create", keyboard]))],
                                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.leds = []
            threading.Thread(target=self.pump, daemon=True).start()
            ready = self.expect(lambda line: line.startswith("ready ") or line.startswith("error"), 15)
            assert ready.startswith("ready /dev/input/event"), ready
            self.node = ready.split()[1]
            devices.append(self)

        def pump(self):
            for raw in self.process.stdout:
                line = raw.decode("utf-8", "replace").strip()
                if line.startswith("led "):
                    self.leds.append(tuple(int(x) for x in line.split()[1:]))
                self.lines.put(line)

        def expect(self, match, timeout=10):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    line = self.lines.get(timeout=max(0.05, deadline - time.monotonic()))
                except queue.Empty:
                    break
                if match(line):
                    return line
            raise TimeoutError("uinput helper")

        def send(self, command):
            self.process.stdin.write((command + "\n").encode())
            self.process.stdin.flush()
            if command != "q":
                assert self.expect(lambda line: line in ("ok", "error emit")) == "ok", command

        def key(self, code, value):
            self.send(f"k {code} {value}")

        def tap(self, code):
            self.key(code, 1); self.key(code, 0)

        def close(self):
            if self.process.poll() is None:
                try:
                    self.send("q")
                    self.expect(lambda line: line == "destroyed", 10)
                except Exception:
                    pass
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
            if self in devices:
                devices.remove(self)

    def observe(node, ms, action):
        """What another reader of the node gets while action runs."""
        process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(shlex.join([helper, "observe", node, keyboard, str(ms)]))],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert process.stdout.readline().strip() == "watching"
        action()
        out, _ = process.communicate(timeout=ms / 1000 + 20)
        seen = [line for line in out.splitlines() if line.startswith("seen ")]
        assert seen, out
        return json.loads(seen[0][5:])

    def test_devices():
        out = root_shell("for d in /sys/class/input/input*; do [ \"$(cat $d/name)\" = " + shlex.quote(keyboard) + " ] && echo $d; done").stdout
        return out.split()

    def start():
        nonlocal active
        log_path = output / f"daemon-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join([binary_dir + "/droidvmd", "--data-dir", data, "--apk", apk, "--test-keyboard-name", keyboard])],
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

    def stop():
        nonlocal active
        process, pid = active
        shell(["kill", "-15", pid], check=True)
        process.wait(timeout=90)
        active = None

    def connect(port, token):
        sock = socket.create_connection(("127.0.0.1", port), timeout=60)
        exchange(sock, "auth", token=token)
        return sock

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        while True:
            result = receive(sock)
            if result.get("type") == "event":
                continue
            assert result["request_id"] == value["request_id"], result
            return result

    def wait_state(sock, state, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if exchange(sock, "vm_status", vm_id=vm_id).get("state") == state:
                return True
            time.sleep(0.25)
        return False

    def until(predicate, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.1)
        return predicate()

    def history(sock):
        result = exchange(sock, "vm_console_history", vm_id=vm_id, stream="serial1")
        return urllib.parse.unquote_to_bytes(result.get("serial1", "")).decode("utf-8", "replace")

    def guest(sock, script, marker, timeout=30):
        tagged = marker + "_" + nonce
        line = script.replace("NONCE", nonce) + "\n"
        # The guest UART has a small FIFO: feed it in pieces.
        for at in range(0, len(line), 16):
            assert exchange(sock, "vm_console_write", vm_id=vm_id, stream="serial1", data=line[at:at + 16])["success"]
            time.sleep(0.02)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            text = history(sock)
            # Echo is off after the first command, so output follows the prompt.
            if any(re.fullmatch(r"(.*# )?" + tagged, line.strip()) for line in text.splitlines()):
                return text
            time.sleep(0.25)
        (output / "serial1.txt").write_text(text, encoding="utf-8")
        raise TimeoutError("guest " + marker)

    def crosvm_pids():
        out = root_shell(f"for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in *crosvm*--name\\ {name}\\ *) echo ${{p#/proc/}};; esac; done").stdout
        return [int(x) for x in out.split()]

    def pool_want():
        return root_shell("cat /sys/module/gh_hugepage_reserve/parameters/pool_want").stdout.strip()

    try:
        report["pool_want_before"] = pool_want()
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        app_uid = shell(["stat", "-c", "%u", f"/data/user/0/{app}"], check=True).stdout.strip()
        assert root_shell(f"mkdir -p {data}/files {data}/bin && chown -R {app_uid}:{app_uid} {data} && chmod 700 {data}").returncode == 0
        assert root_shell(f"ln -s {PRODUCTION}/usr {data}/usr && ln -s {PRODUCTION}/lib {data}/lib && ln -s {PRODUCTION}/bin/lbx {data}/bin/lbx && chown -h {app_uid}:{app_uid} {data}/usr {data}/lib {data}/bin/lbx").returncode == 0
        shell(["mkdir", binary_dir], check=True)
        subprocess.run(adb + ["push", str(args.daemon), binary_dir + "/droidvmd"], check=True, timeout=60, capture_output=True)
        subprocess.run(adb + ["push", str(args.helper), helper], check=True, timeout=60, capture_output=True)
        shell(["chmod", "755", binary_dir + "/droidvmd", helper], check=True)
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        # The phone's own keyboards, before ours exists; the fixture daemon never sees them.
        report["host_keyboards"] = json.loads(root_shell(helper + " list").stdout)
        kbd = Keyboard()
        report["node"] = kbd.node
        check(test_devices(), "uinput test keyboard created")
        port, token = start()
        with connect(port, token) as sock:
            cap = exchange(sock, "capabilities")
            check(cap.get("keyboard_grab") is True and cap.get("service_api_minor") == 9 and
                  all(c in cap["commands"] for c in ("kbd_config", "kbd_grab", "kbd_status")), "capabilities advertise the keyboard grab")
            status = exchange(sock, "kbd_status")
            report["status_idle"] = status
            entry = {"path": kbd.node, "name": keyboard, "bus": "0006", "vendor": "1209", "product": "d7a1", "held": False}
            check(status["success"] and status["config"] == {"enabled": True, "escape_chord": []} and status["armed"] is False and
                  "vm_id" not in status and status["held"] == [] and status["keyboards"] == [entry], "kbd_status idle shape")
            check(status["leds"] == {"known": 0, "on": 0, "num": None, "caps": None, "scroll": None}, "unknown lamps are null")
            status = exchange(sock, "kbd_config", escape_chord=[CTRL, str(ALT), CTRL, 0, 768, "x"])
            check(status["success"] and status["config"] == {"enabled": True, "escape_chord": [CTRL, ALT]}, "kbd_config filters the chord")
            check(read(data + "/files/keyboard_grab.json") == f'{{\n  "enabled": true,\n  "escape_chord": [\n    {CTRL},\n    {ALT}\n  ]\n}}', "keyboard_grab.json written as JSONObject.toString(2)")
            check(root_shell(f"stat -c '%u %a' {data}/files/keyboard_grab.json").stdout.strip() == f"{app_uid} 600", "config owned like its directory, mode 600")
            check(exchange(sock, "kbd_config", enabled="FALSE")["config"] == {"enabled": False, "escape_chord": [CTRL, ALT]}, "kbd_config keeps the chord when only enabled changes")
            check(exchange(sock, "kbd_grab")["message"] == "missing vm_id", "kbd_grab without vm_id")
            unknown = str(uuid.uuid4())
            check(exchange(sock, "kbd_grab", vm_id=unknown)["message"] == "VM not found: " + unknown, "kbd_grab unknown VM")
            check(exchange(sock, "kbd_grab", grab=False)["success"], "release with nothing held")
            config = {"id": vm_id, "name": name, "backend": "crosvm", "hypervisor": "auto", "memory_mb": 512, "cpu_count": 1,
                      "hugepages": True, "networks": [], "disks": [],
                      "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": True, "exporter": "native", "input_enabled": True}},
                      "boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": PRODUCTION + "/usr/share/droidvm/vmlinuz",
                                                              "initrd": PRODUCTION + "/usr/share/droidvm/initramfs.img",
                                                              "cmdline": "console=ttyS0 earlycon rdinit=/bin/sh panic=-1"}}}
            check(exchange(sock, "vm_create", config=config)["success"], "create native-display VM")
            status = exchange(sock, "kbd_grab", vm_id=vm_id, screen="simplefb")
            check(status["success"] and status["armed"] is False, "disabled grab arms nothing")
            exchange(sock, "kbd_config", enabled=True)
            status = exchange(sock, "kbd_grab", vm_id=vm_id.upper(), screen="simplefb")
            check(status["armed"] is True and status["vm_id"] == vm_id.upper() and status["held"] == [], "armed for a stopped VM, nothing taken")
            check(exchange(sock, "vm_start", vm_id=vm_id)["success"], "start native-display VM")
            check(wait_state(sock, "running", 120), "VM reaches RUNNING")
            check(exchange(sock, "kbd_status")["armed"] is False, "an IPC grab ends when the VM leaves STOPPED (legacy onVmState)")
            text = until(lambda: re.search(r"[/~] # ", history(sock)), 60)
            check(text, "guest shell on the serial console")
            guest(sock, "stty -echo; echo SHELL_NONCE", "SHELL")
            setup = ("export PATH=/bin:/sbin:/usr/bin:/usr/sbin; mount -t devtmpfs devtmpfs /dev 2>/dev/null; mount -t proc proc /proc 2>/dev/null; "
                     "mount -t sysfs sysfs /sys 2>/dev/null; mkdir -p /tmp; dev=; for e in /sys/class/input/event*; do "
                     f"[ \"$(cat $e/device/name)\" = \"{guest_name}\" ] && dev=/dev/input/${{e##*/}}; done; "
                     "if [ -n \"$dev\" ]; then cat $dev > /tmp/k & kpid=$!; sleep 0.5; else echo KNODEV_NONCE; fi; echo KREADY_NONCE")
            console = guest(sock, setup, "KREADY", 30)
            check("KNODEV_" + nonce not in console, "guest has the screen's keyboard")
            # Grabbed: the keys reach the guest and no other reader of the node.
            status = exchange(sock, "kbd_grab", vm_id=vm_id, screen="simplefb")
            check(status["armed"] is True and [h["path"] for h in status["held"]] == [kbd.node] and status["keyboards"][0]["held"] is True, "running VM: the test keyboard is held")
            check(root_shell(shlex.join([helper, "grab", kbd.node, keyboard])).stdout.strip() == f"grab {EBUSY}", "EVIOCGRAB is exclusive (EBUSY for another grabber)")

            def typing():
                kbd.tap(30)
                kbd.key(48, 1); kbd.key(48, 2); kbd.key(48, 0)
                kbd.send("m")
                time.sleep(0.5)
            check(observe(kbd.node, 2500, typing) == [], "grabbed keys reach no other reader")
            # The guest's caps lamp comes back through the status records onto the real keyboard.
            led = "printf '" + "\\000" * 16 + "\\021\\000\\001\\000\\001\\000\\000\\000' > $dev; echo LED_NONCE"
            guest(sock, led, "LED")
            status = until(lambda: (lambda s: s if s["leds"]["caps"] is True else None)(exchange(sock, "kbd_status")), 10)
            check(status and status["leds"]["known"] == 2 and status["leds"]["on"] == 2 and status["leds"]["num"] is None, "guest lamps in kbd_status")
            check(until(lambda: (1, 1) in kbd.leds, 5), "caps lamp written back to the grabbed keyboard")
            # The escape chord: its last key is not forwarded, held keys are lifted, the grab is gone.
            kbd.key(CTRL, 1); kbd.key(ALT, 1)
            status = until(lambda: (lambda s: s if not s["armed"] else None)(exchange(sock, "kbd_status")), 5)
            check(status and status["held"] == [] and status["leds"]["known"] == 0, "escape chord releases the grab")
            kbd.key(ALT, 0); kbd.key(CTRL, 0)
            check(observe(kbd.node, 1500, lambda: kbd.send("m")) == [[4, 4, 0x70004]], "released: events reach other readers again")
            # The keyboard going away while held, and coming back on whatever node is free.
            exchange(sock, "kbd_grab", vm_id=vm_id, screen="simplefb")
            check([h["path"] for h in exchange(sock, "kbd_status")["held"]] == [kbd.node], "grabbed again")
            kbd.close()
            status = until(lambda: (lambda s: s if s["held"] == [] else None)(exchange(sock, "kbd_status")), 5)
            check(status and status["armed"] is True and status["keyboards"] == [], "removed keyboard is dropped, console stays armed")
            check(not test_devices(), "uinput device removed")
            kbd = Keyboard()
            report["node_again"] = kbd.node
            status = until(lambda: (lambda s: s if s["held"] else None)(exchange(sock, "kbd_status")), 5)
            check(status and [h["path"] for h in status["held"]] == [kbd.node], "hotplug: a returning keyboard is grabbed again")
            kbd.tap(30)
            time.sleep(0.5)
            dump = guest(sock, "kill $kpid 2>/dev/null; echo KBEGIN; od -An -v -tx1 /tmp/k; echo KEND_NONCE", "KEND")
            match = re.search(r"KBEGIN\s*\n(.*?)^KEND_" + nonce, dump, re.M | re.S)
            raw = bytes.fromhex(match.group(1)) if match else b""
            events = [(t, c, v) for _, _, t, c, v in struct.iter_unpack("<qqHHi", raw)] if raw and len(raw) % 24 == 0 else []
            keys = [(c, v) for t, c, v in events if t == 1]
            report["guest_keys"] = keys
            check(keys == [(30, 1), (30, 0), (48, 1), (48, 0), (CTRL, 1), (CTRL, 0), (30, 1), (30, 0)],
                  "guest got the grabbed keys: no repeat, no chord key, held key lifted")
            check(all(t != 4 for t, _, _ in events), "host-only events (EV_MSC) are not forwarded")
            # The VM stopping hands the keyboard back.
            check(exchange(sock, "vm_stop", vm_id=vm_id)["success"] and wait_state(sock, "stopped", 60), "stop the VM")
            status = exchange(sock, "kbd_status")
            check(status["armed"] is False and status["held"] == [], "VM stop releases the grab")
            check(observe(kbd.node, 1500, lambda: kbd.send("m")) == [[4, 4, 0x70004]], "after the stop, events reach other readers")
            check(not crosvm_pids(), "no VMM left")
            check(exchange(sock, "vm_delete", vm_id=vm_id)["success"], "delete the VM")
        stop()
        log_text = read(data + "/cache/daemon.log")
        (output / "daemon.log").write_text(log_text, encoding="utf-8")
        for line in ("has the keyboard", "for the guest", "keyboard back to Android (escape chord)", "guest lamps: num=? caps=on scroll=?",
                     "keyboard(s) joined the grab", f"keyboard back to Android (VM {name} is stopping)"):
            check(line in log_text, "daemon.log: " + line)
        report["passed"] = True
    finally:
        for device in list(devices):
            device.close()
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
        report["leftover_devices"] = test_devices()
        report["pool_want_after"] = pool_want()
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)
        if not report["leftover_vmm"]:
            root_shell(f"rm -r {data} {binary_dir}")
            report["removed"] = root_shell(f"ls -d {data} {binary_dir} 2>/dev/null").stdout.strip() == ""
            (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    assert report.get("passed") and not report["leftover_devices"] and report["pool_want_after"] == report["pool_want_before"], report
    print("PASS: physical keyboard grab through the native daemon,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
