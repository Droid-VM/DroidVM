# SPDX-License-Identifier: GPL-3.0-or-later
"""Coordinated TCP scenarios, using the existing isolated fixture runner."""
import base64
import json
import shlex
import uuid


def run(start, stop, connect, exchange, snapshot, check, shell, directory):
    scenarios = [(None, None)] + [
        (mode, stage) for mode in ("--fail-once", "--crash-once")
        for stage in ("before_decision", "participant_synced", "decision_removed")]
    networks = [{"id": str(uuid.uuid4()), "name": f"fixture-{i}", "schema": 2,
                 "bridge_name": f"test{i}", "bridge_type": "gvisor", "uplink_mode": "l3",
                 "l3": {"vlans": []}, "extension": "preserved"} for i in range(len(scenarios))]
    vm = {"id": str(uuid.uuid4()), "name": "fixture VM", "extension": "preserved",
          "networks": [{"network_id": row["id"], "extension": "preserved"} for row in networks]}
    # Seed only this run's new directory, before any fixture/ownership claim.
    for name, value in (("vms.json", {"vms": [vm], "extension": "preserved"}),
                        ("networks.json", {"networks": networks, "extension": "preserved"})):
        shell(["sh", "-c", "printf %s " + shlex.quote(json.dumps(value)) + " > " +
               shlex.quote(directory + "/" + name)], check=True)

    def upload(local, token, revision, expected):
        payload = json.dumps({"expected": expected}, separators=(",", ":")).encode()
        with connect(local, token) as sock:
            begun = exchange(sock, "network_config_delete_begin", revision=revision, bytes=len(payload))
            assert begun["success"], begun
            upload_id = begun["upload_id"]
            cut = len(payload) // 2
            assert exchange(sock, "network_config_delete_append", upload_id=upload_id, offset=0,
                            data=base64.b64encode(payload[:cut]).decode())["received"] == cut
        with connect(local, token) as sock:
            for _ in range(2):
                assert exchange(sock, "network_config_delete_append", upload_id=upload_id, offset=cut,
                                data=base64.b64encode(payload[cut:]).decode())["received"] == len(payload)
        return upload_id

    remaining = list(networks)
    for index, (mode, stage) in enumerate(scenarios):
        options = ["--coordinated"] + ([mode, stage] if mode else [])
        process, pid, local, _, token = start(*options)
        target = networks[index]
        with connect(local) as sock:
            for command in ("network_config_delete_begin", "configuration_recover"):
                assert exchange(sock, command)["error"]["code"] == "UNAUTHENTICATED"
        with connect(local, token) as sock:
            caps = exchange(sock, "capabilities")
            assert caps["network_config_delete"] == "revisioned-stop-chunks-v1"
            assert caps["configuration_recover"] == "redo-v1"
            _, metadata, before = snapshot(sock)
            _, _, network_document = snapshot(sock, "network_config_document")
            expected = next(row for row in network_document["networks"] if row["id"] == target["id"])
            assert exchange(sock, "network_start", network_id=target["id"])["success"]
            assert exchange(sock, "network_delete", network_id=target["id"])["error"]["code"] == "CONFLICT"
        maps = shell(["cat", f"/proc/{pid}/maps"], check=True).stdout
        assert "libart.so" not in maps and "libjvm" not in maps
        # A stale confirmation must fail without consuming the one-shot fault.
        stale = dict(expected, name="different confirmation")
        stale_id = upload(local, token, metadata["revision"], stale)
        with connect(local, token) as sock:
            assert exchange(sock, "network_config_delete_commit", upload_id=stale_id)["error"]["code"] == "CONFLICT"
        upload_id = upload(local, token, metadata["revision"], expected)
        with connect(local, token) as sock:
            if mode == "--crash-once":
                try:
                    exchange(sock, "network_config_delete_commit", upload_id=upload_id)
                except (EOFError, ConnectionError):
                    pass
                else:
                    raise AssertionError("crash injection returned a response")
            else:
                reply = exchange(sock, "network_config_delete_commit", upload_id=upload_id)
                if mode:
                    code = "IO_ERROR" if stage == "before_decision" else "COMMIT_UNCERTAIN"
                    assert reply["error"]["code"] == code, reply
                    if stage != "before_decision":
                        assert exchange(sock, "vm_config_snapshot")["error"]["code"] == "UNAVAILABLE"
                    assert exchange(sock, "configuration_recover")["success"]
                else:
                    assert reply["success"] and reply["network_id"] == target["id"], reply
                assert exchange(sock, "network_config_delete_commit", upload_id=upload_id)["error"]["code"] == "NOT_FOUND"
        if mode == "--crash-once":
            assert process.wait(timeout=15) == 137
            process, pid, local, _, token = start("--coordinated")
        if stage == "before_decision":
            with connect(local, token) as sock:
                _, metadata, unchanged = snapshot(sock)
                assert unchanged == before
                _, _, unchanged_networks = snapshot(sock, "network_config_document")
                assert unchanged_networks == network_document
            retry = upload(local, token, metadata["revision"], expected)
            with connect(local, token) as sock:
                assert exchange(sock, "network_config_delete_commit", upload_id=retry)["success"]
        remaining = [row for row in remaining if row["id"] != target["id"]]
        with connect(local, token) as sock:
            _, _, after = snapshot(sock)
            _, _, after_networks = snapshot(sock, "network_config_document")
            wanted = json.loads(json.dumps(before))
            wanted["vms"][0]["networks"] = [nic for nic in wanted["vms"][0]["networks"]
                                             if nic["network_id"] != target["id"]]
            assert after == wanted, after
            assert after_networks["networks"] == remaining, after_networks
        persisted = json.loads(shell(["cat", directory + "/networks.json"], check=True).stdout)
        assert persisted == {"networks": remaining, "extension": "preserved"}, persisted
        persisted_vm = json.loads(shell(["cat", directory + "/vms.json"], check=True).stdout)
        assert persisted_vm == after, persisted_vm
        check(True, f"coordinated TCP delete: {mode or 'success'} {stage or ''}; fixed confirmation, chunks, NICs, recovery")
        stop(process, pid)
