from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v15_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

PAGE_PATHS = {
    "/approvals/admin/routing",
    "/approvals/admin/integrations",
    "/approvals/admin/audit",
    "/approvals/admin/analytics",
    "/approvals/admin/deployments",
}
ALLOWED_CAPABILITIES = {
    "approvals.design.read",
    "approvals.design.update",
    "approvals.design.publish",
    "approvals.policy.read",
    "approvals.policy.update",
    "approvals.policy.publish",
    "approvals.operations.read",
    "approvals.operations.execute",
    "approvals.audit.operations.read",
}
LOW_RISK_UPDATES = {
    "route.approvals.admin.template-draft.action",
    "route.approvals.admin.form-studio-draft.action",
    "route.approvals.admin.routing-directory-update.action",
    "route.approvals.admin.policy-automation-update.action",
}


class ProductAuthorizationV15Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = GENERATOR.load_source()
        cls.snapshots = GENERATOR.build_snapshots(cls.source)
        cls.v14 = cls.snapshots[13]
        cls.v15 = cls.snapshots[14]
        prior = {route["routeContractKey"] for route in cls.v14["routes"]}
        cls.all_appended = [
            route for route in cls.v15["routes"]
            if route["routeContractKey"] not in prior
        ]
        cls.appended = [
            route for route in cls.all_appended
            if route["subject"].get("productKey") == "approvals"
        ]

    def test_v15_is_the_exact_apr17_through_24_append(self):
        self.assertEqual(len(self.appended), 43)
        self.assertEqual(
            {kind: sum(route["routeKind"] == kind for route in self.appended)
             for kind in ("PAGE", "DATA", "ACTION")},
            {"PAGE": 5, "DATA": 18, "ACTION": 20},
        )
        self.assertEqual(
            "a9eab001b26d6488de0f7f176fa1d70fea2c9792a230769f645eb53ba90cf89f",
            self.v15["checksum"],
        )
        self.assertEqual(
            GENERATOR.render(self.v15).encode(),
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[15].read_bytes(),
        )
        for version, snapshot in enumerate(self.snapshots[:14], start=1):
            self.assertEqual(
                GENERATOR.render(snapshot).encode(),
                GENERATOR.VERSIONED_CONTRACT_OUTPUTS[version].read_bytes(),
            )

    def test_pages_capabilities_and_wires_are_exact(self):
        pages = {
            route["uiRoutePattern"]
            for route in self.appended
            if route["routeKind"] == "PAGE"
        }
        capabilities = {
            profile["requiredAccess"]["capabilityContractKey"]
            for route in self.appended
            for profile in route["accessProfiles"]
            if profile["requiredAccess"]["type"] == "CAPABILITY"
        }
        self.assertEqual(pages, PAGE_PATHS)
        self.assertEqual(capabilities, ALLOWED_CAPABILITIES)

        binding_count = 0
        for route in self.appended:
            public = {binding["bindingKey"]: binding
                      for binding in route["gatewayApiBindings"]}
            service = {binding["bindingKey"]: binding
                       for binding in route["servicePepBindings"]}
            self.assertEqual(public.keys(), service.keys())
            binding_count += len(service)
            for binding_key, owner in service.items():
                gateway = public[binding_key]
                self.assertEqual(gateway["method"], owner["method"])
                self.assertEqual(gateway["path"], "/api/approvals" + owner["path"])
                self.assertEqual(owner["serviceKey"], "approval")
                self.assertNotIn("*", gateway["path"])
        self.assertEqual(binding_count, 89)

    def test_only_exact_high_risk_commands_have_step_up_bindings(self):
        commands = [
            command
            for route in self.appended
            for command in route.get("stepUpCommandBindings", [])
        ]
        self.assertEqual(len(commands), 28)
        for route in self.appended:
            service_keys = {
                binding["bindingKey"] for binding in route["servicePepBindings"]
            }
            for command in route.get("stepUpCommandBindings", []):
                self.assertIn(command["bindingKey"], service_keys)
                self.assertEqual(command["ownerServiceKey"], "approval")
                self.assertEqual(command["audience"], "dwp-approval-server")
                self.assertEqual(command["expectedObjectVersionSource"], "COMMAND_HEADER")
                self.assertEqual(
                    command["expectedObjectVersionName"],
                    "X-DWP-Expected-Object-Version",
                )
                self.assertNotEqual(
                    "targetIdPathParameter" in command,
                    "targetIdBodyFields" in command,
                )
        routes = {route["routeContractKey"]: route for route in self.appended}
        for route_key in LOW_RISK_UPDATES:
            self.assertNotIn("stepUpCommandBindings", routes[route_key])

    def test_v15_adds_the_exact_workplace_connector_runtime_routes(self):
        routes = [
            route for route in self.all_appended
            if route["routeContractKey"].startswith(
                "route.workplace.management.connector-"
            )
        ]
        self.assertEqual(
            {route["routeContractKey"] for route in routes},
            {
                "route.workplace.management.connector-operations.data",
                "route.workplace.management.connector-operation.data",
                "route.workplace.management.connector-replay-preview.action",
                "route.workplace.management.connector-replay-start.action",
                "route.workplace.management.connector-replay-status.data",
            },
        )
        self.assertEqual(
            {kind: sum(route["routeKind"] == kind for route in routes)
             for kind in ("PAGE", "DATA", "ACTION")},
            {"PAGE": 0, "DATA": 3, "ACTION": 2},
        )
        self.assertEqual(
            {profile["requiredAccess"]["capabilityContractKey"]
             for route in routes for profile in route["accessProfiles"]},
            {"workplace.connector-runtime.read", "workplace.connector-replay.manage"},
        )
        elevated = {
            route["routeContractKey"]
            for route in routes
            if route["accessProfiles"][0]["activeAccessModes"] == ["ELEVATED"]
        }
        self.assertEqual(elevated, {
            "route.workplace.management.connector-replay-preview.action",
            "route.workplace.management.connector-replay-start.action",
            "route.workplace.management.connector-replay-status.data",
        })

    def test_v15_find_and_planner_pages_are_explicit_explore_wire_aliases(self):
        routes = {
            route["routeContractKey"]: route
            for route in self.v15["routes"]
            if route["routeContractKey"] in {
                "route.workplace.work.explore.page",
                "route.workplace.work.find.page",
                "route.workplace.work.planner.page",
            }
        }
        self.assertEqual(len(routes), 3)
        explore = routes["route.workplace.work.explore.page"]
        find = routes["route.workplace.work.find.page"]
        planner = routes["route.workplace.work.planner.page"]
        self.assertEqual(
            explore["authorizationEquivalenceKey"],
            "wire-authority.workplace.work.explore.v1",
        )
        self.assertEqual(find["authorizationEquivalenceKey"],
                         explore["authorizationEquivalenceKey"])
        self.assertEqual(find["gatewayApiBindings"][0]["path"],
                         explore["gatewayApiBindings"][0]["path"])
        self.assertEqual(find["servicePepBindings"][0]["path"],
                         explore["servicePepBindings"][0]["path"])
        self.assertEqual(find["accessProfiles"][0]["requiredAccess"],
                         explore["accessProfiles"][0]["requiredAccess"])
        self.assertEqual(
            find["accessProfiles"][0]["responseProjectionBindings"][0]
                ["projectionPolicyKey"],
            explore["accessProfiles"][0]["responseProjectionBindings"][0]
                ["projectionPolicyKey"],
        )
        self.assertEqual(planner["uiRoutePattern"], "/workplace/planner")
        self.assertEqual(planner["authorizationEquivalenceKey"],
                         explore["authorizationEquivalenceKey"])
        self.assertEqual(planner["gatewayApiBindings"][0]["path"],
                         explore["gatewayApiBindings"][0]["path"])
        self.assertEqual(planner["servicePepBindings"][0]["path"],
                         explore["servicePepBindings"][0]["path"])
        self.assertEqual(planner["accessProfiles"][0]["requiredAccess"],
                         explore["accessProfiles"][0]["requiredAccess"])
        self.assertEqual(
            planner["accessProfiles"][0]["responseProjectionBindings"][0]
                ["projectionPolicyKey"],
            explore["accessProfiles"][0]["responseProjectionBindings"][0]
                ["projectionPolicyKey"],
        )

    def test_v15_reservations_page_keeps_two_authoritative_read_projections(self):
        route = next(
            route for route in self.v15["routes"]
            if route["routeContractKey"]
            == "route.workplace.work.reservations.page"
        )
        self.assertEqual(route["uiRoutePattern"], "/workplace/reservations")
        self.assertEqual(
            [binding["path"] for binding in route["gatewayApiBindings"]],
            [
                "/api/platform/v1/workplace/bookings",
                "/api/platform/v1/rooms/bookings",
            ],
        )
        projections = route["accessProfiles"][0]["responseProjectionBindings"]
        self.assertEqual(len(projections), 2)
        self.assertEqual(
            {projection["apiBindingKey"] for projection in projections},
            {
                "route.workplace.work.reservations.page.binding.01",
                "route.workplace.work.reservations.page.binding.02",
            },
        )
        self.assertEqual(
            len({projection["projectionPolicyKey"] for projection in projections}), 2
        )

    def test_wildcard_and_target_source_drift_fail_generation(self):
        for mutation in ("wildcard", "target"):
            with self.subTest(mutation=mutation):
                source = copy.deepcopy(self.source)
                wave = next(item for item in source["waves"] if item["version"] == 15)
                route = next(item for item in wave["routes"] if item["routeContractKey"] ==
                             "route.approvals.admin.routing-directory-publish.action")
                if mutation == "wildcard":
                    route["gatewayApiBindings"][0]["path"] += "/**"
                else:
                    route["stepUpCommandBindings"][0]["targetIdPathParameter"] = \
                        "notAControllerParameter"
                with self.assertRaises(GENERATOR.ContractError):
                    GENERATOR.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
