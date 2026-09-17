#!/usr/bin/env python3
"""Fail closed when checked owner Home provider wiring drifts from its internal contract."""

from __future__ import annotations

import hashlib
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
    expected_service_owners = [
        "approval", "meeting", "notification", "space", "messaging", "people",
    ]
    profiles = document.get("authProfiles")
    if not isinstance(profiles, dict) or profiles.get("service-token-v1") != {
        "owners": expected_service_owners,
        "requiredHeaders": [
            "X-DWP-Service-Identity", "X-DWP-Service-Token",
            "X-DWP-Tenant-ID", "X-DWP-User-ID",
            "X-DWP-Current-Decision-Revision", "X-DWP-Current-Revalidate-At",
            "X-DWP-Home-Deadline-At",
        ],
    } or profiles.get("dwp1-hmac-sha256") != {
        "owners": ["dwaion"],
        "requiredHeaders": [
            "X-DWP-Home-Assertion", "X-DWP-Tenant-ID", "X-DWP-User-ID",
            "X-DWP-Identity-Plane", "X-DWP-Permissions", "X-DWP-Roles",
            "X-DWP-Group-Refs", "X-DWP-Current-Decision-Revision",
            "X-DWP-Current-Revalidate-At", "X-DWP-Home-Deadline-At",
        ],
        "forbiddenHeaders": [
            "Authorization", "Cookie", "X-DWP-Service-Identity",
            "X-DWP-Service-Token", "X-DWP-Support-Session-ID",
            "X-DWP-Provider-Tenant-ID", "X-DWP-Actor-Tenant-ID",
        ],
    }:
        fail("owner authentication profile partition drifted")
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
        if entry.get("providerKey") != owner:
            fail(f"{owner} provider key drifted")
        if entry.get("externalOwner"):
            check_external_signed_owner(owner, entry, platform_clients, platform_router)
            continue
        module = ROOT / entry["module"]
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


def check_external_signed_owner(
        owner: str,
        entry: dict[str, object],
        platform_clients: str,
        platform_router: str) -> None:
    if entry.get("module") != "external:dwp_agent" or entry.get("authProfile") != "dwp1-hmac-sha256":
        fail(f"{owner} external owner profile drifted")
    for property_key in (
        entry.get("platformUrlProperty"),
        entry.get("platformSigningSecretProperty"),
        entry.get("platformKeyIdProperty"),
    ):
        if not isinstance(property_key, str) or property_key not in platform_clients:
            fail(f"{owner} platform signed provider configuration is missing {property_key}")
    signed = entry.get("signedWorkload")
    document_path = "/internal/home/v1/widget-data:batch"
    expected_claims = [
        "v", "kid", "iss", "aud", "sub", "tid", "pid", "cid",
        "traceparent", "tracestate", "ip", "htm", "htu", "permissions",
        "roles", "groups", "authorityRevision", "authorityRevalidateAt",
        "deadlineAt", "bodySha256", "iat", "nbf", "exp", "jti",
    ]
    fixture_path = "contracts/home-runtime/dwaion-signed-workload.v1.json"
    fixture_assertion_sha = \
        "ac7ff7568c38f0110d8807ba359400fb3a380589ea0c239607e871e724cf3909"
    expected_signed = {
        "profile": "dwp1-hmac-sha256",
        "method": "POST",
        "path": document_path,
        "header": "X-DWP-Home-Assertion",
        "issuer": "dwp-platform-server",
        "audience": "dwp-agent-home",
        "ttlSeconds": 30,
        "bodySha256": True,
        "singleUseJti": True,
        "recipientBound": True,
        "claims": expected_claims,
        "fixturePath": fixture_path,
        "fixtureAssertionSha256": fixture_assertion_sha,
    }
    if signed != expected_signed:
        fail(f"{owner} signed workload contract drifted from {document_path}")
    fixture_bytes = (ROOT / fixture_path).read_bytes()
    fixture = json.loads(fixture_bytes)
    if list(fixture.get("claims", {})) != expected_claims \
            or fixture.get("path") != document_path \
            or fixture.get("profile") != signed["profile"] \
            or fixture.get("keyId") != "platform-dwaion-home-v1" \
            or hashlib.sha256(fixture.get("assertion", "").encode("ascii")).hexdigest() \
            != fixture_assertion_sha \
            or hashlib.sha256(fixture.get("requestBodyUtf8", "").encode("utf-8")).hexdigest() \
            != fixture.get("requestBodySha256"):
        fail(f"{owner} shared signed workload fixture drifted")
    expected_evidence = {
        "security": {
            "ref": f"contract:{fixture_path}",
            "sha256": hashlib.sha256(fixture_bytes).hexdigest(),
        },
        "privacy": {
            "ref": "git:7092a93ea7446d08af6e45bb88e7225dd9ed4148:"
                   "tests/test_artifact_home_projection.py",
            "sha256": "2b61d64f15ccbb14e680289c851221e4a2899517466178bfe061d4e0164d2fb3",
        },
    }
    if entry.get("ownerEvidenceCommit") != "7092a93ea7446d08af6e45bb88e7225dd9ed4148" \
            or entry.get("evidence") != expected_evidence:
        fail(f"{owner} pinned owner evidence drifted")
    bindings = entry.get("definitionBindings")
    definitions = entry.get("definitions")
    if not isinstance(bindings, dict) or not isinstance(definitions, list) \
            or set(bindings) != set(definitions):
        fail(f"{owner} definition binding inventory drifted")
    protocol = (ROOT / "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
                "DwaionHomeWorkloadProtocol.java").read_text(encoding="utf-8")
    client = (ROOT / "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
              "DwaionHomeWidgetProviderClient.java").read_text(encoding="utf-8")
    signer = (ROOT / "dwp-platform-server/src/main/java/com/dwp/services/platform/home/runtime/"
              "DwaionHomeWorkloadAssertionSigner.java").read_text(encoding="utf-8")
    for marker in (signed["path"], signed["header"], signed["issuer"], signed["audience"]):
        if f'"{marker}"' not in protocol:
            fail(f"{owner} protocol is missing {marker}")
    for claim in expected_claims:
        if f'claims.put("{claim}"' not in signer:
            fail(f"{owner} signer is missing declared claim {claim}")
    for forbidden in ("X-DWP-Service-Token", "X-DWP-Service-Identity", "Authorization"):
        if forbidden in client or forbidden in signer:
            fail(f"{owner} signed client contains forbidden credential marker {forbidden}")
    migrations = "\n".join(path.read_text(encoding="utf-8") for path in sorted(
        (ROOT / "dwp-platform-server/src/main/resources/db/migration").glob("V*.sql")
    ))
    for definition in definitions:
        binding = bindings[definition]
        if not isinstance(binding, dict):
            fail(f"{owner} definition {definition} binding is invalid")
        required = binding.get("requiredAuthorities")
        if not isinstance(required, list) or set(required) != {
                "APP.ASK:VIEW", "APP.DWAION_ARTIFACTS:VIEW"}:
            fail(f"{owner} definition {definition} authorities drifted")
        source = binding.get("sourceAppResourceKey")
        if not isinstance(source, str) or f'"{source}"' not in platform_router:
            fail(f"{owner} definition {definition} is not routed by its source app")
        for marker in (
            definition,
            binding.get("definitionVersion"),
            binding.get("manifestHash"),
            *required,
        ):
            if not isinstance(marker, str) or marker not in (protocol + client + migrations):
                fail(f"{owner} definition {definition} is missing canonical marker {marker}")
        if binding.get("rendererBindingRevisionPolicy") != "CURRENT_ACTIVE_CATALOG_SHA256":
            fail(f"{owner} definition {definition} binding revision policy drifted")
        if fixture.get("definition") != {
                "key": definition,
                "version": binding.get("definitionVersion"),
                "manifestHash": binding.get("manifestHash"),
                "rendererBindingRevisionPolicy": "CURRENT_ACTIVE_CATALOG_SHA256",
                "fixtureRendererBindingRevision": "9" * 64,
        }:
            fail(f"{owner} definition {definition} fixture binding drifted")
        if 'rendererBindingRevision().matches("[0-9a-f]{64}")' not in client.replace("\n", "") \
                .replace(" ", ""):
            fail(f"{owner} definition {definition} does not validate the current catalog revision")


if __name__ == "__main__":
    main()
