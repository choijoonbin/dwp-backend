from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V51_CHECKSUM = "3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c"
V51_FILE_SHA256 = "9c5a2f02551c7eaf38046a696ea0db80e97a16cf9c0a9324d3e515d2e9c6d8aa"
V52_CHECKSUM = "b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346"

ROUTES = {
    "route.hcm.personal.requests.page",
    "route.hcm.personal.requests.data",
    "route.hcm.operations.certificates-documents.page",
    "route.hcm.operations.certificates-documents.data",
    "route.hcm.operations.certificate-issue.action",
}


class ProductAuthorizationV52Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v51_path = CONTRACTS / "product-surfaces-v1.bundle-v51.json"
        cls.v51 = json.loads(cls.v51_path.read_text())
        cls.v52 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v52.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v52["routes"]}
        cls.capabilities = {
            capability["contractKey"]: capability
            for capability in cls.v52["capabilities"]
        }

    def test_v52_is_the_exact_draft_append_only_successor(self) -> None:
        self.assertEqual(V51_CHECKSUM, self.v51["checksum"])
        self.assertEqual(
            V51_FILE_SHA256, hashlib.sha256(self.v51_path.read_bytes()).hexdigest()
        )
        self.assertEqual((52, "DRAFT", V52_CHECKSUM), (
            self.v52["version"], self.v52["bundleStatus"], self.v52["checksum"]
        ))
        self.assertEqual((268, 24, 17, 81, 1092), (
            len(self.v52["capabilities"]), len(self.v52["accessPolicies"]),
            len(self.v52["entitlementExpressions"]),
            len(self.v52["predicatePolicies"]), len(self.v52["routes"]),
        ))
        self.assertEqual(
            ROUTES,
            set(self.routes) - {route["routeContractKey"] for route in self.v51["routes"]},
        )
        self.assertNotIn("route.hcm.personal.certificate-request.action", self.routes)

    def test_capabilities_and_existing_people_view_binding_are_exact(self) -> None:
        expected = {
            "hcm.personal.requests.view": (
                "DATA.HRIS_SELF_REQUEST:VIEW", "SELF", "MEDIUM", None
            ),
            "hcm.personal.certificate.request": (
                "ACTION.HRIS_CERTIFICATE:CREATE", "SELF", "MEDIUM", None
            ),
            "hcm.operations.certificate.issue": (
                "ACTION.HRIS_CERTIFICATE:ISSUE", "WORKFORCE_TARGET_POPULATION",
                "HIGH", "STEPUP-MGMT-HIGH-V1"
            ),
        }
        for key, values in expected.items():
            capability = self.capabilities[key]
            self.assertEqual(values, (
                capability["resolvedCapabilityCode"], capability["scopeResolver"],
                capability["riskTier"], capability["activationPolicy"],
            ))
        people_view = self.capabilities["hcm.operations.people.view"]
        self.assertEqual("DATA.HRIS_WORKFORCE:VIEW", people_view["resolvedCapabilityCode"])
        self.assertTrue({
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data",
        }.issubset(people_view["routeContractKeys"]))

    def test_same_wire_reads_and_exact_data_projections_are_sealed(self) -> None:
        for stem, equivalence, projection in (
            ("personal.requests", "wire-authority.hcm.personal.requests.v1",
             "route.hcm.personal.requests.data.self-request-reader.projection.v1"),
            ("operations.certificates-documents",
             "wire-authority.hcm.operations.certificates-documents.v1",
             "route.hcm.operations.certificates-documents.data."
             "people-operations-reader.projection.v1"),
        ):
            page = self.routes[f"route.hcm.{stem}.page"]
            data = self.routes[f"route.hcm.{stem}.data"]
            self.assertEqual(equivalence, page["authorizationEquivalenceKey"])
            self.assertEqual(equivalence, data["authorizationEquivalenceKey"])
            self.assertEqual(
                page["gatewayApiBindings"][0]["path"],
                data["gatewayApiBindings"][0]["path"],
            )
            self.assertEqual(
                page["servicePepBindings"][0]["path"],
                data["servicePepBindings"][0]["path"],
            )
            self.assertEqual(
                projection,
                data["accessProfiles"][0]["responseProjectionBindings"][0]
                ["projectionPolicyKey"],
            )

    def test_shared_action_has_two_profiles_one_high_wire_and_person_binding(self) -> None:
        action = self.routes["route.hcm.operations.certificate-issue.action"]
        self.assertEqual("ACTION", action["routeKind"])
        self.assertEqual(
            [("certificate-operations-issuer", 300,
              "hcm.operations.certificate.issue"),
             ("certificate-self-requester", 200,
              "hcm.personal.certificate.request")],
            [(profile["profileKey"], profile["precedence"],
              profile["requiredAccess"]["capabilityContractKey"])
             for profile in action["accessProfiles"]],
        )
        binding = action["stepUpCommandBindings"]
        self.assertEqual(1, len(binding))
        self.assertEqual(("PERSON", ["personPublicId"], "COMMAND_HEADER",
                          "X-DWP-Expected-Object-Version", "people",
                          "dwp-people-server"), (
            binding[0]["targetType"], binding[0]["targetIdBodyFields"],
            binding[0]["expectedObjectVersionSource"],
            binding[0]["expectedObjectVersionName"], binding[0]["ownerServiceKey"],
            binding[0]["audience"],
        ))

    def test_v52_auth_seed_and_service_peps_remain_immutable(self) -> None:
        seed = json.loads((AUTH / "product-surfaces-v1.bundle-v52.generated.json").read_text())
        self.assertEqual(self.v52, seed)
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v52.generated.json"
            ).read_text())
            self.assertEqual(52, pep["registryRef"]["version"])
            self.assertEqual(V52_CHECKSUM, pep["registryRef"]["sha256"])
        people = json.loads((
            ROOT / "dwp-people-server/src/main/resources/product-authorization/"
            "hcm-people-pep-v52.generated.json"
        ).read_text())
        self.assertTrue(ROUTES.issubset(
            {route["routeContractKey"] for route in people["routes"]}
        ))


if __name__ == "__main__":
    unittest.main()
