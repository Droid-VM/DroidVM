#!/usr/bin/env python3
"""Flag Java string building done with `+` instead of the project's fmt() helper.

Reports every `+` where exactly one operand is a string literal and the other is
a runtime expression (`"x=" + v`, `v + " done"`), which reads better as
`fmt("x=%s", v)`. Deliberately NOT flagged:
  - `+` inside a string literal ("wlan+", "%s+", "\\s+") -- masked out first;
  - literal + literal ("a" + "b") -- a compile-time constant, fmt adds nothing;
  - `++` and numeric `+` with no string operand;
  - a `+=` append with a SINGLE `+` (`s += "x" + y`) -- appending one piece to
    an accumulator is clear intent fmt() would only obscure. But a `+=` chaining
    two or more `+` (`s += "a" + b + "c"`) IS flagged: keep append complexity
    low and reach for fmt() there. The count is per statement (to the next
    `;`/`{`/`}`).

A line is exempted with a trailing `// concat-ok` comment (for the rare case
where `+` genuinely reads better, e.g. a one-off constant split for width).

Not a full parser: it masks comments and string/char literals, then inspects
the token on each side of every bare `+`. Good enough to catch the pattern the
reviewers keep flagging without drowning in false positives.

Exit status is 0 when clean, 1 when any violation is found. Run from the repo
root, or pass the repo root as argv[1].
"""
import os
import re
import subprocess
import sys

SUPPRESS = "concat-ok"


def mask(src):
    """Replace comments with spaces and each string/char literal with one STR
    sentinel (\x01), preserving newlines so line numbers stay accurate."""
    out = []
    i, n = 0, len(src)
    STR = "\x01"
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in src[i:j]))
            i = j
        elif c in "\"'":
            j = i + 1                         # consume literal, honour escapes
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == c:
                    j += 1
                    break
                j += 1
            out.append(STR)
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


# Significant tokens: a string literal (\x01), '+=' (marks an append statement),
# a value (identifier / number / closing bracket = end of a sub-expression), a
# '+', or a statement boundary (; { }) that scopes the '+=' exemption.
TOKEN = re.compile(r"""
      \x01                                   # string literal
    | \+\+ | \+=                             # compound operators (+= = append)
    | \+                                     # the concatenation operator
    | [A-Za-z_$][A-Za-z0-9_$]* | [0-9][\w.]* # identifier / number
    | [)\]]                                  # end of a sub-expression
    | [;{}]                                  # statement boundary
""", re.VERBOSE)


def _statement_hits(stmt, masked, suppressed):
    """Violations within one statement's tokens. A '+=' append stays exempt only
    while it is glance-obvious - at most ONE '+'. A second '+' (`s += "a"+b+"c"`)
    is too complex: the exemption drops and every literal-joining '+' is reported,
    steering it to fmt()."""
    is_append = any(t == "+=" for t, _ in stmt)
    plus_count = sum(1 for t, _ in stmt if t == "+")
    if is_append and plus_count <= 1:        # simple accumulator append: allow
        return
    for k, (tok, pos) in enumerate(stmt):
        if tok != "+":
            continue
        left = stmt[k - 1][0] if k > 0 else None
        right = stmt[k + 1][0] if k + 1 < len(stmt) else None
        if (left == "\x01") != (right == "\x01"):   # exactly one is a literal
            line = masked.count("\n", 0, pos) + 1
            if line not in suppressed:
                yield line


def violations_in(src, masked):
    """Yield line numbers where a bare '+' joins a string literal and an expr.
    Tokens are split into statements on ; { } so a '+=' append exemption cannot
    leak past its statement and its '+' count is scoped correctly. Literal+literal
    that spans lines stays within one statement, so it is still never flagged."""
    # suppression: any line carrying the marker is skipped. Checked against the
    # original source, since masking blanks the comment the marker lives in.
    suppressed = {i for i, ln in enumerate(src.split("\n"), 1) if SUPPRESS in ln}
    stmt = []
    for m in TOKEN.finditer(masked):
        tok = m.group(0)
        if tok in (";", "{", "}"):
            yield from _statement_hits(stmt, masked, suppressed)
            stmt = []
        else:
            stmt.append((tok, m.start()))
    yield from _statement_hits(stmt, masked, suppressed)


def tracked_java(root):
    out = subprocess.check_output(["git", "-C", root, "ls-files", "*.java"])
    return out.decode().split()


def main():
    root = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.getcwd()
    total = 0
    files = 0
    for rel in tracked_java(root):
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8", errors="replace") as fh:
            src = fh.read()
        lines = sorted(set(violations_in(src, mask(src))))
        if not lines:
            continue
        files += 1
        total += len(lines)
        raw = src.split("\n")
        for ln in lines:
            print(f"{rel}:{ln}: {raw[ln - 1].strip()[:100]}")

    if total:
        print()
        print(f"FAIL: {total} string concatenation(s) in {files} file(s); "
              f"use fmt() instead of +.")
        print(f"For a deliberate exception, add a trailing '// {SUPPRESS}' comment.")
        return 1
    print("OK: no string-literal '+' concatenation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
