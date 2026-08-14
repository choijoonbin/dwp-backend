#!/usr/bin/env python3
"""Export stable service contracts and compose the browser-facing Gateway contract."""

from __future__ import annotations

import argparse
import copy
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = ROOT / "contracts" / "openapi"
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


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
)


def fetch_contract(service: ServiceContract) -> dict[str, Any]:
    url = f"http://127.0.0.1:{service.port}/v3/api-docs"
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
    if not isinstance(value, dict):
        return value

    rewritten: dict[str, Any] = {}
    for key, child in value.items():
        if key == "$ref" and isinstance(child, str) and child.startswith("#/components/"):
            parts = child.split("/")
            replacement = names.get((parts[2], parts[3])) if len(parts) == 4 else None
            rewritten[key] = f"#/components/{parts[2]}/{replacement}" if replacement else child
        elif key == "security" and isinstance(child, list):
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


def gateway_contract(documents: dict[str, dict[str, Any]]) -> dict[str, Any]:
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
    return gateway


def rendered(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="replace checked-in contract snapshots")
    mode.add_argument("--check", action="store_true", help="fail when runtime contracts drift")
    args = parser.parse_args()

    documents = {service.name: fetch_contract(service) for service in SERVICES}
    outputs = {f"{name}.json": document for name, document in documents.items()}
    outputs["gateway-public.json"] = gateway_contract(documents)
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
