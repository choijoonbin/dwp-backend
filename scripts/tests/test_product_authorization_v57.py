from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
CHECKSUM = "f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e"
CAPABILITY = "hcm.integration.workforce-ingestion.execute"
PREDICATE = "predicate.hcm-workforce-ingestion-scope.v1"
PAGE = "route.hcm.operations.people-data-quality.page"
DATA = "route.hcm.operations.people-data-quality.data"
ACTION = "route.hcm.operations.workforce-ingestion.action"
ROUTES = {PAGE, DATA, ACTION}


class ProductAuthorizationV57Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v56.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v57.json").read_text()
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

    def test_exact_draft_successor_and_v56_semantic_preservation(self) -> None:
        self.assertEqual(
            (57, "DRAFT", CHECKSUM),
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
                "routes": 1123,
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
            {"PAGE": 157, "DATA": 339, "ACTION": 627},
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
            expected_new = {
                "capabilities": {CAPABILITY},
                "predicatePolicies": {PREDICATE},
                "routes": ROUTES,
            }.get(section, set())
            self.assertEqual(expected_new, set(after) - set(before), section)
            for descriptor_key in before:
                # Reverse route indexes are the only generated appendages permitted.
                before[descriptor_key].pop("routeContractKeys", None)
                after[descriptor_key].pop("routeContractKeys", None)
                self.assertEqual(before[descriptor_key], after[descriptor_key], descriptor_key)

    def test_narrow_capability_and_people_owned_predicate_are_exact(self) -> None:
        capability = self.capabilities[CAPABILITY]
        self.assertEqual(
            (
                "hcm",
                "hcm.operations",
                "ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE",
                "ACTION.HRIS_INTEGRATION_EXECUTE",
                "EXECUTE",
                "PERMISSION_AND_RELATIONSHIP",
                "CONNECTOR_OR_LEGAL_ENTITY_SCOPE",
                "HIGH",
                "STEPUP-MGMT-HIGH-V1",
                "REQUIRED",
                "HRIS_DUTY_INTEGRATION_EXECUTOR",
                True,
                [ACTION],
            ),
            (
                capability["productKey"],
                capability["surfaceKey"],
                capability["resolvedCapabilityCode"],
                capability["resourceKey"],
                capability["action"],
                capability["authorityMode"],
                capability["scopeResolver"],
                capability["riskTier"],
                capability["activationPolicy"],
                capability["responsibilityRequirement"],
                capability["requiredResponsibilityCode"],
                capability["requiresProductEntitlement"],
                capability["routeContractKeys"],
            ),
        )
        legacy = self.capabilities["hcm.integration.execute"]
        self.assertEqual(
            (
                "ACTION.WORKFORCE_DATA_OPERATIONS:EXECUTE",
                "APP_CONFIG_ADMIN",
                "APP_RESOURCE_SET:RS_HCM_CONFIG",
            ),
            (
                legacy["resolvedCapabilityCode"],
                legacy["requiredResponsibilityCode"],
                legacy["scopeResolver"],
            ),
        )

        predicate = self.predicates[PREDICATE]
        self.assertEqual(
            (
                "people",
                ["OBJECT", "TARGET_POPULATION"],
                "HcmWorkforceIngestionScopeEvidenceV1",
                {"additionalProperties": False, "properties": {}, "type": "object"},
                sorted(ROUTES),
            ),
            (
                predicate["ownerServiceKey"],
                predicate["targetBindingKinds"],
                predicate["inputEvidenceSchemaKey"],
                predicate["parameterSchema"],
                predicate["routeContractKeys"],
            ),
        )

    def test_read_pair_and_command_bindings_are_exact_and_separate(self) -> None:
        for key, kind in ((PAGE, "PAGE"), (DATA, "DATA")):
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            self.assertEqual(
                (
                    kind,
                    "wire-authority.hcm.operations.people-data-quality.v1",
                    "hcm.operations",
                    "hcm.operations.people-data-quality" if kind == "PAGE" else None,
                    "/hr/operations/people/data-quality" if kind == "PAGE" else None,
                ),
                (
                    route["routeKind"],
                    route["authorizationEquivalenceKey"],
                    route["navigationContextId"],
                    route["uiRouteId"],
                    route["uiRoutePattern"],
                ),
            )
            self.assertEqual(
                (
                    "people-data-quality-reader",
                    "hcm.operations.people.view",
                    ["TARGET_POPULATION"],
                    [PREDICATE],
                    "Hrm012DataQualityProjectionV1",
                    True,
                ),
                (
                    profile["profileKey"],
                    profile["requiredAccess"]["capabilityContractKey"],
                    profile["targetBindingKinds"],
                    profile["predicatePolicyKeys"],
                    profile["responseProjectionBindings"][0]["responseSchemaKey"],
                    profile["readOnly"],
                ),
            )
            self.assertEqual(
                ("GET", "/api/people/v1/hris/workbench/operations/data/quality"),
                (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ),
            )
            self.assertEqual(
                ("GET", "/v1/hris/workbench/operations/data/quality", "people"),
                (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                    route["servicePepBindings"][0]["serviceKey"],
                ),
            )

        route = self.routes[ACTION]
        profile = route["accessProfiles"][0]
        self.assertEqual(
            (
                "ACTION",
                "workforce-ingestion-executor",
                ["ELEVATED"],
                CAPABILITY,
                ["OBJECT", "TARGET_POPULATION"],
                [PREDICATE, "predicate.people.object-version.v1"],
                False,
            ),
            (
                route["routeKind"],
                profile["profileKey"],
                profile["activeAccessModes"],
                profile["requiredAccess"]["capabilityContractKey"],
                profile["targetBindingKinds"],
                profile["predicatePolicyKeys"],
                profile["readOnly"],
            ),
        )
        self.assertEqual(
            ("POST", "/api/people/v1/hris/integrations/workforce-ingestions"),
            (
                route["gatewayApiBindings"][0]["method"],
                route["gatewayApiBindings"][0]["path"],
            ),
        )
        self.assertEqual(
            ("POST", "/v1/hris/integrations/workforce-ingestions", "people"),
            (
                route["servicePepBindings"][0]["method"],
                route["servicePepBindings"][0]["path"],
                route["servicePepBindings"][0]["serviceKey"],
            ),
        )
        self.assertEqual(
            {
                "bindingKey": ACTION + ".binding.01",
                "targetType": "WORKFORCE_INGESTION_OBJECT",
                "targetIdBodyFields": ["objectRef"],
                "expectedObjectVersionSource": "COMMAND_HEADER",
                "expectedObjectVersionName": "If-Match",
                "ownerServiceKey": "people",
                "audience": "dwp-people-server",
            },
            route["stepUpCommandBindings"][0],
        )

    def test_current_alias_is_v62_and_v57_owner_peps_preserve_lineage(self) -> None:
        latest = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_text()
        )
        for path in (CONTRACTS / "product-surfaces-v1.json",
                     AUTH / "product-surfaces-v1.generated.json"):
            self.assertEqual(latest, json.loads(path.read_text()), str(path))
        historical_seed = AUTH / "product-surfaces-v1.bundle-v57.generated.json"
        self.assertEqual(self.bundle, json.loads(historical_seed.read_text()))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual(
            (62, latest["checksum"], 62),
            (index["latestVersion"], index["latestChecksum"], len(index["versions"])),
        )
        historical = next(value for value in index["versions"] if value["version"] == 57)
        self.assertEqual(CHECKSUM, historical["checksum"])
        self.assertEqual(
            index,
            json.loads((AUTH / "product-surfaces-v1.index.generated.json").read_text()),
        )
        fixture = json.loads((CONTRACTS / "pilot-fixtures.v1.generated.json").read_text())
        self.assertEqual(62, fixture["registryLineage"]["latestAliasVersion"])
        self.assertEqual(
            {"bundleKey": "product-surfaces", "version": 62,
             "sha256": latest["checksum"]},
            fixture["registryLineage"]["versions"][-1],
        )
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads(
                (
                    ROOT
                    / f"dwp-{service}-server/src/main/resources/product-authorization/"
                    f"hcm-{service}-pep-v57.generated.json"
                ).read_text()
            )
            self.assertEqual(
                (57, CHECKSUM),
                (pep["registryRef"]["version"], pep["registryRef"]["sha256"]),
            )
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(ROUTES if service == "people" else set(), ROUTES & present)


if __name__ == "__main__":
    unittest.main()
