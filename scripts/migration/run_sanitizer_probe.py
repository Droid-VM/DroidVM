# SPDX-License-Identifier: GPL-3.0-or-later
"""Run sanitizer-built contract binaries on a device.

build/migration/standalone-arm64-asan is ASan (c++_shared); standalone-arm64-asan2 is UBSan only
(-fno-sanitize=vptr,function: both report false positives across the c++_shared boundary)."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import uuid

LIBS = "/data/local/tmp/droidvm-asan-libs"  # the sanitizer build's fixed RUNPATH
CONTRACTS = ["task", "event", "config_file", "runtime", "process", "managed_process", "helper_config", "keyboard", "usb", "usb_acm"]
NDK = "F:/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64"


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build", type=Path, default=root / "build/migration/standalone-arm64-asan")
    parser.add_argument("--install-libs", action="store_true", help="Only push the sanitizer runtime for other probes")
    args = parser.parse_args()
    nonce = uuid.uuid4().hex
    output = root / "build/migration/sanitizer-device" / nonce
    output.mkdir(parents=True)
    adb = ["adb", "-s", args.serial]
    remote = f"/data/local/tmp/droidvm-asan-{nonce}"

    def root_shell(command, timeout=300):
        return subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], text=True, encoding="utf-8", errors="replace",
                              capture_output=True, timeout=timeout)

    runtimes = [sorted(Path(NDK).glob(f"lib/clang/*/lib/linux/libclang_rt.{name}-aarch64-android.so"))[-1] for name in ("asan", "ubsan_standalone")]
    stl = Path(NDK) / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    root_shell(f"mkdir -p {LIBS} && chmod 755 {LIBS}")
    for lib in (*runtimes, stl):
        subprocess.run(adb + ["push", str(lib), "/data/local/tmp/" + lib.name], check=True, capture_output=True, timeout=60)
        root_shell(f"mv /data/local/tmp/{lib.name} {LIBS}/ && chmod 644 {LIBS}/{lib.name}")
    if args.install_libs:
        print("sanitizer runtime installed in", LIBS)
        return
    report = {"build": str(args.build), "contracts": {}}
    try:
        root_shell(f"mkdir {remote}")
        for name in CONTRACTS:
            binary = args.build / f"droidvm_{name}_contract"
            subprocess.run(adb + ["push", str(binary), f"{remote}/{binary.name}"], check=True, capture_output=True, timeout=120)
            scratch = f"{remote}/scratch-{name}"
            root_shell(f"mkdir {scratch} && chmod 755 {remote}/{binary.name}")
            env = "ASAN_OPTIONS=halt_on_error=1:abort_on_error=0:detect_leaks=0 UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1"
            result = root_shell(f"cd {scratch} && {env} timeout 240 {remote}/{binary.name} {scratch if name.startswith('usb') else ''}")
            text = result.stdout + result.stderr
            (output / f"{name}.txt").write_text(text, encoding="utf-8")
            sanitizer = "AddressSanitizer" in text or "runtime error:" in text or "UndefinedBehaviorSanitizer" in text
            report["contracts"][name] = {"exit": result.returncode, "sanitizer_report": sanitizer}
            print(name, result.returncode, "SANITIZER" if sanitizer else "")
        report["passed"] = all(row["exit"] == 0 and not row["sanitizer_report"] for row in report["contracts"].values())
    finally:
        (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Results:", output)
        if report.get("passed"):
            root_shell(f"rm -r {remote}")
    assert report.get("passed"), report
    print("PASS: sanitizer contracts,", len(report["contracts"]), "binaries")


if __name__ == "__main__":
    main()
