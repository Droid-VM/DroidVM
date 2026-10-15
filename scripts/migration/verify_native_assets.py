# SPDX-License-Identifier: GPL-3.0-or-later
"""Check native executables in the real APK, optionally exercise its arm64 daemon."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import uuid
import zipfile


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--serial", help="Also run the packaged daemon and launcher on this arm64 device")
    parser.add_argument("--data-layout", action="store_true", help="Exercise the app-owned data layout in device checks")
    args = parser.parse_args()
    directory = root / "app/build/outputs/apk" / args.variant
    metadata_file = directory / "output-metadata.json"
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    if len(metadata["elements"]) != 1:
        raise RuntimeError("Expected the existing universal APK configuration")
    apk = directory / metadata["elements"][0]["outputFile"]
    output = root / "build/migration/packaged" / args.variant / uuid.uuid4().hex
    output.mkdir(parents=True)
    report = {"apk": str(apk), "metadata": metadata, "binaries": {}}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
            for name in ("droidvm", "daemon", "droidvmd"):
                asset = f"assets/bin/{abi}/{name}"
                if names.count(asset) != 1:
                    raise RuntimeError(f"Missing or duplicate executable: {asset}")
                content = archive.read(asset)
                built = root / "app/build/intermediates/native_binaries" / args.variant / abi / name
                if content != built.read_bytes():
                    raise RuntimeError(f"APK contains a stale executable: {asset}")
                if content[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", content, 18)[0] != machine:
                    raise RuntimeError(f"Wrong ELF architecture: {asset}")
                target = output / "bin" / abi / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                report["binaries"][asset] = {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        forbidden = ("droidvm_platform_probe", "ipc_client_contract", "droidvm_task_contract", "droidvm_event_contract", "droidvm_service_fixture", "droidvm_process_contract", "droidvm_runtime_contract", "droidvm_config_probe", "droidvm_config_file_contract", "droidvm_network_contract", "droidvm_network_fixture")
        if any(Path(name).name in forbidden for name in names):
            raise RuntimeError("A migration-only test executable was packaged")
    report["apk_assets_passed"] = True
    if args.serial:
        subprocess.run([sys.executable, str(root / "scripts/migration/run_daemon_probe.py"),
            "--serial", args.serial, "--daemon", str(output / "bin/arm64-v8a/droidvmd"),
            "--launcher", str(output / "bin/arm64-v8a/daemon"), "--app-metadata", str(metadata_file),
            "--hash-apk", str(apk)] + (["--data-layout"] if args.data_layout else []), check=True)
        report["packaged_arm64_device_checks_passed"] = True
    (output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"PASS: APK contains all six current native executables with correct ELF ABIs. Results: {output}")


if __name__ == "__main__":
    main()
