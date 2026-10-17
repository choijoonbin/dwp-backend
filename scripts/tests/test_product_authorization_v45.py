import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PLATFORM_PEP = (
    ROOT / "dwp-platform-server" / "src" / "main" / "resources"
    / "product-authorization" / "hcm-platform-pep-v45.generated.json"
)
V44_SHA256 = "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b"
V45_SHA256 = "6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64"

RUNTIME = {
    "route.hcm.shell.navigation-aggregate.data": (
        "/api/platform/v2/hris/navigation/projection",
        "/v2/hris/navigation/projection",
        "HrisNavigationAggregateV2",
        "hcm.utility", "navigation-shell-reader", ["SELF"], "TENANT", "ABSENT",
    ),
    "route.hcm.shell.navigation-deep-link-decision.data": (
        "/api/platform/v2/hris/navigation/deep-link-decision",
        "/v2/hris/navigation/deep-link-decision",
        "HrisNavigationDeepLinkDecisionV2",
        "hcm.utility", "navigation-shell-reader", ["SELF"], "TENANT", "ABSENT",
    ),
    "route.hcm.shell.navigation-aggregate-legal-entity.data": (
        "/api/platform/v2/hris/navigation/projection",
        "/v2/hris/navigation/projection",
        "HrisNavigationAggregateV2",
        "hcm.operations", "hr-operations-reader", ["TARGET_POPULATION"],
        "LEGAL_ENTITY", "REQUIRED",
    ),
    "route.hcm.shell.navigation-deep-link-decision-legal-entity.data": (
        "/api/platform/v2/hris/navigation/deep-link-decision",
        "/v2/hris/navigation/deep-link-decision",
        "HrisNavigationDeepLinkDecisionV2",
        "hcm.operations", "hr-operations-reader", ["TARGET_POPULATION"],
        "LEGAL_ENTITY", "REQUIRED",
    ),
}

OPERATIONS_READ_CAPABILITIES = {
    "hcm.operations.workforce.read", "hcm.operations.time.read",
    "hcm.operations.absence.read", "hcm.operations.benefits.read",
    "hcm.operations.pay.read", "hcm.operations.talent.read",
    "hcm.compensation.plan.view",
}

ADMIN = {
    "route.hcm.configuration.navigation-policy-v2-workspace.data": (
        "DATA", "GET", "/workspace", "hcm.configuration.navigation-policy.view", False
    ),
    "route.hcm.configuration.navigation-policy-v2-draft-create.action": (
        "ACTION", "POST", "/drafts", "hcm.configuration.navigation-policy.update", False
    ),
    "route.hcm.configuration.navigation-policy-v2-draft-update.action": (
        "ACTION", "PUT", "/drafts/{revisionId}",
        "hcm.configuration.navigation-policy.update", False
    ),
    "route.hcm.configuration.navigation-policy-v2-draft-validate.action": (
        "ACTION", "POST", "/drafts/{revisionId}/validate",
        "hcm.configuration.navigation-policy.update", False
    ),
    "route.hcm.configuration.navigation-policy-v2-draft-submit.action": (
        "ACTION", "POST", "/drafts/{revisionId}/submit",
        "hcm.configuration.navigation-policy.update", False
    ),
    "route.hcm.configuration.navigation-policy-v2-revision-approve.action": (
        "ACTION", "POST", "/revisions/{revisionId}/approve",
        "hcm.configuration.navigation-policy.publish", True
    ),
    "route.hcm.configuration.navigation-policy-v2-revision-publish.action": (
        "ACTION", "POST", "/revisions/{revisionId}/publish",
        "hcm.configuration.navigation-policy.publish", True
    ),
    "route.hcm.configuration.navigation-policy-v2-revision-restore.action": (
        "ACTION", "POST", "/revisions/{revisionId}/restore",
        "hcm.configuration.navigation-policy.publish", True
    ),
}


class ProductAuthorizationV45Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v44 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v44.json").read_text()
        )
        cls.v45 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v45.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v45["routes"]}

    def test_v45_is_only_the_append_only_navigation_v2_carrier(self) -> None:
        self.assertEqual((44, V44_SHA256), (self.v44["version"], self.v44["checksum"]))
        self.assertEqual((45, V45_SHA256, "DRAFT"), (
            self.v45["version"], self.v45["checksum"], self.v45["bundleStatus"]
        ))
        self.assertEqual((258, 24, 17, 73, 1046), (
            len(self.v45["capabilities"]), len(self.v45["accessPolicies"]),
            len(self.v45["entitlementExpressions"]), len(self.v45["predicatePolicies"]),
            len(self.v45["routes"]),
        ))
        self.assertEqual({"PAGE": 133, "DATA": 315, "ACTION": 598}, {
            kind: sum(route["routeKind"] == kind for route in self.v45["routes"])
            for kind in ("PAGE", "DATA", "ACTION")
        })
        for section, identity in (
            ("capabilities", "contractKey"),
            ("accessPolicies", "accessPolicyKey"),
            ("entitlementExpressions", "expressionKey"),
            ("predicatePolicies", "predicatePolicyKey"),
        ):
            self.assertEqual(
                {item[identity] for item in self.v44[section]},
                {item[identity] for item in self.v45[section]},
            )
        added = (
            {route["routeContractKey"] for route in self.v45["routes"]}
            - {route["routeContractKey"] for route in self.v44["routes"]}
        )
        self.assertEqual(set(RUNTIME) | set(ADMIN), added)

    def test_runtime_uses_shell_authority_without_replacing_node_page_intersection(self) -> None:
        for key, (
            gateway_path, service_path, response_schema, surface, profile_key,
            target_kinds, navigation_scope, legal_entity_constraint,
        ) in RUNTIME.items():
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            with self.subTest(route=key):
                self.assertEqual(("DATA", True, surface), (
                    route["routeKind"], route["sideEffectFree"],
                    route["subject"]["surfaceKey"],
                ))
                self.assertEqual(("GET", gateway_path), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual(("GET", service_path, "platform"), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ))
                self.assertEqual((profile_key, target_kinds, response_schema), (
                    profile["profileKey"],
                    profile["targetBindingKinds"],
                    profile["responseProjectionBindings"][0]["responseSchemaKey"],
                ))
                if surface == "hcm.utility":
                    self.assertEqual({
                        "type": "CAPABILITY",
                        "capabilityContractKey": "hcm.app.shell.view",
                    }, profile["requiredAccess"])
                else:
                    self.assertEqual("CAPABILITY_EXPRESSION",
                                     profile["requiredAccess"]["type"])
                    self.assertEqual("ANY", profile["requiredAccess"]["mode"])
                    self.assertEqual(OPERATIONS_READ_CAPABILITIES, set(
                        profile["requiredAccess"]["capabilityContractKeys"]
                    ))
                expected_query = {
                    "navigationScope": {"kind": "FIXED", "value": navigation_scope},
                    "legalEntityPublicId": {"kind": legal_entity_constraint},
                }
                self.assertEqual(expected_query,
                                 route["gatewayApiBindings"][0]
                                 ["queryParameterConstraints"])
                self.assertEqual(expected_query,
                                 route["servicePepBindings"][0]
                                 ["queryParameterConstraints"])
                self.assertIn("intersection", profile["responseProjectionBindings"][0]
                              ["projectionPolicyKey"])
                self.assertNotIn("stepUpCommandBindings", route)

    def test_admin_routes_keep_view_update_and_publish_authorities_disjoint(self) -> None:
        root = "/v2/admin/hris/navigation-policies"
        for key, (kind, method, suffix, capability, step_up) in ADMIN.items():
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            with self.subTest(route=key):
                self.assertEqual((kind, method, "/api/platform" + root + suffix), (
                    route["routeKind"], route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual((method, root + suffix, "platform"), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ))
                self.assertEqual(capability,
                                 profile["requiredAccess"]["capabilityContractKey"])
                if step_up:
                    binding = route["stepUpCommandBindings"][0]
                    self.assertEqual((
                        "HRIS_NAVIGATION_POLICY_REVISION", "revisionId", "COMMAND_BODY",
                        "expectedVersion", "platform", "dwp-platform-server",
                    ), (
                        binding["targetType"], binding["targetIdPathParameter"],
                        binding["expectedObjectVersionSource"],
                        binding["expectedObjectVersionName"], binding["ownerServiceKey"],
                        binding["audience"],
                    ))
                else:
                    self.assertNotIn("stepUpCommandBindings", route)

    def test_immutable_v45_platform_projection_remains_pinned(self) -> None:
        projection = json.loads(PLATFORM_PEP.read_text())
        self.assertEqual({
            "bundleKey": "product-surfaces", "version": 45, "sha256": V45_SHA256,
        }, projection["registryRef"])
        self.assertEqual((48, 49, {"ACTION": 27, "DATA": 15, "PAGE": 6}), (
            projection["projectedRouteContractCount"], projection["bindingPairCount"],
            projection["routeKindCounts"],
        ))
        self.assertEqual((20, 4, 3, 9), (
            len(projection["capabilities"]), len(projection["accessPolicies"]),
            len(projection["entitlementExpressions"]),
            len(projection["predicatePolicies"]),
        ))


if __name__ == "__main__":
    unittest.main()
