# SPDX-License-Identifier: GPL-3.0-or-later
"""Disk registry TCP checks sharing the isolated VM fixture runner/cleanup."""
import base64
import json


def run(start, stop, connect, exchange, snapshot, check, shell, read, output, directory):
    def disk_snapshot(sock):
        return snapshot(sock, "disk_config_snapshot")

    def upload(sock, revision, value):
        data = json.dumps(value, separators=(",", ":")).encode()
        begun = exchange(sock, "disk_config_upload_begin", revision=revision, bytes=len(data))
        assert begun["success"], begun
        identity = begun["upload_id"]
        for offset in range(0, len(data), begun["chunk_bytes"]):
            part = data[offset:offset + begun["chunk_bytes"]]
            assert exchange(sock, "disk_config_upload_append", upload_id=identity, offset=offset,
                            data=base64.b64encode(part).decode())["received"] == offset + len(part)
        return identity

    process, pid, local, remote, token = start("--disk-config")
    with connect(local) as sock:
        for command in ("disk_config_status", "disk_config_snapshot", "disk_config_upload_begin", "disk_config_upload_commit"):
            assert exchange(sock, command)["error"]["code"] == "UNAUTHENTICATED"
    with connect(local, token) as sock:
        caps = exchange(sock, "capabilities")
        check(caps["service_api_minor"] == 23 and caps["disk_config_upload"] == "revisioned-chunks-v1",
              "disk registry read/write API23 advertised")
        _, state, document = disk_snapshot(sock)
        assert document == {"disks": []}
        pending = exchange(sock, "disk_config_upload_begin", revision=state["revision"], bytes=12)["upload_id"]
        assert exchange(sock, "disk_config_upload_append", upload_id=pending, offset=0, data="eyJkaXNr")["received"] == 6
    with connect(local, token) as sock:
        for _ in range(2):
            assert exchange(sock, "disk_config_upload_append", upload_id=pending, offset=6, data="cyI6W119")["received"] == 12
        assert exchange(sock, "disk_config_upload_abort", upload_id=pending)["success"]
        check(True, "disk upload survives reconnect and repeated chunk")
        stale = upload(sock, state["revision"], document)
        winner = upload(sock, state["revision"], {"disks": [], "python_probe": True})
        assert exchange(sock, "disk_config_upload_commit", upload_id=winner)["success"]
        assert exchange(sock, "disk_config_upload_commit", upload_id=stale)["error"]["code"] == "CONFLICT"
        assert exchange(sock, "disk_config_upload_commit", upload_id=stale)["error"]["code"] == "NOT_FOUND"
        check(True, "stale disk commit rejected and consumed once")

    java = shell(["am", "instrument", "-w", "-r", "-e", "class", "cn.classfun.droidvm.migration.DiskConfigTcpTest",
                  "-e", "port", remote, "-e", "token", token,
                  "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120)
    (output / "java-disk-tests.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
    check(java.returncode == 0 and "OK (1 test)" in java.stdout,
          "production Java disk client: large document, stale confirmation, lost reply, events, private snapshots")
    with connect(local, token) as sock:
        view, state, document = disk_snapshot(sock)
        check(document["python_probe"] and document["winner"] and document["lost_reply"] and
              document["future_root"]["integer"] == 9007199254740993 and len(document["disks"]) == 2,
              "Python agrees with production Java after lost reply")
        pending = upload(sock, state["revision"], document)
        check(json.loads(read("disks.json")) == document, "disk snapshot equals persisted registry")
        checksum = shell(["sha256sum", directory + "/disks.json"], check=True).stdout.split()[0]
    maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
    check("libart.so" not in maps and "libjvm" not in maps, "disk fixture has no ART/JVM")
    stop(process, pid)
    process, pid, local, _, token = start("--disk-config")
    with connect(local, token) as sock:
        current_view, current_state, current = disk_snapshot(sock)
        check(current == document and current_state["revision"] != state["revision"] and current_view["epoch"] != view["epoch"],
              "restart preserves registry and invalidates revision/epoch")
        assert exchange(sock, "disk_config_upload_commit", upload_id=pending)["error"]["code"] == "NOT_FOUND"
        assert exchange(sock, "disk_config_upload_begin", revision=state["revision"], bytes=12)["error"]["code"] == "CONFLICT"
        check(shell(["sha256sum", directory + "/disks.json"], check=True).stdout.split()[0] == checksum,
              "restart and stale requests do not rewrite registry")
    stop(process, pid)
