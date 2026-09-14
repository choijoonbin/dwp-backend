#!/usr/bin/env python3
"""Build an unsealed append-only wave from the exact closed release10 owner response graph."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "release10_source_projection", ROOT / "scripts/generate-approval-release10-projection-contracts.py")
PROJECTION = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PROJECTION)
OWNER = "Approvals + Security"
PLANNING = "route.approvals.admin.workflow-planning-simulation.data"
SIGNATURE_RECEIPT = "route.approvals.work.signature-command-receipt.data"
SIGNATURE_SOURCE = "predicate.approval.signature-source.v1"
RECEIPT_SOURCE = "predicate.approval.signature-command-receipt.v1"
PLANNING_SOURCE = "predicate.approval.workflow-planning-simulation.v1"
OBJECT_VERSION = "predicate.approval.object-version.v1"
SIGNATURE_READ = "approvals.work.signature.read"
SIGNATURE_UPDATE = "approvals.work.signature.update"
SIGNATURE_SIGN = "approvals.work.signature.sign"
PLANNING_READ = "approvals.admin.workflow-planning-simulation.read"
PLANNING_FORM_READ = "approvals.admin.workflow-planning-form.read"


def capability(key, resource, action, scope, *, management=False, high=False):
    value = {
        "contractKey": key, "productKey": "approvals",
        "surfaceKey": "approvals.admin" if management else "approvals.work",
        "authorityMode": "PERMISSION", "resourceKey": resource, "action": action,
        "resolvedCapabilityCode": resource + ":" + action,
        "responsibilityRequirement": "REQUIRED" if management else "NOT_REQUIRED",
        "requiresProductEntitlement": not management, "scopeResolver": scope,
        "riskTier": "HIGH" if high else "LOW", "sodPolicyId": None,
        "activationPolicy": "STEPUP-MGMT-HIGH-V1" if high else None,
        "mappingVersion": 1, "policyVersion": 1, "routeContractKeys": [],
        "legacySource": None, "sunsetAt": None, "lifecycleState": "ACTIVE", "owner": OWNER,
    }
    if management:
        value["requiredResponsibilityCode"] = "APP_CONFIG_ADMIN"
    return value


def predicate(key, evidence, targets):
    return {
        "predicatePolicyKey": key, "targetBindingKinds": targets, "ownerServiceKey": "approval",
        "parameterSchemaKey": key + ".parameters.v1", "inputEvidenceSchemaKey": evidence,
        "parameterSchema": {"type": "object", "additionalProperties": False, "properties": {}},
        "policyVersion": 1, "routeContractKeys": [], "lifecycleState": "ACTIVE", "owner": OWNER,
    }


def build_wave(api):
    _, responses = PROJECTION.response_graph(api)
    capabilities = [
        capability(SIGNATURE_READ, "ACTION.APPROVAL_REQUEST", "VIEW",
                   "ACTOR_OWNED_SIGNATURE_SOURCE_OR_ORIGINAL_COMMAND"),
        capability(SIGNATURE_UPDATE, "ACTION.APPROVAL_SIGNATURE", "UPDATE", "ACTOR_OWNED_APPROVED_SIGNATURE_SOURCE"),
        capability(SIGNATURE_SIGN, "ACTION.APPROVAL_SIGNATURE", "SIGN", "ACTOR_OWNED_APPROVED_SIGNATURE_SOURCE", high=True),
        capability(PLANNING_READ, "ADMIN.APPROVAL_WORKFLOW", "UPDATE", "APP_RESOURCE_SET:RS_APPROVALS", management=True),
        capability(PLANNING_FORM_READ, "ACTION.APPROVAL_FORM", "VIEW", "APP_RESOURCE_SET:RS_APPROVALS", management=True),
    ]
    routes = []
    for key, (method, path, kind, _) in PROJECTION.OPERATIONS.items():
        work = key.startswith("route.approvals.work.")
        profile_key = "full-work" if work else "full-management"
        predicates, targets = [], ["SELF", "OBJECT"] if work else ["OBJECT"]
        if key == SIGNATURE_RECEIPT:
            profile_key, cap, predicates = "approval.signature.command-receipt.v1", SIGNATURE_READ, [RECEIPT_SOURCE]
        elif work:
            cap = SIGNATURE_SIGN if "signature-sign.action" in key else SIGNATURE_UPDATE if kind == "ACTION" else SIGNATURE_READ
            predicates = [SIGNATURE_SOURCE] + ([OBJECT_VERSION] if kind == "ACTION" else [])
        elif key == PLANNING:
            cap, predicates = PLANNING_READ, [PLANNING_SOURCE]
        else:
            leaf = key.removeprefix("route.approvals.admin.")
            cap = ("approvals.policy.publish" if "policy-publish" in leaf else
                   "approvals.policy.update" if kind == "ACTION" and "policy" in leaf else
                   "approvals.operations.execute" if kind == "ACTION" else
                   "approvals.policy.read" if "policy" in leaf else "approvals.operations.read")
            if kind == "ACTION" and "initialize" not in leaf:
                predicates = [OBJECT_VERSION]
        required = {"type": "CAPABILITY", "capabilityContractKey": cap}
        if key == PLANNING:
            required = {"type": "CAPABILITY_EXPRESSION", "mode": "ALL",
                        "capabilityContractKeys": sorted([PLANNING_FORM_READ, PLANNING_READ])}
        profile = {
            "profileKey": profile_key, "precedence": 300, "activeAccessModes": ["NORMAL", "ELEVATED"],
            "requiredAccess": required, "targetBindingKinds": targets, "predicatePolicyKeys": predicates,
            "readOnly": kind == "DATA" and (not work or key == SIGNATURE_RECEIPT),
        }
        binding_key = key + ".binding.01"
        if kind == "DATA":
            profile["responseProjectionBindings"] = [{
                "apiBindingKey": binding_key, "projectionPolicyKey": f"{key}.{profile_key}.projection.v1",
                "responseSchemaKey": responses[key]["responseSchemaKey"], "schemaVersion": 1,
                "openApiSchemaSha256": responses[key]["openApiSchemaSha256"], "additionalProperties": False,
            }]
        surface = "approvals.work" if work else "approvals.admin"
        binding = {"bindingKey": binding_key, "method": method, "path": path, "pathParameterConstraints": {}}
        route = {
            "routeContractKey": key, "navigationContextId": surface,
            "subject": {"type": "PRODUCT", "productKey": "approvals", "surfaceKey": surface},
            "routeKind": kind, "sideEffectFree": True if kind == "DATA" else None,
            "uiRouteId": None, "uiRoutePattern": None, "accessProfiles": [profile],
            "gatewayApiBindings": [{**binding, "path": "/api/approvals" + path}],
            "servicePepBindings": [{**binding, "serviceKey": "approval"}],
            "owner": OWNER, "policyVersion": 1, "lifecycleState": "ACTIVE",
        }
        high = {
            "route.approvals.work.signature-sign.action": ("APPROVAL_SIGNATURE_REQUEST", "signatureRequestId"),
            "route.approvals.admin.retention-policy-publish.action": ("RETENTION_POLICY", "policyId"),
            "route.approvals.admin.retention-record-claim.action": ("RETENTION_RECORD", "requestId"),
        }.get(key)
        if high:
            route["stepUpCommandBindings"] = [{
                "bindingKey": binding_key, "targetType": high[0], "targetIdPathParameter": high[1],
                "expectedObjectVersionSource": "COMMAND_BODY", "expectedObjectVersionName": "expectedVersion",
                "ownerServiceKey": "approval", "audience": "dwp-approval-server",
            }]
        routes.append(route)
    return {
        "version": 10, "capabilities": capabilities, "accessPolicies": [], "entitlementExpressions": [],
        "predicatePolicies": [predicate(SIGNATURE_SOURCE, "ApprovalSignatureSourceEvidenceV1", ["SELF", "OBJECT"]),
                              predicate(RECEIPT_SOURCE, "ApprovalSignatureCommandReceiptEvidenceV1", ["SELF", "OBJECT"]),
                              predicate(PLANNING_SOURCE, "ApprovalWorkflowPlanningEvidenceV1", ["OBJECT"])],
        "routes": routes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--previous-openapi", type=Path, required=True)
    args = parser.parse_args()
    try:
        api = PROJECTION.load_json(args.openapi)
        PROJECTION.validate_operation_superset(api, PROJECTION.load_json(args.previous_openapi))
        print(json.dumps(build_wave(api), ensure_ascii=False, indent=2))
    except (PROJECTION.ContractError, OSError, ValueError) as error:
        parser.exit(1, f"Unsealed release10 wave error: {error}\n")


if __name__ == "__main__":
    main()
