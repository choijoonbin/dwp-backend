#!/usr/bin/env python3
"""Derive the closed v7 work DATA projection graph from a frozen Approval OpenAPI."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "contracts/product-authorization/approval-work-projections-v7.generated.json"
RUNTIME_OUTPUT = (
    ROOT / "dwp-approval-server/src/main/resources/product-authorization"
    / OUTPUT.name
)
DATA_FIELDS = {
    "request-draft-revision": {"revision", "payload", "draftSnapshot"},
    "draft-command-reconciliation": {"idempotencyKey", "receipts"},
}
PAGE_FIELDS = {"items", "totalElements", "totalPages", "page", "size", "hasNext", "evaluatedAt"}
DATA_NAMES = {"tasks-search", "requests-search", "request-draft-revisions",
              "request-draft-revision", "draft-command-reconciliation"}


class ContractError(ValueError):
    pass


def require(value, message):
    if not value:
        raise ContractError(message)


def sha256(value):
    return hashlib.sha256(json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")).hexdigest()


def build_contracts(api, snapshot, *, version=7, route_fields=None,
                    projection_key="approval-work-projections-v7"):
    require(snapshot["version"] == version, f"Response schemas must derive v{version}")
    components = api.get("components", {}).get("schemas", {})
    graph = {}

    def schema_ref(value):
        ref = value.get("$ref")
        require(isinstance(ref, str) and ref.startswith("#/components/schemas/"),
                "An exact component schema reference is required")
        key = ref.removeprefix("#/components/schemas/")
        require(key in components, f"Unresolved OpenAPI schema {key}")
        return key, components[key]

    def collect(value):
        if isinstance(value, dict):
            if "$ref" in value:
                key, schema = schema_ref(value)
                if key not in graph:
                    graph[key] = copy.deepcopy(schema)
                    collect(schema)
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    bindings = []
    route_keys = (set(route_fields) if route_fields is not None else
                  {f"route.approvals.work.{name}.data" for name in DATA_NAMES})
    routes = [route for route in snapshot["routes"] if route["routeContractKey"] in route_keys]
    require(len(routes) == len(route_keys), f"Exact v{version} DATA schema closure is required")
    for route in routes:
        key = route["routeContractKey"]
        binding = route["servicePepBindings"][0]
        operation = api.get("paths", {}).get(binding["path"], {}).get("get")
        require(operation is not None, f"{key}: frozen OpenAPI operation is absent")
        contents = operation.get("responses", {}).get("200", {}).get("content", {})
        responses = [entry.get("schema") for entry in contents.values()]
        require(responses and all(value == responses[0] for value in responses),
                f"{key}: one unambiguous 200 response schema is required")
        _, envelope = schema_ref(responses[0])
        data_ref = envelope.get("properties", {}).get("data")
        require(isinstance(data_ref, dict), f"{key}: ApiResponse data schema is absent")
        schema_key, schema = schema_ref(data_ref)
        require(schema.get("type") == "object" and schema.get("additionalProperties") is False,
                f"{key}: DATA record schema must be closed in the actual OpenAPI")
        short_key = key.removeprefix("route.approvals.work.").removesuffix(".data")
        expected_fields = (route_fields[key] if route_fields is not None else
                           DATA_FIELDS.get(short_key, PAGE_FIELDS))
        require(set(schema.get("properties", {})) == expected_fields,
                f"{key}: actual DTO fields drift")
        collect(data_ref)
        profile = route["accessProfiles"][0]
        bindings.append({
            "routeContractKey": key,
            "profileKey": profile["profileKey"],
            "apiBindingKey": binding["bindingKey"],
            "projectionPolicyKey": f"{key}.{profile['profileKey']}.projection.v1",
            "responseSchemaKey": schema_key,
            "schemaVersion": 1,
            "openApiSchemaSha256": sha256(schema),
            "additionalProperties": False,
        })
    result = {
        "schemaVersion": 1,
        "projectionKey": projection_key,
        "registryRef": {
            "bundleKey": snapshot["bundleKey"], "version": version, "sha256": snapshot["checksum"],
        },
        "bindingCount": len(route_keys),
        "bindings": sorted(bindings, key=lambda value: value["routeContractKey"]),
        "schemas": dict(sorted(graph.items())),
        "schemaClosureSha256": sha256(graph),
        "checksumAlgorithm": "SHA-256",
    }
    result["checksum"] = sha256(result)
    return result


def validate_projection_bindings(snapshot, contracts):
    by_route = {binding["routeContractKey"]: binding for binding in contracts["bindings"]}
    for route in snapshot["routes"]:
        expected = by_route.get(route["routeContractKey"])
        if expected is None:
            continue
        projections = route["accessProfiles"][0].get("responseProjectionBindings", [])
        payload = {key: value for key, value in expected.items()
                   if key not in {"routeContractKey", "profileKey"}}
        require(projections == [payload], f"{route['routeContractKey']}: projection metadata drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True,
                        help="Root's authoritative frozen export, not stale live compiled API")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location(
        "product_authorization", ROOT / "scripts/generate-product-authorization-contracts.py")
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    try:
        snapshot = next(value for value in generator.build_snapshots(generator.load_source())
                        if value["version"] == 7)
        contracts = build_contracts(json.loads(args.openapi.read_text()), snapshot)
        if args.check:
            validate_projection_bindings(snapshot, contracts)
        content = json.dumps(contracts, ensure_ascii=False, indent=2) + "\n"
        for target in (OUTPUT, RUNTIME_OUTPUT):
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            else:
                require(target.exists() and target.read_text() == content,
                        f"Projection schema artifact drift: {target}")
    except (ContractError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"Approval work projection error: {error}\n")
    print(f"PASS v7 work projection graph: 5 bindings, {len(contracts['schemas'])} schemas, "
          f"{contracts['checksum']}")


if __name__ == "__main__":
    main()
