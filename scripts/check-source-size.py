#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LIMIT = 700
BASELINE_FILE = ROOT / "scripts/source-size-baseline.json"


def line_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8").splitlines())


def main() -> int:
    baseline = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    source_files = sorted(ROOT.glob("dwp-*/src/main/java/**/*.java"))
    violations: list[str] = []

    for source_file in source_files:
        relative = str(source_file.relative_to(ROOT))
        lines = line_count(source_file)
        limit = baseline.get(relative, DEFAULT_LIMIT)
        if lines > limit:
            violations.append(f"{relative}: {lines} lines exceeds {limit}")

    for relative in baseline:
        if not (ROOT / relative).exists():
            violations.append(f"{relative}: stale source-size baseline entry")

    if violations:
        print("Source-size budget violations found.", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1

    print(
        f"PASS source-size budget: {len(source_files)} production files checked; "
        f"new files are limited to {DEFAULT_LIMIT} lines."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
