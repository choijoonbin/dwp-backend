#!/usr/bin/env python3
"""Verify Wave 4 owner widgets stay truthful, non-authoritative, and hash-bound."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path("contracts/widget-registry/wave4-owner-widget-manifests.v1.json")
PROVIDER_CONTRACT = Path("contracts/home-runtime/owner-provider-batch.v1.json")
MIGRATION = Path(
    "dwp-platform-server/src/main/resources/db/migration/"
    "V299__seed_wave4_owner_home_widget_providers.sql"
)
BACKEND_EVIDENCE = frozenset({"MANIFEST", "SECURITY", "PRIVACY"})
FRONTEND_EVIDENCE = frozenset({"A11Y", "PERFORMANCE", "LOCALIZATION"})
GIT_REF = re.compile(r"^git:([0-9a-f]{40}):([^#]+)#([a-z0-9-]+)$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def canonical_sha256(value: object) -> str:
    # The checked manifests use only the RFC 8785 JSON subset of strings, integers,
    # booleans, nulls, arrays, and objects, so this is their JCS representation.
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def git_blob(root: Path, commit: str, path: str) -> bytes:
    completed = subprocess.run(
        ["git", "show", f"{commit}:{path}"],
        cwd=root,
        check=False,
        capture_output=True,
    )
    if completed.returncode:
        raise ValueError(f"git evidence does not resolve: {commit}:{path}")
    return completed.stdout


def _load_object(path: Path, label: str, problems: list[str]) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        problems.append(f"{label} cannot be loaded: {exception}")
        return {}
    if not isinstance(value, dict):
        problems.append(f"{label} must be a JSON object")
        return {}
    return value


def violations(
    root: Path = ROOT,
    blob_reader: Callable[[Path, str, str], bytes] = git_blob,
) -> list[str]:
    root = root.resolve()
    problems: list[str] = []
    fixture = _load_object(root / FIXTURE, "owner widget fixture", problems)
    providers = _load_object(root / PROVIDER_CONTRACT, "owner provider contract", problems)
    try:
        migration = (root / MIGRATION).read_text(encoding="utf-8")
    except OSError as exception:
        problems.append(f"owner widget migration cannot be loaded: {exception}")
        migration = ""

    if fixture.get("registryMode") != "SHADOW":
        problems.append("owner widget fixture must remain SHADOW")
    if fixture.get("runtimeActivationReady") is not False:
        problems.append("owner widget fixture cannot claim runtime activation readiness")
    if fixture.get("canonicalization") != {
        "algorithm": "RFC8785-JCS",
        "encoding": "UTF-8",
        "hash": "SHA-256",
        "hashScope": "manifest",
        "stringPrecondition": "NFC",
    }:
        problems.append("owner widget fixture canonicalization contract drifted")

    expected_by_key: dict[str, tuple[str, dict]] = {}
    owners = providers.get("owners")
    if not isinstance(owners, dict):
        problems.append("owner provider contract has no owner map")
        owners = {}
    for owner, entry in owners.items():
        if not isinstance(entry, dict):
            continue
        bindings = entry.get("definitionBindings")
        if not isinstance(bindings, dict):
            problems.append(f"provider owner {owner} has no definition bindings")
            continue
        for definition, binding in bindings.items():
            if definition in expected_by_key:
                problems.append(f"provider definition is duplicated: {definition}")
            expected_by_key[definition] = (owner, binding)

    fixtures = fixture.get("fixtures")
    if not isinstance(fixtures, list):
        problems.append("owner widget fixture must contain a fixtures array")
        fixtures = []
    if len(fixtures) != 12:
        problems.append(f"owner widget fixture must contain 12 definitions, found {len(fixtures)}")
    actual_keys: set[str] = set()
    ids: set[str] = set()
    enabled: set[str] = set()
    expected_sql_evidence: set[tuple[str, str, str, str]] = set()

    for index, raw in enumerate(fixtures):
        label = f"fixtures[{index}]"
        if not isinstance(raw, dict) or not isinstance(raw.get("manifest"), dict):
            problems.append(f"{label} must contain a manifest object")
            continue
        manifest = raw["manifest"]
        definition = manifest.get("definitionKey")
        if not isinstance(definition, str) or definition in actual_keys:
            problems.append(f"{label} has a missing or duplicate definitionKey")
            continue
        actual_keys.add(definition)
        for id_field in ("definitionId", "versionId", "rendererBindingId", "releaseChannelId"):
            identifier = raw.get(id_field)
            if not isinstance(identifier, str) or identifier in ids:
                problems.append(f"{label}.{id_field} is missing or duplicated")
            else:
                ids.add(identifier)
        expected = expected_by_key.get(definition)
        if expected is None:
            problems.append(f"{label} is not declared by an owner provider: {definition}")
            continue
        owner, binding = expected
        if raw.get("providerKey") != owner:
            problems.append(f"{definition} providerKey does not match {owner}")
        if raw.get("legacyWidgetKey") != binding.get("legacyWidgetKey"):
            problems.append(f"{definition} legacy widget binding drifted")
        if manifest.get("owner", {}).get("sourceAppResourceKey") != binding.get(
            "sourceAppResourceKey"
        ):
            problems.append(f"{definition} source app binding drifted")
        if manifest.get("renderer", {}).get("rendererKey") != binding.get("rendererKey"):
            problems.append(f"{definition} renderer binding drifted")
        manifest_authorities = manifest.get("requiredAuthorities")
        contract_authorities = binding.get("requiredAuthorities")
        if (
            not isinstance(manifest_authorities, list)
            or len(manifest_authorities) != len(set(manifest_authorities))
            or set(manifest_authorities) != set(contract_authorities or [])
        ):
            problems.append(f"{definition} required authority binding drifted")
        if manifest.get("actionCapabilities") != []:
            problems.append(f"{definition} cannot advertise commands in Wave 4")
        if manifest.get("privacy", {}).get("recipientContextBinding") is not True:
            problems.append(f"{definition} must remain recipient-bound")

        digest = canonical_sha256(manifest)
        if raw.get("expectedSha256") != digest:
            problems.append(f"{definition} manifest hash is not canonical")
        compact_manifest = json.dumps(manifest, ensure_ascii=False, separators=(",", ":"))
        if compact_manifest not in migration or digest not in migration:
            problems.append(f"{definition} manifest/hash is missing from V299")

        if raw.get("enabledByDefault") is True:
            enabled.add(definition)
        evidence = raw.get("backendEvidence")
        if not isinstance(evidence, list):
            problems.append(f"{definition} backendEvidence must be an array")
            continue
        types = {item.get("type") for item in evidence if isinstance(item, dict)}
        if types != BACKEND_EVIDENCE or len(evidence) != len(BACKEND_EVIDENCE):
            problems.append(f"{definition} must have exactly MANIFEST/SECURITY/PRIVACY evidence")
        for item in evidence:
            if not isinstance(item, dict):
                problems.append(f"{definition} has malformed backend evidence")
                continue
            kind = item.get("type")
            reference = item.get("ref")
            evidence_hash = item.get("sha256")
            if not isinstance(evidence_hash, str) or not SHA256.fullmatch(evidence_hash):
                problems.append(f"{definition} {kind} evidence hash is invalid")
                continue
            if kind == "MANIFEST":
                expected_ref = f"fixture:wave4-owner-widget-manifests.v1:{definition}"
                if reference != expected_ref or evidence_hash != digest:
                    problems.append(f"{definition} manifest evidence is not self-bound")
            elif kind in {"SECURITY", "PRIVACY"}:
                match = GIT_REF.fullmatch(reference or "")
                if match is None:
                    problems.append(f"{definition} {kind} evidence must use a full git blob ref")
                    continue
                commit, path, _anchor = match.groups()
                try:
                    actual_hash = hashlib.sha256(blob_reader(root, commit, path)).hexdigest()
                except ValueError as exception:
                    problems.append(str(exception))
                    continue
                if evidence_hash != actual_hash:
                    problems.append(f"{definition} {kind} evidence hash does not match its git blob")
            if isinstance(kind, str) and isinstance(reference, str):
                expected_sql_evidence.add((definition, kind, reference, evidence_hash))

    # The provider batch contract is cumulative. Later waves may add signed external owners
    # (for example DWAI·ON) without rewriting the immutable Wave 4 twelve-definition fixture.
    # Every Wave 4 fixture must still resolve to a provider binding; later bindings are outside
    # this gate's declared WAVE4_OWNER_PROVIDER_DEFINITIONS scope.
    undeclared = actual_keys - set(expected_by_key)
    if undeclared:
        problems.append(
            "owner widget fixture/provider inventory mismatch: "
            f"undeclared={sorted(undeclared)}"
        )
    if enabled != {"notification.app-badges"}:
        problems.append(f"only notification.app-badges may be enabled in Wave 4, found {sorted(enabled)}")

    if "AUTHORITATIVE" in migration:
        problems.append("V299 must not contain an AUTHORITATIVE registry mode")
    if "RUNTIME_ACTIVATION_READY = TRUE" in migration.upper():
        problems.append("V299 must not enable runtime activation")
    for forbidden in FRONTEND_EVIDENCE:
        if f"'{forbidden}'" in migration:
            problems.append(f"V299 fabricates frontend {forbidden} evidence")
    version_blocks = re.findall(
        r"INSERT INTO plt_widget_definition_versions\b.*?ON CONFLICT.*?;",
        migration,
        flags=re.DOTALL,
    )
    if len(version_blocks) != 12:
        problems.append(f"V299 must insert 12 owner versions, found {len(version_blocks)}")
    for index, block in enumerate(version_blocks):
        if "certification_status" not in block or "'NOT_RUN'" not in block:
            problems.append(f"V299 owner version {index + 1} must remain certification NOT_RUN")
        if "'PASS'" in block:
            problems.append(f"V299 owner version {index + 1} falsely claims PASS certification")
        if "OWNER_PROVIDER_SHADOW" not in block:
            problems.append(f"V299 owner version {index + 1} lacks SHADOW attestation")
    if "definition.definition_key = 'notification.app-badges'" not in migration:
        problems.append("V299 does not constrain default enablement to notification.app-badges")
    for definition, kind, reference, digest in expected_sql_evidence:
        if all(value in migration for value in (definition, kind, reference, digest)):
            continue
        problems.append(f"V299 is missing hash-bound {kind} evidence for {definition}")

    return sorted(set(problems))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    problems = violations(args.root)
    result = {
        "gate": "W4-OWNER-WIDGET-SHADOW-SEED",
        "status": "PASS" if not problems else "FAIL",
        "violations": problems,
    }
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    elif problems:
        print("Wave 4 owner widget seed violations found.", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
    else:
        print("PASS Wave 4 owner widgets: 12 SHADOW definitions, truthful backend evidence, no FE certification.")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
