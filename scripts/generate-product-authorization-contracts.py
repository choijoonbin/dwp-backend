#!/usr/bin/env python3
"""Generate deterministic CORE-006 authorization registry artifacts.

The canonical source uses the JSON-compatible profile of YAML 1.2 so the
generator has no environment-dependent YAML parser. Generated files are never
inputs to this script.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import re
import sys
from collections import defaultdict
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts/product-authorization/product-surfaces-v1.yaml"
CONTRACT_DIRECTORY = ROOT / "contracts/product-authorization"
AUTH_SEED_DIRECTORY = (
    ROOT / "dwp-auth-server/src/main/resources/product-authorization"
)
LATEST_CONTRACT_OUTPUT = CONTRACT_DIRECTORY / "product-surfaces-v1.json"
LATEST_AUTH_SEED_OUTPUT = AUTH_SEED_DIRECTORY / "product-surfaces-v1.generated.json"
CONTRACT_INDEX_OUTPUT = CONTRACT_DIRECTORY / "product-surfaces-v1.index.json"
AUTH_SEED_INDEX_OUTPUT = AUTH_SEED_DIRECTORY / "product-surfaces-v1.index.generated.json"
PLATFORM_CANARY_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-canary-pep-v1.generated.json"
)
APPROVAL_PILOT_PEP_OUTPUT = (
    ROOT
    / "dwp-approval-server/src/main/resources/product-authorization/"
    / "approval-pilot-pep-v2.generated.json"
)
PLATFORM_APPROVALS_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-approvals-pep-v2.generated.json"
)
PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-telemetry-dimensions-v2.generated.json"
)
VERSIONED_CONTRACT_OUTPUTS = {
    version: CONTRACT_DIRECTORY / f"product-surfaces-v1.bundle-v{version}.json"
    for version in (1, 2, 3)
}
VERSIONED_AUTH_SEED_OUTPUTS = {
    version: AUTH_SEED_DIRECTORY / f"product-surfaces-v1.bundle-v{version}.generated.json"
    for version in (1, 2, 3)
}

SECTION_KEYS = {
    "capabilities": "contractKey",
    "accessPolicies": "accessPolicyKey",
    "entitlementExpressions": "expressionKey",
    "predicatePolicies": "predicatePolicyKey",
    "routes": "routeContractKey",
}
APPROVAL_FIELD_MASK_SCHEMA_PROFILES = {
    "ApprovalOversightAdminPulseV1": "legacy-oversight",
    "ApprovalOversightWorkflowV1": "legacy-oversight",
    "ApprovalOversightFormV1": "legacy-oversight",
    "ApprovalOversightPolicyV1": "legacy-oversight",
    "ApprovalAuditorOperationsV1": "auditor",
    "ApprovalOversightOperationsV1": "legacy-oversight",
    "ApprovalOversightSignatureV1": "legacy-oversight",
}
PROJECTION_BASE_FIELDS = {
    "apiBindingKey", "projectionPolicyKey", "responseSchemaKey"
}
PROJECTION_METADATA_FIELDS = {
    "schemaVersion", "openApiSchemaSha256", "additionalProperties"
}
LOWERCASE_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_RELEASE_COUNTS = {
    1: {"capabilities": 10, "accessPolicies": 5, "entitlementExpressions": 2,
        "predicatePolicies": 6, "routes": 35, "PAGE": 18, "DATA": 1, "ACTION": 16},
    2: {"capabilities": 34, "accessPolicies": 6, "entitlementExpressions": 3,
        "predicatePolicies": 13, "routes": 76, "PAGE": 33, "DATA": 6, "ACTION": 37},
    3: {"capabilities": 62, "accessPolicies": 14, "entitlementExpressions": 8,
        "predicatePolicies": 25, "routes": 129, "PAGE": 58, "DATA": 12, "ACTION": 59},
}
PLATFORM_CANARY_PRODUCTS = {"communications", "services"}

ROUTE_KINDS = {"PAGE", "DATA", "ACTION"}
ACCESS_MODES = {"NORMAL", "ELEVATED", "PROVIDER_SUPPORT"}
TARGET_KINDS = {"SELF", "OBJECT", "RELATIONSHIP", "TARGET_POPULATION", "CONFIG_SCOPE"}
LIFECYCLE_STATES = {"ACTIVE", "RETIRED"}
BUNDLE_STATES = {"DRAFT", "APPROVED", "ACTIVE", "RETIRED"}
SERVICE_PATH_PREFIXES = {
    "auth": "/auth/",
    "platform": "/v1/",
    "approval": "/v1/",
    "people": "/v1/",
}


class ContractError(ValueError):
    """Raised when the canonical registry is not total or internally closed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def stable_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def checksum(value: dict[str, Any]) -> str:
    payload = copy.deepcopy(value)
    payload.pop("checksum", None)
    payload.pop("bundleStatus", None)
    return hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest()


def unique(items: list[dict[str, Any]], key: str, section: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in items:
        value = item.get(key)
        require(isinstance(value, str) and value, f"{section}: missing {key}")
        require(value not in result, f"{section}: duplicate {key} {value}")
        result[value] = item
    return result


def normalize_source(source: dict[str, Any]) -> dict[str, Any]:
    required_sections = (
        "capabilities",
        "accessPolicies",
        "entitlementExpressions",
        "predicatePolicies",
        "routes",
    )
    require(source.get("schemaVersion") == 1, "schemaVersion must be 1")
    require(source.get("bundleKey") == "product-surfaces", "bundleKey must be product-surfaces")
    require(source.get("version") in {1, 2, 3}, "bundle version must be 1, 2, or 3")
    require(source.get("bundleStatus") in BUNDLE_STATES, "invalid bundleStatus")
    for section in required_sections:
        require(isinstance(source.get(section), list), f"{section} must be an array")

    generated = copy.deepcopy(source)
    generated["checksumAlgorithm"] = "SHA-256"

    capabilities = unique(generated["capabilities"], "contractKey", "capabilities")
    policies = unique(generated["accessPolicies"], "accessPolicyKey", "accessPolicies")
    expressions = unique(
        generated["entitlementExpressions"], "expressionKey", "entitlementExpressions"
    )
    predicates = unique(
        generated["predicatePolicies"], "predicatePolicyKey", "predicatePolicies"
    )
    routes = unique(generated["routes"], "routeContractKey", "routes")

    capability_routes: dict[str, set[str]] = defaultdict(set)
    policy_routes: dict[str, set[str]] = defaultdict(set)
    predicate_routes: dict[str, set[str]] = defaultdict(set)

    for expression in expressions.values():
        _validate_expression(expression.get("expression"), expression["expressionKey"])
        _validate_revision(expression, expression["expressionKey"])

    for predicate in predicates.values():
        _validate_revision(predicate, predicate["predicatePolicyKey"])
        target_kinds = predicate.get("targetBindingKinds")
        require(
            isinstance(target_kinds, list)
            and target_kinds
            and len(target_kinds) == len(set(target_kinds))
            and set(target_kinds) <= TARGET_KINDS,
            f"{predicate['predicatePolicyKey']}: invalid targetBindingKinds",
        )
        require(
            predicate.get("ownerServiceKey") in SERVICE_PATH_PREFIXES,
            f"{predicate['predicatePolicyKey']}: unknown ownerServiceKey",
        )
        schema = predicate.get("parameterSchema")
        require(
            isinstance(schema, dict)
            and schema.get("type") == "object"
            and schema.get("additionalProperties") is False,
            f"{predicate['predicatePolicyKey']}: closed parameterSchema required",
        )

    for policy in policies.values():
        _validate_policy(policy, capabilities, expressions)
        _validate_revision(policy, policy["accessPolicyKey"])

    for capability in capabilities.values():
        _validate_capability(capability, generated["version"])
        _validate_revision(capability, capability["contractKey"])

    for route in routes.values():
        _validate_route(
            route,
            capabilities,
            policies,
            predicates,
            capability_routes,
            policy_routes,
            predicate_routes,
            generated["version"],
        )
        _validate_revision(route, route["routeContractKey"])

    _validate_wire_authority_equivalence(routes)

    # MODE_BRANCH capabilities are consumers of every route that references
    # that policy. This closes the descriptor-to-route reverse index.
    for policy_key, route_keys in policy_routes.items():
        policy = policies[policy_key]
        for branch in policy.get("modeBranches") or []:
            if branch.get("resultGrantKind") == "CAPABILITY":
                for capability_key in branch["capabilityContractKeys"]:
                    capability_routes[capability_key].update(route_keys)

    for key, item in capabilities.items():
        item["routeContractKeys"] = sorted(capability_routes[key])
    for key, item in policies.items():
        item["routeContractKeys"] = sorted(policy_routes[key])
    for key, item in predicates.items():
        item["routeContractKeys"] = sorted(predicate_routes[key])

    generated["capabilities"] = sorted(capabilities.values(), key=lambda item: item["contractKey"])
    generated["accessPolicies"] = sorted(policies.values(), key=lambda item: item["accessPolicyKey"])
    generated["entitlementExpressions"] = sorted(
        expressions.values(), key=lambda item: item["expressionKey"]
    )
    generated["predicatePolicies"] = sorted(
        predicates.values(), key=lambda item: item["predicatePolicyKey"]
    )
    generated["routes"] = sorted(routes.values(), key=lambda item: item["routeContractKey"])
    generated["checksum"] = checksum(generated)
    return generated


def build_snapshots(source: dict[str, Any]) -> list[dict[str, Any]]:
    """Expand gate-isolated declarations into three complete DRAFT snapshots."""
    canonical = copy.deepcopy(source)
    waves = canonical.pop("waves", None)
    descriptor_enrichments = canonical.pop("descriptorEnrichments", None)
    require(isinstance(waves, list), "waves must be an array")
    require(
        [wave.get("version") for wave in waves if isinstance(wave, dict)] == [2, 3],
        "waves must contain exactly versions 2 and 3 in order",
    )
    require(canonical.get("version") == 1, "canonical base must be bundle version 1")
    _validate_descriptor_enrichment_envelope(canonical, waves, descriptor_enrichments)

    snapshots = [normalize_source(
        _apply_descriptor_enrichments(canonical, descriptor_enrichments)
    )]
    accumulated = copy.deepcopy(canonical)
    for wave in waves:
        require(
            set(wave) == {"version", *SECTION_KEYS},
            f"wave {wave.get('version')}: unexpected or missing section",
        )
        version = wave["version"]
        require(version == accumulated["version"] + 1, f"wave {version}: non-contiguous version")
        for section, key in SECTION_KEYS.items():
            require(isinstance(wave[section], list), f"wave {version}/{section}: array required")
            prior_keys = {item[key] for item in accumulated[section]}
            additions = unique(wave[section], key, f"wave {version}/{section}")
            overlap = prior_keys & additions.keys()
            require(not overlap, f"wave {version}/{section}: descriptor redefinition {sorted(overlap)}")
            accumulated[section].extend(copy.deepcopy(wave[section]))
        accumulated["version"] = version
        snapshots.append(normalize_source(
            _apply_descriptor_enrichments(accumulated, descriptor_enrichments)
        ))

    for previous, current in zip(snapshots, snapshots[1:]):
        _validate_exact_superset(previous, current)
    for snapshot in snapshots:
        _validate_release_snapshot(snapshot)
    return snapshots


def _validate_descriptor_enrichment_envelope(
    base: dict[str, Any], waves: list[dict[str, Any]], enrichment: Any
) -> None:
    """Require every descriptor patch to land in the snapshot that introduces its key."""
    require(isinstance(enrichment, dict), "descriptorEnrichments must be an object")
    require(
        set(enrichment) == {"capabilities", "routes", "authorityEndpoints"},
        "descriptorEnrichments envelope is invalid",
    )
    authority = enrichment["authorityEndpoints"]
    require(
        isinstance(authority, dict)
        and set(authority) == {"introducedInVersion", "values"}
        and authority["introducedInVersion"] == 2
        and isinstance(authority["values"], list),
        "authorityEndpoints must be introduced exactly in registry v2",
    )

    accumulated = copy.deepcopy(base)
    first_versions: dict[str, dict[str, int]] = {
        "capabilities": {}, "routes": {}
    }
    for section, key in (("capabilities", "contractKey"),
                         ("routes", "routeContractKey")):
        first_versions[section].update({
            item[key]: 1 for item in accumulated[section]
        })
    for wave in waves:
        for section, key in (("capabilities", "contractKey"),
                             ("routes", "routeContractKey")):
            for item in wave[section]:
                first_versions[section][item[key]] = wave["version"]

    for section, key in (("capabilities", "contractKey"),
                         ("routes", "routeContractKey")):
        patches = unique(enrichment[section], key, f"{section} enrichments")
        require(
            patches.keys() <= first_versions[section].keys(),
            f"{section}: enrichment references an unknown descriptor",
        )

    all_capabilities = {
        item["contractKey"]: item
        for item in base["capabilities"]
    }
    for wave in waves:
        all_capabilities.update({
            item["contractKey"]: item for item in wave["capabilities"]
        })
    capability_patches = unique(
        enrichment["capabilities"], "contractKey", "capability enrichments"
    )
    require(
        set(capability_patches) == {
            key for key, value in all_capabilities.items()
            if value.get("responsibilityRequirement") == "REQUIRED"
        },
        "every and only REQUIRED capability must declare responsibility enrichment",
    )


def _apply_descriptor_enrichments(
    source: dict[str, Any], enrichment: dict[str, Any]
) -> dict[str, Any]:
    """Apply only enrichments whose descriptors exist in this gate snapshot."""
    candidate = copy.deepcopy(source)
    version = candidate["version"]
    authority = enrichment["authorityEndpoints"]
    if version >= authority["introducedInVersion"]:
        candidate["authorityEndpoints"] = copy.deepcopy(authority["values"])
    else:
        candidate.pop("authorityEndpoints", None)

    capabilities = unique(
        candidate["capabilities"], "contractKey", f"v{version} capabilities"
    )
    capability_patches = unique(
        enrichment["capabilities"], "contractKey", "capability enrichments"
    )
    for key, patch in capability_patches.items():
        if key not in capabilities:
            continue
        require(
            set(patch) <= {"contractKey", "requiredResponsibilityCode", "scopeResolver"}
            and isinstance(patch.get("requiredResponsibilityCode"), str)
            and patch["requiredResponsibilityCode"],
            f"{key}: invalid capability enrichment",
        )
        capabilities[key]["requiredResponsibilityCode"] = patch["requiredResponsibilityCode"]
        if "scopeResolver" in patch:
            capabilities[key]["scopeResolver"] = patch["scopeResolver"]

    routes = unique(candidate["routes"], "routeContractKey", f"v{version} routes")
    route_patches = unique(
        enrichment["routes"], "routeContractKey", "route enrichments"
    )
    for key, patch in route_patches.items():
        if key not in routes:
            continue
        require(
            set(patch) <= {
                "routeContractKey", "authorizationEquivalenceKey",
                "queryParameterConstraintsByBinding", "projectionBindings",
                "stepUpCommandBindings"
            },
            f"{key}: invalid route enrichment fields",
        )
        route = routes[key]
        if "authorizationEquivalenceKey" in patch:
            route["authorizationEquivalenceKey"] = patch["authorizationEquivalenceKey"]
        if "stepUpCommandBindings" in patch:
            route["stepUpCommandBindings"] = copy.deepcopy(patch["stepUpCommandBindings"])
        for binding_key, constraints in patch.get(
            "queryParameterConstraintsByBinding", {}
        ).items():
            _apply_query_constraints(route, binding_key, constraints)
        for projection in patch.get("projectionBindings", []):
            _apply_projection_binding(route, projection)
    return candidate


def _apply_query_constraints(
    route: dict[str, Any], binding_key: str, constraints: Any
) -> None:
    require(isinstance(constraints, dict) and constraints,
            f"{binding_key}: query constraints required")
    matched = 0
    for field in ("gatewayApiBindings", "servicePepBindings"):
        for binding in route[field]:
            if binding["bindingKey"] == binding_key:
                binding["queryParameterConstraints"] = copy.deepcopy(constraints)
                matched += 1
    require(matched == 2, f"{binding_key}: public/service enrichment mismatch")


def _apply_projection_binding(route: dict[str, Any], patch: Any) -> None:
    patch_fields = set(patch) if isinstance(patch, dict) else set()
    expected_base = PROJECTION_BASE_FIELDS | {"profileKey"}
    has_metadata = patch_fields == expected_base | PROJECTION_METADATA_FIELDS
    require(
        isinstance(patch, dict)
        and (patch_fields == expected_base or has_metadata),
        f"{route['routeContractKey']}: invalid projection enrichment",
    )
    if has_metadata:
        require(
            patch["schemaVersion"] == 1
            and isinstance(patch["openApiSchemaSha256"], str)
            and LOWERCASE_SHA256_PATTERN.fullmatch(
                patch["openApiSchemaSha256"]
            ) is not None
            and patch["additionalProperties"] is False,
            f"{route['routeContractKey']}: invalid projection schema metadata",
        )
    profiles = [
        profile for profile in route["accessProfiles"]
        if profile["profileKey"] == patch["profileKey"]
    ]
    require(len(profiles) == 1, f"{route['routeContractKey']}: unknown projection profile")
    projections = profiles[0].setdefault("responseProjectionBindings", [])
    projections = [
        value for value in projections
        if value["apiBindingKey"] != patch["apiBindingKey"]
    ]
    projection = {
        "apiBindingKey": patch["apiBindingKey"],
        "projectionPolicyKey": patch["projectionPolicyKey"],
        "responseSchemaKey": patch["responseSchemaKey"],
    }
    if has_metadata:
        projection.update({
            field: patch[field] for field in PROJECTION_METADATA_FIELDS
        })
    projections.append(projection)
    profiles[0]["responseProjectionBindings"] = projections


def _validate_exact_superset(previous: dict[str, Any], current: dict[str, Any]) -> None:
    require(current["version"] == previous["version"] + 1, "snapshot versions must be contiguous")
    for section, key in SECTION_KEYS.items():
        prior = {item[key]: item for item in previous[section]}
        candidate = {item[key]: item for item in current[section]}
        require(prior.keys() < candidate.keys(), f"v{current['version']}/{section}: not an exact strict superset")
        drift = []
        for descriptor_key, descriptor in prior.items():
            prior_descriptor = copy.deepcopy(descriptor)
            candidate_descriptor = copy.deepcopy(candidate[descriptor_key])
            prior_routes = set(prior_descriptor.pop("routeContractKeys", []))
            candidate_routes = set(candidate_descriptor.pop("routeContractKeys", []))
            if prior_descriptor != candidate_descriptor or not prior_routes <= candidate_routes:
                drift.append(descriptor_key)
        require(
            not drift,
            f"v{current['version']}/{section}: prior descriptor or reverse-reference drift {drift}",
        )
    prior_endpoints = previous.get("authorityEndpoints", [])
    current_endpoints = current.get("authorityEndpoints", [])
    if current["version"] == 2:
        require(not prior_endpoints and current_endpoints,
                "v2 must introduce the step-up authority endpoint")
    else:
        require(prior_endpoints == current_endpoints,
                f"v{current['version']}: authority endpoint drift")


def _validate_release_snapshot(snapshot: dict[str, Any]) -> None:
    version = snapshot["version"]
    expected = EXPECTED_RELEASE_COUNTS[version]
    actual = {section: len(snapshot[section]) for section in SECTION_KEYS}
    for kind in ROUTE_KINDS:
        actual[kind] = sum(route["routeKind"] == kind for route in snapshot["routes"])
    require(actual == expected, f"v{version}: release count drift expected={expected} actual={actual}")
    require(snapshot["bundleStatus"] == "DRAFT", f"v{version}: generated seed must remain DRAFT")

    _validate_approval_projection_schema_metadata(snapshot)

    endpoints = unique(
        snapshot.get("authorityEndpoints", []), "endpointKey", "authorityEndpoints"
    )
    expected_endpoints = {} if version == 1 else {
        "product-surface-step-up-challenge.issue": {
            "endpointKey": "product-surface-step-up-challenge.issue",
            "method": "POST",
            "publicPath": "/api/auth/product-surface-step-up-challenges",
            "serviceKey": "auth",
            "servicePath": "/auth/product-surface-step-up-challenges",
            "requiresAuthentication": True,
            "requiresCsrf": True,
            "expectedDecisionRevisionHeader":
                "X-DWP-Expected-Decision-Revision",
        }
    }
    require(endpoints == expected_endpoints,
            f"v{version}: authority endpoint contract drift")

    if version == 2:
        hcm_capabilities = [
            item for item in snapshot["capabilities"]
            if item["contractKey"].startswith("hcm.")
            or item.get("productKey") == "hcm"
        ]
        hcm_routes = [
            item for item in snapshot["routes"]
            if item["routeContractKey"].startswith("route.hcm.")
            or item["subject"].get("productKey") == "hcm"
        ]
        hcm_policies = [
            item for item in snapshot["accessPolicies"]
            if item["accessPolicyKey"].startswith("hcm.")
            or item.get("productKey") == "hcm"
        ]
        require(
            not hcm_capabilities and not hcm_routes and not hcm_policies,
            "v2 W1a snapshot must contain exactly zero HCM product descriptors",
        )
        approval_high_bindings = [
            binding
            for route in snapshot["routes"]
            if route["subject"].get("productKey") == "approvals"
            for binding in route.get("stepUpCommandBindings", [])
        ]
        require(
            len(approval_high_bindings) == 4
            and len({binding["bindingKey"] for binding in approval_high_bindings}) == 4,
            "v2 W1a snapshot must close exactly four Approval HIGH bindings",
        )

    if version != 3:
        return
    capability_keys = {item["contractKey"] for item in snapshot["capabilities"]}
    route_keys = {item["routeContractKey"] for item in snapshot["routes"]}
    binding_paths = {
        binding["path"]
        for route in snapshot["routes"]
        for field in ("gatewayApiBindings", "servicePepBindings")
        for binding in route[field]
    }
    require("hcm.reference.publish" not in capability_keys, "reserved reference publish capability forbidden")
    require("hcm.integration.rotate-secret" not in capability_keys, "reserved rotate-secret capability forbidden")
    require(not any("sample-import" in path for path in binding_paths), "sample-import binding forbidden")
    require(not any("credential" in key.lower() for key in route_keys), "credential writer route forbidden")
    for route in snapshot["routes"]:
        if route["routeContractKey"] in {
            "route.hcm.management.integration-create.action",
            "route.hcm.management.integration-update.action",
        }:
            predicate_keys = {
                key
                for profile in route["accessProfiles"]
                for key in profile["predicatePolicyKeys"]
            }
            require(
                "predicate.hcm-integration-nonsecret-update.v1" in predicate_keys,
                f"{route['routeContractKey']}: credentialReference deny predicate required",
            )


def _validate_approval_projection_schema_metadata(
    snapshot: dict[str, Any]
) -> None:
    seen_schemas: set[str] = set()
    for route in snapshot["routes"]:
        subject = route["subject"]
        approval_route = (
            subject.get("type") == "PRODUCT"
            and subject.get("productKey") == "approvals"
        )
        for profile in route["accessProfiles"]:
            profile_key = profile["profileKey"]
            target_profile = (
                snapshot["version"] >= 2
                and approval_route
                and profile_key in {"auditor", "legacy-oversight"}
            )
            for projection in profile.get("responseProjectionBindings", []):
                fields = set(projection)
                if target_profile:
                    schema_key = projection.get("responseSchemaKey")
                    require(
                        fields == PROJECTION_BASE_FIELDS | PROJECTION_METADATA_FIELDS
                        and APPROVAL_FIELD_MASK_SCHEMA_PROFILES.get(schema_key)
                        == profile_key
                        and projection.get("schemaVersion") == 1
                        and isinstance(projection.get("openApiSchemaSha256"), str)
                        and LOWERCASE_SHA256_PATTERN.fullmatch(
                            projection["openApiSchemaSha256"]
                        ) is not None
                        and projection.get("additionalProperties") is False,
                        f"{route['routeContractKey']}/{profile_key}: "
                        "invalid Approval projection schema metadata",
                    )
                    seen_schemas.add(schema_key)
                else:
                    require(
                        fields == PROJECTION_BASE_FIELDS,
                        f"{route['routeContractKey']}/{profile_key}: "
                        "projection schema metadata is forbidden",
                    )
    expected = (
        set(APPROVAL_FIELD_MASK_SCHEMA_PROFILES)
        if snapshot["version"] >= 2 else set()
    )
    require(
        seen_schemas == expected,
        f"v{snapshot['version']}: Approval projection schema coverage drift",
    )


def _validate_revision(item: dict[str, Any], key: str) -> None:
    require(item.get("policyVersion") == 1, f"{key}: policyVersion must be 1")
    require(item.get("lifecycleState") in LIFECYCLE_STATES, f"{key}: invalid lifecycleState")
    require(isinstance(item.get("owner"), str) and item["owner"], f"{key}: owner required")


def _validate_expression(node: Any, key: str) -> None:
    require(isinstance(node, dict), f"{key}: expression must be an object")
    node_type = node.get("type")
    require(node_type in {"LEAF", "ANY", "ALL"}, f"{key}: invalid expression node")
    if node_type == "LEAF":
        entitlement = node.get("entitlement")
        require(
            isinstance(entitlement, str)
            and entitlement.startswith("APP.")
            and ":" in entitlement,
            f"{key}: invalid entitlement leaf",
        )
        require(set(node) == {"type", "entitlement"}, f"{key}: invalid LEAF fields")
        return
    children = node.get("children")
    require(isinstance(children, list) and children, f"{key}: empty {node_type} expression")
    require(set(node) == {"type", "children"}, f"{key}: invalid {node_type} fields")
    for child in children:
        _validate_expression(child, key)


def _validate_capability(capability: dict[str, Any], bundle_version: int) -> None:
    key = capability["contractKey"]
    code = capability.get("resolvedCapabilityCode")
    require(isinstance(code, str) and code.count(":") == 1, f"{key}: exact capability code required")
    resource, action = code.split(":", 1)
    require(capability.get("resourceKey") == resource, f"{key}: resource mapping drift")
    require(capability.get("action") == action, f"{key}: action mapping drift")
    require(capability.get("mappingVersion") == 1, f"{key}: mappingVersion must be 1")
    require(
        capability.get("authorityMode")
        in {"PERMISSION", "PERMISSION_AND_RELATIONSHIP", "PERMISSION_OR_RELATIONSHIP"},
        f"{key}: invalid authorityMode",
    )
    require(
        capability.get("responsibilityRequirement")
        in {"REQUIRED", "NOT_REQUIRED", "LEGACY_OVERSIGHT"},
        f"{key}: invalid responsibilityRequirement",
    )
    required_responsibility = capability.get("requiredResponsibilityCode")
    if capability["responsibilityRequirement"] == "REQUIRED":
        require(
            isinstance(required_responsibility, str)
            and required_responsibility == "APP_CONFIG_ADMIN",
            f"{key}: exact requiredResponsibilityCode required",
        )
    else:
        require(
            required_responsibility is None,
            f"{key}: requiredResponsibilityCode forbidden",
        )
    require(capability.get("riskTier") in {"LOW", "MEDIUM", "HIGH", "CRITICAL"}, f"{key}: invalid riskTier")
    require(isinstance(capability.get("scopeResolver"), str) and capability["scopeResolver"], f"{key}: scopeResolver required")
    require(isinstance(capability.get("requiresProductEntitlement"), bool), f"{key}: entitlement flag required")


def _validate_policy(
    policy: dict[str, Any],
    capabilities: dict[str, dict[str, Any]],
    expressions: dict[str, dict[str, Any]],
) -> None:
    key = policy["accessPolicyKey"]
    product_key = policy.get("productKey")
    surface_key = policy.get("surfaceKey")
    entries = policy.get("surfaceEntryKeys")
    require((product_key is None) == (surface_key is None), f"{key}: incomplete policy subject")
    require(isinstance(entries, list), f"{key}: surfaceEntryKeys required")
    if product_key is None:
        require(not entries, f"{key}: governed context cannot own a surface entry")
    else:
        require(entries, f"{key}: product policy requires a surface entry")
    require(isinstance(policy.get("scopeResolver"), str) and policy["scopeResolver"], f"{key}: scopeResolver required")
    require(isinstance(policy.get("requiresProductEntitlement"), bool), f"{key}: entitlement flag required")
    evaluation = policy.get("evaluationType")
    require(evaluation in {"SINGLE", "MODE_BRANCH"}, f"{key}: invalid evaluationType")
    if evaluation == "SINGLE":
        mode = policy.get("authorityMode")
        require(mode in {"ENTITLEMENT", "RELATIONSHIP", "ENTITLEMENT_AND_RELATIONSHIP", "SUPPORT_SESSION"}, f"{key}: invalid authorityMode")
        require(not policy.get("modeBranches"), f"{key}: SINGLE cannot have modeBranches")
        expression_key = policy.get("entitlementExpressionKey")
        if mode in {"ENTITLEMENT", "ENTITLEMENT_AND_RELATIONSHIP"}:
            require(expression_key in expressions, f"{key}: unknown entitlement expression")
        else:
            require(expression_key is None, f"{key}: expression forbidden")
        support_scopes = policy.get("supportScopes")
        if mode == "SUPPORT_SESSION":
            require(isinstance(support_scopes, list) and support_scopes, f"{key}: supportScopes required")
        else:
            require(not support_scopes, f"{key}: supportScopes forbidden")
        return
    require(policy.get("authorityMode") is None, f"{key}: MODE_BRANCH authorityMode forbidden")
    require(policy.get("entitlementExpressionKey") is None, f"{key}: MODE_BRANCH expression forbidden")
    require(not policy.get("supportScopes"), f"{key}: MODE_BRANCH top-level supportScopes forbidden")
    branches = policy.get("modeBranches")
    require(isinstance(branches, list) and branches, f"{key}: branches required")
    modes: set[str] = set()
    for branch in branches:
        mode = branch.get("activeAccessMode")
        require(mode in ACCESS_MODES and mode not in modes, f"{key}: duplicate/invalid branch mode")
        modes.add(mode)
        if branch.get("resultGrantKind") == "CAPABILITY":
            keys = branch.get("capabilityContractKeys")
            require(mode != "PROVIDER_SUPPORT", f"{key}: support capability branch forbidden")
            require(isinstance(keys, list) and keys and all(value in capabilities for value in keys), f"{key}: invalid capability branch")
            require(branch.get("capabilityMode") in {"ANY", "ALL"}, f"{key}: capabilityMode required")
            require(branch.get("responsibilityRequirement") in {"REQUIRED", "NOT_REQUIRED", "LEGACY_OVERSIGHT"}, f"{key}: responsibility required")
            require(branch.get("authorityMode") is None and not branch.get("supportScopes"), f"{key}: capability support union fields forbidden")
        else:
            require(
                branch.get("resultGrantKind") == "POLICY"
                and mode == "PROVIDER_SUPPORT"
                and branch.get("authorityMode") == "SUPPORT_SESSION"
                and branch.get("capabilityMode") is None
                and not branch.get("capabilityContractKeys")
                and branch.get("responsibilityRequirement") is None
                and isinstance(branch.get("supportScopes"), list)
                and branch["supportScopes"],
                f"{key}: invalid support branch",
            )


def _validate_route(
    route: dict[str, Any],
    capabilities: dict[str, dict[str, Any]],
    policies: dict[str, dict[str, Any]],
    predicates: dict[str, dict[str, Any]],
    capability_routes: dict[str, set[str]],
    policy_routes: dict[str, set[str]],
    predicate_routes: dict[str, set[str]],
    bundle_version: int,
) -> None:
    key = route["routeContractKey"]
    kind = route.get("routeKind")
    require(kind in ROUTE_KINDS, f"{key}: invalid routeKind")
    subject = route.get("subject")
    require(isinstance(subject, dict), f"{key}: subject required")
    if subject.get("type") == "PRODUCT":
        require(subject.get("productKey") and subject.get("surfaceKey"), f"{key}: product subject incomplete")
        require(not key.startswith("route.context."), f"{key}: product route namespace mismatch")
    else:
        require(subject == {"type": "GOVERNED_CONTEXT"}, f"{key}: invalid governed context subject")
        context_id = route.get("navigationContextId")
        token = context_id.replace(".", "__")
        require(key.startswith(f"route.context.{token}."), f"{key}: non-product context token mismatch")
        require("_" not in context_id, f"{key}: non-canonical navigationContextId")
    if kind == "PAGE":
        require(route.get("uiRouteId") and route.get("uiRoutePattern"), f"{key}: PAGE UI contract required")
    else:
        require(route.get("uiRouteId") is None and route.get("uiRoutePattern") is None, f"{key}: non-PAGE UI fields forbidden")
    if kind == "DATA" and any(binding["method"] == "POST" for binding in route.get("gatewayApiBindings", [])):
        require(route.get("sideEffectFree") is True, f"{key}: POST DATA must be sideEffectFree")
    if kind == "ACTION":
        require(route.get("sideEffectFree") is None, f"{key}: ACTION sideEffectFree forbidden")

    gateway = unique(route.get("gatewayApiBindings", []), "bindingKey", key + ".gatewayApiBindings")
    service = unique(route.get("servicePepBindings", []), "bindingKey", key + ".servicePepBindings")
    require(gateway and gateway.keys() == service.keys(), f"{key}: public/service binding mismatch")
    for binding_key, public_binding in gateway.items():
        service_binding = service[binding_key]
        require(public_binding["method"] == service_binding["method"], f"{binding_key}: method mismatch")
        require(public_binding.get("pathParameterConstraints", {}) == service_binding.get("pathParameterConstraints", {}), f"{binding_key}: constraint mismatch")
        require(public_binding.get("queryParameterConstraints", {}) == service_binding.get("queryParameterConstraints", {}), f"{binding_key}: query constraint mismatch")
        _validate_binding_constraints(public_binding, binding_key)
        _validate_binding_constraints(service_binding, binding_key)
        service_key = service_binding.get("serviceKey")
        prefix = SERVICE_PATH_PREFIXES.get(service_key)
        require(prefix and service_binding["path"].startswith(prefix), f"{binding_key}: service path grammar mismatch")
        require("/**" not in public_binding["path"] and "/**" not in service_binding["path"], f"{binding_key}: wildcard binding forbidden")

    profiles = route.get("accessProfiles")
    require(isinstance(profiles, list) and profiles, f"{key}: accessProfiles required")
    profile_keys: set[str] = set()
    precedences: set[int] = set()
    for profile in profiles:
        profile_key = profile.get("profileKey")
        precedence = profile.get("precedence")
        require(profile_key and profile_key not in profile_keys, f"{key}: duplicate profile")
        require(isinstance(precedence, int) and precedence not in precedences, f"{key}: duplicate precedence")
        profile_keys.add(profile_key)
        precedences.add(precedence)
        modes = profile.get("activeAccessModes")
        require(isinstance(modes, list) and modes and len(modes) == len(set(modes)) and set(modes) <= ACCESS_MODES, f"{key}/{profile_key}: invalid activeAccessModes")
        require(isinstance(profile.get("readOnly"), bool), f"{key}/{profile_key}: readOnly required")
        access = profile.get("requiredAccess")
        require(isinstance(access, dict), f"{key}/{profile_key}: requiredAccess required")
        if access.get("type") == "CAPABILITY":
            capability_key = access.get("capabilityContractKey")
            require(capability_key in capabilities, f"{key}: unknown capability {capability_key}")
            capability_routes[capability_key].add(key)
        elif access.get("type") == "CAPABILITY_EXPRESSION":
            capability_keys = access.get("capabilityContractKeys")
            require(access.get("mode") in {"ANY", "ALL"} and isinstance(capability_keys, list) and capability_keys, f"{key}: invalid capability expression")
            for capability_key in capability_keys:
                require(capability_key in capabilities, f"{key}: unknown capability {capability_key}")
                capability_routes[capability_key].add(key)
        else:
            require(access.get("type") == "POLICY", f"{key}: invalid access union")
            policy_key = access.get("accessPolicyKey")
            require(policy_key in policies, f"{key}: unknown policy {policy_key}")
            policy_routes[policy_key].add(key)

        target_kinds = profile.get("targetBindingKinds", [])
        predicate_keys = profile.get("predicatePolicyKeys", [])
        require(len(target_kinds) == len(set(target_kinds)) and set(target_kinds) <= TARGET_KINDS, f"{key}/{profile_key}: invalid targets")
        require(len(predicate_keys) == len(set(predicate_keys)), f"{key}/{profile_key}: duplicate predicates")
        covered: set[str] = set()
        for predicate_key in predicate_keys:
            require(predicate_key in predicates, f"{key}: unknown predicate {predicate_key}")
            effective = set(target_kinds) & set(predicates[predicate_key]["targetBindingKinds"])
            require(effective, f"{key}/{profile_key}: predicate target mismatch")
            covered.update(effective)
            predicate_routes[predicate_key].add(key)
        if predicate_keys:
            require(covered == set(target_kinds), f"{key}/{profile_key}: predicate target union does not cover profile")

        projections = profile.get("responseProjectionBindings", [])
        if kind == "ACTION":
            require(not projections, f"{key}/{profile_key}: ACTION projection forbidden")
        else:
            if not projections:
                projections = [
                    {
                        "apiBindingKey": binding_key,
                        "projectionPolicyKey": profile.get(
                            "projectionPolicyKey",
                            f"{key}.{profile_key}.projection.v1",
                        ),
                        "responseSchemaKey": profile.get(
                            "responseSchemaKey",
                            f"{key}.response.v1",
                        ),
                    }
                    for binding_key in gateway
                ]
                profile["responseProjectionBindings"] = projections
            require(
                {projection["apiBindingKey"] for projection in projections} == set(gateway),
                f"{key}/{profile_key}: incomplete response projections",
            )
        profile.pop("projectionPolicyKey", None)
        profile.pop("responseSchemaKey", None)

    elevated_step_up = any(
        profile["requiredAccess"].get("type") == "CAPABILITY"
        and capabilities[profile["requiredAccess"]["capabilityContractKey"]]
            .get("riskTier") in {"HIGH", "CRITICAL"}
        and str(capabilities[profile["requiredAccess"]["capabilityContractKey"]]
            .get("activationPolicy", "")).startswith("STEPUP-")
        for profile in profiles
    )
    if elevated_step_up:
        _validate_step_up_command_binding(route, service)
    else:
        require(route.get("stepUpCommandBindings") is None,
                f"{key}: stepUpCommandBindings forbidden")


def _validate_step_up_command_binding(
    route: dict[str, Any], service_bindings: dict[str, dict[str, Any]]
) -> None:
    key = route["routeContractKey"]
    values = route.get("stepUpCommandBindings")
    require(
        isinstance(values, list) and values,
        f"{key}: exact stepUpCommandBindings required",
    )
    bindings = unique(values, "bindingKey", key + ".stepUpCommandBindings")
    require(bindings.keys() == service_bindings.keys(),
            f"{key}: incomplete step-up command bindings")
    for binding_key, value in bindings.items():
        common_fields = {
            "bindingKey", "targetType", "expectedObjectVersionSource",
            "expectedObjectVersionName", "ownerServiceKey", "audience"
        }
        target_fields = set(value) - common_fields
        require(target_fields in ({"targetIdPathParameter"}, {"targetIdBodyFields"}),
                f"{key}: exactly one step-up target source required")
        require(isinstance(value["targetType"], str) and value["targetType"]
                and value["targetType"].replace("_", "").isalnum()
                and value["targetType"] == value["targetType"].upper(),
                f"{key}: invalid step-up target type")
        require(value["expectedObjectVersionSource"] in {"COMMAND_BODY", "COMMAND_HEADER"}
                and isinstance(value["expectedObjectVersionName"], str)
                and value["expectedObjectVersionName"],
                f"{key}: invalid expected object version binding")
        service = service_bindings[binding_key]
        require(service["serviceKey"] == value["ownerServiceKey"],
                f"{key}: step-up owner service mismatch")
        require(value["audience"] == f"dwp-{value['ownerServiceKey']}-server",
                f"{key}: step-up audience mismatch")
        if "targetIdPathParameter" in value:
            require(isinstance(value["targetIdPathParameter"], str)
                    and value["targetIdPathParameter"],
                    f"{key}: invalid step-up target path parameter")
            placeholder = "{" + value["targetIdPathParameter"] + "}"
            require(placeholder in service["path"],
                    f"{key}: step-up target placeholder mismatch")
        else:
            fields = value["targetIdBodyFields"]
            require(isinstance(fields, list) and fields
                    and all(isinstance(field, str) and field for field in fields)
                    and len(fields) == len(set(fields)),
                    f"{key}: invalid step-up target body fields")


def _validate_binding_constraints(binding: dict[str, Any], key: str) -> None:
    placeholders = set(__import__("re").findall(r"\{([^/{}]+)}", binding["path"]))
    path_constraints = binding.get("pathParameterConstraints", {})
    require(
        isinstance(path_constraints, dict) and set(path_constraints) <= placeholders,
        f"{key}: invalid path constraint keys",
    )
    for parameter, constraint in path_constraints.items():
        _validate_parameter_constraint(constraint, f"{key}/path/{parameter}", False)
    query_constraints = binding.get("queryParameterConstraints", {})
    require(isinstance(query_constraints, dict), f"{key}: query constraints must be an object")
    for parameter, constraint in query_constraints.items():
        require(
            isinstance(parameter, str) and parameter
            and all(character.isalnum() or character in "_-" for character in parameter),
            f"{key}: invalid query parameter name",
        )
        _validate_parameter_constraint(constraint, f"{key}/query/{parameter}", True)


def _validate_parameter_constraint(constraint: Any, label: str, allow_absent: bool) -> None:
    require(isinstance(constraint, dict), f"{label}: constraint must be an object")
    kind = constraint.get("kind")
    if kind == "FIXED":
        require(set(constraint) == {"kind", "value"}
                and isinstance(constraint.get("value"), str)
                and constraint["value"], f"{label}: invalid FIXED constraint")
    elif kind == "ALLOWLIST":
        values = constraint.get("values")
        require(set(constraint) == {"kind", "values"}
                and isinstance(values, list) and values
                and len(values) == len(set(values))
                and all(isinstance(value, str) and value for value in values),
                f"{label}: invalid ALLOWLIST constraint")
    else:
        require(allow_absent and constraint == {"kind": "ABSENT"},
                f"{label}: invalid constraint kind")


def _validate_wire_authority_equivalence(
    routes: dict[str, dict[str, Any]]
) -> None:
    wire: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = defaultdict(list)
    equivalence_members: dict[str, set[str]] = defaultdict(set)
    for route in routes.values():
        equivalence = route.get("authorizationEquivalenceKey")
        if equivalence is not None:
            require(
                isinstance(equivalence, str)
                and equivalence.startswith("wire-authority.")
                and equivalence.endswith(".v1"),
                f"{route['routeContractKey']}: invalid authorizationEquivalenceKey",
            )
            equivalence_members[equivalence].add(route["routeContractKey"])
        for binding in route["gatewayApiBindings"]:
            key = stable_json({
                "method": binding["method"],
                "path": binding["path"],
                "pathParameterConstraints": binding.get("pathParameterConstraints", {}),
                "queryParameterConstraints": binding.get("queryParameterConstraints", {}),
            })
            wire[key].append((route, binding))
    for wire_key, members in wire.items():
        if len(members) == 1:
            continue
        equivalence_keys = {
            route.get("authorizationEquivalenceKey") for route, _ in members
        }
        require(
            len(equivalence_keys) == 1 and None not in equivalence_keys,
            f"duplicate wire binding lacks one authorization equivalence: {wire_key}",
        )
        semantics = {
            stable_json(_wire_authority_semantics(route, binding))
            for route, binding in members
        }
        require(
            len(semantics) == 1,
            f"duplicate wire binding semantic drift: {wire_key}",
        )
    for equivalence, members in equivalence_members.items():
        require(len(members) >= 2, f"{equivalence}: equivalence group must have aliases")


def _wire_authority_semantics(
    route: dict[str, Any], public_binding: dict[str, Any]
) -> dict[str, Any]:
    binding_key = public_binding["bindingKey"]
    service = next(
        binding for binding in route["servicePepBindings"]
        if binding["bindingKey"] == binding_key
    )
    profiles = []
    for profile in route["accessProfiles"]:
        projection = next(
            value for value in profile.get("responseProjectionBindings", [])
            if value["apiBindingKey"] == binding_key
        ) if route["routeKind"] != "ACTION" else None
        profiles.append({
            "profileKey": profile["profileKey"],
            "precedence": profile["precedence"],
            "activeAccessModes": profile["activeAccessModes"],
            "requiredAccess": profile["requiredAccess"],
            "targetBindingKinds": profile["targetBindingKinds"],
            "predicatePolicyKeys": profile["predicatePolicyKeys"],
            "readOnly": profile["readOnly"],
            "projection": None if projection is None else {
                field: value for field, value in projection.items()
                if field != "apiBindingKey"
            },
        })
    return {
        "subject": route["subject"],
        "navigationContextId": route["navigationContextId"],
        "routeKind": route["routeKind"],
        "profiles": profiles,
        "serviceKey": service["serviceKey"],
        "servicePath": service["path"],
        "owner": route["owner"],
    }


def load_source() -> dict[str, Any]:
    try:
        value = json.loads(SOURCE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(
            f"{SOURCE}: expected the dependency-free JSON profile of YAML 1.2: {exc}"
        ) from exc
    require(isinstance(value, dict), "canonical source must be an object")
    return value


def render(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def build_index(snapshots: list[dict[str, Any]]) -> dict[str, Any]:
    versions = []
    for snapshot in snapshots:
        version = snapshot["version"]
        versions.append(
            {
                "version": version,
                "bundleStatus": snapshot["bundleStatus"],
                "checksum": snapshot["checksum"],
                "artifact": VERSIONED_CONTRACT_OUTPUTS[version].name,
                "authSeedArtifact": VERSIONED_AUTH_SEED_OUTPUTS[version].name,
                "counts": {
                    section: len(snapshot[section])
                    for section in SECTION_KEYS
                },
            }
        )
    latest = snapshots[-1]
    index = {
        "schemaVersion": 1,
        "bundleKey": latest["bundleKey"],
        "latestVersion": latest["version"],
        "latestChecksum": latest["checksum"],
        "latestArtifact": VERSIONED_CONTRACT_OUTPUTS[latest["version"]].name,
        "latestAuthSeedArtifact": VERSIONED_AUTH_SEED_OUTPUTS[latest["version"]].name,
        "versions": versions,
        "indexChecksumAlgorithm": "SHA-256",
    }
    index["indexChecksum"] = index_checksum(index)
    return index


def index_checksum(index: dict[str, Any]) -> str:
    payload = copy.deepcopy(index)
    payload.pop("indexChecksum", None)
    return hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest()


def build_platform_canary_pep(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Project the immutable v1 Platform Canary PEP from the registry graph."""
    require(snapshot["version"] == 1, "Platform Canary PEP must project immutable v1")
    routes = [
        copy.deepcopy(route)
        for route in snapshot["routes"]
        if route["subject"].get("productKey") in PLATFORM_CANARY_PRODUCTS
    ]
    require(len(routes) == 33, "Platform Canary PEP must contain exactly 33 product routes")
    require(
        all(
            binding.get("serviceKey") == "platform"
            for route in routes
            for binding in route["servicePepBindings"]
        ),
        "Platform Canary PEP cannot contain a non-platform service binding",
    )

    capability_keys: set[str] = set()
    policy_keys: set[str] = set()
    predicate_keys: set[str] = set()
    for route in routes:
        for profile in route["accessProfiles"]:
            access = profile["requiredAccess"]
            if access["type"] == "CAPABILITY":
                capability_keys.add(access["capabilityContractKey"])
            elif access["type"] == "CAPABILITY_EXPRESSION":
                capability_keys.update(access["capabilityContractKeys"])
            else:
                policy_keys.add(access["accessPolicyKey"])
            predicate_keys.update(profile["predicatePolicyKeys"])

    policies_by_key = {
        policy["accessPolicyKey"]: policy for policy in snapshot["accessPolicies"]
    }
    expression_keys: set[str] = set()
    for policy_key in policy_keys:
        policy = policies_by_key[policy_key]
        expression_key = policy.get("entitlementExpressionKey")
        if expression_key:
            expression_keys.add(expression_key)
        for branch in policy.get("modeBranches") or []:
            capability_keys.update(branch.get("capabilityContractKeys") or [])

    def owned_path_root(path: str) -> str:
        segments = path.strip("/").split("/")
        require(len(segments) >= 2 and segments[0] == "v1", "invalid Platform Canary path")
        width = 3 if segments[1] == "admin" else 2
        require(len(segments) >= width, "incomplete Platform Canary path")
        return "/" + "/".join(segments[:width])

    owned_path_roots = sorted({
        owned_path_root(binding["path"])
        for route in routes
        for binding in route["servicePepBindings"]
    })
    require(len(owned_path_roots) == 4, "Platform Canary owned path root count drift")

    projection = {
        "schemaVersion": 1,
        "projectionKey": "platform-canary-pep-v1",
        "ownerServiceKey": "platform",
        "ownedPathRoots": owned_path_roots,
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "projectedRouteContractCount": len(routes),
        "bindingPairCount": sum(len(route["servicePepBindings"]) for route in routes),
        "routeKindCounts": {
            kind: sum(route["routeKind"] == kind for route in routes)
            for kind in sorted(ROUTE_KINDS)
        },
        "capabilities": [
            copy.deepcopy(capability)
            for capability in snapshot["capabilities"]
            if capability["contractKey"] in capability_keys
        ],
        "accessPolicies": [
            copy.deepcopy(policy)
            for policy in snapshot["accessPolicies"]
            if policy["accessPolicyKey"] in policy_keys
        ],
        "entitlementExpressions": [
            copy.deepcopy(expression)
            for expression in snapshot["entitlementExpressions"]
            if expression["expressionKey"] in expression_keys
        ],
        "predicatePolicies": [
            copy.deepcopy(predicate)
            for predicate in snapshot["predicatePolicies"]
            if predicate["predicatePolicyKey"] in predicate_keys
        ],
        "routes": routes,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    require(projection["bindingPairCount"] == 36, "Platform Canary PEP binding count drift")
    require(
        projection["routeKindCounts"] == {"ACTION": 15, "DATA": 0, "PAGE": 18},
        "Platform Canary PEP route kind count drift",
    )
    require(len(projection["capabilities"]) == 10, "Platform Canary capability closure drift")
    require(len(projection["accessPolicies"]) == 3, "Platform Canary policy closure drift")
    require(len(projection["entitlementExpressions"]) == 2, "Platform Canary expression closure drift")
    require(len(projection["predicatePolicies"]) == 5, "Platform Canary predicate closure drift")
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_platform_canary_pep(projection: dict[str, Any]) -> None:
    document = json.loads(PLATFORM_CANARY_PEP_OUTPUT.read_text(encoding="utf-8"))
    require(document == projection, "Platform Canary PEP generated artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        "Platform Canary PEP checksum mismatch",
    )
    route_keys = {route["routeContractKey"] for route in document["routes"]}
    for descriptor in document["capabilities"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['contractKey']}: Platform Canary reverse-reference escaped projection",
        )
    for descriptor in document["accessPolicies"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['accessPolicyKey']}: Platform Canary reverse-reference escaped projection",
        )
    for descriptor in document["predicatePolicies"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['predicatePolicyKey']}: Platform Canary reverse-reference escaped projection",
        )


def build_approvals_pep(
    snapshot: dict[str, Any],
    service_key: str,
    projection_key: str,
    expected: dict[str, Any],
) -> dict[str, Any]:
    """Project one service-owned W1a Approvals PEP from registry v2."""
    require(snapshot["version"] == 2, "Approvals PEP must project registry v2")
    routes: list[dict[str, Any]] = []
    for source_route in snapshot["routes"]:
        if source_route["subject"].get("productKey") != "approvals":
            continue
        bindings = [
            copy.deepcopy(binding)
            for binding in source_route["servicePepBindings"]
            if binding["serviceKey"] == service_key
        ]
        if not bindings:
            continue
        route = copy.deepcopy(source_route)
        route["servicePepBindings"] = bindings
        routes.append(route)

    capability_keys: set[str] = set()
    policy_keys: set[str] = set()
    predicate_keys: set[str] = set()
    for route in routes:
        for profile in route["accessProfiles"]:
            access = profile["requiredAccess"]
            if access["type"] == "CAPABILITY":
                capability_keys.add(access["capabilityContractKey"])
            elif access["type"] == "CAPABILITY_EXPRESSION":
                capability_keys.update(access["capabilityContractKeys"])
            else:
                policy_keys.add(access["accessPolicyKey"])
            predicate_keys.update(profile["predicatePolicyKeys"])

    policies_by_key = {
        policy["accessPolicyKey"]: policy for policy in snapshot["accessPolicies"]
    }
    expression_keys: set[str] = set()
    for policy_key in policy_keys:
        policy = policies_by_key[policy_key]
        expression_key = policy.get("entitlementExpressionKey")
        if expression_key:
            expression_keys.add(expression_key)
        for branch in policy.get("modeBranches") or []:
            capability_keys.update(branch.get("capabilityContractKeys") or [])

    projection = {
        "schemaVersion": 1,
        "projectionKey": projection_key,
        "ownerServiceKey": service_key,
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "projectedRouteContractCount": len(routes),
        "bindingPairCount": sum(len(route["servicePepBindings"]) for route in routes),
        "routeKindCounts": {
            kind: sum(route["routeKind"] == kind for route in routes)
            for kind in sorted(ROUTE_KINDS)
        },
        "capabilities": [
            copy.deepcopy(capability)
            for capability in snapshot["capabilities"]
            if capability["contractKey"] in capability_keys
        ],
        "accessPolicies": [
            copy.deepcopy(policy)
            for policy in snapshot["accessPolicies"]
            if policy["accessPolicyKey"] in policy_keys
        ],
        "entitlementExpressions": [
            copy.deepcopy(expression)
            for expression in snapshot["entitlementExpressions"]
            if expression["expressionKey"] in expression_keys
        ],
        "predicatePolicies": [
            copy.deepcopy(predicate)
            for predicate in snapshot["predicatePolicies"]
            if predicate["predicatePolicyKey"] in predicate_keys
        ],
        "routes": routes,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    actual = {
        "routes": projection["projectedRouteContractCount"],
        "bindings": projection["bindingPairCount"],
        "routeKinds": projection["routeKindCounts"],
        "capabilities": len(projection["capabilities"]),
        "accessPolicies": len(projection["accessPolicies"]),
        "entitlementExpressions": len(projection["entitlementExpressions"]),
        "predicatePolicies": len(projection["predicatePolicies"]),
    }
    require(actual == expected, f"{projection_key}: projection closure drift: {actual}")
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_approvals_pep(path: pathlib.Path, projection: dict[str, Any]) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    require(document == projection, f"{projection['projectionKey']}: generated artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        f"{projection['projectionKey']}: projection checksum mismatch",
    )
    require(
        all(
            binding["serviceKey"] == projection["ownerServiceKey"]
            for route in document["routes"]
            for binding in route["servicePepBindings"]
        ),
        f"{projection['projectionKey']}: foreign service binding escaped projection",
    )


def build_platform_telemetry_dimensions(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Project closed W0.5 + W1a telemetry dimensions from immutable registry v2."""
    require(snapshot["version"] == 2, "Telemetry dimensions must project registry v2")
    surfaces: dict[tuple[str, str], set[str]] = defaultdict(set)
    for policy in snapshot["accessPolicies"]:
        product_key = policy.get("productKey")
        surface_key = policy.get("surfaceKey")
        if product_key is not None:
            require(product_key != "hcm", "HCM telemetry dimensions are forbidden before W1b")
            surfaces[(product_key, surface_key)]
    for route in snapshot["routes"]:
        subject = route["subject"]
        if subject.get("type") != "PRODUCT":
            continue
        product_key = subject.get("productKey")
        surface_key = subject.get("surfaceKey")
        require(product_key != "hcm", "HCM telemetry dimensions are forbidden before W1b")
        route_ids = surfaces[(product_key, surface_key)]
        if route.get("uiRouteId") is not None:
            route_ids.add(route["uiRouteId"])

    product_documents: list[dict[str, Any]] = []
    for product_key in sorted({key[0] for key in surfaces}):
        product_documents.append({
            "productKey": product_key,
            "surfaces": [
                {
                    "surfaceKey": surface_key,
                    "routeIds": sorted(surfaces[(product_key, surface_key)]),
                }
                for owner, surface_key in sorted(surfaces)
                if owner == product_key
            ],
        })

    projection = {
        "schemaVersion": 1,
        "projectionKey": "platform-telemetry-dimensions-v2",
        "ownerServiceKey": "platform",
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "productCount": len(product_documents),
        "surfaceCount": sum(len(product["surfaces"]) for product in product_documents),
        "routeIdCount": sum(
            len(surface["routeIds"])
            for product in product_documents
            for surface in product["surfaces"]
        ),
        "products": product_documents,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    require(
        (projection["productCount"], projection["surfaceCount"], projection["routeIdCount"])
        == (3, 6, 33),
        "Platform telemetry dimension closure drift",
    )
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_platform_telemetry_dimensions(projection: dict[str, Any]) -> None:
    document = json.loads(
        PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT.read_text(encoding="utf-8")
    )
    require(document == projection, "Platform telemetry dimension artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        "Platform telemetry dimension checksum mismatch",
    )
    registry = document["registryRef"]
    require(
        registry["version"] == 2
        and LOWERCASE_SHA256_PATTERN.fullmatch(registry["sha256"]) is not None,
        "Platform telemetry dimensions must bind exact W1a registry v2",
    )
    route_ids: set[str] = set()
    for product in document["products"]:
        product_key = product["productKey"]
        require(product_key in {"communications", "services", "approvals"},
                "Unknown or pre-W1b telemetry product escaped projection")
        for surface in product["surfaces"]:
            require(
                surface["surfaceKey"].startswith(product_key + "."),
                "Cross-product telemetry surface escaped projection",
            )
            for route_id in surface["routeIds"]:
                require(
                    route_id.startswith(surface["surfaceKey"] + ".")
                    and route_id not in route_ids,
                    "Cross-surface or duplicate telemetry route escaped projection",
                )
                route_ids.add(route_id)


def write_or_check(path: pathlib.Path, content: str, check: bool) -> bool:
    if check:
        try:
            current = path.read_text(encoding="utf-8")
        except OSError:
            print(f"missing generated artifact: {path.relative_to(ROOT)}", file=sys.stderr)
            return False
        if current != content:
            print(f"generated artifact drift: {path.relative_to(ROOT)}", file=sys.stderr)
            return False
        return True
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def verify_artifact_set(index: dict[str, Any]) -> None:
    require(index_checksum(index) == index["indexChecksum"], "index checksum mismatch")
    require(
        [entry["version"] for entry in index["versions"]] == [1, 2, 3]
        and all(entry["bundleStatus"] == "DRAFT" for entry in index["versions"]),
        "seed index must contain only DRAFT versions 1, 2, and 3",
    )
    latest_version = index["latestVersion"]
    latest_contract = VERSIONED_CONTRACT_OUTPUTS[latest_version].read_bytes()
    latest_seed = VERSIONED_AUTH_SEED_OUTPUTS[latest_version].read_bytes()
    require(LATEST_CONTRACT_OUTPUT.read_bytes() == latest_contract, "latest contract alias drift")
    require(LATEST_AUTH_SEED_OUTPUT.read_bytes() == latest_seed, "latest auth seed alias drift")
    require(latest_contract == latest_seed, "latest contract/auth seed bytes differ")
    require(
        json.loads(CONTRACT_INDEX_OUTPUT.read_text(encoding="utf-8")) == index,
        "contract index drift",
    )
    require(
        json.loads(AUTH_SEED_INDEX_OUTPUT.read_text(encoding="utf-8")) == index,
        "auth seed index drift",
    )
    for entry in index["versions"]:
        version = entry["version"]
        contract_path = VERSIONED_CONTRACT_OUTPUTS[version]
        seed_path = VERSIONED_AUTH_SEED_OUTPUTS[version]
        require(contract_path.exists() and seed_path.exists(), f"v{version}: artifact missing")
        require(contract_path.read_bytes() == seed_path.read_bytes(), f"v{version}: artifact bytes differ")
        document = json.loads(contract_path.read_text(encoding="utf-8"))
        require(document["version"] == version, f"v{version}: artifact version mismatch")
        require(document["checksum"] == entry["checksum"], f"v{version}: index checksum drift")
        require(checksum(document) == entry["checksum"], f"v{version}: artifact checksum invalid")


def verify_no_out_of_lineage_artifacts() -> None:
    allowed = {
        *VERSIONED_CONTRACT_OUTPUTS.values(),
        *VERSIONED_AUTH_SEED_OUTPUTS.values(),
        APPROVAL_PILOT_PEP_OUTPUT,
        PLATFORM_APPROVALS_PEP_OUTPUT,
        PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
    }
    candidates = {
        *CONTRACT_DIRECTORY.glob("product-surfaces-v1.bundle-v*.json"),
        *AUTH_SEED_DIRECTORY.glob("product-surfaces-v1.bundle-v*.generated.json"),
        *APPROVAL_PILOT_PEP_OUTPUT.parent.glob("approval-pilot-pep-v*.generated.json"),
        *PLATFORM_APPROVALS_PEP_OUTPUT.parent.glob(
            "platform-approvals-pep-v*.generated.json"
        ),
        *PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT.parent.glob(
            "platform-telemetry-dimensions-v*.generated.json"
        ),
    }
    stale = sorted(path.relative_to(ROOT) for path in candidates - allowed)
    require(not stale, f"out-of-lineage generated artifacts are forbidden: {stale}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if generated artifacts drift")
    args = parser.parse_args()
    try:
        snapshots = build_snapshots(load_source())
        index = build_index(snapshots)
        platform_canary_pep = build_platform_canary_pep(snapshots[0])
        approval_pilot_pep = build_approvals_pep(
            snapshots[1],
            "approval",
            "approval-pilot-pep-v2",
            {
                "routes": 39,
                "bindings": 47,
                "routeKinds": {"ACTION": 20, "DATA": 4, "PAGE": 15},
                "capabilities": 24,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 6,
            },
        )
        platform_approvals_pep = build_approvals_pep(
            snapshots[1],
            "platform",
            "platform-approvals-pep-v2",
            {
                "routes": 2,
                "bindings": 2,
                "routeKinds": {"ACTION": 1, "DATA": 1, "PAGE": 0},
                "capabilities": 0,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
        )
        platform_telemetry_dimensions = build_platform_telemetry_dimensions(
            snapshots[1]
        )
    except ContractError as exc:
        print(f"authorization contract error: {exc}", file=sys.stderr)
        return 1
    outcomes = []
    for snapshot in snapshots:
        version = snapshot["version"]
        content = render(snapshot)
        outcomes.extend(
            [
                write_or_check(VERSIONED_CONTRACT_OUTPUTS[version], content, args.check),
                write_or_check(VERSIONED_AUTH_SEED_OUTPUTS[version], content, args.check),
            ]
        )
    latest_content = render(snapshots[-1])
    index_content = render(index)
    outcomes.extend(
        [
            write_or_check(LATEST_CONTRACT_OUTPUT, latest_content, args.check),
            write_or_check(LATEST_AUTH_SEED_OUTPUT, latest_content, args.check),
            write_or_check(CONTRACT_INDEX_OUTPUT, index_content, args.check),
            write_or_check(AUTH_SEED_INDEX_OUTPUT, index_content, args.check),
            write_or_check(
                PLATFORM_CANARY_PEP_OUTPUT,
                render(platform_canary_pep),
                args.check,
            ),
            write_or_check(
                APPROVAL_PILOT_PEP_OUTPUT,
                render(approval_pilot_pep),
                args.check,
            ),
            write_or_check(
                PLATFORM_APPROVALS_PEP_OUTPUT,
                render(platform_approvals_pep),
                args.check,
            ),
            write_or_check(
                PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
                render(platform_telemetry_dimensions),
                args.check,
            ),
        ]
    )
    if not all(outcomes):
        return 1
    try:
        verify_artifact_set(index)
        verify_no_out_of_lineage_artifacts()
        verify_platform_canary_pep(platform_canary_pep)
        verify_approvals_pep(APPROVAL_PILOT_PEP_OUTPUT, approval_pilot_pep)
        verify_approvals_pep(PLATFORM_APPROVALS_PEP_OUTPUT, platform_approvals_pep)
        verify_platform_telemetry_dimensions(platform_telemetry_dimensions)
    except (ContractError, OSError, json.JSONDecodeError) as exc:
        print(f"authorization artifact error: {exc}", file=sys.stderr)
        return 1
    verb = "verified" if args.check else "generated"
    summary = ", ".join(
        f"v{snapshot['version']}={snapshot['checksum']} routes={len(snapshot['routes'])}"
        for snapshot in snapshots
    )
    print(f"{verb} product-surfaces snapshots: {summary}; latest=v{snapshots[-1]['version']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
