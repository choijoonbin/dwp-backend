from __future__ import annotations

import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v32_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


EXPECTED_ROUTE_KEYS = {
    "route.hcm.operations.people360-search.data",
    "route.hcm.operations.people360-detail.data",
    "route.hcm.personal.people360-self.data",
    "route.hcm.team.people360-detail.data",
    "route.hcm.operations.performance-cycles-list.data",
    "route.hcm.operations.performance-cycle-detail.data",
    "route.hcm.operations.performance-cycle-create.action",
    "route.hcm.operations.performance-cycle-update.action",
    "route.hcm.operations.performance-cycle-validate.action",
    "route.hcm.operations.performance-cycle-population-preview.action",
    "route.hcm.operations.performance-cycle-publish.action",
    "route.hcm.operations.performance-cycle-command-receipt.data",
    "route.hcm.operations.payroll-foundation-configurations.data",
    "route.hcm.operations.payroll-foundation-configuration.data",
    "route.hcm.operations.payroll-foundation-versions.data",
    "route.hcm.operations.payroll-foundation-create.action",
    "route.hcm.operations.payroll-foundation-update.action",
    "route.hcm.operations.payroll-foundation-simulate.action",
    "route.hcm.operations.payroll-foundation-publish.action",
    "route.hcm.operations.payroll-foundation-reverse.action",
    "route.hcm.operations.payroll-foundation-receipt.data",
    "route.hcm.operations.payroll-foundation-reconcile.action",
    "route.hcm.operations.work-plans-list.data",
    "route.hcm.operations.work-plan-create.action",
    "route.hcm.operations.work-plan-simulate.action",
    "route.hcm.operations.work-plan-validate.action",
    "route.hcm.operations.work-plan-submit-review.action",
    "route.hcm.operations.work-plan-apply-approval.action",
    "route.hcm.operations.work-plan-publish.action",
    "route.hcm.operations.work-plan-receipt.data",
    "route.hcm.personal.product-access-snapshot.data",
    "route.hcm.personal.configuration-projection.data",
}


class ProductAuthorizationV32Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v31 = next(snapshot for snapshot in cls.snapshots if snapshot["version"] == 31)
        cls.v32 = next(snapshot for snapshot in cls.snapshots if snapshot["version"] == 32)
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v32["routes"]
        }
        prior = {route["routeContractKey"] for route in cls.v31["routes"]}
        cls.added = [
            route for route in cls.v32["routes"]
            if route["routeContractKey"] not in prior
        ]

    def test_v32_is_the_exact_hris_wave1_contract_closure(self) -> None:
        self.assertEqual(32, self.v32["version"])
        self.assertEqual(
            "9e4e274bf457d1a5947c8b54e83299d28fb9fe128d9f1100991bc30634b54344",
            self.v32["checksum"],
        )
        self.assertEqual(EXPECTED_ROUTE_KEYS, {
            route["routeContractKey"] for route in self.added
        })
        self.assertEqual(32, len(self.added))
        self.assertEqual(15, sum(
            route["routeKind"] == "DATA" for route in self.added
        ))
        self.assertEqual(17, sum(
            route["routeKind"] == "ACTION" for route in self.added
        ))
        self.assertEqual((219, 23, 17, 53, 943), (
            len(self.v32["capabilities"]),
            len(self.v32["accessPolicies"]),
            len(self.v32["entitlementExpressions"]),
            len(self.v32["predicatePolicies"]),
            len(self.v32["routes"]),
        ))

    def test_people360_and_legacy_queries_are_mutually_exclusive(self) -> None:
        cases = (
            ("route.hcm.operations.people.page", "projection", "ABSENT"),
            ("route.hcm.operations.person-detail.data", "projection", "ABSENT"),
            ("route.hcm.personal.home.page", "projection", "ABSENT"),
            ("route.hcm.team.home.page", "projection", "ABSENT"),
        )
        for route_key, parameter, kind in cases:
            route = self.routes[route_key]
            self.assertEqual(kind, route["gatewayApiBindings"][0]
                             ["queryParameterConstraints"][parameter]["kind"])
        for route_key in (
            "route.hcm.operations.people360-search.data",
            "route.hcm.operations.people360-detail.data",
            "route.hcm.personal.people360-self.data",
            "route.hcm.team.people360-detail.data",
        ):
            constraints = self.routes[route_key]["gatewayApiBindings"][0][
                "queryParameterConstraints"
            ]
            self.assertEqual({"kind": "FIXED", "value": "people360"},
                             constraints["projection"])
            self.assertEqual({"kind": "REQUIRED"}, constraints["asOf"])

    def test_high_risk_hris_commands_bind_exact_owner_step_up_evidence(self) -> None:
        expected = {
            "route.hcm.operations.performance-cycle-publish.action":
                ("people", "COMMAND_HEADER", "X-DWP-Expected-Object-Version"),
            "route.hcm.operations.payroll-foundation-publish.action":
                ("payroll", "COMMAND_BODY", "expectedVersion"),
            "route.hcm.operations.payroll-foundation-reverse.action":
                ("payroll", "COMMAND_BODY", "expectedVersion"),
            "route.hcm.operations.work-plan-publish.action":
                ("time", "COMMAND_BODY", "expectedVersion"),
        }
        for route_key, values in expected.items():
            step_up = self.routes[route_key]["stepUpCommandBindings"]
            self.assertEqual(1, len(step_up))
            self.assertEqual(values, (
                step_up[0]["ownerServiceKey"],
                step_up[0]["expectedObjectVersionSource"],
                step_up[0]["expectedObjectVersionName"],
            ))

    def test_v32_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v32).encode()
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[32].read_bytes(),
        )
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[32].read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
