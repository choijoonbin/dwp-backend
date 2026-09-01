#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LIMIT = 1_000
MAX_SAFE_INTEGER = (1 << 53) - 1
BASELINE_FILE = ROOT / "scripts/test-source-size-baseline.json"
REWRITE_COMMAND = "python3 scripts/check-test-source-size.py --write"


def line_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8").splitlines())


def governed_test_files() -> list[Path]:
    return sorted(ROOT.glob("dwp-*/src/test/java/**/*.java"))


def valid_baseline_path(relative: object) -> bool:
    if not isinstance(relative, str) or not relative or "\\" in relative:
        return False
    path = PurePosixPath(relative)
    parts = path.parts
    return (
        not path.is_absolute()
        and ".." not in parts
        and len(parts) >= 5
        and parts[0].startswith("dwp-")
        and tuple(parts[1:4]) == ("src", "test", "java")
        and path.suffix == ".java"
    )


def load_baseline() -> dict[str, int]:
    try:
        value = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(str(exception)) from exception

    if not isinstance(value, dict):
        raise ValueError("root must be a JSON object")

    baseline: dict[str, int] = {}
    for relative, limit in value.items():
        if not valid_baseline_path(relative):
            raise ValueError(f"invalid governed test path {relative!r}")
        if (
            not isinstance(limit, int)
            or isinstance(limit, bool)
            or limit <= DEFAULT_LIMIT
            or limit > MAX_SAFE_INTEGER
        ):
            raise ValueError(
                f"{relative} must have a safe integer limit greater than {DEFAULT_LIMIT}"
            )
        baseline[relative] = limit
    return baseline


def measured_tests() -> dict[str, int]:
    return {
        str(test_file.relative_to(ROOT)): line_count(test_file)
        for test_file in governed_test_files()
    }


def write_baseline(baseline: dict[str, int], measured: dict[str, int]) -> int:
    increases = []
    for relative, lines in measured.items():
        allowed = baseline.get(relative, DEFAULT_LIMIT)
        if lines > allowed:
            increases.append(f"{relative}: {lines} lines exceeds {allowed}")

    if increases:
        print(
            "Refusing to raise the test source-size baseline; split these files first.",
            file=sys.stderr,
        )
        for increase in increases:
            print(f"- {increase}", file=sys.stderr)
        return 1

    exact_baseline = {
        relative: lines
        for relative, lines in measured.items()
        if lines > DEFAULT_LIMIT
    }
    BASELINE_FILE.write_text(
        json.dumps(exact_baseline, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"Updated test source-size baseline with {len(exact_baseline)} exact exceptions."
    )
    return 0


def check_baseline(baseline: dict[str, int], measured: dict[str, int]) -> int:
    violations: list[str] = []

    for relative, lines in measured.items():
        allowed = baseline.get(relative, DEFAULT_LIMIT)
        if lines > allowed:
            violations.append(f"{relative}: {lines} lines exceeds {allowed}")
        elif relative in baseline and lines <= DEFAULT_LIMIT:
            violations.append(
                f"{relative}: {lines} lines no longer needs a baseline exception; "
                f"run {REWRITE_COMMAND}"
            )
        elif relative in baseline and lines < allowed:
            violations.append(
                f"{relative}: reduced from {allowed} to {lines} lines; "
                f"run {REWRITE_COMMAND} to lock in the improvement"
            )

    for relative in baseline:
        if relative not in measured:
            violations.append(
                f"{relative}: stale test source-size baseline entry; run {REWRITE_COMMAND}"
            )

    if violations:
        print("Test source-size budget violations found.", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1

    print(
        f"PASS test source-size budget: {len(measured)} Java test files checked; "
        f"{len(baseline)} exact legacy exception(s); new files are limited to "
        f"{DEFAULT_LIMIT} lines."
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if arguments not in ([], ["--write"]):
        print(f"Usage: {Path(sys.argv[0]).name} [--write]", file=sys.stderr)
        return 2

    try:
        baseline = load_baseline()
        measured = measured_tests()
    except (OSError, UnicodeError, ValueError) as exception:
        print(f"Invalid test source-size baseline: {exception}", file=sys.stderr)
        return 1

    if arguments == ["--write"]:
        return write_baseline(baseline, measured)
    return check_baseline(baseline, measured)


if __name__ == "__main__":
    raise SystemExit(main())
