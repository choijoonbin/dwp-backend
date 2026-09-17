#!/usr/bin/env python3
"""Fail closed when Home Runtime crosses an owner database or telemetry boundary."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
RUNTIME_RELATIVE = Path(
    "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime"
)
PLATFORM_BUILD = Path("dwp-platform-server/build.gradle")
PLATFORM_CONFIG = Path("dwp-platform-server/src/main/resources/application.yml")
PLATFORM_OWNED_PERSISTENCE = {
    (
        "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
        "HomeOwnerActionReceipt.java"
    ),
    (
        "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
        "HomeOwnerActionReceiptRepository.java"
    ),
}

OWNER_MODULES = {
    "dwp-approval-server",
    "dwp-auth-server",
    "dwp-meeting-server",
    "dwp-messaging-server",
    "dwp-notification-server",
    "dwp-people-server",
    "dwp-provider-server",
    "dwp-space-server",
}
OWNER_PACKAGE_RE = re.compile(
    r"^\s*import\s+com\.dwp\.services\."
    r"(?:approval|auth|meeting|messaging|notification|people|provider|space)\.",
    re.MULTILINE,
)
PERSISTENCE_IMPORT_RE = re.compile(
    r"^\s*import\s+(?:"
    r"jakarta\.persistence\."
    r"|java\.sql\."
    r"|javax\.sql\."
    r"|org\.springframework\.data\."
    r"|org\.springframework\.jdbc\."
    r")",
    re.MULTILINE,
)
PERSISTENCE_SYMBOL_RE = re.compile(
    r"\b(?:DataSource|DriverManager|EntityManager|JdbcTemplate|"
    r"NamedParameterJdbcTemplate|createNativeQuery|createQuery)\b|@Query\b"
)
PROJECT_DEP_RE = re.compile(
    r"^\s*(?:api|implementation|compileOnly|runtimeOnly)\s+"
    r"project\(['\"]:([^'\"]+)['\"]\)",
    re.MULTILINE,
)
SIBLING_DB_ENV_RE = re.compile(
    r"\$\{(?:AUTH|APPROVAL|MEETING|MESSAGING|NOTIFICATION|PEOPLE|PROVIDER|SPACE)_DB_"
)
LOGGER_CALL_RE = re.compile(
    r"\b(?:log|logger)\.(?:trace|debug|info|warn|error)\s*\((.*?)\)\s*;",
    re.DOTALL,
)
METRIC_CALL_RE = re.compile(
    r"\b(?:meterRegistry|metrics)\.(?:counter|timer|gauge|summary)\s*\((.*?)\)\s*;",
    re.DOTALL,
)
SENSITIVE_TELEMETRY_IDENTIFIERS = re.compile(
    r"\b(?:tenantId|userId|recipientId|personPublicId|displayName|permissions|"
    r"resourceRoles|payload|title|body|credential|token|authorization|cookie)\b",
    re.IGNORECASE,
)
JAVA_COMMENTS_AND_LITERALS_RE = re.compile(
    r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\r\n]*',
    re.DOTALL,
)


def _executable_java(source: str) -> str:
    return JAVA_COMMENTS_AND_LITERALS_RE.sub(
        lambda match: " " * len(match.group()), source
    )


def _relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def violations(root: Path = DEFAULT_ROOT, require_runtime: bool = True) -> list[str]:
    root = root.resolve()
    runtime_root = root / RUNTIME_RELATIVE
    problems: list[str] = []
    sources = sorted(runtime_root.rglob("*.java")) if runtime_root.is_dir() else []
    if require_runtime and not sources:
        problems.append(f"{RUNTIME_RELATIVE.as_posix()} has no runtime Java sources")
    for source_path in sources:
        source = source_path.read_text(encoding="utf-8")
        executable = _executable_java(source)
        relative = _relative(source_path, root)
        if OWNER_PACKAGE_RE.search(source):
            problems.append(
                f"{relative} imports an owner-service implementation; use the shared provider contract"
            )
        platform_owned_persistence = relative in PLATFORM_OWNED_PERSISTENCE
        if PERSISTENCE_IMPORT_RE.search(source) and not platform_owned_persistence:
            problems.append(
                f"{relative} imports persistence APIs; Home Runtime must call owner providers"
            )
        symbol = PERSISTENCE_SYMBOL_RE.search(executable)
        if symbol and not platform_owned_persistence:
            problems.append(
                f"{relative} uses persistence symbol {symbol.group(0)!r}; sibling data is provider-owned"
            )
        for call in LOGGER_CALL_RE.findall(executable):
            match = SENSITIVE_TELEMETRY_IDENTIFIERS.search(call)
            if match:
                problems.append(
                    f"{relative} sends sensitive identifier {match.group(0)!r} to a logger"
                )
        for call in METRIC_CALL_RE.findall(executable):
            match = SENSITIVE_TELEMETRY_IDENTIFIERS.search(call)
            if match:
                problems.append(
                    f"{relative} sends sensitive identifier {match.group(0)!r} to a metric"
                )

    build_path = root / PLATFORM_BUILD
    if build_path.is_file():
        build = build_path.read_text(encoding="utf-8")
        for module in PROJECT_DEP_RE.findall(build):
            if module in OWNER_MODULES:
                problems.append(
                    f"{PLATFORM_BUILD.as_posix()} directly depends on owner module {module}"
                )

    config_path = root / PLATFORM_CONFIG
    if config_path.is_file():
        for line_number, line in enumerate(
            config_path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            executable_line = line.split("#", 1)[0]
            match = SIBLING_DB_ENV_RE.search(executable_line)
            if match:
                problems.append(
                    f"{PLATFORM_CONFIG.as_posix()}:{line_number} references sibling database configuration"
                )
    return sorted(set(problems))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--allow-missing-runtime", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    problems = violations(args.root, require_runtime=not args.allow_missing_runtime)
    result = {
        "gate": "W4-SIBLING-DB-AND-TELEMETRY-BOUNDARY",
        "status": "PASS" if not problems else "FAIL",
        "violations": problems,
    }
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    elif problems:
        print("Home Runtime boundary violations found.", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
    else:
        print(
            "PASS Home Runtime boundary: no sibling implementation/database access "
            "or sensitive runtime telemetry arguments detected."
        )
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
