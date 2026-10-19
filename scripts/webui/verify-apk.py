"""Read-only APK inspection; never install or start services on a device."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, action="append", required=True)
    parser.add_argument("--dist", type=Path, default=Path("webui/dist"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    resources = [args.dist / "index.html", *sorted((args.dist / "assets").iterdir())]
    if not resources or any(not resource.is_file() for resource in resources):
        raise RuntimeError("Missing production resources")
    results = []
    for apk in args.apk:
        with zipfile.ZipFile(apk) as package:
            notices = package.read("assets/licenses/webui-third-party.txt").decode("utf-8")
            for expected in ("libwebsockets", "vue-router", "vue-i18n", "ajv", "WIDE Project", "Permission is hereby granted"):
                if expected not in notices:
                    raise RuntimeError(f"Missing packaged notice: {expected}")
            for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
                binary = package.read(f"assets/bin/{abi}/droidvmd")
                if binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != machine:
                    raise RuntimeError(f"Wrong daemon ABI: {abi}")
                for resource in resources:
                    data = resource.read_bytes()
                    etag = hashlib.sha256(data).hexdigest().encode("ascii")
                    if data not in binary or etag not in binary:
                        raise RuntimeError(f"Daemon is missing current resource: {abi}/{resource.name}")
            if any("/dev.html" in name or name.endswith(".map") for name in package.namelist() if name.startswith("assets/webui/")):
                raise RuntimeError("Development resource in APK")
        results.append({"apk": apk.as_posix(), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "bytes": apk.stat().st_size, "abis": ["arm64-v8a", "x86_64"], "resources": len(resources), "notices": True})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"passed": len(results), "results": results}, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(results)} APK(s): dual-ABI ELF, exact current production resources/ETags and upstream notices")


if __name__ == "__main__":
    main()
