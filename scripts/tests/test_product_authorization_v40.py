import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
PLATFORM_PROJECTION = (
    ROOT / "dwp-platform-server" / "src" / "main" / "resources"
    / "product-authorization" / "hcm-platform-pep-v40.generated.json"
)
V40_SHA256 = "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329"

CAPABILITIES = {
    "hcm.configuration.navigation-policy.view":
        "CONFIG.HRIS_NAVIGATION_POLICY:VIEW",
    "hcm.configuration.navigation-policy.update":
        "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE",
    "hcm.configuration.navigation-policy.publish":
        "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH",
}
ROUTES = {
    "route.hcm.configuration.navigation-policy-workspace.page",
    "route.hcm.configuration.navigation-policy-draft-create.action",
    "route.hcm.configuration.navigation-policy-draft-update.action",
    "route.hcm.configuration.navigation-policy-draft-validate.action",
    "route.hcm.configuration.navigation-policy-draft-submit.action",
    "route.hcm.configuration.navigation-policy-revision-approve.action",
    "route.hcm.configuration.navigation-policy-revision-publish.action",
    "route.hcm.configuration.navigation-policy-revision-restore.action",
    "route.hcm.operations.navigation-projection.data",
}
HR_OPERATIONS_READERS = {
    "hcm.operations.workforce.read",
    "hcm.operations.time.read",
    "hcm.operations.absence.read",
    "hcm.operations.benefits.read",
    "hcm.operations.pay.read",
    "hcm.operations.talent.read",
    "hcm.compensation.plan.view",
}


class ProductAuthorizationV40Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v39 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v39.json").read_text()
        )
        cls.v40 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v40.json").read_text()
        )
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v40["routes"]
        }

    def test_v40_is_the_exact_append_only_navigation_policy_release(self) -> None:
        self.assertEqual(40, self.v40["version"])
        self.assertEqual(V40_SHA256, self.v40["checksum"])
        self.assertEqual((248, 24, 17, 61, 1012), (
            len(self.v40["capabilities"]),
            len(self.v40["accessPolicies"]),
            len(self.v40["entitlementExpressions"]),
            len(self.v40["predicatePolicies"]),
            len(self.v40["routes"]),
        ))
        for section, identity, expected in (
            ("capabilities", "contractKey", set(CAPABILITIES)),
            ("predicatePolicies", "predicatePolicyKey", {
                "predicate.hcm-navigation-policy-authority.v1"
            }),
            ("routes", "routeContractKey", ROUTES),
        ):
            previous = {value[identity] for value in self.v39[section]}
            current = {value[identity] for value in self.v40[section]}
            self.assertEqual(expected, current - previous)
            self.assertTrue(previous.issubset(current))

    def test_admin_lifecycle_has_exact_capabilities_and_step_up_boundary(self) -> None:
        workspace = self.routes[
            "route.hcm.configuration.navigation-policy-workspace.page"
        ]
        self.assertEqual("hcm.management", workspace["navigationContextId"])
        self.assertEqual(
            workspace["subject"]["surfaceKey"], workspace["navigationContextId"]
        )
        capability_codes = {
            value["contractKey"]: value["resolvedCapabilityCode"]
            for value in self.v40["capabilities"]
            if value["contractKey"] in CAPABILITIES
        }
        self.assertEqual(CAPABILITIES, capability_codes)

        for suffix in ("approve", "publish", "restore"):
            route = self.routes[
                f"route.hcm.configuration.navigation-policy-revision-{suffix}.action"
            ]
            self.assertEqual(
                "hcm.configuration.navigation-policy.publish",
                route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
            )
            self.assertEqual([{
                "audience": "dwp-platform-server",
                "bindingKey": route["routeContractKey"] + ".binding.01",
                "expectedObjectVersionName": "expectedVersion",
                "expectedObjectVersionSource": "COMMAND_BODY",
                "ownerServiceKey": "platform",
                "targetIdPathParameter": "revisionId",
                "targetType": "HRIS_NAVIGATION_POLICY_REVISION",
            }], route["stepUpCommandBindings"])

        for key in ROUTES - {
            f"route.hcm.configuration.navigation-policy-revision-{suffix}.action"
            for suffix in ("approve", "publish", "restore")
        }:
            self.assertNotIn("stepUpCommandBindings", self.routes[key])

    def test_runtime_projection_is_hr_operations_authority_not_config_admin(self) -> None:
        route = self.routes["route.hcm.operations.navigation-projection.data"]
        access = route["accessProfiles"][0]["requiredAccess"]
        self.assertEqual("CAPABILITY_EXPRESSION", access["type"])
        self.assertEqual("ANY", access["mode"])
        self.assertEqual(HR_OPERATIONS_READERS, set(access["capabilityContractKeys"]))
        self.assertTrue(set(CAPABILITIES).isdisjoint(access["capabilityContractKeys"]))
        self.assertEqual(
            "/api/platform/v1/hris/navigation/projection",
            route["gatewayApiBindings"][0]["path"],
        )

    def test_platform_projection_remains_immutably_pinned_to_v40(self) -> None:
        index = json.loads(
            (CONTRACTS / "product-surfaces-v1.index.json").read_text()
        )
        projection = json.loads(PLATFORM_PROJECTION.read_text())

        v40_index = next(value for value in index["versions"] if value["version"] == 40)
        self.assertEqual(V40_SHA256, v40_index["checksum"])
        self.assertEqual(40, projection["registryRef"]["version"])
        self.assertEqual(V40_SHA256, projection["registryRef"]["sha256"])
        self.assertEqual(28, projection["projectedRouteContractCount"])
        self.assertEqual(29, projection["bindingPairCount"])
        self.assertTrue(ROUTES.issubset({
            route["routeContractKey"] for route in projection["routes"]
        }))


if __name__ == "__main__":
    unittest.main()
