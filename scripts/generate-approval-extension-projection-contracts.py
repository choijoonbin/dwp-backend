#!/usr/bin/env python3
"""Derive v9 JSON DATA and binary download projections from an actual owner export."""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "contracts/product-authorization/approval-extension-projections-v9.generated.json"
RUNTIME_OUTPUT = ROOT / "dwp-approval-server/src/main/resources/product-authorization" / OUTPUT.name
WORKSPACE_FIELDS = {"catalogAvailability", "catalogPolicyEligible", "formId", "formRevision",
                    "lastEditorUserId", "observedAt", "published", "workingDraft", "workspaceRevision"}
DEFINITIONS = {
    "route.approvals.admin.form-working-draft.data": ("ApprovalFormLifecycleWorkspace", WORKSPACE_FIELDS),
    "route.approvals.admin.form-version-history.data": ("ApprovalFormLifecycleHistory", {"mayBeTruncated", "versions"}),
    "route.approvals.admin.form-version-detail.data": ("ApprovalFormLifecycleVersion", {
        "basePublishedVersionId", "capturedAt", "capturedBy", "createdAt", "createdBy", "formVersionId",
        "lifecycleState", "materialDigest", "metadata", "metadataProvenance", "publishedAt", "publishedBy",
        "route", "schema", "schemaSha256", "sourceVersionId", "versionNumber"}),
    "route.approvals.admin.form-version-diff.data": ("ApprovalFormLifecycleDiff", {
        "fromVersionId", "toVersionId", "fromSchemaSha256", "toSchemaSha256", "changes", "complete",
        "fromMetadataProvenance", "toMetadataProvenance"}),
    "route.approvals.admin.form-publish-review.data": ("ApprovalFormLifecycleReview", {
        "authorityValidUntil", "basePublishedVersionId", "draftFormVersionId", "formId", "formRevision",
        "independentCheckerEligible", "lastEditorUserId", "makerUserId", "reviewContentDigest",
        "schemaSha256", "workspaceRevision"}),
    "route.approvals.work.attachment-upload.data": ("ApprovalAttachmentUpload", {
        "attachmentId", "avState", "expiresAt", "passiveContentState", "reason", "sha256", "sizeBytes",
        "state", "uploadId", "version"}),
    "route.approvals.admin.attachment-policy.data": ("ApprovalAttachmentPolicy", {
        "downloadReadiness", "pending", "pendingMakerUserId", "pendingRevision", "pendingRulesSha256",
        "policyId", "providerReadiness", "publishEligible", "publishReason", "published", "publishedRevision",
        "publishedRulesSha256", "resourceSetKey", "version"}),
    "route.approvals.work.request-attachments.data": ("ApprovalAttachmentAttachments", {
        "allowedMediaTypes", "download", "evaluatedAt", "manifest", "maxFileBytes", "maxFiles", "maxRequestBytes",
        "policyId", "policyVersion", "upload"}),
    "route.approvals.work.task-attachments.data": ("ApprovalAttachmentAttachments", {
        "allowedMediaTypes", "download", "evaluatedAt", "manifest", "maxFileBytes", "maxFiles", "maxRequestBytes",
        "policyId", "policyVersion", "upload"}),
    "route.approvals.admin.policy-impact.data": ("ApprovalPolicyImpactResult", {
        "authority", "observedAt", "policy", "requests", "semanticDiff", "sourceDigest", "status", "tasks", "workflows"}),
    "route.approvals.work.information-command-receipt.data": ("ApprovalInformationCommandReceipt", {
        "status", "roundId", "generation", "requestVersion", "payloadRevision", "payloadSha256", "materialChange"}),
}
BINARY_ROUTE = "route.approvals.work.attachment-download-content.data"
BINARY_SCHEMA_KEY = "ApprovalAttachmentDownloadBytesV1"


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


WORK = module("extension_response_graph", "generate-approval-work-projection-contracts.py")
ContractError = WORK.ContractError
require = WORK.require
sha256 = WORK.sha256


def projection_metadata(binding):
    fields = {"apiBindingKey", "projectionPolicyKey", "responseSchemaKey"}
    if binding["schemaKind"] == "JSON_RECORD":
        fields |= {"schemaVersion", "openApiSchemaSha256", "additionalProperties"}
    return {key: binding[key] for key in sorted(fields)}


def build_contracts(api, snapshot):
    require(snapshot.get("version") == 9, "Extension projections require exact v9")
    components = api.get("components", {}).get("schemas", {})
    graph, binaries, bindings = {}, {}, []

    def schema_ref(value):
        ref = value.get("$ref") if isinstance(value, dict) else None
        require(isinstance(ref, str) and ref.startswith("#/components/schemas/"),
                "Exact component reference is required")
        key = ref.removeprefix("#/components/schemas/")
        require(key in components, f"Unresolved response schema {key}")
        return key, components[key]

    def collect(value):
        if isinstance(value, dict):
            if "$ref" in value:
                key, schema = schema_ref(value)
                if key not in graph:
                    graph[key] = copy.deepcopy(schema)
                    collect(schema)
            for child in value.values():
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    keys = set(DEFINITIONS) | {BINARY_ROUTE}
    routes = [route for route in snapshot["routes"] if route["routeContractKey"] in keys]
    require(len(routes) == len(keys) and {route["routeContractKey"] for route in routes} == keys,
            "Exact twelve extension DATA routes are required")
    for route in routes:
        key = route["routeContractKey"]
        require(route["routeKind"] == "DATA" and len(route["servicePepBindings"]) == 1
                and len(route["accessProfiles"]) == 1, f"{key}: exact DATA profile/binding required")
        service, profile = route["servicePepBindings"][0], route["accessProfiles"][0]
        require(service["serviceKey"] == "approval", f"{key}: unexpected owner")
        operation = api.get("paths", {}).get(service["path"], {}).get(service["method"].lower())
        require(operation is not None, f"{key}: actual owner operation is absent")
        contents = operation.get("responses", {}).get("200", {}).get("content", {})
        require(isinstance(contents, dict) and contents, f"{key}: actual 200 content is required")
        binding = {
            "routeContractKey": key, "profileKey": profile["profileKey"],
            "apiBindingKey": service["bindingKey"],
            "projectionPolicyKey": f"{key}.{profile['profileKey']}.projection.v1",
            "contentTypes": sorted(contents), "method": service["method"], "servicePath": service["path"],
        }
        if key == BINARY_ROUTE:
            expected = {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}
            require(contents == expected and service["method"] == "GET", f"{key}: exact raw binary content required")
            binaries[key] = copy.deepcopy(contents)
            binding.update(schemaKind="RAW_BINARY", responseSchemaKey=BINARY_SCHEMA_KEY,
                           openApiSchemaSha256=sha256(contents))
        else:
            schemas = [content.get("schema") for content in contents.values()]
            require(all(schema == schemas[0] for schema in schemas), f"{key}: ambiguous response media schema")
            _, envelope = schema_ref(schemas[0])
            data = envelope.get("properties", {}).get("data")
            schema_key, schema = schema_ref(data)
            expected_key, fields = DEFINITIONS[key]
            require(schema_key == expected_key and schema.get("type") == "object"
                    and schema.get("additionalProperties") is False
                    and set(schema.get("properties", {})) == fields,
                    f"{key}: actual typed record name, closure or fields drift")
            collect(data)
            binding.update(schemaKind="JSON_RECORD", responseSchemaKey=schema_key,
                           schemaVersion=1, additionalProperties=False, openApiSchemaSha256=sha256(schema))
        bindings.append(binding)
    result = {
        "schemaVersion": 1, "projectionKey": "approval-extension-projections-v9",
        "registryRef": {"bundleKey": snapshot["bundleKey"], "version": 9, "sha256": snapshot["checksum"]},
        "bindingCount": len(keys), "bindings": sorted(bindings, key=lambda item: item["routeContractKey"]),
        "schemas": dict(sorted(graph.items())), "binaryResponses": dict(sorted(binaries.items())),
        "schemaClosureSha256": sha256({"schemas": graph, "binaryResponses": binaries}), "checksumAlgorithm": "SHA-256",
    }
    result["checksum"] = sha256(result)
    return result


def validate_projection_bindings(snapshot, contracts):
    by_key = {binding["routeContractKey"]: binding for binding in contracts["bindings"]}
    for route in snapshot["routes"]:
        if route["routeContractKey"] in by_key:
            require(route["accessProfiles"][0].get("responseProjectionBindings") ==
                    [projection_metadata(by_key[route["routeContractKey"]])],
                    f"{route['routeContractKey']}: source projection metadata drift")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True)
    args = parser.parse_args()
    try:
        generator = module("extension_authorization", "generate-product-authorization-contracts.py")
        snapshot = next(value for value in generator.build_snapshots(generator.load_source()) if value["version"] == 9)
        contracts = build_contracts(json.loads(args.openapi.read_text()), snapshot)
        validate_projection_bindings(snapshot, contracts)
        content = json.dumps(contracts, ensure_ascii=False, indent=2) + "\n"
        for target in (OUTPUT, RUNTIME_OUTPUT):
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            else:
                require(target.exists() and target.read_text() == content, f"Projection artifact drift: {target}")
    except (ContractError, OSError, ValueError, StopIteration) as error:
        parser.exit(1, f"Approval extension projection error: {error}\n")
    print(f"PASS v9 extension response graph: 12 bindings, {len(contracts['schemas'])} schemas, {contracts['checksum']}")


if __name__ == "__main__":
    main()
