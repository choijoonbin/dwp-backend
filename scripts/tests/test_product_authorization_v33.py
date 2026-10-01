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


class ProductAuthorizationV33Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v32 = next(snapshot for snapshot in cls.snapshots if snapshot["version"] == 32)
        cls.v33 = next(snapshot for snapshot in cls.snapshots if snapshot["version"] == 33)
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v33["routes"]
        }

    def test_v33_adds_only_the_system_management_page_contract(self) -> None:
        prior = {route["routeContractKey"] for route in self.v32["routes"]}
        added = [
            route for route in self.v33["routes"]
            if route["routeContractKey"] not in prior
        ]
        self.assertEqual(
            ["route.hcm.management.system.page"],
            [route["routeContractKey"] for route in added],
        )
        self.assertEqual(
            "254ead674e1126d50e8dcf1011486ea1127fb2479f7a82d832cdf0466995bc49",
            self.v33["checksum"],
        )
        self.assertEqual((219, 24, 17, 53, 944), (
            len(self.v33["capabilities"]),
            len(self.v33["accessPolicies"]),
            len(self.v33["entitlementExpressions"]),
            len(self.v33["predicatePolicies"]),
            len(self.v33["routes"]),
        ))

    def test_system_page_owns_exact_management_surface_and_wire_discriminator(self) -> None:
        route = self.routes["route.hcm.management.system.page"]
        self.assertEqual("PAGE", route["routeKind"])
        self.assertEqual("hcm.management", route["navigationContextId"])
        self.assertEqual("hcm.management", route["subject"]["surfaceKey"])
        self.assertEqual("/hr/manage/system", route["uiRoutePattern"])
        self.assertEqual(
            "hcm.management-system-access.v1",
            route["accessProfiles"][0]["requiredAccess"]["accessPolicyKey"],
        )
        policy = next(
            value for value in self.v33["accessPolicies"]
            if value["accessPolicyKey"] == "hcm.management-system-access.v1"
        )
        self.assertEqual("hcm.management", policy["navigationContextId"])
        self.assertEqual("hcm.management", policy["surfaceKey"])
        self.assertEqual(["hcm.management"], policy["surfaceEntryKeys"])
        self.assertEqual(2, len(route["gatewayApiBindings"]))
        for binding in route["gatewayApiBindings"]:
            self.assertEqual(
                {"view": {"kind": "FIXED", "value": "system"}},
                binding["queryParameterConstraints"],
            )

    def test_legacy_personal_bindings_are_mutually_exclusive(self) -> None:
        for route_key in (
            "route.hcm.personal.product-access-snapshot.data",
            "route.hcm.personal.configuration-projection.data",
        ):
            route = self.routes[route_key]
            self.assertEqual(
                {"view": {"kind": "ABSENT"}},
                route["gatewayApiBindings"][0]["queryParameterConstraints"],
            )

    def test_v33_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v33).encode()
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[33].read_bytes(),
        )
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[33].read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
