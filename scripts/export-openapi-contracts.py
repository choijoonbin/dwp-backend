#!/usr/bin/env python3
"""Export stable service contracts and compose the browser-facing Gateway contract."""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
from collections import deque
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = ROOT / "contracts" / "openapi"
GATEWAY_OWNED_SNAPSHOT = CONTRACT_ROOT / "gateway-owned.json"
PRODUCT_AUTHORIZATION_REGISTRY = (
    ROOT / "contracts" / "product-authorization" / "product-surfaces-v1.bundle-v3.json"
)
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
TELEMETRY_PUBLIC_PATH = "/api/platform/v1/observability/product-surface-events"
TELEMETRY_TRUSTED_HEADERS = {"X-DWP-Tenant-ID", "X-DWP-Rollout-Cohort"}
SCOPE_SELECTION_PARAMETER = {
    "name": "contextScopeKey",
    "in": "query",
    "required": False,
    "description": (
        "Opaque management scope returned by the product authority contract. Send exactly "
        "one value when more than one scope is available; omit it only when authority has "
        "one unambiguous scope. Blank, duplicate, malformed, oversized, revoked, or stale "
        "values fail closed. The Gateway consumes this parameter and forwards only its "
        "server-verified X-DWP-Context-Scope-Key evidence."
    ),
    "schema": {
        "type": "string",
        "minLength": 3,
        "maxLength": 200,
        "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]{2,199}$",
    },
}
EXPECTED_DECISION_REVISION_PARAMETER = {
    "name": "X-DWP-Expected-Decision-Revision",
    "in": "header",
    "required": False,
    "description": (
        "Required and fail-closed for product-authorization rollout states 110/111; "
        "optional for backward-compatible baseline/shadow states 000/100."
    ),
    "schema": {
        "type": "string",
        "minLength": 1,
        "maxLength": 200,
    },
    "x-dwp-conditional-required": {
        "enforcement": "FAIL_CLOSED",
        "rolloutStates": ["110", "111"],
    },
}


@dataclass(frozen=True)
class ServiceContract:
    name: str
    port: int
    public_path: Callable[[str], str | None]


def auth_path(path: str) -> str | None:
    if path.startswith("/scim/v2/") or path == "/scim/v2":
        return path
    if path.startswith("/auth/") or path == "/auth":
        return f"/api{path}"
    return None


def prefixed(prefix: str) -> Callable[[str], str | None]:
    def transform(path: str) -> str | None:
        if path.startswith("/internal/") or not (path.startswith("/v1/") or path == "/v1"):
            return None
        return f"{prefix}{path}"

    return transform


SERVICES = (
    ServiceContract("auth", 8001, auth_path),
    ServiceContract("platform", 8002, prefixed("/api/platform")),
    ServiceContract("people", 8003, prefixed("/api/people")),
    ServiceContract("provider", 8004, prefixed("/api/provider")),
    ServiceContract("approval", 8005, prefixed("/api/approvals")),
    ServiceContract("space", 8006, prefixed("/api/spaces")),
    ServiceContract("messaging", 8007, prefixed("/api/messaging")),
    ServiceContract("notification", 8008, prefixed("/api/notifications")),
)


def fetch_contract(service: ServiceContract) -> dict[str, Any]:
    url = os.environ.get(
        f"DWP_OPENAPI_{service.name.upper().replace('-', '_')}_URL",
        f"http://127.0.0.1:{service.port}/v3/api-docs",
    )
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            document = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Unable to read {service.name} OpenAPI document at {url}: {error}") from error
    if not str(document.get("openapi", "")).startswith("3."):
        raise RuntimeError(f"{service.name} did not publish an OpenAPI 3 document")
    if not document.get("paths"):
        raise RuntimeError(f"{service.name} published an empty OpenAPI path registry")
    document.pop("servers", None)
    return document


def load_snapshot(service: ServiceContract) -> dict[str, Any]:
    target = CONTRACT_ROOT / f"{service.name}.json"
    try:
        document = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read approved {service.name} OpenAPI snapshot at {target}: {error}"
        ) from error
    if not str(document.get("openapi", "")).startswith("3.") or not document.get("paths"):
        raise RuntimeError(f"{service.name} approved OpenAPI snapshot is invalid")
    return document


def load_gateway_owned_snapshot() -> dict[str, Any]:
    try:
        document = json.loads(GATEWAY_OWNED_SNAPSHOT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read Gateway-owned OpenAPI snapshot at {GATEWAY_OWNED_SNAPSHOT}: {error}"
        ) from error
    if not str(document.get("openapi", "")).startswith("3.") or not document.get("paths"):
        raise RuntimeError("Gateway-owned OpenAPI snapshot is invalid")
    for path in document["paths"]:
        if not path.startswith("/api/") or path.startswith("/api/internal/"):
            raise RuntimeError(f"Gateway-owned contract contains a non-public path: {path}")
    return document


def component_names(service: str, document: dict[str, Any]) -> dict[tuple[str, str], str]:
    return {
        (category, name): f"{service}_{name}"
        for category, values in document.get("components", {}).items()
        if isinstance(values, dict)
        for name in values
    }


def rewrite_contract_value(value: Any, names: dict[tuple[str, str], str]) -> Any:
    if isinstance(value, list):
        return [rewrite_contract_value(item, names) for item in value]
    if isinstance(value, str) and value.startswith("#/components/"):
        parts = value.split("/")
        replacement = names.get((parts[2], parts[3])) if len(parts) == 4 else None
        return f"#/components/{parts[2]}/{replacement}" if replacement else value
    if not isinstance(value, dict):
        return value

    rewritten: dict[str, Any] = {}
    for key, child in value.items():
        if key == "security" and isinstance(child, list):
            rewritten[key] = [
                {
                    names.get(("securitySchemes", scheme), scheme): scopes
                    for scheme, scopes in requirement.items()
                }
                for requirement in child
            ]
        else:
            rewritten[key] = rewrite_contract_value(child, names)
    return rewritten


def gateway_contract(
        documents: dict[str, dict[str, Any]],
        gateway_owned: dict[str, Any] | None = None) -> dict[str, Any]:
    gateway: dict[str, Any] = {
        "openapi": "3.1.0",
        "info": {
            "title": "DWP Gateway Public API",
            "version": "1.0.0",
            "description": "Browser-facing, same-origin API contract. Internal service paths are excluded.",
        },
        "servers": [{"url": "/", "description": "Same-origin enterprise Gateway"}],
        "paths": {},
        "components": {},
        "tags": [],
    }
    if gateway_owned is not None:
        names = component_names("gateway", gateway_owned)
        for path, path_item in gateway_owned.get("paths", {}).items():
            gateway["paths"][path] = rewrite_contract_value(copy.deepcopy(path_item), names)
        for category, values in gateway_owned.get("components", {}).items():
            if not isinstance(values, dict):
                continue
            target = gateway["components"].setdefault(category, {})
            for name, value in values.items():
                target[names[(category, name)]] = rewrite_contract_value(
                    copy.deepcopy(value), names
                )
        gateway["tags"].append({
            "name": "gateway",
            "description": "Gateway-owned public contracts",
        })
    for service in SERVICES:
        document = documents[service.name]
        names = component_names(service.name, document)
        for path, path_item in document.get("paths", {}).items():
            public_path = service.public_path(path)
            if public_path is None:
                continue
            rewritten = rewrite_contract_value(copy.deepcopy(path_item), names)
            for method, operation in rewritten.items():
                if method in HTTP_METHODS and isinstance(operation, dict):
                    operation["tags"] = [service.name]
                    if operation.get("operationId"):
                        operation["operationId"] = f"{service.name}_{operation['operationId']}"
                    if public_path == TELEMETRY_PUBLIC_PATH:
                        operation["parameters"] = [
                            parameter
                            for parameter in operation.get("parameters", [])
                            if not (
                                isinstance(parameter, dict)
                                and parameter.get("in") == "header"
                                and parameter.get("name") in TELEMETRY_TRUSTED_HEADERS
                            )
                        ]
                        if not operation["parameters"]:
                            operation.pop("parameters", None)
            if public_path in gateway["paths"]:
                raise RuntimeError(f"Gateway contract path collision: {public_path}")
            gateway["paths"][public_path] = rewritten

        for category, values in document.get("components", {}).items():
            if not isinstance(values, dict):
                continue
            target = gateway["components"].setdefault(category, {})
            for name, value in values.items():
                target[names[(category, name)]] = rewrite_contract_value(copy.deepcopy(value), names)
        gateway["tags"].append({"name": service.name, "description": f"{service.name} service"})

    if not gateway["paths"]:
        raise RuntimeError("Gateway public contract contains no routes")
    add_product_governance_contract(gateway)
    return prune_unreachable_components(gateway)


def product_governed_operations() -> dict[tuple[str, str], bool]:
    try:
        registry = json.loads(PRODUCT_AUTHORIZATION_REGISTRY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read product authorization registry at "
            f"{PRODUCT_AUTHORIZATION_REGISTRY}: {error}"
        ) from error
    if (
        registry.get("bundleKey") != "product-surfaces"
        or registry.get("version") != 3
        or registry.get("bundleStatus") != "DRAFT"
        or not isinstance(registry.get("routes"), list)
    ):
        raise RuntimeError("Product authorization v3 registry is invalid")
    operations: dict[tuple[str, str], bool] = {}
    for route in registry["routes"]:
        subject = route.get("subject", {})
        if route.get("lifecycleState") != "ACTIVE" or subject.get("type") != "PRODUCT":
            continue
        state_changing = (
            route.get("routeKind") == "ACTION"
            and route.get("sideEffectFree") is not True
        )
        bindings = route.get("gatewayApiBindings")
        if not isinstance(bindings, list) or not bindings:
            raise RuntimeError(
                f"Active PRODUCT route has no Gateway binding: {route.get('routeContractKey')}"
            )
        for binding in bindings:
            method = str(binding.get("method", "")).lower()
            path = binding.get("path")
            if (
                method not in HTTP_METHODS
                or not isinstance(path, str)
                or not path.startswith("/api/")
            ):
                raise RuntimeError(
                    f"Active PRODUCT route has an invalid Gateway binding: "
                    f"{route.get('routeContractKey')}"
                )
            key = (path, method)
            operations[key] = operations.get(key, False) or state_changing
    if not operations:
        raise RuntimeError("Product authorization v3 registry has no public operations")
    return operations


def add_product_governance_contract(document: dict[str, Any]) -> None:
    injected = 0
    revision_injected = 0
    for (path, method), state_changing in sorted(product_governed_operations().items()):
        operation = document.get("paths", {}).get(path, {}).get(method)
        if not isinstance(operation, dict):
            # The DRAFT registry also contains fail-closed bindings for service
            # routes that are not yet exported as public OpenAPI operations.
            continue
        parameters = operation.setdefault("parameters", [])
        if not isinstance(parameters, list):
            raise RuntimeError(
                f"Gateway operation parameters are invalid: {method.upper()} {path}"
            )
        collisions = [
            parameter
            for parameter in parameters
            if isinstance(parameter, dict)
            and parameter.get("in") == "query"
            and parameter.get("name") == SCOPE_SELECTION_PARAMETER["name"]
        ]
        if collisions:
            raise RuntimeError(
                f"Gateway scope selection parameter collision: {method.upper()} {path}"
            )
        parameters.append(copy.deepcopy(SCOPE_SELECTION_PARAMETER))
        injected += 1
        if state_changing:
            revision_collisions = [
                (index, parameter)
                for index, parameter in enumerate(parameters)
                if isinstance(parameter, dict)
                and str(parameter.get("in", "")).lower() == "header"
                and str(parameter.get("name", "")).lower()
                == EXPECTED_DECISION_REVISION_PARAMETER["name"].lower()
            ]
            if len(revision_collisions) > 1:
                raise RuntimeError(
                    f"Gateway expected-decision revision parameter collision: "
                    f"{method.upper()} {path}"
                )
            canonical_revision = copy.deepcopy(EXPECTED_DECISION_REVISION_PARAMETER)
            if revision_collisions:
                parameters[revision_collisions[0][0]] = canonical_revision
            else:
                parameters.append(canonical_revision)
            revision_injected += 1
    if injected == 0:
        raise RuntimeError("Gateway public contract has no exported governed PRODUCT operation")
    if revision_injected == 0:
        raise RuntimeError(
            "Gateway public contract has no exported state-changing PRODUCT operation"
        )


def prune_unreachable_components(document: dict[str, Any]) -> dict[str, Any]:
    """Keep only components reachable from public operations and their transitive refs."""
    components = document.get("components", {})
    pending: deque[tuple[str, str]] = deque()
    reachable: set[tuple[str, str]] = set()

    def discover(value: Any) -> None:
        if isinstance(value, list):
            for item in value:
                discover(item)
            return
        if not isinstance(value, dict):
            if isinstance(value, str) and value.startswith("#/components/"):
                parts = value.split("/")
                if len(parts) == 4:
                    pending.append((parts[2], parts[3]))
            return
        for key, child in value.items():
            if key == "security" and isinstance(child, list):
                for requirement in child:
                    if isinstance(requirement, dict):
                        for scheme in requirement:
                            pending.append(("securitySchemes", scheme))
            discover(child)

    discover(document.get("paths", {}))
    discover(document.get("security", []))
    while pending:
        category, name = pending.popleft()
        key = (category, name)
        if key in reachable:
            continue
        value = components.get(category, {}).get(name)
        if value is None:
            raise RuntimeError(
                f"Gateway public contract references a missing component: {category}/{name}"
            )
        reachable.add(key)
        discover(value)

    document["components"] = {
        category: {
            name: value
            for name, value in values.items()
            if (category, name) in reachable
        }
        for category, values in components.items()
        if isinstance(values, dict)
        and any(candidate_category == category for candidate_category, _ in reachable)
    }
    return document


def rendered(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="replace checked-in contract snapshots")
    mode.add_argument("--check", action="store_true", help="fail when runtime contracts drift")
    parser.add_argument(
        "--service",
        action="append",
        choices=[service.name for service in SERVICES],
        help=(
            "fetch only this service from runtime and compose the Gateway contract with "
            "approved snapshots for the others; repeat to select multiple services"
        ),
    )
    args = parser.parse_args()

    selected = set(args.service or (service.name for service in SERVICES))
    documents = {
        service.name: fetch_contract(service) if service.name in selected else load_snapshot(service)
        for service in SERVICES
    }
    gateway_owned = load_gateway_owned_snapshot()
    outputs = {f"{name}.json": document for name, document in documents.items()}
    outputs["gateway-owned.json"] = gateway_owned
    outputs["gateway-public.json"] = gateway_contract(documents, gateway_owned)
    CONTRACT_ROOT.mkdir(parents=True, exist_ok=True)

    changed: list[str] = []
    for filename, document in outputs.items():
        target = CONTRACT_ROOT / filename
        content = rendered(document)
        if args.write:
            target.write_text(content, encoding="utf-8")
        elif not target.exists() or target.read_text(encoding="utf-8") != content:
            changed.append(filename)

    if changed:
        print("OpenAPI contract drift detected: " + ", ".join(changed), file=sys.stderr)
        print("Review the API change and run scripts/export-openapi-contracts.py --write.", file=sys.stderr)
        return 1
    action = "wrote" if args.write else "verified"
    print(
        f"OpenAPI contracts {action}: {len(documents)} services, "
        f"{len(outputs['gateway-public.json']['paths'])} public Gateway paths."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
