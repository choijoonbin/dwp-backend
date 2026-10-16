import copy
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PLATFORM_PEP = ROOT / "dwp-platform-server" / "src" / "main" / "resources" / "product-authorization" / "hcm-platform-pep-v44.generated.json"
V43_SHA256 = "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400"
V44_SHA256 = "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b"


class ProductAuthorizationV44Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v43 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v43.json").read_text())
        cls.v44 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v44.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.v44["routes"]}

    def test_v44_is_the_exact_append_only_controlled_transfer_carrier(self) -> None:
        self.assertEqual((43, V43_SHA256), (self.v43["version"], self.v43["checksum"]))
        self.assertEqual((44, V44_SHA256, "DRAFT"), (
            self.v44["version"], self.v44["checksum"], self.v44["bundleStatus"]
        ))
        self.assertEqual((258, 24, 17, 73, 1034), (
            len(self.v44["capabilities"]), len(self.v44["accessPolicies"]),
            len(self.v44["entitlementExpressions"]), len(self.v44["predicatePolicies"]),
            len(self.v44["routes"]),
        ))
        additions = {
            section: {item[key] for item in self.v44[section]}
            - {item[key] for item in self.v43[section]}
            for section, key in (
                ("capabilities", "contractKey"),
                ("predicatePolicies", "predicatePolicyKey"),
                ("routes", "routeContractKey"),
            )
        }
        self.assertEqual({
            "hcm.integration.transfer.view", "hcm.integration.transfer.create"
        }, additions["capabilities"])
        self.assertEqual({
            "predicate.hcm-controlled-transfer-authority.v1",
            "predicate.hcm-controlled-transfer-create-sod.v1",
        }, additions["predicatePolicies"])
        self.assertEqual({
            "route.hcm.management.controlled-transfer.data",
            "route.hcm.management.controlled-transfer-create.action",
        }, additions["routes"])

    def test_exact_read_and_create_pep_bindings_are_closed(self) -> None:
        read = self.routes["route.hcm.management.controlled-transfer.data"]
        create = self.routes["route.hcm.management.controlled-transfer-create.action"]
        for route, kind, method, capability, profile in (
            (read, "DATA", "GET", "hcm.integration.transfer.view", "controlled-transfer-reader"),
            (create, "ACTION", "POST", "hcm.integration.transfer.create", "controlled-transfer-creator"),
        ):
            with self.subTest(method=method):
                self.assertEqual((kind, "hcm.management"), (
                    route["routeKind"], route["navigationContextId"]
                ))
                self.assertEqual((method, "/api/platform/v1/admin/hris/file-transfers"), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual((method, "/v1/admin/hris/file-transfers", "platform"), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ))
                self.assertEqual((profile, capability), (
                    route["accessProfiles"][0]["profileKey"],
                    route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
                ))
        self.assertNotIn("stepUpCommandBindings", read)
        self.assertEqual(("CONTROLLED_TRANSFER_OBJECT", ["objectRef", "objectVersion"]), (
            create["stepUpCommandBindings"][0]["targetType"],
            create["stepUpCommandBindings"][0]["targetIdBodyFields"],
        ))

    def test_responsibility_codes_are_not_mutable_aliases(self) -> None:
        spec = importlib.util.spec_from_file_location(
            "v44_authorization", ROOT / "scripts" / "generate-product-authorization-contracts.py"
        )
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        source = copy.deepcopy(generator.load_source())
        wave = next(value for value in source["waves"] if value["version"] == 44)
        wave["capabilities"][0]["requiredResponsibilityCode"] = "HRIS_DUTY_ARBITRARY"
        enrichment = next(value for value in source["descriptorEnrichments"]["capabilities"]
                          if value["contractKey"] == "hcm.integration.transfer.view")
        enrichment["requiredResponsibilityCode"] = "HRIS_DUTY_ARBITRARY"
        with self.assertRaises(generator.ContractError):
            generator.build_snapshots(source)

    def test_latest_alias_auth_seed_and_platform_projection_are_v44(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.generated.json").read_text())
        projection = json.loads(PLATFORM_PEP.read_text())
        self.assertEqual(self.v44, alias)
        self.assertEqual(self.v44, seed)
        self.assertEqual({
            "bundleKey": "product-surfaces", "version": 44, "sha256": V44_SHA256,
        }, projection["registryRef"])
        self.assertEqual((36, 37, {"ACTION": 20, "DATA": 10, "PAGE": 6}), (
            projection["projectedRouteContractCount"], projection["bindingPairCount"],
            projection["routeKindCounts"],
        ))
        self.assertEqual((20, 4, 3, 9), (
            len(projection["capabilities"]), len(projection["accessPolicies"]),
            len(projection["entitlementExpressions"]), len(projection["predicatePolicies"]),
        ))


if __name__ == "__main__":
    unittest.main()
