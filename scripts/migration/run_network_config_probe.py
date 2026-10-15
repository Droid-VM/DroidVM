# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the real native network configuration service through existing TCP commands."""
import argparse
import concurrent.futures
import json
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
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    parser.add_argument("--skip-slow", action="store_true", help="Skip the separate 35-second write-delay fixture")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/network-config-device" / nonce
    output.mkdir(parents=True)
    app = "cn.classfun.droidvm.migration.test"
    data = f"/data/user/0/{app}/cache/network-{nonce}"
    binary_dir = f"/data/local/tmp/droidvm-network-{nonce}"
    adb = ["adb", "-s", args.serial]
    report, processes, forwards, logs = {}, [], [], []
    active = None

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=45, **kwargs)

    def read(path):
        return shell(["cat", path], check=True).stdout.strip()

    def config(name):
        return {"id": str(uuid.uuid4()), "name": name, "schema": 2, "bridge_name": "brtest",
                "extension": {"unicode": "网络🌏", "integer": 9007199254740993, "null": None},
                "l3": {"vlans": [], "future": {"enabled": True}}}

    def seed(text):
        source = output / "seed.json"
        source.write_text(text, encoding="utf-8")
        subprocess.run(adb + ["push", str(source), binary_dir + "/seed.json"], check=True, timeout=20)
        shell(["cp", binary_dir + "/seed.json", data + "/files/networks.json"], check=True)
        shell(["chown", uid + ":" + uid, data + "/files/networks.json"], check=True)
        shell(["chmod", "600", data + "/files/networks.json"], check=True)

    def start(fixture=False):
        nonlocal active
        executable = binary_dir + ("/fixture" if fixture else "/droidvmd")
        log_path = output / f"daemon-{len(processes)}.txt"
        log = log_path.open("w", encoding="utf-8"); logs.append(log)
        command = [executable, "--data-dir", data, "--apk", apk]
        if fixture:
            command += ["--test-write-delay-ms", "35000"]
        process = subprocess.Popen(adb + ["shell", shlex.join(command)], stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Daemon startup failed: " + log_path.read_text(encoding="utf-8"))
            pid = shell(["cat", data + "/run/droidvmd.pid"]).stdout.strip()
            port = shell(["cat", data + "/run/droidvmd-port.txt"]).stdout.strip()
            if pid.isdecimal() and port.isdecimal():
                token = read(data + "/run/droidvmd-token.txt")
                forward = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True, timeout=10)
                local = int(forward.stdout.strip()); forwards.append(local)
                active = (process, pid, executable)
                return local, token, log_path
            time.sleep(0.025)
        raise TimeoutError("Daemon readiness")

    def stop():
        nonlocal active
        process, pid, executable = active
        assert shell(["readlink", f"/proc/{pid}/exe"], check=True).stdout.strip() == executable
        shell(["kill", "-15", pid], check=True)
        assert process.wait(timeout=45) == 143
        active = None

    def connect(auth=True):
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

    try:
        test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
        subprocess.run(adb + ["install", "-r", "-t", str(test_apk)], check=True, timeout=60)
        shell(["run-as", app, "mkdir", "-p", data + "/files"], check=True)
        uid = shell(["stat", "-c", "%u", data], check=True).stdout.strip()
        shell(["mkdir", binary_dir], check=True)
        binaries = [(args.daemon, "droidvmd"), (root / "build/migration/standalone-arm64-v8a/droidvm_network_contract", "contract")]
        if not args.skip_slow:
            binaries.append((root / "build/migration/standalone-arm64-v8a/droidvm_network_fixture", "fixture"))
        for binary, name in binaries:
            subprocess.run(adb + ["push", str(binary), binary_dir + "/" + name], check=True, timeout=30)
            shell(["chmod", "755", binary_dir + "/" + name], check=True)
        rejected = shell([binary_dir + "/droidvmd", "--test-write-delay-ms", "35000"])
        assert rejected.returncode == 1 and "invalid arguments" in rejected.stderr
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip().removeprefix("package:")
        assert apk.startswith("/") and "\n" not in apk
        existing, future = config("existing"), config("future")
        future["schema"] = 9
        legacy = {"id": str(uuid.uuid4()), "name": "legacy", "bridge_name": "legacy0", "ipv4_addresses": ["10.1.2.1/24"]}
        seed(json.dumps({"networks": [existing, future, legacy], "root_extension": {"future": True}}, ensure_ascii=False))
        port, token, _ = start()
        with connect(False) as sock:
            assert exchange(sock, "network_create", config=config("unauthorized"), authenticated=True, administrator=True)["message"] == "client is not authorized to perform this action"
            assert not exchange(sock, "network_list_interfaces", authenticated=True)["success"]
            assert exchange(sock, "auth", token=token)["success"]
            cap = exchange(sock, "capabilities")
            assert cap["network_runtime"] is False and cap["network_config_persistence"] is True
            assert all(name in cap["commands"] for name in ("network_create", "network_modify", "network_list", "network_info", "network_exists"))
            assert "network_delete" not in cap["commands"] and "network_start" not in cap["commands"]
            assert cap["network_host_interfaces"] is True and "network_list_interfaces" in cap["commands"]
            # Missing tool must surface an error, not a successful empty list.
            assert exchange(sock, "network_list_interfaces")["error"]["code"] == "IO_ERROR"
            shell(["mkdir", data + "/bin"], check=True)
            shell(["cp", "/data/user/0/cn.classfun.droidvm/bin/netbox", data + "/bin/netbox"], check=True)
            shell(["chmod", "700", data + "/bin/netbox"], check=True)
            raw_links = json.loads(shell([data + "/bin/netbox", "link-list"], check=True).stdout)
            expected_names = {row["ifname"] for row in raw_links if row["ifname"] != "lo" and row["kind"] != "bridge"}
            interfaces = exchange(sock, "network_list_interfaces")["data"]
            assert {row["name"] for row in interfaces} == expected_names
            assert all(set(row) == {"name", "state", "master"} and row["state"] == row["state"].upper() for row in interfaces)
            report["real_host_interface_enumeration_auth_and_tool_failure"] = True
            # Exercise a slow real child through this new command's dispatch
            # path. Only the isolated test directory's netbox is replaced.
            sleeper = output / "slow-netbox"
            sleeper.write_bytes(b"#!/system/bin/sh\nexec /system/bin/sleep 12\n")
            subprocess.run(adb + ["push", str(sleeper), data + "/bin/slow-netbox"], check=True, timeout=20)
            shell(["chmod", "700", data + "/bin/slow-netbox"], check=True)
            shell(["mv", data + "/bin/netbox", data + "/bin/real-netbox"], check=True)
            shell(["mv", data + "/bin/slow-netbox", data + "/bin/netbox"], check=True)
            def slow_query():
                with connect() as slow:
                    slow.settimeout(20)
                    return exchange(slow, "network_list_interfaces")
            # Native steady-clock budgets exclude deep suspend, whereas the
            # Windows client's deadline includes it. Hold only our bounded test
            # wake lock during this comparison; restore without changing display
            # or global stay-awake settings. The kernel expires it in 30 seconds.
            wake_name = "droidvm-netprobe-" + nonce
            shell(["sh", "-c", 'printf "%s" "$1" > /sys/power/wake_lock', "sh", wake_name + " 30000000000"], check=True)
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                    started = time.monotonic()
                    pending = pool.submit(slow_query)
                    time.sleep(0.2)
                    ping_start = time.monotonic()
                    assert exchange(sock, "ping", request="PING")["response"] == "PONG"
                    latency = time.monotonic() - ping_start
                    assert latency < 1
                    assert not pending.done(), pending.result()
                    timed_out = pending.result(timeout=15)
                    assert timed_out["error"]["code"] == "UNAVAILABLE", timed_out
                    assert time.monotonic() - started < 13
            finally:
                shell(["sh", "-c", 'printf "%s" "$1" > /sys/power/wake_unlock', "sh", wake_name], check=True)
            shell(["mv", data + "/bin/real-netbox", data + "/bin/netbox"], check=True)
            assert exchange(sock, "network_list_interfaces")["success"]
            report["tool_timeout_ping_seconds"] = latency
            report["tool_timeout_releases_worker_and_query_recovers"] = True
            rows = exchange(sock, "network_list")["data"]
            assert [row["name"] for row in rows] == ["existing", "legacy"] and all(row["state"] == "stopped" for row in rows)
            assert exchange(sock, "network_info", network_id=existing["id"])["data"]["state"] == "STOPPED"
            assert exchange(sock, "network_exists", network_id="bad")["exists"] is False
            for value, expected in ((None, "null"), (123, "123"), (True, "true"), ([], "[]")):
                reply = exchange(sock, "network_exists", network_id=value)
                assert reply["exists"] is False and reply["network_id"] == expected
                assert exchange(sock, "network_info", network_id=value)["message"] == "internal error"
            assert exchange(sock, "network_info", network_id="bad")["message"] == "internal error"
            assert exchange(sock, "network_info")["message"] == "missing network_id"
            assert exchange(sock, "network_create")["message"] == "missing config"
            created = config("created")
            assert exchange(sock, "network_create", config=created)["network_id"] == created["id"]
            assert exchange(sock, "network_create", config=created)["message"] == "failed to create network"
            bad_duplicate = dict(created, bridge_name="bad bridge")
            assert exchange(sock, "network_create", config=bad_duplicate)["message"] == "failed to create network"
            edit = {key: value for key, value in created.items() if key not in ("extension", "l3")}
            edit["name"] = "edited"
            assert exchange(sock, "network_modify", config=edit)["success"]
            info = exchange(sock, "network_info", network_id=created["id"])["data"]
            assert info["extension"] == created["extension"] and info["l3"]["future"] == {"enabled": True}
            edit["name"] = "existing"
            assert exchange(sock, "network_modify", config=edit)["message"] == "internal error"
            assert exchange(sock, "network_info", network_id=created["id"])["data"]["name"] == "edited"
            assert exchange(sock, "network_modify", config=config("missing"))["message"] == "failed to modify network"
        stored = json.loads(read(data + "/files/networks.json"))
        assert stored["root_extension"] == {"future": True} and stored["networks"][1] == future
        assert shell(["stat", "-c", "%u:%g:%a", data + "/files/networks.json"], check=True).stdout.strip() == f"{uid}:{uid}:600"
        shell(["run-as", app, "cat", data + "/files/networks.json"], check=True)
        report["legacy_commands_migration_auth_unknowns_app_readable"] = True
        duplicate = config("concurrent")
        def attempt(_):
            with connect() as sock:
                return exchange(sock, "network_create", config=duplicate)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(attempt, range(12)))
        assert sum(result["success"] for result in results) == 1
        assert all(result["success"] or result["message"] == "failed to create network" for result in results)
        # adb forward closes the entire channel on host SHUT_WR. Exercise real
        # TCP half-close from a device-local client instead of testing adb.
        half_result = shell([binary_dir + "/contract", "--half-close", read(data + "/run/droidvmd-port.txt"), token], check=True)
        half = {"id": json.loads(half_result.stdout)["network_id"]}
        report["twelve_concurrent_creates_and_half_close"] = True
        stop()
        port, token, _ = start()
        with connect() as sock:
            assert exchange(sock, "network_exists", network_id=created["id"])["exists"]
            assert exchange(sock, "network_exists", network_id=half["id"])["exists"]
        stop()
        backup = json.loads(read(data + "/files/networks.json.bak"))
        seed("{broken")
        port, token, _ = start()
        with connect() as sock:
            expected = [row["id"] for row in backup["networks"] if row.get("schema") == 2]
            assert [row["id"] for row in exchange(sock, "network_list")["data"]] == expected
            assert exchange(sock, "network_create", config=config("after recovery"))["success"]
        assert read(data + "/files/networks.json.corrupt") == "{broken"
        report["restart_and_corrupt_primary_recovery"] = True
        stop()
        if not args.skip_slow:
            port, token, log_path = start(True)
            slow, flood = connect(), connect()
            delayed, second = config("delayed durable operation"), config("second queued operation")
            slow.sendall(frame(request("network_create", config=delayed)))
            deadline = time.monotonic() + 5
            while "fixture write delay started" not in log_path.read_text(encoding="utf-8"):
                assert time.monotonic() < deadline
                time.sleep(0.02)
            batch = [request("network_create", config=second)] + [request("network_list") for _ in range(12)]
            ping = request("ping", request="PING")
            started = time.monotonic()
            flood.sendall(b"".join(frame(value) for value in batch + [ping]))
            refusals = 0
            while True:
                reply = receive(flood)
                if reply["request_id"] == ping["request_id"]:
                    assert reply["response"] == "PONG"
                    break
                assert reply["error"]["code"] == "RESOURCE_EXHAUSTED"
                refusals += 1
            latency = time.monotonic() - started
            assert refusals >= 5 and latency < 1.0, (refusals, latency)
            budget_client, rejected_client = connect(), connect()
            budget_ping = request("ping", request="PING")
            budget_client.sendall(b"".join(frame(request("network_list")) for _ in range(8)) + frame(budget_ping))
            assert receive(budget_client)["request_id"] == budget_ping["request_id"]
            # Seven list responses reserved on flood plus eight here nearly
            # consume 32 MiB. A different connection cannot bypass the budget.
            assert exchange(rejected_client, "network_list")["error"]["code"] == "RESOURCE_EXHAUSTED"
            assert exchange(rejected_client, "ping", request="PING")["response"] == "PONG"
            budget_client.close(); rejected_client.close()
            report["global_response_reservations_bound_all_connections"] = True
            flood.close()
            slow.settimeout(30)
            started = time.monotonic()
            try:
                receive(slow)
                raise AssertionError("Delayed request unexpectedly completed within client deadline")
            except socket.timeout:
                assert time.monotonic() - started >= 29.5
            finally:
                slow.close()
            with connect() as observer:
                assert exchange(observer, "ping", request="PING")["response"] == "PONG"
                for target in (delayed["id"], second["id"]):
                    deadline = time.monotonic() + 12
                    while True:
                        result = exchange(observer, "network_exists", network_id=target)
                        if result.get("success") and result.get("exists"):
                            break
                        # The deliberately saturated global reservation also
                        # protects queries until completed old requests drain.
                        if not result.get("success"):
                            assert result["error"]["code"] == "RESOURCE_EXHAUSTED", result
                        assert time.monotonic() < deadline, result
                        time.sleep(0.05)
                assert exchange(observer, "task_snapshot")["items"] == []
            report["slow_write_ping_seconds"] = latency
            report["per_client_capacity_refusals"] = refusals
            report["real_config_commit_survives_30s_client_deadline_and_disconnect"] = True
            stop()
        report["passed"] = True
    finally:
        if active is not None:
            stop()
        for process in processes:
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
        for log in logs:
            log.close()
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{local}"], capture_output=True, timeout=10)
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            shell(["rm", "-f", data + "/run/droidvmd.lock", data + "/cache/daemon.log",
                   data + "/bin/netbox",
                   data + "/files/networks.json", data + "/files/networks.json.bak", data + "/files/networks.json.corrupt",
                   binary_dir + "/droidvmd", binary_dir + "/fixture", binary_dir + "/contract", binary_dir + "/seed.json"], check=True)
            shell(["rmdir", data + "/run", data + "/cache", data + "/files", data + "/bin", data, binary_dir], check=True)
    print("PASS: persistent network configuration, legacy TCP and bounded asynchronous dispatch")


if __name__ == "__main__":
    main()
