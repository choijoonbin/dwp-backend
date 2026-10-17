import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PLATFORM_PEP = (
    ROOT / "dwp-platform-server" / "src" / "main" / "resources"
    / "product-authorization" / "hcm-platform-pep-v47.generated.json"
)
V46_SHA256 = "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e"
V47_SHA256 = "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e"
V48_SHA256 = "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043"
V47_PEP_SHA256 = "4afb76601a7c0e3dbf7647b075ad17433ca70107f825071584c4f3ba1bb1caae"

EXPECTED = {
    "route.hcm.operations.time-work-plans.page": (
        "/hr/operations/time/work-plans", "GET", "/api/time/v1/hris/work-plans",
        "time", "/v1/hris/work-plans", "hcm.operations.time.read", ["CONFIG_SCOPE"]),
    "route.hcm.operations.performance-goal-cycles.page": (
        "/hr/operations/performance/goal-cycles", "GET",
        "/api/people/v1/hris/performance/cycles", "people",
        "/v1/hris/performance/cycles", "hcm.operations.talent.read",
        ["TARGET_POPULATION"]),
    "route.hcm.team.leave.page": (
        "/hr/team/leave", "GET", "/api/people/v1/hr/team/absence", "people",
        "/v1/hr/team/absence", "hcm.team.absence.read",
        ["RELATIONSHIP", "TARGET_POPULATION"]),
    "route.hcm.operations.time-leave-operations.page": (
        "/hr/operations/time/leave-operations", "GET",
        "/api/people/v1/hr/operations/{domain}", "people",
        "/v1/hr/operations/{domain}", "hcm.operations.absence.read",
        ["TARGET_POPULATION"]),
    "route.hcm.operations.home.page": (
        "/hr/operations/home", "GET",
        "/api/people/v1/workforce/operations/overview", "people",
        "/v1/workforce/operations/overview", None, []),
}


class ProductAuthorizationV47Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v46 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v46.json").read_text()
        )
        cls.v47 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v47.json").read_text()
        )
        cls.v48 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v48.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v47["routes"]}

    def test_v47_appends_only_the_five_canonical_page_routes(self) -> None:
        self.assertEqual((46, V46_SHA256), (self.v46["version"], self.v46["checksum"]))
        self.assertEqual((47, V47_SHA256, "DRAFT"), (
            self.v47["version"], self.v47["checksum"], self.v47["bundleStatus"]
        ))
        self.assertEqual((258, 24, 17, 73, 1053), (
            len(self.v47["capabilities"]), len(self.v47["accessPolicies"]),
            len(self.v47["entitlementExpressions"]),
            len(self.v47["predicatePolicies"]), len(self.v47["routes"]),
        ))
        previous = {route["routeContractKey"]: route for route in self.v46["routes"]}
        self.assertEqual(set(EXPECTED), set(self.routes) - set(previous))
        self.assertEqual(previous, {key: self.routes[key] for key in previous})

    def test_canonical_page_routes_reuse_only_the_sealed_first_reads(self) -> None:
        for key, expected in EXPECTED.items():
            path, method, gateway, service_key, service, capability, targets = expected
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            with self.subTest(route=key):
                self.assertEqual(("PAGE", "ACTIVE", path), (
                    route["routeKind"], route["lifecycleState"],
                    route["uiRoutePattern"],
                ))
                self.assertEqual((method, gateway), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual((method, service_key, service), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["serviceKey"],
                    route["servicePepBindings"][0]["path"],
                ))
                self.assertEqual(targets, profile["targetBindingKinds"])
                if capability is not None:
                    self.assertEqual(capability,
                                     profile["requiredAccess"]["capabilityContractKey"])
        time_route = self.routes["route.hcm.operations.time-work-plans.page"]
        self.assertEqual({"effectiveOn": {"kind": "REQUIRED"}},
                         time_route["gatewayApiBindings"][0]["queryParameterConstraints"])
        leave_route = self.routes[
            "route.hcm.operations.time-leave-operations.page"
        ]
        self.assertEqual({"domain": {"kind": "FIXED", "value": "ABSENCE"}},
                         leave_route["gatewayApiBindings"][0]["pathParameterConstraints"])

    def test_latest_alias_advances_to_v48_while_platform_pep_remains_pinned_to_v47(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        projection = json.loads(PLATFORM_PEP.read_text())
        self.assertEqual(self.v48, alias)
        self.assertEqual(self.v48, seed)
        self.assertEqual((48, V48_SHA256), (
            index["latestVersion"], index["latestChecksum"]
        ))
        self.assertEqual({
            "bundleKey": "product-surfaces", "version": 47, "sha256": V47_SHA256,
        }, projection["registryRef"])
        self.assertEqual(V47_PEP_SHA256, projection["projectionChecksum"])

    def test_deferred_aliases_are_not_part_of_the_new_page_set(self) -> None:
        self.assertTrue({
            "/hr/operations/time", "/hr/operations/pay",
            "/hr/operations/talent", "/hr/data/exports",
        }.isdisjoint({self.routes[key]["uiRoutePattern"] for key in EXPECTED}))


if __name__ == "__main__":
    unittest.main()
