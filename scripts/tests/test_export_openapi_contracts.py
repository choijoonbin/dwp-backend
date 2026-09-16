from __future__ import annotations

import copy
import json
import runpy
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXPORTER = runpy.run_path(str(ROOT / "scripts" / "export-openapi-contracts.py"))


class ExportOpenApiContractsTest(unittest.TestCase):
    def test_widget_version_transition_schema_does_not_replace_existing_transition_contracts(
        self,
    ) -> None:
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        def request_schema(path: str) -> str:
            return gateway["paths"][path]["post"]["requestBody"]["content"][
                "application/json"
            ]["schema"]["$ref"]

        existing_transition = "#/components/schemas/platform_TransitionRequest"
        widget_transition = (
            "#/components/schemas/platform_WidgetVersionTransitionRequest"
        )
        for path in (
            "/api/platform/v1/admin/localization/revisions/{revisionId}/publish",
            "/api/platform/v1/admin/localization/revisions/{revisionId}/submit",
            "/api/platform/v1/admin/services/requests/{requestId}/transition",
        ):
            self.assertEqual(request_schema(path), existing_transition)
        for path in (
            "/api/provider/v1/admin/widget-definition-versions/{versionId}/submit",
            "/api/provider/v1/admin/widget-definition-versions/{versionId}/rework",
        ):
            self.assertEqual(request_schema(path), widget_transition)

        schemas = gateway["components"]["schemas"]
        self.assertEqual(
            schemas["platform_TransitionRequest"]["required"],
            ["targetStatus", "version"],
        )
        self.assertEqual(
            schemas["platform_WidgetVersionTransitionRequest"]["required"],
            ["expectedVersion", "reasonCode", "reasonText"],
        )

    def test_widget_registry_provider_control_plane_has_one_public_identity_plane(self) -> None:
        platform_path = EXPORTER["platform_path"]
        self.assertEqual(
            platform_path("/v1/admin/widget-definitions"),
            "/api/provider/v1/admin/widget-definitions",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-definition-versions/123/publish"),
            "/api/provider/v1/admin/widget-definition-versions/123/publish",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-registry/readiness"),
            "/api/provider/v1/admin/widget-registry/readiness",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-catalog"),
            "/api/platform/v1/admin/widget-catalog",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-policies/123"),
            "/api/platform/v1/admin/widget-policies/123",
        )

    def test_home_v2_is_the_only_exported_platform_v2_surface(self) -> None:
        platform_path = EXPORTER["platform_path"]
        self.assertEqual(platform_path("/v2/home"), "/api/platform/v2/home")
        self.assertEqual(
            platform_path("/v2/home/widget-actions:execute"),
            "/api/platform/v2/home/widget-actions:execute",
        )
        self.assertIsNone(platform_path("/v2/admin/unsafe"))
        operations = EXPORTER["product_governed_operations"]()
        self.assertFalse(operations[("/api/platform/v2/home", "get")])
        self.assertTrue(operations[(
            "/api/platform/v2/home/widget-actions:execute", "post"
        )])

    def test_approval_and_gateway_snapshots_publish_exact_signature_contract(self) -> None:
        owner = json.loads(
            (ROOT / "contracts/openapi/approval.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        EXPORTER["validate_approval_signature_operations"](owner)
        EXPORTER["validate_approval_signature_operations"](gateway, gateway=True)
        self.assertEqual(len(EXPORTER["APPROVAL_SIGNATURE_FEATURE_OPERATIONS"]), 27)

    def test_signature_contract_validation_rejects_missing_or_extra_routes(self) -> None:
        expected = EXPORTER["APPROVAL_SIGNATURE_FEATURE_OPERATIONS"] | {
            EXPORTER["APPROVAL_SIGNATURE_LEGACY_OPERATION"]
        }
        document = {
            "paths": {
                path: {method: {}}
                for method, path in expected
            }
        }
        EXPORTER["validate_approval_signature_operations"](document)

        missing = copy.deepcopy(document)
        missing["paths"].pop("/v1/requests/{requestId}/signature-context")
        with self.assertRaisesRegex(RuntimeError, "missing"):
            EXPORTER["validate_approval_signature_operations"](missing)

        unexpected = copy.deepcopy(document)
        unexpected["paths"]["/v1/signature-requests/{signatureRequestId}/retry"] = {
            "post": {}
        }
        with self.assertRaisesRegex(RuntimeError, "unexpected"):
            EXPORTER["validate_approval_signature_operations"](unexpected)

    def test_gateway_openapi_projection_consumes_append_only_v14_registry(self) -> None:
        registry_path = EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"]
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        dwaion_routes = [
            route
            for route in registry["routes"]
            if route["subject"].get("productKey") == "dwaion"
        ]

        self.assertEqual(EXPORTER["PRODUCT_AUTHORIZATION_VERSION"], 16)
        self.assertEqual(registry["version"], 16)
        self.assertEqual(len(dwaion_routes), 95)
        self.assertEqual(
            sum(route["routeKind"] == "ACTION" for route in dwaion_routes), 57
        )
        approval_routes = [route for route in registry["routes"]
                           if route["subject"].get("productKey") == "approvals"]
        self.assertEqual(len(approval_routes), 151)
        self.assertEqual(sum(route["routeKind"] == "ACTION" for route in approval_routes), 80)

    def test_v14_governs_every_new_approval_operation(self) -> None:
        operations = EXPORTER["product_governed_operations"]()
        required = {
            ("/api/approvals/v1/requests/{requestId}/resubmit-draft", "post"),
            ("/api/approvals/v1/delegations/{delegationId}", "put"),
            ("/api/approvals/v1/admin/operations/events/{outboxId}/dead-letter", "post"),
            ("/api/approvals/v1/admin/operations/events/{outboxId}/replay", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/retry", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/dead-letter", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/replay", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/reconcile", "post"),
            ("/api/approvals/v1/admin/operations/tasks/{taskId}/reassign", "post"),
            ("/api/approvals/v1/admin/operations/tasks/reassign", "post"),
            ("/api/approvals/v1/admin/signatures/diagnostics", "get"),
            ("/api/approvals/v1/admin/signatures/providers/{providerId}/diagnostics", "get"),
            ("/api/approvals/v1/admin/signatures/diagnostic-history", "get"),
            ("/api/approvals/v1/admin/signatures/probes", "post"),
            ("/api/approvals/v1/admin/signatures/kms/probes", "post"),
            ("/api/approvals/v1/admin/signatures/worm-inspections", "post"),
            ("/api/approvals/v1/admin/signatures/policy", "get"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/history", "get"),
            ("/api/approvals/v1/admin/signatures/policies", "post"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/draft", "put"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/publish", "post"),
            ("/api/approvals/v1/requests/{requestId}/external-signature-context", "get"),
            ("/api/approvals/v1/requests/{requestId}/external-signature-requests", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}", "get"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/handovers", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/refresh", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/cancel", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/audit", "get"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}", "get"),
            ("/api/approvals/v1/requests/{requestId}/draft/migration-preview", "get"),
            ("/api/approvals/v1/requests/{requestId}/draft/migrate", "post"),
            ("/api/approvals/v1/admin/policies", "post"),
            ("/api/approvals/v1/admin/forms/publish-review-candidates", "get"),
            ("/api/approvals/v1/admin/forms/publish-review-requests", "get"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-request", "get"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-request", "post"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject", "post"),
        }

        self.assertEqual(len(required), 37)
        self.assertTrue(required.issubset(operations))
        document = {"paths": {}}
        for path, method in required:
            document["paths"].setdefault(path, {})[method] = {"parameters": []}
        EXPORTER["add_product_governance_contract"](document)
        for operation in required:
            self.assertEqual(operations[operation], operation[1] != "get")
            parameters = document["paths"][operation[0]][operation[1]]["parameters"]
            names = {parameter["name"] for parameter in parameters}
            self.assertIn("contextScopeKey", names)
            if operation[1] == "get":
                self.assertNotIn("X-DWP-Expected-Decision-Revision", names)
            else:
                self.assertIn("X-DWP-Expected-Decision-Revision", names)

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
