#!/usr/bin/env python3
"""Fail if a Java method or constructor declares too many parameters.

A call site cannot pass more arguments than the signature declares, so the
place to hold the line is the declaration -- and only there. Checking call
sites too would flag every varargs helper (`fmt("%s %s ...", a, b, c, d, e)`,
`runList("ip", "addr", "add", ...)`), where a long argument list is the point
and the signature is still two parameters wide.

The limit is 7, Checkstyle's ParameterNumber default. Past that, callers
start passing arguments positionally by luck: bundle the related ones into a
small object, or split the method.

Two escape hatches:
  - a trailing `// arity-ok` comment on the line the signature starts on, for a
    deliberate exception in new code;
  - scripts/ci/arity_allow.txt, one `path:name` per line, for signatures that
    predate this check. That file is a debt list, not a blessing: new code must
    not grow it, and an entry should disappear when its signature is reworked.

Not a full parser: it masks comments and string/char literals, then reads the
`name(` sites whose text before them looks like `<type-or-modifier> name` and
whose text after them starts a body (`{`, `;`, or `throws ...`). Generic
parameter types (`Map<String, Integer>`) are counted as one parameter.

Exit status is 0 when clean, 1 when any violation is found. Run from the repo
root, or pass the repo root as argv[1].
"""
import os
import re
import subprocess
import sys

LIMIT = 7
SUPPRESS = "arity-ok"
ALLOWLIST = "scripts/ci/arity_allow.txt"
SOURCES = "app/src"

# Control-flow keywords that also read as `name(`.
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return",
            "assert", "do", "else", "try", "case", "new", "throw", "yield",
            "instanceof"}
# Words that can precede `name(` and still leave it an expression, not a signature.
NOT_A_TYPE = {"new", "return", "throw", "else", "instanceof", "case", "assert", "yield"}
DECL_BEFORE = re.compile(r"[\w>\]]\s+$")
# A constructor with no modifier has no type before it, only the end of the
# previous member -- recognised by the capitalised name in member position.
CTOR_BEFORE = re.compile(r"[{};]\s+$")
LAST_WORD = re.compile(r"(\w+)\s+$")
TAIL_IS_BODY = re.compile(r"\s*(throws [\w.,\s]+)?[{;]")
CALL_SITE = re.compile(r"\b([A-Za-z_$][\w$]*)\s*\(")


def mask(src):
    """Blank out comments and string/char literals, keeping every offset and
    newline in place so line numbers and slices stay true to the original."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, min(j, n)):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src[i] in "\"'":
            quote, j = src[i], i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def count_params(text):
    """Top-level commas + 1. Nested (), [], {} and generics all shield commas."""
    if not text.strip():
        return 0
    count, depth = 1, 0
    for ch in text:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth = max(0, depth - 1)
        elif ch == "," and depth == 0:
            count += 1
    return count


def declarations(src):
    """Yield (line, name, param_count) for every method/constructor signature."""
    masked = mask(src)
    for m in CALL_SITE.finditer(masked):
        name = m.group(1)
        if name in KEYWORDS:
            continue
        before = masked[:m.start()]
        if not DECL_BEFORE.search(before):
            if not (name[:1].isupper() and CTOR_BEFORE.search(before)):
                continue
        word = LAST_WORD.search(before)
        if word and word.group(1) in NOT_A_TYPE:
            continue
        start = m.end() - 1
        depth, i = 0, start
        while i < len(masked):
            if masked[i] == "(":
                depth += 1
            elif masked[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        else:
            continue
        if not TAIL_IS_BODY.match(masked[i + 1:i + 120]):
            continue
        yield masked.count("\n", 0, m.start()) + 1, name, count_params(masked[start + 1:i])


def load_allowlist(root):
    path = os.path.join(root, ALLOWLIST)
    allowed = set()
    if not os.path.exists(path):
        return allowed
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                allowed.add(line)
    return allowed


def java_files(root):
    out = subprocess.run(
        ["git", "-C", root, "ls-files", SOURCES],
        capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line.endswith(".java")]


def check(root):
    allowed = load_allowlist(root)
    problems = []
    for rel in java_files(root):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            src = f.read()
        lines = src.split("\n")
        for line, name, count in declarations(src):
            if count <= LIMIT:
                continue
            if SUPPRESS in lines[line - 1]:
                continue
            if "%s:%s" % (rel, name) in allowed:
                continue
            problems.append((rel, line, name, count))
    return problems


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    problems = check(root)
    for rel, line, name, count in problems:
        print("%s:%d: %s() declares %d parameters (limit %d)"
              % (rel, line, name, count, LIMIT))
    if problems:
        print("\nFAIL: %d signature(s) over the parameter limit." % len(problems))
        print("Group the related parameters into an object, or split the method.")
        print("For a deliberate exception, add a trailing '// arity-ok' comment.")
        return 1
    print("OK: no signature declares more than %d parameters." % LIMIT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
