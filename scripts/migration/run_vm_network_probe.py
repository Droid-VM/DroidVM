# SPDX-License-Identifier: GPL-3.0-or-later
"""Attach a real Gunyah crosvm VM to a native Linux L3 network inside a private network namespace."""
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
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/vm-network-device" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/vmnet-{nonce[:8]}"
    binary_dir = f"/data/local/tmp/droidvm-vmnet-{nonce}"
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs, events = {"checks": []}, [], [], [], []
    vm_id, network_id = str(uuid.uuid4()), str(uuid.uuid4())
    name, bridge = "nt" + nonce[:6], "brn" + nonce[:6]
    tap = "vm" + vm_id[:8] + "-0"
    daemon_pid = None

    def root_shell(command, timeout=60):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=timeout)

    def inside(command):
        return root_shell(f"nsenter -t {daemon_pid} -n -- {command}").stdout

    def check(condition, label):
        report["checks"].append(label)
        assert condition, label

    def spawn(command, log_name):
        log_path = output / log_name
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        process = subprocess.Popen(adb + ["shell", "su -c " + shlex.quote(command)], stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process, log_path

    def wait_line(process, log_path, pattern, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(log_path.read_text(encoding="utf-8"))
            found = re.search(pattern, log_path.read_text(encoding="utf-8"))
            if found:
                return found
            time.sleep(0.05)
        raise TimeoutError(pattern)

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

    def history(sock):
        result = exchange(sock, "vm_console_history", vm_id=vm_id, stream="serial1")
        return urllib.parse.unquote_to_bytes(result.get("serial1", "")).decode("utf-8", "replace")

    def wait_text(sock, text, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = history(sock)
            if text in current:
                return current
            time.sleep(0.5)
        return history(sock)

    try:
        before_want = root_shell("cat /sys/module/gh_hugepage_reserve/parameters/pool_want").stdout.strip()
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60, capture_output=True)
        subprocess.run(adb + ["shell", shlex.join(["run-as", app, "mkdir", "-p", data + "/files", data + "/bin"])], check=True, timeout=30)
        uid = root_shell(f"stat -c %u {data}").stdout.strip()
        links = " && ".join([f"ln -s {PRODUCTION}/usr {data}/usr", f"ln -s {PRODUCTION}/lib {data}/lib"] +
                            [f"ln -s {PRODUCTION}/bin/{tool} {data}/bin/{tool}" for tool in ("lbx", "netbox", "bridgedhcp")])
        # Production layout: app-owned, files/ is 0771 with the app's own group.
        assert root_shell(links + f" && chown -h {uid}:{uid} {data}/usr {data}/lib {data}/bin/* && chown {uid}:{uid} {data} {data}/files {data}/bin"
                          f" && chmod 700 {data} && chmod 771 {data}/files && chmod 700 {data}/bin").returncode == 0
        assert root_shell(f"mkdir {binary_dir}").returncode == 0
        contract = root / "build/migration/standalone-arm64-v8a/droidvm_network_backend_contract"
        for local, remote in ((args.daemon, "droidvmd"), (contract, "contract")):
            subprocess.run(adb + ["push", str(local), binary_dir + "/" + remote], check=True, timeout=60, capture_output=True)
        root_shell(f"chmod 755 {binary_dir}/droidvmd {binary_dir}/contract")
        report["daemon_sha256"] = hashlib.sha256(args.daemon.read_bytes()).hexdigest()
        apk = subprocess.run(adb + ["shell", "pm path cn.classfun.droidvm"], capture_output=True, text=True).stdout.strip().removeprefix("package:")
        daemon, daemon_log = spawn(f"{binary_dir}/contract --netns-exec {data}/bin/netbox {binary_dir}/droidvmd --data-dir {data} --apk {apk}", "daemon.txt")
        ready = wait_line(daemon, daemon_log, r"droidvmd ready pid=(\d+) port=(\d+)")
        daemon_pid, inner_port = ready.groups()
        relay, relay_log = spawn(f"{binary_dir}/contract --netns-relay {daemon_pid} {inner_port}", "relay.txt")
        relay_port = wait_line(relay, relay_log, r"relay port=(\d+)").group(1)
        local = int(subprocess.run(adb + ["forward", "tcp:0", "tcp:" + relay_port], check=True, capture_output=True, text=True).stdout.strip())
        forwards.append(local)
        token = root_shell(f"cat {data}/run/droidvmd-token.txt").stdout.strip()
        with socket.create_connection(("127.0.0.1", local), timeout=60) as sock:
            exchange(sock, "auth", token=token)
            network = {"schema": 2, "id": network_id, "name": "vm network " + nonce[:6], "bridge_name": bridge, "bridge_type": "linux",
                       "uplink_mode": "l3", "l3": {"vlans": [{"vlan_id": 0, "ipv4": {"cidr": "10.77.0.1/24", "snat": True,
                                                                                      "dhcp": {"enabled": True, "offset_start": 100, "offset_end": 120}}}]}}
            check(exchange(sock, "network_create", config=network)["success"], "create Linux L3 network")
            config = {"id": vm_id, "name": name, "backend": "crosvm", "memory_mb": 512, "cpu_count": 1, "disks": [],
                      "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": False}},
                      "networks": [{"network_id": network_id, "mac_address": "02:77:00:00:00:0a",
                                    "dhcp4_lease": {"enabled": True, "offset": 10,
                                                    "forwards": [{"proto": "tcp", "host": "18080", "guest": "80"},
                                                                 {"proto": "udp", "host": "x", "guest": "1"}]}}],
                      "boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": PRODUCTION + "/usr/share/droidvm/vmlinuz",
                                                              "initrd": PRODUCTION + "/usr/share/droidvm/initramfs.img", "cmdline": "console=ttyS0"}}}
            check(exchange(sock, "vm_create", config=config)["success"], "create VM with a NIC")
            check(exchange(sock, "network_status", network_id=network_id)["state"] == "stopped", "network starts stopped")
            # VMNicConfig.validate runs before the backend: an invalid forward fails the start.
            check(not exchange(sock, "vm_start", vm_id=vm_id)["success"], "invalid forward fails the start, as validate()")
            stdio = urllib.parse.unquote(exchange(sock, "vm_console_history", vm_id=vm_id, stream="stdio").get("stdio", ""))
            check("network setup failed: Invalid port: x" in stdio, "validation failure reported on the stdio console")
            check(not inside(f"ip -o link show {tap}"), "failed attach leaves no TAP")
            config["networks"][0]["dhcp4_lease"]["forwards"] = config["networks"][0]["dhcp4_lease"]["forwards"][:1]
            check(exchange(sock, "vm_modify", config=config)["success"], "replace the invalid forward")
            started = exchange(sock, "vm_start", vm_id=vm_id)
            if not started["success"]:
                stdio = exchange(sock, "vm_console_history", vm_id=vm_id, stream="stdio").get("stdio", "")
                report["start_failure"] = urllib.parse.unquote(stdio)
                report["network_status"] = exchange(sock, "network_status", network_id=network_id)
            check(started["success"], "vm_start attaches the NIC")
            check(exchange(sock, "network_status", network_id=network_id)["state"] == "running", "VM start auto-starts its network")
            stored = exchange(sock, "vm_get", vm_id=vm_id)["data"]
            check(stored["networks"][0]["tap_name"] == tap, "default TAP name recorded in the VM config")
            link = inside(f"ip -o link show {tap}")
            report["tap_link"] = link
            report["links"] = inside("ip -o link")
            check(f"master {bridge}" in link, "TAP is a port of the network bridge")
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline and exchange(sock, "vm_status", vm_id=vm_id)["state"] != "running":
                time.sleep(0.25)
            check(exchange(sock, "vm_status", vm_id=vm_id)["state"] == "running", "VM with a NIC reaches RUNNING")
            argv = root_shell(f"for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in *crosvm*{name}*) echo \"$c\";; esac; done").stdout
            check(f"--net tap-name={tap},mac=02:77:00:00:00:0a" in argv, "crosvm receives the attached TAP")
            rules = inside("iptables -S FORWARD")
            check("10.77.0.10/32" in rules and "--dport 80" in rules, "valid forward installs its FORWARD accept")
            info = exchange(sock, "network_info", network_id=network_id)
            report["network_info"] = info.get("data")
            check(not exchange(sock, "network_stop", network_id=network_id)["success"], "network stop refused while a running VM uses it")
            # The guest drops to the emergency shell without a root disk; drive it over the console.
            check("Press Enter for system maintenance" in wait_text(sock, "Press Enter for system maintenance", 120), "guest reaches its console")
            exchange(sock, "vm_console_write", vm_id=vm_id, stream="serial1", data="\n")
            time.sleep(3)
            exchange(sock, "vm_console_write", vm_id=vm_id, stream="serial1",
                     data="ip link set eth0 up; ip addr add 10.77.0.10/24 dev eth0; ping -c 3 -W 2 10.77.0.1 && echo GATEWAY-$((6*7))-OK\n")
            text = wait_text(sock, "GATEWAY-42-OK", 40)
            (output / "serial1.txt").write_text(text, encoding="utf-8")
            check("GATEWAY-42-OK" in text, "guest reaches the bridge gateway through the TAP")
            # The static lease pushed to bridgedhcp: offset 10 of 10.77.0.0/24.
            exchange(sock, "vm_console_write", vm_id=vm_id, stream="serial1",
                     data="ip addr flush dev eth0; for c in udhcpc busybox; do command -v $c; done; "
                          "udhcpc -i eth0 -n -q -t 5 -s /bin/true 2>&1; echo DHCP-$((6*7))-DONE\n")
            text = wait_text(sock, "DHCP-42-DONE", 60)
            (output / "serial1.txt").write_text(text, encoding="utf-8")
            tail = text[text.rfind("ip addr flush"):]
            report["dhcp_client"] = "udhcpc" in tail.split("DHCP-")[0].split("\n", 1)[-1]
            if report["dhcp_client"]:
                check("lease of 10.77.0.10 obtained" in tail.lower(), "guest DHCP receives its static lease")
            check(exchange(sock, "vm_stop", vm_id=vm_id)["success"], "stop VM")
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and exchange(sock, "vm_status", vm_id=vm_id)["state"] != "stopped":
                time.sleep(0.25)
            check(exchange(sock, "vm_status", vm_id=vm_id)["state"] == "stopped", "VM stopped")
            report["tap_after_stop"] = inside(f"ip -o link show {tap}")
            report["daemon_log"] = root_shell(f"cat {data}/cache/daemon.log").stdout[-3000:]
            check("does not exist" in root_shell(f"nsenter -t {daemon_pid} -n -- ip link show {tap} 2>&1").stdout or not inside(f"ip -o link show {tap}"), "TAP removed after VM exit")
            check("10.77.0.10/32" not in inside("iptables -S FORWARD"), "VM forward retired with its NIC")
            check(exchange(sock, "network_status", network_id=network_id)["state"] == "running", "network keeps running after the VM")
            check(exchange(sock, "network_stop", network_id=network_id)["success"], "network stops once unused")
        root_shell(f"kill -15 {daemon_pid}")
        daemon.wait(timeout=60)
        report["pool_want_before"] = before_want
        report["pool_want_after"] = root_shell("cat /sys/module/gh_hugepage_reserve/parameters/pool_want").stdout.strip()
        check(report["pool_want_after"] == before_want, "reserve target restored")
        report["passed"] = True
    finally:
        if daemon_pid:
            # SIGTERM stops the VMs first; SIGKILL would orphan their VMMs.
            root_shell(f"kill -15 {daemon_pid} 2>/dev/null; for i in $(seq 1 60); do [ -e /proc/{daemon_pid} ] || break; sleep 0.5; done; kill -9 {daemon_pid} 2>/dev/null")
        for process in processes:
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
        root_shell(f"pkill -f '{binary_dir}/contract --netns-relay'")
        for log in logs:
            log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        report["events"] = len(events)
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)
        leftovers = root_shell(f"for p in /proc/[0-9]*; do c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); case \"$c\" in */crosvm\\ --extended-status*--name\\ {name}\\ *) echo $p;; esac; done").stdout.strip()
        report["leftover_vmm"] = leftovers
        if report.get("passed") and not leftovers:
            root_shell(f"rm -r {data} {binary_dir}")
    assert report.get("passed"), report
    print("PASS: VM NIC attached to a native Linux network,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
