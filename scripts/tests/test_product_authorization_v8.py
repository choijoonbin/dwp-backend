import copy
import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


GENERATOR = load("v8_authorization", "generate-product-authorization-contracts.py")
DOCUMENT = load("v8_document", "generate-approval-document-projection-contracts.py")
EXPECTED_OPERATIONS = {
    ("GET", "/v1/requests/{requestId}/document-tools"),
    ("GET", "/v1/tasks/{taskId}/document-tools"),
    ("GET", "/v1/requests/{requestId}/comments"),
    ("GET", "/v1/tasks/{taskId}/comments"),
    ("POST", "/v1/requests/{requestId}/comments"),
    ("POST", "/v1/tasks/{taskId}/comments"),
    ("POST", "/v1/requests/{requestId}/document-exports"),
    ("POST", "/v1/tasks/{taskId}/document-exports"),
    ("POST", "/v1/requests/archive/document-exports"),
    ("GET", "/v1/admin/document-tools/policy"),
    ("PUT", "/v1/admin/document-tools/policies/{policyId}/draft"),
    ("POST", "/v1/admin/document-tools/policies/{policyId}/publish"),
    ("GET", "/v1/admin/document-tools/holds/{requestId}"),
    ("POST", "/v1/admin/document-tools/holds/{requestId}/proposals"),
    ("POST", "/v1/admin/document-tools/holds/{requestId}/publish"),
    ("GET", "/v1/catalog/forms/{formId}/versions/{formVersionId}/field-candidates"),
    ("GET", "/v1/admin/forms/{formId}/versions/{formVersionId}/field-candidates"),
}


class ProductAuthorizationV8Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v7, cls.v8 = cls.snapshots[6:8]
        prior = {route["routeContractKey"] for route in cls.v7["routes"]}
        cls.added = [route for route in cls.v8["routes"] if route["routeContractKey"] not in prior]
        cls.api = json.loads((ROOT / "contracts/openapi/approval.json").read_text())

    def test_all_seven_inherited_releases_remain_pinned_byte_identical(self):
        for snapshot in self.snapshots[:7]:
            version = snapshot["version"]
            self.assertEqual(GENERATOR.IMMUTABLE_RELEASE_CHECKSUMS[version], snapshot["checksum"])
            expected = GENERATOR.render(snapshot).encode()
            self.assertEqual(expected, GENERATOR.VERSIONED_CONTRACT_OUTPUTS[version].read_bytes())
            self.assertEqual(expected, GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[version].read_bytes())

    def test_exact_seventeen_operations_with_no_old_policy_alias_or_later_wave(self):
        self.assertEqual(17, len(self.added))
        self.assertEqual(EXPECTED_OPERATIONS, {
            (binding["method"], binding["path"])
            for route in self.added for binding in route["servicePepBindings"]})
        self.assertEqual(8, sum(route["routeKind"] == "DATA" for route in self.added))
        self.assertEqual(9, sum(route["routeKind"] == "ACTION" for route in self.added))

    def test_export_comment_and_user_source_permissions_are_explicit_all_not_approve_manage(self):
        for route in self.added:
            key = route["routeContractKey"]
            access = route["accessProfiles"][0]["requiredAccess"]
            if not any(name in key for name in ("document-export.action", "-comment.action",
                                                "field-candidates.data")):
                continue
            self.assertEqual("CAPABILITY_EXPRESSION", access["type"])
            self.assertEqual("ALL", access["mode"])
            keys = access["capabilityContractKeys"]
            self.assertEqual(2, len(keys))
            self.assertFalse(any(".approve" in value or ".manage" in value for value in keys))
            if "field-candidates" in key:
                self.assertTrue(any("form-user-directory.read" in value for value in keys))

    def test_policy_and_hold_high_risk_targets_use_exact_path_and_existing_version_contract(self):
        routes = [route for route in self.added if "stepUpCommandBindings" in route]
        self.assertEqual(2, len(routes))
        for route in routes:
            binding = route["stepUpCommandBindings"][0]
            policy = "document-policy-publish" in route["routeContractKey"]
            self.assertEqual("DOCUMENT_POLICY" if policy else "DOCUMENT_HOLD", binding["targetType"])
            self.assertEqual("policyId" if policy else "requestId", binding["targetIdPathParameter"])
            self.assertNotIn("targetIdBodyFields", binding)
            self.assertEqual("COMMAND_BODY", binding["expectedObjectVersionSource"])
            self.assertEqual("expectedVersion", binding["expectedObjectVersionName"])
            self.assertEqual("approvals.policy.publish",
                             route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"])

    def test_new_release_cannot_mutate_even_with_recomputed_checksum(self):
        for original in self.snapshots:
            changed = copy.deepcopy(original)
            changed["owner"] = "Changed immutable descriptor owner"
            changed["checksum"] = GENERATOR.checksum(changed)
            with self.assertRaises(GENERATOR.ContractError):
                GENERATOR._validate_release_snapshot(changed)

    def test_response_graph_hash_is_independent_of_all_operation_parameters(self):
        first = DOCUMENT.build_contracts(self.api, self.v8)
        changed = copy.deepcopy(self.api)
        for route in self.added:
            binding = route["servicePepBindings"][0]
            changed["paths"][binding["path"]][binding["method"].lower()]["parameters"] = [
                {"in": "header", "name": "X-DWP-Test", "schema": {"type": "string"}}]
        self.assertEqual(first, DOCUMENT.build_contracts(changed, self.v8))
        self.assertEqual(13, len(first["schemas"]))

    def test_changed_transitive_person_content_is_rejected_without_repin(self):
        changed = copy.deepcopy(self.api)
        schemas = changed["components"]["schemas"]
        person_ref = schemas["ApprovalFormUserCandidates"]["properties"]["people"]["items"]["$ref"]
        person = schemas[person_ref.removeprefix("#/components/schemas/")]
        person["properties"]["email"] = {"type": "string"}
        with self.assertRaises(DOCUMENT.ContractError):
            DOCUMENT.build_contracts(changed, self.v8)


if __name__ == "__main__":
    unittest.main()
