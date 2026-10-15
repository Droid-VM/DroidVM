# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise real task TCP dispatch with a test-only producer in an isolated process."""
import argparse
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
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/task-transport" / nonce
    output.mkdir(parents=True)
    directory = "/data/local/tmp/droidvm-task-" + nonce
    executable = directory + "/fixture"
    adb = ["adb", "-s", args.serial]
    report, events = {}, []
    processes, logs, sockets, forwards = [], [], [], []

    def shell(argv, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(argv)], text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=30, **kwargs)

    def read(name):
        return shell(["cat", directory + "/" + name]).stdout.strip()

    def start():
        log = (output / f"fixture-{len(processes)}.txt").open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen(adb + ["shell", shlex.join([executable, directory, apk])], stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Fixture exited before readiness")
            pid, port, token = read("droidvmd.pid"), read("droidvmd-port.txt"), read("droidvmd-token.txt")
            if pid.isdecimal() and port.isdecimal() and len(token) == 32:
                result = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True, timeout=10)
                local_port = int(result.stdout.strip())
                forwards.append(local_port)
                return process, pid, local_port, token
            time.sleep(0.02)
        raise TimeoutError("Fixture readiness")

    def connect():
        sock = socket.create_connection(("127.0.0.1", local_port), timeout=5)
        sockets.append(sock)
        return sock

    def exchange(sock, command, **fields):
        value = request(command, **fields)
        sock.sendall(frame(value))
        while True:
            reply = receive(sock)
            if reply["type"] == "event":
                events.append(reply)
                continue
            assert reply["request_id"] == value["request_id"], reply
            return reply

    def auth(sock):
        assert exchange(sock, "auth", token=token)["success"]

    def get(sock, task):
        return exchange(sock, "task_get", epoch=epoch, task_id=task)

    def stop(process, pid):
        assert shell(["readlink", f"/proc/{pid}/exe"], check=True).stdout.strip() == executable
        shell(["kill", "-15", pid], check=True)
        assert process.wait(timeout=10) == 143
        assert shell(["test", "-e", directory + "/droidvmd.pid"]).returncode != 0

    try:
        shell(["mkdir", directory], check=True)
        subprocess.run(adb + ["push", str(root / "build/migration/standalone-arm64-v8a/droidvm_service_fixture"), executable], check=True, timeout=30)
        shell(["chmod", "755", executable], check=True)
        apk = shell(["pm", "path", "cn.classfun.droidvm"], check=True).stdout.strip()
        assert apk.startswith("package:") and "\n" not in apk
        apk = apk.removeprefix("package:")
        process, pid, local_port, token = start()
        legacy = connect()
        assert exchange(legacy, "task_snapshot")["error"]["code"] == "UNAUTHENTICATED"
        auth(legacy)
        capabilities = exchange(legacy, "capabilities")
        epoch = capabilities["epoch"]
        assert capabilities["task_events"] == "explicit-subscription-v1" and capabilities["production_ready"] is False
        snapshot = exchange(legacy, "task_snapshot")
        assert snapshot["success"] and snapshot["epoch"] == epoch
        tasks = {value["kind"]: value for value in snapshot["items"]}
        running, queued, result_task = [tasks["migration." + name]["task_id"] for name in ("running", "queued", "result")]
        assert exchange(legacy, "task_snapshot_page", snapshot_id=snapshot["snapshot_id"], offset=-1)["error"]["code"] == "INVALID_ARGUMENT"
        assert exchange(legacy, "task_get", epoch="old", task_id=running)["error"]["code"] == "TASK_EPOCH_MISMATCH"
        assert exchange(legacy, "task_get", epoch=epoch, task_id=running, principal="forged")["task"]["owner"] == "local-admin"
        assert exchange(legacy, "task_snapshot_release", snapshot_id=snapshot["snapshot_id"])["success"]
        legacy.settimeout(0.15)
        try:
            legacy.recv(1)
            raise AssertionError("Old client received unsolicited events")
        except socket.timeout:
            pass
        legacy.settimeout(5)
        report["auth_errors_shared_epoch_legacy_opt_in"] = True

        observer = connect()
        auth(observer)
        stale = exchange(observer, "task_snapshot")
        time.sleep(2)
        assert exchange(observer, "task_subscribe", epoch=epoch, cursor=stale["cursor"])["error"]["code"] == "RESYNC_REQUIRED"
        assert exchange(observer, "task_snapshot_release", snapshot_id=stale["snapshot_id"])["success"]
        snapshot = exchange(observer, "task_snapshot")
        assert exchange(observer, "task_subscribe", epoch=epoch, cursor=snapshot["cursor"])["success"]
        value = receive(observer)
        assert value["type"] == "event" and value["event"] == "task_events" and value["epoch"] == epoch
        assert all(isinstance(item["seq"], str) for item in value["events"])
        events.append(value)
        for _ in range(20):
            assert exchange(observer, "ping", request="PING")["response"] == "PONG"
        before = int(get(observer, running)["task"]["completed"])
        assert exchange(observer, "task_snapshot_release", snapshot_id=snapshot["snapshot_id"])["success"]
        observer.close()
        report["snapshot_replay_live_response_interleaving"] = True
        print("Waiting 31 seconds with observer disconnected; task must keep running.", flush=True)
        time.sleep(31)
        resumed = connect()
        auth(resumed)
        task = get(resumed, running)["task"]
        assert task["state"] == "running" and int(task["completed"]) > before
        report["disconnect_survives_31_seconds"] = True
        snapshot = exchange(resumed, "task_snapshot")
        assert exchange(resumed, "task_subscribe", epoch=epoch, cursor=snapshot["cursor"])["success"]
        assert exchange(resumed, "task_cancel", epoch=epoch, task_id=queued)["task"]["state"] == "cancelled"
        assert exchange(resumed, "task_cancel", epoch=epoch, task_id=running)["task"]["state"] == "cancelling"
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            task = get(resumed, result_task)["task"]
            if task["state"] == "succeeded":
                break
            time.sleep(0.02)
        assert task["result"] == {"unicode": "任务 🧪", "integer": 9223372036854775807, "array": []}
        assert get(resumed, running)["task"]["state"] == "cancelled"
        assert get(resumed, queued)["task"]["state"] == "cancelled"
        assert exchange(resumed, "task_unsubscribe")["success"]
        for _ in range(100):
            reply = request("ping", request="PING")
            legacy.sendall(frame(reply))
            response = receive(legacy)
            assert response["type"] == "response" and response["request_id"] == reply["request_id"]
        report["cancel_result_legacy_isolation"] = True
        maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
        assert "libart.so" not in maps and "libjvm" not in maps
        stop(process, pid)
        process, pid, local_port, token = start()
        restarted = connect()
        auth(restarted)
        assert exchange(restarted, "capabilities")["epoch"] != epoch
        assert get(restarted, running)["error"]["code"] == "TASK_EPOCH_MISMATCH"
        assert exchange(restarted, "task_subscribe", epoch=epoch, cursor=snapshot["cursor"])["error"]["code"] == "RESYNC_REQUIRED"
        stop(process, pid)
        report["restart_invalidates_refs_shutdown_cleanup"] = True
        report["passed"] = True
    finally:
        for sock in sockets:
            sock.close()
        for process in processes:
            if process.poll() is None:
                candidate = read("droidvmd.pid")
                if candidate.isdecimal() and shell(["readlink", f"/proc/{candidate}/exe"]).stdout.strip() == executable:
                    shell(["kill", "-15", candidate])
                process.wait(timeout=10)
        for log in logs:
            log.close()
        for port in forwards:
            subprocess.run(adb + ["forward", "--remove", "tcp:" + str(port)], capture_output=True, timeout=10)
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        (output / "events.json").write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")
        shell(["rm", "-f", executable, directory + "/droidvmd.lock"])
        shell(["rmdir", directory])
        print("Results:", output)
    print("PASS: task TCP lifecycle, opt-in events, disconnect, cancellation and restart")


if __name__ == "__main__":
    main()
