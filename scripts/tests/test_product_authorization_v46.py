import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PLATFORM_PEP = (
    ROOT / "dwp-platform-server" / "src" / "main" / "resources"
    / "product-authorization" / "hcm-platform-pep-v46.generated.json"
)
V45_SHA256 = "6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64"
V46_SHA256 = "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e"
PROJECTION_SHA256 = "a3bf5dbd0438fe6cfa68c2d187521beacc98b275c35d12b26c60c41bf5d731fd"

SIX_POLICY_SELF_ROUTES = {
    "route.hcm.personal.me.page": ("/hr/me", "hcm.personal-core-access.v1"),
    "route.hcm.personal.time.page": ("/hr/time", "hcm.personal-core-access.v1"),
    "route.hcm.personal.absence.page": ("/hr/absence", "hcm.personal-core-access.v1"),
    "route.hcm.personal.pay.page": ("/hr/pay", "hcm.personal-core-access.v1"),
    "route.hcm.personal.talent.page": ("/hr/talent", "hcm.personal-core-access.v1"),
    "route.hcm.personal.services.page": ("/hr/services", "hcm.personal-services-access.v1"),
}

V3_DATA_ROUTES = {
    "route.hcm.shell.navigation-aggregate-v3.data": (
        "/api/platform/v3/hris/navigation/projection",
        "/v3/hris/navigation/projection",
        "HrisNavigationAuthorityAggregateV3",
    ),
    "route.hcm.shell.navigation-deep-link-decision-v3.data": (
        "/api/platform/v3/hris/navigation/deep-link-decision",
        "/v3/hris/navigation/deep-link-decision",
        "HrisNavigationAuthorityDeepLinkDecisionV3",
    ),
}


class ProductAuthorizationV46Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v45 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v45.json").read_text()
        )
        cls.v46 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v46.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v46["routes"]}

    def test_v46_is_exact_append_only_v3_tenant_self_carrier(self) -> None:
        self.assertEqual((45, V45_SHA256), (self.v45["version"], self.v45["checksum"]))
        self.assertEqual((46, V46_SHA256, "DRAFT"), (
            self.v46["version"], self.v46["checksum"], self.v46["bundleStatus"]
        ))
        self.assertEqual((258, 24, 17, 73, 1048), (
            len(self.v46["capabilities"]), len(self.v46["accessPolicies"]),
            len(self.v46["entitlementExpressions"]), len(self.v46["predicatePolicies"]),
            len(self.v46["routes"]),
        ))
        added = set(self.routes) - {
            route["routeContractKey"] for route in self.v45["routes"]
        }
        self.assertEqual(set(V3_DATA_ROUTES), added)
        self.assertEqual(
            {route["routeContractKey"]: route for route in self.v45["routes"]},
            {key: self.routes[key] for key in {
                route["routeContractKey"] for route in self.v45["routes"]
            }},
        )

    def test_six_server_owned_policy_self_routes_remain_exact(self) -> None:
        for key, (path, policy) in SIX_POLICY_SELF_ROUTES.items():
            route = self.routes[key]
            profile = route["accessProfiles"]
            with self.subTest(route=key):
                self.assertEqual(("ACTIVE", "PAGE", "hcm.personal", path), (
                    route["lifecycleState"], route["routeKind"],
                    route["navigationContextId"], route["uiRoutePattern"],
                ))
                self.assertEqual(1, len(profile))
                self.assertEqual(("full-work", ["SELF"], ["NORMAL", "ELEVATED"]), (
                    profile[0]["profileKey"], profile[0]["targetBindingKinds"],
                    profile[0]["activeAccessModes"],
                ))
                self.assertEqual({"type": "POLICY", "accessPolicyKey": policy},
                                 profile[0]["requiredAccess"])

    def test_v3_routes_and_platform_projection_are_exact(self) -> None:
        for key, (gateway, service, schema) in V3_DATA_ROUTES.items():
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            with self.subTest(route=key):
                self.assertEqual(("DATA", True, "hcm.utility"), (
                    route["routeKind"], route["sideEffectFree"],
                    route["subject"]["surfaceKey"],
                ))
                self.assertEqual(("GET", gateway), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual(("GET", service, "platform"), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ))
                self.assertEqual(("navigation-shell-reader", ["SELF"], schema), (
                    profile["profileKey"], profile["targetBindingKinds"],
                    profile["responseProjectionBindings"][0]["responseSchemaKey"],
                ))
                expected_query = {
                    "navigationScope": {"kind": "FIXED", "value": "TENANT"},
                    "legalEntityPublicId": {"kind": "ABSENT"},
                }
                self.assertEqual(expected_query,
                                 route["gatewayApiBindings"][0]["queryParameterConstraints"])
                self.assertEqual(expected_query,
                                 route["servicePepBindings"][0]["queryParameterConstraints"])

        projection = json.loads(PLATFORM_PEP.read_text())
        self.assertEqual({
            "bundleKey": "product-surfaces", "version": 46, "sha256": V46_SHA256,
        }, projection["registryRef"])
        self.assertEqual(PROJECTION_SHA256, projection["projectionChecksum"])
        self.assertEqual((50, 51, {"ACTION": 27, "DATA": 17, "PAGE": 6}), (
            projection["projectedRouteContractCount"], projection["bindingPairCount"],
            projection["routeKindCounts"],
        ))

    def test_v46_versioned_contract_and_seed_remain_immutable(self) -> None:
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.bundle-v46.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(self.v46, seed)
        entry = next(value for value in index["versions"] if value["version"] == 46)
        self.assertEqual(V46_SHA256, entry["checksum"])
        self.assertEqual("product-surfaces-v1.bundle-v46.json", entry["artifact"])
        self.assertEqual("product-surfaces-v1.bundle-v46.generated.json",
                         entry["authSeedArtifact"])


if __name__ == "__main__":
    unittest.main()
