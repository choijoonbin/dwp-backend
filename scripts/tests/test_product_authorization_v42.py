import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
V42_SHA256 = "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983"

CAPABILITIES = {
    "hcm.audit.evidence.view",
    "hcm.team.performance.review",
    "per.review.self.submit",
}
PREDICATES = {
    "predicate.hcm-audit-evidence-authority.v1",
    "predicate.hcm-performance-review-assignment.v1",
    "predicate.hcm-time-command-receipt-authority.v1",
    "predicate.hcm-timecard-authority.v1",
    "predicate.hcm-timecard-decision-sod.v1",
}
PER_ROUTES = {
    "route.hcm.operations.performance-multirater-surveys.page",
    "route.hcm.performance.assigned-review-draft-update.action",
    "route.hcm.performance.assigned-review-submit.action",
    "route.hcm.performance.assigned-review.data",
}
TIM_ROUTES = {
    "route.hcm.time.command-receipt.data",
    "route.hcm.time.timecard-correction.action",
    "route.hcm.time.timecard-decision.action",
    "route.hcm.time.timecard-submit.action",
    "route.hcm.time.timecard.data",
}
SYS_ROUTES = {
    "route.hcm.audit.evidence.page",
    "route.hcm.audit.evidence-detail.data",
}
ROUTES = PER_ROUTES | TIM_ROUTES | SYS_ROUTES


class ProductAuthorizationV42Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v41 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v41.json").read_text())
        cls.v42 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v42.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.v42["routes"]}

    def test_v42_is_exact_combined_append_only_control_bridge(self) -> None:
        self.assertEqual(42, self.v42["version"])
        self.assertEqual(V42_SHA256, self.v42["checksum"])
        self.assertEqual("DRAFT", self.v42["bundleStatus"])
        self.assertEqual((255, 24, 17, 69, 1028), (
            len(self.v42["capabilities"]), len(self.v42["accessPolicies"]),
            len(self.v42["entitlementExpressions"]), len(self.v42["predicatePolicies"]),
            len(self.v42["routes"]),
        ))
        self.assertEqual({"PAGE": 132, "DATA": 308, "ACTION": 588}, {
            kind: sum(route["routeKind"] == kind for route in self.v42["routes"])
            for kind in ("PAGE", "DATA", "ACTION")
        })
        for section, identity, expected in (
            ("capabilities", "contractKey", CAPABILITIES),
            ("predicatePolicies", "predicatePolicyKey", PREDICATES),
            ("routes", "routeContractKey", ROUTES),
        ):
            before = {item[identity] for item in self.v41[section]}
            after = {item[identity] for item in self.v42[section]}
            self.assertEqual(expected, after - before)
            self.assertTrue(before.issubset(after))

    def test_per_and_tim_routes_are_normal_and_never_step_up_commands(self) -> None:
        self.assertTrue(all(
            route.get("stepUpCommandBindings") is None
            for route in (self.routes[key] for key in PER_ROUTES | TIM_ROUTES)
        ))
        for key in (
            "route.hcm.time.timecard.data",
            "route.hcm.time.timecard-correction.action",
        ):
            self.assertEqual({
                "type": "CAPABILITY_EXPRESSION",
                "mode": "ANY",
                "capabilityContractKeys": ["hcm.operations.time.update"],
            }, self.routes[key]["accessProfiles"][0]["requiredAccess"])
        assigned = self.routes["route.hcm.performance.assigned-review.data"]
        self.assertEqual(["OBJECT", "TARGET_POPULATION"], assigned["accessProfiles"][0]["targetBindingKinds"])
        self.assertEqual(
            ["predicate.hcm-performance-review-assignment.v1"],
            assigned["accessProfiles"][0]["predicatePolicyKeys"],
        )

    def test_sys_audit_routes_are_exact_elevated_read_only_closed_projections(self) -> None:
        expected = {
            "route.hcm.audit.evidence.page": ("/api/platform/v1/admin/audit-control/events", "/v1/admin/audit-control/events", "HcmAuditEvidencePageV1"),
            "route.hcm.audit.evidence-detail.data": ("/api/platform/v1/admin/audit-control/events/{eventId}", "/v1/admin/audit-control/events/{eventId}", "HcmAuditEvidenceV1"),
        }
        fixed_product = {"product": {"kind": "FIXED", "value": "HCM"}}
        for key, (public_path, owner_path, schema) in expected.items():
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            with self.subTest(route=key):
                self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
                self.assertTrue(profile["readOnly"])
                self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
                self.assertEqual({"type": "CAPABILITY", "capabilityContractKey": "hcm.audit.evidence.view"}, profile["requiredAccess"])
                self.assertEqual(["predicate.hcm-audit-evidence-authority.v1"], profile["predicatePolicyKeys"])
                self.assertEqual(schema, profile["responseProjectionBindings"][0]["responseSchemaKey"])
                self.assertNotIn("stepUpCommandBindings", route)
                self.assertEqual(("GET", public_path, fixed_product), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                    route["gatewayApiBindings"][0]["queryParameterConstraints"],
                ))
                self.assertEqual(("GET", owner_path, "platform", fixed_product), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                    route["servicePepBindings"][0]["queryParameterConstraints"],
                ))

    def test_latest_alias_index_and_owner_projections_are_v42(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(self.v42, alias)
        self.assertEqual(self.v42, seed)
        self.assertEqual((42, V42_SHA256), (index["latestVersion"], index["latestChecksum"]))
        expected = {
            "dwp-people-server/src/main/resources/product-authorization/hcm-people-pep-v42.generated.json": (97, 124, {"PAGE": 30, "DATA": 19, "ACTION": 48}),
            "dwp-time-server/src/main/resources/product-authorization/hcm-time-pep-v42.generated.json": (20, 20, {"PAGE": 3, "DATA": 5, "ACTION": 12}),
            "dwp-platform-server/src/main/resources/product-authorization/hcm-platform-pep-v42.generated.json": (30, 31, {"PAGE": 5, "DATA": 8, "ACTION": 17}),
        }
        for path, (routes, bindings, kinds) in expected.items():
            projection = json.loads((ROOT / path).read_text())
            self.assertEqual({"bundleKey": "product-surfaces", "version": 42, "sha256": V42_SHA256}, projection["registryRef"])
            self.assertEqual((routes, bindings, kinds), (projection["projectedRouteContractCount"], projection["bindingPairCount"], projection["routeKindCounts"]))

    def test_sys_no_command_binding_exception_is_exact_and_closed(self) -> None:
        spec = importlib.util.spec_from_file_location(
            "v42_authorization",
            ROOT / "scripts" / "generate-product-authorization-contracts.py",
        )
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        for mutation in ("route-key", "method", "query", "mode", "read-only", "projection"):
            source = copy.deepcopy(generator.load_source())
            wave = next(item for item in source["waves"] if item["version"] == 42)
            route = next(item for item in wave["routes"] if item["routeContractKey"] == "route.hcm.audit.evidence.page")
            profile = route["accessProfiles"][0]
            if mutation == "route-key":
                route["routeContractKey"] += "-copy"
            elif mutation == "method":
                route["gatewayApiBindings"][0]["method"] = "POST"
                route["servicePepBindings"][0]["method"] = "POST"
            elif mutation == "query":
                route["gatewayApiBindings"][0]["queryParameterConstraints"]["product"]["value"] = "PAY"
            elif mutation == "mode":
                profile["activeAccessModes"] = ["NORMAL", "ELEVATED"]
            elif mutation == "read-only":
                profile["readOnly"] = False
            else:
                profile.pop("responseSchemaKey")
            with self.subTest(mutation=mutation), self.assertRaises(generator.ContractError):
                generator.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
