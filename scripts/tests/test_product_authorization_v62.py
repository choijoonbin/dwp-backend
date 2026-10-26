from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
CHECKSUM = "cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770"
CAPABILITY = "approvals.work.hris-separation-waiver-task.approve"
ROUTE = "route.approvals.work.hris-separation-waiver-task-approve.action"


class ProductAuthorizationV62Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v61.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_text()
        )
        cls.routes = {value["routeContractKey"]: value for value in cls.bundle["routes"]}
        cls.capabilities = {
            value["contractKey"]: value for value in cls.bundle["capabilities"]
        }

    def test_exact_draft_successor_and_v61_semantic_preservation(self) -> None:
        self.assertEqual(
            (62, "DRAFT", CHECKSUM),
            (self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]),
        )
        self.assertEqual(
            (280, 24, 17, 87, 1158),
            tuple(len(self.bundle[key]) for key in (
                "capabilities", "accessPolicies", "entitlementExpressions",
                "predicatePolicies", "routes",
            )),
        )
        self.assertEqual(
            {"PAGE": 162, "DATA": 349, "ACTION": 647},
            Counter(value["routeKind"] for value in self.bundle["routes"]),
        )
        additions = {
            "capabilities": {CAPABILITY},
            "accessPolicies": set(),
            "entitlementExpressions": set(),
            "predicatePolicies": set(),
            "routes": {ROUTE},
        }
        keys = {
            "capabilities": "contractKey",
            "accessPolicies": "accessPolicyKey",
            "entitlementExpressions": "expressionKey",
            "predicatePolicies": "predicatePolicyKey",
            "routes": "routeContractKey",
        }
        for section, key in keys.items():
            before = {value[key]: copy.deepcopy(value) for value in self.previous[section]}
            after = {value[key]: copy.deepcopy(value) for value in self.bundle[section]}
            self.assertEqual(additions[section], set(after) - set(before), section)
            for descriptor_key in before:
                before[descriptor_key].pop("routeContractKeys", None)
                after[descriptor_key].pop("routeContractKeys", None)
                self.assertEqual(before[descriptor_key], after[descriptor_key], descriptor_key)

    def test_dedicated_hrm013_approval_capability_and_route_are_exact(self) -> None:
        capability = self.capabilities[CAPABILITY]
        self.assertEqual("approvals", capability["productKey"])
        self.assertEqual("approvals.work", capability["surfaceKey"])
        self.assertEqual("Approvals + Security", capability["owner"])
        self.assertEqual("ACTION.APPROVAL_TASK:APPROVE",
                         capability["resolvedCapabilityCode"])
        self.assertEqual("ACTION.APPROVAL_TASK", capability["resourceKey"])
        self.assertEqual("APPROVE", capability["action"])
        self.assertEqual("PERMISSION", capability["authorityMode"])
        self.assertEqual("DECIDABLE_TASK_SELF_APPROVAL_SOD",
                         capability["scopeResolver"])
        self.assertEqual("HIGH", capability["riskTier"])
        self.assertEqual("STEPUP-MGMT-HIGH-V1", capability["activationPolicy"])
        self.assertEqual("NOT_REQUIRED", capability["responsibilityRequirement"])
        self.assertIsNone(capability["sodPolicyId"])
        self.assertTrue(capability["requiresProductEntitlement"])
        self.assertEqual([ROUTE], capability["routeContractKeys"])

        route = self.routes[ROUTE]
        self.assertEqual("approvals.work", route["navigationContextId"])
        self.assertEqual(
            {"type": "PRODUCT", "productKey": "approvals",
             "surfaceKey": "approvals.work"},
            route["subject"],
        )
        self.assertEqual("ACTION", route["routeKind"])
        self.assertIsNone(route["sideEffectFree"])
        self.assertIsNone(route["uiRouteId"])
        self.assertIsNone(route["uiRoutePattern"])
        self.assertEqual(1, len(route["accessProfiles"]))
        self.assertEqual(1, len(route["gatewayApiBindings"]))
        self.assertEqual(1, len(route["servicePepBindings"]))
        self.assertEqual(1, len(route["stepUpCommandBindings"]))
        gateway = route["gatewayApiBindings"][0]
        service = route["servicePepBindings"][0]
        self.assertEqual(ROUTE + ".binding.01", gateway["bindingKey"])
        self.assertEqual(ROUTE + ".binding.01", service["bindingKey"])
        self.assertEqual("POST", gateway["method"])
        self.assertEqual("POST", service["method"])
        self.assertEqual(
            "/api/approvals/v1/hris/separation-waiver-tasks/{taskId}/approve",
            gateway["path"],
        )
        self.assertEqual(
            "/v1/hris/separation-waiver-tasks/{taskId}/approve",
            service["path"],
        )
        self.assertEqual("approval", service["serviceKey"])
        profile = route["accessProfiles"][0]
        self.assertEqual("full-work", profile["profileKey"])
        self.assertEqual(["NORMAL", "ELEVATED"], profile["activeAccessModes"])
        self.assertEqual(CAPABILITY,
                         profile["requiredAccess"]["capabilityContractKey"])
        self.assertEqual(["OBJECT"], profile["targetBindingKinds"])
        self.assertEqual(
            ["predicate.approval-task-decision.v1",
             "predicate.approval.object-version.v1"],
            profile["predicatePolicyKeys"],
        )
        self.assertFalse(profile["readOnly"])
        step_up = route["stepUpCommandBindings"][0]
        self.assertEqual(ROUTE + ".binding.01", step_up["bindingKey"])
        self.assertEqual("HRIS_SEPARATION_WAIVER_TASK", step_up["targetType"])
        self.assertEqual("taskId", step_up["targetIdPathParameter"])
        self.assertEqual("COMMAND_HEADER", step_up["expectedObjectVersionSource"])
        self.assertEqual("X-DWP-Expected-Object-Version",
                         step_up["expectedObjectVersionName"])
        self.assertEqual("approval", step_up["ownerServiceKey"])
        self.assertEqual("dwp-approval-server", step_up["audience"])

    def test_latest_alias_indexes_seeds_and_service_peps_share_lineage(self) -> None:
        auth = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
        canonical = (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_bytes()
        self.assertEqual(canonical, (CONTRACTS / "product-surfaces-v1.json").read_bytes())
        self.assertEqual(canonical, (auth / "product-surfaces-v1.generated.json").read_bytes())
        self.assertEqual(
            canonical,
            (auth / "product-surfaces-v1.bundle-v62.generated.json").read_bytes(),
        )
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(
            (62, CHECKSUM, 62),
            (index["latestVersion"], index["latestChecksum"], len(index["versions"])),
        )

        approval = json.loads(
            (ROOT / "dwp-approval-server/src/main/resources/product-authorization/"
             "approval-pilot-pep-v62.generated.json").read_text()
        )
        self.assertEqual((62, CHECKSUM),
                         (approval["registryRef"]["version"],
                          approval["registryRef"]["sha256"]))
        self.assertEqual(
            (40, 1, 1, 18, 217, 286,
             {"PAGE": 20, "DATA": 84, "ACTION": 113}),
            (len(approval["capabilities"]), len(approval["accessPolicies"]),
             len(approval["entitlementExpressions"]),
             len(approval["predicatePolicies"]), len(approval["routes"]),
             approval["bindingPairCount"],
             Counter(value["routeKind"] for value in approval["routes"])),
        )

        expected = {
            "people": (65, 5, 3, 23, 136, 163,
                       {"PAGE": 47, "DATA": 35, "ACTION": 54}),
            "time": (15, 0, 0, 9, 29, 29,
                     {"PAGE": 7, "DATA": 6, "ACTION": 16}),
            "platform": (24, 4, 3, 11, 89, 90,
                         {"PAGE": 8, "DATA": 24, "ACTION": 57}),
            "payroll": (17, 0, 0, 9, 41, 41,
                        {"PAGE": 5, "DATA": 14, "ACTION": 22}),
        }
        for service, counts in expected.items():
            pep = json.loads((ROOT / f"dwp-{service}-server/src/main/resources/"
                              f"product-authorization/hcm-{service}-pep-v62.generated.json")
                             .read_text())
            self.assertEqual((62, CHECKSUM),
                             (pep["registryRef"]["version"],
                              pep["registryRef"]["sha256"]))
            actual = (len(pep["capabilities"]), len(pep["accessPolicies"]),
                      len(pep["entitlementExpressions"]),
                      len(pep["predicatePolicies"]), len(pep["routes"]),
                      pep["bindingPairCount"],
                      Counter(value["routeKind"] for value in pep["routes"]))
            self.assertEqual(counts, actual, service)


if __name__ == "__main__":
    unittest.main()
