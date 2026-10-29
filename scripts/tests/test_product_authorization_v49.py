import hashlib
import importlib.util
import json
import pathlib
import copy
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V48_CHECKSUM = "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043"
V48_FILE_SHA256 = "5b1d87250c3adcc72f2dd75238956c1da69a31d47512394480dba865ebced3c4"
V49_CHECKSUM = "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60"
V50_CHECKSUM = "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760"
SPEC = importlib.util.spec_from_file_location(
    "v49_authorization_generator",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

GENERIC_SESSION_STEP_UP_ROUTES = {
    "route.hcm.configuration.configuration-version.data",
    "route.hcm.configuration.configurations.data",
    "route.hcm.operations.payroll-population.page",
    "route.hcm.operations.payroll-support.data",
    "route.hcm.operations.payroll-run.data",
    "route.hcm.operations.time-close-validate.action",
    "route.hcm.operations.time-interpretation-run.action",
    "route.hcm.operations.time-interpretation-trace.data",
    "route.hcm.operations.time-payroll-handoff-create.action",
    "route.hcm.operations.time-payroll-handoff.data",
    "route.hcm.time.timecard-correction.action",
    "route.hcm.time.timecard.data",
}

NEW_ROUTES = {
    "route.hcm.operations.organization-positions.page":
        ("PAGE", "GET", "/api/people/v1/hris/organizations/graph", "people",
         "/v1/hris/organizations/graph", "HR_OPERATIONS"),
    "route.hcm.operations.organization-graph.data":
        ("DATA", "GET", "/api/people/v1/hris/organizations/graph", "people",
         "/v1/hris/organizations/graph", "HR_OPERATIONS"),
    "route.hcm.operations.payroll-population.page":
        ("PAGE", "GET", "/api/payroll/v1/workbench/population", "payroll",
         "/v1/workbench/population", "PAYROLL_OPERATIONS"),
    "route.hcm.operations.payroll-support.data":
        ("DATA", "GET", "/api/payroll/v1/workbench/population", "payroll",
         "/v1/workbench/population", "PAYROLL_OPERATIONS"),
    "route.hcm.operations.payroll-run.action":
        ("ACTION", "POST", "/api/payroll/v1/runs", "payroll", "/v1/runs",
         "PAYROLL_PROCESSING"),
    "route.hcm.configuration.settings-operations.page":
        ("PAGE", "GET", "/api/platform/v1/hris/workbench/settings-operations",
         "platform", "/v1/hris/workbench/settings-operations", "HRIS_CONFIGURATION"),
    "route.hcm.operations.exception.data":
        ("DATA", "GET", "/api/platform/v1/admin/hris/operations/exceptions",
         "platform", "/v1/admin/hris/operations/exceptions", "HRIS_OPERATIONS"),
    "route.hcm.operations.exception-detail.data":
        ("DATA", "GET", "/api/platform/v1/admin/hris/operations/exceptions/{exceptionId}",
         "platform", "/v1/admin/hris/operations/exceptions/{exceptionId}",
         "HRIS_OPERATIONS"),
    "route.hcm.operations.time-command-center.page":
        ("PAGE", "GET", "/api/time/v1/hris/workbench/time-command-center", "time",
         "/v1/hris/workbench/time-command-center", "TIME_OPERATIONS"),
    "route.hcm.operations.time-daily-close.page":
        ("PAGE", "GET", "/api/time/v1/hris/workbench/time-daily-close", "time",
         "/v1/hris/workbench/time-daily-close", "TIME_CLOSE"),
    "route.hcm.operations.time-monthly-close.page":
        ("PAGE", "GET", "/api/time/v1/hris/workbench/time-monthly-close", "time",
         "/v1/hris/workbench/time-monthly-close", "TIME_CLOSE"),
    "route.hcm.operations.time-close-validate.action":
        ("ACTION", "POST", "/api/time/v1/close-periods/{periodId}/validations",
         "time", "/v1/close-periods/{periodId}/validations", "WORK_TIME_PERIOD_CLOSURE"),
    "route.hcm.operations.time-close.action":
        ("ACTION", "POST", "/api/time/v1/close-periods/{periodId}/close", "time",
         "/v1/close-periods/{periodId}/close", "WORK_TIME_PERIOD_CLOSURE"),
    "route.hcm.operations.time-reopen.action":
        ("ACTION", "POST", "/api/time/v1/close-periods/{periodId}/reopen", "time",
         "/v1/close-periods/{periodId}/reopen", "WORK_TIME_PERIOD_CLOSURE"),
    "route.hcm.operations.time-payroll-handoff-create.action":
        ("ACTION", "POST", "/api/time/v1/close-periods/{periodId}/payroll-handoffs",
         "time", "/v1/close-periods/{periodId}/payroll-handoffs",
         "CLOSED_TIME_PAYROLL_TRANSFER"),
    "route.hcm.operations.time-payroll-handoff.data":
        ("DATA", "GET", "/api/time/v1/payroll-handoffs/{handoffId}", "time",
         "/v1/payroll-handoffs/{handoffId}", "CLOSED_TIME_PAYROLL_TRANSFER"),
}


class ProductAuthorizationV49Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v48_path = CONTRACTS / "product-surfaces-v1.bundle-v48.json"
        cls.v48 = json.loads(cls.v48_path.read_text())
        cls.v49 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v49.json").read_text()
        )
        cls.v50 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v50.json").read_text()
        )
        cls.routes = {route["routeContractKey"]: route for route in cls.v49["routes"]}
        cls.capabilities = {
            item["contractKey"]: item for item in cls.v49["capabilities"]
        }
        cls.predicates = {
            item["predicatePolicyKey"]: item for item in cls.v49["predicatePolicies"]
        }

    def test_v49_is_the_exact_append_plus_narrow_payroll_hardening(self) -> None:
        self.assertEqual((48, V48_CHECKSUM), (self.v48["version"], self.v48["checksum"]))
        self.assertEqual(
            V48_FILE_SHA256, hashlib.sha256(self.v48_path.read_bytes()).hexdigest()
        )
        self.assertEqual((49, V49_CHECKSUM, "DRAFT"), (
            self.v49["version"], self.v49["checksum"], self.v49["bundleStatus"]
        ))
        self.assertEqual((262, 24, 17, 81, 1079), (
            len(self.v49["capabilities"]), len(self.v49["accessPolicies"]),
            len(self.v49["entitlementExpressions"]),
            len(self.v49["predicatePolicies"]), len(self.v49["routes"]),
        ))
        previous_routes = {item["routeContractKey"]: item for item in self.v48["routes"]}
        self.assertEqual(set(NEW_ROUTES), set(self.routes) - set(previous_routes))
        changed = {
            key for key, prior in previous_routes.items() if self.routes[key] != prior
        }
        self.assertEqual(
            {"route.hcm.operations.payroll-run-calculate.action"}, changed
        )

    def test_exact_route_and_gateway_purpose_tuples(self) -> None:
        gateway_filter = (
            ROOT / "dwp-gateway/src/main/java/com/dwp/gateway/filter/"
                   "ProductSurfaceDecisionContextFilter.java"
        ).read_text()
        gateway_policies = (
            ROOT / "dwp-gateway/src/main/java/com/dwp/gateway/filter/"
                   "ProductSurfaceRoutePolicies.java"
        ).read_text()
        self.assertIn("import static com.dwp.gateway.filter.ProductSurfaceRoutePolicies.*;",
                      gateway_filter)
        self.assertIn("ownerPurpose(route.routeContractKey())", gateway_filter)
        for key, expected in NEW_ROUTES.items():
            kind, method, public_path, service_key, owner_path, purpose = expected
            route = self.routes[key]
            with self.subTest(route=key):
                self.assertEqual(kind, route["routeKind"])
                self.assertEqual((method, public_path), (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ))
                self.assertEqual((service_key, owner_path), (
                    route["servicePepBindings"][0]["serviceKey"],
                    route["servicePepBindings"][0]["path"],
                ))
                marker = f'Map.entry("{key}",'
                self.assertIn(marker, gateway_policies)
                self.assertIn(
                    f'"{purpose}"',
                    gateway_policies.split(marker, 1)[1].split("),", 1)[0],
                )
        self.assertNotIn("SVC-PEP-TIM-001", json.dumps(self.v49, sort_keys=True))
        self.assertNotIn("/internal/hris-time/", json.dumps(self.v49, sort_keys=True))

    def test_new_capabilities_predicates_and_reuse_boundaries_are_exact(self) -> None:
        expected_capabilities = {
            "hcm.operations.organization.view":
                ("DATA.HRIS_ORGANIZATION:VIEW", "HIGH", None,
                 "HRIS_DUTY_ORGANIZATION_VIEWER"),
            "hcm.operations.exception.view":
                ("DATA.HRIS_OPERATION_EXCEPTION:VIEW", "HIGH", None,
                 "HRIS_DUTY_OPERATION_EXCEPTION_READER"),
            "hcm.operations.time.close":
                ("ACTION.HRIS_TIME_CLOSE:EXECUTE", "CRITICAL", "STEPUP-MGMT-HIGH-V1",
                 "HRIS_DUTY_TIME_CLOSE_OPERATOR"),
            "hcm.operations.time.reopen":
                ("ACTION.HRIS_TIME_REOPEN:MANAGE", "CRITICAL", "STEPUP-MGMT-HIGH-V1",
                 "HRIS_DUTY_TIME_REOPEN_OPERATOR"),
        }
        for key, expected in expected_capabilities.items():
            item = self.capabilities[key]
            self.assertEqual(expected, (
                item["resolvedCapabilityCode"], item["riskTier"],
                item["activationPolicy"], item["requiredResponsibilityCode"],
            ))
            self.assertTrue(item["requiresProductEntitlement"])
        self.assertEqual(("CRITICAL", "STEPUP-MGMT-HIGH-V1"), (
            self.capabilities["hcm.operations.payroll-run.execute"]["riskTier"],
            self.capabilities["hcm.operations.payroll-run.execute"]["activationPolicy"],
        ))
        self.assertEqual(("HIGH", "STEPUP-MGMT-HIGH-V1"), (
            self.capabilities["hcm.operations.payroll-support.view"]["riskTier"],
            self.capabilities["hcm.operations.payroll-support.view"]["activationPolicy"],
        ))
        self.assertEqual(8, len(set(self.predicates) - {
            item["predicatePolicyKey"] for item in self.v48["predicatePolicies"]
        }))
        self.assertEqual(
            next(route for route in self.v48["routes"]
                 if route["routeContractKey"]
                 == "route.hcm.utility.explorer-command-receipt.data"),
            self.routes["route.hcm.utility.explorer-command-receipt.data"],
        )

    def test_generic_and_fresh_command_step_up_decisions_are_exact(self) -> None:
        self.assertEqual(("CRITICAL", "STEPUP-MGMT-HIGH-V1"), (
            self.capabilities["hcm.operations.time.close"]["riskTier"],
            self.capabilities["hcm.operations.time.close"]["activationPolicy"],
        ))
        self.assertEqual(("CRITICAL", "STEPUP-MGMT-HIGH-V1"), (
            self.capabilities["hcm.operations.time.reopen"]["riskTier"],
            self.capabilities["hcm.operations.time.reopen"]["activationPolicy"],
        ))
        for key in (
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
        ):
            route = self.routes[key]
            self.assertEqual(["ELEVATED"],
                             route["accessProfiles"][0]["activeAccessModes"])
            self.assertNotIn("stepUpCommandBindings", route)
        calculate = self.routes["route.hcm.operations.payroll-run-calculate.action"]
        self.assertEqual(["ELEVATED"], calculate["accessProfiles"][0]["activeAccessModes"])
        self.assertEqual("runId", calculate["stepUpCommandBindings"][0]["targetIdPathParameter"])
        prepare = self.routes["route.hcm.operations.payroll-run.action"]
        self.assertEqual(["preparationId"],
                         prepare["stepUpCommandBindings"][0]["targetIdBodyFields"])
        for key in (
            "route.hcm.operations.time-close.action",
            "route.hcm.operations.time-reopen.action",
        ):
            route = self.routes[key]
            self.assertEqual(["NORMAL", "ELEVATED"],
                             route["accessProfiles"][0]["activeAccessModes"])
            self.assertEqual("If-Match",
                             route["stepUpCommandBindings"][0]["expectedObjectVersionName"])
        for key in (
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data",
        ):
            self.assertEqual(
                "hcm.operations.time.close",
                self.routes[key]["accessProfiles"][0]["requiredAccess"]
                ["capabilityContractKey"],
            )
            self.assertEqual(["NORMAL", "ELEVATED"],
                             self.routes[key]["accessProfiles"][0]["activeAccessModes"])
            self.assertNotIn("stepUpCommandBindings", self.routes[key])

    def test_generator_seals_exactly_the_twelve_v49_generic_session_topologies(self) -> None:
        self.assertEqual(
            GENERIC_SESSION_STEP_UP_ROUTES,
            set(GENERATOR.GRANDFATHERED_GENERIC_SESSION_STEP_UP_ROUTE_KEYS)
            | set(GENERATOR.V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS),
        )
        self.assertTrue(
            GENERIC_SESSION_STEP_UP_ROUTES.issubset(
                GENERATOR.GENERIC_SESSION_STEP_UP_ROUTE_KEYS
            )
        )
        for key in GENERIC_SESSION_STEP_UP_ROUTES:
            route = self.routes[key]
            gateway = {
                item["bindingKey"]: item for item in route["gatewayApiBindings"]
            }
            service = {
                item["bindingKey"]: item for item in route["servicePepBindings"]
            }
            with self.subTest(route=key):
                self.assertTrue(GENERATOR._is_generic_session_step_up_exception(
                    route, gateway, service, 49
                ))

        thirteenth = self.routes[
            "route.hcm.operations.payroll-run-calculate.action"
        ]
        self.assertFalse(GENERATOR._is_generic_session_step_up_exception(
            thirteenth,
            {item["bindingKey"]: item for item in thirteenth["gatewayApiBindings"]},
            {item["bindingKey"]: item for item in thirteenth["servicePepBindings"]},
            49,
        ))

        drifted = copy.deepcopy(
            self.routes["route.hcm.configuration.configurations.data"]
        )
        drifted["servicePepBindings"][0]["path"] += "-drift"
        with self.assertRaises(GENERATOR.ContractError):
            GENERATOR._is_generic_session_step_up_exception(
                drifted,
                {item["bindingKey"]: item for item in drifted["gatewayApiBindings"]},
                {item["bindingKey"]: item for item in drifted["servicePepBindings"]},
                49,
            )
        alternative_drift = copy.deepcopy(
            self.routes["route.hcm.time.timecard-correction.action"]
        )
        alternative_drift["accessProfiles"][1]["requiredAccess"][
            "capabilityContractKey"
        ] = "hcm.personal.time.view"
        with self.assertRaises(GENERATOR.ContractError):
            GENERATOR._is_generic_session_step_up_exception(
                alternative_drift,
                {item["bindingKey"]: item
                 for item in alternative_drift["gatewayApiBindings"]},
                {item["bindingKey"]: item
                 for item in alternative_drift["servicePepBindings"]},
                49,
            )

    def test_wire_equivalence_and_time_sod_binding_boundaries_are_exact(self) -> None:
        expected_equivalence = {
            "route.hcm.operations.organization-positions.page":
                "wire-authority.hcm.operations.organization-graph.v1",
            "route.hcm.operations.organization-graph.data":
                "wire-authority.hcm.operations.organization-graph.v1",
            "route.hcm.operations.payroll-population.page":
                "wire-authority.hcm.operations.payroll-population.v1",
            "route.hcm.operations.payroll-support.data":
                "wire-authority.hcm.operations.payroll-population.v1",
        }
        for key, equivalence in expected_equivalence.items():
            self.assertEqual(equivalence,
                             self.routes[key]["authorizationEquivalenceKey"])
        self.assertEqual(["OBJECT"], self.predicates[
            "predicate.hcm-time-close-sod.v1"
        ]["targetBindingKinds"])
        self.assertEqual(["OBJECT"], self.predicates[
            "predicate.hcm-time-reopen-sod.v1"
        ]["targetBindingKinds"])

    def test_current_alias_is_v62_and_historical_v49_owner_projections_are_pinned(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH_SEEDS / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        latest = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v62.json").read_text()
        )
        self.assertEqual(latest, alias)
        self.assertEqual(latest, seed)
        self.assertEqual((62, latest["checksum"]),
                         (index["latestVersion"], index["latestChecksum"]))
        expected = {
            "people": (103, 130, {"ACTION": 48, "DATA": 20, "PAGE": 35},
                       "3346929f550830ee236af702be33da2e58ccfbd0704c44d820f5d8f98a33739a"),
            "payroll": (34, 34, {"ACTION": 21, "DATA": 11, "PAGE": 2},
                        "5204125c7748c5052a952506646b30ffb8109463d1b01066f3830e1c1af663b6"),
            "platform": (53, 54, {"ACTION": 27, "DATA": 19, "PAGE": 7},
                         "de70aba5a34df3bfdf1687386fb9c183a1a250e205dc4ac73968e2dfb731dd5e"),
            "time": (29, 29, {"ACTION": 16, "DATA": 6, "PAGE": 7},
                     "848bee74cf878067755e83f2cce5203dc029fe2a782810436e9f7cbc7ac7c682"),
        }
        for service, values in expected.items():
            projection = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                       f"hcm-{service}-pep-v49.generated.json"
            ).read_text())
            routes, bindings, kinds, checksum = values
            self.assertEqual((routes, bindings, kinds, checksum), (
                projection["projectedRouteContractCount"],
                projection["bindingPairCount"], projection["routeKindCounts"],
                projection["projectionChecksum"],
            ))
            self.assertEqual({"bundleKey": "product-surfaces", "version": 49,
                              "sha256": V49_CHECKSUM}, projection["registryRef"])


if __name__ == "__main__":
    unittest.main()
