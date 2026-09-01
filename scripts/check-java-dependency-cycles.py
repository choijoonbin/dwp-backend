#!/usr/bin/env python3
"""Ratchet Java source dependency cycles and forbid Spring constructor cycles."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE_FILE = ROOT / "docs/architecture/java-dependency-cycle-baseline.json"
JAVA_GLOB = "*/src/main/java/**/*.java"
COMPONENT_ANNOTATIONS = (
    "Component",
    "Configuration",
    "Controller",
    "Repository",
    "RestController",
    "Service",
)
COMPONENT_PATTERN = re.compile(
    r"@(?:[\w.]+\.)?(?:" + "|".join(COMPONENT_ANNOTATIONS) + r")\b"
)
NON_CODE_PATTERN = re.compile(
    r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\r\n]*',
    re.DOTALL,
)


@dataclass(frozen=True)
class JavaSource:
    path: Path
    relative_path: str
    package: str
    class_name: str
    fqcn: str
    code: str
    identifiers: frozenset[str]
    imports: tuple[str, ...]


def main() -> int:
    try:
        sources = load_sources()
        dependency_cycles = strongly_connected_components(dependency_graph(sources))
        runtime_cycles = strongly_connected_components(constructor_injection_graph(sources))
        errors = compare_with_baseline(dependency_cycles)
        if runtime_cycles:
            errors.append("Spring constructor dependency cycles are never allowlisted:")
            errors.extend(f"  - {' -> '.join(cycle)}" for cycle in runtime_cycles)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Java dependency cycle check could not run: {error}", file=sys.stderr)
        return 2

    if errors:
        print("Java dependency cycle policy violations found.", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1

    print(
        "PASS Java dependency cycles: "
        f"{len(sources)} production classes, {len(dependency_cycles)} exact legacy SCCs, "
        "0 Spring constructor cycles."
    )
    return 0


def load_sources() -> dict[str, JavaSource]:
    sources: dict[str, JavaSource] = {}
    for path in sorted(ROOT.glob(JAVA_GLOB)):
        raw = path.read_text(encoding="utf-8")
        code = strip_non_code(raw)
        package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.MULTILINE)
        if package_match is None:
            continue
        package = package_match.group(1)
        class_name = path.stem
        fqcn = f"{package}.{class_name}"
        if fqcn in sources:
            raise ValueError(f"duplicate production class {fqcn}")
        sources[fqcn] = JavaSource(
            path=path,
            relative_path=path.relative_to(ROOT).as_posix(),
            package=package,
            class_name=class_name,
            fqcn=fqcn,
            code=code,
            identifiers=frozenset(re.findall(r"\b[A-Za-z_$][\w$]*\b", code)),
            imports=tuple(
                re.findall(
                    r"^\s*import\s+(?:static\s+)?([\w.]+)(?:\.\*)?\s*;",
                    code,
                    re.MULTILINE,
                )
            ),
        )
    return sources


def dependency_graph(sources: dict[str, JavaSource]) -> dict[str, set[str]]:
    by_package: dict[str, dict[str, str]] = {}
    for source in sources.values():
        by_package.setdefault(source.package, {})[source.class_name] = source.fqcn

    graph = {fqcn: set() for fqcn in sources}
    for source in sources.values():
        dependencies = graph[source.fqcn]
        for imported in source.imports:
            target = imported
            while target and target not in sources:
                target = target.rpartition(".")[0]
            if target in sources:
                dependencies.add(target)
        for class_name, target in by_package[source.package].items():
            if class_name in source.identifiers:
                dependencies.add(target)
        dependencies.discard(source.fqcn)
    return graph


def constructor_injection_graph(
    sources: dict[str, JavaSource],
) -> dict[str, set[str]]:
    components = {
        fqcn: source
        for fqcn, source in sources.items()
        if COMPONENT_PATTERN.search(source.code)
    }
    graph = {fqcn: set() for fqcn in components}
    by_simple_name: dict[str, set[str]] = {}
    for target, source in components.items():
        by_simple_name.setdefault(source.class_name, set()).add(target)
    for fqcn, source in components.items():
        constructor_start = re.compile(
            rf"^[ \t]*(?:(?:public|protected|private)[ \t]+)?"
            rf"{re.escape(source.class_name)}[ \t]*\(",
            re.MULTILINE,
        )
        parameters = "\n".join(
            balanced_parentheses(source.code, match.end() - 1)
            for match in constructor_start.finditer(source.code)
        )
        parameter_identifiers = set(re.findall(r"\b[A-Za-z_$][\w$]*\b", parameters))
        imported_types = set(source.imports)
        for class_name in parameter_identifiers:
            for target in by_simple_name.get(class_name, set()):
                candidate = components[target]
                if target != fqcn and (
                    candidate.package == source.package or target in imported_types
                ):
                    graph[fqcn].add(target)
    return graph


def balanced_parentheses(source: str, opening: int) -> str:
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "(":
            depth += 1
        elif source[index] == ")":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise ValueError("unterminated Java constructor parameter list")


def strongly_connected_components(
    graph: dict[str, set[str]],
) -> list[tuple[str, ...]]:
    index = 0
    indexes: dict[str, int] = {}
    low_links: dict[str, int] = {}
    stack: list[str] = []
    active: set[str] = set()
    components: list[tuple[str, ...]] = []

    def visit(node: str) -> None:
        nonlocal index
        indexes[node] = index
        low_links[node] = index
        index += 1
        stack.append(node)
        active.add(node)
        for dependency in sorted(graph[node]):
            if dependency not in indexes:
                visit(dependency)
                low_links[node] = min(low_links[node], low_links[dependency])
            elif dependency in active:
                low_links[node] = min(low_links[node], indexes[dependency])
        if low_links[node] != indexes[node]:
            return
        component: list[str] = []
        while True:
            member = stack.pop()
            active.remove(member)
            component.append(member)
            if member == node:
                break
        if len(component) > 1:
            components.append(tuple(sorted(component)))

    for node in sorted(graph):
        if node not in indexes:
            visit(node)
    return sorted(components)


def compare_with_baseline(actual_cycles: list[tuple[str, ...]]) -> list[str]:
    baseline = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    if baseline.get("schemaVersion") != 1 or not isinstance(baseline.get("cycles"), list):
        raise ValueError("cycle baseline must use schemaVersion 1 and a cycles array")

    errors: list[str] = []
    expected: dict[tuple[str, ...], str] = {}
    seen_ids: set[str] = set()
    today = date.today()
    for entry in baseline["cycles"]:
        cycle_id = required_text(entry, "id")
        owner = required_text(entry, "owner")
        reason = required_text(entry, "reason")
        review_by = date.fromisoformat(required_text(entry, "reviewBy"))
        members = entry.get("members")
        if not isinstance(members, list) or len(members) < 2:
            raise ValueError(f"{cycle_id}: members must contain at least two classes")
        canonical = tuple(sorted(str(member) for member in members))
        if len(canonical) != len(set(canonical)):
            raise ValueError(f"{cycle_id}: duplicate cycle member")
        if cycle_id in seen_ids or canonical in expected:
            raise ValueError(f"duplicate cycle baseline entry {cycle_id}")
        if entry.get("runtimeComponentCycle") is not False:
            raise ValueError(f"{cycle_id}: runtimeComponentCycle must be false")
        seen_ids.add(cycle_id)
        expected[canonical] = cycle_id
        if review_by < today:
            errors.append(
                f"  - stale exception {cycle_id} owned by {owner}: reviewBy {review_by}; {reason}"
            )

    actual = set(actual_cycles)
    expected_cycles = set(expected)
    for cycle in sorted(actual - expected_cycles):
        errors.append(f"  - new or expanded SCC: {', '.join(cycle)}")
    for cycle in sorted(expected_cycles - actual):
        errors.append(
            f"  - stale baseline {expected[cycle]}: cycle was removed; delete its exception"
        )
    return errors


def required_text(entry: object, key: str) -> str:
    if not isinstance(entry, dict):
        raise ValueError("each cycle baseline entry must be an object")
    value = entry.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"cycle baseline {key} must be non-empty")
    return value.strip()


def strip_non_code(source: str) -> str:
    return NON_CODE_PATTERN.sub(
        lambda match: "".join(
            "\n" if character == "\n" else " " for character in match.group()
        ),
        source,
    )


if __name__ == "__main__":
    raise SystemExit(main())
