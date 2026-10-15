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
from guest_input_probe import guest_commands, input_devices, validate_guest


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--input", action="store_true", help="Also verify seven input routes against guest evdev")
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
        "--name", "migration-" + nonce, "--mem", "512", "--cpus", "1",
        "--hypervisor", "gunyah", "--protected-vm-without-firmware",
        "--disable-sandbox", "--no-usb", "--no-balloon", "--no-rng",
        "--simplefb", "width=320,height=240,poll-hz=10",
        "--android-display-service", "name=" + service + ",screen=simplefb,transport-cap=cpu",
        "--socket", directory + "/control",
        "--serial", "type=file,hardware=virtio-console,console=true,path=" + directory + "/console" +
            (",input=" + directory + "/input" if args.input else ""),
        "--initrd", prefix + "/share/droidvm/initramfs.img",
        "--params", "console=hvc0 rdinit=/bin/sh panic=-1", prefix + "/share/droidvm/vmlinuz"]
    if args.input:
        command[-1:-1] = input_devices(directory)
    (output / "command.json").write_text(json.dumps(command, indent=2), encoding="utf-8")
    report = {"service": service, "isolated_vm_exit": None, "display_passed": False}
    process = None
    binder = None
    binder_log = None
    console_writer = None
    binder_command = [sys.executable, str(root / "scripts/migration/run_binder_probe.py"),
        "--serial", args.serial, "--probe", str(args.probe), "--display-service", service,
        "--output", str(output / "binder")]
    started = time.monotonic()
    shell(["mkdir", directory], check=True)
    try:
        subprocess.run(adb + ["push", str(args.probe), remote_probe], check=True, timeout=60)
        shell(["chmod", "755", remote_probe], check=True)
        if args.input:
            (output / "commands.txt").write_text(guest_commands(nonce), encoding="utf-8", newline="\n")
            shell(["mkfifo", directory + "/input"], check=True)
            writer_command = ["timeout", "-s", "TERM", "-k", "2", "60", "sh", "-c", 'cat > "$1"', "migration-input", directory + "/input"]
            console_writer = subprocess.Popen(adb + ["shell", shlex.join(writer_command)], stdin=subprocess.PIPE,
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            binder_log = (output / "binder-driver.txt").open("w", encoding="utf-8")
            binder = subprocess.Popen(binder_command + ["--input-directory", directory], stdout=binder_log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 25
            while shell(["test", "-S", directory + "/aux3"], capture_output=True).returncode:
                if binder.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("Native broker did not prebind input sockets")
                time.sleep(0.1)
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
            if args.input:
                deadline = time.monotonic() + 25
                commands_sent = False
                while True:
                    console = shell(["cat", directory + "/console"], capture_output=True).stdout
                    if not commands_sent and "~ #" in console:
                        console_writer.stdin.write(guest_commands(nonce).encode("utf-8"))
                        console_writer.stdin.close()
                        console_writer.wait(timeout=5)
                        commands_sent = True
                    if "DROIDVM_READY_" + nonce in console.splitlines():
                        shell(["touch", directory + "/guest-ready"], check=True)
                        break
                    if time.monotonic() >= deadline or process.poll() is not None or binder.poll() is not None:
                        raise RuntimeError("Guest input readers did not become ready")
                    time.sleep(0.2)
                if binder.wait(timeout=40):
                    raise RuntimeError("Binder input checks failed; see binder-driver.txt")
                deadline = time.monotonic() + 15
                while True:
                    console = shell(["cat", directory + "/console"], capture_output=True).stdout
                    if "DROIDVM_DONE_" + nonce in console.splitlines():
                        report["input"] = validate_guest(console)
                        break
                    if time.monotonic() >= deadline or process.poll() is not None:
                        raise RuntimeError("Guest did not finish input capture")
                    time.sleep(0.2)
            else:
                subprocess.run(binder_command, check=True, timeout=120)
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
        if binder is not None and binder.poll() is None:
            try:
                binder.wait(timeout=55)
            except subprocess.TimeoutExpired:
                binder.kill()
                binder.wait(timeout=5)
                report["cleanup_failed"] = True
        if binder_log is not None:
            binder_log.close()
        if console_writer is not None and console_writer.poll() is None:
            console_writer.kill()
            console_writer.wait(timeout=5)
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        # Remove only named files in this run's unique directory, never recurse.
        if report.get("service_removed") and not report.get("cleanup_failed"):
            shell(["rm", "-f", remote_probe, directory + "/console", directory + "/control",
                   directory + "/input", directory + "/guest-ready"] +
                  ([directory + "/" + name for name in ("main0", "aux0", "main1", "aux1", "vm2", "main3", "aux3")] if args.input else []), check=True)
            shell(["rmdir", directory], check=True)
        print(f"Results: {output}", flush=True)
    if report.get("cleanup_failed") or not report.get("service_removed") or report["isolated_vm_exit"] != 0:
        raise RuntimeError("Isolated crosvm cleanup did not complete normally")
    print("PASS: real crosvm Surface frame and transactions, followed by isolated VM cleanup")


if __name__ == "__main__":
    main()
