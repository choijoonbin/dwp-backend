from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
CHECKSUM = "405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c"

CAPABILITIES = {
    "hcm.integration.definition.update",
    "hcm.integration.control-plane.execute",
}
ROUTES = {
    "route.hcm.configuration.settings-integrations.page",
    "route.hcm.configuration.dwp-integrations.data",
    "route.hcm.configuration.connector-catalog.data",
    "route.hcm.configuration.connector-definition-create.action",
    "route.hcm.configuration.connector-test.action",
    "route.hcm.configuration.connector-mapping-dry-run.action",
    "route.hcm.configuration.connector-execution-create.action",
    "route.hcm.configuration.connector-execution-detail.data",
    "route.hcm.configuration.connector-definition-validate.action",
    "route.hcm.configuration.connector-definition-submit.action",
    "route.hcm.configuration.connector-definition-approve.action",
    "route.hcm.configuration.connector-definition-activate.action",
    "route.hcm.configuration.connector-mapping-version-create.action",
    "route.hcm.configuration.connector-mapping-version-validate.action",
    "route.hcm.configuration.connector-mapping-version-submit.action",
    "route.hcm.configuration.connector-mapping-version-approve.action",
    "route.hcm.configuration.connector-mapping-version-activate.action",
    "route.hcm.configuration.connector-execution-reconcile.action",
    "route.hcm.configuration.connector-execution-retry.action",
    "route.hcm.configuration.connector-execution-cancel.action",
}
REFERENCE_OBJECT_ROUTES = ROUTES - {
    "route.hcm.configuration.settings-integrations.page",
    "route.hcm.configuration.dwp-integrations.data",
    "route.hcm.configuration.connector-catalog.data",
    "route.hcm.configuration.connector-definition-create.action",
}
AUTHOR_ROUTES = {
    "route.hcm.configuration.connector-catalog.data",
    "route.hcm.configuration.connector-definition-create.action",
    "route.hcm.configuration.connector-test.action",
    "route.hcm.configuration.connector-mapping-dry-run.action",
    "route.hcm.configuration.connector-definition-validate.action",
    "route.hcm.configuration.connector-definition-submit.action",
    "route.hcm.configuration.connector-mapping-version-create.action",
    "route.hcm.configuration.connector-mapping-version-validate.action",
    "route.hcm.configuration.connector-mapping-version-submit.action",
}
EXECUTOR_ROUTES = {
    "route.hcm.configuration.connector-execution-create.action",
    "route.hcm.configuration.connector-execution-detail.data",
    "route.hcm.configuration.connector-definition-approve.action",
    "route.hcm.configuration.connector-definition-activate.action",
    "route.hcm.configuration.connector-mapping-version-approve.action",
    "route.hcm.configuration.connector-mapping-version-activate.action",
    "route.hcm.configuration.connector-execution-reconcile.action",
    "route.hcm.configuration.connector-execution-retry.action",
    "route.hcm.configuration.connector-execution-cancel.action",
}
STEP_UP_ROUTES = EXECUTOR_ROUTES - {
    "route.hcm.configuration.connector-execution-create.action",
    "route.hcm.configuration.connector-execution-detail.data",
}


class ProductAuthorizationV60Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v59.json").read_text()
        )
        cls.bundle = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v60.json").read_text()
        )
        cls.routes = {value["routeContractKey"]: value for value in cls.bundle["routes"]}
        cls.capabilities = {
            value["contractKey"]: value for value in cls.bundle["capabilities"]
        }
        cls.predicates = {
            value["predicatePolicyKey"]: value
            for value in cls.bundle["predicatePolicies"]
        }

    def test_exact_draft_successor_and_v59_semantic_preservation(self) -> None:
        self.assertEqual(
            (60, "DRAFT", CHECKSUM),
            (self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]),
        )
        self.assertEqual(
            (278, 24, 17, 86, 1154),
            tuple(
                len(self.bundle[key])
                for key in (
                    "capabilities",
                    "accessPolicies",
                    "entitlementExpressions",
                    "predicatePolicies",
                    "routes",
                )
            ),
        )
        self.assertEqual(
            {"PAGE": 161, "DATA": 348, "ACTION": 645},
            Counter(value["routeKind"] for value in self.bundle["routes"]),
        )
        additions = {
            "capabilities": CAPABILITIES,
            "accessPolicies": set(),
            "entitlementExpressions": set(),
            "predicatePolicies": set(),
            "routes": ROUTES,
        }
        key_fields = {
            "capabilities": "contractKey",
            "accessPolicies": "accessPolicyKey",
            "entitlementExpressions": "expressionKey",
            "predicatePolicies": "predicatePolicyKey",
            "routes": "routeContractKey",
        }
        for section, key in key_fields.items():
            before = {value[key]: copy.deepcopy(value) for value in self.previous[section]}
            after = {value[key]: copy.deepcopy(value) for value in self.bundle[section]}
            self.assertEqual(additions[section], set(after) - set(before), section)
            for descriptor_key in before:
                before[descriptor_key].pop("routeContractKeys", None)
                after[descriptor_key].pop("routeContractKeys", None)
                self.assertEqual(before[descriptor_key], after[descriptor_key], descriptor_key)

    def test_new_capabilities_are_exact_and_historical_meanings_do_not_collide(self) -> None:
        expected = {
            "hcm.integration.definition.update": (
                "CONFIG.HRIS_INTEGRATION:UPDATE",
                "CONFIG.HRIS_INTEGRATION",
                "UPDATE",
                "HIGH",
                None,
                "HRIS_DUTY_INTEGRATION_AUTHOR",
                AUTHOR_ROUTES,
            ),
            "hcm.integration.control-plane.execute": (
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE",
                "EXECUTE",
                "CRITICAL",
                "STEPUP-MGMT-HIGH-V1",
                "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR",
                EXECUTOR_ROUTES,
            ),
        }
        for key, values in expected.items():
            capability = self.capabilities[key]
            self.assertEqual(
                values,
                (
                    capability["resolvedCapabilityCode"],
                    capability["resourceKey"],
                    capability["action"],
                    capability["riskTier"],
                    capability["activationPolicy"],
                    capability["requiredResponsibilityCode"],
                    set(capability["routeContractKeys"]),
                ),
                key,
            )
            self.assertEqual("PERMISSION_AND_RELATIONSHIP", capability["authorityMode"])
            self.assertEqual("REQUIRED", capability["responsibilityRequirement"])
            self.assertEqual("CONNECTOR_OR_LEGAL_ENTITY_SCOPE", capability["scopeResolver"])
            self.assertTrue(capability["requiresProductEntitlement"])

        previous_capabilities = {
            value["contractKey"]: value for value in self.previous["capabilities"]
        }
        for key in (
            "hcm.integration.execute",
            "hcm.integration.workforce-ingestion.execute",
        ):
            self.assertEqual(previous_capabilities[key], self.capabilities[key], key)
            self.assertTrue(ROUTES.isdisjoint(self.capabilities[key]["routeContractKeys"]))

    def test_exact_route_partition_profiles_predicates_and_step_up_topology(self) -> None:
        self.assertEqual(
            {"PAGE": 1, "DATA": 3, "ACTION": 16},
            Counter(self.routes[key]["routeKind"] for key in ROUTES),
        )
        self.assertEqual(
            ROUTES,
            set(self.predicates["predicate.hcm-settings-operations-authority.v1"]
                ["routeContractKeys"])
            - set(next(value for value in self.previous["predicatePolicies"]
                       if value["predicatePolicyKey"]
                       == "predicate.hcm-settings-operations-authority.v1")
                  ["routeContractKeys"]),
        )
        self.assertEqual(
            REFERENCE_OBJECT_ROUTES,
            set(self.predicates["predicate.hcm-reference-configuration-authority.v1"]
                ["routeContractKeys"])
            - set(next(value for value in self.previous["predicatePolicies"]
                       if value["predicatePolicyKey"]
                       == "predicate.hcm-reference-configuration-authority.v1")
                  ["routeContractKeys"]),
        )

        for key in ROUTES:
            route = self.routes[key]
            binding_key = f"{key}.binding.01"
            self.assertEqual("Platform + HCM + Security", route["owner"])
            self.assertEqual("hcm.management", route["navigationContextId"])
            self.assertEqual(
                {"type": "PRODUCT", "productKey": "hcm", "surfaceKey": "hcm.management"},
                route["subject"],
            )
            self.assertEqual([binding_key], [value["bindingKey"]
                                             for value in route["gatewayApiBindings"]])
            self.assertEqual([binding_key], [value["bindingKey"]
                                             for value in route["servicePepBindings"]])
            self.assertEqual("platform", route["servicePepBindings"][0]["serviceKey"])
            self.assertEqual(route["gatewayApiBindings"][0]["method"],
                             route["servicePepBindings"][0]["method"])
            self.assertEqual({}, route["gatewayApiBindings"][0]["pathParameterConstraints"])
            self.assertEqual({}, route["servicePepBindings"][0]["pathParameterConstraints"])

        self.assertNotIn(
            "stepUpCommandBindings",
            self.routes["route.hcm.configuration.connector-execution-create.action"],
        )
        self.assertNotIn(
            "stepUpCommandBindings",
            self.routes["route.hcm.configuration.connector-execution-detail.data"],
        )
        for key in STEP_UP_ROUTES:
            step_up = self.routes[key]["stepUpCommandBindings"]
            self.assertEqual(1, len(step_up), key)
            self.assertEqual(
                (f"{key}.binding.01", "platform", "dwp-platform-server",
                 "COMMAND_HEADER", "If-Match"),
                (
                    step_up[0]["bindingKey"],
                    step_up[0]["ownerServiceKey"],
                    step_up[0]["audience"],
                    step_up[0]["expectedObjectVersionSource"],
                    step_up[0]["expectedObjectVersionName"],
                ),
                key,
            )

    def test_fixed_v60_artifacts_and_service_projections_preserve_lineage(self) -> None:
        auth = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
        for path in (
            CONTRACTS / "product-surfaces-v1.bundle-v60.json",
            auth / "product-surfaces-v1.bundle-v60.generated.json",
        ):
            self.assertEqual(self.bundle, json.loads(path.read_text()), str(path))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        v60 = next(value for value in index["versions"] if value["version"] == 60)
        self.assertEqual((60, CHECKSUM), (v60["version"], v60["checksum"]))
        fixture = json.loads((CONTRACTS / "pilot-fixtures.v1.generated.json").read_text())
        fixture_v60 = next(
            value for value in fixture["registryLineage"]["versions"]
            if value["version"] == 60
        )
        self.assertEqual(
            {"bundleKey": "product-surfaces", "version": 60, "sha256": CHECKSUM},
            fixture_v60,
        )
        expected_counts = {
            "people": (64, 5, 3, 22, 133, 160, {"PAGE": 46, "DATA": 34, "ACTION": 53}),
            "time": (15, 0, 0, 9, 29, 29, {"PAGE": 7, "DATA": 6, "ACTION": 16}),
            "platform": (24, 4, 3, 11, 89, 90, {"PAGE": 8, "DATA": 24, "ACTION": 57}),
            "payroll": (17, 0, 0, 9, 41, 41, {"PAGE": 5, "DATA": 14, "ACTION": 22}),
        }
        for service, counts in expected_counts.items():
            path = ROOT / (
                f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v60.generated.json"
            )
            pep = json.loads(path.read_text())
            self.assertEqual((60, CHECKSUM),
                             (pep["registryRef"]["version"], pep["registryRef"]["sha256"]))
            actual = (
                len(pep["capabilities"]),
                len(pep["accessPolicies"]),
                len(pep["entitlementExpressions"]),
                len(pep["predicatePolicies"]),
                len(pep["routes"]),
                pep["bindingPairCount"],
                Counter(route["routeKind"] for route in pep["routes"]),
            )
            self.assertEqual(counts, actual, service)
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(ROUTES if service == "platform" else set(), ROUTES & present)


if __name__ == "__main__":
    unittest.main()
