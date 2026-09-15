import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v13_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


ROUTES = {
    "route.approvals.work.request-draft-migration-preview.data": (
        "DATA",
        "GET",
        "/v1/requests/{requestId}/draft/migration-preview",
        "approvals.work.request.read",
        ["predicate.approval.own-request.v1"],
    ),
    "route.approvals.work.request-draft-migrate.action": (
        "ACTION",
        "POST",
        "/v1/requests/{requestId}/draft/migrate",
        "approvals.work.request.update",
        ["predicate.approval.own-request.v1", "predicate.approval.object-version.v1"],
    ),
    "route.approvals.admin.policy-create.action": (
        "ACTION",
        "POST",
        "/v1/admin/policies",
        "approvals.policy.update",
        [],
    ),
}


class ProductAuthorizationV13Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = GENERATOR.load_source()
        cls.snapshots = GENERATOR.build_snapshots(cls.source)
        cls.v12 = cls.snapshots[11]
        cls.v13 = cls.snapshots[12]
        cls.routes = {route["routeContractKey"]: route for route in cls.v13["routes"]}

    def test_v13_is_the_exact_three_route_append(self):
        prior = {route["routeContractKey"] for route in self.v12["routes"]}
        self.assertEqual(set(ROUTES), set(self.routes) - prior)
        self.assertEqual(
            "3bd67d7b145c5b7c845788c70f8884c8afadedd9920de419ecd1e1d0e8a4c8b0",
            self.v13["checksum"],
        )
        self.assertEqual(
            GENERATOR.render(self.v12).encode(),
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[12].read_bytes(),
        )
        self.assertEqual(
            GENERATOR.render(self.v13).encode(),
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[13].read_bytes(),
        )

    def test_routes_match_controller_wires_and_least_privilege(self):
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

    def test_preview_uses_the_bounded_default_projection(self):
        route = self.routes[
            "route.approvals.work.request-draft-migration-preview.data"
        ]
        projection = route["accessProfiles"][0]["responseProjectionBindings"][0]
        self.assertEqual(
            {"apiBindingKey", "projectionPolicyKey", "responseSchemaKey"},
            set(projection),
        )
        serialized = str(projection).lower()
        self.assertNotIn("secret", serialized)
        self.assertNotIn("rawpayload", serialized)

    def test_route_path_context_scope_or_predicate_drift_fails_closed(self):
        for mutation in ("gateway-path", "context", "capability", "predicate"):
            with self.subTest(mutation=mutation):
                source = copy.deepcopy(self.source)
                wave = next(value for value in source["waves"] if value["version"] == 13)
                route = next(value for value in wave["routes"] if value["routeContractKey"] ==
                             "route.approvals.work.request-draft-migrate.action")
                if mutation == "gateway-path":
                    route["gatewayApiBindings"][0]["path"] += "/alias"
                elif mutation == "context":
                    route["navigationContextId"] = "approvals.admin"
                elif mutation == "capability":
                    route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"] = \
                        "approvals.work.request.read"
                else:
                    route["accessProfiles"][0]["predicatePolicyKeys"] = [
                        "predicate.approval.object-version.v1"
                    ]
                with self.assertRaises(GENERATOR.ContractError):
                    GENERATOR.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
