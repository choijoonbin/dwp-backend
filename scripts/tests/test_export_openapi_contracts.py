from __future__ import annotations

import copy
import json
import runpy
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXPORTER = runpy.run_path(str(ROOT / "scripts" / "export-openapi-contracts.py"))


class ExportOpenApiContractsTest(unittest.TestCase):
    def test_gateway_openapi_projection_consumes_append_only_v9_registry(self) -> None:
        registry_path = EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"]
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        dwaion_routes = [
            route
            for route in registry["routes"]
            if route["subject"].get("productKey") == "dwaion"
        ]

        self.assertEqual(EXPORTER["PRODUCT_AUTHORIZATION_VERSION"], 9)
        self.assertEqual(registry["version"], 9)
        self.assertEqual(len(dwaion_routes), 95)
        self.assertEqual(
            sum(route["routeKind"] == "ACTION" for route in dwaion_routes), 57
        )
        approval_routes = [route for route in registry["routes"]
                           if route["subject"].get("productKey") == "approvals"]
        self.assertEqual(len(approval_routes), 93)
        self.assertEqual(sum(route["routeKind"] == "ACTION" for route in approval_routes), 48)

    def test_document_policy_uuid_operations_receive_scope_and_revision(self) -> None:
        base = "/api/approvals/v1/admin/document-tools/policies/{policyId}"
        document = {"paths": {
            base + "/draft": {"put": {"parameters": [{"name": "policyId", "in": "path", "required": True}]}},
            base + "/publish": {"post": {"parameters": [{"name": "policyId", "in": "path", "required": True}]}}
        }}
        EXPORTER["add_product_governance_contract"](document)
        for path, method in [(base + "/draft", "put"), (base + "/publish", "post")]:
            parameters = document["paths"][path][method]["parameters"]
            self.assertEqual(sum(parameter["name"] == "contextScopeKey" for parameter in parameters), 1)
            self.assertEqual(sum(parameter["name"] == "X-DWP-Expected-Decision-Revision" for parameter in parameters), 1)
            self.assertTrue(next(parameter for parameter in parameters if parameter["name"] == "policyId")["required"])
        operations = EXPORTER["product_governed_operations"]()
        self.assertNotIn(("/api/approvals/v1/admin/document-tools/policy/draft", "put"), operations)
        self.assertNotIn(("/api/approvals/v1/admin/document-tools/policy/publish", "post"), operations)

    def test_gateway_openapi_documents_scope_and_revision_for_a02_mutation(self) -> None:
        path = "/api/platform/v1/admin/dwaion/agents/{entryKey}/revisions/{revisionNumber}/activate"
        document = {
            "paths": {
                path: {
                    "post": {
                        "parameters": [
                            {"name": "entryKey", "in": "path", "required": True},
                            {"name": "revisionNumber", "in": "path", "required": True},
                        ]
                    }
                }
            }
        }

        EXPORTER["add_product_governance_contract"](document)
        parameters = document["paths"][path]["post"]["parameters"]
        by_name = {parameter["name"]: parameter for parameter in parameters}

        self.assertEqual(
            by_name["contextScopeKey"],
            copy.deepcopy(EXPORTER["SCOPE_SELECTION_PARAMETER"]),
        )
        self.assertEqual(
            by_name["X-DWP-Expected-Decision-Revision"],
            copy.deepcopy(EXPORTER["EXPECTED_DECISION_REVISION_PARAMETER"]),
        )


if __name__ == "__main__":
    unittest.main()
