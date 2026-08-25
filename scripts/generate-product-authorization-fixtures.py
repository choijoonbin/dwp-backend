#!/usr/bin/env python3
"""Validate and deterministically render CORE-006 authorization fixtures.

The checked-in ``*.yaml`` inputs intentionally use JSON syntax. JSON is a YAML 1.2
subset, which keeps the build independent from an ambient PyYAML installation and
makes the canonical bytes reproducible across developer and CI environments.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import copy
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = ROOT / "contracts" / "product-authorization"
FIXTURE_SOURCE = CONTRACT_ROOT / "pilot-fixtures.v1.yaml"
FIXTURE_OUTPUT = CONTRACT_ROOT / "pilot-fixtures.v1.generated.json"
OVERRIDE_SOURCE = CONTRACT_ROOT / "pilot-test-registry-overrides.v1.yaml"
OVERRIDE_OUTPUT = CONTRACT_ROOT / "pilot-test-registry-overrides.v1.generated.json"
REGISTRY_INDEX = CONTRACT_ROOT / "product-surfaces-v1.index.json"
REGISTRY_LATEST_ALIAS = CONTRACT_ROOT / "product-surfaces-v1.json"
AUTH_SEED_ROOT = (
    ROOT / "dwp-auth-server" / "src" / "main" / "resources"
    / "product-authorization"
)
AUTH_SEED_INDEX = AUTH_SEED_ROOT / "product-surfaces-v1.index.generated.json"
AUTH_SEED_LATEST_ALIAS = AUTH_SEED_ROOT / "product-surfaces-v1.generated.json"

EXPECTED_TEST_COUNTS = {"GUARD": 17, "CANARY": 12, "APPROVALS": 18, "HCM": 24}
EXPECTED_NEGATIVE_COUNT = 46
EXPECTED_GENERATOR_NEGATIVE_CONTROLS = 41
EXPECTED_CONTEXT_IDS = {
    "FX-C-MULTI-WINDOW",
    "FX-C-SUPPORT-EXCLUSIVE",
    "FX-C-SOURCE-REVISION",
}
ALLOWED_OVERRIDE_REFS = {
    "PS-G006": "test.management-and-app.v1",
    "PS-G010": "test.services-catalog-jit.v1",
}
RESERVED_CONTRACTS = {"hcm.reference.publish", "hcm.integration.rotate-secret"}
REGISTRY_VERSIONS = (1, 2, 3)
FIXED_GROUP_VERSIONS = {"CANARY": 1, "APPROVALS": 2, "HCM": 3}
DESCRIPTOR_SECTIONS = {
    "capabilityContractKeys": ("capabilities", "contractKey"),
    "accessPolicyKeys": ("accessPolicies", "accessPolicyKey"),
    "entitlementExpressionKeys": ("entitlementExpressions", "expressionKey"),
    "predicatePolicyKeys": ("predicatePolicies", "predicatePolicyKey"),
    "routeContractKeys": ("routes", "routeContractKey"),
}

STEP_UP_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs0T79NDWWfUnO4qfn3rq
BD7AbkQ8gLIfNNbPIbGhtcYBi+BzEwzmD+znR8zq9URVZgWNgvRLgJC1S6vVSn1I
APkzPCtyia79hePPwswXo8Zc5P/pQ8y9M88+vfBEM0SBqCRCqXjRNO6o4vH7kQFJ
rfcsYlzQtX6BWtIuiWVmmINN3FQ4Az7tnO79YwmyYwX6QHUt/p0x0NcpQgJ6qH8I
0CWp6FAIUsTIjvSzGX2lRdtACSrKesmJYeMpgz3lDEfSB/pA1gHTLsFc6v6Vt+/Z
5WLMQNF1jFBXR/HmmCWiwoQ1hngNnGmX68mUy1Qg2j+e7HSujuyjIPNQ91BQKhif
0QIDAQAB
-----END PUBLIC KEY-----"""
STEP_UP_RSA_MODULUS = int(
    "b344fbf4d0d659f5273b8a9f9f7aea043ec06e443c80b21f34d6cf21b1a1b5c6"
    "018be073130ce60fece747cceaf5445566058d82f44b8090b54babd54a7d4800f9"
    "333c2b7289aefd85e3cfc2cc17a3c65ce4ffe943ccbd33cf3ebdf044334481a824"
    "42a978d134eea8e2f1fb910149adf72c625cd0b57e815ad22e89656698834ddc54"
    "38033eed9ceefd6309b26305fa40752dfe9d31d0d72942027aa87f08d025a9e850"
    "0852c4c88ef4b3197da545db40092aca7ac98961e329833de50c47d207fa40d601"
    "d32ec15ceafe95b7efd9e562cc40d1758c505747f1e69825a2c2843586780d9c69"
    "97ebc994cb5420da3f9eec74ae8eeca320f350f750502a189fd1",
    16,
)
STEP_UP_RSA_PRIVATE_EXPONENT = int(
    "08fcf81480dd846e611890455b4fc478b0c4416b10ad03b1429b1410cc890ef5"
    "f5e9235865afbf7fb4292eed8868c273b52d295893e2ceb802acfff92e564c7447"
    "0a51759cb52845cc0f1d61370e26df18d47efbbd3360579f08166a1be345a7aa0c"
    "625ec0a0dda26b2b90c62411b055e5770f59f7b967d5b8bde1c93001363b869681"
    "045faa9e25e24c50401da1005eeba2622276e13a5086057f145ce8542ba0cc8a6b"
    "5d2ad5c99b377c30ada4dc761fe635572f25d0f86738e99b1ed69cffff41411c99"
    "1c38ec4a01edc79609eb8432bdf9b9d4aa7b392e4f6ce89ffb5a4b8b9131c57ede"
    "981cd337f4e6ccb2236779482ae53e52061bb8df7f1a7c45d079",
    16,
)
STEP_UP_SOURCE_FIELDS = {
    "key", "challengeId", "policy", "capabilityContractKey", "decisionRevision",
    "scopeRef", "targetType", "targetId", "targetVersion", "method", "path",
    "actorUserId", "tenantId", "idempotencyKey", "payload", "issuedAt",
    "authenticatedAt", "expiresAt", "nonce", "state",
}
STEP_UP_CONTEXT_KEYS = {
    "STEPUP_HIGH_WORKFLOW_PUBLISH_1": "approval-management",
    "STEPUP_HIGH_FORM_PUBLISH_1": "approval-management",
    "STEPUP_HIGH_POLICY_PUBLISH_1": "approval-management",
    "STEPUP_HIGH_RECOVERY_1": "approval-management",
    "STEPUP_HIGH_ORG_PUBLISH_1": "hcm-management",
    "STEPUP_HIGH_INTEGRATION_EXECUTE_1": "hcm-management",
    "STEPUP_CRITICAL_FRESH_1": "hcm-management",
    "STEPUP_CRITICAL_EXPORT_RETRY_1": "hcm-management",
    "STEPUP_CRITICAL_CONSUMED_1": "hcm-management",
}
STEP_UP_CLAIM_FIELDS = {
    "iss", "sub", "aud", "jti", "nonce", "iat", "nbf", "exp", "auth_time",
    "acr", "amr", "tenant_id", "owner_service_key", "command_contract_key",
    "activation_policy", "capability_contract_key", "context_key", "scope_ref",
    "target_type", "target_id", "target_version", "command_method", "command_path",
    "idempotency_key", "payload_sha256", "command_sha256", "decision_revision",
}


class ContractError(RuntimeError):
    """Raised when a canonical fixture invariant is violated."""


def load_json_yaml(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"Unable to read JSON-compatible YAML {path}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain an object")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )


def sha256(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def epoch(value: Any, field: str) -> int:
    if not isinstance(value, str) or re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value
    ) is None:
        raise ContractError(
            f"Step-up challenge {field} must be a whole-second RFC3339 UTC instant"
        )
    try:
        instant = datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as error:
        raise ContractError(f"Step-up challenge {field} is invalid") from error
    if instant.tzinfo != timezone.utc:
        raise ContractError(f"Step-up challenge {field} must use UTC")
    return int(instant.timestamp())


def rs256(header: dict[str, Any], claims: dict[str, Any]) -> str:
    encoded_header = base64url(canonical_bytes(header))
    encoded_claims = base64url(canonical_bytes(claims))
    signing_input = f"{encoded_header}.{encoded_claims}".encode("ascii")
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") \
        + hashlib.sha256(signing_input).digest()
    key_bytes = (STEP_UP_RSA_MODULUS.bit_length() + 7) // 8
    padding = b"\xff" * (key_bytes - len(digest_info) - 3)
    encoded_message = b"\x00\x01" + padding + b"\x00" + digest_info
    signature = pow(
        int.from_bytes(encoded_message, "big"),
        STEP_UP_RSA_PRIVATE_EXPONENT,
        STEP_UP_RSA_MODULUS,
    ).to_bytes(key_bytes, "big")
    return f"{encoded_header}.{encoded_claims}.{base64url(signature)}"


def decode_base64url(value: str, label: str) -> bytes:
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9_-]+", value) is None:
        raise ContractError(f"{label} is not canonical base64url")
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, binascii.Error) as error:
        raise ContractError(f"{label} is invalid base64url") from error


def decode_and_verify_rs256(token: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(token, str) or token.count(".") != 2:
        raise ContractError("Step-up compact token must have exactly three segments")
    encoded_header, encoded_claims, encoded_signature = token.split(".")
    try:
        header = json.loads(decode_base64url(encoded_header, "JWT header"))
        claims = json.loads(decode_base64url(encoded_claims, "JWT claims"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ContractError("Step-up compact token JSON is invalid") from error
    if not isinstance(header, dict) or not isinstance(claims, dict):
        raise ContractError("Step-up compact token header and claims must be objects")
    signature = decode_base64url(encoded_signature, "JWT signature")
    key_bytes = (STEP_UP_RSA_MODULUS.bit_length() + 7) // 8
    if len(signature) != key_bytes:
        raise ContractError("Step-up compact token signature size is invalid")
    signing_input = f"{encoded_header}.{encoded_claims}".encode("ascii")
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") \
        + hashlib.sha256(signing_input).digest()
    padding = b"\xff" * (key_bytes - len(digest_info) - 3)
    expected = b"\x00\x01" + padding + b"\x00" + digest_info
    actual = pow(
        int.from_bytes(signature, "big"), 65537, STEP_UP_RSA_MODULUS
    ).to_bytes(key_bytes, "big")
    if actual != expected:
        raise ContractError("Step-up compact token RS256 signature is invalid")
    return header, claims


def signed_step_up_challenges(
    source: dict[str, Any],
    catalog_keys: set[str],
    required_versions: dict[str, int],
    registries: dict[int, dict[str, Any]],
) -> list[dict[str, Any]]:
    verification = source.get("stepUpVerification")
    expected_verification = {
        "algorithm": "RS256",
        "keyId": "fixture-approval-step-up-rs256-v1",
        "issuer": "https://auth.fixture.dwp.test",
        "audienceByOwnerService": {
            "approval": "dwp-approval-server",
            "people": "dwp-people-server",
        },
        "requiredAcr": "urn:dwp:acr:mfa",
        "publicKeyPem": STEP_UP_PUBLIC_KEY_PEM,
    }
    if verification != expected_verification:
        raise ContractError("stepUpVerification must match the immutable fixture RS256 key contract")
    fixed_clock = epoch(source.get("fixedClock"), "fixedClock")
    challenges = source.get("catalogs", {}).get("stepUpChallenges")
    if not isinstance(challenges, list) or not challenges:
        raise ContractError("catalogs.stepUpChallenges must be a non-empty array")
    if {challenge.get("key") for challenge in challenges if isinstance(challenge, dict)} \
            != set(STEP_UP_CONTEXT_KEYS):
        raise ContractError("Step-up challenge keys must be the closed nine-challenge set")
    rendered_challenges: list[dict[str, Any]] = []
    for challenge in challenges:
        if not isinstance(challenge, dict) or set(challenge) != STEP_UP_SOURCE_FIELDS:
            actual = sorted(challenge) if isinstance(challenge, dict) else []
            raise ContractError(
                "Step-up challenge source fields must be exact: "
                f"expected {sorted(STEP_UP_SOURCE_FIELDS)}, found {actual}"
            )
        key = challenge["key"]
        required_version = required_versions.get(key)
        if required_version not in registries:
            raise ContractError(
                f"Step-up challenge {key} has no exact registry gate"
            )
        authority = resolve_step_up_registry_binding(
            challenge, registries[required_version]
        )
        if challenge["scopeRef"] not in catalog_keys:
            raise ContractError(f"Step-up challenge {key} references an unknown scope")
        if challenge["state"] not in {"ACTIVE", "CONSUMED"}:
            raise ContractError(f"Step-up challenge {key} state is invalid")
        if challenge["method"] not in {"POST", "PUT", "PATCH", "DELETE"} \
                or not str(challenge["path"]).startswith("/api/"):
            raise ContractError(f"Step-up challenge {key} command is invalid")
        if not all(isinstance(challenge[field], int) and challenge[field] > 0
                   for field in ("actorUserId", "tenantId")):
            raise ContractError(f"Step-up challenge {key} actor and tenant must be positive")
        if not isinstance(challenge["targetVersion"], int) or challenge["targetVersion"] < 0:
            raise ContractError(f"Step-up challenge {key} targetVersion is invalid")
        if not isinstance(challenge["payload"], dict):
            raise ContractError(f"Step-up challenge {key} payload must be an object")
        issued_at = epoch(challenge["issuedAt"], "issuedAt")
        authenticated_at = epoch(challenge["authenticatedAt"], "authenticatedAt")
        expires_at = epoch(challenge["expiresAt"], "expiresAt")
        if not authenticated_at <= issued_at <= fixed_clock < expires_at \
                or expires_at - issued_at > 900 \
                or fixed_clock - authenticated_at > 600:
            raise ContractError(f"Step-up challenge {key} assurance window is invalid")
        metadata, claims = step_up_material(challenge, authority, verification)
        rendered_challenge = {
            **challenge,
            **metadata,
            "requiredRegistryRef": registry_reference(
                registries[required_version]
            ),
            "compactToken": rs256(
                {"alg": "RS256", "kid": verification["keyId"], "typ": "JWT"},
                claims,
            ),
        }
        validate_rendered_step_up_challenge(
            rendered_challenge, challenge, authority, verification,
            registry_reference(registries[required_version]),
        )
        rendered_challenges.append(rendered_challenge)
    return rendered_challenges


def step_up_material(
    challenge: dict[str, Any],
    authority: dict[str, Any],
    verification: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    key = str(challenge["key"])
    context_key = STEP_UP_CONTEXT_KEYS.get(key)
    if not isinstance(context_key, str) or not context_key.strip() \
            or "\n" in context_key or "\r" in context_key:
        raise ContractError(f"Step-up challenge {key} context binding is invalid")
    step_up = authority["stepUp"]
    route = authority["route"]
    owner_service_key = step_up["ownerServiceKey"]
    audience = step_up["audience"]
    audiences = verification["audienceByOwnerService"]
    if audiences.get(owner_service_key) != audience:
        raise ContractError(f"Step-up challenge {key} owner audience projection drift")
    validate_step_up_binding_truth(challenge, authority)
    payload_digest = sha256(challenge["payload"])
    command_digest = hashlib.sha256("\n".join((
        route["routeContractKey"], owner_service_key, audience,
        challenge["method"], challenge["path"], context_key,
        challenge["scopeRef"], challenge["targetType"], challenge["targetId"],
        str(challenge["targetVersion"]), challenge["idempotencyKey"],
        payload_digest, challenge["decisionRevision"],
    )).encode("utf-8")).hexdigest()
    metadata = {
        "ownerServiceKey": owner_service_key,
        "commandContractKey": route["routeContractKey"],
        "stepUpCommandBindingKey": step_up["bindingKey"],
        "contextKey": context_key,
        "algorithm": verification["algorithm"],
        "keyId": verification["keyId"],
        "issuer": verification["issuer"],
        "audience": audience,
        "acr": verification["requiredAcr"],
        "amr": ["mfa"],
        "expectedObjectVersionSource": step_up["expectedObjectVersionSource"],
        "expectedObjectVersionName": step_up["expectedObjectVersionName"],
        "payloadSha256": payload_digest,
        "commandSha256": command_digest,
    }
    if "targetIdPathParameter" in step_up:
        metadata["targetIdSource"] = "PATH_PARAMETER"
        metadata["targetIdPathParameter"] = step_up["targetIdPathParameter"]
    else:
        metadata["targetIdSource"] = "COMMAND_BODY"
        metadata["targetIdBodyFields"] = step_up["targetIdBodyFields"]
    claims = {
        "iss": verification["issuer"],
        "aud": audience,
        "sub": str(challenge["actorUserId"]),
        "tenant_id": challenge["tenantId"],
        "owner_service_key": owner_service_key,
        "command_contract_key": route["routeContractKey"],
        "iat": epoch(challenge["issuedAt"], "issuedAt"),
        "nbf": epoch(challenge["issuedAt"], "issuedAt"),
        "auth_time": epoch(challenge["authenticatedAt"], "authenticatedAt"),
        "exp": epoch(challenge["expiresAt"], "expiresAt"),
        "acr": verification["requiredAcr"],
        "amr": ["mfa"],
        "jti": challenge["challengeId"],
        "nonce": challenge["nonce"],
        "activation_policy": challenge["policy"],
        "capability_contract_key": challenge["capabilityContractKey"],
        "context_key": context_key,
        "scope_ref": challenge["scopeRef"],
        "target_type": challenge["targetType"],
        "target_id": challenge["targetId"],
        "target_version": challenge["targetVersion"],
        "command_method": challenge["method"],
        "command_path": challenge["path"],
        "idempotency_key": challenge["idempotencyKey"],
        "payload_sha256": payload_digest,
        "command_sha256": command_digest,
        "decision_revision": challenge["decisionRevision"],
    }
    return metadata, claims


def validate_rendered_step_up_challenge(
    rendered_challenge: dict[str, Any],
    source_challenge: dict[str, Any],
    authority: dict[str, Any],
    verification: dict[str, Any],
    expected_registry_ref: dict[str, Any],
) -> None:
    metadata, expected_claims = step_up_material(
        source_challenge, authority, verification
    )
    expected_fields = (
        STEP_UP_SOURCE_FIELDS | set(metadata)
        | {"requiredRegistryRef", "compactToken"}
    )
    if set(rendered_challenge) != expected_fields:
        raise ContractError(
            f"Step-up challenge {source_challenge['key']} rendered fields drift"
        )
    for field in STEP_UP_SOURCE_FIELDS:
        if rendered_challenge.get(field) != source_challenge.get(field):
            raise ContractError(
                f"Step-up challenge {source_challenge['key']} source field {field} drift"
            )
    if any(rendered_challenge.get(field) != value for field, value in metadata.items()):
        raise ContractError(
            f"Step-up challenge {source_challenge['key']} binding projection drift"
        )
    validate_registry_reference(
        rendered_challenge.get("requiredRegistryRef"), expected_registry_ref,
        f"Step-up challenge {source_challenge['key']}",
    )
    header, claims = decode_and_verify_rs256(rendered_challenge.get("compactToken"))
    expected_header = {
        "alg": "RS256", "kid": verification["keyId"], "typ": "JWT",
    }
    if header != expected_header or set(claims) != STEP_UP_CLAIM_FIELDS \
            or claims != expected_claims:
        raise ContractError(
            f"Step-up challenge {source_challenge['key']} signed claims drift"
        )
    if rendered_challenge["compactToken"] != rs256(expected_header, expected_claims):
        raise ContractError(
            f"Step-up challenge {source_challenge['key']} compact token bytes drift"
        )


def registry_checksum(registry: dict[str, Any]) -> str:
    payload = copy.deepcopy(registry)
    payload.pop("checksum", None)
    payload.pop("bundleStatus", None)
    return sha256(payload)


def validate_registry_index(index: dict[str, Any]) -> None:
    if set(index) != {
        "schemaVersion", "bundleKey", "latestVersion", "latestChecksum",
        "latestArtifact", "latestAuthSeedArtifact", "versions",
        "indexChecksumAlgorithm", "indexChecksum",
    } or index.get("schemaVersion") != 1 or index.get("bundleKey") != "product-surfaces":
        raise ContractError("Product authorization registry index envelope drift")
    checksum_payload = dict(index)
    actual_index_checksum = checksum_payload.pop("indexChecksum", None)
    if index.get("indexChecksumAlgorithm") != "SHA-256" or actual_index_checksum != sha256(
        checksum_payload
    ):
        raise ContractError("Product authorization registry index checksum mismatch")
    if any("active" in str(key).lower() for key in index):
        raise ContractError("Product authorization registry index must not contain an active pointer")
    versions = index.get("versions")
    if not isinstance(versions, list) or [
        entry.get("version") for entry in versions if isinstance(entry, dict)
    ] != list(REGISTRY_VERSIONS):
        raise ContractError("Registry lineage must contain exactly versions 1, 2, and 3")


def validate_registry_entry(
    index: dict[str, Any],
    entry: dict[str, Any],
    registry: dict[str, Any],
    contract_bytes: bytes,
    auth_seed_bytes: bytes,
) -> None:
    version = entry["version"]
    expected_counts = {
        section: len(registry.get(section, []))
        for section, _ in DESCRIPTOR_SECTIONS.values()
    }
    if set(entry) != {
        "version", "bundleStatus", "checksum", "artifact", "authSeedArtifact", "counts"
    } or entry.get("counts") != expected_counts:
        raise ContractError(f"Registry v{version} index descriptor counts drift")
    if (
        registry.get("bundleKey") != index.get("bundleKey")
        or registry.get("version") != version
        or registry.get("bundleStatus") != "DRAFT"
        or registry.get("checksumAlgorithm") != "SHA-256"
        or registry.get("checksum") != entry.get("checksum")
        or registry_checksum(registry) != entry.get("checksum")
    ):
        raise ContractError(f"Registry v{version} content/checksum does not match its index")
    if contract_bytes != auth_seed_bytes:
        raise ContractError(f"Registry v{version} contract/Auth seed bytes differ")


def validate_monotonic_registry_lineage(
    registries: dict[int, dict[str, Any]]
) -> None:
    def monotonic_contains(current: Any, inherited: Any) -> bool:
        if isinstance(inherited, dict):
            return isinstance(current, dict) and all(
                key in current and monotonic_contains(current[key], value)
                for key, value in inherited.items()
            )
        if isinstance(inherited, list):
            return isinstance(current, list) and len(current) >= len(inherited) \
                and all(monotonic_contains(current[index], value)
                        for index, value in enumerate(inherited))
        return current == inherited

    keyed_sections = list(DESCRIPTOR_SECTIONS.values()) + [
        ("authorityEndpoints", "endpointKey")
    ]
    previous: dict[tuple[str, str], dict[str, Any]] = {}
    for version in REGISTRY_VERSIONS:
        current: dict[tuple[str, str], dict[str, Any]] = {}
        for section, key_field in keyed_sections:
            for record in registries[version].get(section, []):
                if not isinstance(record, dict) or not isinstance(record.get(key_field), str):
                    raise ContractError(f"Registry v{version} has an invalid {section} descriptor")
                identity = (section, record[key_field])
                if identity in current:
                    raise ContractError(f"Registry v{version} duplicates {identity}")
                current[identity] = record
        if any(
            identity not in current
            or not monotonic_contains(current[identity], record)
            for identity, record in previous.items()
        ):
            raise ContractError(f"Registry v{version} is not an exact monotonic superset")
        previous = current


def load_registry_lineage() -> tuple[dict[str, Any], dict[int, dict[str, Any]]]:
    index = load_json_yaml(REGISTRY_INDEX)
    validate_registry_index(index)
    try:
        if REGISTRY_INDEX.read_bytes() != AUTH_SEED_INDEX.read_bytes():
            raise ContractError("Registry contract/Auth seed index bytes differ")
    except OSError as error:
        raise ContractError(f"Unable to verify registry/Auth seed index: {error}") from error
    versions = index["versions"]
    registries: dict[int, dict[str, Any]] = {}
    for entry in versions:
        version = entry["version"]
        artifact_name = entry.get("artifact")
        expected_artifact = f"product-surfaces-v1.bundle-v{version}.json"
        auth_seed_name = entry.get("authSeedArtifact")
        expected_auth_seed = f"product-surfaces-v1.bundle-v{version}.generated.json"
        if (
            entry.get("bundleStatus") != "DRAFT"
            or artifact_name != expected_artifact
            or auth_seed_name != expected_auth_seed
            or Path(str(artifact_name)).name != artifact_name
            or Path(str(auth_seed_name)).name != auth_seed_name
        ):
            raise ContractError(f"Registry v{version} index entry is invalid")
        contract_path = CONTRACT_ROOT / artifact_name
        auth_seed_path = AUTH_SEED_ROOT / auth_seed_name
        registry = load_json_yaml(contract_path)
        try:
            contract_bytes = contract_path.read_bytes()
            auth_seed_bytes = auth_seed_path.read_bytes()
        except OSError as error:
            raise ContractError(f"Unable to verify registry v{version} bytes: {error}") from error
        validate_registry_entry(
            index, entry, registry, contract_bytes, auth_seed_bytes
        )
        registries[version] = registry

    validate_monotonic_registry_lineage(registries)

    latest_version = REGISTRY_VERSIONS[-1]
    latest = registries[latest_version]
    if (
        index.get("latestVersion") != latest_version
        or index.get("latestChecksum") != latest["checksum"]
        or index.get("latestArtifact")
        != f"product-surfaces-v1.bundle-v{latest_version}.json"
        or index.get("latestAuthSeedArtifact")
        != f"product-surfaces-v1.bundle-v{latest_version}.generated.json"
    ):
        raise ContractError("Registry latest alias metadata is not v3-exact")
    try:
        if REGISTRY_LATEST_ALIAS.read_bytes() != (
            CONTRACT_ROOT / index["latestArtifact"]
        ).read_bytes():
            raise ContractError("Registry latest alias is not byte-identical to the indexed artifact")
        if AUTH_SEED_LATEST_ALIAS.read_bytes() != (
            AUTH_SEED_ROOT / index["latestAuthSeedArtifact"]
        ).read_bytes():
            raise ContractError("Auth seed latest alias is not byte-identical to the indexed artifact")
        if REGISTRY_LATEST_ALIAS.read_bytes() != AUTH_SEED_LATEST_ALIAS.read_bytes():
            raise ContractError("Registry/Auth seed latest alias bytes differ")
    except OSError as error:
        raise ContractError(f"Unable to verify registry latest alias: {error}") from error
    return index, registries


def registry_reference(registry: dict[str, Any]) -> dict[str, Any]:
    return {
        "bundleKey": registry["bundleKey"],
        "version": registry["version"],
        "sha256": registry["checksum"],
    }


def descriptor_earliest_versions(
    registries: dict[int, dict[str, Any]]
) -> dict[str, int]:
    earliest: dict[str, int] = {}
    for version in REGISTRY_VERSIONS:
        registry = registries[version]
        for section, key_field in DESCRIPTOR_SECTIONS.values():
            for record in registry.get(section, []):
                key = record.get(key_field) if isinstance(record, dict) else None
                if isinstance(key, str) and key:
                    earliest.setdefault(key, version)
    return earliest


def descriptor_references(value: dict[str, Any]) -> set[str]:
    references: set[str] = set()
    for field in DESCRIPTOR_SECTIONS:
        values = value.get(field, [])
        if isinstance(values, list):
            references.update(item for item in values if isinstance(item, str))
    singular_fields = {
        "capabilityContractKey", "accessPolicyKey", "entitlementExpressionKey",
        "predicatePolicyKey", "routeContractKey", "commandContractKey",
    }
    references.update(
        value[field] for field in singular_fields
        if isinstance(value.get(field), str)
    )
    return references


def required_version_for_references(
    references: set[str], earliest: dict[str, int], label: str
) -> int:
    unknown = sorted(references - earliest.keys())
    if unknown:
        raise ContractError(f"{label} references unknown registry descriptors: {unknown}")
    return max((earliest[key] for key in references), default=1)


def resolve_step_up_registry_binding(
    challenge: dict[str, Any], registry: dict[str, Any]
) -> dict[str, Any]:
    key = str(challenge.get("key"))
    capabilities = {
        capability["contractKey"]: capability
        for capability in registry["capabilities"]
    }
    capability = capabilities.get(challenge.get("capabilityContractKey"))
    if capability is None:
        raise ContractError(f"Step-up challenge {key} crosses its capability registry gate")
    expected_risk = "CRITICAL" if "-CRITICAL-" in str(challenge.get("policy")) else "HIGH"
    if (
        capability.get("lifecycleState") != "ACTIVE"
        or capability.get("riskTier") != expected_risk
        or capability.get("activationPolicy") != challenge.get("policy")
    ):
        raise ContractError(f"Step-up challenge {key} capability activation binding drift")
    candidates: list[tuple[dict[str, Any], dict[str, Any], dict[str, str]]] = []
    for route in registry.get("routes", []):
        profile_capabilities = {
            profile.get("requiredAccess", {}).get("capabilityContractKey")
            for profile in route.get("accessProfiles", [])
        }
        if challenge.get("capabilityContractKey") not in profile_capabilities:
            continue
        for gateway in route.get("gatewayApiBindings", []):
            parameters = template_parameters(
                gateway.get("path"), challenge.get("path")
            )
            if gateway.get("method") == challenge.get("method") and parameters is not None:
                candidates.append((route, gateway, parameters))
    if len(candidates) != 1:
        raise ContractError(
            f"Step-up challenge {key} capability/method/path does not resolve exactly once"
        )
    route, gateway, parameters = candidates[0]
    binding_key = gateway.get("bindingKey")
    services = [
        value for value in route.get("servicePepBindings", [])
        if value.get("bindingKey") == binding_key
    ]
    step_ups = [
        value for value in route.get("stepUpCommandBindings", [])
        if value.get("bindingKey") == binding_key
    ]
    if len(services) != 1 or len(step_ups) != 1:
        raise ContractError(
            f"Step-up challenge {key} must resolve one paired service and step-up binding"
        )
    service = services[0]
    step_up = step_ups[0]
    if (
        route.get("routeKind") != "ACTION"
        or service.get("method") != challenge.get("method")
        or step_up.get("ownerServiceKey") != service.get("serviceKey")
        or step_up.get("audience") != f"dwp-{service.get('serviceKey')}-server"
        or template_parameter_names(gateway.get("path"))
        != template_parameter_names(service.get("path"))
    ):
        raise ContractError(f"Step-up challenge {key} paired route/service binding drift")
    return {
        "route": route,
        "gateway": gateway,
        "service": service,
        "stepUp": step_up,
        "capability": capability,
        "pathParameters": parameters,
    }


def validate_step_up_binding_truth(
    challenge: dict[str, Any], authority: dict[str, Any]
) -> None:
    key = str(challenge.get("key"))
    step_up = authority["stepUp"]
    target_fields = set(step_up) - {
        "bindingKey", "targetType", "expectedObjectVersionSource",
        "expectedObjectVersionName", "ownerServiceKey", "audience",
    }
    if target_fields not in ({"targetIdPathParameter"}, {"targetIdBodyFields"}):
        raise ContractError(f"Step-up challenge {key} has an ambiguous target source")
    if challenge.get("targetType") != step_up.get("targetType"):
        raise ContractError(f"Step-up challenge {key} targetType binding drift")
    payload = challenge.get("payload")
    if not isinstance(payload, dict):
        raise ContractError(f"Step-up challenge {key} payload must be an object")
    if "targetIdPathParameter" in step_up:
        parameter = step_up["targetIdPathParameter"]
        if authority["pathParameters"].get(parameter) != challenge.get("targetId"):
            raise ContractError(f"Step-up challenge {key} path target binding drift")
    else:
        fields = step_up["targetIdBodyFields"]
        if (
            not isinstance(fields, list) or not fields
            or len(fields) != len(set(fields))
            or any(not isinstance(payload.get(field), str) or not payload[field]
                   for field in fields)
            or any(":" in payload[field] or "\n" in payload[field] or "\r" in payload[field]
                   for field in fields)
            or ":".join(payload[field] for field in fields) != challenge.get("targetId")
        ):
            raise ContractError(f"Step-up challenge {key} body target binding drift")
    version_source = step_up.get("expectedObjectVersionSource")
    version_name = step_up.get("expectedObjectVersionName")
    if version_source == "COMMAND_BODY":
        version = payload.get(version_name)
        if isinstance(version, bool) or not isinstance(version, int) \
                or version != challenge.get("targetVersion"):
            raise ContractError(f"Step-up challenge {key} COMMAND_BODY version drift")
    elif version_source == "COMMAND_HEADER":
        version_keys = [
            field for field in payload
            if "expectedversion" in re.sub(r"[^a-z0-9]", "", field.lower())
            or "expectedobjectversion" in re.sub(r"[^a-z0-9]", "", field.lower())
        ]
        if version_name in payload or version_keys:
            raise ContractError(
                f"Step-up challenge {key} COMMAND_HEADER version leaked into payload"
            )
    else:
        raise ContractError(f"Step-up challenge {key} version source is invalid")


def template_parameter_names(template: Any) -> list[str]:
    if not isinstance(template, str):
        return []
    return re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", template)


def template_parameters(template: Any, path: Any) -> dict[str, str] | None:
    if not isinstance(template, str) or not isinstance(path, str):
        return None
    names = template_parameter_names(template)
    if len(names) != len(set(names)):
        return None
    cursor = 0
    pattern = "^"
    for match in re.finditer(r"\{[A-Za-z][A-Za-z0-9]*\}", template):
        pattern += re.escape(template[cursor:match.start()]) + r"([^/]+)"
        cursor = match.end()
    pattern += re.escape(template[cursor:]) + "$"
    resolved = re.fullmatch(pattern, path)
    if resolved is None:
        return None
    return dict(zip(names, resolved.groups()))


def rendered(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def require_unique(values: list[str], label: str) -> None:
    duplicates = sorted(key for key, count in Counter(values).items() if count > 1)
    if duplicates:
        raise ContractError(f"Duplicate {label}: {', '.join(duplicates)}")


def registry_keys(registry: dict[str, Any], field: str, key_field: str) -> set[str]:
    records = registry.get(field, [])
    if not isinstance(records, list):
        raise ContractError(f"Registry {field} must be an array")
    return {str(record[key_field]) for record in records if isinstance(record, dict) and key_field in record}


def validate_overrides(source: dict[str, Any]) -> dict[str, Any]:
    if source.get("schemaVersion") != 1 or source.get("profile") != "contract-test":
        raise ContractError("Test registry overrides require schemaVersion=1 and profile=contract-test")
    overrides = source.get("overrides")
    if not isinstance(overrides, list) or len(overrides) != 2:
        raise ContractError("Test registry overrides must contain exactly two descriptors")
    keys = {item.get("key") for item in overrides if isinstance(item, dict)}
    if keys != set(ALLOWED_OVERRIDE_REFS.values()):
        raise ContractError(f"Unexpected contract-test override keys: {sorted(keys)}")
    test_ids = {
        test_id
        for item in overrides
        for test_id in (item.get("testIds", []) if isinstance(item, dict) else [])
    }
    if test_ids != set(ALLOWED_OVERRIDE_REFS):
        raise ContractError(f"Test override IDs must be exactly {sorted(ALLOWED_OVERRIDE_REFS)}")
    payload = {"schemaVersion": source["schemaVersion"], "profile": source["profile"], "overrides": overrides}
    expected = sha256(payload)
    actual = source.get("integrity", {}).get("sha256")
    if actual != expected:
        raise ContractError(f"Test override integrity mismatch: expected {expected}, found {actual}")
    return {**payload, "integrity": {"algorithm": "SHA-256", "sha256": expected}}


def validate_fixtures(
    source: dict[str, Any],
    index: dict[str, Any],
    registries: dict[int, dict[str, Any]],
) -> dict[str, Any]:
    if source.get("schemaVersion") != 1 or source.get("fixtureBundleKey") != "pilot-fixtures.v1":
        raise ContractError("Fixture source requires schemaVersion=1 and fixtureBundleKey=pilot-fixtures.v1")
    if "registryRef" in source:
        raise ContractError(
            "A global fixture registryRef is forbidden; gate authority is case-specific"
        )
    registry = registries[REGISTRY_VERSIONS[-1]]
    earliest = descriptor_earliest_versions(registries)

    catalogs = source.get("catalogs")
    if not isinstance(catalogs, dict):
        raise ContractError("catalogs must be an object")
    catalog_keys: set[str] = set()
    for name in (
        "scopes",
        "targetPopulations",
        "objects",
        "payloads",
        "relationships",
        "supportSessions",
        "stepUpChallenges",
    ):
        records = catalogs.get(name)
        if not isinstance(records, list):
            raise ContractError(f"catalogs.{name} must be an array")
        keys = [str(item.get("key")) for item in records if isinstance(item, dict)]
        if len(keys) != len(records) or any(key in {"", "None"} for key in keys):
            raise ContractError(f"catalogs.{name} contains a record without key")
        require_unique(keys, f"catalogs.{name} keys")
        overlap = catalog_keys.intersection(keys)
        if overlap:
            raise ContractError(f"Catalog keys must be globally unique: {sorted(overlap)}")
        catalog_keys.update(keys)

    components = source.get("components")
    if not isinstance(components, list) or not components:
        raise ContractError("components must be a non-empty array")
    component_keys = [str(item.get("key")) for item in components if isinstance(item, dict)]
    require_unique(component_keys, "component keys")
    if len(component_keys) != len(components):
        raise ContractError("Every component requires a key")

    capability_keys = registry_keys(registry, "capabilities", "contractKey")
    policy_keys = registry_keys(registry, "accessPolicies", "accessPolicyKey")
    route_keys = registry_keys(registry, "routes", "routeContractKey")
    reserved = set(source.get("reservedContracts", []))
    if reserved != RESERVED_CONTRACTS:
        raise ContractError(f"Reserved contracts must be exactly {sorted(RESERVED_CONTRACTS)}")

    for component in components:
        for key in component.get("capabilityContractKeys", []):
            if key in reserved:
                raise ContractError(f"Reserved capability {key} cannot be materialized")
            if key not in capability_keys:
                raise ContractError(f"Component {component['key']} references unknown capability {key}")
        for key in component.get("accessPolicyKeys", []):
            if key not in policy_keys:
                raise ContractError(f"Component {component['key']} references unknown policy {key}")
        for ref in (
            component.get("scopeRefs", [])
            + component.get("relationshipRefs", [])
            + component.get("evidenceRefs", [])
        ):
            if ref not in catalog_keys:
                raise ContractError(f"Component {component['key']} references unknown catalog key {ref}")
        support_ref = component.get("supportSessionRef")
        if support_ref and support_ref not in catalog_keys:
            raise ContractError(f"Component {component['key']} references unknown support session {support_ref}")

    component_versions = {
        str(component["key"]): required_version_for_references(
            descriptor_references(component), earliest,
            f"Component {component['key']}",
        )
        for component in components
    }
    challenge_versions: dict[str, int] = {}
    for challenge in catalogs["stepUpChallenges"]:
        key = str(challenge["key"])
        if key not in STEP_UP_CONTEXT_KEYS:
            raise ContractError(f"Step-up challenge {key} has no governed context binding")
        authority = resolve_step_up_registry_binding(challenge, registry)
        challenge_versions[key] = required_version_for_references(
            {
                str(challenge.get("capabilityContractKey")),
                str(authority["route"]["routeContractKey"]),
            },
            earliest,
            f"Step-up challenge {key}",
        )

    tests = source.get("testCases")
    if not isinstance(tests, list):
        raise ContractError("testCases must be an array")
    require_unique([str(item.get("testId")) for item in tests], "test IDs")
    counts = Counter(str(item.get("group")) for item in tests)
    if dict(counts) != EXPECTED_TEST_COUNTS:
        raise ContractError(f"Test group counts must be {EXPECTED_TEST_COUNTS}, found {dict(counts)}")
    valid_refs = set(component_keys) | catalog_keys
    test_versions: dict[str, int] = {}
    for test in tests:
        test_id = str(test.get("testId"))
        override = test.get("testRegistryOverrideRef")
        if override != ALLOWED_OVERRIDE_REFS.get(test_id):
            if override is not None or test_id in ALLOWED_OVERRIDE_REFS:
                raise ContractError(f"Illegal testRegistryOverrideRef for {test_id}: {override}")
        for ref in test.get("composition", []):
            if ref not in valid_refs and not ref.startswith("CASE:"):
                raise ContractError(f"Test {test_id} references unknown component/evidence {ref}")
        referenced_versions = [
            component_versions.get(ref, challenge_versions.get(ref, 1))
            for ref in test.get("composition", [])
            if not str(ref).startswith("CASE:")
        ]
        computed_version = max(referenced_versions, default=1)
        group = str(test.get("group"))
        required_version = FIXED_GROUP_VERSIONS.get(group, computed_version)
        if computed_version > required_version:
            raise ContractError(
                f"Test {test_id} group {group} crosses into registry v{computed_version}"
            )
        test_versions[test_id] = required_version

    negatives = source.get("negativeCases")
    if not isinstance(negatives, list) or len(negatives) != EXPECTED_NEGATIVE_COUNT:
        raise ContractError(f"negativeCases must contain {EXPECTED_NEGATIVE_COUNT} cases")
    require_unique([str(item.get("fixtureId")) for item in negatives], "negative fixture IDs")

    contexts = source.get("contextCases")
    context_ids = {str(item.get("fixtureId")) for item in contexts or []}
    if context_ids != EXPECTED_CONTEXT_IDS:
        raise ContractError(f"Context fixture IDs must be exactly {sorted(EXPECTED_CONTEXT_IDS)}")

    challenge_capabilities = {
        str(item.get("capabilityContractKey"))
        for item in catalogs["stepUpChallenges"]
        if item.get("capabilityContractKey")
    }
    if challenge_capabilities.intersection(reserved):
        raise ContractError("Reserved contracts cannot have active challenges")
    if any(key.startswith("test.") or key.startswith("route.test.") for key in capability_keys | route_keys):
        raise ContractError("Production registry must not contain contract-test keys")

    normalized = json.loads(json.dumps(source))
    normalized["catalogs"]["stepUpChallenges"] = signed_step_up_challenges(
        source, catalog_keys, challenge_versions, registries)
    normalized["testCases"] = [
        {
            **test,
            "requiredRegistryRef": registry_reference(
                registries[test_versions[str(test["testId"])]])
        }
        for test in source["testCases"]
    ]
    normalized["registryLineage"] = expected_registry_lineage(index, registries)
    validate_rendered_gate_refs(
        normalized, source, index, registries, test_versions, challenge_versions
    )
    run_cross_gate_negative_controls(
        normalized, source, index, registries, test_versions, challenge_versions
    )
    normalized["fixtureChecksumAlgorithm"] = "SHA-256"
    normalized["fixtureChecksum"] = sha256(normalized)
    return normalized


def expected_registry_lineage(
    index: dict[str, Any], registries: dict[int, dict[str, Any]]
) -> dict[str, Any]:
    return {
        "authority": "INFORMATIONAL_ONLY",
        "bundleKey": index["bundleKey"],
        "indexSha256": index["indexChecksum"],
        "latestAliasVersion": REGISTRY_VERSIONS[-1],
        "versions": [
            registry_reference(registries[version])
            for version in REGISTRY_VERSIONS
        ],
    }


def validate_registry_reference(
    value: Any, expected: dict[str, Any], label: str
) -> None:
    if not isinstance(value, dict) or value != expected:
        raise ContractError(f"{label} has a cross-gate requiredRegistryRef")


def validate_rendered_gate_refs(
    fixture: dict[str, Any],
    source: dict[str, Any],
    index: dict[str, Any],
    registries: dict[int, dict[str, Any]],
    test_versions: dict[str, int],
    challenge_versions: dict[str, int],
) -> None:
    if fixture.get("registryLineage") != expected_registry_lineage(index, registries):
        raise ContractError("Fixture registryLineage is not exact to the registry index/bundles")
    source_challenges = {
        challenge["key"]: challenge
        for challenge in source["catalogs"]["stepUpChallenges"]
    }
    for challenge in fixture["catalogs"]["stepUpChallenges"]:
        key = str(challenge["key"])
        version = challenge_versions[key]
        validate_registry_reference(
            challenge.get("requiredRegistryRef"),
            registry_reference(registries[version]),
            f"Step-up challenge {key}",
        )
        authority = resolve_step_up_registry_binding(
            source_challenges[key], registries[version]
        )
        validate_rendered_step_up_challenge(
            challenge, source_challenges[key], authority,
            source["stepUpVerification"], registry_reference(registries[version]),
        )
    for test in fixture["testCases"]:
        test_id = str(test["testId"])
        validate_registry_reference(
            test.get("requiredRegistryRef"),
            registry_reference(registries[test_versions[test_id]]),
            f"Test {test_id}",
        )


def run_cross_gate_negative_controls(
    fixture: dict[str, Any],
    source: dict[str, Any],
    index: dict[str, Any],
    registries: dict[int, dict[str, Any]],
    test_versions: dict[str, int],
    challenge_versions: dict[str, int],
) -> None:
    rejected = 0

    def expect_rejected(action: Any, label: str) -> None:
        nonlocal rejected
        try:
            action()
        except ContractError:
            rejected += 1
            return
        raise ContractError(f"Generator negative control was accepted: {label}")

    controls = copy.deepcopy(fixture)
    approval = next(
        test for test in controls["testCases"] if test["group"] == "APPROVALS"
    )
    approval["requiredRegistryRef"] = registry_reference(registries[3])
    hcm_challenge = next(
        challenge for challenge in controls["catalogs"]["stepUpChallenges"]
        if challenge["ownerServiceKey"] == "people"
    )
    hcm_challenge["requiredRegistryRef"] = registry_reference(registries[2])
    for value, expected, label in (
        (approval["requiredRegistryRef"],
         registry_reference(registries[test_versions[approval["testId"]]]),
         "Approval negative control"),
        (hcm_challenge["requiredRegistryRef"],
         registry_reference(registries[challenge_versions[hcm_challenge["key"]]]),
         "HCM challenge negative control"),
    ):
        expect_rejected(
            lambda value=value, expected=expected, label=label:
            validate_registry_reference(value, expected, label),
            label,
        )

    lineage_drift = copy.deepcopy(fixture)
    lineage_drift["registryLineage"]["indexSha256"] = "0" * 64
    expect_rejected(
        lambda: validate_rendered_gate_refs(
            lineage_drift, source, index, registries,
            test_versions, challenge_versions,
        ),
        "registryLineage index checksum drift",
    )

    index_drift = copy.deepcopy(index)
    index_drift["latestChecksum"] = "0" * 64
    expect_rejected(
        lambda: validate_registry_index(index_drift),
        "registry index checksum drift",
    )
    latest_entry = copy.deepcopy(index["versions"][-1])
    latest_registry = registries[3]
    contract_bytes = (CONTRACT_ROOT / latest_entry["artifact"]).read_bytes()
    auth_seed_bytes = (AUTH_SEED_ROOT / latest_entry["authSeedArtifact"]).read_bytes()
    count_drift = copy.deepcopy(latest_entry)
    count_drift["counts"]["routes"] += 1
    expect_rejected(
        lambda: validate_registry_entry(
            index, count_drift, latest_registry, contract_bytes, auth_seed_bytes
        ),
        "registry index descriptor count drift",
    )
    content_drift = copy.deepcopy(latest_registry)
    content_drift["routes"][0]["owner"] += ".drift"
    expect_rejected(
        lambda: validate_registry_entry(
            index, latest_entry, content_drift, contract_bytes, auth_seed_bytes
        ),
        "registry artifact canonical checksum drift",
    )
    expect_rejected(
        lambda: validate_registry_entry(
            index, latest_entry, latest_registry, contract_bytes, auth_seed_bytes + b"\n"
        ),
        "registry/Auth seed byte drift",
    )
    non_monotonic = copy.deepcopy(registries)
    inherited_key = registries[2]["routes"][0]["routeContractKey"]
    non_monotonic[3]["routes"] = [
        route for route in non_monotonic[3]["routes"]
        if route["routeContractKey"] != inherited_key
    ]
    expect_rejected(
        lambda: validate_monotonic_registry_lineage(non_monotonic),
        "registry non-monotonic lineage",
    )

    source_challenges = {
        challenge["key"]: challenge
        for challenge in source["catalogs"]["stepUpChallenges"]
    }
    rendered_challenges = {
        challenge["key"]: challenge
        for challenge in fixture["catalogs"]["stepUpChallenges"]
    }
    for key, source_challenge in source_challenges.items():
        version = challenge_versions[key]
        authority = resolve_step_up_registry_binding(
            source_challenge, registries[version]
        )
        binding_drift = copy.deepcopy(rendered_challenges[key])
        binding_drift["stepUpCommandBindingKey"] += ".drift"
        expect_rejected(
            lambda binding_drift=binding_drift, source_challenge=source_challenge,
            authority=authority, version=version: validate_rendered_step_up_challenge(
                binding_drift, source_challenge, authority,
                source["stepUpVerification"], registry_reference(registries[version]),
            ),
            f"{key} step-up binding projection drift",
        )
        version_truth_drift = copy.deepcopy(source_challenge)
        step_up = authority["stepUp"]
        if step_up["expectedObjectVersionSource"] == "COMMAND_HEADER":
            version_truth_drift["payload"]["expectedVersion"] = \
                version_truth_drift["targetVersion"]
        else:
            version_truth_drift["payload"].pop(
                step_up["expectedObjectVersionName"], None
            )
        expect_rejected(
            lambda version_truth_drift=version_truth_drift, authority=authority:
            validate_step_up_binding_truth(version_truth_drift, authority),
            f"{key} expected-version source truth table drift",
        )

    representative = copy.deepcopy(next(iter(rendered_challenges.values())))
    source_representative = source_challenges[representative["key"]]
    representative_version = challenge_versions[representative["key"]]
    representative_authority = resolve_step_up_registry_binding(
        source_representative, registries[representative_version]
    )
    for field, drift in (
        ("ownerServiceKey", "people"),
        ("capabilityContractKey", "hcm.org-design.publish"),
        ("commandContractKey", "route.hcm.management.org-publish.action"),
        ("method", "PATCH"),
        ("path", "/api/approvals/v1/admin/workflows/drift/publish"),
        ("audience", "dwp-people-server"),
        ("targetType", "DRIFT"),
        ("targetId", "DRIFT"),
        ("targetVersion", 999),
        ("expectedObjectVersionSource", "COMMAND_HEADER"),
        ("expectedObjectVersionName", "X-DWP-Expected-Object-Version"),
    ):
        mutated = copy.deepcopy(representative)
        mutated[field] = drift
        expect_rejected(
            lambda mutated=mutated: validate_rendered_step_up_challenge(
                mutated, source_representative, representative_authority,
                source["stepUpVerification"],
                registry_reference(registries[representative_version]),
            ),
            f"challenge {field} drift",
        )

    audience_token = copy.deepcopy(next(
        challenge for challenge in rendered_challenges.values()
        if challenge["ownerServiceKey"] == "people"
    ))
    header, claims = decode_and_verify_rs256(audience_token["compactToken"])
    claims["aud"] = "dwp-approval-server"
    audience_token["compactToken"] = rs256(header, claims)
    hcm_key = audience_token["key"]
    hcm_source = source_challenges[hcm_key]
    hcm_version = challenge_versions[hcm_key]
    hcm_authority = resolve_step_up_registry_binding(hcm_source, registries[hcm_version])
    expect_rejected(
        lambda: validate_rendered_step_up_challenge(
            audience_token, hcm_source, hcm_authority,
            source["stepUpVerification"], registry_reference(registries[hcm_version]),
        ),
        "People compact-token aud drift",
    )
    signature_token = copy.deepcopy(representative)
    token = signature_token["compactToken"]
    signature_token["compactToken"] = token[:-1] + ("A" if token[-1] != "A" else "B")
    expect_rejected(
        lambda: validate_rendered_step_up_challenge(
            signature_token, source_representative, representative_authority,
            source["stepUpVerification"],
            registry_reference(registries[representative_version]),
        ),
        "compact-token signature drift",
    )
    fractional_time = source_representative["issuedAt"].removesuffix("Z") + ".500Z"
    expect_rejected(
        lambda: epoch(fractional_time, "issuedAt"),
        "fractional RFC3339 challenge instant",
    )
    body_source = next(
        challenge for challenge in source_challenges.values()
        if rendered_challenges[challenge["key"]]["targetIdSource"] == "COMMAND_BODY"
    )
    body_authority = resolve_step_up_registry_binding(
        body_source, registries[challenge_versions[body_source["key"]]]
    )
    body_delimiter_drift = copy.deepcopy(body_source)
    first_body_field = body_authority["stepUp"]["targetIdBodyFields"][0]
    body_delimiter_drift["payload"][first_body_field] += ":drift"
    body_delimiter_drift["targetId"] = ":".join(
        body_delimiter_drift["payload"][field]
        for field in body_authority["stepUp"]["targetIdBodyFields"]
    )
    expect_rejected(
        lambda: validate_step_up_binding_truth(body_delimiter_drift, body_authority),
        "body target reserved delimiter collision",
    )
    if rejected != EXPECTED_GENERATOR_NEGATIVE_CONTROLS:
        raise ContractError(
            "Generator negative-control coverage drift: "
            f"expected {EXPECTED_GENERATOR_NEGATIVE_CONTROLS}, found {rejected}"
        )


def write_or_check(target: Path, content: str, write: bool) -> bool:
    if write:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return True
    return target.exists() and target.read_text(encoding="utf-8") == content


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()

    try:
        index, registries = load_registry_lineage()
        fixtures = validate_fixtures(
            load_json_yaml(FIXTURE_SOURCE), index, registries
        )
        overrides = validate_overrides(load_json_yaml(OVERRIDE_SOURCE))
    except ContractError as error:
        print(f"Fixture contract error: {error}", file=sys.stderr)
        return 1

    outputs = {
        FIXTURE_OUTPUT: rendered(fixtures),
        OVERRIDE_OUTPUT: rendered(overrides),
    }
    drift = [str(path.relative_to(ROOT)) for path, content in outputs.items() if not write_or_check(path, content, args.write)]
    if drift:
        print("Product authorization fixture drift: " + ", ".join(drift), file=sys.stderr)
        return 1
    action = "wrote" if args.write else "verified"
    print(
        f"Pilot fixtures {action}: {len(fixtures['testCases'])} tests, "
        f"{len(fixtures['negativeCases'])} negative cases, "
        f"{EXPECTED_GENERATOR_NEGATIVE_CONTROLS} generator mutation controls, "
        f"checksum {fixtures['fixtureChecksum']}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
