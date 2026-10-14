import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = (
    ROOT / "dwp-auth-server" / "src" / "main" / "resources"
    / "product-authorization"
)
PAYROLL_PROJECTION = (
    ROOT / "dwp-payroll-server" / "src" / "main" / "resources"
    / "product-authorization" / "hcm-payroll-pep-v41.generated.json"
)
V41_SHA256 = "026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93"

CAPABILITIES = {
    "hcm.operations.payroll-run.execute": "ACTION.HRIS_PAYROLL_RUN:EXECUTE",
    "hcm.operations.payroll-support.view": "DATA.HRIS_PAYROLL_STATUS:VIEW",
    "hcm.operations.payroll.approve": "ACTION.HRIS_PAYROLL_APPROVE:APPROVE",
    "hcm.operations.payroll.release": "ACTION.HRIS_PAYROLL_RELEASE:EXECUTE",
}
PREDICATES = {
    "predicate.hcm-payroll-run-authority.v1",
    "predicate.hcm-payroll-run-sod.v1",
    "predicate.hcm-payroll-command-receipt-authority.v1",
}
ROUTES = {
    "route.hcm.operations.payroll-run-calculate.action": (
        "POST", "/api/payroll/v1/runs/{runId}/attempts",
        "/v1/runs/{runId}/attempts",
    ),
    "route.hcm.operations.payroll-run.data": (
        "GET", "/api/payroll/v1/runs/{runId}", "/v1/runs/{runId}",
    ),
    "route.hcm.operations.payroll-run-approve.action": (
        "POST", "/api/payroll/v1/runs/{runId}/approvals",
        "/v1/runs/{runId}/approvals",
    ),
    "route.hcm.operations.payroll-run-finalize.action": (
        "POST", "/api/payroll/v1/runs/{runId}/finalize",
        "/v1/runs/{runId}/finalize",
    ),
    "route.hcm.operations.payroll-command-receipt.data": (
        "GET", "/api/payroll/v1/command-receipts/{receiptId}",
        "/v1/command-receipts/{receiptId}",
    ),
}


class ProductAuthorizationV41Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v40 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v40.json").read_text()
        )
        cls.v41 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v41.json").read_text()
        )
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v41["routes"]
        }

    def test_v41_is_the_exact_append_only_payroll_run_bridge(self) -> None:
        self.assertEqual(41, self.v41["version"])
        self.assertEqual(V41_SHA256, self.v41["checksum"])
        self.assertEqual("DRAFT", self.v41["bundleStatus"])
        self.assertEqual((252, 24, 17, 64, 1017), (
            len(self.v41["capabilities"]),
            len(self.v41["accessPolicies"]),
            len(self.v41["entitlementExpressions"]),
            len(self.v41["predicatePolicies"]),
            len(self.v41["routes"]),
        ))
        for section, identity, expected in (
            ("capabilities", "contractKey", set(CAPABILITIES)),
            ("predicatePolicies", "predicatePolicyKey", PREDICATES),
            ("routes", "routeContractKey", set(ROUTES)),
        ):
            previous = {item[identity] for item in self.v40[section]}
            current = {item[identity] for item in self.v41[section]}
            self.assertEqual(expected, current - previous)
            self.assertTrue(previous.issubset(current))

    def test_five_peps_have_exact_owner_and_gateway_bindings(self) -> None:
        for route_key, (method, public_path, owner_path) in ROUTES.items():
            route = self.routes[route_key]
            with self.subTest(route=route_key):
                self.assertEqual("hcm.operations", route["navigationContextId"])
                self.assertEqual("hcm.operations", route["subject"]["surfaceKey"])
                self.assertEqual({
                    "bindingKey": route_key + ".binding.01",
                    "method": method,
                    "path": public_path,
                    "pathParameterConstraints": {},
                }, route["gatewayApiBindings"][0])
                self.assertEqual({
                    "bindingKey": route_key + ".binding.01",
                    "method": method,
                    "path": owner_path,
                    "pathParameterConstraints": {},
                    "serviceKey": "payroll",
                }, route["servicePepBindings"][0])

    def test_only_approve_and_finalize_require_step_up_and_maker_checker(self) -> None:
        step_up_routes = {
            key for key in ROUTES if self.routes[key].get("stepUpCommandBindings")
        }
        self.assertEqual({
            "route.hcm.operations.payroll-run-approve.action",
            "route.hcm.operations.payroll-run-finalize.action",
        }, step_up_routes)
        capabilities = {
            item["contractKey"]: item for item in self.v41["capabilities"]
        }
        self.assertEqual(
            "RUN_MAKER_NE_APPROVER",
            capabilities["hcm.operations.payroll.approve"]["sodPolicyId"],
        )
        self.assertEqual(
            "RUN_MAKER_NE_FINALIZER",
            capabilities["hcm.operations.payroll.release"]["sodPolicyId"],
        )
        for key in step_up_routes:
            binding = self.routes[key]["stepUpCommandBindings"]
            self.assertEqual(1, len(binding))
            self.assertEqual("PAYROLL_RUN", binding[0]["targetType"])
            self.assertEqual("runId", binding[0]["targetIdPathParameter"])
            self.assertEqual("COMMAND_HEADER", binding[0]["expectedObjectVersionSource"])
            self.assertEqual("payroll", binding[0]["ownerServiceKey"])
            self.assertEqual("dwp-payroll-server", binding[0]["audience"])

    def test_alias_seed_index_and_payroll_projection_are_v41(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        auth_alias = json.loads(
            (AUTH_SEEDS / "product-surfaces-v1.generated.json").read_text()
        )
        index = json.loads(
            (CONTRACTS / "product-surfaces-v1.index.json").read_text()
        )
        projection = json.loads(PAYROLL_PROJECTION.read_text())

        self.assertEqual(self.v41, alias)
        self.assertEqual(self.v41, auth_alias)
        self.assertEqual(41, index["latestVersion"])
        self.assertEqual(V41_SHA256, index["latestChecksum"])
        self.assertEqual(41, projection["registryRef"]["version"])
        self.assertEqual(V41_SHA256, projection["registryRef"]["sha256"])
        self.assertEqual("payroll", projection["ownerServiceKey"])
        self.assertTrue(set(ROUTES).issubset({
            route["routeContractKey"] for route in projection["routes"]
        }))


if __name__ == "__main__":
    unittest.main()
