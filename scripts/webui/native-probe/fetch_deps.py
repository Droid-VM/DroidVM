"""Prepare pinned native dependencies for probes and APK builds; never change submodules."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[3] / "build/webui-native")
    parser.add_argument("--offline", action="store_true", help="Use only an already downloaded archive")
    args = parser.parse_args()
    root = args.output.resolve()
    manifest = json.loads(Path(__file__).with_name("dependencies.json").read_text(encoding="utf-8"))
    downloads = root / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for name, dependency in manifest.items():
        archive = downloads / dependency["archive"]
        if not archive.exists():
            if args.offline:
                raise RuntimeError(f"Offline archive missing: {archive}")
            with urllib.request.urlopen(dependency["url"], timeout=60) as response:
                content = response.read()
            if hashlib.sha256(content).hexdigest() != dependency["sha256"]:
                raise RuntimeError(f"Download checksum mismatch: {dependency['archive']}")
            temporary = archive.with_suffix(archive.suffix + ".tmp")
            temporary.write_bytes(content)
            temporary.replace(archive)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != dependency["sha256"]:
            raise RuntimeError(f"Checksum mismatch: {archive}")
        destination = root / "deps" / name
        destination.mkdir(parents=True, exist_ok=True)
        # Only regular source files/directories are needed. Strip the archive's
        # root. Skip unused example symlinks; reject all other special entries.
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                parts = PurePosixPath(member.name).parts
                if not parts or parts[0] == "/" or ".." in parts or "\\" in member.name:
                    raise RuntimeError(f"Unsafe archive path: {member.name}")
                if len(parts) == 1:
                    continue
                if member.issym() and parts[1] in ("minimal-examples", "minimal-examples-lowlevel"):
                    continue
                target = destination.joinpath(*parts[1:]).resolve()
                if not target.is_relative_to(destination):
                    raise RuntimeError(f"Archive path escaped destination: {member.name}")
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as content:
                        target.write_bytes(content.read())
                else:
                    raise RuntimeError(f"Unsupported archive entry: {member.name}")
        print(f"{name} {dependency['version']}: SHA-256 verified")


if __name__ == "__main__":
    main()
