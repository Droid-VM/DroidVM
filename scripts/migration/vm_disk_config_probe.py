# SPDX-License-Identifier: GPL-3.0-or-later
"""Joint registry TCP checks using the isolated runner's lifecycle and cleanup."""
import base64
import json
import time
import uuid


def run(start, stop, connect, exchange, snapshot, check, shell, read, output, directory):
    prefix = "configuration_vm_disks_"

    def pair(sock):
        return snapshot(sock), snapshot(sock, "disk_config_snapshot")

    def upload(sock, states, documents):
        data = json.dumps(dict(zip(("vms", "disks"), documents)), separators=(",", ":")).encode()
        begun = exchange(sock, prefix + "begin", vm_revision=states[0]["revision"],
                         disk_revision=states[1]["revision"], bytes=len(data))
        assert begun["success"], begun
        identity = begun["upload_id"]
        for offset in range(0, len(data), begun["chunk_bytes"]):
            part = data[offset:offset + begun["chunk_bytes"]]
            assert exchange(sock, prefix + "append", upload_id=identity, offset=offset,
                            data=base64.b64encode(part).decode())["received"] == offset + len(part)
        return identity

    def persisted(documents):
        check(all(json.loads(read(name)) == doc for name, doc in
                  zip(("vms.json", "disks.json"), documents)), "both persisted registries equal snapshots")

    process, pid, local, remote, token = start("--vm-disks")
    with connect(local) as sock:
        for suffix in ("begin", "append", "commit", "abort"):
            assert exchange(sock, prefix + suffix)["error"]["code"] == "UNAUTHENTICATED"
    with connect(local, token) as sock:
        caps = exchange(sock, "capabilities")
        check(caps["service_api_minor"] == 25 and caps["configuration_vm_disks"] == "revisioned-chunks-v1",
              "joint registry API24 advertised")
        initial = pair(sock)
        states = [entry[1] for entry in initial]
        documents = [entry[2] for entry in initial]
        assert documents == [{"vms": []}, {"disks": []}]
        stale = upload(sock, states, documents)
        winner_docs = [dict(doc, python_probe=True) for doc in documents]
        winner = upload(sock, states, winner_docs)
        # Repeat the complete short chunk after reconnect, before consuming it.
        data = json.dumps(dict(zip(("vms", "disks"), winner_docs)), separators=(",", ":")).encode()
    with connect(local, token) as sock:
        assert exchange(sock, prefix + "append", upload_id=winner, offset=0,
                        data=base64.b64encode(data).decode())["received"] == len(data)
        assert exchange(sock, prefix + "commit", upload_id=winner)["success"]
        assert exchange(sock, prefix + "commit", upload_id=stale)["error"]["code"] == "CONFLICT"
        assert exchange(sock, prefix + "commit", upload_id=stale)["error"]["code"] == "NOT_FOUND"
        check(True, "joint upload reconnect, duplicate chunk and stale commit consumption")

    java = shell(["am", "instrument", "-w", "-r", "-e", "class",
                  "cn.classfun.droidvm.migration.ConfigurationTcpTest", "-e", "port", remote,
                  "-e", "token", token,
                  "cn.classfun.droidvm.migration.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120)
    (output / "java-vm-disk-tests.txt").write_text(java.stdout + java.stderr, encoding="utf-8")
    check(java.returncode == 0 and "OK (1 test)" in java.stdout,
          "production joint Java client: references, conflicts, chunks, lost reply and events")
    with connect(local, token) as sock:
        before = pair(sock)
        documents = [entry[2] for entry in before]
        check(all(doc["python_probe"] and doc["winner"] and doc["joint_lost"] and
                  doc["future_integer"] == 9007199254740993 for doc in documents) and
              len(documents[0]["vms"]) == 1 and documents[0]["vms"][0]["disks"] == [] and
              documents[1]["disks"] == [], "Python confirms Java's joint detach after lost reply")
        pending = upload(sock, [entry[1] for entry in before], documents)
        persisted(documents)
        checksums = shell(["sha256sum", directory + "/vms.json", directory + "/disks.json"], check=True).stdout
    maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
    check("libart.so" not in maps and "libjvm" not in maps, "joint fixture has no ART/JVM")
    stop(process, pid)
    process, pid, local, _, token = start("--vm-disks")
    with connect(local, token) as sock:
        after = pair(sock)
        check(all(new[2] == old[2] and new[1]["revision"] != old[1]["revision"] and
                  new[0]["epoch"] != old[0]["epoch"] for old, new in zip(before, after)),
              "restart preserves both documents and invalidates both revisions/epochs")
        assert exchange(sock, prefix + "commit", upload_id=pending)["error"]["code"] == "NOT_FOUND"
        assert exchange(sock, prefix + "begin", vm_revision=before[0][1]["revision"],
                        disk_revision=before[1][1]["revision"], bytes=32)["error"]["code"] == "CONFLICT"
        check(shell(["sha256sum", directory + "/vms.json", directory + "/disks.json"], check=True).stdout == checksums,
              "restart and stale joint requests do not rewrite either file")
    stop(process, pid)

    for mode in ("--fail-once", "--crash-once"):
        for stage in ("before_decision", "participant_synced", "decision_removed"):
            process, pid, local, _, token = start("--vm-disks", mode, stage)
            with connect(local, token) as sock:
                before = pair(sock)
                old_docs = [entry[2] for entry in before]
                new_docs = [dict(doc, fault_marker=mode + ":" + stage) for doc in old_docs]
                identity = upload(sock, [entry[1] for entry in before], new_docs)
                if mode == "--crash-once":
                    try:
                        exchange(sock, prefix + "commit", upload_id=identity)
                    except (EOFError, ConnectionError):
                        pass
                    else:
                        raise AssertionError("crash injection returned a response")
                else:
                    reply = exchange(sock, prefix + "commit", upload_id=identity)
                    assert reply["error"]["code"] == ("IO_ERROR" if stage == "before_decision" else "COMMIT_UNCERTAIN"), reply
                    if stage != "before_decision":
                        for command in ("vm_config_snapshot", "disk_config_snapshot"):
                            assert exchange(sock, command)["error"]["code"] == "UNAVAILABLE"
                    assert exchange(sock, "configuration_recover")["success"]
                    assert exchange(sock, prefix + "commit", upload_id=identity)["error"]["code"] == "NOT_FOUND"
            if mode == "--crash-once":
                assert process.wait(timeout=15) == 137
                process, pid, local, _, token = start("--vm-disks")
            with connect(local, token) as sock:
                actual = [entry[2] for entry in pair(sock)]
                check(actual == (old_docs if stage == "before_decision" else new_docs),
                      f"joint recovery publishes matching documents: {mode} {stage}")
                persisted(actual)
            stop(process, pid)

    # Native disk operations: the fixture reads <directory>/usr/bin/qemu-img.
    installed = "/data/data/cn.classfun.droidvm/usr/bin/qemu-img"
    tool, images = directory + "/usr/bin/qemu-img", directory + "/images"
    copied = shell(["su", "-c", f"mkdir -p {directory}/usr/bin {images} && cp {installed} {tool} && "
                    f"chown -R 2000:2000 {directory}/usr {images} && chmod 0755 {tool}"])
    check(copied.returncode == 0, "isolated qemu-img copy for the fixture")
    shell([tool, "create", "-q", "-f", "raw", images + "/base.raw", "1M"], check=True)
    shell(["sh", "-c", f"printf BASEDATA | dd of={images}/base.raw conv=notrunc 2>/dev/null"], check=True)
    shell([tool, "create", "-q", "-f", "qcow2", "-F", "raw", "-b", images + "/base.raw", images + "/ov.qcow2"], check=True)
    process, pid, local, _, token = start("--vm-disks")

    def finish(sock, started):
        assert started["success"], started
        deadline = time.monotonic() + 30
        while True:
            task = exchange(sock, "task_get", epoch=started["epoch"], task_id=started["task_id"])["task"]
            if task["state"] in ("succeeded", "failed", "cancelled"):
                return task
            check(time.monotonic() < deadline, "disk operation task completes")
            time.sleep(.02)

    def image(path):
        return json.loads(shell([tool, "info", "--output=json", path], check=True).stdout)

    with connect(local) as sock:
        assert exchange(sock, "disk_operation_start")["error"]["code"] == "UNAUTHENTICATED"
    with connect(local, token) as sock:
        caps = exchange(sock, "capabilities")
        check(caps["service_api_minor"] == 25 and caps["disk_operations"] == "revisioned-tasks-v1"
              and {"disk_operation_start", "disk_operation_recover"} <= set(caps["commands"])
              and caps["disk_operation_actions"] == ["resize", "convert", "flatten"], "disk operations API25 advertised")
        states = pair(sock)
        documents = [entry[2] for entry in states]
        base_id, overlay_id = str(uuid.uuid4()), str(uuid.uuid4())
        documents[1] = dict(documents[1], disks=documents[1]["disks"] + [
            {"id": base_id, "name": "base.raw", "folder": images},
            {"id": overlay_id, "name": "ov.qcow2", "folder": images, "parent": base_id}])
        assert exchange(sock, prefix + "commit", upload_id=upload(sock, [e[1] for e in states], documents))["success"]
        revision = exchange(sock, "disk_config_status")["revision"]
        assert exchange(sock, "disk_operation_start", disk_revision="stale", disk_id=overlay_id,
                        action="flatten")["error"]["code"] == "CONFLICT"
        assert exchange(sock, "disk_operation_start", disk_revision=revision, disk_id=overlay_id,
                        action="flatten", output="/elsewhere")["error"]["code"] == "INVALID_ARGUMENT"
        assert exchange(sock, "disk_operation_start", disk_revision=revision, disk_id=base_id,
                        action="convert")["error"]["code"] == "CONFLICT"
        check(True, "disk operation admission: stale revision, unknown field and base with overlays")
        task = finish(sock, exchange(sock, "disk_operation_start", disk_revision=revision, disk_id=overlay_id,
                                     action="resize", size=2097152))
        info = image(images + "/ov.qcow2")
        check(task["state"] == "succeeded" and task["kind"] == "disk_operation" and info["virtual-size"] == 2097152
              and info["backing-filename"] == images + "/base.raw", "TCP resize keeps overlay backing")
        check(exchange(sock, "disk_config_status")["revision"] == revision, "resize leaves the registry unchanged")
        task = finish(sock, exchange(sock, "disk_operation_start", disk_revision=revision, disk_id=overlay_id,
                                     action="flatten", compress="zstd"))
        info = image(images + "/ov.qcow2")
        rows = {row["id"]: row for row in json.loads(read("disks.json"))["disks"]}
        check(task["state"] == "succeeded" and "backing-filename" not in info and "parent" not in rows[overlay_id]
              and info["format-specific"]["data"]["compression-type"] == "zstd"
              and exchange(sock, "disk_config_status")["revision"] != revision, "TCP flatten persists standalone link")
        check(exchange(sock, "disk_operation_recover")["pending"] == 0
              and "disk_operations.json" in shell(["ls", directory]).stdout, "no unrecovered disk operations")
    maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
    check("libart.so" not in maps and "libjvm" not in maps, "disk operation fixture has no ART/JVM")
    stop(process, pid)
