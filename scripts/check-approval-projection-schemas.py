#!/usr/bin/env python3
"""Verify W1a v2 Approval field-mask projections against raw OpenAPI schemas."""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts/product-authorization/product-surfaces-v1.yaml"
REGISTRY = ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v2.json"
PEP = (
    ROOT
    / "dwp-approval-server/src/main/resources/product-authorization"
    / "approval-pilot-pep-v2.generated.json"
)
OPENAPI = ROOT / "contracts/openapi/approval.json"

SCHEMA_PROFILES = {
    "ApprovalOversightAdminPulseV1": "legacy-oversight",
    "ApprovalOversightWorkflowV1": "legacy-oversight",
    "ApprovalOversightFormV1": "legacy-oversight",
    "ApprovalOversightPolicyV1": "legacy-oversight",
    "ApprovalAuditorOperationsV1": "auditor",
    "ApprovalOversightOperationsV1": "legacy-oversight",
    "ApprovalOversightSignatureV1": "legacy-oversight",
}
TARGET_PROFILES = frozenset(SCHEMA_PROFILES.values())
BASE_FIELDS = frozenset({
    "apiBindingKey", "projectionPolicyKey", "responseSchemaKey"
})
METADATA_FIELDS = frozenset({
    "schemaVersion", "openApiSchemaSha256", "additionalProperties"
})
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"cannot read {path.relative_to(ROOT)}: {exception}")
    if not isinstance(value, dict):
        fail(f"{path.relative_to(ROOT)} must contain one JSON object")
    return value


def fail(message: str) -> None:
    raise SystemExit(f"Approval projection schema check failed: {message}")


def raw_schema_sha256(schema: dict[str, Any]) -> str:
    payload = json.dumps(
        schema, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def openapi_hashes(document: dict[str, Any]) -> dict[str, str]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        fail("approval OpenAPI components.schemas is missing")
    result: dict[str, str] = {}
    for schema_key in SCHEMA_PROFILES:
        schema = schemas.get(schema_key)
        if not isinstance(schema, dict):
            fail(f"OpenAPI component {schema_key} is missing")
        if schema.get("additionalProperties") is not False:
            fail(f"OpenAPI component {schema_key} must set additionalProperties=false")
        result[schema_key] = raw_schema_sha256(schema)
    return result


def binding_record(
    route_key: str,
    profile_key: str,
    binding: dict[str, Any],
    hashes: dict[str, str],
) -> tuple[Any, ...]:
    if set(binding) != BASE_FIELDS | METADATA_FIELDS:
        fail(f"{route_key}/{profile_key}: projection fields changed")
    schema_key = binding.get("responseSchemaKey")
    if SCHEMA_PROFILES.get(schema_key) != profile_key:
        fail(f"{route_key}/{profile_key}: unregistered field-mask schema {schema_key}")
    schema_version = binding.get("schemaVersion")
    schema_hash = binding.get("openApiSchemaSha256")
    if type(schema_version) is not int or schema_version != 1:
        fail(f"{route_key}/{profile_key}: schemaVersion must be integer 1")
    if not isinstance(schema_hash, str) or SHA256_PATTERN.fullmatch(schema_hash) is None:
        fail(f"{route_key}/{profile_key}: OpenAPI schema hash format is invalid")
    if schema_hash != hashes[schema_key]:
        fail(f"{route_key}/{profile_key}: {schema_key} OpenAPI schema hash drift")
    if binding.get("additionalProperties") is not False:
        fail(f"{route_key}/{profile_key}: additionalProperties must be false")
    return (
        route_key,
        profile_key,
        binding["apiBindingKey"],
        binding["projectionPolicyKey"],
        schema_key,
        schema_version,
        schema_hash,
        False,
    )


def generated_records(
    document: dict[str, Any],
    hashes: dict[str, str],
    registry: bool,
) -> set[tuple[Any, ...]]:
    records: set[tuple[Any, ...]] = set()
    seen_schemas: set[str] = set()
    routes = document.get("routes")
    if not isinstance(routes, list):
        fail("generated routes are missing")
    for route in routes:
        if not isinstance(route, dict):
            fail("generated route must be an object")
        subject = route.get("subject", {})
        if registry and not (
            isinstance(subject, dict) and subject.get("productKey") == "approvals"
        ):
            continue
        route_key = route.get("routeContractKey")
        for profile in route.get("accessProfiles", []):
            profile_key = profile.get("profileKey")
            for binding in profile.get("responseProjectionBindings", []):
                if not isinstance(binding, dict):
                    fail(f"{route_key}/{profile_key}: projection must be an object")
                if profile_key in TARGET_PROFILES:
                    record = binding_record(route_key, profile_key, binding, hashes)
                    if record in records:
                        fail(f"{route_key}/{profile_key}: duplicate projection binding")
                    records.add(record)
                    seen_schemas.add(binding["responseSchemaKey"])
                elif set(binding) != BASE_FIELDS:
                    fail(f"{route_key}/{profile_key}: field-mask metadata is forbidden")
    if seen_schemas != set(SCHEMA_PROFILES):
        fail("the seven Approval field-mask schemas are not covered exactly")
    return records


def source_records(
    document: dict[str, Any], hashes: dict[str, str]
) -> set[tuple[Any, ...]]:
    enrichment = document.get("descriptorEnrichments")
    if not isinstance(enrichment, dict):
        fail("canonical descriptorEnrichments is missing")
    records: set[tuple[Any, ...]] = set()
    for route in enrichment.get("routes", []):
        route_key = route.get("routeContractKey")
        for binding in route.get("projectionBindings", []):
            if not isinstance(binding, dict):
                fail(f"{route_key}: canonical projection must be an object")
            profile_key = binding.get("profileKey")
            has_metadata = bool(set(binding) & METADATA_FIELDS)
            if profile_key in TARGET_PROFILES or has_metadata:
                generated_shape = {
                    key: value for key, value in binding.items() if key != "profileKey"
                }
                record = binding_record(
                    route_key, profile_key, generated_shape, hashes
                )
                if record in records:
                    fail(f"{route_key}/{profile_key}: duplicate canonical projection")
                records.add(record)
    if {record[4] for record in records} != set(SCHEMA_PROFILES):
        fail("canonical source does not carry all seven schema hashes")
    return records


def main() -> None:
    hashes = openapi_hashes(load(OPENAPI))
    source = source_records(load(SOURCE), hashes)
    registry = generated_records(load(REGISTRY), hashes, registry=True)
    pep = generated_records(load(PEP), hashes, registry=False)
    if source != registry or registry != pep:
        fail("canonical source, v2 registry, and Approval PEP projections differ")
    print(
        "Approval projection schema contract OK: "
        f"schemas={len(hashes)} bindings={len(registry)}"
    )


if __name__ == "__main__":
    main()
