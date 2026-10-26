from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
CHECKSUM = "f7cf92a97f2fabf86f257010064a3183acf58f86cc2c9d7692a1555cab55aa24"
CAPABILITY = "hcm.operations.separation.manage"
PREDICATE = "predicate.hcm-separation-case-scope.v1"
PAGE = "route.hcm.operations.exit-return.page"
DATA = "route.hcm.operations.exit-return.data"
ACTION = "route.hcm.operations.separation-case.action"
ROUTES = {PAGE, DATA, ACTION}


class ProductAuthorizationV61Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v60.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v61.json").read_text()
        )
        cls.routes = {value["routeContractKey"]: value for value in cls.bundle["routes"]}
        cls.capabilities = {
            value["contractKey"]: value for value in cls.bundle["capabilities"]
        }
        cls.predicates = {
            value["predicatePolicyKey"]: value
            for value in cls.bundle["predicatePolicies"]
        }

    def test_exact_draft_successor_and_v60_semantic_preservation(self) -> None:
        self.assertEqual(
            (61, "DRAFT", CHECKSUM),
            (self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]),
        )
        self.assertEqual(
            (279, 24, 17, 87, 1157),
            tuple(len(self.bundle[key]) for key in (
                "capabilities", "accessPolicies", "entitlementExpressions",
                "predicatePolicies", "routes",
            )),
        )
        self.assertEqual(
            {"PAGE": 162, "DATA": 349, "ACTION": 646},
            Counter(value["routeKind"] for value in self.bundle["routes"]),
        )
        additions = {
            "capabilities": {CAPABILITY},
            "accessPolicies": set(),
            "entitlementExpressions": set(),
            "predicatePolicies": {PREDICATE},
            "routes": ROUTES,
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

    def test_separation_capability_predicate_and_routes_are_exact(self) -> None:
        capability = self.capabilities[CAPABILITY]
        self.assertEqual("ACTION.HRIS_SEPARATION_CASE:MANAGE",
                         capability["resolvedCapabilityCode"])
        self.assertEqual("ACTION.HRIS_SEPARATION_CASE", capability["resourceKey"])
        self.assertEqual("MANAGE", capability["action"])
        self.assertEqual("PERMISSION_AND_RELATIONSHIP", capability["authorityMode"])
        self.assertEqual("WORKFORCE_TARGET_POPULATION", capability["scopeResolver"])
        self.assertEqual("CRITICAL", capability["riskTier"])
        self.assertEqual("STEPUP-MGMT-HIGH-V1", capability["activationPolicy"])
        self.assertEqual("REQUIRED", capability["responsibilityRequirement"])
        self.assertEqual("HRIS_DUTY_SEPARATION_MAKER",
                         capability["requiredResponsibilityCode"])
        self.assertEqual("NO_APPROVE_OWN_CHANGE", capability["sodPolicyId"])
        self.assertTrue(capability["requiresProductEntitlement"])
        self.assertEqual([ACTION], capability["routeContractKeys"])

        predicate = self.predicates[PREDICATE]
        self.assertEqual("people", predicate["ownerServiceKey"])
        self.assertEqual(["OBJECT", "TARGET_POPULATION"],
                         predicate["targetBindingKinds"])
        self.assertEqual("HcmSeparationCaseScopeEvidenceV1",
                         predicate["inputEvidenceSchemaKey"])
        self.assertEqual({"type": "object", "additionalProperties": False,
                          "properties": {}}, predicate["parameterSchema"])
        self.assertEqual([DATA, PAGE, ACTION], predicate["routeContractKeys"])

        page = self.routes[PAGE]
        data = self.routes[DATA]
        self.assertEqual("wire-authority.hcm.operations.exit-return.v1",
                         page["authorizationEquivalenceKey"])
        self.assertEqual(page["authorizationEquivalenceKey"],
                         data["authorizationEquivalenceKey"])
        self.assertEqual("hcm.operations.exit-return", page["uiRouteId"])
        self.assertEqual("/hr/operations/people/exit-return", page["uiRoutePattern"])
        for route in (page, data):
            self.assertEqual("GET", route["gatewayApiBindings"][0]["method"])
            self.assertEqual("/api/people/v1/hris/workbench/operations/exit/return",
                             route["gatewayApiBindings"][0]["path"])
            self.assertEqual("/v1/hris/workbench/operations/exit/return",
                             route["servicePepBindings"][0]["path"])
            profile = route["accessProfiles"][0]
            self.assertEqual("people-operations-reader", profile["profileKey"])
            self.assertEqual("hcm.operations.people.view",
                             profile["requiredAccess"]["capabilityContractKey"])
            self.assertEqual(["TARGET_POPULATION"], profile["targetBindingKinds"])
            self.assertEqual([PREDICATE], profile["predicatePolicyKeys"])
            self.assertEqual("Hrm013ExitReturnProjectionV1",
                             profile["responseProjectionBindings"][0]["responseSchemaKey"])

        action = self.routes[ACTION]
        self.assertEqual("POST", action["gatewayApiBindings"][0]["method"])
        self.assertEqual("/api/people/v1/hris/separation-cases",
                         action["gatewayApiBindings"][0]["path"])
        profile = action["accessProfiles"][0]
        self.assertEqual("separation-case-maker", profile["profileKey"])
        self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
        self.assertEqual(["OBJECT", "TARGET_POPULATION"],
                         profile["targetBindingKinds"])
        self.assertEqual([PREDICATE, "predicate.people.object-version.v1"],
                         profile["predicatePolicyKeys"])
        step_up = action["stepUpCommandBindings"][0]
        self.assertEqual("WORK_RELATIONSHIP", step_up["targetType"])
        self.assertEqual(["relationshipPublicId"], step_up["targetIdBodyFields"])
        self.assertEqual("COMMAND_HEADER", step_up["expectedObjectVersionSource"])
        self.assertEqual("If-Match", step_up["expectedObjectVersionName"])
        self.assertEqual("people", step_up["ownerServiceKey"])
        self.assertEqual("dwp-people-server", step_up["audience"])

    def test_latest_alias_index_auth_seed_and_service_peps_share_lineage(self) -> None:
        auth = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
        canonical = (CONTRACTS / "product-surfaces-v1.bundle-v61.json").read_bytes()
        self.assertEqual(canonical, (CONTRACTS / "product-surfaces-v1.json").read_bytes())
        self.assertEqual(canonical, (auth / "product-surfaces-v1.generated.json").read_bytes())
        self.assertEqual(canonical,
                         (auth / "product-surfaces-v1.bundle-v61.generated.json").read_bytes())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual((61, CHECKSUM, 61),
                         (index["latestVersion"], index["latestChecksum"],
                          len(index["versions"])))
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
                              f"product-authorization/hcm-{service}-pep-v61.generated.json")
                             .read_text())
            self.assertEqual(61, pep["registryRef"]["version"])
            self.assertEqual(CHECKSUM, pep["registryRef"]["sha256"])
            actual = (len(pep["capabilities"]), len(pep["accessPolicies"]),
                      len(pep["entitlementExpressions"]), len(pep["predicatePolicies"]),
                      len(pep["routes"]), pep["bindingPairCount"],
                      Counter(value["routeKind"] for value in pep["routes"]))
            self.assertEqual(counts, actual, service)


if __name__ == "__main__":
    unittest.main()
