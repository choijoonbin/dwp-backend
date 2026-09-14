#!/usr/bin/env python3
"""Pin the v8 document/USER response-only graph from the official Approval export."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "contracts/product-authorization/approval-document-projections-v8.generated.json"
RUNTIME_OUTPUT = (ROOT / "dwp-approval-server/src/main/resources/product-authorization"
                  / OUTPUT.name)
SCHEMA_FIELDS = {
    "ApprovalDocumentTools": {
        "requestId", "taskId", "requestVersion", "taskVersion", "payloadRevision", "payloadSha256",
        "policyVersion", "commentsVersion", "holdVersion", "legalHold", "copyIdentifier", "history",
        "comment", "print", "jsonExport", "attachments", "evaluatedAt", "policyId", "resourceSetKey",
        "archiveExport", "maxBatchItems", "preservationPending",
    },
    "ApprovalDocumentComments": {"items", "totalElements", "page", "size", "commentsVersion", "evaluatedAt"},
    "ApprovalDocumentPolicy": {"policyId", "resourceSetKey", "version", "published", "pending"},
    "ApprovalDocumentHold": {"requestId", "version", "active", "pending", "journal", "purgeState",
                             "retainUntil", "preservationPending", "purgeEligible"},
    "ApprovalFormUserCandidates": {"formVersionId", "schemaSha256", "fieldPath", "decisionRevision",
                                  "validUntil", "people", "mayBeTruncated", "requestId", "requestVersion"},
}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


GENERATOR = module("document_authorization", "generate-product-authorization-contracts.py")
WORK = module("document_response_graph", "generate-approval-work-projection-contracts.py")
ContractError = WORK.ContractError


def build_contracts(api, snapshot):
    fields = {key: SCHEMA_FIELDS[value[0]]
              for key, value in GENERATOR.APPROVAL_DOCUMENT_V8_SCHEMAS.items()}
    result = WORK.build_contracts(api, snapshot, version=8, route_fields=fields,
                                  projection_key="approval-document-projections-v8")
    for binding in result["bindings"]:
        expected = GENERATOR.APPROVAL_DOCUMENT_V8_SCHEMAS[binding["routeContractKey"]]
        WORK.require((binding["responseSchemaKey"], binding["openApiSchemaSha256"]) == expected,
                     f"{binding['routeContractKey']}: response-only schema hash drift")
    schemas = result["schemas"]
    WORK.require(len(schemas) == 13
                 and result["schemaClosureSha256"] == "26dee9603cb2b12511e0f49a773d5e8511b59a78acb5ace3ce755716735ce535"
                 and result["checksum"] == "8fd5bd6d861a3a45d7029ba485c9244c6b83902301cff89cddd767128b4f9327",
                 "Immutable v8 transitive response graph drift")
    person_ref = schemas["ApprovalFormUserCandidates"]["properties"]["people"]["items"]["$ref"]
    person = schemas[person_ref.removeprefix("#/components/schemas/")]
    WORK.require(person.get("additionalProperties") is False
                 and set(person.get("properties", {})) == {"personPublicId", "displayName"},
                 "Public USER schema must expose only UUID and display name")
    WORK.validate_projection_bindings(snapshot, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--openapi", type=Path, required=True)
    args = parser.parse_args()
    try:
        snapshot = next(value for value in GENERATOR.build_snapshots(GENERATOR.load_source())
                        if value["version"] == 8)
        contracts = build_contracts(json.loads(args.openapi.read_text()), snapshot)
        content = json.dumps(contracts, ensure_ascii=False, indent=2) + "\n"
        for target in (OUTPUT, RUNTIME_OUTPUT):
            if args.write:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            else:
                WORK.require(target.exists() and target.read_text() == content,
                             f"Projection schema artifact drift: {target}")
    except (ContractError, GENERATOR.ContractError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"Approval document projection error: {error}\n")
    print(f"PASS v8 response-only graph: 8 bindings, {len(contracts['schemas'])} schemas, "
          f"{contracts['checksum']}")


if __name__ == "__main__":
    main()
