#!/usr/bin/env python3
"""Derive the five recovery DATA projections from an actual Approval owner export."""

from __future__ import annotations

import argparse
import copy
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "recovery11_release_helpers", ROOT / "scripts/generate-approval-release10-projection-contracts.py")
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)
ContractError, require, sha256 = HELPERS.ContractError, HELPERS.require, HELPERS.sha256
OUTPUT = ROOT / "contracts/product-authorization/approval-recovery11-projections.generated.json"
RUNTIME_OUTPUT = ROOT / "dwp-approval-server/src/main/resources/product-authorization" / OUTPUT.name
RECEIPT_PROFILE = "approval.retention.command-receipt.original-authority.v1"
RECEIPT_FIELDS = {
    "commandId", "operation", "idempotencyKey", "actorUserId", "resourceSetKey", "originalTargetId",
    "resultReferenceId", "requestBodySha256", "originalExpectedVersion", "resultVersion", "status",
    "committedAt", "originAuthorityProfile", "profileVersion",
}
SELECTION_FIELDS = {
    "workflowId", "workflowVersionId", "workflowRevision", "workflowSha256", "managementResourceSetKey",
    "policy", "forms", "selectedFormId", "generatedAt",
}
SCHEMAS = {
    "ApprovalRetentionCommandReceipt": RECEIPT_FIELDS,
    "ApprovalWorkflowPlanningSelection": SELECTION_FIELDS,
    "ApprovalWorkflowPlanningPolicyPin": {"version", "sha256"},
    "ApprovalWorkflowPlanningFormPin": {
        "formId", "formVersionId", "formRevision", "formVersion", "formSchemaSha256"},
}
PREFIX = "route.approvals.admin."
OPERATIONS = {
    PREFIX + "retention-policy-initialization-command.data":
        ("/v1/admin/retention/policy-initialization-commands/{idempotencyKey}", "ApprovalRetentionCommandReceipt"),
    PREFIX + "retention-policy-draft-command.data":
        ("/v1/admin/retention/policies/{policyId}/draft-commands/{idempotencyKey}", "ApprovalRetentionCommandReceipt"),
    PREFIX + "retention-policy-publication-command.data":
        ("/v1/admin/retention/policies/{policyId}/publication-commands/{idempotencyKey}", "ApprovalRetentionCommandReceipt"),
    PREFIX + "retention-record-command.data":
        ("/v1/admin/retention/records/{requestId}/claim-commands/{idempotencyKey}", "ApprovalRetentionCommandReceipt"),
    PREFIX + "workflow-planning-selection.data":
        ("/v1/admin/workflows/{workflowId}/planning-selection", "ApprovalWorkflowPlanningSelection"),
}


def validate_operation_superset(api, baseline):
    def operations(value):
        return {(method, path): operation for path, item in value.get("paths", {}).items()
                for method, operation in item.items()
                if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}}

    actual, previous = operations(api), operations(baseline)
    expected = {("get", path) for path, _ in OPERATIONS.values()}
    require(set(previous) <= set(actual) and set(actual) - set(previous) == expected,
            "Recovery owner export must add exactly five GET operations without removals")
    for key, before in previous.items():
        require({k: v for k, v in before.items() if k != "operationId"} ==
                {k: v for k, v in actual[key].items() if k != "operationId"},
                f"Inherited owner operation changed beyond Springdoc operationId: {key}")
    current_schemas = api.get("components", {}).get("schemas", {})
    require(all(current_schemas.get(key) == value for key, value in
                baseline.get("components", {}).get("schemas", {}).items()),
            "Inherited owner component schema drift")


def response_graph(api):
    components, graph, responses = api.get("components", {}).get("schemas", {}), {}, {}

    def schema_ref(value):
        ref = value.get("$ref") if isinstance(value, dict) else None
        require(isinstance(ref, str) and ref.startswith("#/components/schemas/"),
                "An exact response component reference is required")
        key = ref.removeprefix("#/components/schemas/")
        require(key in components and isinstance(components[key], dict), f"Unresolved response schema: {key}")
        return key, components[key]

    def collect(value):
        if isinstance(value, dict):
            if "$ref" in value:
                key, schema = schema_ref(value)
                types = value.get("type")
                require(not (types == "null" or isinstance(types, list) and "null" in types)
                        or schema == {"type": "null"}, "Response ref/null intersection is not a nullable union")
                if key not in graph:
                    graph[key] = copy.deepcopy(schema)
                    collect(schema)
            types = value.get("type")
            if types == "object" or isinstance(types, list) and "object" in types:
                require(value.get("additionalProperties") is False, "Every recovery response record must be closed")
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    for key, (path, expected_schema) in OPERATIONS.items():
        operation = api.get("paths", {}).get(path, {}).get("get")
        require(isinstance(operation, dict) and "requestBody" not in operation,
                f"{key}: exact bodyless owner GET is required")
        contents = operation.get("responses", {}).get("200", {}).get("content", {})
        require(isinstance(contents, dict) and contents and set(contents) <= {"application/json", "*/*"},
                f"{key}: actual JSON-only 200 response is required")
        envelopes = [entry.get("schema") for entry in contents.values() if isinstance(entry, dict)]
        require(len(envelopes) == len(contents) and all(value == envelopes[0] for value in envelopes),
                f"{key}: ambiguous response media schema")
        _, envelope = schema_ref(envelopes[0])
        data = envelope.get("properties", {}).get("data")
        schema_key, schema = schema_ref(data)
        require(schema_key == expected_schema, f"{key}: native response name drift")
        collect(data)
        responses[key] = {"responseSchemaKey": schema_key, "openApiSchemaSha256": sha256(schema),
                          "contentTypes": sorted(contents)}
    require(set(SCHEMAS) <= set(graph), "Native policy and form pins must be in the selection response graph")
    for key, fields in SCHEMAS.items():
        schema = graph[key]
        require(schema.get("type") == "object" and schema.get("additionalProperties") is False
                and set(schema.get("properties", {})) == fields, f"{key}: closed native DTO fields drift")
    receipt = graph["ApprovalRetentionCommandReceipt"]["properties"]
    for field, values in {
        "operation": ["INITIALIZE_POLICY", "SAVE_POLICY", "PUBLISH_POLICY", "CLAIM_RECORD"],
        "status": ["COMMITTED"],
        "originAuthorityProfile": ["POLICY_UPDATE_TRUSTED", "POLICY_PUBLISH_SIGNED_HIGH_INDEPENDENT_CHECKER",
                                   "RETENTION_RECORD_EXECUTE_SIGNED_HIGH"],
        "profileVersion": ["RETENTION_COMMAND_RECEIPT_STEP_UP_TYPED_JSON_V1"],
    }.items():
        require(receipt[field].get("type") == "string" and receipt[field].get("enum") == values,
                f"Receipt {field}: exact native enum/profile is required")
    require(set(graph["ApprovalWorkflowPlanningSelection"].get("required", [])) == SELECTION_FIELDS,
            "All nine selection fields, including nullable selectedFormId, must be declared")
    return dict(sorted(graph.items())), responses


def projection_metadata(binding):
    keys = {"apiBindingKey", "projectionPolicyKey", "responseSchemaKey", "schemaVersion",
            "openApiSchemaSha256", "additionalProperties"}
    return {key: binding[key] for key in sorted(keys)}


def build_wave(api):
    _, responses = response_graph(api)
    receipt_predicate = "predicate.approval.retention-command-original-authority.v1"
    selection_predicate = "predicate.approval.workflow-planning-selection.v1"
    capabilities = ["approvals.policy.update", "approvals.policy.update", "approvals.policy.publish",
                    "approvals.operations.execute"]
    routes = []
    for index, (key, (path, schema_key)) in enumerate(OPERATIONS.items()):
        receipt = schema_key == "ApprovalRetentionCommandReceipt"
        profile_key = RECEIPT_PROFILE if receipt else "full-management"
        required = {"type": "CAPABILITY", "capabilityContractKey": capabilities[index]} if receipt else {
            "type": "CAPABILITY_EXPRESSION", "mode": "ALL", "capabilityContractKeys": sorted([
                "approvals.admin.workflow-planning-form.read", "approvals.admin.workflow-planning-simulation.read"])}
        binding_key = key + ".binding.01"
        profile = {"profileKey": profile_key, "precedence": 300, "activeAccessModes": ["NORMAL", "ELEVATED"],
                   "requiredAccess": required, "targetBindingKinds": ["OBJECT"],
                   "predicatePolicyKeys": [receipt_predicate if receipt else selection_predicate], "readOnly": True,
                   "responseProjectionBindings": [{"apiBindingKey": binding_key,
                       "projectionPolicyKey": f"{key}.{profile_key}.projection.v1", "schemaVersion": 1,
                       "responseSchemaKey": schema_key, "openApiSchemaSha256": responses[key]["openApiSchemaSha256"],
                       "additionalProperties": False}]}
        binding = {"bindingKey": binding_key, "method": "GET", "path": path, "pathParameterConstraints": {}}
        routes.append({"routeContractKey": key, "navigationContextId": "approvals.admin",
                       "subject": {"type": "PRODUCT", "productKey": "approvals", "surfaceKey": "approvals.admin"},
                       "routeKind": "DATA", "sideEffectFree": True, "uiRouteId": None, "uiRoutePattern": None,
                       "accessProfiles": [profile],
                       "gatewayApiBindings": [{**binding, "path": "/api/approvals" + path}],
                       "servicePepBindings": [{**binding, "serviceKey": "approval"}],
                       "owner": "Approvals + Security", "policyVersion": 1, "lifecycleState": "ACTIVE"})
    predicates = [{"predicatePolicyKey": key, "targetBindingKinds": ["OBJECT"], "ownerServiceKey": "approval",
                   "parameterSchemaKey": key + ".parameters.v1", "inputEvidenceSchemaKey": evidence,
                   "parameterSchema": {"type": "object", "additionalProperties": False, "properties": {}},
                   "policyVersion": 1, "routeContractKeys": [], "lifecycleState": "ACTIVE", "owner": "Approvals + Security"}
                  for key, evidence in [(receipt_predicate, "ApprovalRetentionOriginalCommandReceiptEvidenceV1"),
                                        (selection_predicate, "ApprovalWorkflowPlanningEvidenceV1")]]
    return {"version": 11, "capabilities": [], "accessPolicies": [], "entitlementExpressions": [],
            "predicatePolicies": predicates, "routes": routes}


def build_contracts(api, snapshot, *, baseline):
    require(snapshot.get("version") == 11 and baseline.get("version") == 10, "Exact recovery11/previous10 required")
    previous = {route["routeContractKey"] for route in baseline["routes"]}
    actual = [route["routeContractKey"] for route in snapshot["routes"]]
    require(len(actual) == len(set(actual)) and set(actual) == previous | set(OPERATIONS)
            and not previous & set(OPERATIONS), "Registry must append exactly five recovery DATA routes")
    graph, responses = response_graph(api)
    bindings = []
    for route in snapshot["routes"]:
        key = route["routeContractKey"]
        if key not in OPERATIONS:
            continue
        path, schema_key = OPERATIONS[key]
        require(route.get("routeKind") == "DATA" and route.get("sideEffectFree") is True
                and len(route.get("accessProfiles", [])) == 1 and len(route.get("servicePepBindings", [])) == 1
                and len(route.get("gatewayApiBindings", [])) == 1 and not route.get("stepUpCommandBindings"),
                f"{key}: exact read-only DATA route, without mutation step-up bindings, required")
        service, gateway, profile = route["servicePepBindings"][0], route["gatewayApiBindings"][0], route["accessProfiles"][0]
        require(service.get("serviceKey") == "approval" and service.get("method") == gateway.get("method") == "GET"
                and service.get("path") == path and gateway.get("path") == "/api/approvals" + path
                and service.get("bindingKey") == gateway.get("bindingKey") and profile.get("readOnly") is True,
                f"{key}: native/public read binding drift")
        require(profile.get("profileKey") == (RECEIPT_PROFILE if schema_key == "ApprovalRetentionCommandReceipt"
                else "full-management"), f"{key}: typed read authority profile drift")
        bindings.append({"routeContractKey": key, "profileKey": profile["profileKey"],
                         "apiBindingKey": service["bindingKey"],
                         "projectionPolicyKey": f"{key}.{profile['profileKey']}.projection.v1",
                         "schemaKind": "JSON_RECORD", "schemaVersion": 1, "additionalProperties": False,
                         "method": "GET", "servicePath": path, **responses[key]})
    result = {"schemaVersion": 1, "projectionKey": "approval-recovery11-projections",
              "registryRef": {"bundleKey": snapshot["bundleKey"], "version": 11, "sha256": snapshot["checksum"]},
              "bindingCount": 5, "bindings": sorted(bindings, key=lambda value: value["routeContractKey"]),
              "schemas": graph, "schemaClosureSha256": sha256(graph), "checksumAlgorithm": "SHA-256"}
    result["checksum"] = sha256(result)
    return result


def validate_projection_bindings(snapshot, contracts):
    by_key = {binding["routeContractKey"]: binding for binding in contracts["bindings"]}
    require(set(by_key) == set(OPERATIONS) and len(contracts["bindings"]) == 5,
            "Exactly five recovery DATA projections are required")
    for route in snapshot["routes"]:
        key = route["routeContractKey"]
        if key in by_key:
            require(route["accessProfiles"][0].get("responseProjectionBindings") == [projection_metadata(by_key[key])],
                    f"{key}: source response projection metadata drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect-source", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--previous-openapi", type=Path, required=True)
    args = parser.parse_args()
    try:
        api = HELPERS.load_json(args.openapi)
        validate_operation_superset(api, HELPERS.load_json(args.previous_openapi))
        if args.inspect_source:
            graph, _ = response_graph(api)
            print(f"PASS unsealed recovery11 response inspection: 5 GET operations, {len(graph)} schemas, {sha256(graph)}")
            return
        spec = importlib.util.spec_from_file_location("recovery11_registry", ROOT / "scripts/generate-product-authorization-contracts.py")
        registry = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(registry)
        snapshots = registry.build_snapshots(registry.load_source())
        by_version = {snapshot["version"]: snapshot for snapshot in snapshots}
        result = build_contracts(api, by_version[11], baseline=by_version[10])
        validate_projection_bindings(by_version[11], result)
        expected = registry.render(result)
        if not all([registry.write_or_check(path, expected, args.check) for path in (OUTPUT, RUNTIME_OUTPUT)]):
            parser.exit(1)
    except (ContractError, OSError, ValueError, KeyError) as error:
        parser.exit(1, f"Recovery11 projection error: {error}\n")


if __name__ == "__main__":
    main()
