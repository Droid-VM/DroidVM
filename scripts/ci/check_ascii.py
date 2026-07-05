#!/usr/bin/env python3
"""Fail if any tracked text file contains non-ASCII bytes.

Source code, build files, layouts, manifests and config must be pure 7-bit
ASCII so a stray smart-quote, em-dash, arrow or CJK character can't slip into
code or a comment. Genuine natural-language / i18n content (translations,
privacy text, docs, ...) is exempt via scripts/ci/ascii_allow.txt.

Scope is the git index (`git ls-files`), so build outputs and submodule
contents are never scanned. Binary files are skipped by extension and by a
NUL-byte sniff. Exit status is 0 when clean, 1 when any violation is found.

Usage: run from the repo root, or pass the repo root as argv[1].
"""
import fnmatch
import os
import subprocess
import sys

ALLOWLIST = "scripts/ci/ascii_allow.txt"

# Extensions that are always binary -- skipped without reading.
BINARY_EXT = {
    ".webp", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".bmp",
    ".jar", ".zip", ".zst", ".gz", ".7z", ".apk", ".aar",
    ".so", ".a", ".o", ".bin", ".dex",
    ".ttf", ".otf", ".woff", ".woff2",
    ".keystore", ".jks", ".pdf",
}

MAX_REPORT_PER_FILE = 5  # don't drown the log on a fully non-ASCII file


def load_allow(root):
    path = os.path.join(root, ALLOWLIST)
    globs = []
    if not os.path.isfile(path):
        return globs
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                globs.append(line)
    return globs


def allowed(rel, globs):
    return any(fnmatch.fnmatch(rel, g) for g in globs)


def tracked_files(root):
    out = subprocess.check_output(
        ["git", "-C", root, "ls-files", "-z"])
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def scan(root, rel):
    """Return a list of (line, col, char) for non-ASCII bytes, or []."""
    path = os.path.join(root, rel)
    if not os.path.isfile(path):  # submodule gitlink, symlink to nowhere, ...
        return []
    if os.path.splitext(rel)[1].lower() in BINARY_EXT:
        return []
    with open(path, "rb") as fh:
        data = fh.read()
    if b"\x00" in data:  # looks binary
        return []
    hits = []
    line = col = 1
    for b in data:
        if b == 0x0A:
            line += 1
            col = 1
            continue
        if b > 0x7F:
            hits.append((line, col, b))
        col += 1
    return hits


def main():
    root = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.getcwd()
    globs = load_allow(root)
    violations = 0
    files_with_hits = 0
    for rel in tracked_files(root):
        if allowed(rel, globs):
            continue
        hits = scan(root, rel)
        if not hits:
            continue
        files_with_hits += 1
        violations += len(hits)
        print(f"{rel}: {len(hits)} non-ASCII byte(s)")
        for line, col, b in hits[:MAX_REPORT_PER_FILE]:
            print(f"    {rel}:{line}:{col}: byte 0x{b:02X}")
        if len(hits) > MAX_REPORT_PER_FILE:
            print(f"    ... and {len(hits) - MAX_REPORT_PER_FILE} more")

    if violations:
        print()
        print(f"FAIL: {violations} non-ASCII byte(s) in {files_with_hits} file(s).")
        print("Use ASCII (e.g. -- for an em-dash, -> for an arrow), or add a")
        print(f"genuinely-i18n path to {ALLOWLIST}.")
        return 1
    print("OK: no non-ASCII in tracked code/config files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
