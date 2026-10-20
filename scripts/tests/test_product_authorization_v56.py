from __future__ import annotations

import copy
import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
CHECKSUM = "4c1e82c4ac70f7bbc4aba7a41d77b8fd238da0478501cbdfed97ebd4b5dd0930"
PREFIX = "route.hcm.configuration.automation-"
ROUTES = {
    PREFIX + "catalog.data": ("DATA", "GET", "/api/platform/v1/admin/hris/automations"),
    PREFIX + "definition-create.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations"),
    PREFIX + "run-queue.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/runs"),
    PREFIX + "run-detail.data": ("DATA", "GET", "/api/platform/v1/admin/hris/automation-runs/{runId}"),
    PREFIX + "run-retry.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-runs/{runId}/retry"),
    PREFIX + "version-create.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/versions"),
    PREFIX + "version-validate.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/versions/{version}/validate"),
    PREFIX + "version-submit.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/versions/{version}/submit"),
    PREFIX + "version-approve.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/versions/{version}/approve"),
    PREFIX + "version-activate.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/versions/{version}/activate"),
    PREFIX + "schedule-create.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automations/{automationId}/schedules"),
    PREFIX + "schedule-submit.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/submit"),
    PREFIX + "schedule-approve.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/approve"),
    PREFIX + "schedule-activate.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/activate"),
    PREFIX + "schedule-suspend.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/suspend"),
    PREFIX + "run-cancel.action": ("ACTION", "POST", "/api/platform/v1/admin/hris/automation-runs/{runId}/cancel"),
}
AUTHOR = {
    PREFIX + "definition-create.action",
    PREFIX + "version-create.action",
    PREFIX + "version-validate.action",
    PREFIX + "version-submit.action",
    PREFIX + "schedule-create.action",
    PREFIX + "schedule-submit.action",
}
PUBLISHER = {PREFIX + "version-approve.action", PREFIX + "schedule-approve.action"}
EXECUTOR = set(ROUTES) - AUTHOR - PUBLISHER - {PREFIX + "catalog.data"}
STEP_UP = {
    PREFIX + "run-queue.action",
    PREFIX + "run-retry.action",
    PREFIX + "version-approve.action",
    PREFIX + "version-activate.action",
    PREFIX + "schedule-approve.action",
    PREFIX + "schedule-activate.action",
    PREFIX + "schedule-suspend.action",
    PREFIX + "run-cancel.action",
}


class ProductAuthorizationV56Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v55.json").read_text())
        cls.bundle = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v56.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.bundle["routes"]}

    def test_exact_draft_sixteen_route_successor_preserves_every_existing_descriptor(self) -> None:
        self.assertEqual((56, "DRAFT", CHECKSUM), (
            self.bundle["version"], self.bundle["bundleStatus"], self.bundle["checksum"]))
        self.assertEqual(1120, len(self.routes))
        self.assertEqual({"PAGE": 156, "DATA": 338, "ACTION": 626},
                         Counter(route["routeKind"] for route in self.routes.values()))
        old = {route["routeContractKey"]: route for route in self.previous["routes"]}
        self.assertEqual(set(ROUTES), set(self.routes) - set(old))
        for key, route in old.items():
            self.assertEqual(route, self.routes[key], key)
        for section in ("capabilities", "accessPolicies", "entitlementExpressions", "predicatePolicies"):
            before, after = copy.deepcopy(self.previous[section]), copy.deepcopy(self.bundle[section])
            self.assertEqual(len(before), len(after))
            for descriptor in before + after:
                descriptor.pop("routeContractKeys", None)
            self.assertEqual(before, after, section)

    def test_exact_platform_bindings_profiles_and_high_risk_step_up_partition(self) -> None:
        capability_by_group = {
            **{key: "hcm.configuration.policy.update" for key in AUTHOR},
            **{key: "hcm.configuration.policy.publish" for key in PUBLISHER},
            **{key: "hcm.integration.execute" for key in EXECUTOR},
        }
        for key, (kind, method, gateway_path) in ROUTES.items():
            route = self.routes[key]
            binding_key = key + ".binding.01"
            self.assertEqual((kind, "hcm.management", None), (
                route["routeKind"], route["navigationContextId"], route["uiRouteId"]))
            self.assertEqual((binding_key, method, gateway_path), (
                route["gatewayApiBindings"][0]["bindingKey"],
                route["gatewayApiBindings"][0]["method"],
                route["gatewayApiBindings"][0]["path"]))
            service = route["servicePepBindings"][0]
            self.assertEqual((binding_key, method, gateway_path.removeprefix("/api/platform"), "platform"), (
                service["bindingKey"], service["method"], service["path"], service["serviceKey"]))
            if key != PREFIX + "catalog.data":
                profile = route["accessProfiles"][0]
                self.assertEqual(capability_by_group[key],
                                 profile["requiredAccess"]["capabilityContractKey"])
                self.assertEqual(kind == "DATA", profile["readOnly"])
            steps = route.get("stepUpCommandBindings", [])
            self.assertEqual(key in STEP_UP, len(steps) == 1, key)
            if steps:
                self.assertEqual((binding_key, "COMMAND_HEADER", "If-Match", "platform", "dwp-platform-server"), (
                    steps[0]["bindingKey"], steps[0]["expectedObjectVersionSource"],
                    steps[0]["expectedObjectVersionName"], steps[0]["ownerServiceKey"],
                    steps[0]["audience"]))

    def test_query_routes_remain_read_only_and_catalog_profiles_are_independent(self) -> None:
        catalog = self.routes[PREFIX + "catalog.data"]
        self.assertNotIn("stepUpCommandBindings", catalog)
        self.assertEqual([
            ("automation-author", 300, "hcm.configuration.policy.update"),
            ("automation-publisher", 200, "hcm.configuration.policy.publish"),
            ("automation-executor", 100, "hcm.integration.execute"),
        ], [(profile["profileKey"], profile["precedence"],
             profile["requiredAccess"]["capabilityContractKey"])
            for profile in catalog["accessProfiles"]])
        for profile in catalog["accessProfiles"]:
            self.assertTrue(profile["readOnly"])
            self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
            self.assertEqual(["predicate.hcm-settings-operations-authority.v1"],
                             profile["predicatePolicyKeys"])
            self.assertEqual("SysApi017GetResponseV1",
                             profile["responseProjectionBindings"][0]["responseSchemaKey"])
        detail = self.routes[PREFIX + "run-detail.data"]
        profile = detail["accessProfiles"][0]
        self.assertTrue(profile["readOnly"])
        self.assertEqual("hcm.integration.execute", profile["requiredAccess"]["capabilityContractKey"])
        self.assertEqual("SysApi019GetResponseV1",
                         profile["responseProjectionBindings"][0]["responseSchemaKey"])
        self.assertNotIn("stepUpCommandBindings", detail)

    def test_latest_seed_index_and_platform_only_peps_have_exact_v56_lineage(self) -> None:
        for path in (CONTRACTS / "product-surfaces-v1.json", AUTH / "product-surfaces-v1.generated.json",
                     AUTH / "product-surfaces-v1.bundle-v56.generated.json"):
            self.assertEqual(self.bundle, json.loads(path.read_text()))
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        self.assertEqual((56, CHECKSUM, 56), (index["latestVersion"], index["latestChecksum"], len(index["versions"])))
        self.assertEqual(index, json.loads((AUTH / "product-surfaces-v1.index.generated.json").read_text()))
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                              f"hcm-{service}-pep-v56.generated.json").read_text())
            self.assertEqual((56, CHECKSUM), (pep["registryRef"]["version"], pep["registryRef"]["sha256"]))
            present = {route["routeContractKey"] for route in pep["routes"]}
            self.assertEqual(set(ROUTES) if service == "platform" else set(), set(ROUTES) & present)


if __name__ == "__main__":
    unittest.main()
