import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v12_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


SIGNATURE_ROUTES = {
    "route.approvals.admin.signature-diagnostics.data": ("GET", "/v1/admin/signatures/diagnostics", "approvals.signature.read"),
    "route.approvals.admin.signature-provider-diagnostics.data": ("GET", "/v1/admin/signatures/providers/{providerId}/diagnostics", "approvals.signature.read"),
    "route.approvals.admin.signature-diagnostic-history.data": ("GET", "/v1/admin/signatures/diagnostic-history", "approvals.signature.read"),
    "route.approvals.admin.signature-probe.action": ("POST", "/v1/admin/signatures/probes", "approvals.signature.manage"),
    "route.approvals.admin.signature-kms-probe.action": ("POST", "/v1/admin/signatures/kms/probes", "approvals.signature.manage"),
    "route.approvals.admin.signature-worm-inspection.action": ("POST", "/v1/admin/signatures/worm-inspections", "approvals.signature.manage"),
    "route.approvals.admin.signature-policy.data": ("GET", "/v1/admin/signatures/policy", "approvals.signature.read"),
    "route.approvals.admin.signature-policy-initialize.action": ("POST", "/v1/admin/signatures/policies", "approvals.signature.manage"),
    "route.approvals.admin.signature-policy-draft-update.action": ("PUT", "/v1/admin/signatures/policies/{policyId}/draft", "approvals.signature.manage"),
    "route.approvals.admin.signature-policy-publish.action": ("POST", "/v1/admin/signatures/policies/{policyId}/publish", "approvals.signature.publish"),
    "route.approvals.admin.signature-policy-history.data": ("GET", "/v1/admin/signatures/policies/{policyId}/history", "approvals.signature.read"),
    "route.approvals.work.external-signature-context.data": ("GET", "/v1/requests/{requestId}/external-signature-context", "approvals.work.signature.read"),
    "route.approvals.work.external-signature-request-create.action": ("POST", "/v1/requests/{requestId}/external-signature-requests", "approvals.work.signature.update"),
    "route.approvals.work.external-signature-request.data": ("GET", "/v1/external-signature-requests/{signatureRequestId}", "approvals.work.signature.read"),
    "route.approvals.work.external-signature-handover.action": ("POST", "/v1/external-signature-requests/{signatureRequestId}/handovers", "approvals.work.signature.sign"),
    "route.approvals.work.external-signature-refresh.action": ("POST", "/v1/external-signature-requests/{signatureRequestId}/refresh", "approvals.work.signature.update"),
    "route.approvals.work.external-signature-cancel.action": ("POST", "/v1/external-signature-requests/{signatureRequestId}/cancel", "approvals.work.signature.update"),
    "route.approvals.work.external-signature-audit.data": ("GET", "/v1/external-signature-requests/{signatureRequestId}/audit", "approvals.work.signature.read"),
    "route.approvals.work.external-signature-artifact.data": ("GET", "/v1/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}", "approvals.work.signature.read"),
}

OPERATION_ROUTES = {
    "route.approvals.admin.operations.dead-letter.action": "/v1/admin/operations/events/{outboxId}/dead-letter",
    "route.approvals.admin.operations.replay.action": "/v1/admin/operations/events/{outboxId}/replay",
    "route.approvals.admin.operations.batch-retry.action": "/v1/admin/operations/deliveries/retry",
    "route.approvals.admin.operations.batch-dead-letter.action": "/v1/admin/operations/deliveries/dead-letter",
    "route.approvals.admin.operations.batch-replay.action": "/v1/admin/operations/deliveries/replay",
    "route.approvals.admin.operations.reconcile.action": "/v1/admin/operations/deliveries/reconcile",
    "route.approvals.admin.operations.task-reassign.action": "/v1/admin/operations/tasks/{taskId}/reassign",
    "route.approvals.admin.operations.task-batch-reassign.action": "/v1/admin/operations/tasks/reassign",
}


class ProductAuthorizationV12Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = GENERATOR.load_source()
        cls.snapshots = GENERATOR.build_snapshots(cls.source)
        cls.v11 = cls.snapshots[10]
        cls.v12 = cls.snapshots[11]
        cls.routes = {route["routeContractKey"]: route for route in cls.v12["routes"]}

    def test_v12_is_the_exact_native_approval_append(self):
        prior = {route["routeContractKey"] for route in self.v11["routes"]}
        added = {key for key in self.routes if key not in prior}
        self.assertEqual(
            set(SIGNATURE_ROUTES) | set(OPERATION_ROUTES) | {
                "route.approvals.work.request-resubmit-draft.action",
                "route.approvals.work.delegation-update.action",
            },
            added,
        )
        self.assertEqual(29, len(added))
        self.assertEqual("65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74", self.v12["checksum"])
        self.assertEqual(GENERATOR.render(self.v12).encode(), GENERATOR.VERSIONED_CONTRACT_OUTPUTS[12].read_bytes())
        self.assertEqual(GENERATOR.render(self.v11).encode(), GENERATOR.VERSIONED_CONTRACT_OUTPUTS[11].read_bytes())

    def test_signature_enum_contracts_are_exact_and_data_projections_are_bounded(self):
        for key, (method, path, capability) in SIGNATURE_ROUTES.items():
            with self.subTest(route=key):
                route = self.routes[key]
                profile = route["accessProfiles"][0]
                self.assertEqual((method, path), (route["servicePepBindings"][0]["method"], route["servicePepBindings"][0]["path"]))
                self.assertEqual("/api/approvals" + path, route["gatewayApiBindings"][0]["path"])
                self.assertEqual(capability, profile["requiredAccess"]["capabilityContractKey"])
                if method == "GET":
                    projection = profile["responseProjectionBindings"][0]
                    self.assertEqual({"apiBindingKey", "projectionPolicyKey", "responseSchemaKey"}, set(projection))
                    self.assertNotIn("secret", str(projection).lower())
                    self.assertNotIn("rawpayload", str(projection).lower())

    def test_operations_are_exact_high_step_up_commands(self):
        for key, path in OPERATION_ROUTES.items():
            with self.subTest(route=key):
                route = self.routes[key]
                profile = route["accessProfiles"][0]
                self.assertEqual(("POST", path), (route["servicePepBindings"][0]["method"], route["servicePepBindings"][0]["path"]))
                self.assertEqual("approvals.operations.execute", profile["requiredAccess"]["capabilityContractKey"])
                binding = route["stepUpCommandBindings"][0]
                self.assertEqual("COMMAND_HEADER", binding["expectedObjectVersionSource"])
                self.assertEqual("X-DWP-Expected-Object-Version", binding["expectedObjectVersionName"])
                self.assertEqual("dwp-approval-server", binding["audience"])

    def test_new_capabilities_are_exact_and_publish_is_high(self):
        capabilities = {value["contractKey"]: value for value in self.v12["capabilities"]}
        manage = capabilities["approvals.signature.manage"]
        publish = capabilities["approvals.signature.publish"]
        self.assertEqual(("ADMIN.APPROVAL_SIGNATURE:MANAGE", "LOW", None),
                         (manage["resolvedCapabilityCode"], manage["riskTier"], manage["activationPolicy"]))
        self.assertEqual(("ADMIN.APPROVAL_SIGNATURE:PUBLISH", "HIGH", "STEPUP-MGMT-HIGH-V1"),
                         (publish["resolvedCapabilityCode"], publish["riskTier"], publish["activationPolicy"]))
        self.assertEqual("APP_CONFIG_ADMIN", manage["requiredResponsibilityCode"])
        self.assertEqual("APP_CONFIG_ADMIN", publish["requiredResponsibilityCode"])

    def test_high_route_or_exact_native_binding_drift_fails_closed(self):
        for mutation in ("missing-step-up", "path", "capability"):
            with self.subTest(mutation=mutation):
                source = copy.deepcopy(self.source)
                wave = next(value for value in source["waves"] if value["version"] == 12)
                route = next(value for value in wave["routes"]
                             if value["routeContractKey"] == "route.approvals.admin.signature-policy-publish.action")
                if mutation == "missing-step-up":
                    route.pop("stepUpCommandBindings")
                elif mutation == "path":
                    route["servicePepBindings"][0]["path"] += "/alias"
                else:
                    route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"] = "approvals.signature.manage"
                with self.assertRaises(GENERATOR.ContractError):
                    GENERATOR.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
