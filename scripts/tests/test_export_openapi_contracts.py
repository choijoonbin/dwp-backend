from __future__ import annotations

import copy
import json
import re
import runpy
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXPORTER = runpy.run_path(str(ROOT / "scripts" / "export-openapi-contracts.py"))

WORKPLACE_V20_PLATFORM_OPERATIONS = frozenset(
    tuple(operation.split(" ", 1))
    for operation in """
get /v1/admin/workplace/visits/exceptions
get /v1/admin/workplace/visits/{visitId}
post /v1/admin/workplace/visits/{visitId}:approve
post /v1/admin/workplace/visits/{visitId}:retry-access
post /v1/admin/workplace/visits/{visitId}:notify-host
post /v1/admin/workplace/visits/{visitId}:confirm-checkout
post /v1/workplace/visits:preview
post /v1/workplace/visits
get /v1/workplace/visits
get /v1/workplace/visits/{visitId}
post /v1/workplace/visits/{visitId}:send-invitation
post /v1/workplace/visits/{visitId}/access-requests
post /v1/workplace/visits/{visitId}:cancel
get /v1/workplace/kiosk/session
get /v1/workplace/kiosk/visits/{visitId}
post /v1/workplace/kiosk/visits/{visitId}:arrive
post /v1/workplace/kiosk/visits/{visitId}:checkout
post /v1/workplace/kiosk/devices/{deviceId}:heartbeat
post /v1/workplace/kiosk/devices/{deviceId}:help
get /v1/admin/workplace/visit-policies
post /v1/admin/workplace/visit-policies
put /v1/admin/workplace/visit-policies/{policyId}
post /v1/admin/workplace/visit-policies/{policyId}:impact-preview
get /v1/admin/workplace/access-zones
post /v1/admin/workplace/access-zones
put /v1/admin/workplace/access-zones/{zoneId}
get /v1/admin/workplace/provider-bindings
post /v1/admin/workplace/provider-bindings
put /v1/admin/workplace/provider-bindings/{bindingId}
post /v1/admin/workplace/provider-bindings/{bindingId}:test
get /v1/admin/workplace/kiosk-devices
post /v1/admin/workplace/kiosk-devices
put /v1/admin/workplace/kiosk-devices/{deviceId}
get /v1/admin/workplace/service-providers
get /v1/admin/workplace/service-providers/{providerId}
post /v1/admin/workplace/service-providers
put /v1/admin/workplace/service-providers/{providerId}
post /v1/admin/workplace/service-providers/{providerId}:state
post /v1/admin/workplace/service-providers/{providerId}:verify
get /v1/admin/workplace/service-assignees
post /v1/admin/workplace/service-orders/{orderId}/tasks/{taskId}:assign
get /v1/workplace/service-catalog/{catalogItemId}/capacity
get /v1/admin/workplace/service-catalog/{catalogItemId}/capacity
put /v1/admin/workplace/service-catalog/{catalogItemId}/capacity
get /v1/workplace/service-orders/{orderId}/lines/{lineId}/inspection
post /v1/workplace/service-orders/{orderId}/lines/{lineId}/inspection-attempts
get /v1/admin/workplace/service-orders/{orderId}/lines/{lineId}/inspection
post /v1/admin/workplace/service-orders/{orderId}/lines/{lineId}/inspection-attempts
post /v1/workplace/service-orders/{orderId}/lines/{lineId}/access-credentials:issue
post /v1/workplace/service-orders/{orderId}/lines/{lineId}/access-credentials/{grantId}:revoke
post /v1/workplace/service-orders/{orderId}/contacts
post /v1/device/workplace/devices:register
post /v1/device/workplace/devices/{deviceId}/heartbeat
get /v1/device/workplace/devices/{deviceId}/projection
get /v1/admin/workplace/navigation/graphs
post /v1/admin/workplace/navigation/graphs
post /v1/admin/workplace/navigation/graphs/{graphId}:publish
post /v1/admin/workplace/navigation/graphs/{graphId}:review
post /v1/admin/workplace/navigation/graphs/{graphId}:archive
get /v1/admin/workplace/devices
get /v1/admin/workplace/devices/{deviceId}
get /v1/admin/workplace/devices/{deviceId}/commands
get /v1/admin/workplace/devices/{deviceId}/audit-events
post /v1/admin/workplace/devices/{deviceId}:approve
post /v1/admin/workplace/devices/{deviceId}:bind
get /v1/admin/workplace/device-providers
put /v1/admin/workplace/device-providers/{capability}
post /v1/admin/workplace/devices/{deviceId}/commands:preview
post /v1/admin/workplace/devices/{deviceId}/commands
get /v1/admin/workplace/devices/commands/{commandId}
get /v1/workplace/navigation/routes
get /v1/workplace/navigation/pois
post /v1/admin/workplace/safety/incidents:preview
get /v1/admin/workplace/safety/activation-previews/{previewId}
post /v1/admin/workplace/safety/incidents
get /v1/admin/workplace/safety/incidents
get /v1/admin/workplace/safety/incidents/{incidentId}
get /v1/admin/workplace/safety/incidents/{incidentId}/commands/{commandId}
post /v1/admin/workplace/safety/incidents/{incidentId}/scope-revisions:preview
get /v1/admin/workplace/safety/incidents/{incidentId}/scope-revisions/{revisionId}
post /v1/admin/workplace/safety/incidents/{incidentId}/scope-revisions
post /v1/admin/workplace/safety/incidents/{incidentId}/dispatches:resend
get /v1/admin/workplace/safety/incidents/{incidentId}/messages
post /v1/admin/workplace/safety/incidents/{incidentId}/assembly-confirmations
post /v1/admin/workplace/safety/incidents/{incidentId}/messages
post /v1/admin/workplace/safety/incidents/{incidentId}/closures:preview
get /v1/admin/workplace/safety/incidents/{incidentId}/closure-previews/{previewId}
post /v1/admin/workplace/safety/incidents/{incidentId}/closure-requests
post /v1/admin/workplace/safety/incidents/{incidentId}/closure-requests/{closureId}:approve
get /v1/admin/workplace/safety/incidents/{incidentId}/report
post /v1/admin/workplace/safety/incidents/{incidentId}/exports
get /v1/admin/workplace/safety/exports/{exportId}/content
get /v1/admin/workplace/safety/connectors
put /v1/admin/workplace/safety/connectors/{kind}
get /v1/admin/workplace/safety/connectors/commands/{commandId}
get /v1/workplace/safety/incidents/active
get /v1/workplace/safety/incidents/{incidentId}
post /v1/workplace/safety/incidents/{incidentId}/responses
get /v1/workplace/safety/incidents/{incidentId}/messages
post /v1/workplace/safety/incidents/{incidentId}/messages
get /v1/admin/workplace/space-planning/overview
get /v1/admin/workplace/space-planning/sources
post /v1/admin/workplace/space-planning/scenarios
get /v1/admin/workplace/space-planning/scenarios
get /v1/admin/workplace/space-planning/scenarios/{scenarioId}
put /v1/admin/workplace/space-planning/scenarios/{scenarioId}
post /v1/admin/workplace/space-planning/scenarios/{scenarioId}:preview
post /v1/admin/workplace/space-planning/scenarios/{scenarioId}:submit
post /v1/admin/workplace/space-planning/scenarios/{scenarioId}:approve
post /v1/admin/workplace/space-planning/scenarios/{scenarioId}:publish
post /v1/admin/workplace/space-planning/scenarios/{scenarioId}/booking-impact:preview
post /v1/workplace/assistant/requests
get /v1/workplace/assistant/requests/{requestId}
post /v1/workplace/assistant/requests/{requestId}:validate
post /v1/workplace/assistant/requests/{requestId}:confirm
get /v1/workplace/assistant/requests/{requestId}/execution
post /v1/workplace/assistant/requests/{requestId}:feedback
get /v1/admin/workplace/assistant/governance
put /v1/admin/workplace/assistant/governance
get /v1/admin/workplace/assistant/audit-events
""".strip().splitlines()
)

DEVICE_IDENTITY_OPERATIONS = frozenset(
    operation for operation in WORKPLACE_V20_PLATFORM_OPERATIONS
    if operation[1].startswith("/v1/device/workplace/devices")
    or operation[1].startswith("/v1/workplace/kiosk")
) | frozenset({
    ("post", "/v1/device/workplace/devices/{deviceId}/access-pass:pair"),
})

ROOM_HUMAN_OPERATIONS = frozenset(
    tuple(operation.split(" ", 1))
    for operation in """
get /v1/admin/rooms/bookings/pending
post /v1/admin/rooms/bookings/{bookingId}/decision
get /v1/admin/rooms/overview
get /v1/admin/rooms/policy
put /v1/admin/rooms/policy
post /v1/admin/rooms/resources
put /v1/admin/rooms/resources/{resourceId}
get /v1/rooms/availability
get /v1/rooms/bookings
post /v1/rooms/bookings
put /v1/rooms/bookings/{eventId}
post /v1/rooms/bookings/{eventId}/cancel
post /v1/rooms/bookings/{eventId}/response
get /v1/rooms/policy
""".strip().splitlines()
)

IDEMPOTENT_WORKPLACE_MUTATIONS = frozenset({
    ("post", "/v1/workplace/bookings/{bookingId}/check-in"),
    ("post", "/v1/workplace/bookings/{bookingId}/cancel"),
    ("post", "/v1/workplace/bookings/{bookingId}/release"),
    ("post", "/v1/workplace/bookings/{bookingId}/relocate"),
    ("post", "/v1/admin/workplace/devices/{deviceId}:approve"),
    ("post", "/v1/admin/workplace/devices/{deviceId}:bind"),
    ("put", "/v1/admin/workplace/device-providers/{capability}"),
    ("post", "/v1/admin/workplace/devices/{deviceId}/commands:preview"),
    ("post", "/v1/admin/workplace/connectors/{kind}/replays:preview"),
    ("post", "/v1/admin/workplace/connectors/{kind}/replays"),
})


class ExportOpenApiContractsTest(unittest.TestCase):
    def test_v20_human_workplace_delta_is_exact_and_gateway_governed(self) -> None:
        previous = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v19.json")
            .read_text(encoding="utf-8")
        )
        current = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v20.json")
            .read_text(encoding="utf-8")
        )
        latest = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v24.json")
            .read_text(encoding="utf-8")
        )
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        def bindings(document: dict) -> set[tuple[str, str]]:
            return {
                (binding["method"].lower(), binding["path"])
                for route in document["routes"]
                for binding in route.get("gatewayApiBindings", [])
            }

        methods = {"get", "post", "put", "patch", "delete"}
        platform_operations = {
            (method, path)
            for path, item in platform["paths"].items()
            for method in item
            if method in methods
        }
        runtime_human_workplace = {
            operation
            for operation in platform_operations
            if operation[1].startswith("/v1/workplace/")
            or operation[1].startswith("/v1/admin/workplace/")
        } - DEVICE_IDENTITY_OPERATIONS
        post_v20 = {
            (method, path.removeprefix("/api/platform"))
            for method, path in bindings(latest) - bindings(current)
            if path.startswith("/api/platform/v1/workplace/")
            or path.startswith("/api/platform/v1/admin/workplace/")
        }
        human_workplace = runtime_human_workplace - post_v20
        self.assertEqual(len(runtime_human_workplace), 311)
        self.assertEqual(len(post_v20), 41)
        room_operations = {
            operation
            for operation in platform_operations
            if operation[1].startswith("/v1/rooms/")
            or operation[1].startswith("/v1/admin/rooms/")
        }
        self.assertEqual(len(human_workplace), 270)
        self.assertEqual(room_operations, ROOM_HUMAN_OPERATIONS)
        expected_all = {
            (method, "/api/platform" + path)
            for method, path in human_workplace | room_operations
        }
        expected = expected_all - bindings(previous)
        delta = bindings(current) - bindings(previous)
        self.assertEqual(len(expected), 226)
        self.assertEqual(delta, expected)
        self.assertFalse({
            (method, "/api/platform" + path)
            for method, path in DEVICE_IDENTITY_OPERATIONS
        } & bindings(current))

        state_changing: dict[tuple[str, str], bool] = {}
        for route in current["routes"]:
            if (
                route.get("lifecycleState") != "ACTIVE"
                or route.get("subject", {}).get("type") != "PRODUCT"
            ):
                continue
            action = (
                route.get("routeKind") == "ACTION"
                and route.get("sideEffectFree") is not True
            )
            for binding in route.get("gatewayApiBindings", []):
                key = (binding["method"].lower(), binding["path"])
                state_changing[key] = state_changing.get(key, False) or action

        for method, public_path in delta:
            service_path = public_path.removeprefix("/api/platform")
            self.assertIn(method, platform["paths"].get(service_path, {}))
            operation = gateway["paths"][public_path][method]
            scope = [
                parameter for parameter in operation.get("parameters", [])
                if parameter.get("in") == "query"
                and parameter.get("name") == "contextScopeKey"
            ]
            revision = [
                parameter for parameter in operation.get("parameters", [])
                if parameter.get("in") == "header"
                and parameter.get("name") == "X-DWP-Expected-Decision-Revision"
            ]
            self.assertEqual(len(scope), 1, f"scope {method} {public_path}")
            self.assertEqual(
                len(revision), 1 if state_changing[(method, public_path)] else 0,
                f"revision {method} {public_path}",
            )

    def test_platform_and_gateway_publish_exact_workplace_v20_openapi_delta(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        self.assertEqual(len(WORKPLACE_V20_PLATFORM_OPERATIONS), 120)
        for method, path in WORKPLACE_V20_PLATFORM_OPERATIONS:
            self.assertIn(method, platform["paths"].get(path, {}), f"{method} {path}")
            public_path = "/api/platform" + path
            self.assertIn(
                method, gateway["paths"].get(public_path, {}),
                f"{method} {public_path}",
            )
            self.assertTrue(
                platform["paths"][path][method].get("responses"),
                f"owner responses {method} {path}",
            )
            self.assertTrue(
                gateway["paths"][public_path][method].get("responses"),
                f"gateway responses {method} {public_path}",
            )

    def test_device_routes_use_only_the_exact_actorless_identity_contract(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        EXPORTER["validate_platform_device_identity"](platform)
        EXPORTER["validate_platform_device_identity"](gateway, gateway=True)
        self.assertEqual(len(DEVICE_IDENTITY_OPERATIONS), 10)
        owner_schemes = platform["components"]["securitySchemes"]
        public_schemes = gateway["components"]["securitySchemes"]
        self.assertEqual(owner_schemes["DeviceTenant"]["name"], "X-DWP-Tenant-ID")
        self.assertEqual(
            owner_schemes["DeviceCredential"]["name"],
            "X-DWP-Device-Credential",
        )
        self.assertEqual(public_schemes["platform_DeviceTenant"]["name"], "X-Tenant-ID")
        self.assertEqual(
            public_schemes["platform_DeviceCredential"]["name"],
            "X-Device-Credential",
        )

        for method, path in DEVICE_IDENTITY_OPERATIONS:
            owner = platform["paths"][path][method]
            public = gateway["paths"]["/api/platform" + path][method]
            self.assertEqual(owner["x-dwp-identity-plane"], "DEVICE")
            self.assertEqual(public["x-dwp-identity-plane"], "DEVICE")
            self.assertEqual(
                owner["security"],
                [{"DeviceCredential": [], "DeviceTenant": []}],
            )
            self.assertEqual(
                public["security"],
                [{"platform_DeviceCredential": [], "platform_DeviceTenant": []}],
            )
            public_parameters = {
                (parameter.get("in"), parameter.get("name"))
                for parameter in public.get("parameters", [])
            }
            self.assertNotIn(("query", "contextScopeKey"), public_parameters)
            self.assertNotIn(
                ("header", "X-DWP-Expected-Decision-Revision"),
                public_parameters,
            )

        missing = copy.deepcopy(platform)
        missing["paths"].pop("/v1/device/workplace/devices:register")
        with self.assertRaisesRegex(RuntimeError, "not exact"):
            EXPORTER["validate_platform_device_identity"](missing)

        human_governed = copy.deepcopy(gateway)
        human_governed["paths"][
            "/api/platform/v1/workplace/kiosk/session"
        ]["get"].setdefault("parameters", []).append({
            "in": "query", "name": "contextScopeKey", "required": False
        })
        with self.assertRaisesRegex(RuntimeError, "human PRODUCT governance"):
            EXPORTER["validate_platform_device_identity"](
                human_governed, gateway=True
            )

    def test_retriable_workplace_mutations_publish_exact_command_headers(self) -> None:
        documents = (
            (
                "owner",
                json.loads(
                    (ROOT / "contracts/openapi/platform.json").read_text(
                        encoding="utf-8"
                    )
                ),
                "",
            ),
            (
                "gateway",
                json.loads(
                    (ROOT / "contracts/openapi/gateway-public.json").read_text(
                        encoding="utf-8"
                    )
                ),
                "/api/platform",
            ),
        )
        self.assertEqual(len(IDEMPOTENT_WORKPLACE_MUTATIONS), 10)
        for document_name, document, prefix in documents:
            for method, path in IDEMPOTENT_WORKPLACE_MUTATIONS:
                operation = document["paths"][prefix + path][method]
                headers = {
                    parameter["name"].lower(): parameter
                    for parameter in operation.get("parameters", [])
                    if parameter.get("in") == "header"
                }
                self.assertTrue(
                    headers["idempotency-key"]["required"],
                    f"{document_name} {method} {path}",
                )
                self.assertFalse(
                    headers["x-correlation-id"]["required"],
                    f"{document_name} {method} {path}",
                )

    def test_approval_and_gateway_snapshots_publish_exact_signature_contract(self) -> None:
        owner = json.loads(
            (ROOT / "contracts/openapi/approval.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        EXPORTER["validate_approval_signature_operations"](owner)
        EXPORTER["validate_approval_signature_operations"](gateway, gateway=True)
        self.assertEqual(len(EXPORTER["APPROVAL_SIGNATURE_FEATURE_OPERATIONS"]), 27)

    def test_signature_contract_validation_rejects_missing_or_extra_routes(self) -> None:
        expected = EXPORTER["APPROVAL_SIGNATURE_FEATURE_OPERATIONS"] | {
            EXPORTER["APPROVAL_SIGNATURE_LEGACY_OPERATION"]
        }
        document = {
            "paths": {
                path: {method: {}}
                for method, path in expected
            }
        }
        EXPORTER["validate_approval_signature_operations"](document)

        missing = copy.deepcopy(document)
        missing["paths"].pop("/v1/requests/{requestId}/signature-context")
        with self.assertRaisesRegex(RuntimeError, "missing"):
            EXPORTER["validate_approval_signature_operations"](missing)

        unexpected = copy.deepcopy(document)
        unexpected["paths"]["/v1/signature-requests/{signatureRequestId}/retry"] = {
            "post": {}
        }
        with self.assertRaisesRegex(RuntimeError, "unexpected"):
            EXPORTER["validate_approval_signature_operations"](unexpected)

    def test_gateway_openapi_projection_consumes_latest_v42_registry(self) -> None:
        registry_path = EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"]
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        dwaion_routes = [
            route
            for route in registry["routes"]
            if route["subject"].get("productKey") == "dwaion"
        ]

        self.assertEqual(EXPORTER["PRODUCT_AUTHORIZATION_VERSION"], 42)
        self.assertEqual(registry["version"], 42)
        self.assertEqual(EXPORTER["HRM_EMPLOYEE_CHANGE_AUTHORIZATION_VERSION"], 38)
        self.assertTrue(str(
            EXPORTER["HRM_EMPLOYEE_CHANGE_AUTHORIZATION_REGISTRY"]
        ).endswith("product-surfaces-v1.bundle-v38.json"))
        void_route = next(
            route for route in registry["routes"]
            if route["routeContractKey"]
            == "route.hcm.operations.time-clock-event-void.action"
        )
        self.assertEqual(
            ["full-management"],
            [profile["profileKey"] for profile in void_route["accessProfiles"]],
        )
        personal_time = next(
            capability for capability in registry["capabilities"]
            if capability["contractKey"] == "hcm.personal.time.submit"
        )
        self.assertNotIn(void_route["routeContractKey"], personal_time["routeContractKeys"])
        self.assertEqual(len(dwaion_routes), 160)
        self.assertEqual(
            sum(route["routeKind"] == "ACTION" for route in dwaion_routes), 93
        )
        approval_routes = [route for route in registry["routes"]
                           if route["subject"].get("productKey") == "approvals"]
        self.assertEqual(len(approval_routes), 218)
        self.assertEqual(sum(route["routeKind"] == "ACTION" for route in approval_routes), 113)

        previous = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v30.json")
            .read_text(encoding="utf-8")
        )
        previous_keys = {route["routeContractKey"] for route in previous["routes"]}
        added = [
            (route, binding)
            for route in approval_routes
            if route["routeContractKey"] not in previous_keys
            for binding in route["gatewayApiBindings"]
        ]
        self.assertEqual(len(added), 38)
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        for route, binding in added:
            resolved = EXPORTER["governed_operation"](
                gateway, binding["path"], binding["method"].lower()
            )
            self.assertIsNotNone(resolved, binding)
            _, operation = resolved
            parameters = {
                (parameter.get("in"), parameter.get("name"))
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            self.assertIn(("query", "contextScopeKey"), parameters)
            if route["routeKind"] == "ACTION":
                self.assertIn(
                    ("header", "X-DWP-Expected-Decision-Revision"), parameters
                )

    def test_compensation_planning_exports_all_v39_bindings_and_preserves_employee_v38(self) -> None:
        registry = json.loads(
            EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"].read_text(encoding="utf-8")
        )
        routes = [
            route for route in registry["routes"]
            if route["routeContractKey"]
            in EXPORTER["COMPENSATION_PLANNING_ROUTE_KEYS"]
        ]
        people = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        self.assertEqual(12, len(routes))
        EXPORTER["validate_compensation_planning_contract"](people, gateway)
        for route in routes:
            binding = route["gatewayApiBindings"][0]
            operation = gateway["paths"][binding["path"]][binding["method"].lower()]
            parameters = {
                (parameter.get("in"), parameter.get("name"))
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            self.assertIn(("query", "contextScopeKey"), parameters)
            if route["routeKind"] == "ACTION":
                self.assertIn(
                    ("header", "X-DWP-Expected-Decision-Revision"), parameters
                )

        employee = gateway["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["x-dwp-product-authorization-v38"]
        self.assertEqual(38, employee["registryVersion"])

    def test_payroll_run_v41_exports_exact_peps_and_proof_boundary(self) -> None:
        registry = json.loads(
            EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"].read_text(encoding="utf-8")
        )
        routes = {
            route["routeContractKey"]: route for route in registry["routes"]
            if route["routeContractKey"] in EXPORTER["PAYROLL_RUN_ROUTE_KEYS"]
        }
        payroll = json.loads(
            (ROOT / "contracts/openapi/payroll.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        self.assertEqual(EXPORTER["PAYROLL_RUN_ROUTE_KEYS"], set(routes))
        EXPORTER["validate_payroll_run_contract"](payroll, gateway)
        proof_routes = {
            "route.hcm.operations.payroll-run-approve.action",
            "route.hcm.operations.payroll-run-finalize.action",
        }
        for route_key, route in routes.items():
            binding = route["gatewayApiBindings"][0]
            operation = gateway["paths"][binding["path"]][binding["method"].lower()]
            parameters = {
                (parameter.get("in"), parameter.get("name")): parameter
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            self.assertIn(("query", "contextScopeKey"), parameters)
            if route["routeKind"] == "ACTION":
                self.assertIn(
                    ("header", "X-DWP-Expected-Decision-Revision"), parameters
                )
            if route_key in proof_routes:
                for name in (
                    "X-DWP-Step-Up-Challenge",
                    "X-DWP-Expected-Decision-Revision",
                    "X-DWP-Expected-Object-Version",
                ):
                    self.assertTrue(parameters[("header", name)]["required"])
            else:
                self.assertNotIn(
                    ("header", "X-DWP-Step-Up-Challenge"), parameters
                )

    def test_wave5_v42_exports_exact_sys_per_tim_boundaries(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        people = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        time = json.loads(
            (ROOT / "contracts/openapi/time.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        EXPORTER["validate_wave5_hris_control_contract"](
            platform, people, time, gateway
        )

        registry = json.loads(
            EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"].read_text(encoding="utf-8")
        )
        routes = {
            route["routeContractKey"]: route for route in registry["routes"]
            if route["routeContractKey"]
            in EXPORTER["WAVE5_HRIS_CONTROL_ROUTE_KEYS"]
        }
        self.assertEqual(11, len(routes))
        for route in routes.values():
            binding = route["gatewayApiBindings"][0]
            operation = gateway["paths"][binding["path"]][binding["method"].lower()]
            parameters = {
                (str(value.get("in", "")).lower(), str(value.get("name", "")).lower())
                for value in operation.get("parameters", [])
            }
            self.assertIn(("query", "contextscopekey"), parameters)
            self.assertNotIn(("header", "x-purpose-code"), parameters)
            self.assertNotIn(("header", "x-dwp-population-kind"), parameters)
            self.assertNotIn(("header", "x-dwp-step-up-challenge"), parameters)

        wrong_one_of = copy.deepcopy(platform)
        wrong_one_of["paths"]["/v1/admin/audit-control/events"]["get"][
            "responses"
        ]["200"]["content"]["*/*"]["schema"]["oneOf"].pop()
        with self.assertRaisesRegex(RuntimeError, "conditional oneOf drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                wrong_one_of, people, time, gateway
            )

        wrong_population = copy.deepcopy(people)
        review_parameters = wrong_population["paths"][
            "/v1/hris/performance/reviews/{reviewId}"
        ]["get"]["parameters"]
        next(parameter for parameter in review_parameters
             if parameter.get("name") == "X-DWP-Population-Kind")["schema"] = {
            "enum": ["ASSIGNED_REVIEW_POPULATION", "SELF"],
            "type": "string",
        }
        with self.assertRaisesRegex(RuntimeError, "trusted owner evidence drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, wrong_population, time, gateway
            )

        leaked_step_up = copy.deepcopy(gateway)
        leaked_step_up["paths"][
            "/api/time/v1/timecards/{timecardId}/decisions"
        ]["post"]["parameters"].append({
            "in": "header", "name": "X-DWP-Step-Up-Challenge",
            "required": True, "schema": {"type": "string"},
        })
        with self.assertRaisesRegex(RuntimeError, "unexpected step-up proof"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, time, leaked_step_up
            )

        wrong_people_schema = copy.deepcopy(people)
        wrong_people_schema["components"]["schemas"]["AssignedReviewV1"][
            "properties"
        ]["stageKey"]["maxLength"] = 81
        with self.assertRaisesRegex(RuntimeError, "generated people schema"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, wrong_people_schema, time, gateway
            )

        wrong_time_state = copy.deepcopy(time)
        wrong_time_state["components"]["schemas"]["TimecardV1"]["properties"][
            "state"
        ]["enum"].remove("LOCKED")
        with self.assertRaisesRegex(RuntimeError, "generated time schema"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, wrong_time_state, gateway
            )

        reordered_time_state = copy.deepcopy(time)
        reordered_time_state["components"]["schemas"]["TimecardV1"][
            "properties"
        ]["state"]["enum"][-2:] = ["SUPERSEDED", "CLOSED"]
        with self.assertRaisesRegex(RuntimeError, "generated time schema"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, reordered_time_state, gateway
            )

        missing_owner_etag = copy.deepcopy(people)
        del missing_owner_etag["paths"][
            "/v1/hris/performance/reviews/{reviewId}"
        ]["get"]["responses"]["200"]["headers"]["ETag"]
        with self.assertRaisesRegex(RuntimeError, "ETag response drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, missing_owner_etag, time, gateway
            )

        missing_gateway_etag = copy.deepcopy(gateway)
        del missing_gateway_etag["paths"][
            "/api/time/v1/timecards/{timecardId}"
        ]["get"]["responses"]["200"]["headers"]["ETag"]
        with self.assertRaisesRegex(RuntimeError, "ETag response drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, time, missing_gateway_etag
            )

        wrong_patch_media = copy.deepcopy(people)
        patch_content = wrong_patch_media["paths"][
            "/v1/hris/performance/reviews/{reviewId}"
        ]["patch"]["requestBody"]["content"]
        patch_content["application/json"] = patch_content.pop(
            "application/merge-patch+json"
        )
        with self.assertRaisesRegex(RuntimeError, "request schema drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, wrong_patch_media, time, gateway
            )

        wrong_success_response = copy.deepcopy(time)
        wrong_success_response["paths"][
            "/v1/timecards/{timecardId}/corrections"
        ]["post"]["responses"]["202"]["content"]["application/json"][
            "schema"
        ] = {"$ref": "#/components/schemas/TimecardV1"}
        with self.assertRaisesRegex(RuntimeError, "success response drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, wrong_success_response, gateway
            )

        wrong_error_response = copy.deepcopy(gateway)
        wrong_error_response["paths"][
            "/api/people/v1/hris/performance/reviews/{reviewId}/submit"
        ]["post"]["responses"]["422"]["content"]["application/json"] = (
            wrong_error_response["paths"][
                "/api/people/v1/hris/performance/reviews/{reviewId}/submit"
            ]["post"]["responses"]["422"]["content"].pop(
                "application/problem+json"
            )
        )
        with self.assertRaisesRegex(RuntimeError, "error response drift"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, time, wrong_error_response
            )

        wrong_iaq_action = copy.deepcopy(gateway)
        wrong_iaq_action["components"]["schemas"][
            "people_Iaq022PerformanceMultiraterSurveyPageV1"
        ]["properties"]["availableActions"]["items"]["enum"][0] = (
            "hris.action.performance.create-multirater-cycle"
        )
        with self.assertRaisesRegex(RuntimeError, "generated people schema"):
            EXPORTER["validate_wave5_hris_control_contract"](
                platform, people, time, wrong_iaq_action
            )

    def test_compensation_planning_validator_rejects_payload_graph_drift(self) -> None:
        people = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        EXPORTER["validate_compensation_planning_contract"](people, gateway)

        wrong_request = copy.deepcopy(people)
        wrong_request["paths"][
            "/v1/hris/performance/compensation-planning/cycles"
        ]["post"]["requestBody"]["content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/PerformanceCycleCreateRequest"
        }
        with self.assertRaisesRegex(RuntimeError, "payload schema drift"):
            EXPORTER["validate_compensation_planning_contract"](
                wrong_request, gateway
            )

        fake_query = copy.deepcopy(people)
        fake_query["paths"][
            "/v1/hris/performance/compensation-planning/cycles"
        ]["get"].setdefault("parameters", []).append({
            "in": "query", "name": "query", "schema": {"type": "object"}
        })
        with self.assertRaisesRegex(RuntimeError, "capture-only query parameter"):
            EXPORTER["validate_compensation_planning_contract"](fake_query, gateway)

        fake_cancel_body = copy.deepcopy(people)
        fake_cancel_body["paths"][
            "/v1/hris/performance/compensation-planning/cycles/{cycleId}/cancel"
        ]["post"]["requestBody"] = {
            "content": {"application/json": {"schema": {"type": "object"}}}
        }
        with self.assertRaisesRegex(RuntimeError, "bodyless operation"):
            EXPORTER["validate_compensation_planning_contract"](
                fake_cancel_body, gateway
            )

        incompatible_rollout = copy.deepcopy(people)
        incompatible_rollout["components"]["schemas"][
            "ModernCompplanCycleProjectionV5"
        ].setdefault("required", []).append("budgetAggregates")
        with self.assertRaisesRegex(RuntimeError, "rolling compatibility drift"):
            EXPORTER["validate_compensation_planning_contract"](
                incompatible_rollout, gateway
            )

        missing_required_nullable = copy.deepcopy(people)
        missing_required_nullable["components"]["schemas"][
            "ModernCompplanCycleProjectionV5"
        ]["required"].remove("effectiveTo")
        with self.assertRaisesRegex(RuntimeError, "required field drift"):
            EXPORTER["validate_compensation_planning_contract"](
                missing_required_nullable, gateway
            )

        non_nullable_date = copy.deepcopy(people)
        non_nullable_date["components"]["schemas"][
            "ModernCompplanCycleProjectionV5"
        ]["properties"]["effectiveTo"]["type"] = "string"
        with self.assertRaisesRegex(RuntimeError, "nullable field drift"):
            EXPORTER["validate_compensation_planning_contract"](
                non_nullable_date, gateway
            )

        unexpected_property = copy.deepcopy(people)
        unexpected_property["components"]["schemas"][
            "ModernCompplanCycleProjectionV5"
        ]["properties"]["unexpected"] = {"type": "string"}
        with self.assertRaisesRegex(RuntimeError, "property set drift"):
            EXPORTER["validate_compensation_planning_contract"](
                unexpected_property, gateway
            )

        wrong_nested_projection = copy.deepcopy(people)
        wrong_nested_projection["components"]["schemas"][
            "ModernCompplanCyclesQueryResponseV5"
        ]["properties"]["items"]["items"] = {
            "$ref": "#/components/schemas/PerformanceCycle"
        }
        with self.assertRaisesRegex(RuntimeError, "payload schema drift"):
            EXPORTER["validate_compensation_planning_contract"](
                wrong_nested_projection, gateway
            )

        leaked_internal_refetch = copy.deepcopy(people)
        leaked_internal_refetch["paths"][
            "/internal/hris-performance/v1/approved-compensation-plan-snapshots/{snapshotId}"
        ] = {"get": {"operationId": "leakedWorkloadOnlySnapshotRefetch"}}
        with self.assertRaisesRegex(RuntimeError, "workload-only refetch leaked"):
            EXPORTER["validate_compensation_planning_contract"](
                leaked_internal_refetch, gateway
            )

    def test_gateway_projects_full_agent_contract_and_v33_governance(self) -> None:
        registry = json.loads(
            EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"].read_text(encoding="utf-8")
        )
        previous = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v28.json")
            .read_text(encoding="utf-8")
        )
        agent = json.loads(
            (ROOT / "contracts/openapi/agent-public.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        expected_agent_operations = {
            (method, f"/api/agent{path}")
            for path, path_item in agent["paths"].items()
            if path.startswith("/v1/")
            for method in path_item
            if method in EXPORTER["HTTP_METHODS"]
        }
        projected_agent_operations = {
            (method, path)
            for path, path_item in gateway["paths"].items()
            if path.startswith("/api/agent/v1/")
            for method in path_item
            if method in EXPORTER["HTTP_METHODS"]
        }
        self.assertEqual(expected_agent_operations, projected_agent_operations)

        previous_keys = {route["routeContractKey"] for route in previous["routes"]}
        latest_agent_bindings = [
            (route, binding)
            for route in registry["routes"]
            if route["routeContractKey"] not in previous_keys
            for binding in route["gatewayApiBindings"]
            if binding["path"].startswith("/api/agent/")
        ]
        self.assertEqual(len(latest_agent_bindings), 18)
        for route, binding in latest_agent_bindings:
            resolved = EXPORTER["governed_operation"](
                gateway, binding["path"], binding["method"].lower()
            )
            self.assertIsNotNone(resolved)
            _, operation = resolved
            parameters = {
                (parameter.get("in"), parameter.get("name"))
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            self.assertIn(("query", "contextScopeKey"), parameters)
            if route["routeKind"] == "ACTION" and not route.get(
                "sideEffectFree", False
            ):
                self.assertIn(
                    ("header", "X-DWP-Expected-Decision-Revision"),
                    parameters,
                )

    def test_gateway_exports_exact_ai_control_methods_and_action_revision_headers(self) -> None:
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        expected = {
            "/api/agent/v1/admin/ai-control": "get",
            "/api/agent/v1/admin/ai-control/bootstrap": "post",
            "/api/agent/v1/admin/ai-control/policy": "put",
            "/api/agent/v1/admin/ai-control/emergency": "post",
        }
        for path, method in expected.items():
            self.assertEqual({method}, set(gateway["paths"][path]))
            headers = {
                parameter.get("name"): parameter
                for parameter in gateway["paths"][path][method].get("parameters", [])
                if parameter.get("in") == "header"
            }
            if method == "get":
                self.assertNotIn("X-DWP-Expected-Decision-Revision", headers)
            else:
                revision = headers["X-DWP-Expected-Decision-Revision"]
                self.assertEqual(
                    {"enforcement": "FAIL_CLOSED", "rolloutStates": ["110", "111"]},
                    revision["x-dwp-conditional-required"],
                )

    def test_wave1_hris_owner_inventory_and_runtime_modes_are_exact(self) -> None:
        expected = {
            "auth": {
                ("get", "/auth/hris/product-access/snapshot", "snapshot"),
            },
            "platform": {
                ("get", "/v1/hris/configuration/projection", "projection"),
                ("get", "/v1/hris/workbench/settings-reference", "workbench"),
                ("get", "/v1/admin/audit-control/events", "page"),
                ("get", "/v1/admin/audit-control/events/{eventId}", "detail"),
                (
                    "get",
                    "/v1/admin/hris/navigation-policies/workspace",
                    "workspace",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/drafts",
                    "createDraft",
                ),
                (
                    "put",
                    "/v1/admin/hris/navigation-policies/drafts/{revisionId}",
                    "updateDraft",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/drafts/{revisionId}/validate",
                    "validate",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/drafts/{revisionId}/submit",
                    "submit",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/revisions/{revisionId}/approve",
                    "approve",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/revisions/{revisionId}/publish",
                    "publish",
                ),
                (
                    "post",
                    "/v1/admin/hris/navigation-policies/revisions/{revisionId}/restore",
                    "restore",
                ),
                ("get", "/v1/hris/navigation/projection", "projection"),
                ("get", "/v1/admin/hris/configurations", "configurations"),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions",
                    "createVersion",
                ),
                (
                    "get",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}",
                    "version",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/validate",
                    "validate",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/simulate",
                    "simulate",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/submit",
                    "submit",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/approve",
                    "approve",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/schedule",
                    "schedule",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/publish",
                    "publish",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/rollback",
                    "rollback",
                ),
                (
                    "post",
                    "/v1/admin/hris/configurations/{configId}/versions/{version}/retire",
                    "retire",
                ),
            },
            "people": {
                ("get", "/v1/hr/home", "people360"),
                ("get", "/v1/hr/team", "teamPeople360"),
                (
                    "post",
                    "/internal/hris-people/v1/employee-change-requests/{requestPublicId}/approval-decisions",
                    "record",
                ),
                (
                    "post",
                    "/v1/hris/employee-change-requests",
                    "create",
                ),
                (
                    "post",
                    "/v1/hris/employee-change-requests/{requestPublicId}/apply",
                    "apply",
                ),
                (
                    "post",
                    "/v1/hris/employee-change-requests/{requestPublicId}/submit",
                    "submit",
                ),
                (
                    "get",
                    "/v1/hris/workbench/operations/employee/change/requests",
                    "workbench",
                ),
                ("get", "/v1/workforce/people", "searchPeople360"),
                ("get", "/v1/workforce/people/{publicId}", "getPeople360"),
                ("get", "/v1/hris/performance/cycles", "cycles"),
                ("post", "/v1/hris/performance/cycles", "create"),
                ("get", "/v1/hris/performance/cycles/{cycleId}", "cycle"),
                ("patch", "/v1/hris/performance/cycles/{cycleId}", "update"),
                ("post", "/v1/hris/performance/cycles/{cycleId}/validate", "validate"),
                ("post", "/v1/hris/performance/cycles/{cycleId}/population-previews", "preview"),
                ("post", "/v1/hris/performance/cycles/{cycleId}/population-freezes", "freeze"),
                ("post", "/v1/hris/performance/cycles/{cycleId}/publish", "publish"),
                ("get", "/v1/hris/performance/command-receipts/{receiptId}", "receipt"),
                ("get", "/v1/hris/performance/workbench/review-operations", "query"),
                ("get", "/v1/hris/performance/reviews/{reviewId}", "review"),
                ("patch", "/v1/hris/performance/reviews/{reviewId}", "updateDraft"),
                ("post", "/v1/hris/performance/reviews/{reviewId}/submit", "submit"),
                ("get", "/v1/hris/performance/workbench/multirater-surveys", "workbench"),
                ("get", "/v1/workforce/assignments/{assignmentId}", "assignment"),
                ("get", "/v1/workforce/assignments/{assignmentId}/timeline", "timeline"),
                ("get", "/v1/workforce/assignment-proposals/{proposalId}", "proposal"),
                ("post", "/v1/workforce/assignment-proposals", "create"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/validate", "validate"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/submit", "submit"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/cancel", "cancel"),
            },
            "payroll": {
                ("get", "/v1/hris/payroll/foundation/configurations", "configurations"),
                ("post", "/v1/hris/payroll/foundation/configurations", "create"),
                ("get", "/v1/hris/payroll/foundation/configurations/{configurationId}", "configuration"),
                ("put", "/v1/hris/payroll/foundation/configurations/{configurationId}", "update"),
                ("get", "/v1/hris/payroll/foundation/configurations/{configurationId}/versions", "versions"),
                ("post", "/v1/hris/payroll/foundation/configurations/{configurationId}/simulations", "simulate"),
                ("post", "/v1/hris/payroll/foundation/configurations/{configurationId}/publish", "publish"),
                ("post", "/v1/hris/payroll/foundation/configurations/{configurationId}/reversals", "reverse"),
                ("get", "/v1/hris/payroll/foundation/receipts/{commandId}", "receipt"),
                ("post", "/v1/hris/payroll/foundation/receipts/{commandId}/reconcile", "reconcile"),
                ("post", "/v1/runs/{runId}/attempts", "calculate"),
                ("get", "/v1/runs/{runId}", "run"),
                ("post", "/v1/runs/{runId}/approvals", "approve"),
                ("post", "/v1/runs/{runId}/finalize", "finalize"),
                ("get", "/v1/command-receipts/{receiptId}", "receipt"),
            },
            "time": {
                ("post", "/v1/clock-events", "record"),
                ("post", "/v1/clock-events/{eventId}/voids", "voidEvent"),
                ("get", "/v1/hris/work-plans", "list"),
                ("post", "/v1/hris/work-plans/drafts", "createDraft"),
                ("post", "/v1/hris/work-plans/{workPlanId}/simulations", "simulate"),
                ("post", "/v1/hris/work-plans/{workPlanId}/actions/{action}", "transition"),
                ("get", "/v1/hris/work-plan-receipts/{receiptId}", "receipt"),
                ("get", "/v1/hris/workbench/clock-ingestion", "workbench"),
                ("get", "/v1/hris/workbench/time-exceptions", "exceptions"),
                ("get", "/v1/timecards/{timecardId}", "timecard"),
                ("post", "/v1/timecards/{timecardId}/corrections", "correct"),
                ("post", "/v1/timecards/{timecardId}/submit", "submit"),
                ("post", "/v1/timecards/{timecardId}/decisions", "decide"),
                ("get", "/v1/command-receipts/{receiptId}", "receipt"),
            },
        }
        overlays = EXPORTER["load_design_time_overlays"]()
        self.assertEqual(set(overlays), set(expected))
        actual = {
            service: {
                (method, path, operation["x-dwp-controller-method"])
                for path, path_item in overlay["paths"].items()
                for method, operation in path_item.items()
            }
            for service, overlay in overlays.items()
        }
        self.assertEqual(actual, expected)
        self.assertEqual(
            {
                (method, path)
                for path, path_item in overlays["people"]["paths"].items()
                for method, operation in path_item.items()
                if operation.get("x-dwp-runtime-default") == "ON"
            },
            {
                ("get", "/v1/workforce/assignments/{assignmentId}"),
                ("get", "/v1/workforce/assignments/{assignmentId}/timeline"),
                ("get", "/v1/workforce/assignment-proposals/{proposalId}"),
                ("post", "/v1/workforce/assignment-proposals"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/validate"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/submit"),
                ("post", "/v1/workforce/assignment-proposals/{proposalId}/cancel"),
            },
        )

        services = {service.name: service for service in EXPORTER["SERVICES"]}
        self.assertEqual(services["time"].port, 8011)
        self.assertEqual(services["payroll"].port, 8012)
        self.assertFalse(services["time"].default_live)
        self.assertFalse(services["payroll"].default_live)
        for name in {"auth", "platform", "people"}:
            self.assertTrue(services[name].default_live)

    def test_wave1_hris_overlays_publish_stable_unique_owner_operations(self) -> None:
        services = {service.name: service for service in EXPORTER["SERVICES"]}
        overlays = EXPORTER["load_design_time_overlays"]()
        patch_variants = {
            ("platform", "/v1/admin/audit-control/events", "get", "page"),
            ("platform", "/v1/admin/audit-control/events/{eventId}", "get", "detail"),
            ("people", "/v1/hr/home", "get", "people360"),
            ("people", "/v1/hr/team", "get", "teamPeople360"),
            ("people", "/v1/workforce/people", "get", "searchPeople360"),
            ("people", "/v1/workforce/people/{publicId}", "get", "getPeople360"),
        }

        for name in overlays:
            document = EXPORTER["load_snapshot"](services[name])
            EXPORTER["validate_unique_operation_ids"](document, name)
            for path, path_item in overlays[name]["paths"].items():
                for method, operation in path_item.items():
                    if operation["x-dwp-overlay-mode"] == "PATCH":
                        variants = document["paths"][path][method][
                            "x-dwp-controller-variants"
                        ]
                        self.assertIn(
                            (
                                name,
                                path,
                                method,
                                operation["x-dwp-controller-method"],
                            ),
                            patch_variants,
                        )
                        self.assertIn(
                            operation["x-dwp-controller-method"],
                            {variant["controllerMethod"] for variant in variants},
                        )
                    else:
                        published = document["paths"][path][method]
                        self.assertEqual(
                            published["operationId"], operation["operationId"]
                        )
                        self.assertEqual(
                            published["x-dwp-controller-method"],
                            operation["x-dwp-controller-method"],
                        )
                        self.assertEqual(
                            published["x-dwp-runtime-default"],
                            operation["x-dwp-runtime-default"],
                        )

        self.assertEqual(len(patch_variants), 6)

    def test_sys_reference_configuration_source_owner_and_gateway_are_exact(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        self.assertEqual(
            len(EXPORTER["SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS"]),
            12,
        )
        EXPORTER["validate_sys_reference_configuration_contract"](
            platform, gateway
        )

        incomplete_gateway = copy.deepcopy(gateway)
        del incomplete_gateway["paths"][
            "/api/platform/v1/hris/workbench/settings-reference"
        ]
        with self.assertRaisesRegex(
            RuntimeError,
            "operation is missing from source, owner, or Gateway",
        ):
            EXPORTER["validate_sys_reference_configuration_contract"](
                platform, incomplete_gateway
            )

        invalid_operation_id = copy.deepcopy(gateway)
        invalid_operation_id["paths"][
            "/api/platform/v1/admin/hris/configurations"
        ]["get"]["operationId"] = "platform_referenceConfigurations"
        with self.assertRaisesRegex(RuntimeError, "operationId drift"):
            EXPORTER["validate_sys_reference_configuration_contract"](
                platform, invalid_operation_id
            )

        invalid_response = copy.deepcopy(gateway)
        invalid_response["paths"][
            "/api/platform/v1/admin/hris/configurations/{configId}/versions"
        ]["post"]["responses"] = {"200": {"description": "OK"}}
        with self.assertRaisesRegex(RuntimeError, "response drift"):
            EXPORTER["validate_sys_reference_configuration_contract"](
                platform, invalid_response
            )

        anonymous_operation = copy.deepcopy(gateway)
        anonymous_operation["paths"][
            "/api/platform/v1/hris/workbench/settings-reference"
        ]["get"]["security"] = []
        with self.assertRaisesRegex(RuntimeError, "anonymous security override"):
            EXPORTER["validate_sys_reference_configuration_contract"](
                platform, anonymous_operation
            )

    def test_hrm_employee_change_source_owner_gateway_and_callback_are_exact(self) -> None:
        people = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        self.assertEqual(len(EXPORTER["HRM_EMPLOYEE_CHANGE_ROUTE_KEYS"]), 4)
        EXPORTER["validate_hrm_employee_change_contract"](people, gateway)
        self.assertNotIn(
            "/internal/hris-people/v1/employee-change-requests/"
            "{requestPublicId}/approval-decisions",
            gateway["paths"],
        )
        self.assertFalse(
            any(path.startswith("/api/hris/v1") for path in gateway["paths"])
        )

        people_schemas = people["components"]["schemas"]
        gateway_schemas = gateway["components"]["schemas"]
        receipt = people_schemas["CommandReceiptV1"]
        self.assertEqual(
            set(receipt["required"]),
            {
                "receiptId",
                "commandType",
                "originatingAction",
                "subjectPrincipalPublicId",
                "populationScopeDigest",
                "fieldPolicyRevision",
                "purposeCode",
                "authorizationRevision",
                "status",
                "correlationId",
                "requestDigest",
                "createdAt",
            },
        )
        self.assertEqual(
            receipt["properties"]["status"]["enum"],
            [
                "ACCEPTED",
                "RUNNING",
                "SUCCEEDED",
                "REJECTED",
                "FAILED",
                "RESULT_UNKNOWN",
            ],
        )
        self.assertIn("people_CommandReceiptV1", gateway_schemas)

        create = gateway["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]
        self.assertEqual(
            create["requestBody"]["content"]["application/json"]["schema"],
            {"$ref": "#/components/schemas/people_EmployeeChangeRequestCreateV1"},
        )
        self.assertEqual(
            create["responses"]["202"]["content"]["application/json"]["schema"],
            {"$ref": "#/components/schemas/people_CommandReceiptV1"},
        )
        self.assertEqual(
            set(create["responses"]),
            {"202"} | EXPORTER["HRM_COMMAND_ERROR_STATUSES"],
        )
        self.assertEqual(
            create["responses"]["202"]["headers"],
            EXPORTER["HRM_RESPONSE_HEADER_CONTRACTS"],
        )

        workbench = gateway["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]["get"]
        workbench_parameters = {
            parameter["name"]: parameter for parameter in workbench["parameters"]
        }
        self.assertNotIn("effectiveOn", workbench_parameters)
        self.assertEqual(
            set(workbench_parameters),
            {
                "limit",
                "filter",
                "sort",
                "locale",
                "timeZone",
                "correlationId",
                "asOf",
                "cursor",
                "contextScopeKey",
            },
        )
        self.assertEqual(workbench_parameters["filter"]["style"], "deepObject")
        self.assertEqual(
            workbench_parameters["sort"]["schema"]["items"]["enum"],
            [
                "changeRequestSummary:ASC",
                "approvalStatus:ASC",
                "employment:ASC",
                "changeRequestSummary:DESC",
                "approvalStatus:DESC",
                "employment:DESC",
                "itemPublicId:ASC",
                "itemPublicId:DESC",
            ],
        )
        projection = people_schemas["Iaq009EmployeeChangeRequestProjectionV1"]
        query_state = projection["properties"]["queryState"]
        self.assertEqual(
            query_state["properties"]["state"]["enum"],
            ["COMPLETE", "EMPTY", "PARTIAL", "UNAVAILABLE"],
        )
        item = projection["properties"]["items"]["items"]
        self.assertEqual(
            set(item["properties"]["fieldGroups"]["properties"]),
            {"CHANGE_REQUEST_SUMMARY", "APPROVAL_STATUS", "EMPLOYMENT"},
        )
        actions = item["properties"]["availableActions"]["items"]["oneOf"]
        self.assertEqual(
            {
                action["properties"]["actionKey"]["const"]:
                action["properties"]["hrefTemplate"]["const"]
                for action in actions
            },
            {
                "hris.action.hrm.create-employee-change-request":
                    "/api/people/v1/hris/employee-change-requests",
                "hris.action.hrm.submit-employee-change-request":
                    "/api/people/v1/hris/employee-change-requests/"
                    "{requestPublicId}/submit",
                "hris.action.hrm.apply-employee-change-request":
                    "/api/people/v1/hris/employee-change-requests/"
                    "{requestPublicId}/apply",
            },
        )

        incomplete_gateway = copy.deepcopy(gateway)
        del incomplete_gateway["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]
        with self.assertRaisesRegex(
            RuntimeError, "Gateway operations are not exact"
        ):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, incomplete_gateway
            )

        invalid_operation_id = copy.deepcopy(gateway)
        invalid_operation_id["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["operationId"] = "people_employeeChangeCreate"
        with self.assertRaisesRegex(RuntimeError, "operationId drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, invalid_operation_id
            )

        missing_pep = copy.deepcopy(gateway)
        del missing_pep["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["x-dwp-product-authorization-v38"]
        with self.assertRaisesRegex(RuntimeError, "v38 PEP authorization linkage drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, missing_pep
            )

        empty_pep = copy.deepcopy(gateway)
        empty_pep["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["x-dwp-product-authorization-v38"] = {}
        with self.assertRaisesRegex(RuntimeError, "v38 PEP authorization linkage drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, empty_pep
            )

        anonymous_operation = copy.deepcopy(gateway)
        anonymous_operation["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["security"] = []
        with self.assertRaisesRegex(RuntimeError, "empty/anonymous security semantics"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, anonymous_operation
            )

        empty_security_requirement = copy.deepcopy(gateway)
        empty_security_requirement["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["security"] = [{}]
        with self.assertRaisesRegex(RuntimeError, "empty/anonymous security semantics"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, empty_security_requirement
            )

        weakened_receipt = copy.deepcopy(gateway)
        weakened_receipt["components"]["schemas"][
            "people_CommandReceiptV1"
        ]["properties"]["status"]["enum"].remove("RESULT_UNKNOWN")
        with self.assertRaisesRegex(RuntimeError, "canonical schema drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, weakened_receipt
            )

        bodyless_success = copy.deepcopy(gateway)
        del bodyless_success["paths"][
            "/api/people/v1/hris/employee-change-requests"
        ]["post"]["responses"]["202"]["content"]
        with self.assertRaisesRegex(RuntimeError, "response schema/header drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, bodyless_success
            )

        for header_name in EXPORTER["HRM_RESPONSE_HEADER_CONTRACTS"]:
            weakened_header = copy.deepcopy(gateway)
            weakened_header["paths"][
                "/api/people/v1/hris/employee-change-requests"
            ]["post"]["responses"]["202"]["headers"][header_name]["schema"] = {}
            with self.subTest(response_header=header_name), self.assertRaisesRegex(
                RuntimeError, "response schema/header drift"
            ):
                EXPORTER["validate_hrm_employee_change_contract"](
                    people, weakened_header
                )

        weakened_sort = copy.deepcopy(gateway)
        weakened_sort["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]["get"]["parameters"][2]["schema"]["items"]["enum"].pop()
        with self.assertRaisesRegex(RuntimeError, "IAQ-009 query schema drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, weakened_sort
            )

        mis_cased_query = copy.deepcopy(gateway)
        mis_cased_parameters = mis_cased_query["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]["get"]["parameters"]
        next(
            parameter
            for parameter in mis_cased_parameters
            if parameter.get("name") == "limit"
        )["name"] = "LIMIT"
        with self.assertRaisesRegex(RuntimeError, "query parameter drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, mis_cased_query
            )

        weakened_sort_serialization = copy.deepcopy(gateway)
        weakened_sort_parameters = weakened_sort_serialization["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]["get"]["parameters"]
        next(
            parameter
            for parameter in weakened_sort_parameters
            if parameter.get("name") == "sort"
        )["explode"] = False
        with self.assertRaisesRegex(RuntimeError, "query schema drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, weakened_sort_serialization
            )

        duplicate_sort = copy.deepcopy(gateway)
        duplicate_sort_parameters = duplicate_sort["paths"][
            "/api/people/v1/hris/workbench/operations/employee/change/requests"
        ]["get"]["parameters"]
        duplicate_sort_parameters.append(
            copy.deepcopy(next(
                parameter
                for parameter in duplicate_sort_parameters
                if parameter.get("name") == "sort"
            ))
        )
        with self.assertRaisesRegex(RuntimeError, "query parameter drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, duplicate_sort
            )

        missing_apply_proof = copy.deepcopy(gateway)
        apply_operation = missing_apply_proof["paths"][
            "/api/people/v1/hris/employee-change-requests/{requestPublicId}/apply"
        ]["post"]
        apply_operation["parameters"] = [
            parameter
            for parameter in apply_operation["parameters"]
            if parameter.get("name") != "X-DWP-Expected-Object-Version"
        ]
        with self.assertRaisesRegex(RuntimeError, "step-up proof drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, missing_apply_proof
            )

        for proof_header_name in (
            "X-DWP-Step-Up-Challenge",
            "X-DWP-Expected-Decision-Revision",
            "X-DWP-Expected-Object-Version",
        ):
            weakened_apply_proof = copy.deepcopy(gateway)
            proof_parameters = weakened_apply_proof["paths"][
                "/api/people/v1/hris/employee-change-requests/"
                "{requestPublicId}/apply"
            ]["post"]["parameters"]
            next(
                parameter
                for parameter in proof_parameters
                if parameter.get("name") == proof_header_name
            )["schema"] = {}
            with self.subTest(proof_header=proof_header_name), self.assertRaisesRegex(
                RuntimeError, "step-up proof drift"
            ):
                EXPORTER["validate_hrm_employee_change_contract"](
                    people, weakened_apply_proof
                )

        missing_request_public_id = copy.deepcopy(gateway)
        submit_parameters = missing_request_public_id["paths"][
            "/api/people/v1/hris/employee-change-requests/"
            "{requestPublicId}/submit"
        ]["post"]["parameters"]
        missing_request_public_id["paths"][
            "/api/people/v1/hris/employee-change-requests/"
            "{requestPublicId}/submit"
        ]["post"]["parameters"] = [
            parameter
            for parameter in submit_parameters
            if parameter.get("name") != "requestPublicId"
        ]
        with self.assertRaisesRegex(RuntimeError, "requestPublicId path parameter drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                people, missing_request_public_id
            )

        weakened_callback = copy.deepcopy(people)
        callback = weakened_callback["paths"][
            "/internal/hris-people/v1/employee-change-requests/"
            "{requestPublicId}/approval-decisions"
        ]["post"]
        callback["parameters"] = [
            parameter
            for parameter in callback["parameters"]
            if parameter.get("name") != "X-DWP-Workload-Proof"
        ]
        with self.assertRaisesRegex(RuntimeError, "callback security drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                weakened_callback, gateway
            )

        missing_workload_semantics = copy.deepcopy(people)
        del missing_workload_semantics["paths"][
            "/internal/hris-people/v1/employee-change-requests/"
            "{requestPublicId}/approval-decisions"
        ]["post"]["x-dwp-workload-proof-v2"]
        with self.assertRaisesRegex(RuntimeError, "callback security drift"):
            EXPORTER["validate_hrm_employee_change_contract"](
                missing_workload_semantics, gateway
            )

    def test_people360_variant_parameters_remain_conditional(self) -> None:
        services = {service.name: service for service in EXPORTER["SERVICES"]}
        people = EXPORTER["load_snapshot"](services["people"])

        for path in (
                "/v1/hr/home",
                "/v1/hr/team",
                "/v1/workforce/people",
                "/v1/workforce/people/{publicId}",
        ):
            operation = people["paths"][path]["get"]
            parameters = {
                (parameter["in"], parameter["name"]): parameter
                for parameter in operation["parameters"]
            }
            self.assertFalse(parameters[("query", "asOf")]["required"])
            self.assertFalse(parameters[("query", "projection")]["required"])
            self.assertIn("asOf", operation["x-dwp-controller-variants"][0]["requiredParameters"])

        detail = people["paths"]["/v1/workforce/people/{publicId}"]["get"]
        detail_parameters = {
            (parameter["in"], parameter["name"]): parameter
            for parameter in detail["parameters"]
        }
        self.assertTrue(detail_parameters[("path", "publicId")]["required"])

    def test_system_view_parameter_is_published_by_owners_and_gateway(self) -> None:
        expected = {
            "auth": (
                "/auth/hris/product-access/snapshot",
                "/api/auth/hris/product-access/snapshot",
            ),
            "platform": (
                "/v1/hris/configuration/projection",
                "/api/platform/v1/hris/configuration/projection",
            ),
        }
        services = {service.name: service for service in EXPORTER["SERVICES"]}
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        def assert_system_view(operation: dict) -> None:
            parameters = {
                (parameter.get("in"), parameter.get("name")): parameter
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            }
            view = parameters[("query", "view")]
            self.assertFalse(view["required"])
            self.assertEqual(view["schema"], {"enum": ["system"], "type": "string"})

        for service_name, (owner_path, gateway_path) in expected.items():
            overlaid = EXPORTER["load_snapshot"](services[service_name])
            assert_system_view(overlaid["paths"][owner_path]["get"])

            checked_in = json.loads(
                (ROOT / "contracts" / "openapi" / f"{service_name}.json")
                .read_text(encoding="utf-8")
            )
            assert_system_view(checked_in["paths"][owner_path]["get"])
            assert_system_view(gateway["paths"][gateway_path]["get"])

    def test_payroll_high_risk_operations_publish_step_up_proof_header(self) -> None:
        owner = json.loads(
            (ROOT / "contracts/openapi/payroll.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        suffixes = (
            "/v1/hris/payroll/foundation/configurations/{configurationId}/publish",
            "/v1/hris/payroll/foundation/configurations/{configurationId}/reversals",
        )

        for owner_path in suffixes:
            gateway_path = "/api/payroll" + owner_path
            for document, path in ((owner, owner_path), (gateway, gateway_path)):
                parameters = {
                    (parameter.get("in"), parameter.get("name")): parameter
                    for parameter in document["paths"][path]["post"].get(
                        "parameters", []
                    )
                    if isinstance(parameter, dict)
                }
                challenge = parameters[("header", "X-DWP-Step-Up-Challenge")]
                self.assertTrue(challenge["required"])
                self.assertEqual(challenge["schema"]["type"], "string")

    def test_payroll_partial_failure_components_remain_owner_specific(self) -> None:
        owner = json.loads(
            (ROOT / "contracts/openapi/payroll.json").read_text(encoding="utf-8")
        )
        schemas = owner["components"]["schemas"]
        self.assertEqual(
            set(schemas["PartialFailure"]["properties"]),
            {"source", "code"},
        )
        self.assertEqual(
            set(schemas["PayrollConfigurationPartialFailureV1"]["properties"]),
            {"componentKey", "errorCode", "correlationId", "retryable"},
        )
        self.assertEqual(
            schemas["WorkspaceView"]["properties"]["partialFailures"]
            ["items"]["$ref"],
            "#/components/schemas/PartialFailure",
        )
        self.assertEqual(
            schemas["QueryState"]["properties"]["partialFailures"]
            ["items"]["$ref"],
            "#/components/schemas/PayrollConfigurationPartialFailureV1",
        )

    def test_wave2_gateway_never_exposes_client_purpose_evidence(self) -> None:
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        self.assertEqual(len(EXPORTER["WAVE2_TRUSTED_PURPOSE_OPERATIONS"]), 28)
        for path, method in EXPORTER["WAVE2_TRUSTED_PURPOSE_OPERATIONS"]:
            operation = gateway["paths"][path][method]
            headers = {
                str(parameter.get("name", "")).lower()
                for parameter in operation.get("parameters", [])
                if parameter.get("in") == "header"
            }
            self.assertNotIn("x-purpose-code", headers, f"{method.upper()} {path}")

    def test_time_workbench_requires_matching_uuid_correlation_header(self) -> None:
        documents = {
            "time": json.loads(
                (ROOT / "contracts/openapi/time.json").read_text(encoding="utf-8")
            ),
            "gateway": json.loads(
                (ROOT / "contracts/openapi/gateway-public.json").read_text(
                    encoding="utf-8"
                )
            ),
        }
        paths = {
            "time": "/v1/hris/workbench/time-interpretation",
            "gateway": "/api/time/v1/hris/workbench/time-interpretation",
        }
        for name, document in documents.items():
            with self.subTest(document=name):
                operation = document["paths"][paths[name]]["get"]
                parameters = {
                    (parameter["in"], parameter["name"]): parameter
                    for parameter in operation["parameters"]
                }
                header = parameters[("header", "X-Correlation-ID")]
                query = parameters[("query", "correlationId")]
                self.assertIs(header["required"], True)
                self.assertEqual(
                    header["schema"], {"format": "uuid", "type": "string"}
                )
                self.assertIn("exactly match", header["description"])
                self.assertIs(query["required"], True)
                self.assertEqual(
                    query["schema"], {"format": "uuid", "type": "string"}
                )
                self.assertIn("exactly match", query["description"])

    def test_wave1_hris_live_overlay_rejects_controller_method_drift(self) -> None:
        service = next(
            service for service in EXPORTER["SERVICES"] if service.name == "payroll"
        )
        live = {
            "openapi": "3.1.0",
            "info": {"title": "test", "version": "1"},
            "paths": {
                "/v1/hris/payroll/foundation/configurations": {
                    "get": {
                        "operationId": "renamedWithoutContractReview",
                        "responses": {"200": {"description": "OK"}},
                    }
                }
            },
        }
        with self.assertRaisesRegex(RuntimeError, "controller method drift"):
            EXPORTER["apply_design_time_overlay"](service, live)

    def test_time_action_overlay_restores_reviewed_allowable_values(self) -> None:
        service = next(
            service for service in EXPORTER["SERVICES"] if service.name == "time"
        )
        path = "/v1/hris/work-plans/{workPlanId}/actions/{action}"
        reviewed = copy.deepcopy(
            EXPORTER["load_design_time_overlays"]()["time"]["paths"][path]["post"]
        )
        live_operation = copy.deepcopy(reviewed)
        live_operation["operationId"] = "transition"
        for key in (
                "x-dwp-controller-method",
                "x-dwp-overlay-mode",
                "x-dwp-runtime-default",
        ):
            live_operation.pop(key)
        action = next(
            parameter for parameter in live_operation["parameters"]
            if parameter["name"] == "action"
        )
        action["schema"].pop("enum")
        live = {
            "openapi": "3.1.0",
            "info": {"title": "DWP Time Service API", "version": "1.0.0"},
            "paths": {path: {"post": live_operation}},
        }

        merged = EXPORTER["apply_design_time_overlay"](service, live)
        parameters = {
            (parameter["in"], parameter["name"]): parameter
            for parameter in merged["paths"][path]["post"]["parameters"]
        }
        self.assertEqual(
            ["apply-approval", "publish", "submit-review", "validate"],
            parameters[("path", "action")]["schema"]["enum"],
        )

    def test_time_action_overlay_rejects_allowable_value_collision(self) -> None:
        service = next(
            service for service in EXPORTER["SERVICES"] if service.name == "time"
        )
        path = "/v1/hris/work-plans/{workPlanId}/actions/{action}"
        operation = copy.deepcopy(
            EXPORTER["load_design_time_overlays"]()["time"]["paths"][path]["post"]
        )
        operation["operationId"] = "transition"
        for key in (
                "x-dwp-controller-method",
                "x-dwp-overlay-mode",
                "x-dwp-runtime-default",
        ):
            operation.pop(key)
        action = next(
            parameter for parameter in operation["parameters"]
            if parameter["name"] == "action"
        )
        action["schema"]["enum"] = ["publish"]
        live = {
            "openapi": "3.1.0",
            "info": {"title": "DWP Time Service API", "version": "1.0.0"},
            "paths": {path: {"post": operation}},
        }

        with self.assertRaisesRegex(RuntimeError, "parameter schema collision"):
            EXPORTER["apply_design_time_overlay"](service, live)

    def test_g3_assignment_reviewed_springdoc_capture_is_closed(self) -> None:
        overlay = EXPORTER["load_design_time_overlays"]()["people"]
        expected = {
            ("post", "/v1/workforce/assignment-proposals"): (
                "createAssignmentProposal",
                "ApiResponseAssignmentProposalCommandResult",
                "AssignmentProposalCreateRequest",
            ),
            ("get", "/v1/workforce/assignment-proposals/{proposalId}"): (
                "getAssignmentProposal", "ApiResponseAssignmentProposal", None,
            ),
            ("post", "/v1/workforce/assignment-proposals/{proposalId}/cancel"): (
                "cancelAssignmentProposal",
                "ApiResponseAssignmentProposalCommandResult",
                "AssignmentProposalCancelCommand",
            ),
            ("post", "/v1/workforce/assignment-proposals/{proposalId}/submit"): (
                "submitAssignmentProposal",
                "ApiResponseAssignmentProposalCommandResult",
                "AssignmentProposalVersionCommand",
            ),
            ("post", "/v1/workforce/assignment-proposals/{proposalId}/validate"): (
                "validateAssignmentProposal",
                "ApiResponseAssignmentProposalCommandResult",
                "AssignmentProposalVersionCommand",
            ),
            ("get", "/v1/workforce/assignments/{assignmentId}"): (
                "getAssignment",
                "ApiResponseAssignmentProposalAssignmentDetail",
                None,
            ),
            ("get", "/v1/workforce/assignments/{assignmentId}/timeline"): (
                "getAssignmentTimeline",
                "ApiResponseListAssignmentProposalTimelineEntry",
                None,
            ),
        }
        captured = {
            (method, path): operation
            for path, path_item in overlay["paths"].items()
            for method, operation in path_item.items()
            if operation.get("x-dwp-reviewed-springdoc-capture") is True
        }
        self.assertEqual(
            set(captured),
            set(expected) | {
                ("post", "/v1/hris/performance/cycles/{cycleId}/population-freezes"),
                ("get", "/v1/hris/performance/workbench/review-operations"),
            },
        )

        schemas = overlay["components"]["schemas"]
        self.assertEqual(
            set(schemas),
            {
                "AssignmentProposalCreateRequest",
                "AssignmentProposalVersionCommand",
                "AssignmentProposalCancelCommand",
                "AssignmentProposalValidationFinding",
                "AssignmentProposal",
                "AssignmentProposalCommandResult",
                "AssignmentProposalAssignmentDetail",
                "AssignmentProposalTimelineEntry",
                "ApiResponseAssignmentProposalCommandResult",
                "ApiResponseAssignmentProposalAssignmentDetail",
                "ApiResponseListAssignmentProposalTimelineEntry",
                "ApiResponseAssignmentProposal",
                "ApiResponseFreezeCommandResult",
                "ApiResponseProjection",
                "AvailableAction",
                "CachePolicy",
                "CommandReceipt",
                "DeepLink",
                "FieldDecisionSummary",
                "FieldGroups",
                "FreezeCommandResult",
                "FreezePopulationRequest",
                "Item",
                "PartialFailure",
                "PopulationFreeze",
                "Projection",
                "QueryState",
            }
            | set(EXPORTER["HRM_CANONICAL_SCHEMA_SHA256"])
            | set(EXPORTER["WAVE5_PER_TRANSPORT_SCHEMA_NAMES"]),
        )
        self.assertEqual(
            set(schemas["AssignmentProposalCreateRequest"]["required"]),
            {
                "changeType",
                "commandId",
                "effectiveDate",
                "expectedAssignmentVersion",
                "proposedChanges",
                "reasonCode",
                "targetAssignmentId",
            },
        )
        self.assertEqual(
            set(schemas["AssignmentProposalVersionCommand"]["required"]),
            {"commandId", "expectedVersion"},
        )
        self.assertEqual(
            set(schemas["AssignmentProposalCancelCommand"]["required"]),
            {"commandId", "expectedVersion", "reason"},
        )
        for (method, path), (operation_id, response_schema, request_schema) in expected.items():
            operation = captured[(method, path)]
            self.assertEqual(operation["operationId"], operation_id)
            self.assertEqual(operation["x-dwp-runtime-default"], "ON")
            self.assertEqual(
                operation["responses"]["200"]["content"]["application/json"]
                ["schema"]["$ref"],
                f"#/components/schemas/{response_schema}",
            )
            self.assertIn(response_schema, schemas)
            if request_schema is not None:
                self.assertEqual(
                    operation["requestBody"]["content"]["application/json"]
                    ["schema"]["$ref"],
                    f"#/components/schemas/{request_schema}",
                )
                self.assertIn(request_schema, schemas)
                parameters = {
                    (parameter["in"], parameter["name"]): parameter
                    for parameter in operation["parameters"]
                }
                idempotency = parameters[("header", "Idempotency-Key")]
                self.assertTrue(idempotency["required"])
                self.assertEqual(
                    idempotency["schema"],
                    {"type": "string", "minLength": 1, "maxLength": 200},
                )

        submit = captured[(
            "post", "/v1/workforce/assignment-proposals/{proposalId}/submit"
        )]
        headers = {
            parameter["name"]: parameter
            for parameter in submit["parameters"]
            if parameter["in"] == "header"
        }
        self.assertEqual(
            headers["X-DWP-Step-Up-Challenge"]["schema"],
            {"type": "string", "minLength": 1},
        )
        self.assertEqual(
            headers["X-DWP-Expected-Decision-Revision"]["schema"],
            {"type": "string", "minLength": 1, "maxLength": 200},
        )
        self.assertEqual(
            headers["X-DWP-Expected-Object-Version"]["schema"],
            {"type": "integer", "format": "int64", "minimum": 0},
        )
        self.assertTrue(all(headers[name]["required"] for name in (
            "Idempotency-Key",
            "X-DWP-Step-Up-Challenge",
            "X-DWP-Expected-Decision-Revision",
            "X-DWP-Expected-Object-Version",
        )))

    def test_g7_per_time_reviewed_springdoc_captures_are_closed(self) -> None:
        expected_operations = {
            "people": {
                ("post", "/v1/hris/performance/cycles/{cycleId}/population-freezes"):
                    ("hrisPerformanceFreezePopulation", "freeze"),
                ("get", "/v1/hris/performance/workbench/review-operations"):
                    ("hrisPerformanceReviewOperations", "query"),
            },
            "time": {
                ("post", "/v1/clock-events"):
                    ("hrisTimeRecordClockEvent", "record"),
                ("post", "/v1/clock-events/{eventId}/voids"):
                    ("hrisTimeVoidClockEvent", "voidEvent"),
                ("get", "/v1/hris/workbench/clock-ingestion"):
                    ("hrisTimeClockIngestionWorkbench", "workbench"),
                ("get", "/v1/hris/workbench/time-exceptions"):
                    ("hrisTimeClockIngestionExceptions", "exceptions"),
            },
        }
        expected_schemas = {
            "people": {
                "ApiResponseFreezeCommandResult",
                "ApiResponseProjection",
                "AvailableAction",
                "CachePolicy",
                "CommandReceipt",
                "DeepLink",
                "FieldDecisionSummary",
                "FieldGroups",
                "FreezeCommandResult",
                "FreezePopulationRequest",
                "Item",
                "PartialFailure",
                "PopulationFreeze",
                "Projection",
                "QueryState",
            },
            "time": {
                "ApiResponseClockCommandReceipt",
                "ApiResponseClockProjection",
                "ApiResponseExceptionProjection",
                "AvailableAction",
                "CachePolicy",
                "ClockCommandReceipt",
                "ClockFieldGroups",
                "ClockItem",
                "ClockProjection",
                "DeepLink",
                "ExceptionFieldGroups",
                "ExceptionItem",
                "ExceptionProjection",
                "FieldDecisionSummary",
                "PartialFailure",
                "QueryState",
                "RecordClockCommand",
                "VoidClockCommand",
            },
        }

        def schema_references(value: object) -> set[str]:
            if isinstance(value, list):
                return set().union(*(schema_references(item) for item in value))
            if not isinstance(value, dict):
                return set()
            references = set()
            reference = value.get("$ref")
            prefix = "#/components/schemas/"
            if isinstance(reference, str) and reference.startswith(prefix):
                references.add(reference.removeprefix(prefix))
            for child in value.values():
                references.update(schema_references(child))
            return references

        overlays = EXPORTER["load_design_time_overlays"]()
        for service, expected in expected_operations.items():
            overlay = overlays[service]
            captured = {
                (method, path): operation
                for path, path_item in overlay["paths"].items()
                for method, operation in path_item.items()
                if (method, path) in expected
                and operation.get("x-dwp-reviewed-springdoc-capture") is True
            }
            self.assertEqual(set(captured), set(expected))
            for operation_key, (operation_id, controller_method) in expected.items():
                operation = captured[operation_key]
                self.assertEqual(operation["operationId"], operation_id)
                self.assertEqual(
                    operation["x-dwp-controller-method"], controller_method
                )
                self.assertEqual(operation["x-dwp-overlay-mode"], "ADD")
                self.assertEqual(operation["x-dwp-runtime-default"], "OFF")

            schemas = overlay["components"]["schemas"]
            closure = set()
            pending = set().union(*(
                schema_references(operation) for operation in captured.values()
            ))
            while pending:
                name = pending.pop()
                self.assertIn(name, schemas)
                if name in closure:
                    continue
                closure.add(name)
                pending.update(schema_references(schemas[name]) - closure)
            self.assertEqual(closure, expected_schemas[service])
            if service == "time":
                self.assertEqual(
                    set(schemas),
                    expected_schemas[service]
                    | set(EXPORTER["WAVE5_TIM_TRANSPORT_SCHEMA_NAMES"]),
                )

    def test_g3_assignment_reviewed_components_reconcile_only_approved_snapshots(
            self) -> None:
        service = next(
            service for service in EXPORTER["SERVICES"] if service.name == "people"
        )
        document = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        document.setdefault("components", {}).setdefault("schemas", {})[
            "AssignmentProposal"
        ] = {"type": "string"}
        with self.assertRaisesRegex(RuntimeError, "reviewed component collision"):
            EXPORTER["apply_design_time_overlay"](service, document)
        reconciled = EXPORTER["apply_design_time_overlay"](
            service, document, approved_snapshot=True
        )
        self.assertEqual(
            reconciled["components"]["schemas"]["AssignmentProposal"],
            EXPORTER["load_design_time_overlays"]()["people"]
            ["components"]["schemas"]["AssignmentProposal"],
        )

    def test_canonical_receipt_replaces_only_its_pinned_runtime_projection(self) -> None:
        service = next(
            service for service in EXPORTER["SERVICES"] if service.name == "people"
        )
        document = json.loads(
            (ROOT / "contracts/openapi/people.json").read_text(encoding="utf-8")
        )
        document["components"]["schemas"]["CommandReceiptV1"] = {
            "properties": {
                "authorizationRevision": {"format": "int64", "type": "integer"},
                "commandType": {"type": "string"},
                "correlationId": {"format": "uuid", "type": "string"},
                "createdAt": {"format": "date-time", "type": "string"},
                "fieldPolicyRevision": {"format": "int64", "type": "integer"},
                "originatingAction": {"type": "string"},
                "populationScopeDigest": {"type": "string"},
                "purposeCode": {"type": "string"},
                "receiptId": {"format": "uuid", "type": "string"},
                "requestDigest": {"type": "string"},
                "status": {"type": "string"},
                "subjectPrincipalPublicId": {"format": "uuid", "type": "string"},
            },
            "type": "object",
        }
        reconciled = EXPORTER["apply_design_time_overlay"](service, document)
        self.assertEqual(
            reconciled["components"]["schemas"]["CommandReceiptV1"],
            EXPORTER["load_design_time_overlays"]()["people"]
            ["components"]["schemas"]["CommandReceiptV1"],
        )

    def test_wave1_hris_reviewed_inventory_matches_controller_annotations(self) -> None:
        contracts = (
            (
                "auth",
                "dwp-auth-server/src/main/java/com/dwp/services/auth/productaccess/HrisProductAccessController.java",
                "/auth/hris/product-access",
                {"snapshot"},
            ),
            (
                "platform",
                "dwp-platform-server/src/main/java/com/dwp/services/platform/hrisconfiguration/HrisConfigurationProjectionController.java",
                "/v1/hris/configuration",
                {"projection"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/HrController.java",
                "/v1/hr",
                {"people360", "teamPeople360"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/workforce/WorkforcePeopleController.java",
                "/v1/workforce/people",
                {"searchPeople360", "getPeople360"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/performance/PerformanceCycleController.java",
                "/v1/hris/performance",
                {"cycles", "cycle", "create", "update", "validate", "preview", "publish", "receipt"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/performance/populationfreeze/PopulationFreezeController.java",
                "/v1/hris/performance",
                {"freeze"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/performance/populationfreeze/PerformanceReviewOperationsController.java",
                "/v1/hris/performance",
                {"query"},
            ),
            (
                "people",
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/assignment/AssignmentProposalController.java",
                "/v1/workforce",
                {"assignment", "timeline", "proposal", "create", "validate", "submit", "cancel"},
            ),
            (
                "payroll",
                "dwp-payroll-server/src/main/java/com/dwp/services/payroll/foundation/PayrollFoundationController.java",
                "/v1/hris/payroll/foundation",
                {"configurations", "create", "configuration", "versions", "update", "simulate", "publish", "reverse", "receipt", "reconcile"},
            ),
            (
                "time",
                "dwp-time-server/src/main/java/com/dwp/services/time/workregime/WorkRegimeOwnerController.java",
                "/v1/hris",
                {"list", "createDraft", "simulate", "transition", "receipt"},
            ),
            (
                "time",
                "dwp-time-server/src/main/java/com/dwp/services/time/clockingestion/ClockOwnerController.java",
                None,
                {"workbench", "exceptions", "record", "voidEvent"},
            ),
        )
        annotation = re.compile(
            r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?\s+"
            r"(?:public\s+)?[\w.<>,? ]+\s+(\w+)\s*\(",
            re.DOTALL,
        )
        overlays = EXPORTER["load_design_time_overlays"]()
        reviewed = {
            (service, method, path, operation["x-dwp-controller-method"])
            for service, overlay in overlays.items()
            for path, path_item in overlay["paths"].items()
            for method, operation in path_item.items()
        }
        verbs = {
            "Get": "get", "Post": "post", "Put": "put",
            "Patch": "patch", "Delete": "delete",
        }

        for service, relative_file, base_path, methods in contracts:
            source = (ROOT / relative_file).read_text(encoding="utf-8")
            if base_path is not None:
                self.assertRegex(
                    source,
                    rf'@RequestMapping\(\s*(?:value\s*=\s*)?"{re.escape(base_path)}"'
                    rf'(?:\s*,[^)]*)?\)',
                )
            discovered = {}
            for verb, arguments, controller_method in annotation.findall(source):
                path_match = re.search(r'"(/[^"]*)"', arguments or "")
                child_path = path_match.group(1) if path_match else ""
                discovered[controller_method] = (
                    verbs[verb], (base_path or "") + child_path
                )
            self.assertTrue(methods <= set(discovered), relative_file)
            for controller_method in methods:
                method, path = discovered[controller_method]
                self.assertIn(
                    (service, method, path, controller_method),
                    reviewed,
                    f"{relative_file}#{controller_method}",
                )

    def test_gateway_publishes_every_wave1_hris_design_time_operation(self) -> None:
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        services = {service.name: service for service in EXPORTER["SERVICES"]}
        overlays = EXPORTER["load_design_time_overlays"]()
        for name, overlay in overlays.items():
            service = services[name]
            for owner_path, path_item in overlay["paths"].items():
                public_path = service.public_path(owner_path)
                if public_path is None:
                    self.assertTrue(owner_path.startswith("/internal/"))
                    self.assertNotIn(owner_path, gateway["paths"])
                    continue
                for method in path_item:
                    self.assertIn(
                        method,
                        gateway["paths"].get(public_path, {}),
                        f"{name} {method.upper()} {public_path}",
                    )
                    parameters = {
                        (parameter.get("in"), parameter.get("name"))
                        for parameter in gateway["paths"][public_path][method].get(
                            "parameters", []
                        )
                        if isinstance(parameter, dict)
                    }
                    self.assertIn(("query", "contextScopeKey"), parameters)

    def test_v15_governs_and_mirrors_every_apr17_through_24_operation(self) -> None:
        current = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v18.json")
            .read_text(encoding="utf-8")
        )
        previous = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.bundle-v14.json")
            .read_text(encoding="utf-8")
        )
        previous_keys = {route["routeContractKey"] for route in previous["routes"]}
        appended = [
            route for route in current["routes"]
            if route["routeContractKey"] not in previous_keys
            and route["subject"].get("productKey") == "approvals"
        ]
        governed = EXPORTER["product_governed_operations"]()

        self.assertEqual(len(appended), 43)
        self.assertEqual(
            {kind: sum(route["routeKind"] == kind for route in appended)
             for kind in ("PAGE", "DATA", "ACTION")},
            {"PAGE": 5, "DATA": 18, "ACTION": 20},
        )
        self.assertEqual(
            sum(len(route["gatewayApiBindings"]) for route in appended), 89
        )
        self.assertEqual(
            sum(len(route.get("stepUpCommandBindings", [])) for route in appended), 28
        )

        for route in appended:
            public = {item["bindingKey"]: item
                      for item in route["gatewayApiBindings"]}
            service = {item["bindingKey"]: item
                       for item in route["servicePepBindings"]}
            self.assertEqual(public.keys(), service.keys())
            for binding_key, owner in service.items():
                gateway = public[binding_key]
                operation = (gateway["path"], gateway["method"].lower())
                self.assertEqual(gateway["method"], owner["method"])
                self.assertEqual(gateway["path"], "/api/approvals" + owner["path"])
                self.assertEqual(owner["serviceKey"], "approval")
                self.assertNotIn("*", gateway["path"])
                self.assertIn(operation, governed)
                self.assertEqual(governed[operation], route["routeKind"] == "ACTION")

        workplace = [
            route for route in current["routes"]
            if route["routeContractKey"] not in previous_keys
            and route["routeContractKey"].startswith(
                "route.workplace.management.connector-"
            )
        ]
        self.assertEqual(len(workplace), 5)
        for route in workplace:
            for binding in route["gatewayApiBindings"]:
                operation = (binding["path"], binding["method"].lower())
                self.assertIn(operation, governed)
                self.assertEqual(
                    governed[operation], route["routeKind"] == "ACTION"
                )

        owner = json.loads(
            (ROOT / "contracts/openapi/approval.json").read_text(encoding="utf-8")
        )
        prefixes = (
            "/v1/admin/forms/templates",
            "/v1/admin/forms/studio-v3",
            "/v1/admin/workflows/routing-directory",
            "/v1/admin/policies/automation",
            "/v1/admin/operations/connectors",
            "/v1/admin/operations/incidents",
            "/v1/admin/operations/audit-records",
            "/v1/admin/operations/analytics",
            "/v1/admin/operations/deployments",
        )
        registered = {
            (binding["method"], binding["path"])
            for route in appended
            for binding in route["servicePepBindings"]
        }
        # This is a historical v15 release closure test. The same controller
        # prefixes now also contain later runtime operations, so compare the
        # immutable v15 binding set to the exact current owner operations it
        # names instead of treating a shared prefix as a release boundary.
        exposed_v15 = {
            (method, path)
            for method, path in registered
            if path.startswith(prefixes)
            and method.lower() in owner["paths"].get(path, {})
        }
        self.assertEqual(len(registered), 89)
        self.assertEqual(registered, exposed_v15)

    def test_apr23_external_attestation_openapi_uses_signed_trusted_attestor_contract(self) -> None:
        approval = json.loads(
            (ROOT / "contracts/openapi/approval.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        service_path = (
            "/v1/admin/operations/audit-records/exports/"
            "{exportId}/external-attestations"
        )
        public_path = "/api/approvals" + service_path

        self.assertIn("post", approval["paths"][service_path])
        self.assertIn("post", gateway["paths"][public_path])
        for document, prefix in ((approval, ""), (gateway, "approval_")):
            request = document["components"]["schemas"][
                prefix + "ApprovalAuditExternalAttestation"
            ]
            request_properties = request["properties"]
            self.assertEqual(
                set(request["required"]),
                {
                    "type",
                    "reference",
                    "attestedAt",
                    "evidencePayloadBase64Url",
                    "evidenceSignatureBase64Url",
                },
            )
            self.assertNotIn("verificationReference", request_properties)
            self.assertFalse(request["additionalProperties"])

            receipt = document["components"]["schemas"][prefix + "ExportReceipt"]
            self.assertTrue(
                {
                    "externalAttestationIssuer",
                    "externalAttestorIdentity",
                    "externalAttestationKeyId",
                    "externalVerificationReference",
                }.issubset(receipt["properties"])
            )

    def test_v14_governs_every_new_approval_operation(self) -> None:
        operations = EXPORTER["product_governed_operations"]()
        required = {
            ("/api/approvals/v1/requests/{requestId}/resubmit-draft", "post"),
            ("/api/approvals/v1/delegations/{delegationId}", "put"),
            ("/api/approvals/v1/admin/operations/events/{outboxId}/dead-letter", "post"),
            ("/api/approvals/v1/admin/operations/events/{outboxId}/replay", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/retry", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/dead-letter", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/replay", "post"),
            ("/api/approvals/v1/admin/operations/deliveries/reconcile", "post"),
            ("/api/approvals/v1/admin/operations/tasks/{taskId}/reassign", "post"),
            ("/api/approvals/v1/admin/operations/tasks/reassign", "post"),
            ("/api/approvals/v1/admin/signatures/diagnostics", "get"),
            ("/api/approvals/v1/admin/signatures/providers/{providerId}/diagnostics", "get"),
            ("/api/approvals/v1/admin/signatures/diagnostic-history", "get"),
            ("/api/approvals/v1/admin/signatures/probes", "post"),
            ("/api/approvals/v1/admin/signatures/kms/probes", "post"),
            ("/api/approvals/v1/admin/signatures/worm-inspections", "post"),
            ("/api/approvals/v1/admin/signatures/policy", "get"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/history", "get"),
            ("/api/approvals/v1/admin/signatures/policies", "post"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/draft", "put"),
            ("/api/approvals/v1/admin/signatures/policies/{policyId}/publish", "post"),
            ("/api/approvals/v1/requests/{requestId}/external-signature-context", "get"),
            ("/api/approvals/v1/requests/{requestId}/external-signature-requests", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}", "get"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/handovers", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/refresh", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/cancel", "post"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/audit", "get"),
            ("/api/approvals/v1/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}", "get"),
            ("/api/approvals/v1/requests/{requestId}/draft/migration-preview", "get"),
            ("/api/approvals/v1/requests/{requestId}/draft/migrate", "post"),
            ("/api/approvals/v1/admin/policies", "post"),
            ("/api/approvals/v1/admin/forms/publish-review-candidates", "get"),
            ("/api/approvals/v1/admin/forms/publish-review-requests", "get"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-request", "get"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-request", "post"),
            ("/api/approvals/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject", "post"),
        }

        self.assertEqual(len(required), 37)
        self.assertTrue(required.issubset(operations))
        document = {"paths": {}}
        for path, method in required:
            document["paths"].setdefault(path, {})[method] = {"parameters": []}
        EXPORTER["add_product_governance_contract"](document)
        for operation in required:
            self.assertEqual(operations[operation], operation[1] != "get")
            parameters = document["paths"][operation[0]][operation[1]]["parameters"]
            names = {parameter["name"] for parameter in parameters}
            self.assertIn("contextScopeKey", names)
            if operation[1] == "get":
                self.assertNotIn("X-DWP-Expected-Decision-Revision", names)
            else:
                self.assertIn("X-DWP-Expected-Decision-Revision", names)

    def test_document_policy_uuid_operations_receive_scope_and_revision(self) -> None:
        base = "/api/approvals/v1/admin/document-tools/policies/{policyId}"
        document = {"paths": {
            base + "/draft": {"put": {"parameters": [{"name": "policyId", "in": "path", "required": True}]}},
            base + "/publish": {"post": {"parameters": [{"name": "policyId", "in": "path", "required": True}]}}
        }}
        EXPORTER["add_product_governance_contract"](document)
        for path, method in [(base + "/draft", "put"), (base + "/publish", "post")]:
            parameters = document["paths"][path][method]["parameters"]
            self.assertEqual(sum(parameter["name"] == "contextScopeKey" for parameter in parameters), 1)
            self.assertEqual(sum(parameter["name"] == "X-DWP-Expected-Decision-Revision" for parameter in parameters), 1)
            self.assertTrue(next(parameter for parameter in parameters if parameter["name"] == "policyId")["required"])
        operations = EXPORTER["product_governed_operations"]()
        self.assertNotIn(("/api/approvals/v1/admin/document-tools/policy/draft", "put"), operations)
        self.assertNotIn(("/api/approvals/v1/admin/document-tools/policy/publish", "post"), operations)

    def test_gateway_openapi_documents_scope_and_revision_for_a02_mutation(self) -> None:
        path = "/api/platform/v1/admin/dwaion/agents/{entryKey}/revisions/{revisionNumber}/activate"
        document = {
            "paths": {
                path: {
                    "post": {
                        "parameters": [
                            {"name": "entryKey", "in": "path", "required": True},
                            {"name": "revisionNumber", "in": "path", "required": True},
                        ]
                    }
                }
            }
        }

        EXPORTER["add_product_governance_contract"](document)
        parameters = document["paths"][path]["post"]["parameters"]
        by_name = {parameter["name"]: parameter for parameter in parameters}

        self.assertEqual(
            by_name["contextScopeKey"],
            copy.deepcopy(EXPORTER["SCOPE_SELECTION_PARAMETER"]),
        )
        self.assertEqual(
            by_name["X-DWP-Expected-Decision-Revision"],
            copy.deepcopy(EXPORTER["EXPECTED_DECISION_REVISION_PARAMETER"]),
        )

    def test_wave5_registry_placement_keys_and_instance_ceiling_are_public(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )
        definition_key = {
            "maxLength": 160,
            "minLength": 0,
            "pattern": "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*",
            "type": "string",
        }

        for document, prefix in ((platform, ""), (gateway, "platform_")):
            schemas = document["components"]["schemas"]
            preference = schemas[f"{prefix}WidgetPreference"]
            self.assertEqual(preference["properties"]["widgetKey"], definition_key)

            layout = schemas[f"{prefix}HomeLayoutPayload"]
            self.assertEqual(layout["properties"]["widgets"]["maxItems"], 30)

            overlay = schemas[f"{prefix}DeviceLayoutOverlay"]
            self.assertEqual(overlay["properties"]["widgetOrder"]["maxItems"], 30)
            self.assertEqual(
                overlay["properties"]["widgetOrder"]["items"], definition_key
            )
            self.assertEqual(
                overlay["properties"]["widgetSizes"]["maxProperties"], 30
            )
            self.assertEqual(
                overlay["properties"]["widgetSizes"]["propertyNames"],
                {
                    "maxLength": 160,
                    "pattern": "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*",
                    "type": "string",
                },
            )

    def test_wave5_home_restore_and_conflict_contracts_are_public_and_typed(
        self,
    ) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        for document, prefix, component_prefix, operation_id in (
            (platform, "", "", "restoreHomeTemplateRevision"),
            (gateway, "/api/platform", "platform_", "platform_restoreHomeTemplateRevision"),
        ):
            restore_path = (
                prefix
                + "/v1/home-templates/{templateId}/revisions/{revisionId}/restore"
            )
            restore = document["paths"][restore_path]["post"]
            self.assertEqual(restore["operationId"], operation_id)
            self.assertEqual(
                restore["requestBody"]["content"]["application/json"]["schema"]["$ref"],
                f"#/components/schemas/{component_prefix}VersionRequest",
            )
            self.assertEqual(
                restore["responses"]["200"]["content"]["*/*"]["schema"]["$ref"],
                f"#/components/schemas/{component_prefix}ApiResponseHomeTemplateResponse",
            )

            update_path = prefix + "/v1/home-views/{viewId}"
            conflict_ref = document["paths"][update_path]["put"]["responses"]["409"][
                "content"
            ]["application/json"]["schema"]["$ref"]
            self.assertEqual(
                conflict_ref,
                f"#/components/schemas/{component_prefix}HomeViewConflictEnvelope",
            )
            conflict = document["components"]["schemas"][
                f"{component_prefix}HomeViewConflictResponse"
            ]
            self.assertEqual(
                conflict["required"],
                [
                    "actualVersion",
                    "changedFields",
                    "expectedVersion",
                    "latestView",
                    "operation",
                    "submittedDraft",
                ],
            )
            self.assertTrue(
                {"expectedDeviceVersion", "actualDeviceVersion", "latestDeviceLayout"}
                .issubset(conflict["properties"])
            )

    def test_home_studio_audit_contract_is_public_and_bounded(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        for document, path, operation_id, response_schema in (
            (
                platform,
                "/v1/admin/home-experience/audit-events",
                "listHomeStudioAuditEvents",
                "#/components/schemas/ApiResponseAuditPage",
            ),
            (
                gateway,
                "/api/platform/v1/admin/home-experience/audit-events",
                "platform_listHomeStudioAuditEvents",
                "#/components/schemas/platform_ApiResponseAuditPage",
            ),
        ):
            operation = document["paths"][path]["get"]
            self.assertEqual(operation["operationId"], operation_id)
            parameters = {
                parameter["name"]: parameter["schema"]
                for parameter in operation["parameters"]
            }
            self.assertEqual(
                parameters["page"],
                {
                    "default": 0,
                    "format": "int32",
                    "maximum": 1000,
                    "minimum": 0,
                    "type": "integer",
                },
            )
            self.assertEqual(
                parameters["size"],
                {
                    "default": 50,
                    "format": "int32",
                    "maximum": 100,
                    "minimum": 1,
                    "type": "integer",
                },
            )
            self.assertEqual(
                operation["responses"]["200"]["content"]["*/*"]["schema"]["$ref"],
                response_schema,
            )

    def test_widget_version_transition_schema_does_not_replace_existing_transition_contracts(
        self,
    ) -> None:
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(encoding="utf-8")
        )

        def request_schema(path: str) -> str:
            return gateway["paths"][path]["post"]["requestBody"]["content"][
                "application/json"
            ]["schema"]["$ref"]

        existing_transition = "#/components/schemas/platform_TransitionRequest"
        widget_transition = (
            "#/components/schemas/platform_WidgetVersionTransitionRequest"
        )
        for path in (
            "/api/platform/v1/admin/localization/revisions/{revisionId}/publish",
            "/api/platform/v1/admin/localization/revisions/{revisionId}/submit",
            "/api/platform/v1/admin/services/requests/{requestId}/transition",
        ):
            self.assertEqual(request_schema(path), existing_transition)
        for path in (
            "/api/provider/v1/admin/widget-definition-versions/{versionId}/submit",
            "/api/provider/v1/admin/widget-definition-versions/{versionId}/rework",
        ):
            self.assertEqual(request_schema(path), widget_transition)

        schemas = gateway["components"]["schemas"]
        self.assertEqual(
            schemas["platform_TransitionRequest"]["required"],
            ["targetStatus", "version"],
        )
        self.assertEqual(
            schemas["platform_WidgetVersionTransitionRequest"]["required"],
            ["expectedVersion", "reasonCode", "reasonText"],
        )

    def test_widget_registry_provider_control_plane_has_one_public_identity_plane(self) -> None:
        platform_path = EXPORTER["platform_path"]
        self.assertEqual(
            platform_path("/v1/admin/widget-definitions"),
            "/api/provider/v1/admin/widget-definitions",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-definition-versions/123/publish"),
            "/api/provider/v1/admin/widget-definition-versions/123/publish",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-registry/readiness"),
            "/api/provider/v1/admin/widget-registry/readiness",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-catalog"),
            "/api/platform/v1/admin/widget-catalog",
        )
        self.assertEqual(
            platform_path("/v1/admin/widget-policies/123"),
            "/api/platform/v1/admin/widget-policies/123",
        )

    def test_home_v2_is_the_only_exported_platform_v2_surface(self) -> None:
        platform_path = EXPORTER["platform_path"]
        self.assertEqual(platform_path("/v2/home"), "/api/platform/v2/home")
        self.assertEqual(
            platform_path("/v2/home/widget-actions:execute"),
            "/api/platform/v2/home/widget-actions:execute",
        )
        self.assertIsNone(platform_path("/v2/admin/unsafe"))
        operations = EXPORTER["product_governed_operations"]()
        self.assertFalse(operations[("/api/platform/v2/home", "get")])
        self.assertTrue(operations[(
            "/api/platform/v2/home/widget-actions:execute", "post"
        )])

    def test_mail_release_and_readiness_contracts_are_current(self) -> None:
        platform = json.loads(
            (ROOT / "contracts/openapi/platform.json").read_text(encoding="utf-8")
        )
        gateway = json.loads(
            (ROOT / "contracts/openapi/gateway-public.json").read_text(
                encoding="utf-8"
            )
        )
        old_path = "/v1/admin/mail/retention/holds/{holdId}/release"
        self.assertNotIn(old_path, platform["paths"])
        self.assertNotIn(f"/api/platform{old_path}", gateway["paths"])

        operations = {
            "/v1/admin/mail/retention/holds/{holdId}/release-previews": "post",
            "/v1/admin/mail/retention/hold-release-previews/{previewId}": "get",
            "/v1/admin/mail/retention/hold-release-previews/{previewId}/approvals": "post",
            "/v1/admin/mail/retention/hold-release-previews/{previewId}/execute": "post",
        }
        for path, method in operations.items():
            self.assertIn(method, platform["paths"][path])
            self.assertIn(method, gateway["paths"][f"/api/platform{path}"])

        stable_mail_operation_ids = {
            "/v1/admin/mail/writing-assets/{kind}/drafts":
                ("post", "createAdminMailWritingAssetDraft"),
            "/v1/admin/mail/writing-assets/{kind}/drafts/{assetId}":
                ("put", "updateAdminMailWritingAssetDraft"),
            "/v1/admin/mail/writing-assets/{kind}/{assetId}/submit":
                ("post", "submitAdminMailWritingAsset"),
            "/v1/admin/mail/writing-assets/{kind}/{assetId}/approve":
                ("post", "approveAdminMailWritingAsset"),
            "/v1/admin/mail/writing-assets/{kind}/{assetId}/publish":
                ("post", "publishAdminMailWritingAsset"),
            "/v1/admin/mail/writing-assets/{kind}/{assetId}/retire":
                ("post", "retireAdminMailWritingAsset"),
            "/v1/mail/deliveries/{deliveryId}/cancel":
                ("post", "cancelMailDelivery"),
            "/v1/mail/deliveries/{deliveryId}/reconcile":
                ("post", "reconcileMailDelivery"),
            "/v1/admin/mail/operations": ("get", "listAdminMailOperations"),
            "/v1/admin/mail/overview": ("get", "getAdminMailOverview"),
            "/v1/mail/threads/{threadId}/lifecycle/preview":
                ("post", "previewMailThreadLifecycle"),
            "/v1/mail/threads/{threadId}/draft": ("put", "updateMailDraft"),
            "/v1/admin/mail/policy": ("put", "updateAdminMailPolicy"),
        }
        for path, (method, operation_id) in stable_mail_operation_ids.items():
            self.assertEqual(
                operation_id,
                platform["paths"][path][method]["operationId"],
            )

        gateway_operation_ids = [
            operation["operationId"]
            for path_item in gateway["paths"].values()
            for method, operation in path_item.items()
            if method in EXPORTER["HTTP_METHODS"]
            and isinstance(operation, dict)
            and "operationId" in operation
        ]
        self.assertEqual(len(gateway_operation_ids), len(set(gateway_operation_ids)))

        schemas = platform["components"]["schemas"]
        self.assertEqual(
            set(schemas["AccountReadiness"]["properties"]),
            {
                "state", "source", "observedAt", "credentialConfigured",
                "lastSuccessfulSyncAt", "lastSuccessfulSyncScope", "errorCode",
                "action", "consentEvidence", "tokenEvidence", "featureReadiness",
            },
        )
        self.assertEqual(
            set(schemas["FeatureReadiness"]["properties"]),
            {
                "state", "source", "observedAt", "errorCode",
                "lastSuccessfulAt", "lastSuccessfulScope", "action",
            },
        )
        self.assertIn("accountId", schemas["GroupMessageRequest"]["properties"])
        self.assertIn("accountId", schemas["GroupSendReceipt"]["properties"])


if __name__ == "__main__":
    unittest.main()
