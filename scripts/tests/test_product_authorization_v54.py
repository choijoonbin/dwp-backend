from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V53_CHECKSUM = "c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359"
V53_FILE_SHA256 = "e05e955d86ab13606a4412da8921c93b6f986bdcd03fe53253e545c856f63f0f"
V54_CHECKSUM = "1a199b3ec84b6d6c16aaec57c9be0b09092c5f81e0c5566f893c4dbe0e0deed4"
ROUTES = {
    "route.hcm.configuration.statutory.page",
    "route.hcm.configuration.statutory.data",
    "route.hcm.operations.pay-tax-social-insurance.page",
    "route.hcm.operations.pay-tax-social-insurance.data",
    "route.hcm.operations.country-pack-activate.action",
}


class ProductAuthorizationV54Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v53_path = CONTRACTS / "product-surfaces-v1.bundle-v53.json"
        cls.v53 = json.loads(cls.v53_path.read_text())
        cls.v54 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v54.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v54["routes"]}
        cls.capabilities = {
            capability["contractKey"]: capability
            for capability in cls.v54["capabilities"]
        }

    def test_v54_is_the_exact_draft_append_only_successor(self) -> None:
        self.assertEqual(V53_CHECKSUM, self.v53["checksum"])
        self.assertEqual(
            V53_FILE_SHA256, hashlib.sha256(self.v53_path.read_bytes()).hexdigest()
        )
        self.assertEqual((54, "DRAFT", V54_CHECKSUM), (
            self.v54["version"], self.v54["bundleStatus"], self.v54["checksum"]
        ))
        self.assertEqual((270, 24, 17, 82, 1100), (
            len(self.v54["capabilities"]), len(self.v54["accessPolicies"]),
            len(self.v54["entitlementExpressions"]),
            len(self.v54["predicatePolicies"]), len(self.v54["routes"]),
        ))
        self.assertEqual(
            ROUTES,
            set(self.routes) - {route["routeContractKey"] for route in self.v53["routes"]},
        )
        self.assertEqual(
            {"hcm.operations.statutory.execute"},
            set(self.capabilities)
            - {capability["contractKey"] for capability in self.v53["capabilities"]},
        )

    def test_statutory_capability_and_owner_predicate_are_exact(self) -> None:
        capability = self.capabilities["hcm.operations.statutory.execute"]
        self.assertEqual((
            "ACTION.HRIS_STATUTORY_FILING:EXECUTE",
            "ACTION.HRIS_STATUTORY_FILING", "EXECUTE",
            "PERMISSION_AND_RELATIONSHIP", "PAYROLL_LEGAL_ENTITY_GROUP_SCOPE",
            "HIGH", "STEPUP-MGMT-HIGH-V1", "REQUIRED",
            "HRIS_DUTY_STATUTORY_OPERATOR",
            "PACK_INSTALLER_NE_STATUTORY_APPROVER",
        ), (
            capability["resolvedCapabilityCode"], capability["resourceKey"],
            capability["action"], capability["authorityMode"],
            capability["scopeResolver"], capability["riskTier"],
            capability["activationPolicy"], capability["responsibilityRequirement"],
            capability["requiredResponsibilityCode"], capability["sodPolicyId"],
        ))
        predicate = next(
            value for value in self.v54["predicatePolicies"]
            if value["predicatePolicyKey"]
            == "predicate.hcm-payroll-country-pack-authority.v1"
        )
        self.assertEqual((
            "HcmPayrollCountryPackAuthorityEvidenceV1", "payroll",
            {"type": "object", "properties": {}, "additionalProperties": False},
        ), (
            predicate["inputEvidenceSchemaKey"], predicate["ownerServiceKey"],
            predicate["parameterSchema"],
        ))

    def test_both_read_pairs_share_exact_wire_projection_and_field_policy(self) -> None:
        for page_key, data_key, wire, schema, field_policy, path in (
            (
                "route.hcm.configuration.statutory.page",
                "route.hcm.configuration.statutory.data",
                "wire-authority.hcm.configuration.statutory.v1",
                "Pay003StatutoryConfigurationProjectionV2",
                "pay003.iaq-026.fields.v1",
                "/api/payroll/v1/workbench/configuration/statutory",
            ),
            (
                "route.hcm.operations.pay-tax-social-insurance.page",
                "route.hcm.operations.pay-tax-social-insurance.data",
                "wire-authority.hcm.operations.pay-tax-social-insurance.v1",
                "Pay003TaxSocialInsuranceProjectionV1",
                "pay003.iaq-048.fields.v1",
                "/api/payroll/v1/workbench/pay-tax-social-insurance",
            ),
        ):
            page, data = self.routes[page_key], self.routes[data_key]
            self.assertEqual(wire, page["authorizationEquivalenceKey"])
            self.assertEqual(wire, data["authorizationEquivalenceKey"])
            self.assertEqual(path, page["gatewayApiBindings"][0]["path"])
            self.assertEqual(path, data["gatewayApiBindings"][0]["path"])
            for route in (page, data):
                projection = route["accessProfiles"][0]["responseProjectionBindings"][0]
                self.assertEqual(schema, projection["responseSchemaKey"])
                self.assertEqual(field_policy, projection["projectionPolicyKey"])

    def test_country_pack_action_is_path_and_header_bound_to_payroll(self) -> None:
        action = self.routes["route.hcm.operations.country-pack-activate.action"]
        profile = action["accessProfiles"][0]
        self.assertEqual(("statutory-operator", ["OBJECT", "TARGET_POPULATION"]), (
            profile["profileKey"], profile["targetBindingKinds"]
        ))
        binding = action["stepUpCommandBindings"][0]
        self.assertEqual((
            "COUNTRY_PACK", "packId", "COMMAND_HEADER",
            "X-DWP-Expected-Object-Version", "payroll", "dwp-payroll-server",
        ), (
            binding["targetType"], binding["targetIdPathParameter"],
            binding["expectedObjectVersionSource"], binding["expectedObjectVersionName"],
            binding["ownerServiceKey"], binding["audience"],
        ))

    def test_latest_alias_index_seed_and_owner_peps_pin_v54(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(self.v54, alias)
        self.assertEqual(self.v54, seed)
        self.assertEqual((54, V54_CHECKSUM, 54), (
            index["latestVersion"], index["latestChecksum"], len(index["versions"])
        ))
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v54.generated.json"
            ).read_text())
            self.assertEqual(54, pep["registryRef"]["version"])
            self.assertEqual(V54_CHECKSUM, pep["registryRef"]["sha256"])
        payroll = json.loads((
            ROOT / "dwp-payroll-server/src/main/resources/product-authorization/"
            "hcm-payroll-pep-v54.generated.json"
        ).read_text())
        self.assertEqual((39, 39, {"ACTION": 22, "DATA": 13, "PAGE": 4}, 17, 9), (
            payroll["projectedRouteContractCount"], payroll["bindingPairCount"],
            payroll["routeKindCounts"], len(payroll["capabilities"]),
            len(payroll["predicatePolicies"]),
        ))
        self.assertTrue(ROUTES.issubset(
            {route["routeContractKey"] for route in payroll["routes"]}
        ))


if __name__ == "__main__":
    unittest.main()
