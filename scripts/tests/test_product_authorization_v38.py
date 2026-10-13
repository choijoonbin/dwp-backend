import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
V38_SHA256 = "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a"

WAVE2_ROUTES = {
    "route.hcm.personal.employment-documents.page",
    "route.hcm.operations.employment-assignments.page",
    "route.hcm.operations.assignment-event-create.action",
    "route.hcm.operations.assignment-event-validate.action",
    "route.hcm.operations.assignment-event-submit.action",
    "route.hcm.operations.assignment-event-publish.action",
    "route.hcm.operations.assignment-event-detail.data",
    "route.hcm.operations.assignment-command-receipt.data",
    "route.hcm.operations.time-interpretation.page",
    "route.hcm.operations.time-interpretation-run.action",
    "route.hcm.operations.time-interpretation-trace.data",
    "route.hcm.configuration.payroll.page",
    "route.hcm.configuration.payroll-element-version-create.action",
    "route.hcm.configuration.payroll-formula-version-create.action",
    "route.hcm.configuration.payroll-formula-validate.action",
    "route.hcm.configuration.payroll-formula-simulate.action",
    "route.hcm.configuration.payroll-formula-publish.action",
}


class ProductAuthorizationV38Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v37_bytes = (CONTRACTS / "product-surfaces-v1.bundle-v37.json").read_bytes()
        cls.v37_seed_bytes = (
            AUTH_SEEDS / "product-surfaces-v1.bundle-v37.generated.json"
        ).read_bytes()
        cls.v38 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v38.json").read_text()
        )

    def test_v37_is_byte_identical_between_registry_and_auth_seed(self) -> None:
        self.assertEqual(self.v37_bytes, self.v37_seed_bytes)
        self.assertEqual(
            "1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c",
            json.loads(self.v37_bytes)["checksum"],
        )

    def test_v38_is_the_exact_append_only_wave2_release(self) -> None:
        self.assertEqual(38, self.v38["version"])
        self.assertEqual(V38_SHA256, self.v38["checksum"])
        self.assertEqual(242, len(self.v38["capabilities"]))
        self.assertEqual(24, len(self.v38["accessPolicies"]))
        self.assertEqual(17, len(self.v38["entitlementExpressions"]))
        self.assertEqual(59, len(self.v38["predicatePolicies"]))
        self.assertEqual(991, len(self.v38["routes"]))
        v37 = json.loads(self.v37_bytes)
        v37_routes = {route["routeContractKey"] for route in v37["routes"]}
        v38_routes = {route["routeContractKey"] for route in self.v38["routes"]}
        self.assertEqual(WAVE2_ROUTES, v38_routes - v37_routes)

    def test_wave2_resolved_permissions_and_receipt_predicate_are_closed(self) -> None:
        capabilities = {
            value["contractKey"]: value["resolvedCapabilityCode"]
            for value in self.v38["capabilities"]
        }
        self.assertEqual("DATA.HRIS_SELF_PROFILE:VIEW", capabilities["hcm.personal.profile.view"])
        self.assertEqual("ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE", capabilities["hcm.operations.assignment.manage"])
        self.assertEqual("DATA.HRIS_COMMAND_RECEIPT:VIEW", capabilities["hcm.command-receipt.view"])
        self.assertEqual("DATA.HR_TIME:VIEW", capabilities["hcm.personal.time.view"])
        self.assertEqual("DATA.HRIS_PAYROLL_INPUT:UPDATE", capabilities["hcm.operations.payroll-inputs.update"])
        routes = {value["routeContractKey"]: value for value in self.v38["routes"]}
        receipt = routes["route.hcm.operations.assignment-command-receipt.data"]
        self.assertEqual(
            ["predicate.hcm-originating-command-receipt.v1"],
            receipt["accessProfiles"][0]["predicatePolicyKeys"],
        )
        self.assertEqual(
            "hcm.command-receipt.view",
            receipt["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )

    def test_owner_projections_pin_the_same_v38_registry(self) -> None:
        projections = {
            "people": ROOT / "dwp-people-server/src/main/resources/product-authorization/hcm-people-pep-v38.generated.json",
            "time": ROOT / "dwp-time-server/src/main/resources/product-authorization/hcm-time-pep-v38.generated.json",
            "payroll": ROOT / "dwp-payroll-server/src/main/resources/product-authorization/hcm-payroll-pep-v38.generated.json",
        }
        for owner, path in projections.items():
            with self.subTest(owner=owner):
                projection = json.loads(path.read_text())
                self.assertEqual(38, projection["registryRef"]["version"])
                self.assertEqual(V38_SHA256, projection["registryRef"]["sha256"])
                self.assertEqual(owner, projection["ownerServiceKey"])


if __name__ == "__main__":
    unittest.main()
