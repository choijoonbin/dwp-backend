from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V52_CHECKSUM = "b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346"
V52_FILE_SHA256 = "6a7c8888b244e7de4fcc885ec1a04cfb6a10eb845c1fabd5d6edbccbce5f93a0"
V53_CHECKSUM = "c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359"
ROUTES = {
    "route.hcm.operations.contracts-compensation-basis.page",
    "route.hcm.operations.contracts-compensation-basis.data",
    "route.hcm.operations.employment-contract-issue.action",
}


class ProductAuthorizationV53Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v52_path = CONTRACTS / "product-surfaces-v1.bundle-v52.json"
        cls.v52 = json.loads(cls.v52_path.read_text())
        cls.v53 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v53.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v53["routes"]}
        cls.capabilities = {
            capability["contractKey"]: capability
            for capability in cls.v53["capabilities"]
        }

    def test_v53_is_the_exact_draft_append_only_successor(self) -> None:
        self.assertEqual(V52_CHECKSUM, self.v52["checksum"])
        self.assertEqual(
            V52_FILE_SHA256, hashlib.sha256(self.v52_path.read_bytes()).hexdigest()
        )
        self.assertEqual((53, "DRAFT", V53_CHECKSUM), (
            self.v53["version"], self.v53["bundleStatus"], self.v53["checksum"]
        ))
        self.assertEqual((269, 24, 17, 81, 1095), (
            len(self.v53["capabilities"]), len(self.v53["accessPolicies"]),
            len(self.v53["entitlementExpressions"]),
            len(self.v53["predicatePolicies"]), len(self.v53["routes"]),
        ))
        self.assertEqual(
            ROUTES,
            set(self.routes) - {route["routeContractKey"] for route in self.v52["routes"]},
        )
        self.assertEqual(
            {"hcm.operations.contract.issue"},
            set(self.capabilities)
            - {capability["contractKey"] for capability in self.v52["capabilities"]},
        )

    def test_contract_issue_capability_is_exact_high_risk_authority(self) -> None:
        capability = self.capabilities["hcm.operations.contract.issue"]
        self.assertEqual((
            "ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE",
            "ACTION.HRIS_EMPLOYMENT_CONTRACT",
            "ISSUE",
            "PERMISSION_AND_RELATIONSHIP",
            "WORKFORCE_TARGET_POPULATION",
            "HIGH",
            "STEPUP-MGMT-HIGH-V1",
            "REQUIRED",
            "HRIS_DUTY_CONTRACT_ISSUER",
            "NO_APPROVE_OWN_CHANGE",
        ), (
            capability["resolvedCapabilityCode"], capability["resourceKey"],
            capability["action"], capability["authorityMode"],
            capability["scopeResolver"], capability["riskTier"],
            capability["activationPolicy"],
            capability["responsibilityRequirement"],
            capability["requiredResponsibilityCode"], capability["sodPolicyId"],
        ))
        self.assertEqual(
            ["route.hcm.operations.employment-contract-issue.action"],
            capability["routeContractKeys"],
        )

    def test_page_and_data_share_one_wire_and_exact_v1_policy_for_v2_schema(self) -> None:
        page = self.routes["route.hcm.operations.contracts-compensation-basis.page"]
        data = self.routes["route.hcm.operations.contracts-compensation-basis.data"]
        self.assertEqual(
            "wire-authority.hcm.operations.contracts-compensation-basis.v1",
            page["authorizationEquivalenceKey"],
        )
        self.assertEqual(page["authorizationEquivalenceKey"], data["authorizationEquivalenceKey"])
        self.assertEqual(
            page["gatewayApiBindings"][0]["path"],
            data["gatewayApiBindings"][0]["path"],
        )
        self.assertEqual(
            page["servicePepBindings"][0]["path"],
            data["servicePepBindings"][0]["path"],
        )
        for route, kind in ((page, "page"), (data, "data")):
            profile = route["accessProfiles"][0]
            self.assertEqual("hcm.operations.people.view",
                             profile["requiredAccess"]["capabilityContractKey"])
            self.assertEqual(["TARGET_POPULATION"], profile["targetBindingKinds"])
            self.assertEqual(
                "Hrm011ContractsCompensationBasisProjectionV2",
                profile["responseProjectionBindings"][0]["responseSchemaKey"],
            )
            self.assertEqual(
                f"route.hcm.operations.contracts-compensation-basis.{kind}."
                "people-operations-reader.projection.v1",
                profile["responseProjectionBindings"][0]["projectionPolicyKey"],
            )

    def test_action_is_bound_to_relationship_version_and_people_owner(self) -> None:
        action = self.routes["route.hcm.operations.employment-contract-issue.action"]
        profile = action["accessProfiles"][0]
        self.assertEqual(("employment-contract-issuer", False,
                          ["OBJECT", "TARGET_POPULATION"]), (
            profile["profileKey"], profile["readOnly"], profile["targetBindingKinds"]
        ))
        binding = action["stepUpCommandBindings"][0]
        self.assertEqual((
            "WORK_RELATIONSHIP", ["relationshipPublicId"], "COMMAND_HEADER",
            "X-DWP-Expected-Object-Version", "people", "dwp-people-server",
        ), (
            binding["targetType"], binding["targetIdBodyFields"],
            binding["expectedObjectVersionSource"], binding["expectedObjectVersionName"],
            binding["ownerServiceKey"], binding["audience"],
        ))

    def test_historical_index_auth_seed_and_service_peps_pin_v53(self) -> None:
        seed = json.loads((AUTH / "product-surfaces-v1.bundle-v53.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(self.v53, seed)
        historical = next(item for item in index["versions"] if item["version"] == 53)
        self.assertEqual(V53_CHECKSUM, historical["checksum"])
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v53.generated.json"
            ).read_text())
            self.assertEqual(53, pep["registryRef"]["version"])
            self.assertEqual(V53_CHECKSUM, pep["registryRef"]["sha256"])
        people = json.loads((
            ROOT / "dwp-people-server/src/main/resources/product-authorization/"
            "hcm-people-pep-v53.generated.json"
        ).read_text())
        self.assertTrue(ROUTES.issubset(
            {route["routeContractKey"] for route in people["routes"]}
        ))


if __name__ == "__main__":
    unittest.main()
