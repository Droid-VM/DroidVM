# SPDX-License-Identifier: GPL-3.0-or-later
"""vm_export/vm_import: the native daemon over TCP against the production Java tasks run as root.

Packages cross in both directions (native export -> Java import, Java export -> native import) for
every compression, single-file and split, with a qcow2 backing chain, boot files and a network;
corrupted packages and refused requests must fail the same way on both sides. Everything lives in
/data/local/tmp/droidvm-pkg-<nonce>; no VM is started and the production app is not touched."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import re
import shlex
import socket
import subprocess
import time
import uuid
import zipfile

from run_daemon_probe import frame, receive, request

PACKAGE = "cn.classfun.droidvm"
QEMU_IMG = f"/data/data/{PACKAGE}/usr/bin/qemu-img"
COMPRESSIONS = ("NONE", "GZIP", "XZ", "ZSTD")
VOLUME = 16 << 20


class Client:
    """One authenticated TCP session; legacy broadcasts are kept apart from responses."""

    def __init__(self, port, token):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=60)
        self.events = []
        assert self.call("auth", token=token)["success"]

    def call(self, command, **fields):
        value = request(command, **fields)
        self.sock.sendall(frame(value))
        while True:
            message = receive(self.sock)
            if message.get("type") == "event":
                self.events.append(message)
                continue
            assert message["request_id"] == value["request_id"], message
            return message

    def wait(self, event, task_id, timeout=900):
        """Every broadcast of one task, through its done/error."""
        deadline = time.monotonic() + timeout
        rows, seen = [], 0
        while True:
            for message in self.events[seen:]:
                data = message.get("data", {})
                if data.get("event") == event and data.get("task_id") == task_id:
                    rows.append(data)
                    if data.get("phase") in ("done", "error"):
                        return rows
            seen = len(self.events)
            if time.monotonic() > deadline:
                raise TimeoutError(event + " " + task_id)
            self.sock.settimeout(max(1, deadline - time.monotonic()))
            self.events.append(receive(self.sock))

    def close(self):
        self.sock.close()


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--daemon", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvmd")
    # The Java daemon left the APK; the oracle runs against an archived pre-removal build.
    parser.add_argument("--oracle-apk", type=Path, default=root / "build/migration/oracle/java-daemon-oracle.apk")
    parser.add_argument("--contract", type=Path, default=root / "build/migration/standalone-arm64-v8a/droidvm_pkg_contract")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/pkg-device" / nonce
    output.mkdir(parents=True)
    base = f"/data/local/tmp/droidvm-pkg-{nonce}"
    data = base + "/data"
    adb = ["adb", "-s", args.serial]
    apk = args.oracle_apk
    test_apk = root / "migration-tests/build/outputs/apk/androidTest/debug/migration-tests-debug-androidTest.apk"
    report = {"checks": [], "differences": [], "daemon_sha256": hashlib.sha256(args.daemon.read_bytes()).hexdigest(),
              "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    process, forward = None, None

    def sh(command, timeout=600, check=True):
        # Wireless ADB drops under load; a dropped transport is retried, a failing command is not.
        for attempt in range(8):
            result = subprocess.run(adb + ["shell", command], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
            if "device offline" not in result.stderr and "not found" not in result.stderr.lower() or attempt == 7:
                break
            subprocess.run(["adb", "disconnect", args.serial], capture_output=True, timeout=20)
            time.sleep(10)
            subprocess.run(["adb", "connect", args.serial], capture_output=True, timeout=30)
        if check and result.returncode != 0:
            raise RuntimeError(f"{command}: {result.stdout}{result.stderr}")
        return result.stdout

    def detached(command, out):
        """Runs on the device without holding an ADB connection; out.rc holds the exit status."""
        sh(f"setsid sh -c {shlex.quote(command + f' > {out} 2>&1; echo $? > {out}.rc')} < /dev/null > /dev/null 2>&1 &")

    def check(condition, name, detail=None):
        report["checks"].append(name)
        if not condition:
            report["differences"].append({"check": name, "detail": detail})
            raise AssertionError(name + ": " + json.dumps(detail, ensure_ascii=False)[:4000])

    def sha(path):
        return sh(f"sha256sum {shlex.quote(path)}").split()[0]

    def head(path):
        """Header fields and the plain manifest of a package (single file or master)."""
        raw = base64.b64decode(sh(f"head -c 73728 {shlex.quote(path)} | base64 -w 0"))
        assert raw[:6] == b"VMPKG\0", path
        size = int.from_bytes(raw[10:12], "little")
        header = {"manifest_version": int.from_bytes(raw[6:8], "little"), "app_version_code": int.from_bytes(raw[8:10], "little"),
                  "manifest_size": size, "compression": int.from_bytes(raw[12:14], "little"),
                  "volume_count": int.from_bytes(raw[14:16], "little"), "data_size": int.from_bytes(raw[16:24], "little")}
        return header, json.loads(raw[4096:4096 + size].decode("utf-8"))

    # Fixtures: a 17 MiB raw disk (two 16 MiB volumes once compressed), a qcow2 overlay whose
    # base is named relatively, a second base.qcow2 elsewhere (archive name collision), manual
    # kernel/initrd boot files and a DHCP-leased NIC on an L3 network.
    vm_id, broken_id, net_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    network = {"schema": 2, "id": net_id, "name": "pkgnet", "bridge_name": "brpkg", "bridge_type": "linux", "uplink_mode": "l3",
               "l3": {"vlans": [{"vlan_id": 0, "ipv4": {"cidr": "10.77.0.1/24", "snat": True, "dhcp": {"enabled": True}}}]}}
    vm = {"id": vm_id, "name": "pkg probe", "memory_mb": 512, "cpu_count": 2,
          "boot": {"protocol": "linux", "linux": {"source": "manual", "kernel": base + "/src/boot/vmlinuz", "initrd": base + "/src/boot/initrd.img",
                                                  "cmdline": "console=hvc0"}},
          "disks": [{"path": base + "/src/disk.img", "readonly": False, "bus": "virtio"},
                    {"path": base + "/src/chain/overlay.qcow2", "readonly": False, "bus": "virtio"},
                    {"path": base + "/src//other/../other/base.qcow2", "readonly": True, "bus": "scsi"}],
          "networks": [{"network_id": net_id, "mac_address": "02:00:00:00:00:01", "tap_name": "vmtap0",
                        "dhcp4_lease": {"enabled": True, "offset": 70}},
                       {"network_id": str(uuid.uuid4()), "mac_address": "02:00:00:00:00:02"}]}
    broken = {"id": broken_id, "name": "broken chain", "disks": [{"path": base + "/src/broken/overlay.qcow2"}]}
    try:
        sh(f"mkdir -p {base}/src/chain {base}/src/other {base}/src/boot {base}/src/broken {data}/files {data}/usr/bin "
           f"{base}/out-native {base}/out-java {base}/imp-native {base}/imp-java {base}/corrupt {base}/bin")
        sh(f"cd {base}/src && head -c 17825792 /dev/urandom > disk.img && head -c 2097152 /dev/urandom > base.raw && "
           f"head -c 1048576 /dev/urandom > boot/vmlinuz && head -c 524288 /dev/urandom > boot/initrd.img && "
           f"{QEMU_IMG} convert -f raw -O qcow2 base.raw chain/base.qcow2 && "
           f"cd chain && {QEMU_IMG} create -q -f qcow2 -b base.qcow2 -F qcow2 overlay.qcow2 && cd .. && "
           f"{QEMU_IMG} create -q -f qcow2 other/base.qcow2 4M && "
           f"{QEMU_IMG} create -q -f qcow2 broken/gone.qcow2 4M && cd broken && {QEMU_IMG} create -q -f qcow2 -b gone.qcow2 -F qcow2 overlay.qcow2 && "
           f"rm gone.qcow2 && ln -s {QEMU_IMG} {data}/usr/bin/qemu-img")
        sources = {name: sha(f"{base}/src/{name}") for name in ("disk.img", "chain/base.qcow2", "other/base.qcow2", "boot/vmlinuz", "boot/initrd.img")}
        seeds = output / "seeds"
        seeds.mkdir()
        (seeds / "vms.json").write_text(json.dumps({"vms": [vm, broken]}, ensure_ascii=False), encoding="utf-8")
        (seeds / "networks.json").write_text(json.dumps({"networks": [network]}, ensure_ascii=False), encoding="utf-8")
        with zipfile.ZipFile(apk) as archive:
            (output / "libzstd-jni.so").write_bytes(archive.read("lib/arm64-v8a/libzstd-jni-1.5.7-11.so"))
        for local, remote in ((output / "libzstd-jni.so", base + "/bin/libzstd-jni.so"), (args.daemon, base + "/bin/droidvmd"), (apk, base + "/bin/app.apk"), (test_apk, base + "/bin/test.apk"),
                              (seeds / "vms.json", data + "/files/vms.json"), (seeds / "networks.json", data + "/files/networks.json")):
            subprocess.run(adb + ["push", str(local), remote], check=True, capture_output=True, timeout=180)
        sh(f"chmod 755 {base}/bin/droidvmd")
        # The building blocks first: tar, codecs, layout, volumes and plans on this device.
        subprocess.run(adb + ["push", str(args.contract), base + "/bin/contract"], check=True, capture_output=True, timeout=180)
        contract = json.loads(sh(f"chmod 755 {base}/bin/contract && {base}/bin/contract", check=False))
        (output / "contract.json").write_text(json.dumps(contract, indent=1), encoding="utf-8")
        check(contract.get("passed") is True, "droidvm_pkg_contract", contract)
        production_apk = sh(f"pm path {PACKAGE}").strip().removeprefix("package:")
        detached(shlex.join([base + "/bin/droidvmd", "--data-dir", data, "--apk", production_apk]), base + "/daemon.out")
        process = True
        deadline = time.monotonic() + 30
        while True:
            text = sh(f"cat {base}/daemon.out 2>/dev/null; cat {base}/daemon.out.rc 2>/dev/null; true")
            ready = re.search(r"droidvmd ready pid=(\d+) port=(\d+)", text)
            if ready:
                break
            if time.monotonic() > deadline or re.search(r"^\d+$", text.strip().splitlines()[-1] if text.strip() else ""):
                raise RuntimeError("daemon startup: " + text)
            time.sleep(0.2)
        pid, port = ready.groups()
        token = sh(f"cat {data}/run/droidvmd-token.txt").strip()
        forward = int(subprocess.run(adb + ["forward", "tcp:0", "tcp:" + port], check=True, capture_output=True, text=True, timeout=10).stdout.strip())
        client = Client(forward, token)
        caps = client.call("capabilities")
        check("vm_export" in caps["commands"] and "vm_import" in caps["commands"] and caps.get("vm_packages") is True, "capabilities advertise packages")
        check(caps.get("package_tasks") is True, "package tasks use the shared registry")

        def native(command, req, timeout=900):
            response = client.call(command, **req)
            row = {k: v for k, v in response.items() if k not in ("type", "request_id")}
            if not response.get("success"):
                return {"failed": response.get("message")}
            row["events"] = client.wait("vm_export_status" if command == "vm_export" else "vm_import_status", response["task_id"], timeout)
            deadline = time.monotonic() + 10
            while True:
                task = client.call("task_get", epoch=response["epoch"], task_id=response["task_id"])
                check(task.get("success") is True, "package task remains queryable")
                task = task["task"]
                if task["state"] in ("succeeded", "failed", "cancelled"):
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError("package task terminal state")
                time.sleep(.02)
            check(task["kind"] == command and task["owner"] == "local-admin", "package task trusted owner and kind")
            check(task["state"] == ("succeeded" if row["events"][-1]["phase"] == "done" else "failed"), "legacy event and retained task agree")
            row["task"] = task
            return row

        # 1. Native exports.
        cases = [(c, mode) for c in COMPRESSIONS for mode in ("single", "split")]
        export_request = lambda out, c, mode, **extra: dict({"vm_id": vm_id, "dest_path": f"{base}/{out}/{c.lower()}-{mode}.vmpkg", "compression": c,
                                                             "volume_size": VOLUME if mode == "split" else 0, "disks": []}, **extra)
        native_exports = {}
        for c, mode in cases:
            native_exports[(c, mode)] = native("vm_export", export_request("out-native", c, mode))
        native_exports[("ZSTD", "subset")] = native("vm_export", export_request("out-native", "zstd", "subset", disks=[1]))
        refusals = [("missing vm_id", {"dest_path": base + "/x.vmpkg"}), ("missing dest_path", {"vm_id": vm_id}),
                    ("small volume", {"vm_id": vm_id, "dest_path": base + "/x.vmpkg", "volume_size": 1000}),
                    ("unknown vm", {"vm_id": str(uuid.uuid4()), "dest_path": base + "/x.vmpkg"}),
                    ("bad vm id", {"vm_id": "nope", "dest_path": base + "/x.vmpkg"}),
                    ("relative dest", {"vm_id": vm_id, "dest_path": "x.vmpkg"}),
                    ("broken chain", {"vm_id": broken_id, "dest_path": base + "/x.vmpkg"}),
                    ("unwritable dest", {"vm_id": vm_id, "dest_path": base + "/missing-dir/x.vmpkg", "compression": "none"})]
        native_refusals = {name: native("vm_export", req) for name, req in refusals}

        # Corrupted copies of native packages, read by both importers.
        n = f"{base}/out-native"
        k = f"{base}/corrupt"
        size = int(sh(f"stat -c %s {n}/gzip-single.vmpkg").strip())
        sh(f"head -c {size - 5000} {n}/gzip-single.vmpkg > {k}/truncated.vmpkg && "
           f"cp {n}/gzip-single.vmpkg {k}/flipped.vmpkg && printf '\\377' | dd of={k}/flipped.vmpkg bs=1 seek={size // 2} conv=notrunc 2>/dev/null && "
           f"cp {n}/zstd-single.vmpkg {k}/magic.vmpkg && printf 'X' | dd of={k}/magic.vmpkg bs=1 seek=0 conv=notrunc 2>/dev/null && "
           f"cp {n}/zstd-single.vmpkg {k}/trailing.vmpkg && head -c 4096 /dev/urandom >> {k}/trailing.vmpkg && "
           f"cp {n}/xz-single.vmpkg {k}/manifest.vmpkg && printf '[' | dd of={k}/manifest.vmpkg bs=1 seek=4096 conv=notrunc 2>/dev/null && "
           f"cp {n}/none-split.vmpkg {k}/missing.vmpkg && cp {n}/none-split.vmpkg.001 {k}/missing.vmpkg.001 && "
           f"cp {n}/none-split.vmpkg {k}/crc.vmpkg && cp {n}/none-split.vmpkg.001 {k}/crc.vmpkg.001 && cp {n}/none-split.vmpkg.002 {k}/crc.vmpkg.002 && "
           f"printf '\\377' | dd of={k}/crc.vmpkg.002 bs=1 seek=1000 conv=notrunc 2>/dev/null && "
           f"cp {n}/none-split.vmpkg {k}/short.vmpkg && cp {n}/none-split.vmpkg.001 {k}/short.vmpkg.001 && head -c 1000 {n}/none-split.vmpkg.002 > {k}/short.vmpkg.002")
        corrupt = ["truncated", "flipped", "magic", "trailing", "manifest", "missing", "crc", "short", "absent"]

        # 2. The Java side: the same exports, and imports of the native packages.
        steps = []
        seeded = {"vms": [vm, broken], "networks": [network]}
        for c, mode in cases:
            steps.append(dict(seeded, name=f"export {c} {mode}", command="vm_export", request=export_request("out-java", c, mode)))
        steps.append(dict(seeded, name="export ZSTD subset", command="vm_export", request=export_request("out-java", "zstd", "subset", disks=[1])))
        for name, req in refusals:
            steps.append(dict(seeded, name="refuse " + name, command="vm_export", request=req))
        plans = {"create": {}, "join": {"network_plan": [{"ref": "net0", "action": "join", "network_id": net_id}]},
                 "skip": {"network_mode": "skip"}}

        def import_request(side, source, case, plan="create"):
            return dict({"src_path": source, "target_dir": f"{base}/imp-{side}/{case}"}, **plans[plan])

        def source_of(producer, c, mode):
            return f"{base}/out-{producer}/{c.lower()}-{mode}.vmpkg" + (".002" if mode == "split" and c == "ZSTD" else "")

        import_cases = [(c, mode, "create") for c, mode in cases] + [("ZSTD", "subset", "create"), ("XZ", "single", "join"), ("GZIP", "split", "skip")]
        for c, mode, plan in import_cases:
            steps.append(dict(seeded, name=f"import {c} {mode} {plan}", command="vm_import",
                              request=import_request("java", source_of("native", c, mode), f"{c.lower()}-{mode}-{plan}", plan)))
        for name in corrupt:
            steps.append(dict(seeded, name="corrupt " + name, command="vm_import",
                              request=import_request("java", f"{k}/{name}.vmpkg", "corrupt-" + name)))
        import_refusals = [("missing src", {"target_dir": base + "/t"}), ("missing target", {"src_path": base + "/x"}),
                           ("relative src", {"src_path": "x.vmpkg", "target_dir": base + "/t"})]
        for name, req in import_refusals:
            steps.append(dict(seeded, name="refuse import " + name, command="vm_import", request=req))
        (output / "steps.json").write_text(json.dumps(steps, ensure_ascii=False, indent=1), encoding="utf-8")
        subprocess.run(adb + ["push", str(output / "steps.json"), base + "/bin/steps.json"], check=True, capture_output=True, timeout=60)
        # The phone runs close to its memory limit; keep lmkd from picking the oracle first.
        detached(f"cd {base}/bin && echo -800 > /proc/self/oom_score_adj && PKG_ORACLE_ZSTD_JNI={base}/bin/libzstd-jni.so CLASSPATH={base}/bin/app.apk:{base}/bin/test.apk app_process64 / "
                 f"cn.classfun.droidvm.migration.PkgOracle {base}/bin/steps.json {base}/bin/java.json", base + "/bin/oracle.out")
        deadline = time.monotonic() + 3600
        while not sh(f"cat {base}/bin/oracle.out.rc 2>/dev/null; true").strip():
            check(time.monotonic() < deadline, "Java oracle finishes in time")
            time.sleep(5)
        oracle_text = sh(f"cat {base}/bin/oracle.out")
        (output / "oracle.txt").write_text(oracle_text, encoding="utf-8")
        check(sh(f"cat {base}/bin/oracle.out.rc").strip() == "0", "Java oracle ran", oracle_text[-4000:])
        java = {row["name"]: row for row in json.loads(sh(f"cat {base}/bin/java.json"))}
        (output / "java.json").write_text(json.dumps(java, ensure_ascii=False, indent=1), encoding="utf-8")
        for name, row in java.items():
            check(row["status"] == "ok", "Java step ran: " + name, row)

        # 3. Native imports of the Java packages, the corrupt set and the refusals. Each imported VM
        # and created network is removed again so every import starts from the seeded stores.
        native_imports = {}
        for c, mode, plan in import_cases:
            native_imports[(c, mode, plan)] = row = native("vm_import", import_request("native", source_of("java", c, mode), f"{c.lower()}-{mode}-{plan}", plan))
            done = row.get("events", [{}])[-1]
            if done.get("phase") == "done":
                row["vm"] = client.call("vm_get", vm_id=done["vm_id"])["data"]
                assert client.call("vm_delete", vm_id=done["vm_id"])["existed"]
                for created in done.get("networks", []):
                    assert client.call("network_delete", network_id=created["id"])["success"]
        native_corrupt = {name: native("vm_import", import_request("native", f"{k}/{name}.vmpkg", "corrupt-" + name)) for name in corrupt}
        native_import_refusals = {name: native("vm_import", req) for name, req in import_refusals}
        (output / "native.json").write_text(json.dumps({"exports": {" ".join(key): v for key, v in native_exports.items()},
                                                        "imports": {" ".join(key): v for key, v in native_imports.items()},
                                                        "refusals": native_refusals, "corrupt": native_corrupt,
                                                        "import_refusals": native_import_refusals}, ensure_ascii=False, indent=1), encoding="utf-8")

        # Comparisons. Progress ticks are timing; paths differ by side; ids, MACs and times are fresh.
        def events(rows, side):
            out, started = [], None
            for data in rows:
                # Per file: the 0-byte start and the completion (done + 1) stay; ticks in between go.
                if "bytes_done" in data:
                    if data["bytes_done"] == 0 and started is None:
                        started = data["done"]
                    elif data["done"] == started:
                        continue
                    else:
                        started = None
                item = {key: value for key, value in data.items() if key not in ("task_id",)}
                # Which sub-volume is open mid-stream depends on the encoder's output timing.
                if data.get("phase") not in ("done", "error"):
                    item.pop("volume_index", None)
                out.append(normalize(item, side))
            return out

        def normalize(value, side):
            text = json.dumps(value, ensure_ascii=False, sort_keys=True)
            text = text.replace(f"{base}/out-{side}/", "<OUT>/").replace(f"{base}/imp-{side}/", "<IMP>/")
            text = re.sub(r'"mac_address": "02(:[0-9a-f]{2}){5}"', '"mac_address": "<MAC>"', text)
            return json.loads(text)

        def strip_ids(value, side, known):
            """Fresh UUIDs (imported VM, created networks) in order of appearance."""
            text = json.dumps(normalize(value, side), ensure_ascii=False, sort_keys=True)
            for found in re.findall(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", text):
                if found not in known:
                    known[found] = f"<ID{len(known)}>"
            for found, label in known.items():
                if not label.startswith("<KEEP"):
                    text = text.replace(found, label)
            return json.loads(text)

        for c, mode in cases + [("ZSTD", "subset")]:
            ours, theirs = native_exports[(c, mode)], java[f"export {c} {mode}"]
            check(ours["events"][-1]["phase"] == "done" and theirs["events"][-1]["phase"] == "done", f"export {c} {mode} done",
                  [ours["events"][-1], theirs["events"][-1]])
            check(events(ours["events"], "native") == events(theirs["events"], "java"), f"export {c} {mode} events match",
                  {"native": events(ours["events"], "native"), "java": events(theirs["events"], "java")})
            check(theirs["task_store_empty"], f"export {c} {mode} Java store drained")
            dest = f"{c.lower()}-{mode}.vmpkg"
            n_head, n_manifest = head(f"{base}/out-native/{dest}")
            j_head, j_manifest = head(f"{base}/out-java/{dest}")
            for manifest in (n_manifest, j_manifest):
                for key in ("created_at", "app_version", "app_version_code", "app_build_type"):
                    manifest.pop(key)
            check(n_manifest == j_manifest, f"export {c} {mode} manifests match", {"native": n_manifest, "java": j_manifest})
            check({k2: v for k2, v in n_head.items() if k2 not in ("app_version_code", "data_size", "manifest_size")} ==
                  {k2: v for k2, v in j_head.items() if k2 not in ("app_version_code", "data_size", "manifest_size")}, f"export {c} {mode} headers match",
                  {"native": n_head, "java": j_head})
            if mode == "split":
                check(n_head["volume_count"] >= 2, f"export {c} split has volumes", n_head)
        check(native_exports[("ZSTD", "subset")]["events"][-1]["phase"] == "done" and head(f"{base}/out-native/zstd-subset.vmpkg")[0]["manifest_version"] == 2,
              "chain-only export is manifest version 2")
        for name, _ in refusals:
            ours, theirs = native_refusals[name], java["refuse " + name]
            mine = {"refused": ours["failed"]} if "failed" in ours and ours["failed"] != "internal error" else \
                {"internal": True} if "failed" in ours else {"error": ours["events"][-1].get("message")}
            other = {"refused": theirs["refused"]} if "refused" in theirs else {"internal": True} if "internal" in theirs else \
                {"error": theirs["events"][-1].get("message")}
            check(mine == other, "export refusal " + name, {"native": ours, "java": theirs})

        for c, mode, plan in import_cases:
            ours, theirs = native_imports[(c, mode, plan)], java[f"import {c} {mode} {plan}"]
            label = f"import {c} {mode} {plan}"
            check(ours["events"][-1]["phase"] == "done" and theirs["events"][-1]["phase"] == "done", label + " done",
                  {"native": ours["events"][-1], "java": theirs["events"][-1]})
            known_n, known_j = {vm_id: "<KEEP>", net_id: "<KEEP>"}, {vm_id: "<KEEP>", net_id: "<KEEP>"}
            n_events, j_events = strip_ids(events(ours["events"], "native"), "native", known_n), strip_ids(events(theirs["events"], "java"), "java", known_j)
            check(n_events == j_events, label + " events match", {"native": n_events, "java": j_events})
            done = theirs["events"][-1]
            java_vm = next(item for item in theirs["vms"] if item["id"] == done["vm_id"])
            n_vm, j_vm = strip_ids(ours["vm"], "native", known_n), strip_ids(java_vm, "java", known_j)
            check(n_vm == j_vm, label + " imported VM configs match", {"native": n_vm, "java": j_vm})
            # The files themselves: copies of the sources, and the overlay re-pointed at its copied base.
            placed = {d["archive_path"]: d["path"] for d in ours["events"][-1]["disks"]}
            for side, rows, config in (("native", ours["events"][-1], ours["vm"]), ("java", done, java_vm)):
                disks = {d["archive_path"]: d["path"] for d in rows["disks"]}
                boots = {b["kind"]: b["archive_path"] for b in rows["boots"]}
                target = f"{base}/imp-{side}/{c.lower()}-{mode}-{plan}"
                if mode != "subset":
                    check(sha(disks["disk.img"]) == sources["disk.img"] and sha(disks["base_1.qcow2"]) == sources["other/base.qcow2"] and
                          set(boots) == {"kernel", "initrd"}, f"{label} {side} files copied", rows)
                    kernel = config["boot"]["linux"]["kernel"]
                    check(kernel.startswith(f"{target}/pkg probe (1)/boot/") and sha(kernel) == sources["boot/vmlinuz"] and
                          sha(config["boot"]["linux"]["initrd"]) == sources["boot/initrd.img"], f"{label} {side} boot files copied", config["boot"])
                check(sha(disks["base.qcow2"]) == sources["chain/base.qcow2"], f"{label} {side} base copied")
                info = json.loads(sh(f"{QEMU_IMG} info --output=json {shlex.quote(disks['overlay.qcow2'])}"))
                check(info.get("full-backing-filename") == disks["base.qcow2"], f"{label} {side} overlay rebased", info)
                compare = subprocess.run(adb + ["shell", f"{QEMU_IMG} compare {shlex.quote(disks['overlay.qcow2'])} {base}/src/chain/overlay.qcow2"],
                                         capture_output=True, text=True, timeout=120)
                check(compare.returncode == 0, f"{label} {side} overlay content", compare.stdout + compare.stderr)
            check(set(placed) == {d["archive_path"] for d in done["disks"]}, label + " same files placed")
        lease = native_imports[("XZ", "single", "join")]["vm"]["networks"][0]
        check(lease["network_id"] == net_id and lease["dhcp4_lease"] == {"enabled": True, "offset": 71}, "join moves the lease off a taken offset", lease)
        created = native_imports[("NONE", "single", "create")]["events"][-1]["networks"]
        check(len(created) == 1 and created[0]["name"] == "pkgnet_1" and created[0]["bridge_name"] == "brpkg1", "created network renamed", created)
        skipped = native_imports[("GZIP", "split", "skip")]
        check(all("network_id" not in nic for nic in skipped["vm"]["networks"]) and skipped["events"][-1]["networks"] == [], "skip attaches nothing")

        for name in corrupt:
            ours, theirs = native_corrupt[name], java["corrupt " + name]
            n_last, j_last = ours["events"][-1], theirs["events"][-1]
            check(n_last["phase"] == "error" and j_last["phase"] == "error", "corrupt " + name + " fails", {"native": n_last, "java": j_last})
            report.setdefault("corrupt_messages", {})[name] = {"native": n_last.get("message"), "java": j_last.get("message")}
            leftovers = sh(f"ls -A {base}/imp-native/corrupt-{name} {base}/imp-java/corrupt-{name} 2>/dev/null; true")
            check(leftovers.strip().replace(f"{base}/imp-native/corrupt-{name}:", "").replace(f"{base}/imp-java/corrupt-{name}:", "").strip() == "",
                  "corrupt " + name + " leaves nothing behind", leftovers)
        same = {name: m for name, m in report["corrupt_messages"].items() if m["native"] == m["java"]}
        report["corrupt_same_message"] = sorted(same)
        # Texts of our own checks must match; codec library texts may differ (README of the probe result).
        for name in ("magic", "trailing", "missing", "crc", "short", "absent"):
            check(name in same, "corrupt " + name + " message matches", report["corrupt_messages"][name])
        for name, _ in import_refusals:
            ours, theirs = native_import_refusals[name], java["refuse import " + name]
            mine = ours.get("failed") if "failed" in ours else ours["events"][-1].get("message")
            other = theirs.get("refused", "internal error" if "internal" in theirs else None)
            check(mine == other, "import refusal " + name, {"native": ours, "java": theirs})
        # Real long operations: keep a sparse source cheap on the device while
        # making cancellation/commit races observable without artificial sleeps.
        big_id = str(uuid.uuid4())
        sh(f"truncate -s 1073741824 {base}/src/task-large.raw")
        check(client.call("vm_create", config={"id": big_id, "name": "task cancellation fixture",
              "disks": [{"path": base + "/src/task-large.raw", "bus": "virtio"}]})["success"], "create task fixture")
        exported = client.call("vm_export", vm_id=big_id, dest_path=base + "/task-large.vmpkg", compression="ZSTD")
        check(exported.get("success") is True, "large export accepted")
        ref = {"epoch": exported["epoch"], "task_id": exported["task_id"]}
        deadline = time.monotonic() + 10
        while True:
            task = client.call("task_get", **ref)["task"]
            if task["state"] != "queued":
                break
            check(time.monotonic() < deadline, "background export starts")
            time.sleep(.01)
        check(task["state"] == "running" and task["cancellable"] is False, "export marks overwrite commit non-cancellable")
        check(client.call("task_cancel", **ref).get("error", {}).get("code") == "NOT_CANCELLABLE", "committing export rejects cancellation")
        before = time.monotonic()
        check(client.call("vm_list")["success"], "VM control RPC remains available during compression")
        check(time.monotonic() - before < 2, "background compression does not occupy control workers")
        check(client.wait("vm_export_status", ref["task_id"])[-1]["phase"] == "done", "large export completes")
        origin = Client(forward, token)
        imported = origin.call("vm_import", src_path=base + "/task-large.vmpkg", target_dir=base + "/cancel-target", network_mode="skip")
        check(imported.get("success") is True, "cancellable import accepted")
        origin.close()
        ref = {"epoch": imported["epoch"], "task_id": imported["task_id"]}
        before_vms = len(client.call("vm_list")["data"])
        deadline = time.monotonic() + 10
        while True:
            task = client.call("task_get", **ref)["task"]
            if task["state"] == "running" and task["phase"] == "pack":
                break
            check(task["state"] not in ("succeeded", "failed", "cancelled") and time.monotonic() < deadline,
                  "disconnected import reaches cancellable extraction")
            time.sleep(.01)
        check(task["cancellable"] is True, "running import is cancellable after origin disconnects")
        cancelled = client.call("task_cancel", **ref)
        check(cancelled.get("success") and cancelled["task"]["state"] in ("cancelling", "cancelled"), "cancel running import")
        deadline = time.monotonic() + 10
        while True:
            task = client.call("task_get", **ref)["task"]
            if task["state"] in ("succeeded", "failed", "cancelled"):
                break
            check(time.monotonic() < deadline, "import cancellation finishes within budget")
            time.sleep(.02)
        check(task["state"] == "cancelled", "import acknowledges cancellation only after cleanup")
        check(sh(f"find {base}/cancel-target -mindepth 1 -print") == "", "cancelled import leaves no extracted files")
        check(len(client.call("vm_list")["data"]) == before_vms, "cancelled import does not register a VM")
        check(client.wait("vm_import_status", ref["task_id"])[-1]["phase"] == "error", "legacy client receives cancellation error event")
        snapshot = client.call("task_snapshot")
        check(any(row["task_id"] == ref["task_id"] and row["state"] == "cancelled" for row in snapshot["items"]), "package task participates in shared snapshot")
        check(client.call("task_snapshot_release", snapshot_id=snapshot["snapshot_id"])["success"], "release package task snapshot")
        check(client.call("task_get", epoch=str(uuid.uuid4()), task_id=ref["task_id"]).get("error", {}).get("code") == "TASK_EPOCH_MISMATCH", "package query rejects stale epoch")
        client.close()
        sh(f"kill -15 {pid}")
        deadline = time.monotonic() + 60
        while not sh(f"cat {base}/daemon.out.rc 2>/dev/null; true").strip() and time.monotonic() < deadline:
            time.sleep(0.5)
        check(sh(f"cat {base}/daemon.out.rc 2>/dev/null; true").strip() == str(128 + 15), "daemon stops cleanly")
        process = None
        report["passed"] = True
    finally:
        if process is not None:
            sh(f"pkill -f {base}/bin/droidvmd; sleep 3; pkill -9 -f {base}/bin/droidvmd; pkill -9 -f PkgOracle; true", check=False)
        if forward:
            subprocess.run(adb + ["forward", "--remove", f"tcp:{forward}"], capture_output=True, timeout=10)
        try:
            (output / "daemon.log").write_text(sh(f"cat {data}/cache/daemon.log", check=False), encoding="utf-8")
            (output / "daemon.txt").write_text(sh(f"cat {base}/daemon.out", check=False), encoding="utf-8")
        except Exception:
            pass
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Results:", output)
        sh(f"rm -rf {base}", check=False)
        report["cleaned"] = sh(f"ls -d {base} 2>/dev/null; true").strip() == ""
        (output / "result.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    assert report.get("passed"), report.get("differences")
    print("PASS: vm_export/vm_import native <-> Java,", len(report["checks"]), "checks")


if __name__ == "__main__":
    main()
