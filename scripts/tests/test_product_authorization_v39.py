import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "product-authorization"
AUTH_SEEDS = ROOT / "dwp-auth-server" / "src" / "main" / "resources" / "product-authorization"
PEOPLE_PROJECTION = (
    ROOT
    / "dwp-people-server"
    / "src"
    / "main"
    / "resources"
    / "product-authorization"
    / "hcm-people-pep-v39.generated.json"
)
V39_SHA256 = "86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c"

V39_ROUTES = {
    "route.hcm.operations.compensation-planning.page",
    "route.hcm.operations.compensation-cycle-detail.data",
    "route.hcm.operations.compensation-proposals.data",
    "route.hcm.operations.compensation-proposal-detail.data",
    "route.hcm.operations.compensation-cycle-create.action",
    "route.hcm.operations.compensation-cycle-revise.action",
    "route.hcm.operations.compensation-budget-allocate.action",
    "route.hcm.operations.compensation-proposal-upsert.action",
    "route.hcm.operations.compensation-proposal-submit.action",
    "route.hcm.operations.compensation-plan-approve.action",
    "route.hcm.operations.compensation-snapshot-publish.action",
    "route.hcm.operations.compensation-cycle-cancel.action",
}

CAPABILITIES = {
    "hcm.compensation.plan.view": "DATA.HRIS_COMPENSATION_PLAN:VIEW",
    "hcm.compensation.plan.propose": "DATA.HRIS_COMPENSATION_PLAN:UPDATE",
    "hcm.compensation.plan.approve": "ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE",
}
NAVIGATION_POLICIES = {
    "hcm.operations-access.v1",
    "hcm.operations-overview-read.v1",
}


class ProductAuthorizationV39Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v38 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v38.json").read_text()
        )
        cls.v39 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v39.json").read_text()
        )

    def test_v39_is_the_exact_append_only_compensation_planning_release(self) -> None:
        self.assertEqual(39, self.v39["version"])
        self.assertEqual(V39_SHA256, self.v39["checksum"])
        self.assertEqual("DRAFT", self.v39["bundleStatus"])
        self.assertEqual(245, len(self.v39["capabilities"]))
        self.assertEqual(24, len(self.v39["accessPolicies"]))
        self.assertEqual(17, len(self.v39["entitlementExpressions"]))
        self.assertEqual(60, len(self.v39["predicatePolicies"]))
        self.assertEqual(1003, len(self.v39["routes"]))

        for collection, identity in (
            ("capabilities", "contractKey"),
            ("predicatePolicies", "predicatePolicyKey"),
            ("routes", "routeContractKey"),
        ):
            previous = {item[identity] for item in self.v38[collection]}
            current = {item[identity] for item in self.v39[collection]}
            expected = {
                "capabilities": set(CAPABILITIES),
                "predicatePolicies": {
                    "predicate.hcm-compensation-planning-authority.v1"
                },
                "routes": V39_ROUTES,
            }[collection]
            self.assertEqual(expected, current - previous)
            self.assertTrue(previous.issubset(current))

        previous_policies = {
            item["accessPolicyKey"]: item for item in self.v38["accessPolicies"]
        }
        current_policies = {
            item["accessPolicyKey"]: item for item in self.v39["accessPolicies"]
        }
        for policy_key, current in current_policies.items():
            previous = previous_policies[policy_key]
            if policy_key not in NAVIGATION_POLICIES:
                self.assertEqual(previous, current)
                continue
            normalized = json.loads(json.dumps(current))
            normal = next(
                branch for branch in normalized["modeBranches"]
                if branch["activeAccessMode"] == "NORMAL"
            )
            self.assertEqual(V39_SHA256, self.v39["checksum"])
            self.assertEqual("hcm.compensation.plan.view", normal["capabilityContractKeys"][-1])
            normal["capabilityContractKeys"].pop()
            self.assertEqual(previous, normalized)
        self.assertEqual(
            self.v38["entitlementExpressions"],
            self.v39["entitlementExpressions"],
        )

    def test_v39_routes_are_exact_and_only_approve_publish_require_step_up(self) -> None:
        capabilities = {
            item["contractKey"]: item for item in self.v39["capabilities"]
        }
        for contract_key, resolved_code in CAPABILITIES.items():
            with self.subTest(capability=contract_key):
                self.assertEqual(
                    resolved_code, capabilities[contract_key]["resolvedCapabilityCode"]
                )

        routes = {
            item["routeContractKey"]: item for item in self.v39["routes"]
            if item["routeContractKey"] in V39_ROUTES
        }
        self.assertEqual(V39_ROUTES, set(routes))
        self.assertTrue(all(route["lifecycleState"] == "ACTIVE" for route in routes.values()))
        self.assertTrue(
            all(
                route["accessProfiles"][0]["predicatePolicyKeys"]
                == ["predicate.hcm-compensation-planning-authority.v1"]
                for route in routes.values()
            )
        )
        high_risk = {
            key for key, route in routes.items() if route.get("stepUpCommandBindings")
        }
        self.assertEqual(
            {
                "route.hcm.operations.compensation-plan-approve.action",
                "route.hcm.operations.compensation-snapshot-publish.action",
            },
            high_risk,
        )
        self.assertTrue(
            all(
                capabilities[
                    route["accessProfiles"][0]["requiredAccess"][
                        "capabilityContractKey"
                    ]
                ]["riskTier"]
                == "HIGH"
                for key, route in routes.items()
                if key in high_risk
            )
        )

    def test_v39_auth_seed_and_people_projection_remain_immutably_pinned(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        auth_seed = json.loads(
            (AUTH_SEEDS / "product-surfaces-v1.bundle-v39.generated.json").read_text()
        )
        projection = json.loads(PEOPLE_PROJECTION.read_text())

        self.assertEqual(40, alias["version"])
        self.assertEqual(self.v39, auth_seed)
        self.assertEqual(40, index["latestVersion"])
        v39_index = next(value for value in index["versions"] if value["version"] == 39)
        self.assertEqual(V39_SHA256, v39_index["checksum"])
        self.assertEqual(39, projection["registryRef"]["version"])
        self.assertEqual(V39_SHA256, projection["registryRef"]["sha256"])
        self.assertEqual("people", projection["ownerServiceKey"])
        self.assertEqual(
            V39_ROUTES,
            {
                route["routeContractKey"]
                for route in projection["routes"]
                if route["routeContractKey"] in V39_ROUTES
            },
        )


if __name__ == "__main__":
    unittest.main()
