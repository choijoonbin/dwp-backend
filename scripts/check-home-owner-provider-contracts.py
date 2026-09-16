#!/usr/bin/env python3
"""Fail closed when checked owner Home provider wiring drifts from its internal contract."""

from __future__ import annotations

import json
import pathlib
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/home-runtime/owner-provider-batch.v1.json"


def fail(message: str) -> None:
    print(f"home owner provider contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    document = json.loads(CONTRACT.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1 or document.get("visibility") != "INTERNAL_ONLY":
        fail("schema version or visibility changed")
    contract_source = (ROOT / "dwp-platform-contracts/src/main/java/com/dwp/platform/contract/home/"
                       "HomeWidgetProviderContract.java").read_text(encoding="utf-8")
    for value in document["paths"].values():
        if f'"{value}"' not in contract_source:
            fail(f"shared contract does not declare {value}")
    platform_clients = (ROOT / "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
                        "HomeProviderClientConfiguration.java").read_text(encoding="utf-8")
    platform_router = (ROOT / "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
                       "WidgetRuntimeBroker.java").read_text(encoding="utf-8")
    for owner, entry in document["owners"].items():
        module = ROOT / entry["module"]
        if entry.get("providerKey") != owner:
            fail(f"{owner} provider key drifted")
        for property_key in (entry.get("platformTokenProperty"), entry.get("platformUrlProperty")):
            if not property_key or property_key not in platform_clients:
                fail(f"{owner} platform provider client configuration is missing {property_key}")
        controllers = list((module / "src/main/java").rglob("*HomeWidgetProviderController.java"))
        if len(controllers) != 1:
            fail(f"{owner} must have exactly one Home provider controller")
        source = controllers[0].read_text(encoding="utf-8")
        if "@Hidden" not in source:
            fail(f"{owner} internal controller is exposed to public Springdoc")
        bindings = entry.get("definitionBindings", {})
        if set(bindings) != set(entry["definitions"]):
            fail(f"{owner} definition binding inventory drifted")
        for definition in entry["definitions"]:
            if f'"{definition}"' not in source:
                fail(f"{owner} is missing owned definition {definition}")
            binding = bindings[definition]
            renderer = binding.get("rendererKey", "")
            authorities = binding.get("requiredAuthorities", [])
            states = binding.get("providerStates", [])
            if not renderer.startswith("home.") or not authorities or not states:
                fail(f"{owner} definition {definition} has an incomplete checked binding")
            source_app = binding.get("sourceAppResourceKey", "")
            if not source_app or f'"{source_app}"' not in platform_router:
                fail(f"{owner} definition {definition} is not routed by its source app")
            for authority in authorities:
                resource = authority.rsplit(":", 1)[0]
                if f'"{resource}"' not in source:
                    fail(f"{owner} definition {definition} authority {authority} is not enforced")
        property_key = entry["tokenProperty"]
        if property_key not in source:
            fail(f"{owner} controller is not bound to its dedicated token property")
        application = (module / "src/main/resources/application.yml").read_text(encoding="utf-8")
        yaml_key = property_key.rsplit(".", 1)[-1] + ":"
        if yaml_key not in application:
            fail(f"{owner} application config is missing {property_key}")
        build = (module / "build.gradle").read_text(encoding="utf-8")
        if "project(':dwp-platform-contracts')" not in build:
            fail(f"{owner} does not consume the shared provider contract")
        filters = list((module / "src/main/java").rglob("*SecurityFilter.java"))
        if not any("isHomeProviderPost" in path.read_text(encoding="utf-8") for path in filters):
            fail(f"{owner} broad security filter does not delegate the exact internal path")
    print(f"home owner provider contract: PASS ({len(document['owners'])} owners)")


if __name__ == "__main__":
    main()
