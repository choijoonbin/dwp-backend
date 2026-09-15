from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v14_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


ROUTES = {
    "route.approvals.admin.form-publish-review-candidates.data": (
        "DATA", "GET", "/v1/admin/forms/publish-review-candidates",
        "approvals.design.read", [],
    ),
    "route.approvals.admin.form-publish-review-queue.data": (
        "DATA", "GET", "/v1/admin/forms/publish-review-requests",
        "approvals.design.read", [],
    ),
    "route.approvals.admin.form-publish-review-request.data": (
        "DATA", "GET", "/v1/admin/forms/{formId}/publish-review-request",
        "approvals.design.read", [],
    ),
    "route.approvals.admin.form-publish-review-request.action": (
        "ACTION", "POST", "/v1/admin/forms/{formId}/publish-review-request",
        "approvals.design.update", ["predicate.approval.object-version.v1"],
    ),
    "route.approvals.admin.form-publish-review-reject.action": (
        "ACTION", "POST",
        "/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject",
        "approvals.design.publish", ["predicate.approval.object-version.v1"],
    ),
}


class ProductAuthorizationV14Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = GENERATOR.load_source()
        cls.snapshots = GENERATOR.build_snapshots(cls.source)
        cls.v13 = cls.snapshots[12]
        cls.v14 = cls.snapshots[13]
        cls.routes = {route["routeContractKey"]: route for route in cls.v14["routes"]}

    def test_v14_is_the_exact_five_route_append(self):
        prior = {route["routeContractKey"] for route in self.v13["routes"]}
        self.assertEqual(set(ROUTES), set(self.routes) - prior)
        self.assertEqual(
            "7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336",
            self.v14["checksum"],
        )
        for version, snapshot in enumerate(self.snapshots[:13], start=1):
            self.assertEqual(
                GENERATOR.render(snapshot).encode(),
                GENERATOR.VERSIONED_CONTRACT_OUTPUTS[version].read_bytes(),
            )
        self.assertEqual(
            GENERATOR.render(self.v14).encode(),
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[14].read_bytes(),
        )

    def test_routes_match_owner_wires_and_least_privilege(self):
        for key, (kind, method, path, capability, predicates) in ROUTES.items():
            with self.subTest(route=key):
                route = self.routes[key]
                profile = route["accessProfiles"][0]
                self.assertEqual(kind, route["routeKind"])
                self.assertEqual((method, path), (
                    route["servicePepBindings"][0]["method"],
                    route["servicePepBindings"][0]["path"],
                ))
                self.assertEqual(
                    "/api/approvals" + path,
                    route["gatewayApiBindings"][0]["path"],
                )
                self.assertEqual(
                    capability,
                    profile["requiredAccess"]["capabilityContractKey"],
                )
                self.assertEqual(predicates, profile["predicatePolicyKeys"])
                self.assertEqual(kind == "DATA", profile["readOnly"])
        self.assertNotIn(
            "stepUpCommandBindings",
            self.routes["route.approvals.admin.form-publish-review-reject.action"],
        )

    def test_data_routes_use_only_the_bounded_default_projection(self):
        for route_key in (
            "route.approvals.admin.form-publish-review-candidates.data",
            "route.approvals.admin.form-publish-review-queue.data",
            "route.approvals.admin.form-publish-review-request.data",
        ):
            projection = self.routes[route_key]["accessProfiles"][0][
                "responseProjectionBindings"
            ][0]
            self.assertEqual(
                {"apiBindingKey", "projectionPolicyKey", "responseSchemaKey"},
                set(projection),
            )
            serialized = str(projection).lower()
            self.assertNotIn("secret", serialized)
            self.assertNotIn("rawpayload", serialized)

    def test_reject_cannot_gain_a_fabricated_step_up_binding(self):
        source = copy.deepcopy(self.source)
        wave = next(value for value in source["waves"] if value["version"] == 14)
        route = next(value for value in wave["routes"] if value["routeContractKey"] ==
                     "route.approvals.admin.form-publish-review-reject.action")
        route["stepUpCommandBindings"] = [{
            "bindingKey": route["servicePepBindings"][0]["bindingKey"],
            "targetType": "FORM",
            "targetIdPathParameter": "formId",
            "expectedObjectVersionSource": "COMMAND_HEADER",
            "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
            "ownerServiceKey": "approval",
            "audience": "dwp-approval-server",
        }]
        with self.assertRaises(GENERATOR.ContractError):
            GENERATOR.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
