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

COMMUNICATION_CODE_SETS = {
    "PLATFORM.COMMUNICATION.CATEGORY",
    "PLATFORM.COMMUNICATION.CONTENT_TYPE",
}


class ProductAuthorizationV32Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v31 = cls.snapshots[-2]
        cls.v32 = cls.snapshots[-1]

    def test_v32_adds_only_the_communications_code_set_boundary(self) -> None:
        self.assertEqual(32, self.v32["version"])
        self.assertEqual(
            "b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc",
            self.v32["checksum"],
        )
        prior_routes = {route["routeContractKey"] for route in self.v31["routes"]}
        added_routes = [
            route for route in self.v32["routes"]
            if route["routeContractKey"] not in prior_routes
        ]
        self.assertEqual(
            ["route.communications.management.code-sets.data"],
            [route["routeContractKey"] for route in added_routes],
        )
        self.assertEqual(
            {item["contractKey"] for item in self.v31["capabilities"]},
            {item["contractKey"] for item in self.v32["capabilities"]},
        )

    def test_route_requires_the_existing_read_capability_and_exact_keys(self) -> None:
        route = next(
            route for route in self.v32["routes"]
            if route["routeContractKey"]
            == "route.communications.management.code-sets.data"
        )
        profile = route["accessProfiles"][0]
        self.assertEqual(
            {"type": "CAPABILITY", "capabilityContractKey": "communications.content.read"},
            profile["requiredAccess"],
        )
        self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
        self.assertEqual(["predicate.communications-code-set.v1"], profile["predicatePolicyKeys"])
        self.assertTrue(profile["readOnly"])
        for binding in route["gatewayApiBindings"] + route["servicePepBindings"]:
            self.assertEqual(
                COMMUNICATION_CODE_SETS,
                set(binding["pathParameterConstraints"]["codeSetKey"]["values"]),
            )

    def test_communications_does_not_widen_the_hcm_predicate(self) -> None:
        predicates = {
            item["predicatePolicyKey"]: item
            for item in self.v32["predicatePolicies"]
        }
        communications = predicates["predicate.communications-code-set.v1"]
        self.assertEqual(
            COMMUNICATION_CODE_SETS,
            set(communications["parameterSchema"]["properties"]["codeSetKey"]["enum"]),
        )
        hcm = predicates["predicate.allowlisted-code-set.v1"]
        self.assertTrue(
            COMMUNICATION_CODE_SETS.isdisjoint(
                hcm["parameterSchema"]["properties"]["codeSetKey"]["enum"]
            )
        )

    def test_v32_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v32).encode()
        self.assertEqual(rendered, GENERATOR.VERSIONED_CONTRACT_OUTPUTS[32].read_bytes())
        self.assertEqual(rendered, GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[32].read_bytes())


if __name__ == "__main__":
    unittest.main()
