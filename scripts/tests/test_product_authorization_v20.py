from __future__ import annotations

import hashlib
import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v20_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

IMMUTABLE_FILE_SHA256 = {
    1: "5175c0e36587241ebab59124a02130605363b5497808fa1459913b1ef8885f24",
    2: "470c8b5608ec53597cc161ae997dea24092218ce735def78bf432ade9268d16a",
    3: "a8014a6f8a7f179a1f39a6d77e374763733296257104f5546ea01f50de60f9f0",
    4: "41ee22506d246ccef8b245acbff0072771eb1f6fdeec95e7e41615122862a123",
    5: "9344fd1a835eb1f6d84089d0f8fd19e260e412e28e01b67e7a606420ead8a34a",
    6: "08fd9f286ff5ed8d6ed960a07a784b28e4a508273d17c74be72505175a932d0b",
    7: "31c7e0cb119ca89394ad587fb56da829e8d787116c655fff40e4d3e056fc8fb5",
    8: "fb90fe5179d2eff7328291813dd302d01d7c8b1623065a7fa3dc0db47081ad00",
    9: "8701f22c23c58049ed7c0bb725510d79390358fd5e905a105b766b5e620fc063",
    10: "73c709267c1692a33ef76051fee0c68f407cf9545eb52cd3b0917d4aeffd0f15",
    11: "af68f13a4b3b04aed188d07716987e4b0313a50e6125058d2bb85b41f5915753",
    12: "74d1b462c96fad2b3ab1e3cb0e41d9dcf3c4ef7fa30546737eecd5eac57a698c",
    13: "368561f193b7521ab6f02f82d7bbdc162f6fea097bf5abdde40c641c70dbbb54",
    14: "7f854df231af6b8972e128a7e60d672cd2d5976009f2fe9247ec9741758b6c5b",
    15: "5932df6939717323f92ca9c4fe5d0626fb08ef21675502db7871a465e3e9500a",
    16: "642fdaed9137b294ff26fa4f5fe23701572016097f4ca1b8b4424e6cdad23bb7",
    17: "ab8937ecc368c0de667a646552c6e96da8b81a306d9e6aa9112b8f61506f3cdd",
    18: "c5875ddc2d546c35a8d4eeee91a2ab19d266f2e6f2cea8c342e874756fa5a062",
    19: "d992c42c84194e9e926d02e79274ed0ab721e94b564934612b4838b3ac65eb82",
}
WORKPLACE_PAGE_KEYS = {
    "route.workplace.work.home.page",
    "route.workplace.work.explore.page",
    "route.workplace.work.find.page",
    "route.workplace.work.planner.page",
    "route.workplace.work.reservations.page",
    "route.workplace.work.service-orders.page",
    "route.workplace.work.wayfinding.page",
    "route.workplace.work.assistant.page",
    "route.workplace.work.safety.page",
    "route.workplace.management.service-catalog.page",
    "route.workplace.management.service-fulfillment.page",
    "route.workplace.management.devices.page",
    "route.workplace.management.service-providers.page",
    "route.workplace.management.space-planning.page",
    "route.workplace.management.assistant-governance.page",
    "route.workplace.management.governance.page",
    "route.workplace.management.safety.page",
    "route.workplace.management.visits.page",
    "route.workplace.management.visit-policies.page",
    "route.workplace.management.access-zones.page",
    "route.workplace.management.visit-providers.page",
    "route.workplace.management.kiosk-devices.page",
    "route.workplace.management.overview.page",
    "route.workplace.management.operations.page",
    "route.workplace.management.locations.page",
    "route.workplace.management.policy.page",
    "route.workplace.management.room-operations.page",
    "route.workplace.management.room-policy.page",
}
DEVICE_BINDINGS = {
    "POST /v1/device/workplace/devices/{deviceId}/heartbeat",
    "GET /v1/device/workplace/devices/{deviceId}/projection",
    "POST /v1/device/workplace/devices:register",
    "POST /v1/workplace/kiosk/devices/{deviceId}:heartbeat",
    "POST /v1/workplace/kiosk/devices/{deviceId}:help",
    "GET /v1/workplace/kiosk/session",
    "GET /v1/workplace/kiosk/visits/{visitId}",
    "POST /v1/workplace/kiosk/visits/{visitId}:arrive",
    "POST /v1/workplace/kiosk/visits/{visitId}:checkout",
}
ROOM_BINDINGS = {
    "GET /v1/rooms/policy",
    "GET /v1/rooms/availability",
    "GET /v1/rooms/bookings",
    "POST /v1/rooms/bookings",
    "PUT /v1/rooms/bookings/{eventId}",
    "POST /v1/rooms/bookings/{eventId}/response",
    "POST /v1/rooms/bookings/{eventId}/cancel",
    "GET /v1/admin/rooms/overview",
    "GET /v1/admin/rooms/policy",
    "PUT /v1/admin/rooms/policy",
    "GET /v1/admin/rooms/bookings/pending",
    "POST /v1/admin/rooms/bookings/{bookingId}/decision",
    "POST /v1/admin/rooms/resources",
    "PUT /v1/admin/rooms/resources/{resourceId}",
}


def service_bindings(bundle: dict) -> set[str]:
    return {
        f"{binding['method']} {binding['path']}"
        for route in bundle["routes"]
        if route["subject"].get("productKey") == "workplace"
        for binding in route["servicePepBindings"]
        if binding["serviceKey"] == "platform"
    }


def openapi_workplace_bindings() -> set[str]:
    document = json.loads((ROOT / "contracts/openapi/platform.json").read_text())
    methods = {"get", "post", "put", "patch", "delete"}
    return {
        f"{method.upper()} {path}"
        for path, item in document["paths"].items()
        if path.startswith((
            "/v1/workplace/", "/v1/admin/workplace/", "/v1/device/workplace/"
        ))
        for method in item
        if method in methods
    }


class ProductAuthorizationV20Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v19 = cls.snapshots[18]
        cls.v20 = cls.snapshots[19]

    def test_v1_through_v19_contract_and_auth_seed_bytes_are_frozen(self):
        checked = 0
        for version, expected in IMMUTABLE_FILE_SHA256.items():
            for path in (
                GENERATOR.VERSIONED_CONTRACT_OUTPUTS[version],
                GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[version],
            ):
                self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest(), path)
                checked += 1
        self.assertEqual(38, checked)

    def test_v20_is_pinned_and_exactly_closes_the_current_human_inventory(self):
        self.assertEqual(20, self.v20["version"])
        self.assertEqual(
            "1acbce34c450c650aa3e8f1c11995b831f177ee4a6139dd8217ea5bdd22e7a60",
            self.v20["checksum"],
        )
        self.assertEqual(
            (159, 22, 16, 46, 686),
            tuple(len(self.v20[key]) for key in (
                "capabilities", "accessPolicies", "entitlementExpressions",
                "predicatePolicies", "routes",
            )),
        )
        self.assertEqual(
            {"PAGE": 111, "DATA": 207, "ACTION": 368},
            {kind: sum(route["routeKind"] == kind for route in self.v20["routes"])
             for kind in ("PAGE", "DATA", "ACTION")},
        )

        current = openapi_workplace_bindings()
        self.assertEqual(279, len(current))
        self.assertTrue(DEVICE_BINDINGS <= current)
        human = current - DEVICE_BINDINGS
        self.assertEqual(270, len(human))
        projection = service_bindings(self.v20)
        strict_projection = {
            binding for binding in projection
            if " /v1/workplace/" in binding or " /v1/admin/workplace/" in binding
        }
        room_projection = {
            binding for binding in projection
            if " /v1/rooms/" in binding or " /v1/admin/rooms/" in binding
        }
        self.assertEqual(human, strict_projection)
        self.assertEqual(ROOM_BINDINGS, room_projection)
        self.assertEqual(284, len(projection))
        self.assertFalse(projection & DEVICE_BINDINGS)

    def test_v20_page_contracts_match_the_frontend_manifest_plus_home_alias(self):
        page_routes = {
            route["routeContractKey"]: route
            for route in self.v20["routes"]
            if route["subject"].get("productKey") == "workplace"
            and route["routeKind"] == "PAGE"
        }
        self.assertEqual(WORKPLACE_PAGE_KEYS, set(page_routes))
        self.assertEqual("/workplace/home", page_routes[
            "route.workplace.work.home.page"]["uiRoutePattern"])
        self.assertEqual(
            "wire-authority.workplace.work.explore.v1",
            page_routes["route.workplace.work.home.page"]["authorizationEquivalenceKey"],
        )

    def test_sensitive_capability_semantics_are_exact(self):
        routes = {route["routeContractKey"]: route for route in self.v20["routes"]}
        for suffix in ("approve", "publish"):
            access = routes[
                "route.workplace.management.space-planning-scenarios-by-scenario-id-"
                + suffix + "-post.action"
            ]["accessProfiles"][0]
            self.assertEqual(["ELEVATED"], access["activeAccessModes"])
            self.assertEqual({
                "type": "CAPABILITY_EXPRESSION",
                "mode": "ALL",
                "capabilityContractKeys": [
                    "workplace.policy.manage", "workplace.policy.approve"
                ],
            }, access["requiredAccess"])

        safety = routes[
            "route.workplace.management.safety-incidents-by-incident-id-exports-post.action"
        ]["accessProfiles"][0]
        self.assertEqual(["ELEVATED"], safety["activeAccessModes"])
        self.assertEqual(
            {"type": "CAPABILITY", "capabilityContractKey": "workplace.operations.export"},
            safety["requiredAccess"],
        )
        for operation in ("check-in", "cancel", "release", "relocate"):
            path = f"/v1/workplace/bookings/{{bookingId}}/{operation}"
            route = next(
                route for route in self.v20["routes"]
                if any(binding["path"] == path for binding in route["servicePepBindings"])
            )
            self.assertEqual(
                "workplace.booking.update",
                route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
            )

    def test_platform_v20_projection_is_closed_over_every_page_and_authority(self):
        projection = json.loads(
            GENERATOR.LEGACY_PLATFORM_WORKPLACE_PEP_V20_OUTPUT.read_text()
        )
        self.assertEqual(20, projection["registryRef"]["version"])
        self.assertEqual(self.v20["checksum"], projection["registryRef"]["sha256"])
        self.assertEqual(285, projection["projectedRouteContractCount"])
        self.assertEqual(287, projection["bindingPairCount"])
        self.assertEqual(
            {"ACTION": 163, "DATA": 94, "PAGE": 28},
            projection["routeKindCounts"],
        )
        self.assertEqual(
            WORKPLACE_PAGE_KEYS,
            {route["routeContractKey"] for route in projection["routes"]
             if route["routeKind"] == "PAGE"},
        )


if __name__ == "__main__":
    unittest.main()
