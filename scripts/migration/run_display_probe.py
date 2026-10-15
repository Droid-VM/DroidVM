# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright DroidVM contributors
# Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
"""Exercise a real isolated crosvm Surface through the paired native Binder probe."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--probe", type=Path, default=root / "build/migration/standalone-arm64-v8a/platform/android/probe/droidvm_platform_probe")
    parser.add_argument("--output", type=Path, default=root / "build/migration/display-device")
    args = parser.parse_args()
    if not args.probe.is_file():
        parser.error(f"Build the native probe first: {args.probe}")
    nonce = uuid.uuid4().hex
    directory = "/data/local/tmp/droidvm-display-" + nonce
    service = "droidvm_migration_" + nonce
    output = args.output / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    prefix = "/data/data/cn.classfun.droidvm/usr"
    env = ["env", "LD_LIBRARY_PATH=" + prefix + "/lib"]
    crosvm = prefix + "/bin/crosvm"
    remote_probe = directory + "/probe"

    def shell(command, **kwargs):
        return subprocess.run(adb + ["shell", shlex.join(command)], text=True,
                              encoding="utf-8", errors="replace", timeout=10, **kwargs)

    def inspect():
        result = shell([remote_probe, "--inspect", service], capture_output=True)
        return result, json.loads(result.stdout)

    # No disks, network or USB are attached. The unique control socket is the
    # sole stop target; never stop existing user VMs or the system qcrosvm.
    command = env + ["timeout", "-s", "TERM", "-k", "5", "150", crosvm, "run",
        "--name", "migration-" + nonce, "--mem", "256", "--cpus", "1",
        "--hypervisor", "gunyah", "--protected-vm-without-firmware",
        "--disable-sandbox", "--no-usb", "--no-balloon", "--no-rng",
        "--simplefb", "width=320,height=240,poll-hz=10",
        "--android-display-service", "name=" + service + ",screen=simplefb,transport-cap=cpu",
        "--socket", directory + "/control",
        "--serial", "type=file,hardware=virtio-console,console=true,path=" + directory + "/console",
        "--initrd", prefix + "/share/droidvm/initramfs.img",
        "--params", "console=hvc0 rdinit=/bin/sh panic=-1", prefix + "/share/droidvm/vmlinuz"]
    (output / "command.json").write_text(json.dumps(command, indent=2), encoding="utf-8")
    report = {"service": service, "isolated_vm_exit": None, "display_passed": False}
    process = None
    started = time.monotonic()
    shell(["mkdir", directory], check=True)
    try:
        subprocess.run(adb + ["push", str(args.probe), remote_probe], check=True, timeout=60)
        shell(["chmod", "755", remote_probe], check=True)
        with (output / "crosvm.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(adb + ["shell", shlex.join(command)],
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            started = time.monotonic()
            for attempt in range(20):
                if process.poll() is not None:
                    raise RuntimeError("Isolated crosvm exited before registering display")
                result, state = inspect()
                (output / "inspect.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
                if result.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise TimeoutError("Isolated display service did not become reachable")
            subprocess.run([sys.executable, str(root / "scripts/migration/run_binder_probe.py"),
                "--serial", args.serial, "--probe", str(args.probe), "--display-service", service,
                "--output", str(output / "binder")], check=True, timeout=120)
            report["display_passed"] = True
    finally:
        if process is not None:
            stop = shell(env + [crosvm, "stop", directory + "/control"], capture_output=True)
            (output / "stop.txt").write_text(stop.stdout + stop.stderr, encoding="utf-8")
            # Remote timeout bounds cleanup even if control stop fails. Killing
            # only the local adb client is not proof that the VM exited.
            try:
                process.wait(timeout=max(5, 160 - (time.monotonic() - started)))
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                report["cleanup_failed"] = True
            report["isolated_vm_exit"] = process.returncode
            subprocess.run(adb + ["pull", directory + "/console", str(output / "console.txt")],
                           capture_output=True, timeout=10)
            _, state = inspect()
            report["service_removed"] = state["services"][-1]["status"] == "not_found_or_denied"
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        # Remove only named files in this run's unique directory, never recurse.
        if report.get("service_removed") and not report.get("cleanup_failed"):
            shell(["rm", "-f", remote_probe, directory + "/console", directory + "/control"], check=True)
            shell(["rmdir", directory], check=True)
        print(f"Results: {output}", flush=True)
    if report.get("cleanup_failed") or not report.get("service_removed") or report["isolated_vm_exit"] != 0:
        raise RuntimeError("Isolated crosvm cleanup did not complete normally")
    print("PASS: real crosvm Surface frame and transactions, followed by isolated VM cleanup")


if __name__ == "__main__":
    main()
