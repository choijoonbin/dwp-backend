from __future__ import annotations

import json
import copy
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V50_CHECKSUM = "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760"
V51_CHECKSUM = "3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c"

HRM = {
    "route.hcm.team.people-changes.page",
    "route.hcm.team.people-changes.data",
    "route.hcm.operations.people-appointments.page",
    "route.hcm.operations.people-appointments.data",
    "route.hcm.configuration.people-employment.page",
    "route.hcm.configuration.people-employment.data",
}
AUTHORIZATION_ONLY = {
    "route.hcm.configuration.workflow.page": (
        "hcm.configuration.workflow.view",
        ["CONFIG_SCOPE"],
        ["predicate.hcm-configuration-scope.v1"],
    ),
    "route.hcm.team.delegation.page": (
        "hcm.team.members.view",
        ["RELATIONSHIP", "TARGET_POPULATION"],
        ["predicate.team-target-population.v1"],
    ),
}

SPEC = importlib.util.spec_from_file_location(
    "product_authorization_v51_generator",
    ROOT / "scripts/generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV51Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v50 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v50.json").read_text())
        cls.v51 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v51.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.v51["routes"]}

    def test_v51_is_an_exact_append_only_successor(self) -> None:
        self.assertEqual(V50_CHECKSUM, self.v50["checksum"])
        self.assertEqual((51, "DRAFT", V51_CHECKSUM), (
            self.v51["version"], self.v51["bundleStatus"], self.v51["checksum"],
        ))
        self.assertEqual((265, 24, 17, 81, 1087), (
            len(self.v51["capabilities"]), len(self.v51["accessPolicies"]),
            len(self.v51["entitlementExpressions"]),
            len(self.v51["predicatePolicies"]), len(self.v51["routes"]),
        ))
        self.assertEqual(HRM | set(AUTHORIZATION_ONLY),
                         set(self.routes) - {r["routeContractKey"] for r in self.v50["routes"]})

    def test_hrm_page_data_pairs_are_exact_wire_equivalents(self) -> None:
        pairs = (
            ("team.people-changes", "wire-authority.hcm.team.people-changes.v1"),
            ("operations.people-appointments", "wire-authority.hcm.operations.people-appointments.v1"),
            ("configuration.people-employment", "wire-authority.hcm.configuration.people-employment.v1"),
        )
        for stem, equivalence in pairs:
            page = self.routes[f"route.hcm.{stem}.page"]
            data = self.routes[f"route.hcm.{stem}.data"]
            self.assertEqual(equivalence, page["authorizationEquivalenceKey"])
            self.assertEqual(equivalence, data["authorizationEquivalenceKey"])
            self.assertEqual(page["gatewayApiBindings"][0]["path"],
                             data["gatewayApiBindings"][0]["path"])
            self.assertEqual(page["servicePepBindings"][0]["path"],
                             data["servicePepBindings"][0]["path"])

    def test_sys_pages_are_authorization_only_hcm_evidence(self) -> None:
        for key, (capability, targets, predicates) in AUTHORIZATION_ONLY.items():
            route = self.routes[key]
            self.assertEqual("AUTHORIZATION_ONLY", route["bindingMode"])
            self.assertEqual(
                "HRIS_CONFIGURATION" if key.endswith("workflow.page") else "TEAM_MANAGEMENT",
                route["purposeCode"],
            )
            self.assertEqual("PAGE", route["routeKind"])
            self.assertEqual([], route["gatewayApiBindings"])
            self.assertEqual([], route["servicePepBindings"])
            self.assertNotIn("authorizationEquivalenceKey", route)
            profile = route["accessProfiles"][0]
            self.assertEqual(capability,
                             profile["requiredAccess"]["capabilityContractKey"])
            self.assertEqual(targets, profile["targetBindingKinds"])
            self.assertEqual(predicates, profile["predicatePolicyKeys"])
            self.assertNotIn("responseProjectionBindings", profile)

    def test_generator_rejects_authorization_only_network_or_projection_bindings(self) -> None:
        for mutation in ("network", "projection"):
            source = copy.deepcopy(GENERATOR.load_source())
            route = next(
                route
                for wave in source["waves"] if wave["version"] == 51
                for route in wave["routes"]
                if route["routeContractKey"] == "route.hcm.configuration.workflow.page"
            )
            if mutation == "network":
                route["gatewayApiBindings"] = [{
                    "bindingKey": route["routeContractKey"] + ".binding.01",
                    "method": "GET",
                    "path": "/api/people/v1/forbidden",
                    "pathParameterConstraints": {},
                }]
            else:
                route["accessProfiles"][0]["projectionPolicyKey"] = "forbidden"
                route["accessProfiles"][0]["responseSchemaKey"] = "Forbidden"
            with self.subTest(mutation=mutation), self.assertRaises(GENERATOR.ContractError):
                GENERATOR.build_snapshots(source)

    def test_current_alias_is_v62_and_v51_service_peps_remain_pinned(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        latest = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_text()
        )
        historical_seed = json.loads(
            (AUTH / "product-surfaces-v1.bundle-v51.generated.json").read_text()
        )
        self.assertEqual(latest, alias)
        self.assertEqual(latest, seed)
        self.assertEqual(self.v51, historical_seed)
        self.assertEqual((62, latest["checksum"], 62), (
            index["latestVersion"], index["latestChecksum"], len(index["versions"]),
        ))
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v51.generated.json"
            ).read_text())
            self.assertEqual(51, pep["registryRef"]["version"])
            self.assertEqual(V51_CHECKSUM, pep["registryRef"]["sha256"])
            route_keys = {route["routeContractKey"] for route in pep["routes"]}
            self.assertTrue(set(AUTHORIZATION_ONLY).isdisjoint(route_keys))
        people = json.loads((
            ROOT / "dwp-people-server/src/main/resources/product-authorization/"
            "hcm-people-pep-v51.generated.json"
        ).read_text())
        self.assertTrue(HRM.issubset({route["routeContractKey"] for route in people["routes"]}))


if __name__ == "__main__":
    unittest.main()
