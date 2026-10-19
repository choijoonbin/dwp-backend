from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
CHECKSUM = "7fb2121a1209b87234bcb389a9bd4c4addca3c4e8c8776a9aac70d278c74d4e6"
ROUTES = {
    "route.hcm.operations.performance-forms-scales.page",
    "route.hcm.operations.performance-forms-scales.data",
    "route.hcm.configuration.settings-performance.page",
    "route.hcm.configuration.settings-performance.data",
}


class ProductAuthorizationV55Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v54.json").read_text())
        cls.bundle = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v55.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.bundle["routes"]}

    def test_exact_draft_four_route_successor_preserves_every_existing_route(self) -> None:
        self.assertEqual((55, "DRAFT", CHECKSUM), (
            self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]))
        self.assertEqual(1104, len(self.routes))
        self.assertEqual({"PAGE": 156, "DATA": 336, "ACTION": 612},
                         Counter(route["routeKind"] for route in self.routes.values()))
        old = {route["routeContractKey"]: route for route in self.previous["routes"]}
        self.assertEqual(ROUTES, set(self.routes) - set(old))
        for key, route in old.items():
            self.assertEqual(route, self.routes[key], key)
        for section in ("capabilities", "accessPolicies", "entitlementExpressions", "predicatePolicies"):
            before, after = copy.deepcopy(self.previous[section]), copy.deepcopy(self.bundle[section])
            self.assertEqual(len(before), len(after))
            for descriptor in before + after:
                descriptor.pop("routeContractKeys", None)
            self.assertEqual(before, after, section)

    def test_exact_people_routes_profiles_scopes_and_projection(self) -> None:
        for stem, surface, ui, path, profile_key, capability, scope, predicate, policy, schema in (
            ("operations.performance-forms-scales", "hcm.operations", "hcm.operations.performance-forms-scales",
             "forms-scales", "full-management", "hcm.operations.performance.view", "TARGET_POPULATION",
             "predicate.hcm-domain-target-population.v1", "per004.iaq-020.fields.v1", "Per004FormsScalesProjectionV2"),
            ("configuration.settings-performance", "hcm.management", "hcm.management.settings-performance",
             "settings-performance", "configuration-workbench-reader", "hcm.configuration.workbench.view", "CONFIG_SCOPE",
             "predicate.hcm-configuration-scope.v1", "per004.iaq-056.fields.v1", "Per004SettingsPerformanceProjectionV2"),
        ):
            for kind in ("page", "data"):
                route = self.routes[f"route.hcm.{stem}.{kind}"]
                self.assertEqual(kind.upper(), route["routeKind"])
                self.assertEqual(surface, route["navigationContextId"])
                self.assertEqual(ui if kind == "page" else None, route["uiRouteId"])
                self.assertEqual(f"wire-authority.hcm.{stem}.v1", route["authorizationEquivalenceKey"])
                self.assertEqual(f"/api/people/v1/hris/performance/workbench/{path}", route["gatewayApiBindings"][0]["path"])
                self.assertEqual(f"/v1/hris/performance/workbench/{path}", route["servicePepBindings"][0]["path"])
                self.assertEqual("people", route["servicePepBindings"][0]["serviceKey"])
                profile = route["accessProfiles"][0]
                self.assertEqual((profile_key, capability, [scope], [predicate], True), (
                    profile["profileKey"], profile["requiredAccess"]["capabilityContractKey"],
                    profile["targetBindingKinds"], profile["predicatePolicyKeys"], profile["readOnly"]))
                self.assertEqual((policy, schema), (
                    profile["responseProjectionBindings"][0]["projectionPolicyKey"],
                    profile["responseProjectionBindings"][0]["responseSchemaKey"]))

    def test_latest_seed_index_and_four_owner_peps_have_exact_lineage(self) -> None:
        for path in (CONTRACTS / "product-surfaces-v1.json", AUTH / "product-surfaces-v1.generated.json",
                     AUTH / "product-surfaces-v1.bundle-v55.generated.json"):
            self.assertEqual(self.bundle, json.loads(path.read_text()))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual((55, CHECKSUM, 55), (index["latestVersion"], index["latestChecksum"], len(index["versions"])))
        self.assertEqual(index, json.loads((AUTH / "product-surfaces-v1.index.generated.json").read_text()))
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                              f"hcm-{service}-pep-v55.generated.json").read_text())
            self.assertEqual((55, CHECKSUM), (pep["registryRef"]["version"], pep["registryRef"]["sha256"]))
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(ROUTES if service == "people" else set(), ROUTES & present)
        self.assertIn(CHECKSUM, (CONTRACTS / "pilot-fixtures.v1.generated.json").read_text())


if __name__ == "__main__":
    unittest.main()
