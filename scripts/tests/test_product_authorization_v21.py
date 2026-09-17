from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v21_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV21Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v20 = cls.snapshots[19]
        cls.v21 = cls.snapshots[20]
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v21["routes"]
        }

    def test_v21_preserves_v20_and_contains_the_complete_extension(self) -> None:
        self.assertEqual(20, self.v20["version"])
        self.assertEqual(
            "1acbce34c450c650aa3e8f1c11995b831f177ee4a6139dd8217ea5bdd22e7a60",
            self.v20["checksum"],
        )
        self.assertEqual(21, self.v21["version"])
        self.assertEqual(
            "4cd1732df91d197cc47fca94699b0fb702ab1f6f2c557d3d17ce0e069d65af85",
            self.v21["checksum"],
        )
        self.assertEqual(709, len(self.v21["routes"]))
        added = set(self.routes) - {
            route["routeContractKey"] for route in self.v20["routes"]
        }
        self.assertEqual(23, len(added))
        self.assertEqual(
            {
                "route.dwaion.management.ai-control.page",
                "route.dwaion.management.ai-control-bootstrap.action",
                "route.dwaion.management.ai-control-update.action",
                "route.dwaion.management.ai-control-emergency.action",
                "route.workplace.management.exception-detail.data",
                "route.workplace.management.exception-export-content.data",
                "route.workplace.management.exception-export-preview.action",
                "route.workplace.management.exception-export.action",
                "route.workplace.management.exception-recovery-preview.action",
                "route.workplace.management.exception-recovery.action",
                "route.workplace.management.exceptions.page",
                "route.workplace.management.facility-closure-command-get.data",
                "route.workplace.management.facility-closure-command-receipt-get.data",
                "route.workplace.management.facility-closure-impact-execute.action",
                "route.workplace.management.facility-closure-impact-preview-create.data",
                "route.workplace.management.facility-closure-impact-preview-get.data",
                "route.workplace.management.facility-closure-notifications-reconcile.action",
                "route.workplace.management.facility-closure-notifications-retry.action",
                "route.workplace.work.access-pass-audit-events-get.data",
                "route.workplace.work.access-pass-command-get.data",
                "route.workplace.work.access-pass-context-get.data",
                "route.workplace.work.access-pass-execute.action",
                "route.workplace.work.access-pass-preview.action",
            },
            added,
        )

    def test_ai_runtime_bindings_and_permissions_are_exact(self) -> None:
        expected = {
            "route.dwaion.management.ai-control.page": (
                "DATA", "GET", "", {"dwaion.safety.read", "dwaion.safety.manage"}
            ),
            "route.dwaion.management.ai-control-bootstrap.action": (
                "ACTION", "POST", "/bootstrap",
                {"dwaion.safety.update", "dwaion.safety.manage"},
            ),
            "route.dwaion.management.ai-control-update.action": (
                "ACTION", "PUT", "/policy",
                {"dwaion.safety.update", "dwaion.safety.manage"},
            ),
            "route.dwaion.management.ai-control-emergency.action": (
                "ACTION", "POST", "/emergency", {"dwaion.safety.manage"},
            ),
        }
        for key, (kind, method, suffix, capabilities) in expected.items():
            route = self.routes[key]
            self.assertEqual(kind, route["routeKind"])
            self.assertEqual("dwaion.management", route["subject"]["surfaceKey"])
            self.assertEqual(
                (method, f"/api/agent/v1/admin/ai-control{suffix}"),
                (route["gatewayApiBindings"][0]["method"],
                 route["gatewayApiBindings"][0]["path"]),
            )
            self.assertEqual(
                (method, f"/v1/admin/ai-control{suffix}"),
                (route["servicePepBindings"][0]["method"],
                 route["servicePepBindings"][0]["path"]),
            )
            profile = route["accessProfiles"][0]
            self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
            access = profile["requiredAccess"]
            actual = (
                set(access["capabilityContractKeys"])
                if access["type"] == "CAPABILITY_EXPRESSION"
                else {access["capabilityContractKey"]}
            )
            self.assertEqual(capabilities, actual)
        manage = next(
            capability for capability in self.v21["capabilities"]
            if capability["contractKey"] == "dwaion.safety.manage"
        )
        self.assertEqual("HIGH", manage["riskTier"])
        self.assertIn(
            "route.dwaion.management.ai-control-emergency.action",
            manage["routeContractKeys"],
        )

    def test_workplace_extensions_have_exact_authority_and_step_up_boundaries(self) -> None:
        access_read = {
            "route.workplace.work.access-pass-context-get.data",
            "route.workplace.work.access-pass-audit-events-get.data",
            "route.workplace.work.access-pass-command-get.data",
        }
        for key in access_read:
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            self.assertEqual("DATA", route["routeKind"])
            self.assertEqual(["NORMAL", "ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                {"type": "POLICY", "accessPolicyKey": "workplace.work-access.v1"},
                profile["requiredAccess"],
            )
            self.assertEqual(["SELF"], profile["targetBindingKinds"])
            self.assertEqual(["predicate.workplace-resource.v1"], profile["predicatePolicyKeys"])

        preview = self.routes["route.workplace.work.access-pass-preview.action"]
        execute = self.routes["route.workplace.work.access-pass-execute.action"]
        self.assertEqual(["NORMAL", "ELEVATED"], preview["accessProfiles"][0]["activeAccessModes"])
        self.assertEqual(["ELEVATED"], execute["accessProfiles"][0]["activeAccessModes"])
        self.assertEqual(
            "workplace.booking.update",
            execute["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )

        closure_actions = {
            "route.workplace.management.facility-closure-impact-execute.action",
            "route.workplace.management.facility-closure-notifications-reconcile.action",
            "route.workplace.management.facility-closure-notifications-retry.action",
        }
        for key in closure_actions:
            profile = self.routes[key]["accessProfiles"][0]
            self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                "workplace.locations.update",
                profile["requiredAccess"]["capabilityContractKey"],
            )

        exception_actions = {
            "route.workplace.management.exception-recovery-preview.action",
            "route.workplace.management.exception-recovery.action",
            "route.workplace.management.exception-export-preview.action",
            "route.workplace.management.exception-export.action",
        }
        for key in exception_actions:
            profile = self.routes[key]["accessProfiles"][0]
            self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                "workplace.operations.manage",
                profile["requiredAccess"]["capabilityContractKey"],
            )
        export_content = self.routes[
            "route.workplace.management.exception-export-content.data"
        ]["accessProfiles"][0]
        self.assertEqual(["ELEVATED"], export_content["activeAccessModes"])
        self.assertEqual(
            "workplace.operations.manage",
            export_content["requiredAccess"]["capabilityContractKey"],
        )

    def test_latest_alias_and_index_point_to_v21(self) -> None:
        alias = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.json")
            .read_text()
        )
        index = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.index.json")
            .read_text()
        )
        self.assertEqual(self.v21, alias)
        self.assertEqual(21, index["latestVersion"])
        self.assertEqual(self.v21["checksum"], index["latestChecksum"])


if __name__ == "__main__":
    unittest.main()
