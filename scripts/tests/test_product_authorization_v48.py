import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
PAYROLL_PEP = (
    ROOT
    / "dwp-payroll-server/src/main/resources/product-authorization/hcm-payroll-pep-v48.generated.json"
)
V48_SHA256 = "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043"
PEP_SHA256 = "a0e8f8ad423201cb13794f2a6209583048665084d1b9c0a18e6d620b9e34c66e"
PREFIX = "route.hcm.configuration.payroll-foundation-"
SETTINGS_OPERATIONS = {
    ("get", "/v1/settings/payroll/foundation/configurations", "settingsPayrollFoundationConfigurations"),
    ("post", "/v1/settings/payroll/foundation/configurations", "settingsPayrollFoundationCreateConfiguration"),
    ("get", "/v1/settings/payroll/foundation/configurations/{configurationId}", "settingsPayrollFoundationConfiguration"),
    ("put", "/v1/settings/payroll/foundation/configurations/{configurationId}", "settingsPayrollFoundationUpdateConfiguration"),
    ("get", "/v1/settings/payroll/foundation/configurations/{configurationId}/versions", "settingsPayrollFoundationConfigurationVersions"),
    ("post", "/v1/settings/payroll/foundation/configurations/{configurationId}/simulations", "settingsPayrollFoundationSimulateConfiguration"),
    ("post", "/v1/settings/payroll/foundation/configurations/{configurationId}/publish", "settingsPayrollFoundationPublishConfiguration"),
    ("post", "/v1/settings/payroll/foundation/configurations/{configurationId}/reversals", "settingsPayrollFoundationReverseConfiguration"),
    ("get", "/v1/settings/payroll/foundation/receipts/{commandId}", "settingsPayrollFoundationCommandReceipt"),
    ("post", "/v1/settings/payroll/foundation/receipts/{commandId}/reconcile", "settingsPayrollFoundationReconcileCommand"),
}


class ProductAuthorizationV48Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v47 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v47.json").read_text())
        cls.v48 = json.loads((CONTRACTS / "product-surfaces-v1.bundle-v48.json").read_text())
        cls.routes = {route["routeContractKey"]: route for route in cls.v48["routes"]}
        cls.pep = json.loads(PAYROLL_PEP.read_text())

    def test_v48_is_exact_append_only_payroll_settings_release(self) -> None:
        self.assertEqual(48, self.v48["version"])
        self.assertEqual(V48_SHA256, self.v48["checksum"])
        self.assertEqual(1063, len(self.v48["routes"]))
        old = {route["routeContractKey"]: route for route in self.v47["routes"]}
        self.assertEqual(old, {key: self.routes[key] for key in old})
        added = set(self.routes) - set(old)
        self.assertEqual(10, len(added))
        self.assertTrue(all(key.startswith(PREFIX) for key in added))
        for section, key in (
            ("capabilities", "contractKey"),
            ("predicatePolicies", "predicatePolicyKey"),
        ):
            previous = {item[key]: item for item in self.v47[section]}
            current = {item[key]: item for item in self.v48[section]}
            self.assertEqual(set(previous), set(current))
            for contract_key, old in previous.items():
                new = current[contract_key]
                for field, value in old.items():
                    if isinstance(value, list) and all(isinstance(item, str) for item in value):
                        self.assertLessEqual(set(value), set(new[field]))
                    elif isinstance(value, list):
                        self.assertEqual(value, new[field])
                    else:
                        self.assertEqual(value, new[field])

    def test_settings_aliases_have_exact_management_authority(self) -> None:
        for key, route in self.routes.items():
            if not key.startswith(PREFIX):
                continue
            self.assertEqual("hcm.management", route["navigationContextId"])
            self.assertEqual(
                {"type": "PRODUCT", "productKey": "hcm", "surfaceKey": "hcm.management"},
                route["subject"],
            )
            self.assertIsNone(route["uiRoutePattern"])
            profile = route["accessProfiles"][0]
            self.assertIn("predicate.hcm-payroll-configuration-authority.v1", profile["predicatePolicyKeys"])
            self.assertIn("predicate.hcm-payroll-foundation-authority.v1", profile["predicatePolicyKeys"])
            public = route["gatewayApiBindings"][0]
            owner = route["servicePepBindings"][0]
            self.assertTrue(public["path"].startswith("/api/payroll/v1/settings/payroll/foundation/"))
            self.assertTrue(owner["path"].startswith("/v1/settings/payroll/foundation/"))
            self.assertEqual(public["path"], "/api/payroll" + owner["path"])
            self.assertEqual("payroll", owner["serviceKey"])
            self.assertNotIn("hcm.operations", json.dumps(route, sort_keys=True))

            if route["routeKind"] == "DATA":
                self.assertEqual("configuration-author", profile["profileKey"])
                self.assertEqual(
                    "hcm.configuration.workbench.view",
                    profile["requiredAccess"]["capabilityContractKey"],
                )
                self.assertTrue(profile["readOnly"])
            elif key.endswith("publish.action") or key.endswith("reverse.action"):
                self.assertEqual("configuration-publisher", profile["profileKey"])
                self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
                self.assertEqual(
                    "hcm.configuration.policy.publish",
                    profile["requiredAccess"]["capabilityContractKey"],
                )
                self.assertIn("predicate.hcm-payroll-foundation-sod.v1", profile["predicatePolicyKeys"])
                self.assertEqual(1, len(route["stepUpCommandBindings"]))
            else:
                self.assertEqual("configuration-author", profile["profileKey"])
                self.assertEqual(
                    "hcm.configuration.policy.update",
                    profile["requiredAccess"]["capabilityContractKey"],
                )

    def test_single_existing_page_is_unchanged_and_no_operations_page_is_added(self) -> None:
        page = self.routes["route.hcm.configuration.payroll.page"]
        prior_page = next(
            route
            for route in self.v47["routes"]
            if route["routeContractKey"] == "route.hcm.configuration.payroll.page"
        )
        self.assertEqual(prior_page, page)
        self.assertEqual("/hr/settings/payroll", page["uiRoutePattern"])
        old_pages = {
            route["routeContractKey"]: route
            for route in self.v47["routes"]
            if route["routeKind"] == "PAGE"
        }
        new_pages = {
            route["routeContractKey"]: route
            for route in self.v48["routes"]
            if route["routeKind"] == "PAGE"
        }
        self.assertEqual(old_pages, new_pages)
        self.assertFalse(
            any(
                "/hr/operations/pay" in json.dumps(self.routes[key], sort_keys=True)
                for key in self.routes
                if key.startswith(PREFIX)
            )
        )
        self.assertFalse(
            any("?" in (route.get("uiRoutePattern") or "") for route in self.v48["routes"])
        )

    def test_payroll_owner_pep_v48_is_checksum_pinned(self) -> None:
        self.assertEqual("hcm-payroll-pep-v48", self.pep["projectionKey"])
        self.assertEqual(PEP_SHA256, self.pep["projectionChecksum"])
        self.assertEqual(
            {"bundleKey": "product-surfaces", "version": 48, "sha256": V48_SHA256},
            self.pep["registryRef"],
        )
        self.assertEqual(31, self.pep["bindingPairCount"])
        self.assertEqual({"ACTION": 20, "DATA": 10, "PAGE": 1}, self.pep["routeKindCounts"])
        projected = {route["routeContractKey"] for route in self.pep["routes"]}
        self.assertEqual(
            {key for key in self.routes if key.startswith(PREFIX)},
            {key for key in projected if key.startswith(PREFIX)},
        )

    def test_gateway_assigns_exact_configuration_purpose_to_every_alias(self) -> None:
        gateway = (
            ROOT
            / "dwp-gateway/src/main/java/com/dwp/gateway/filter/ProductSurfaceDecisionContextFilter.java"
        ).read_text()
        for key in sorted(key for key in self.routes if key.startswith(PREFIX)):
            marker = f'Map.entry("{key}",'
            self.assertIn(marker, gateway)
            tail = gateway.split(marker, 1)[1].split("),", 1)[0]
            self.assertIn('"PAYROLL_CONFIGURATION"', tail)

    def test_default_off_owner_and_gateway_openapi_expose_only_settings_prefix(self) -> None:
        payroll = json.loads((ROOT / "contracts/openapi/payroll.json").read_text())
        gateway = json.loads((ROOT / "contracts/openapi/gateway-public.json").read_text())
        actual_owner = {
            (method, path, operation["operationId"])
            for path, path_item in payroll["paths"].items()
            if path.startswith("/v1/settings/payroll/foundation/")
            for method, operation in path_item.items()
        }
        self.assertEqual(SETTINGS_OPERATIONS, actual_owner)
        actual_gateway = {
            (method, path, operation["operationId"])
            for path, path_item in gateway["paths"].items()
            if path.startswith("/api/payroll/v1/settings/payroll/foundation/")
            for method, operation in path_item.items()
        }
        self.assertEqual(
            {
                (method, "/api/payroll" + path, "payroll_" + operation_id)
                for method, path, operation_id in SETTINGS_OPERATIONS
            },
            actual_gateway,
        )
        for method, path, _ in SETTINGS_OPERATIONS:
            operation = payroll["paths"][path][method]
            self.assertEqual("OFF", operation["x-dwp-runtime-default"])
            self.assertEqual("PAYROLL_CONFIGURATION", operation["x-dwp-purpose-code"])


if __name__ == "__main__":
    unittest.main()
