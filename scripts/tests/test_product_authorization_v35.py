from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v35_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV35Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v34 = snapshots[33]
        cls.v35 = snapshots[34]
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v35["routes"]
        }

    def test_v35_is_the_single_exact_g7_authorization_wave(self) -> None:
        def added(section: str, key: str) -> set[str]:
            prior = {value[key] for value in self.v34[section]}
            current = {value[key] for value in self.v35[section]}
            return current - prior

        self.assertEqual(35, self.v35["version"])
        self.assertEqual(
            "38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d",
            self.v35["checksum"],
        )
        self.assertEqual(
            {
                "hcm.configuration.policy.publish",
                "hcm.configuration.policy.update",
                "hcm.configuration.workbench.view",
                "hcm.operations.people-change.approve",
                "hcm.operations.people.update",
                "hcm.operations.people.view",
                "hcm.operations.performance.update",
                "hcm.operations.performance.view",
                "hcm.operations.time.update",
                "hcm.operations.time.view",
                "hcm.personal.profile.update",
                "hcm.personal.time.submit",
                "hcm.team.people-change.request",
            },
            added("capabilities", "contractKey"),
        )
        self.assertEqual(
            {
                "predicate.hcm-employee-change-authority.v1",
                "predicate.hcm-reference-configuration-authority.v1",
                "predicate.hcm-time-capture-authority.v1",
            },
            added("predicatePolicies", "predicatePolicyKey"),
        )
        self.assertEqual(
            {
                "route.hcm.configuration.configuration-version-approve.action",
                "route.hcm.configuration.configuration-version-create.action",
                "route.hcm.configuration.configuration-version-publish.action",
                "route.hcm.configuration.configuration-version-retire.action",
                "route.hcm.configuration.configuration-version-rollback.action",
                "route.hcm.configuration.configuration-version-schedule.action",
                "route.hcm.configuration.configuration-version-simulate.action",
                "route.hcm.configuration.configuration-version-submit.action",
                "route.hcm.configuration.configuration-version-validate.action",
                "route.hcm.configuration.configuration-version.data",
                "route.hcm.configuration.configurations.data",
                "route.hcm.configuration.settings-reference.page",
                "route.hcm.operations.employee-change-request-apply.action",
                "route.hcm.operations.employee-change-request-create.action",
                "route.hcm.operations.employee-change-request-submit.action",
                "route.hcm.operations.employee-change-requests.page",
                "route.hcm.operations.performance-population-freeze.action",
                "route.hcm.operations.performance-review-operations.page",
                "route.hcm.operations.time-clock-event-void.action",
                "route.hcm.operations.time-clock-ingestion.page",
                "route.hcm.operations.time-exceptions.page",
                "route.hcm.personal.time-clock-event-record.action",
            },
            added("routes", "routeContractKey"),
        )

    def test_five_g7_workbenches_are_exact_governed_page_routes(self) -> None:
        expected = {
            "route.hcm.operations.performance-review-operations.page": (
                "/hr/operations/performance/review-operations",
                "hcm.operations.performance.view",
                "GET",
                "/api/people/v1/hris/performance/workbench/review-operations",
                "people",
                "/v1/hris/performance/workbench/review-operations",
            ),
            "route.hcm.operations.time-clock-ingestion.page": (
                "/hr/operations/time/clock-ingestion",
                "hcm.operations.time.view",
                "GET",
                "/api/time/v1/hris/workbench/clock-ingestion",
                "time",
                "/v1/hris/workbench/clock-ingestion",
            ),
            "route.hcm.operations.time-exceptions.page": (
                "/hr/operations/time/exceptions",
                "hcm.operations.time.view",
                "GET",
                "/api/time/v1/hris/workbench/time-exceptions",
                "time",
                "/v1/hris/workbench/time-exceptions",
            ),
            "route.hcm.operations.employee-change-requests.page": (
                "/hr/operations/people/employee-change-requests",
                "hcm.operations.people.view",
                "GET",
                "/api/people/v1/hris/workbench/operations/employee/change/requests",
                "people",
                "/v1/hris/workbench/operations/employee/change/requests",
            ),
            "route.hcm.configuration.settings-reference.page": (
                "/hr/settings/reference-data",
                "hcm.configuration.workbench.view",
                "GET",
                "/api/platform/v1/hris/workbench/settings-reference",
                "platform",
                "/v1/hris/workbench/settings-reference",
            ),
        }
        projection_files = {
            "people": ROOT / "dwp-people-server/src/main/resources/product-authorization/hcm-people-pep-v35.generated.json",
            "time": ROOT / "dwp-time-server/src/main/resources/product-authorization/hcm-time-pep-v35.generated.json",
            "platform": ROOT / "dwp-platform-server/src/main/resources/product-authorization/hcm-platform-pep-v35.generated.json",
        }
        projections = {
            owner: {
                route["routeContractKey"]: route
                for route in json.loads(path.read_text())["routes"]
            }
            for owner, path in projection_files.items()
        }

        for key, (ui, capability, method, gateway, owner, service_path) in expected.items():
            route = self.routes[key]
            self.assertEqual("PAGE", route["routeKind"])
            self.assertEqual(ui, route["uiRoutePattern"])
            self.assertEqual(
                (method, gateway),
                (
                    route["gatewayApiBindings"][0]["method"],
                    route["gatewayApiBindings"][0]["path"],
                ),
            )
            service = route["servicePepBindings"][0]
            self.assertEqual((owner, method, service_path), (
                service["serviceKey"], service["method"], service["path"]
            ))
            profile = route["accessProfiles"][0]
            self.assertEqual(
                capability,
                profile["requiredAccess"]["capabilityContractKey"],
            )
            projection = profile["responseProjectionBindings"][0]
            self.assertEqual(
                route["gatewayApiBindings"][0]["bindingKey"],
                projection["apiBindingKey"],
            )
            self.assertEqual(route, projections[owner][key])

        stale = {key.removesuffix(".page") + ".data" for key in expected}
        self.assertTrue(stale.isdisjoint(self.routes))

    def test_time_void_has_the_exact_step_up_command_binding(self) -> None:
        key = "route.hcm.operations.time-clock-event-void.action"
        route = self.routes[key]
        self.assertEqual(
            [{
                "audience": "dwp-time-server",
                "bindingKey": key + ".binding.01",
                "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
                "expectedObjectVersionSource": "COMMAND_HEADER",
                "ownerServiceKey": "time",
                "targetIdPathParameter": "eventId",
                "targetType": "CLOCK_EVENT",
            }],
            route["stepUpCommandBindings"],
        )

        for nearby in (
            "route.hcm.operations.time-approve.action",
            "route.hcm.operations.time-clock-ingestion.page",
            "route.hcm.operations.time-exceptions.page",
        ):
            self.assertNotIn("stepUpCommandBindings", self.routes[nearby])


if __name__ == "__main__":
    unittest.main()
