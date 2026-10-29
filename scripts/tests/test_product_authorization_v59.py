from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
CHECKSUM = "56582749cebf382c5c2669d83a30fb4c81fcb532db2878b15e6a83ced6898094"
CAPABILITIES = {
    "hcm.team.workbench.view",
    "hcm.personal.performance.view",
    "per.feedback.request.create",
    "per.feedback.assigned.submit",
    "per.feedback.aggregate.view",
}
PREDICATES = {
    "predicate.hcm-performance-feedback-request.v1",
    "predicate.hcm-performance-feedback-assignment.v1",
    "predicate.hcm-performance-feedback-release.v1",
}
ROUTES = {
    "route.hcm.team.performance.page",
    "route.hcm.team.performance.data",
    "route.hcm.personal.performance-feedback.page",
    "route.hcm.performance.feedback-request-options.data",
    "route.hcm.performance.feedback-request-create.action",
    "route.hcm.personal.feedback-assignments-mine.data",
    "route.hcm.personal.feedback-assignment-detail.data",
    "route.hcm.personal.feedback-response-submit.action",
    "route.hcm.performance.feedback-release.data",
}


class ProductAuthorizationV59Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v58.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v59.json").read_text()
        )
        cls.routes = {value["routeContractKey"]: value for value in cls.bundle["routes"]}
        cls.capabilities = {
            value["contractKey"]: value for value in cls.bundle["capabilities"]
        }
        cls.predicates = {
            value["predicatePolicyKey"]: value
            for value in cls.bundle["predicatePolicies"]
        }

    def test_exact_draft_successor_and_v58_semantic_preservation(self) -> None:
        self.assertEqual(
            (59, "DRAFT", CHECKSUM),
            (self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]),
        )
        self.assertEqual(
            (276, 24, 17, 86, 1134),
            tuple(
                len(self.bundle[key])
                for key in (
                    "capabilities",
                    "accessPolicies",
                    "entitlementExpressions",
                    "predicatePolicies",
                    "routes",
                )
            ),
        )
        self.assertEqual(
            {"PAGE": 160, "DATA": 345, "ACTION": 629},
            Counter(value["routeKind"] for value in self.bundle["routes"]),
        )
        additions = {
            "capabilities": CAPABILITIES,
            "accessPolicies": set(),
            "entitlementExpressions": set(),
            "predicatePolicies": PREDICATES,
            "routes": ROUTES,
        }
        key_fields = {
            "capabilities": "contractKey",
            "accessPolicies": "accessPolicyKey",
            "entitlementExpressions": "expressionKey",
            "predicatePolicies": "predicatePolicyKey",
            "routes": "routeContractKey",
        }
        for section, key in key_fields.items():
            before = {value[key]: copy.deepcopy(value) for value in self.previous[section]}
            after = {value[key]: copy.deepcopy(value) for value in self.bundle[section]}
            self.assertEqual(additions[section], set(after) - set(before), section)
            for descriptor_key in before:
                before[descriptor_key].pop("routeContractKeys", None)
                after[descriptor_key].pop("routeContractKeys", None)
                self.assertEqual(before[descriptor_key], after[descriptor_key], descriptor_key)

    def test_capabilities_and_predicates_are_exact(self) -> None:
        expected_capabilities = {
            "hcm.team.workbench.view": (
                "DATA.HRIS_TEAM_WORKBENCH:VIEW", "hcm.team",
                "TEAM_TARGET_POPULATION", "HIGH", None, None,
            ),
            "hcm.personal.performance.view": (
                "DATA.HR_TALENT:VIEW", "hcm.personal", "SELF", "MEDIUM", None, None,
            ),
            "per.feedback.request.create": (
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "hcm.personal",
                "SELF", "MEDIUM", None, None,
            ),
            "per.feedback.assigned.submit": (
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:UPDATE", "hcm.personal",
                "FEEDBACK_ASSIGNMENT", "HIGH", None,
                "NO_FEEDBACK_OUTSIDE_ASSIGNMENT",
            ),
            "per.feedback.aggregate.view": (
                "DATA.HRIS_PERFORMANCE_FEEDBACK:VIEW", "hcm.personal",
                "FEEDBACK_RELEASE_POPULATION", "HIGH", None,
                "NO_FEEDBACK_RELEASE_OUTSIDE_POLICY",
            ),
        }
        for key, expected in expected_capabilities.items():
            value = self.capabilities[key]
            self.assertEqual(
                expected,
                (
                    value["resolvedCapabilityCode"], value["surfaceKey"],
                    value["scopeResolver"], value["riskTier"],
                    value["activationPolicy"], value["sodPolicyId"],
                ),
                key,
            )
            self.assertEqual("PERMISSION_AND_RELATIONSHIP", value["authorityMode"])
            self.assertTrue(value["requiresProductEntitlement"])

        expected_predicates = {
            "predicate.hcm-performance-feedback-request.v1": (
                ["SELF", "OBJECT", "TARGET_POPULATION"],
                "PerformanceFeedbackRequestAuthorityEvidenceV1",
            ),
            "predicate.hcm-performance-feedback-assignment.v1": (
                ["OBJECT", "TARGET_POPULATION"],
                "PerformanceFeedbackAssignmentAuthorityEvidenceV1",
            ),
            "predicate.hcm-performance-feedback-release.v1": (
                ["SELF", "OBJECT", "TARGET_POPULATION"],
                "PerformanceFeedbackReleaseAuthorityEvidenceV1",
            ),
        }
        for key, expected in expected_predicates.items():
            value = self.predicates[key]
            self.assertEqual("people", value["ownerServiceKey"])
            self.assertEqual(expected, (value["targetBindingKinds"], value["inputEvidenceSchemaKey"]))

    def test_exact_routes_profiles_and_owner_pep_risk_policy(self) -> None:
        expected = {
            "route.hcm.team.performance.page": ("PAGE", "GET", "hcm.team", 1),
            "route.hcm.team.performance.data": ("DATA", "GET", "hcm.team", 1),
            "route.hcm.personal.performance-feedback.page": ("PAGE", "GET", "hcm.personal", 1),
            "route.hcm.performance.feedback-request-options.data": ("DATA", "GET", "hcm.operations", 2),
            "route.hcm.performance.feedback-request-create.action": ("ACTION", "POST", "hcm.operations", 2),
            "route.hcm.personal.feedback-assignments-mine.data": ("DATA", "GET", "hcm.personal", 1),
            "route.hcm.personal.feedback-assignment-detail.data": ("DATA", "GET", "hcm.personal", 1),
            "route.hcm.personal.feedback-response-submit.action": ("ACTION", "POST", "hcm.personal", 1),
            "route.hcm.performance.feedback-release.data": ("DATA", "GET", "hcm.operations", 3),
        }
        for key, (kind, method, surface, profiles) in expected.items():
            route = self.routes[key]
            self.assertEqual((kind, surface), (route["routeKind"], route["subject"]["surfaceKey"]))
            self.assertEqual(profiles, len(route["accessProfiles"]))
            self.assertEqual(method, route["gatewayApiBindings"][0]["method"])
            self.assertEqual(method, route["servicePepBindings"][0]["method"])
            self.assertEqual("people", route["servicePepBindings"][0]["serviceKey"])
            self.assertNotIn("stepUpCommandBindings", route)
        options = self.routes["route.hcm.performance.feedback-request-options.data"]
        self.assertEqual(
            {
                "cycleVersionId": {"kind": "REQUIRED"},
                "subjectWorkerPublicId": {"kind": "REQUIRED"},
                "subjectAssignmentPublicId": {"kind": "REQUIRED"},
            },
            options["gatewayApiBindings"][0]["queryParameterConstraints"],
        )
        release = self.routes["route.hcm.performance.feedback-release.data"]
        self.assertEqual(
            [
                ("self-feedback-release-reader", 300, ["SELF", "OBJECT"]),
                ("manager-feedback-release-reader", 200, ["OBJECT", "TARGET_POPULATION"]),
                ("operator-feedback-release-reader", 100, ["OBJECT", "TARGET_POPULATION"]),
            ],
            [
                (profile["profileKey"], profile["precedence"], profile["targetBindingKinds"])
                for profile in release["accessProfiles"]
            ],
        )

    def test_current_alias_is_v62_and_v59_service_projections_preserve_lineage(self) -> None:
        auth = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
        latest = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_text()
        )
        for path in (CONTRACTS / "product-surfaces-v1.json",
                     auth / "product-surfaces-v1.generated.json"):
            self.assertEqual(latest, json.loads(path.read_text()), str(path))
        historical_seed = auth / "product-surfaces-v1.bundle-v59.generated.json"
        self.assertEqual(self.bundle, json.loads(historical_seed.read_text()))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual((62, latest["checksum"], 62), (
            index["latestVersion"], index["latestChecksum"], len(index["versions"])
        ))
        historical = next(value for value in index["versions"] if value["version"] == 59)
        self.assertEqual(CHECKSUM, historical["checksum"])
        fixture = json.loads((CONTRACTS / "pilot-fixtures.v1.generated.json").read_text())
        self.assertEqual(62, fixture["registryLineage"]["latestAliasVersion"])
        self.assertEqual(
            {"bundleKey": "product-surfaces", "version": 62,
             "sha256": latest["checksum"]},
            fixture["registryLineage"]["versions"][-1],
        )
        expected_counts = {
            "people": (64, 5, 3, 22, 133, 160, {"PAGE": 46, "DATA": 34, "ACTION": 53}),
            "time": (15, 0, 0, 9, 29, 29, {"PAGE": 7, "DATA": 6, "ACTION": 16}),
            "platform": (22, 4, 3, 11, 69, 70, {"PAGE": 7, "DATA": 21, "ACTION": 41}),
            "payroll": (17, 0, 0, 9, 41, 41, {"PAGE": 5, "DATA": 14, "ACTION": 22}),
        }
        for service, counts in expected_counts.items():
            pep = json.loads((ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/hcm-{service}-pep-v59.generated.json").read_text())
            self.assertEqual((59, CHECKSUM), (pep["registryRef"]["version"], pep["registryRef"]["sha256"]))
            actual = (
                len(pep["capabilities"]), len(pep["accessPolicies"]),
                len(pep["entitlementExpressions"]), len(pep["predicatePolicies"]),
                len(pep["routes"]), pep["bindingPairCount"],
                Counter(route["routeKind"] for route in pep["routes"]),
            )
            self.assertEqual(counts, actual, service)
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(ROUTES if service == "people" else set(), ROUTES & present)


if __name__ == "__main__":
    unittest.main()
