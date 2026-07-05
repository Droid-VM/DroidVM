#!/usr/bin/env python3
"""Fail if any locale is missing (or has extra) translated string entries.

The base `values/strings.xml` is the source of truth: every entry that is not
`translatable="false"` must appear in every `values-<locale>/strings.xml`, and a
locale must not carry entries that no longer exist in the base. This keeps all
languages at the same set of keys so nothing ships half-translated.

Entries compared: <string>, <plurals> and <string-array>, keyed by name. (Only
presence of the key is checked, not per-plural quantities -- those legitimately
differ by language.) `values-night` and other non-locale qualifiers are ignored
because they carry no strings.xml.

Exit status is 0 when every locale matches the base, 1 otherwise.

Usage: run from the repo root, or pass the res/ directory as argv[1].
"""
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_RES = "app/src/main/res"
ENTRY_TAGS = {"string", "plurals", "string-array"}


def names(strings_xml):
    """(translatable names, translatable=false names) in one strings.xml."""
    root = ET.parse(strings_xml).getroot()
    keep, nontrans = set(), set()
    for el in root:
        if el.tag not in ENTRY_TAGS:
            continue
        name = el.get("name")
        if not name:
            continue
        if (el.get("translatable") or "").lower() == "false":
            nontrans.add(name)
        else:
            keep.add(name)
    return keep, nontrans


def main():
    res = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else \
        os.path.join(os.getcwd(), DEFAULT_RES)

    base_xml = os.path.join(res, "values", "strings.xml")
    if not os.path.isfile(base_xml):
        print(f"FAIL: base strings.xml not found at {base_xml}")
        return 1
    base, base_nontrans = names(base_xml)
    print(f"base (values/strings.xml): {len(base)} translatable "
          f"({len(base_nontrans)} translatable=false)")

    locales = []
    for d in sorted(os.listdir(res)):
        if not d.startswith("values-"):
            continue
        sp = os.path.join(res, d, "strings.xml")
        if os.path.isfile(sp):       # non-locale qualifiers (e.g. values-night) have none
            locales.append((d, sp))

    if not locales:
        print("OK: no translation locales to check.")
        return 0

    failed = False
    for d, sp in locales:
        loc, _ = names(sp)
        missing = sorted(base - loc)
        extra = sorted(loc - base)
        stale = sorted(loc & base_nontrans)  # translating a non-translatable key
        if not missing and not extra and not stale:
            print(f"  OK   {d}: {len(loc)}/{len(base)}")
            continue
        failed = True
        print(f"  FAIL {d}: {len(loc)}/{len(base)}")
        for n in missing:
            print(f"        missing:  {n}")
        for n in extra:
            print(f"        extra:    {n}  (not in base)")
        for n in stale:
            print(f"        stale:    {n}  (base marks it translatable=false)")

    if failed:
        print()
        print("FAIL: translations are out of sync with the base strings.")
        return 1
    print("OK: every locale matches the base string set.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
