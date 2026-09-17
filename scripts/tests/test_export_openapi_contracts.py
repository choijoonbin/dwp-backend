from __future__ import annotations

import copy
import json
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

    def test_gateway_openapi_projection_consumes_append_only_v28_registry(self) -> None:
        registry_path = EXPORTER["PRODUCT_AUTHORIZATION_REGISTRY"]
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        dwaion_routes = [
            route
            for route in registry["routes"]
            if route["subject"].get("productKey") == "dwaion"
        ]

        self.assertEqual(EXPORTER["PRODUCT_AUTHORIZATION_VERSION"], 28)
        self.assertEqual(registry["version"], 28)
        self.assertEqual(len(dwaion_routes), 145)
        self.assertEqual(
            sum(route["routeKind"] == "ACTION" for route in dwaion_routes), 85
        )
        approval_routes = [route for route in registry["routes"]
                           if route["subject"].get("productKey") == "approvals"]
        self.assertEqual(len(approval_routes), 195)
        self.assertEqual(sum(route["routeKind"] == "ACTION" for route in approval_routes), 101)

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
        exposed = {
            (method.upper(), path)
            for path, path_item in owner["paths"].items()
            if path.startswith(prefixes)
            for method in path_item
            if method in {"get", "post", "put", "patch", "delete"}
        }
        registered = {
            (binding["method"], binding["path"])
            for route in appended
            for binding in route["servicePepBindings"]
        }
        self.assertEqual(len(exposed), 89)
        self.assertEqual(registered, exposed)

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


if __name__ == "__main__":
    unittest.main()
