from __future__ import annotations

import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v33_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


EXPECTED_HRIS_ROUTES = {
    "route.hcm.management.system.page",
    "route.hcm.operations.payroll-foundation-configuration.data",
    "route.hcm.operations.payroll-foundation-configurations.data",
    "route.hcm.operations.payroll-foundation-create.action",
    "route.hcm.operations.payroll-foundation-publish.action",
    "route.hcm.operations.payroll-foundation-receipt.data",
    "route.hcm.operations.payroll-foundation-reconcile.action",
    "route.hcm.operations.payroll-foundation-reverse.action",
    "route.hcm.operations.payroll-foundation-simulate.action",
    "route.hcm.operations.payroll-foundation-update.action",
    "route.hcm.operations.payroll-foundation-versions.data",
    "route.hcm.operations.people360-detail.data",
    "route.hcm.operations.people360-search.data",
    "route.hcm.operations.performance-cycle-command-receipt.data",
    "route.hcm.operations.performance-cycle-create.action",
    "route.hcm.operations.performance-cycle-detail.data",
    "route.hcm.operations.performance-cycle-population-preview.action",
    "route.hcm.operations.performance-cycle-publish.action",
    "route.hcm.operations.performance-cycle-update.action",
    "route.hcm.operations.performance-cycle-validate.action",
    "route.hcm.operations.performance-cycles-list.data",
    "route.hcm.operations.work-plan-apply-approval.action",
    "route.hcm.operations.work-plan-create.action",
    "route.hcm.operations.work-plan-publish.action",
    "route.hcm.operations.work-plan-receipt.data",
    "route.hcm.operations.work-plan-simulate.action",
    "route.hcm.operations.work-plan-submit-review.action",
    "route.hcm.operations.work-plan-validate.action",
    "route.hcm.operations.work-plans-list.data",
    "route.hcm.personal.configuration-projection.data",
    "route.hcm.personal.people360-self.data",
    "route.hcm.personal.product-access-snapshot.data",
    "route.hcm.team.people360-detail.data",
}


class ProductAuthorizationV33Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v32 = next(value for value in snapshots if value["version"] == 32)
        cls.v33 = next(value for value in snapshots if value["version"] == 33)
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v33["routes"]
        }

    def test_preserves_canonical_v32_and_appends_combined_hris_closure(self) -> None:
        self.assertEqual(
            "b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc",
            self.v32["checksum"],
        )
        self.assertEqual(
            "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639",
            self.v33["checksum"],
        )
        prior = {route["routeContractKey"] for route in self.v32["routes"]}
        self.assertEqual(EXPECTED_HRIS_ROUTES, set(self.routes) - prior)
        self.assertIn("route.communications.management.code-sets.data", self.routes)
        self.assertEqual((219, 24, 17, 54, 945), (
            len(self.v33["capabilities"]),
            len(self.v33["accessPolicies"]),
            len(self.v33["entitlementExpressions"]),
            len(self.v33["predicatePolicies"]),
            len(self.v33["routes"]),
        ))

    def test_shared_owner_paths_have_exact_mutually_exclusive_discriminators(self) -> None:
        expected = {
            "route.hcm.operations.people.page": {
                "view": {"kind": "ABSENT"},
                "projection": {"kind": "ABSENT"},
            },
            "route.hcm.operations.assignments.page": {
                "view": {"kind": "FIXED", "value": "assignments"},
                "projection": {"kind": "ABSENT"},
            },
            "route.hcm.operations.person-detail.data": {
                "projection": {"kind": "ABSENT"},
            },
            "route.hcm.personal.home.page": {
                "projection": {"kind": "ABSENT"},
            },
            "route.hcm.team.home.page": {
                "projection": {"kind": "ABSENT"},
            },
            "route.hcm.personal.product-access-snapshot.data": {
                "view": {"kind": "ABSENT"},
            },
            "route.hcm.personal.configuration-projection.data": {
                "view": {"kind": "ABSENT"},
            },
        }
        for route_key, constraints in expected.items():
            route = self.routes[route_key]
            self.assertEqual(
                constraints,
                route["gatewayApiBindings"][0]["queryParameterConstraints"],
            )
            self.assertEqual(
                constraints,
                route["servicePepBindings"][0]["queryParameterConstraints"],
            )

    def test_system_page_and_high_risk_owner_bindings_remain_exact(self) -> None:
        system = self.routes["route.hcm.management.system.page"]
        self.assertEqual("PAGE", system["routeKind"])
        self.assertEqual("/hr/manage/system", system["uiRoutePattern"])
        self.assertEqual(2, len(system["gatewayApiBindings"]))
        for binding in system["gatewayApiBindings"] + system["servicePepBindings"]:
            self.assertEqual(
                {"view": {"kind": "FIXED", "value": "system"}},
                binding["queryParameterConstraints"],
            )

        expected = {
            "route.hcm.operations.performance-cycle-publish.action": "people",
            "route.hcm.operations.payroll-foundation-publish.action": "payroll",
            "route.hcm.operations.payroll-foundation-reverse.action": "payroll",
            "route.hcm.operations.work-plan-publish.action": "time",
        }
        for route_key, owner in expected.items():
            bindings = self.routes[route_key]["stepUpCommandBindings"]
            self.assertEqual(1, len(bindings))
            self.assertEqual(owner, bindings[0]["ownerServiceKey"])

    def test_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v33).encode()
        self.assertEqual(
            rendered, GENERATOR.VERSIONED_CONTRACT_OUTPUTS[33].read_bytes()
        )
        self.assertEqual(
            rendered, GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[33].read_bytes()
        )


if __name__ == "__main__":
    unittest.main()
