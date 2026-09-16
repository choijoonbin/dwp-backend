#!/usr/bin/env python3
"""Validate machine-readable Wave 4 evidence without trusting prose status claims."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CHECKS = {
    "W4-AUTHORIZATION-CACHE",
    "W4-CACHE-INVALIDATION",
    "W4-COMMAND-RECEIPT",
    "W4-CONTRACT-CANONICAL",
    "W4-FAILURE-ISOLATION",
    "W4-FRESHNESS-STATE",
    "W4-GATEWAY-IDENTITY",
    "W4-PRIVACY-TELEMETRY",
    "W4-PROVIDER-IDENTITY",
    "W4-ROLLBACK",
    "W4-SIBLING-DB-BOUNDARY",
    "W4-SLO",
}
SHA256_RE = re.compile(r"[0-9a-f]{64}")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")


def _object(value: Any, label: str, problems: list[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        problems.append(f"{label} must be an object")
        return {}
    return value


def _safe_path(root: Path, relative: Any, label: str, problems: list[str]) -> Optional[Path]:
    if not isinstance(relative, str) or not relative or relative.startswith(("/", "~")):
        problems.append(f"{label} must be a non-empty repository-relative path")
        return None
    candidate = (root / relative).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        problems.append(f"{label} escapes the repository")
        return None
    return candidate


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _commit_is_ancestor(root: Path, commit: str) -> bool:
    completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit, "HEAD"],
        cwd=root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return completed.returncode == 0


def validate(
    evidence: dict[str, Any], root: Path = DEFAULT_ROOT, allow_pending: bool = False
) -> list[str]:
    root = root.resolve()
    problems: list[str] = []
    if evidence.get("schemaVersion") != 1:
        problems.append("schemaVersion must equal 1")
    if evidence.get("gateId") != "W4_RUNTIME_GATE":
        problems.append("gateId must equal W4_RUNTIME_GATE")
    expected_status = "PENDING" if allow_pending else "PASS"
    if evidence.get("status") != expected_status:
        problems.append(f"status must equal {expected_status}")
    if evidence.get("registryAuthoritative") is not False:
        problems.append("registryAuthoritative must remain false through Wave 4")
    if evidence.get("productionReleaseReady") is not False:
        problems.append("productionReleaseReady must remain false until Wave 6")
    if evidence.get("nextWave") != "WAVE5":
        problems.append("nextWave must equal WAVE5")

    commit = evidence.get("backendCommit")
    if allow_pending:
        if commit is not None:
            problems.append("pending template backendCommit must be null")
    elif not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit):
        problems.append("backendCommit must be a full lowercase Git commit")
    elif not _commit_is_ancestor(root, commit):
        problems.append("backendCommit is not an ancestor of the checked-out repository")

    checks = evidence.get("checks")
    if not isinstance(checks, list):
        problems.append("checks must be an array")
        checks = []
    check_ids = [item.get("id") for item in checks if isinstance(item, dict)]
    if len(check_ids) != len(set(check_ids)):
        problems.append("checks contain duplicate ids")
    if set(check_ids) != REQUIRED_CHECKS:
        missing = sorted(REQUIRED_CHECKS - set(check_ids))
        extra = sorted(set(check_ids) - REQUIRED_CHECKS)
        problems.append(f"checks mismatch; missing={missing}, extra={extra}")
    for index, item in enumerate(checks):
        check = _object(item, f"checks[{index}]", problems)
        if check.get("status") != expected_status:
            problems.append(f"checks[{index}].status must equal {expected_status}")
        command = check.get("command")
        if not isinstance(command, list) or not command or not all(
            isinstance(part, str) and part for part in command
        ):
            problems.append(f"checks[{index}].command must be a non-empty argv array")
        artifacts = check.get("artifacts")
        if not isinstance(artifacts, list):
            problems.append(f"checks[{index}].artifacts must be an array")
            continue
        if not allow_pending and not artifacts:
            problems.append(f"checks[{index}] must reference hashed evidence")
        for artifact_index, artifact_value in enumerate(artifacts):
            artifact = _object(
                artifact_value,
                f"checks[{index}].artifacts[{artifact_index}]",
                problems,
            )
            path = _safe_path(
                root,
                artifact.get("path"),
                f"checks[{index}].artifacts[{artifact_index}].path",
                problems,
            )
            digest = artifact.get("sha256")
            if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
                problems.append(
                    f"checks[{index}].artifacts[{artifact_index}].sha256 is invalid"
                )
            if path is not None and not allow_pending:
                if not path.is_file():
                    problems.append(f"evidence file does not exist: {path.relative_to(root)}")
                elif isinstance(digest, str) and SHA256_RE.fullmatch(digest):
                    if _sha256(path) != digest:
                        problems.append(f"evidence hash mismatch: {path.relative_to(root)}")

    contract_hashes = evidence.get("contractHashes")
    if not isinstance(contract_hashes, dict):
        problems.append("contractHashes must be an object")
        contract_hashes = {}
    if not allow_pending and not contract_hashes:
        problems.append("contractHashes must contain canonical contract inputs")
    for relative, digest in contract_hashes.items():
        path = _safe_path(root, relative, f"contractHashes[{relative!r}]", problems)
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            problems.append(f"contractHashes[{relative!r}] is not SHA-256")
        elif path is not None and not path.is_file():
            problems.append(f"contract file does not exist: {relative}")
        elif path is not None and _sha256(path) != digest:
            problems.append(f"contract hash mismatch: {relative}")

    coverage = _object(evidence.get("providerCoverage"), "providerCoverage", problems)
    active = coverage.get("activeDefinitions")
    verified = coverage.get("verifiedDefinitions")
    if allow_pending:
        if active != 0 or verified != 0:
            problems.append("pending provider coverage must be zero")
    elif type(active) is not int or active <= 0 or verified != active:
        problems.append("every active definition must have a verified provider")

    security = _object(evidence.get("security"), "security", problems)
    for field in ("crossTenantLeakCount", "forbiddenLeakCount", "telemetryLeakCount"):
        if security.get(field) != 0:
            problems.append(f"security.{field} must equal zero")

    slo = _object(evidence.get("slo"), "slo", problems)
    if slo.get("liveProduction") is not False:
        problems.append("slo.liveProduction must remain false for the Wave 4 pre-production gate")
    if allow_pending:
        if slo.get("status") != "PENDING":
            problems.append("pending slo.status must equal PENDING")
    else:
        if slo.get("status") != "PASS":
            problems.append("slo.status must equal PASS")
        if type(slo.get("sampleCount")) is not int or slo["sampleCount"] < 1000:
            problems.append("slo.sampleCount must be at least 1000")
        if not isinstance(slo.get("serverP95Ms"), (int, float)) or slo["serverP95Ms"] > 1000:
            problems.append("slo.serverP95Ms must be at most 1000")
        if not isinstance(slo.get("hardFailureRate"), (int, float)) or not 0 <= slo["hardFailureRate"] <= 0.001:
            problems.append("slo.hardFailureRate must be between 0 and 0.001")

    rollback = _object(evidence.get("rollback"), "rollback", problems)
    if allow_pending:
        if rollback.get("status") != "PENDING":
            problems.append("pending rollback.status must equal PENDING")
    else:
        if rollback.get("status") != "PASS":
            problems.append("rollback.status must equal PASS")
        if rollback.get("v2KillSwitchVerified") is not True:
            problems.append("rollback.v2KillSwitchVerified must be true")
        if rollback.get("commandKillSwitchVerified") is not True:
            problems.append("rollback.commandKillSwitchVerified must be true")
        if rollback.get("providerKillSwitchVerified") is not True:
            problems.append("rollback.providerKillSwitchVerified must be true")
        if rollback.get("cachePurgeVerified") is not True:
            problems.append("rollback.cachePurgeVerified must be true")
        seconds = rollback.get("recoveryTimeSeconds")
        if type(seconds) is not int or not 0 <= seconds <= 300:
            problems.append("rollback.recoveryTimeSeconds must be between 0 and 300")

    production = _object(
        evidence.get("wave6ProductionTelemetry"),
        "wave6ProductionTelemetry",
        problems,
    )
    if production.get("status") != "PENDING":
        problems.append("wave6ProductionTelemetry.status must remain PENDING through Wave 4")
    if production.get("lcpP75") is not None:
        problems.append("wave6ProductionTelemetry.lcpP75 must remain null through Wave 4")
    if production.get("inpP75") is not None:
        problems.append("wave6ProductionTelemetry.inpP75 must remain null through Wave 4")
    if production.get("canary") != "PENDING":
        problems.append("wave6ProductionTelemetry.canary must remain PENDING through Wave 4")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--allow-pending-template", action="store_true")
    args = parser.parse_args()
    try:
        evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        print(f"Unable to load Wave 4 evidence: {exception}", file=sys.stderr)
        return 1
    if not isinstance(evidence, dict):
        print("Wave 4 evidence root must be an object.", file=sys.stderr)
        return 1
    problems = validate(evidence, args.root, args.allow_pending_template)
    if problems:
        print("Wave 4 runtime gate evidence is invalid.", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    print(
        "PASS Wave 4 runtime gate template structure."
        if args.allow_pending_template
        else "PASS Wave 4 runtime gate evidence; Wave 5 may begin."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
