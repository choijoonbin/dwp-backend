#!/usr/bin/env python3
"""Generate the sealed TIM-007 v50 close-workbench projection contract."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v50.json"
OUTPUT = ROOT / "contracts/product-authorization/hcm-time-close-projections-v50.generated.json"
RUNTIME_OUTPUT = (
    ROOT / "dwp-time-server/src/main/resources/product-authorization"
    / OUTPUT.name
)

BINDINGS = {
    "route.hcm.operations.time-command-center.page": {
        "bindingId": "IAQ-039",
        "path": "/v1/hris/workbench/time-command-center",
        "component": "TimeCommandCenterProjectionV1",
        "query": "TimeCommandCenterQueryV1",
        "filter": "TimeCommandCenterFilterV1",
        "filterFields": ["commandReceipt", "effectiveOn", "exceptionSummary", "timePeriod"],
        "requestShapeSha256": "9d9cdcc8167d834c225201a85171688cbc4abab9cffea93cf402e45dc0c16882",
        "responseShapeSha256": "b6ec3f072228de6e2cbd26ef7cd280342d02dae807d6a712ab4227cf65c266d8",
    },
    "route.hcm.operations.time-daily-close.page": {
        "bindingId": "IAQ-043",
        "path": "/v1/hris/workbench/time-daily-close",
        "component": "TimeDailyCloseProjectionV1",
        "query": "TimeDailyCloseQueryV1",
        "filter": "TimeDailyCloseFilterV1",
        "filterFields": ["closeStatus", "effectiveOn", "impactSummary", "timePeriod"],
        "requestShapeSha256": "88e86bfced9b5f766de79d3937a0fa99061d517c4a4a6bc34d31c3a66e8a4e92",
        "responseShapeSha256": "2f630f7e883e9889552f12345d8c9c7c3a758e998fddef5bac58e55daae14a7c",
    },
    "route.hcm.operations.time-monthly-close.page": {
        "bindingId": "IAQ-044",
        "path": "/v1/hris/workbench/time-monthly-close",
        "component": "TimeMonthlyCloseProjectionV1",
        "query": "TimeMonthlyCloseQueryV1",
        "filter": "TimeMonthlyCloseFilterV1",
        "filterFields": ["closeStatus", "effectiveOn", "payrollHandoff", "timePeriod"],
        "requestShapeSha256": "12d54d5ef6749483c1d22eecb110199dedd6a8cac609e73c47af2f0ef4fb652f",
        "responseShapeSha256": "b89467f686abde5193b310ee2f9c4e0c967df2d31534a2bb471311ff63ede492",
    },
}


class ContractError(RuntimeError):
    pass


def require(value: bool, message: str) -> None:
    if not value:
        raise ContractError(message)


def load_json(path: Path) -> dict[str, Any]:
    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key in {path}: {key}")
            result[key] = value
        return result

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    require(isinstance(value, dict), f"JSON object required: {path}")
    return value


def sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def collect_schema_graph(
    components: dict[str, Any], roots: list[str]
) -> dict[str, Any]:
    graph: dict[str, Any] = {}

    def visit_value(value: Any) -> None:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
                visit(ref.removeprefix("#/components/schemas/"))
            for child in value.values():
                visit_value(child)
        elif isinstance(value, list):
            for child in value:
                visit_value(child)

    def visit(name: str) -> None:
        require(name in components and isinstance(components[name], dict),
                f"Unresolved OpenAPI schema: {name}")
        if name in graph:
            return
        graph[name] = copy.deepcopy(components[name])
        visit_value(graph[name])

    for root in roots:
        visit(root)
    return dict(sorted(graph.items()))


def build_contract(openapi: dict[str, Any], bundle: dict[str, Any]) -> dict[str, Any]:
    require(bundle.get("version") == 50 and bundle.get("bundleStatus") == "DRAFT",
            "Exact DRAFT product-authorization v50 is required")
    components = openapi.get("components", {}).get("schemas", {})
    require(isinstance(components, dict), "OpenAPI components.schemas is required")
    routes = {
        route.get("routeContractKey"): route
        for route in bundle.get("routes", [])
        if route.get("routeContractKey") in BINDINGS
    }
    require(set(routes) == set(BINDINGS), "Exact three TIM-007 query routes are required")

    generated_bindings: list[dict[str, Any]] = []
    roots: list[str] = []
    required_query = {"limit", "filter", "sort", "locale", "timeZone", "correlationId"}
    optional_query = {"asOf", "cursor"}
    for route_key, seal in BINDINGS.items():
        route = routes[route_key]
        service_bindings = route.get("servicePepBindings", [])
        profiles = route.get("accessProfiles", [])
        require(
            route.get("routeKind") == "PAGE"
            and route.get("sideEffectFree") is None
            and len(service_bindings) == 1
            and service_bindings[0].get("serviceKey") == "time"
            and service_bindings[0].get("method") == "GET"
            and service_bindings[0].get("path") == seal["path"]
            and len(profiles) == 1,
            f"{route_key}: v50 route topology drift",
        )
        operation = openapi.get("paths", {}).get(seal["path"], {}).get("get")
        require(isinstance(operation, dict), f"{route_key}: OpenAPI operation missing")
        require(
            operation.get("x-dwp-entry-query-id") == seal["bindingId"]
            and operation.get("x-dwp-request-shape-sha256")
            == seal["requestShapeSha256"]
            and operation.get("x-dwp-response-shape-sha256")
            == seal["responseShapeSha256"],
            f"{route_key}: source supplement lineage drift",
        )
        response_schema = (
            operation.get("responses", {}).get("200", {}).get("content", {})
            .get("application/json", {}).get("schema")
        )
        require(
            response_schema == {"$ref": f"#/components/schemas/{seal['component']}"},
            f"{route_key}: response component drift",
        )
        parameters = {
            parameter.get("name"): parameter
            for parameter in operation.get("parameters", [])
            if isinstance(parameter, dict) and parameter.get("in") == "query"
        }
        require(
            set(parameters) == required_query | optional_query
            and all(parameters[name].get("required") is True for name in required_query)
            and all(parameters[name].get("required") is False for name in optional_query)
            and parameters["sort"].get("style") == "form"
            and parameters["sort"].get("explode") is True
            and parameters["filter"].get("style") == "deepObject"
            and parameters["filter"].get("explode") is True,
            f"{route_key}: exact query transport drift",
        )
        filter_schema = components.get(seal["filter"], {})
        require(
            filter_schema.get("additionalProperties") is False
            and set(filter_schema.get("properties", {})) == set(seal["filterFields"]),
            f"{route_key}: filter field drift",
        )
        response_component = components.get(seal["component"])
        require(
            isinstance(response_component, dict)
            and response_component.get("additionalProperties") is False,
            f"{route_key}: response schema must be a closed record",
        )
        openapi_hash = sha256(response_component)
        projection_bindings = profiles[0].get("responseProjectionBindings", [])
        expected_projection = {
            "additionalProperties": False,
            "apiBindingKey": service_bindings[0]["bindingKey"],
            "openApiSchemaSha256": openapi_hash,
            "projectionPolicyKey": f"{route_key}.{profiles[0]['profileKey']}.projection.v1",
            "responseSchemaKey": seal["component"],
            "schemaVersion": 1,
        }
        require(projection_bindings == [expected_projection],
                f"{route_key}: v50 projection metadata drift")
        roots.extend([seal["component"], seal["query"], seal["filter"]])
        generated_bindings.append({
            "routeContractKey": route_key,
            "profileKey": profiles[0]["profileKey"],
            "method": "GET",
            "servicePath": seal["path"],
            "querySchemaKey": seal["query"],
            "filterSchemaKey": seal["filter"],
            "sourceBindingId": seal["bindingId"],
            "sourceRequestShapeSha256": seal["requestShapeSha256"],
            "sourceResponseShapeSha256": seal["responseShapeSha256"],
            **expected_projection,
        })

    graph = collect_schema_graph(components, roots)
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "projectionKey": "hcm-time-close-projections-v50",
        "registryRef": {
            "bundleKey": bundle["bundleKey"],
            "version": 50,
            "sha256": bundle["checksum"],
        },
        "bindingCount": 3,
        "bindings": generated_bindings,
        "schemas": graph,
        "schemaClosureSha256": sha256(graph),
        "checksumAlgorithm": "SHA-256",
    }
    result["checksum"] = sha256(result)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True)
    args = parser.parse_args()
    try:
        contract = build_contract(load_json(args.openapi), load_json(BUNDLE))
        content = json.dumps(contract, ensure_ascii=False, indent=2) + "\n"
        for target in (OUTPUT, RUNTIME_OUTPUT):
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
            else:
                require(target.exists() and target.read_text(encoding="utf-8") == content,
                        f"Projection artifact drift: {target}")
    except (ContractError, OSError, ValueError, KeyError) as error:
        parser.exit(1, f"TIM-007 projection contract error: {error}\n")
    print(
        "PASS TIM-007 v50 projections: "
        f"3 bindings, {len(contract['schemas'])} schemas, {contract['checksum']}"
    )


if __name__ == "__main__":
    main()
