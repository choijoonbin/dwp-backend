import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PLATFORM_PEP = (
    ROOT
    / "dwp-platform-server"
    / "src"
    / "main"
    / "resources"
    / "product-authorization"
    / "hcm-platform-pep-v43.generated.json"
)
V42_SHA256 = "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983"
V43_SHA256 = "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400"

ROUTES = {
    "route.hcm.utility.explorer.page": (
        "PAGE", "GET", "/api/platform/v1/hris/explorer", "/v1/hris/explorer"
    ),
    "route.hcm.utility.explorer-preferences-update.action": (
        "ACTION", "PUT", "/api/platform/v1/hris/explorer/preferences",
        "/v1/hris/explorer/preferences"
    ),
    "route.hcm.utility.explorer-recent-create.action": (
        "ACTION", "POST", "/api/platform/v1/hris/explorer/recents",
        "/v1/hris/explorer/recents"
    ),
    "route.hcm.utility.explorer-command-receipt.data": (
        "DATA", "GET", "/api/platform/v1/admin/hris/command-receipts/{receiptId}",
        "/v1/admin/hris/command-receipts/{receiptId}"
    ),
}


class ProductAuthorizationV43Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v42 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v42.json").read_text())
        cls.v43 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v43.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.v43["routes"]}

    def test_v43_is_the_exact_append_only_work_explorer_carrier(self) -> None:
        self.assertEqual((42, V42_SHA256), (self.v42["version"], self.v42["checksum"]))
        self.assertEqual((43, V43_SHA256, "DRAFT"), (
            self.v43["version"], self.v43["checksum"], self.v43["bundleStatus"]
        ))
        self.assertEqual((256, 24, 17, 71, 1032), (
            len(self.v43["capabilities"]), len(self.v43["accessPolicies"]),
            len(self.v43["entitlementExpressions"]), len(self.v43["predicatePolicies"]),
            len(self.v43["routes"]),
        ))
        self.assertEqual({"PAGE": 133, "DATA": 309, "ACTION": 590}, {
            kind: sum(route["routeKind"] == kind for route in self.v43["routes"])
            for kind in ("PAGE", "DATA", "ACTION")
        })
        for section, identity, expected in (
            ("capabilities", "contractKey", {"hcm.app.shell.view"}),
            ("predicatePolicies", "predicatePolicyKey", {
                "predicate.hcm-explorer-authority.v1",
                "predicate.hcm-platform-command-receipt-authority.v1",
            }),
            ("routes", "routeContractKey", set(ROUTES)),
        ):
            before = {item[identity] for item in self.v42[section]}
            after = {item[identity] for item in self.v43[section]}
            self.assertEqual(expected, after - before)
            self.assertTrue(before.issubset(after))

    def test_routes_have_only_the_four_sealed_platform_bindings(self) -> None:
        for key, (kind, method, gateway_path, service_path) in ROUTES.items():
            route = self.routes[key]
            with self.subTest(route=key):
                self.assertEqual((kind, "hcm.utility", "hcm.utility"), (
                    route["routeKind"], route["navigationContextId"],
                    route["subject"]["surfaceKey"],
                ))
                self.assertNotIn("stepUpCommandBindings", route)
                self.assertEqual((method, gateway_path), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual((method, service_path, "platform"), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ))
        receipt_profiles = {
            profile["profileKey"]: profile
            for profile in self.routes[
                "route.hcm.utility.explorer-command-receipt.data"
            ]["accessProfiles"]
        }
        self.assertEqual(
            {"explorer-originating-receipt-reader", "platform-originating-command-receipt-reader"},
            set(receipt_profiles),
        )
        self.assertEqual(
            "hcm.command-receipt.view",
            receipt_profiles["platform-originating-command-receipt-reader"]
            ["requiredAccess"]["capabilityContractKey"],
        )

    def test_shell_capability_has_one_closed_responsibility_mapping(self) -> None:
        capability = next(
            value for value in self.v43["capabilities"]
            if value["contractKey"] == "hcm.app.shell.view"
        )
        self.assertEqual((
            "DATA.HRIS_APP_SHELL:VIEW", "REQUIRED", "HRIS_DUTY_APP_SHELL_READER",
            "SELF", True,
        ), (
            capability["resolvedCapabilityCode"], capability["responsibilityRequirement"],
            capability["requiredResponsibilityCode"], capability["scopeResolver"],
            capability["requiresProductEntitlement"],
        ))

        spec = importlib.util.spec_from_file_location(
            "v43_authorization", ROOT / "scripts" / "generate-product-authorization-contracts.py"
        )
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        source = copy.deepcopy(generator.load_source())
        wave = next(value for value in source["waves"] if value["version"] == 43)
        wave["capabilities"][0]["requiredResponsibilityCode"] = "HRIS_DUTY_ARBITRARY"
        enrichment = next(
            value for value in source["descriptorEnrichments"]["capabilities"]
            if value["contractKey"] == "hcm.app.shell.view"
        )
        enrichment["requiredResponsibilityCode"] = "HRIS_DUTY_ARBITRARY"
        with self.assertRaises(generator.ContractError):
            generator.build_snapshots(source)

    def test_immutable_versioned_seed_and_platform_projection_are_v43(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v43.json").read_text())
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.bundle-v43.generated.json").read_text())
        projection = json.loads(PLATFORM_PEP.read_text())
        self.assertEqual(self.v43, alias)
        self.assertEqual(self.v43, seed)
        self.assertEqual({
            "bundleKey": "product-surfaces", "version": 43, "sha256": V43_SHA256,
        }, projection["registryRef"])
        self.assertEqual((34, 35, 35, 36), (
            projection["projectedRouteContractCount"], projection["bindingPairCount"],
            sum(len(route["servicePepBindings"]) for route in projection["routes"]),
            sum(len(route["gatewayApiBindings"]) for route in projection["routes"]),
        ))
        self.assertEqual((18, 4, 3, 7), (
            len(projection["capabilities"]), len(projection["accessPolicies"]),
            len(projection["entitlementExpressions"]), len(projection["predicatePolicies"]),
        ))


if __name__ == "__main__":
    unittest.main()
