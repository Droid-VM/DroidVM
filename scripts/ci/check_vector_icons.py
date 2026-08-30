#!/usr/bin/env python3
"""Fail if an icon ships as a bitmap instead of a vector drawable.

Every icon the app draws must be an XML vector: it stays sharp at any density,
tints from the theme (`?attr/colorOnSurface`), and costs one small file instead
of five PNG buckets. So `res/drawable*` may hold XML only -- vectors, shapes,
selectors, layer-lists -- and no XML in there may wrap a raster through
`<bitmap>` either, which would smuggle one in the back door.

The one exception is the launcher icon: Android still hands `res/mipmap-*` to
legacy launchers as a bitmap, and the adaptive-icon foreground/background are
generated with it. So `mipmap-*` may carry raster files, but only ones named
`ic_launcher*`; anything else belongs in `drawable/` as a vector.

Scope is the git index (`git ls-files`), so build outputs never count. Exit
status is 0 when clean, 1 when any violation is found.

Usage: run from the repo root, or pass the repo root as argv[1].
"""
import os
import re
import subprocess
import sys

RES = "app/src/main/res"
RASTER_EXT = {".png", ".webp", ".jpg", ".jpeg", ".gif", ".bmp", ".9.png"}
LAUNCHER_PREFIX = "ic_launcher"
# <bitmap src="@drawable/foo"> and friends: an XML wrapper around a raster.
BITMAP_TAG = re.compile(r"<\s*bitmap\b")


def tracked_files(root):
    out = subprocess.run(
        ["git", "-C", root, "ls-files", RES],
        capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line.strip()]


def check(root):
    problems = []
    for rel in tracked_files(root):
        parts = rel.split("/")
        if len(parts) < 2:
            continue
        folder, name = parts[-2], parts[-1]
        ext = os.path.splitext(name)[1].lower()
        if folder.startswith("drawable"):
            if ext != ".xml":
                problems.append((rel, "raster in drawable/; ship an XML vector"))
                continue
            with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as f:
                if BITMAP_TAG.search(f.read()):
                    problems.append((rel, "<bitmap> wraps a raster; ship an XML vector"))
        elif folder.startswith("mipmap"):
            if ext in RASTER_EXT and not name.startswith(LAUNCHER_PREFIX):
                problems.append((rel, "only ic_launcher* may be a raster in mipmap/"))
    return problems


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    problems = check(root)
    for rel, why in problems:
        print("%s: %s" % (rel, why))
    if problems:
        print("\nFAIL: %d non-vector icon(s)." % len(problems))
        return 1
    print("OK: every icon is a vector drawable.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
