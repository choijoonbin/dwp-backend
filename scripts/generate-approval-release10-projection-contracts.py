#!/usr/bin/env python3
"""Derive release10 response projections only from a closed actual owner export."""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "contracts/product-authorization/approval-release10-projections.generated.json"
RUNTIME_OUTPUT = ROOT / "dwp-approval-server/src/main/resources/product-authorization" / OUTPUT.name


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


WORK = module("release10_response_graph", "generate-approval-work-projection-contracts.py")
ContractError, require, sha256 = WORK.ContractError, WORK.require, WORK.sha256
POLICY_FIELDS = {"pending", "pendingMakerUserId", "pendingRevision", "pendingRulesSha256", "policyId",
                 "publishEligible", "publishReason", "published", "publishedRevision", "publishedRulesSha256",
                 "resourceSetKey", "runtimeReadiness", "version"}
RECORD_FIELDS = {"claimEligible", "claimId", "claimReason", "eligibleAfter", "holdVersion", "inventoryRows",
                 "inventorySha256", "inventoryTables", "objectCount", "policyId", "policyVersion", "requestId",
                 "resourceSetKey", "runtimeReadiness", "state", "version"}
CLAIM_FIELDS = {"claimId", "executionClaimId", "foreignCopyState", "foreignRequests", "inventorySha256",
                "reason", "requestId", "resourceSetKey", "runtimeReadiness", "state",
                "verifiedAcknowledgements", "version"}
CONTEXT_FIELDS = {"artifact", "consentRequired", "evaluatedAt", "signerKind", "signingReadiness", "source",
                  "sourceDigest", "terms"}
CEREMONY_FIELDS = {"artifact", "consentReceiptId", "evidence", "expiresAt", "preservationState", "requestId",
                   "signatureRequestId", "signerKind", "source", "sourceDigest", "state", "terms", "version"}
RECEIPT_FIELDS = {"ceremony", "commandReceiptId", "committedAt", "outcome"}
COMMAND_RECEIPT_FIELDS = {"committedAt", "eventSequence", "originalOperation", "receiptId", "requestId",
                          "resultState", "resultVersion", "signatureRequestId", "sourceCurrent"}
COMMAND_RECEIPT_TYPES = {
    "committedAt": ("string", "date-time"), "eventSequence": ("integer", "int64"),
    "originalOperation": ("string", None), "receiptId": ("string", "uuid"),
    "requestId": ("string", "uuid"), "resultState": ("string", None),
    "resultVersion": ("integer", "int64"), "signatureRequestId": ("string", "uuid"),
    "sourceCurrent": ("boolean", None),
}
PLANNING_FIELDS = {"authorityRevision", "expiresAt", "mode", "requesterExclusion", "runtimeEligibility",
                   "snapshotSha256", "stages"}
SCHEMAS = {
    "ApprovalRetentionPolicy": POLICY_FIELDS,
    "ApprovalRetentionRecord": RECORD_FIELDS,
    "ApprovalRetentionClaim": CLAIM_FIELDS,
    "ApprovalSignatureContext": CONTEXT_FIELDS,
    "ApprovalSignatureCeremony": CEREMONY_FIELDS,
    "ApprovalSignatureAudit": {"items", "truncated"},
    "ApprovalSignatureReceipt": RECEIPT_FIELDS,
    "ApprovalSignatureCommandReceiptMetadata": COMMAND_RECEIPT_FIELDS,
    "ApprovalWorkflowPlanningResult": PLANNING_FIELDS,
}

# The closed release group includes ACTION responses, but only DATA receives projection metadata.
OPERATIONS = {
    "route.approvals.admin.retention-policy.data":
        ("GET", "/v1/admin/retention/policy", "DATA", "ApprovalRetentionPolicy"),
    "route.approvals.admin.retention-policy-initialize.action":
        ("POST", "/v1/admin/retention/policies", "ACTION", "ApprovalRetentionPolicy"),
    "route.approvals.admin.retention-policy-draft.action":
        ("PUT", "/v1/admin/retention/policies/{policyId}/draft", "ACTION", "ApprovalRetentionPolicy"),
    "route.approvals.admin.retention-policy-publish.action":
        ("POST", "/v1/admin/retention/policies/{policyId}/publish", "ACTION", "ApprovalRetentionPolicy"),
    "route.approvals.admin.retention-record.data":
        ("GET", "/v1/admin/retention/records/{requestId}", "DATA", "ApprovalRetentionRecord"),
    "route.approvals.admin.retention-record-claim.action":
        ("POST", "/v1/admin/retention/records/{requestId}/claims", "ACTION", "ApprovalRetentionClaim"),
    "route.approvals.admin.retention-claim.data":
        ("GET", "/v1/admin/retention/claims/{claimId}", "DATA", "ApprovalRetentionClaim"),
    "route.approvals.admin.workflow-planning-simulation.data":
        ("POST", "/v1/admin/workflows/{workflowId}/versions/{versionId}/simulation", "DATA",
         "ApprovalWorkflowPlanningResult"),
    "route.approvals.work.signature-context.data":
        ("GET", "/v1/requests/{requestId}/signature-context", "DATA", "ApprovalSignatureContext"),
    "route.approvals.work.signature-request-create.action":
        ("POST", "/v1/requests/{requestId}/signature-requests", "ACTION", "ApprovalSignatureReceipt"),
    "route.approvals.work.signature-request.data":
        ("GET", "/v1/signature-requests/{signatureRequestId}", "DATA", "ApprovalSignatureCeremony"),
    "route.approvals.work.signature-consent.action":
        ("POST", "/v1/signature-requests/{signatureRequestId}/consents", "ACTION", "ApprovalSignatureReceipt"),
    "route.approvals.work.signature-sign.action":
        ("POST", "/v1/signature-requests/{signatureRequestId}/sign", "ACTION", "ApprovalSignatureReceipt"),
    "route.approvals.work.signature-cancel.action":
        ("POST", "/v1/signature-requests/{signatureRequestId}/cancel", "ACTION", "ApprovalSignatureReceipt"),
    "route.approvals.work.signature-audit.data":
        ("GET", "/v1/signature-requests/{signatureRequestId}/audit", "DATA", "ApprovalSignatureAudit"),
    "route.approvals.work.signature-command-receipt.data":
        ("GET", "/v1/signature-command-receipts/{idempotencyKey}", "DATA",
         "ApprovalSignatureCommandReceiptMetadata"),
}
DATA_KEYS = {key for key, (_, _, kind, _) in OPERATIONS.items() if kind == "DATA"}


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    return json.loads(path.read_text(), object_pairs_hook=unique)


def projection_metadata(binding):
    keys = {"apiBindingKey", "projectionPolicyKey", "responseSchemaKey", "schemaVersion",
            "openApiSchemaSha256", "additionalProperties"}
    return {key: binding[key] for key in sorted(keys)}


def validate_operation_superset(api, baseline):
    def operations(value):
        return {(method.upper(), path): operation
                for path, item in value.get("paths", {}).items()
                for method, operation in item.items()
                if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}}

    current, previous = operations(api), operations(baseline)
    expected = {(method, path) for method, path, _, _ in OPERATIONS.values()}
    require(set(previous) <= set(current) and set(current) - set(previous) == expected,
            "Actual owner export must add exactly sixteen operations without removals")
    for key, before in previous.items():
        after = current[key]
        require({field: value for field, value in before.items() if field != "operationId"} ==
                {field: value for field, value in after.items() if field != "operationId"},
                f"Inherited owner operation changed beyond Springdoc operationId: {key}")
    old_schemas = baseline.get("components", {}).get("schemas", {})
    new_schemas = api.get("components", {}).get("schemas", {})
    require(all(key in new_schemas and value == new_schemas[key] for key, value in old_schemas.items()),
            "Inherited owner response/input component schema drift")


def response_graph(api):
    components = api.get("components", {}).get("schemas", {})
    graph, responses = {}, {}

    def schema_ref(value):
        ref = value.get("$ref") if isinstance(value, dict) else None
        require(isinstance(ref, str) and ref.startswith("#/components/schemas/"),
                "Exact response component reference is required")
        key = ref.removeprefix("#/components/schemas/")
        require(key in components and isinstance(components[key], dict), f"Unresolved response schema {key}")
        return key, components[key]

    def collect(value):
        if isinstance(value, dict):
            if "$ref" in value:
                key, schema = schema_ref(value)
                declared_type = value.get("type")
                nullable_type = declared_type == "null" or (
                    isinstance(declared_type, list) and "null" in declared_type)
                require(not nullable_type or schema == {"type": "null"},
                    "A response $ref plus null type is an intersection, not an explicit nullable union")
                if key not in graph:
                    graph[key] = copy.deepcopy(schema)
                    collect(schema)
            declared_type = value.get("type")
            if declared_type == "object" or isinstance(declared_type, list) and "object" in declared_type:
                require(value.get("additionalProperties") is False,
                        "Every actual response record in the release10 graph must be closed")
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    for key, (method, path, _, expected_schema) in OPERATIONS.items():
        operation = api.get("paths", {}).get(path, {}).get(method.lower())
        require(isinstance(operation, dict), f"{key}: actual exact owner operation is absent")
        contents = operation.get("responses", {}).get("200", {}).get("content", {})
        require(isinstance(contents, dict) and contents, f"{key}: actual 200 content is required")
        require(set(contents) <= {"application/json", "*/*"}, f"{key}: JSON-only response media required")
        schemas = [content.get("schema") for content in contents.values() if isinstance(content, dict)]
        require(len(schemas) == len(contents) and all(schema == schemas[0] for schema in schemas),
                f"{key}: ambiguous response media schema")
        _, envelope = schema_ref(schemas[0])
        data = envelope.get("properties", {}).get("data")
        schema_key, schema = schema_ref(data)
        require(schema_key == expected_schema and schema.get("type") == "object"
                and schema.get("additionalProperties") is False
                and set(schema.get("properties", {})) == SCHEMAS[expected_schema],
                f"{key}: actual typed response name, closure or fields drift")
        if key.endswith("signature-command-receipt.data"):
            require(set(schema.get("required", [])) == COMMAND_RECEIPT_FIELDS,
                    f"{key}: all nine metadata fields must be required")
            properties = schema["properties"]
            require(all(properties[field].get("type") == kind and properties[field].get("format") == fmt
                        and not set(properties[field]) & {"$ref", "oneOf", "anyOf", "allOf", "nullable"}
                        for field, (kind, fmt) in COMMAND_RECEIPT_TYPES.items())
                    and properties["originalOperation"].get("enum") == ["CREATE", "CONSENT", "SIGN", "CANCEL"]
                    and properties["resultState"].get("enum") == ["AWAITING_CONSENT", "CONSENTED", "ATTESTED", "CANCELLED"],
                    f"{key}: exact nonnullable metadata types, formats and operation/state enums required")
        if key.endswith("workflow-planning-simulation.data"):
            require(set(schema.get("required", [])) == PLANNING_FIELDS,
                    f"{key}: all seven planning result fields must be required")
            require(schema["properties"]["mode"].get("enum") == ["ROLE_POOL_PREVIEW"]
                    and schema["properties"]["runtimeEligibility"].get("enum") == ["NOT_EVALUATED"]
                    and schema["properties"]["requesterExclusion"].get("enum") == ["NOT_EVALUATED"],
                    f"{key}: planning preview must not imply runtime or requester eligibility")
        if expected_schema == "ApprovalSignatureCeremony":
            evidence = schema["properties"]["evidence"]
            union = evidence.get("oneOf", evidence.get("anyOf")) if isinstance(evidence, dict) else None
            def null_branch(branch):
                if branch == {"type": "null"}:
                    return True
                if isinstance(branch, dict) and set(branch) == {"$ref"}:
                    _, target = schema_ref(branch)
                    return target == {"type": "null"}
                return False

            evidence_ref = {"$ref": "#/components/schemas/ApprovalSignatureEvidence"}
            require(isinstance(union, list) and len(union) == 2
                    and "$ref" not in evidence and "type" not in evidence and "allOf" not in evidence
                    and ("oneOf" in evidence) != ("anyOf" in evidence)
                    and sum(branch == evidence_ref for branch in union) == 1
                    and sum(null_branch(branch) for branch in union) == 1
                    and "evidence" not in schema.get("required", []),
                    "ApprovalSignatureCeremony.evidence must explicitly allow the typed evidence or null")
        collect(data)
        responses[key] = {"responseSchemaKey": schema_key, "openApiSchemaSha256": sha256(schema),
                          "contentTypes": sorted(contents)}
    return dict(sorted(graph.items())), responses


def build_contracts(api, snapshot, *, baseline=None):
    require(snapshot.get("version") == 10, "Release10 projections require exact v10")
    if baseline is None:
        baseline = load_json(ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v9.json")
    require(baseline.get("version") == 9, "Exact previous v9 release is required")
    previous = {route["routeContractKey"] for route in baseline["routes"]}
    actual = [route["routeContractKey"] for route in snapshot["routes"]]
    require(len(actual) == len(set(actual)) and set(actual) == previous | set(OPERATIONS)
            and not previous & set(OPERATIONS), "Exact v9 plus sixteen release10 routes required")
    routes = [route for route in snapshot["routes"] if route["routeContractKey"] in OPERATIONS]
    require(len(routes) == 16 and {route["routeContractKey"] for route in routes} == set(OPERATIONS),
            "Exact sixteen release10 routes are required")
    graph, responses = response_graph(api)
    bindings = []
    for route in routes:
        key = route["routeContractKey"]
        method, path, kind, _ = OPERATIONS[key]
        require(route.get("routeKind") == kind
                and route.get("sideEffectFree") is (True if kind == "DATA" else None)
                and len(route.get("servicePepBindings", [])) == 1
                and len(route.get("gatewayApiBindings", [])) == 1
                and len(route.get("accessProfiles", [])) == 1, f"{key}: exact route kind/profile/binding required")
        service, gateway = route["servicePepBindings"][0], route["gatewayApiBindings"][0]
        require(service.get("serviceKey") == "approval" and service.get("method") == method
                and service.get("path") == path and gateway.get("method") == method
                and gateway.get("path") == "/api/approvals" + path
                and service.get("bindingKey") == gateway.get("bindingKey"),
                f"{key}: canonical native/public binding drift")
        if key not in DATA_KEYS:
            require(not route["accessProfiles"][0].get("responseProjectionBindings"),
                    f"{key}: ACTION must not borrow DATA projection metadata")
            continue
        profile = route["accessProfiles"][0]
        bindings.append({
            "routeContractKey": key, "profileKey": profile["profileKey"],
            "apiBindingKey": service["bindingKey"],
            "projectionPolicyKey": f"{key}.{profile['profileKey']}.projection.v1",
            "schemaKind": "JSON_RECORD", "schemaVersion": 1, "additionalProperties": False,
            "method": method, "servicePath": path, **responses[key],
        })
    result = {
        "schemaVersion": 1, "projectionKey": "approval-release10-projections",
        "registryRef": {"bundleKey": snapshot["bundleKey"], "version": 10, "sha256": snapshot["checksum"]},
        "bindingCount": len(DATA_KEYS), "bindings": sorted(bindings, key=lambda value: value["routeContractKey"]),
        "schemas": graph, "schemaClosureSha256": sha256(graph), "checksumAlgorithm": "SHA-256",
    }
    result["checksum"] = sha256(result)
    return result


def validate_projection_bindings(snapshot, contracts):
    by_key = {binding["routeContractKey"]: binding for binding in contracts["bindings"]}
    require(set(by_key) == DATA_KEYS and len(contracts["bindings"]) == 8, "Exact eight DATA projections required")
    for route in snapshot["routes"]:
        key = route["routeContractKey"]
        if key in by_key:
            require(route["accessProfiles"][0].get("responseProjectionBindings") ==
                    [projection_metadata(by_key[key])], f"{key}: source projection metadata drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect-source", action="store_true", help="Validate actual responses without emitting a seal")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--previous-openapi", type=Path,
                        help="Frozen v9 owner export for exact superset/component preservation")
    args = parser.parse_args()
    try:
        api = load_json(args.openapi)
        if args.previous_openapi:
            validate_operation_superset(api, load_json(args.previous_openapi))
        if args.inspect_source:
            graph, _ = response_graph(api)
            print(f"PASS unsealed release10 response inspection: 16 operations, {len(graph)} schemas, {sha256(graph)}")
            return
        generator = module("release10_authorization", "generate-product-authorization-contracts.py")
        snapshot = next(value for value in generator.build_snapshots(generator.load_source()) if value["version"] == 10)
        contracts = build_contracts(api, snapshot)
        validate_projection_bindings(snapshot, contracts)
        content = json.dumps(contracts, ensure_ascii=False, indent=2) + "\n"
        for target in (OUTPUT, RUNTIME_OUTPUT):
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            else:
                require(target.exists() and target.read_text() == content, f"Projection artifact drift: {target}")
    except (ContractError, OSError, ValueError, StopIteration) as error:
        parser.exit(1, f"Approval release10 projection error: {error}\n")
    print(f"PASS v10 response projections: 8 bindings, {len(contracts['schemas'])} schemas, {contracts['checksum']}")


if __name__ == "__main__":
    main()
