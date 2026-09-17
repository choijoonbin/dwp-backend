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
WORK_PROJECTION = (
    ROOT
    / "contracts/product-authorization/approval-work-projections-v7.generated.json"
)

SCHEMA_PROFILES = {
    "ApprovalOversightAdminPulseV1": "legacy-oversight",
    "ApprovalOversightWorkflowV1": "legacy-oversight",
    "ApprovalOversightFormV1": "legacy-oversight",
    "ApprovalOversightPolicyV1": "legacy-oversight",
    "ApprovalAuditorOperationsV1": "auditor",
    "ApprovalOversightOperationsV1": "legacy-oversight",
    "ApprovalOversightSignatureV1": "legacy-oversight",
}
WORK_SCHEMA_PROFILES = {
    "DraftReconciliation": "full-work",
    "DraftRevisionDetail": "full-work",
    "PageDraftRevision": "full-work",
    "PageRequestSummary": "full-work",
    "PageTaskSummary": "full-work",
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
    for schema_key in SCHEMA_PROFILES | WORK_SCHEMA_PROFILES:
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
    schema_profiles: dict[str, str],
) -> tuple[Any, ...]:
    if set(binding) != BASE_FIELDS | METADATA_FIELDS:
        fail(f"{route_key}/{profile_key}: projection fields changed")
    schema_key = binding.get("responseSchemaKey")
    if schema_profiles.get(schema_key) != profile_key:
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
                    record = binding_record(
                        route_key, profile_key, binding, hashes, SCHEMA_PROFILES
                    )
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
) -> tuple[set[tuple[Any, ...]], set[tuple[Any, ...]]]:
    enrichment = document.get("descriptorEnrichments")
    if not isinstance(enrichment, dict):
        fail("canonical descriptorEnrichments is missing")
    records: set[tuple[Any, ...]] = set()
    work_records: set[tuple[Any, ...]] = set()
    for route in enrichment.get("routes", []):
        route_key = route.get("routeContractKey")
        for binding in route.get("projectionBindings", []):
            if not isinstance(binding, dict):
                fail(f"{route_key}: canonical projection must be an object")
            profile_key = binding.get("profileKey")
            has_metadata = bool(set(binding) & METADATA_FIELDS)
            if profile_key in TARGET_PROFILES:
                generated_shape = {
                    key: value for key, value in binding.items() if key != "profileKey"
                }
                record = binding_record(
                    route_key, profile_key, generated_shape, hashes, SCHEMA_PROFILES
                )
                if record in records:
                    fail(f"{route_key}/{profile_key}: duplicate canonical projection")
                records.add(record)
            elif binding.get("responseSchemaKey") in WORK_SCHEMA_PROFILES:
                generated_shape = {
                    key: value for key, value in binding.items() if key != "profileKey"
                }
                record = binding_record(
                    route_key,
                    profile_key,
                    generated_shape,
                    hashes,
                    WORK_SCHEMA_PROFILES,
                )
                if record in work_records:
                    fail(f"{route_key}/{profile_key}: duplicate work projection")
                work_records.add(record)
            elif has_metadata:
                fail(
                    f"{route_key}/{profile_key}: unregistered field-mask schema "
                    f"{binding.get('responseSchemaKey')}"
                )
    if {record[4] for record in records} != set(SCHEMA_PROFILES):
        fail("canonical source does not carry all seven schema hashes")
    if {record[4] for record in work_records} != set(WORK_SCHEMA_PROFILES):
        fail("canonical source does not carry all five v7 Work schema hashes")
    return records, work_records


def work_contract_records(
    document: dict[str, Any], hashes: dict[str, str]
) -> set[tuple[Any, ...]]:
    bindings = document.get("bindings")
    if not isinstance(bindings, list) or document.get("bindingCount") != len(bindings):
        fail("v7 Work projection binding count is invalid")
    schemas = document.get("schemas")
    if not isinstance(schemas, dict):
        fail("v7 Work projection schema closure is missing")
    records: set[tuple[Any, ...]] = set()
    for binding in bindings:
        if not isinstance(binding, dict):
            fail("v7 Work projection binding must be an object")
        route_key = binding.get("routeContractKey")
        profile_key = binding.get("profileKey")
        generated_shape = {
            key: value
            for key, value in binding.items()
            if key not in {"routeContractKey", "profileKey"}
        }
        record = binding_record(
            route_key,
            profile_key,
            generated_shape,
            hashes,
            WORK_SCHEMA_PROFILES,
        )
        if record in records:
            fail(f"{route_key}/{profile_key}: duplicate v7 Work projection binding")
        records.add(record)
    if {record[4] for record in records} != set(WORK_SCHEMA_PROFILES):
        fail("v7 Work projection artifact does not cover all five schemas exactly")
    for schema_key in WORK_SCHEMA_PROFILES:
        schema = schemas.get(schema_key)
        if not isinstance(schema, dict) or raw_schema_sha256(schema) != hashes[schema_key]:
            fail(f"v7 Work projection artifact schema drift: {schema_key}")
    return records


def main() -> None:
    hashes = openapi_hashes(load(OPENAPI))
    source, source_work = source_records(load(SOURCE), hashes)
    registry = generated_records(load(REGISTRY), hashes, registry=True)
    pep = generated_records(load(PEP), hashes, registry=False)
    work = work_contract_records(load(WORK_PROJECTION), hashes)
    if source != registry or registry != pep:
        fail("canonical source, v2 registry, and Approval PEP projections differ")
    if source_work != work:
        fail("canonical source and v7 Work projection artifact differ")
    print(
        "Approval projection schema contract OK: "
        f"legacySchemas={len(SCHEMA_PROFILES)} legacyBindings={len(registry)} "
        f"workSchemas={len(WORK_SCHEMA_PROFILES)} workBindings={len(work)}"
    )


if __name__ == "__main__":
    main()
