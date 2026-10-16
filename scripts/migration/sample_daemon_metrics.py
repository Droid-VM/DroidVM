# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only resource and ping samples for an already-running daemon.

Does not restart a daemon or touch VM state. Results describe the observed
workload; samples from different configurations are not a performance verdict.
"""
import argparse
import json
import math
from pathlib import Path
import re
import shlex
import socket
import statistics
import subprocess
import time
import uuid

from run_daemon_probe import frame, receive, request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--requests", type=int, default=100)
    args = parser.parse_args()
    if not args.run_dir.startswith("/") or not 10 <= args.requests <= 1000:
        parser.error("run-dir must be absolute and requests must be between 10 and 1000")
    adb = ["adb", "-s", args.serial]
    root = Path(__file__).resolve().parents[2]
    output = root / "build/migration/metrics" / uuid.uuid4().hex
    output.mkdir(parents=True)

    def shell(command):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)],
                              check=True, capture_output=True, text=True, timeout=30).stdout.strip()

    def read(path):
        return shell("cat " + shlex.quote(path))

    pid = int(read(args.run_dir.rstrip("/") + "/droidvmd.pid"))
    if pid <= 1:
        raise RuntimeError("Invalid daemon PID")
    proc = f"/proc/{pid}"
    # starttime, not just PID, detects a concurrent replacement/PID reuse.
    def identity():
        return read(proc + "/stat").rsplit(")", 1)[1].split()[19]

    starttime = identity()
    command = read(proc + "/cmdline").replace("\0", " ")
    comm = read(proc + "/comm")
    if comm != "droidvmd" and "cn.classfun.droidvm.daemon.Daemon" not in command:
        raise RuntimeError("Discovery PID is not a DroidVM daemon")
    port = int(read(args.run_dir.rstrip("/") + "/droidvmd-port.txt"))
    if not 0 < port <= 65535:
        raise RuntimeError("Invalid daemon port")
    token = read(args.run_dir.rstrip("/") + "/droidvmd-token.txt")
    local = subprocess.run(adb + ["forward", "tcp:0", f"tcp:{port}"],
                           check=True, capture_output=True, text=True, timeout=15).stdout.strip()
    report = {"label": args.label, "pid": pid, "starttime_ticks": starttime,
              "transport": "ADB forward TCP (includes host and transport latency)",
              "startup_ms": None, "startup_note": "Existing process; startup was not measured",
              "comparable_workload_verified": False}
    try:
        with socket.create_connection(("127.0.0.1", int(local)), timeout=10) as sock:
            def call(command, **fields):
                value = request(command, **fields)
                sock.sendall(frame(value))
                deadline = time.monotonic() + 10
                while True:
                    sock.settimeout(max(0.001, deadline - time.monotonic()))
                    response = receive(sock)
                    if response.get("request_id") == value["request_id"]:
                        if response.get("success") is not True:
                            raise RuntimeError("Metrics request failed: " + command)
                        return response
                    if time.monotonic() >= deadline:
                        raise TimeoutError(command)

            call("auth", token=token)
            version = call("version")
            report["version"] = {k: version.get(k) for k in ("version", "version_code", "build_type", "daemon_hash")}
            vms = call("vm_list").get("data", [])
            report["vm_states"] = [vm.get("state") for vm in vms]
            for _ in range(5):
                call("ping", request="PING")
            times = []
            for _ in range(args.requests):
                before = time.perf_counter_ns()
                assert call("ping", request="PING").get("response") == "PONG"
                times.append((time.perf_counter_ns() - before) / 1_000_000)
            ordered = sorted(times)
            report["ping_ms"] = {"count": len(times), "median": statistics.median(times),
                                 "p95": ordered[math.ceil(len(times) * .95) - 1], "max": max(times), "samples": times}
        status = read(proc + "/status")
        rollup = read(proc + "/smaps_rollup")
        for key, source in (("VmRSS", status), ("VmSize", status), ("Threads", status), ("Pss", rollup)):
            match = re.search(r"^" + key + r":\s+(\d+)", source, re.M)
            if not match:
                raise RuntimeError("Missing process metric: " + key)
            report[key + ("_KiB" if key != "Threads" else "")] = int(match.group(1))
        report["fds"] = len(shell("ls -1 " + proc + "/fd").splitlines())
        maps = read(proc + "/maps")
        report["art_or_jvm_loaded"] = any(name in maps for name in ("/libart.so", "/libjvm.so", "/libandroid_runtime.so"))
        if identity() != starttime or int(read(args.run_dir.rstrip("/") + "/droidvmd.pid")) != pid:
            raise RuntimeError("Daemon changed during sampling")
        report["sample_complete"] = True
    finally:
        subprocess.run(adb + ["forward", "--remove", "tcp:" + local], capture_output=True, timeout=15)
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)


if __name__ == "__main__":
    main()
