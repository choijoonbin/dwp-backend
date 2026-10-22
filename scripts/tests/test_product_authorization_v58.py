from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
CHECKSUM = "556f2cc1554f0aaab6f88e50c34d64ca5cdd95af9b2d55072f6e6745f4652cc2"
CAPABILITY = "hcm.operations.payroll-support.view"
PREDICATE = "predicate.hcm-payroll-population-authority.v1"
PAGE = "route.hcm.operations.pay-retirement.page"
DATA = "route.hcm.operations.pay-retirement.data"
ROUTES = {PAGE, DATA}


class ProductAuthorizationV58Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v57.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v58.json").read_text()
        )
        cls.routes = {
            route["routeContractKey"]: route for route in cls.bundle["routes"]
        }
        cls.capabilities = {
            value["contractKey"]: value for value in cls.bundle["capabilities"]
        }
        cls.predicates = {
            value["predicatePolicyKey"]: value
            for value in cls.bundle["predicatePolicies"]
        }

    def test_exact_draft_successor_and_v57_semantic_preservation(self) -> None:
        self.assertEqual(
            (58, "DRAFT", CHECKSUM),
            (
                self.bundle["version"],
                self.bundle["bundleStatus"],
                self.bundle["checksum"],
            ),
        )
        self.assertEqual(
            {
                "capabilities": 271,
                "accessPolicies": 24,
                "entitlementExpressions": 17,
                "predicatePolicies": 83,
                "routes": 1125,
            },
            {
                key: len(self.bundle[key])
                for key in (
                    "capabilities",
                    "accessPolicies",
                    "entitlementExpressions",
                    "predicatePolicies",
                    "routes",
                )
            },
        )
        self.assertEqual(
            {"PAGE": 158, "DATA": 340, "ACTION": 627},
            Counter(route["routeKind"] for route in self.bundle["routes"]),
        )

        for section, key in (
            ("capabilities", "contractKey"),
            ("accessPolicies", "accessPolicyKey"),
            ("entitlementExpressions", "expressionKey"),
            ("predicatePolicies", "predicatePolicyKey"),
            ("routes", "routeContractKey"),
        ):
            before = {value[key]: copy.deepcopy(value) for value in self.previous[section]}
            after = {value[key]: copy.deepcopy(value) for value in self.bundle[section]}
            self.assertEqual(ROUTES if section == "routes" else set(), set(after) - set(before))
            for descriptor_key in before:
                before[descriptor_key].pop("routeContractKeys", None)
                after[descriptor_key].pop("routeContractKeys", None)
                self.assertEqual(before[descriptor_key], after[descriptor_key], descriptor_key)

    def test_reused_capability_and_predicate_gain_only_the_two_reads(self) -> None:
        capability = self.capabilities[CAPABILITY]
        self.assertEqual(
            (
                "DATA.HRIS_PAYROLL_STATUS:VIEW",
                "DATA.HRIS_PAYROLL_STATUS",
                "VIEW",
                "PAY_TARGET_POPULATION",
                "HIGH",
                "STEPUP-MGMT-HIGH-V1",
            ),
            (
                capability["resolvedCapabilityCode"],
                capability["resourceKey"],
                capability["action"],
                capability["scopeResolver"],
                capability["riskTier"],
                capability["activationPolicy"],
            ),
        )
        self.assertEqual(
            {
                "route.hcm.operations.pay-tax-social-insurance.data",
                "route.hcm.operations.pay-tax-social-insurance.page",
                "route.hcm.operations.payroll-population.page",
                "route.hcm.operations.payroll-run.data",
                "route.hcm.operations.payroll-support.data",
                PAGE,
                DATA,
            },
            set(capability["routeContractKeys"]),
        )

        predicate = self.predicates[PREDICATE]
        self.assertEqual("payroll", predicate["ownerServiceKey"])
        self.assertEqual(["TARGET_POPULATION"], predicate["targetBindingKinds"])
        self.assertEqual("HcmPayrollPopulationAuthorityEvidenceV1",
                         predicate["inputEvidenceSchemaKey"])
        self.assertEqual(
            {
                "route.hcm.operations.pay-tax-social-insurance.data",
                "route.hcm.operations.pay-tax-social-insurance.page",
                "route.hcm.operations.payroll-population.page",
                "route.hcm.operations.payroll-support.data",
                PAGE,
                DATA,
            },
            set(predicate["routeContractKeys"]),
        )

    def test_read_pair_is_exact_and_declares_no_action_or_step_up(self) -> None:
        for key, kind in ((PAGE, "PAGE"), (DATA, "DATA")):
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            binding_key = key + ".binding.01"
            self.assertEqual(
                (
                    kind,
                    "wire-authority.hcm.operations.pay-retirement.v1",
                    "hcm.operations",
                    "hcm.operations.pay-retirement" if kind == "PAGE" else None,
                    "/hr/operations/payroll/retirement" if kind == "PAGE" else None,
                    None if kind == "PAGE" else True,
                ),
                (
                    route["routeKind"],
                    route["authorizationEquivalenceKey"],
                    route["navigationContextId"],
                    route["uiRouteId"],
                    route["uiRoutePattern"],
                    route["sideEffectFree"],
                ),
            )
            self.assertEqual(
                (
                    "payroll-support-viewer",
                    300,
                    ["NORMAL", "ELEVATED"],
                    CAPABILITY,
                    ["TARGET_POPULATION"],
                    [PREDICATE],
                    True,
                ),
                (
                    profile["profileKey"],
                    profile["precedence"],
                    profile["activeAccessModes"],
                    profile["requiredAccess"]["capabilityContractKey"],
                    profile["targetBindingKinds"],
                    profile["predicatePolicyKeys"],
                    profile["readOnly"],
                ),
            )
            self.assertEqual(
                [{
                    "apiBindingKey": binding_key,
                    "projectionPolicyKey": "pay004.iaq-049.fields.v1",
                    "responseSchemaKey": "Pay004RetirementProjectionV1",
                }],
                profile["responseProjectionBindings"],
            )
            self.assertEqual(
                (binding_key, "GET", "/api/payroll/v1/workbench/pay-retirement"),
                tuple(route["gatewayApiBindings"][0][name]
                      for name in ("bindingKey", "method", "path")),
            )
            self.assertEqual(
                (binding_key, "GET", "/v1/workbench/pay-retirement", "payroll"),
                tuple(route["servicePepBindings"][0][name]
                      for name in ("bindingKey", "method", "path", "serviceKey")),
            )
            self.assertNotIn("stepUpCommandBindings", route)

    def test_latest_alias_indexes_fixtures_and_owner_peps_share_v58_lineage(self) -> None:
        for path in (
            CONTRACTS / "product-surfaces-v1.json",
            AUTH / "product-surfaces-v1.generated.json",
            AUTH / "product-surfaces-v1.bundle-v58.generated.json",
        ):
            self.assertEqual(self.bundle, json.loads(path.read_text()), str(path))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(
            (58, CHECKSUM, 58),
            (index["latestVersion"], index["latestChecksum"], len(index["versions"])),
        )
        self.assertEqual(
            index,
            json.loads((AUTH / "product-surfaces-v1.index.generated.json").read_text()),
        )
        fixture = json.loads((CONTRACTS / "pilot-fixtures.v1.generated.json").read_text())
        self.assertEqual(58, fixture["registryLineage"]["latestAliasVersion"])
        self.assertEqual(
            {"bundleKey": "product-surfaces", "version": 58, "sha256": CHECKSUM},
            fixture["registryLineage"]["versions"][-1],
        )
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads(
                (
                    ROOT
                    / f"dwp-{service}-server/src/main/resources/product-authorization/"
                    f"hcm-{service}-pep-v58.generated.json"
                ).read_text()
            )
            self.assertEqual(
                (58, CHECKSUM),
                (pep["registryRef"]["version"], pep["registryRef"]["sha256"]),
            )
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(ROUTES if service == "payroll" else set(), ROUTES & present)


if __name__ == "__main__":
    unittest.main()
