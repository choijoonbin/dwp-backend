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


GENERATOR = load("v9_authorization", "generate-product-authorization-contracts.py")
PROJECTION = load("v9_projection", "generate-approval-extension-projection-contracts.py")
EXPECTED_OPERATIONS = {
    ("PUT", "/v1/admin/attachments/policies/{policyId}/draft"),
    ("POST", "/v1/admin/attachments/policies"),
    ("POST", "/v1/admin/attachments/policies/{policyId}/publish"),
    ("GET", "/v1/admin/attachments/policy"),
    ("GET", "/v1/admin/forms/{formId}/publish-review"),
    ("POST", "/v1/admin/forms/{formId}/reinstate"),
    ("POST", "/v1/admin/forms/{formId}/retire"),
    ("POST", "/v1/admin/forms/{formId}/publish-reviewed"),
    ("POST", "/v1/admin/forms/{formId}/versions/{formVersionId}/branch"),
    ("GET", "/v1/admin/forms/{formId}/versions/{formVersionId}"),
    ("GET", "/v1/admin/forms/{formId}/diff"),
    ("GET", "/v1/admin/forms/{formId}/versions"),
    ("PUT", "/v1/admin/forms/{formId}/working-draft"),
    ("GET", "/v1/admin/forms/{formId}/working-draft"),
    ("GET", "/v1/admin/policies/{policyId}/impact"),
    ("GET", "/v1/attachment-downloads/{grantId}/content"),
    ("POST", "/v1/attachment-uploads/{uploadId}/cancel"),
    ("PUT", "/v1/attachment-uploads/{uploadId}/content"),
    ("POST", "/v1/attachment-uploads/{uploadId}/reconcile"),
    ("GET", "/v1/attachment-uploads/{uploadId}"),
    ("POST", "/v1/requests/{requestId}/information-commands/{originalKey}/receipt"),
    ("POST", "/v1/requests/{requestId}/attachments/{attachmentId}/downloads"),
    ("POST", "/v1/requests/{requestId}/attachment-uploads"),
    ("PUT", "/v1/requests/{requestId}/attachments"),
    ("GET", "/v1/requests/{requestId}/attachments"),
    ("POST", "/v1/tasks/{taskId}/attachments/{attachmentId}/downloads"),
    ("GET", "/v1/tasks/{taskId}/attachments"),
}


class ProductAuthorizationV9Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v9 = cls.snapshots[8]
        prior = {route["routeContractKey"] for route in cls.snapshots[7]["routes"]}
        cls.added = [route for route in cls.v9["routes"] if route["routeContractKey"] not in prior]
        cls.api = json.loads((ROOT / "contracts/openapi/approval.json").read_text())

    def test_all_eight_inherited_releases_and_seeds_are_byte_identical(self):
        for snapshot in self.snapshots[:8]:
            version = snapshot["version"]
            self.assertEqual(GENERATOR.IMMUTABLE_RELEASE_CHECKSUMS[version], snapshot["checksum"])
            expected = GENERATOR.render(snapshot).encode()
            self.assertEqual(expected, GENERATOR.VERSIONED_CONTRACT_OUTPUTS[version].read_bytes())
            self.assertEqual(expected, GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[version].read_bytes())

    def test_exact_twenty_seven_operations_exist_in_actual_owner_openapi(self):
        operations = {(binding["method"], binding["path"])
                      for route in self.added for binding in route["servicePepBindings"]}
        self.assertEqual(27, len(self.added))
        self.assertEqual(EXPECTED_OPERATIONS, operations)
        self.assertEqual(12, sum(route["routeKind"] == "DATA" for route in self.added))
        self.assertEqual(15, sum(route["routeKind"] == "ACTION" for route in self.added))
        for method, path in operations:
            self.assertIn(method.lower(), self.api["paths"][path])
        self.assertFalse(any("signature" in path or "simulation" in path for _, path in operations))

    def test_twelve_projection_graphs_match_actual_owner_response_components(self):
        actual = PROJECTION.build_contracts(self.api, self.v9)
        pinned = json.loads((ROOT / "contracts/product-authorization/approval-extension-projections-v9.generated.json").read_text())
        self.assertEqual(pinned, actual)
        self.assertEqual(12, actual["bindingCount"])
        self.assertEqual(24, len(actual["schemas"]))
        self.assertEqual(1, len(actual["binaryResponses"]))
        PROJECTION.validate_projection_bindings(self.v9, actual)
        for route in self.added:
            if route["routeKind"] == "DATA":
                self.assertTrue(route["sideEffectFree"])
                self.assertTrue(all(profile["readOnly"] for profile in route["accessProfiles"]))

    def test_reviewed_publish_is_bound_to_exact_header_version_and_owner_target(self):
        high = [route for route in self.added if route.get("stepUpCommandBindings")]
        self.assertEqual(2, len(high))
        for route in high:
            binding = route["stepUpCommandBindings"][0]
            policy = "attachment-policy" in route["routeContractKey"]
            self.assertEqual("ATTACHMENT_POLICY" if policy else "FORM", binding["targetType"])
            self.assertEqual("policyId" if policy else "formId", binding["targetIdPathParameter"])
            self.assertEqual("COMMAND_HEADER", binding["expectedObjectVersionSource"])
            self.assertEqual("X-DWP-Expected-Object-Version", binding["expectedObjectVersionName"])
            self.assertEqual("approval", binding["ownerServiceKey"])
            self.assertEqual("dwp-approval-server", binding["audience"])

    def test_receipt_is_readonly_post_with_original_actor_not_old_mutation_authority(self):
        route = next(route for route in self.added if "information-command-receipt" in route["routeContractKey"])
        self.assertEqual("DATA", route["routeKind"])
        self.assertEqual("POST", route["servicePepBindings"][0]["method"])
        self.assertNotIn("stepUpCommandBindings", route)
        profile = route["accessProfiles"][0]
        self.assertEqual("approvals.work.information-command-receipt.read",
                         profile["requiredAccess"]["capabilityContractKey"])
        self.assertEqual(["predicate.approval.original-information-command-receipt.v1"],
                         profile["predicatePolicyKeys"])
        impact = next(route for route in self.added if "policy-impact" in route["routeContractKey"])
        access = impact["accessProfiles"][0]["requiredAccess"]
        self.assertEqual("ALL", access["mode"])
        self.assertEqual({"approvals.design.read", "approvals.policy.read", "approvals.operations.read"},
                         set(access["capabilityContractKeys"]))

    def test_recomputed_checksum_cannot_replace_sealed_release_nine(self):
        changed = copy.deepcopy(self.v9)
        changed["routes"][0]["routeContractKey"] += ".alias"
        changed["checksum"] = GENERATOR.checksum(changed)
        with self.assertRaises(GENERATOR.ContractError):
            GENERATOR._validate_release_snapshot(changed)


if __name__ == "__main__":
    unittest.main()
