#!/usr/bin/env python3
"""Conservative fail-closed check for trivially unreachable private Java methods."""

from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path

IDENTIFIER = re.compile(r"\b[A-Za-z_$][A-Za-z0-9_$]*\b")
PRIVATE_METHOD = re.compile(
    r"(?m)^\s*private\s+(?:static\s+)?(?:final\s+)?"
    r"(?:<[^;{}]+>\s*)?[\w.$<>?, @\[\]]+\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\("
)
SERIALIZATION_HOOKS = {"readObject", "readResolve", "writeObject", "writeReplace"}


def strip_comments_and_literals(source: str) -> str:
    """Replace comments and literals with spaces while preserving newlines and offsets."""
    result = list(source)
    index = 0
    state = "code"
    quote = ""
    while index < len(source):
        current = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if current == "/" and following == "/":
                result[index] = result[index + 1] = " "
                state = "line-comment"
                index += 2
                continue
            if current == "/" and following == "*":
                result[index] = result[index + 1] = " "
                state = "block-comment"
                index += 2
                continue
            if current in {'"', "'"}:
                quote = current
                result[index] = " "
                state = "literal"
                index += 1
                continue
        elif state == "line-comment":
            if current == "\n":
                state = "code"
            else:
                result[index] = " "
            index += 1
            continue
        elif state == "block-comment":
            if current == "*" and following == "/":
                result[index] = result[index + 1] = " "
                state = "code"
                index += 2
                continue
            if current != "\n":
                result[index] = " "
            index += 1
            continue
        elif state == "literal":
            if current == "\\":
                result[index] = " "
                if index + 1 < len(source) and source[index + 1] != "\n":
                    result[index + 1] = " "
                index += 2
                continue
            if current == quote:
                result[index] = " "
                state = "code"
            elif current != "\n":
                result[index] = " "
            index += 1
            continue
        index += 1
    return "".join(result)


def has_adjacent_annotation(source: str, start: int) -> bool:
    lines = source[:start].splitlines()
    for line in reversed(lines):
        stripped = line.strip()
        if not stripped:
            continue
        return stripped.startswith("@")
    return False


def scan(repository_root: Path) -> list[str]:
    violations: list[str] = []
    for source_file in sorted(repository_root.glob("**/src/main/java/**/*.java")):
        source = source_file.read_text(encoding="utf-8")
        stripped = strip_comments_and_literals(source)
        counts = Counter(IDENTIFIER.findall(stripped))
        for match in PRIVATE_METHOD.finditer(stripped):
            name = match.group("name")
            if (
                counts[name] != 1
                or name in SERIALIZATION_HOOKS
                or has_adjacent_annotation(source, match.start())
            ):
                continue
            relative = source_file.relative_to(repository_root).as_posix()
            line = stripped.count("\n", 0, match.start()) + 1
            violations.append(f"{relative}:{line}#{name}")
    return violations


def main() -> int:
    root = Path(sys.argv[sys.argv.index("--root") + 1] if "--root" in sys.argv else ".").resolve()
    violations = scan(root)
    if violations:
        print(f"Unused private method check failed with {len(violations)} issue(s):", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("PASS unused private methods: no trivially unreachable unannotated private methods.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
