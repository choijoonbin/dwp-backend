from __future__ import annotations

import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v34_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


CAPABILITIES = {
    "hcm.operations.assignment-proposal.create",
    "hcm.operations.assignment-proposal.validate",
    "hcm.operations.assignment-proposal.submit",
    "hcm.operations.assignment-proposal.cancel",
}
ROUTES = {
    "route.hcm.operations.assignment-detail.data": (
        "GET",
        "/api/people/v1/workforce/assignments/{assignmentId}",
        "/v1/workforce/assignments/{assignmentId}",
    ),
    "route.hcm.operations.assignment-timeline.data": (
        "GET",
        "/api/people/v1/workforce/assignments/{assignmentId}/timeline",
        "/v1/workforce/assignments/{assignmentId}/timeline",
    ),
    "route.hcm.operations.assignment-proposal-detail.data": (
        "GET",
        "/api/people/v1/workforce/assignment-proposals/{proposalId}",
        "/v1/workforce/assignment-proposals/{proposalId}",
    ),
    "route.hcm.operations.assignment-proposal-create.action": (
        "POST",
        "/api/people/v1/workforce/assignment-proposals",
        "/v1/workforce/assignment-proposals",
    ),
    "route.hcm.operations.assignment-proposal-validate.action": (
        "POST",
        "/api/people/v1/workforce/assignment-proposals/{proposalId}/validate",
        "/v1/workforce/assignment-proposals/{proposalId}/validate",
    ),
    "route.hcm.operations.assignment-proposal-submit.action": (
        "POST",
        "/api/people/v1/workforce/assignment-proposals/{proposalId}/submit",
        "/v1/workforce/assignment-proposals/{proposalId}/submit",
    ),
    "route.hcm.operations.assignment-proposal-cancel.action": (
        "POST",
        "/api/people/v1/workforce/assignment-proposals/{proposalId}/cancel",
        "/v1/workforce/assignment-proposals/{proposalId}/cancel",
    ),
}


class ProductAuthorizationV34Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v33 = next(value for value in snapshots if value["version"] == 33)
        cls.v34 = next(value for value in snapshots if value["version"] == 34)
        cls.capabilities = {
            value["contractKey"]: value for value in cls.v34["capabilities"]
        }
        cls.routes = {
            value["routeContractKey"]: value for value in cls.v34["routes"]
        }

    def test_preserves_v33_and_adds_only_assignment_proposal_descriptors(self) -> None:
        self.assertEqual(
            "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639",
            self.v33["checksum"],
        )
        self.assertEqual(
            "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b",
            self.v34["checksum"],
        )
        prior_capabilities = {
            value["contractKey"] for value in self.v33["capabilities"]
        }
        prior_routes = {value["routeContractKey"] for value in self.v33["routes"]}
        self.assertEqual(CAPABILITIES, set(self.capabilities) - prior_capabilities)
        self.assertEqual(set(ROUTES), set(self.routes) - prior_routes)
        self.assertEqual(
            (223, 24, 17, 54, 952),
            (
                len(self.v34["capabilities"]),
                len(self.v34["accessPolicies"]),
                len(self.v34["entitlementExpressions"]),
                len(self.v34["predicatePolicies"]),
                len(self.v34["routes"]),
            ),
        )

    def test_capabilities_have_exact_risk_and_activation_closure(self) -> None:
        for key in CAPABILITIES:
            capability = self.capabilities[key]
            self.assertEqual("DATA.WORKFORCE:MANAGE", capability["resolvedCapabilityCode"])
            self.assertEqual("PERMISSION_AND_RELATIONSHIP", capability["authorityMode"])
            self.assertEqual("WORKFORCE_TARGET_POPULATION", capability["scopeResolver"])
            self.assertIsNone(capability["sodPolicyId"])
            if key.endswith(".submit"):
                self.assertEqual("HIGH", capability["riskTier"])
                self.assertEqual("STEPUP-MGMT-HIGH-V1", capability["activationPolicy"])
            else:
                self.assertEqual("MEDIUM", capability["riskTier"])
                self.assertIsNone(capability["activationPolicy"])

    def test_routes_match_backend_paths_and_authority_closure(self) -> None:
        reads = {
            "route.hcm.operations.assignment-detail.data",
            "route.hcm.operations.assignment-timeline.data",
            "route.hcm.operations.assignment-proposal-detail.data",
        }
        expected_commands = {
            "route.hcm.operations.assignment-proposal-create.action":
                "hcm.operations.assignment-proposal.create",
            "route.hcm.operations.assignment-proposal-validate.action":
                "hcm.operations.assignment-proposal.validate",
            "route.hcm.operations.assignment-proposal-submit.action":
                "hcm.operations.assignment-proposal.submit",
            "route.hcm.operations.assignment-proposal-cancel.action":
                "hcm.operations.assignment-proposal.cancel",
        }
        for key, (method, public_path, service_path) in ROUTES.items():
            route = self.routes[key]
            self.assertEqual(method, route["gatewayApiBindings"][0]["method"])
            self.assertEqual(public_path, route["gatewayApiBindings"][0]["path"])
            self.assertEqual(service_path, route["servicePepBindings"][0]["path"])
            self.assertEqual("people", route["servicePepBindings"][0]["serviceKey"])
            profile = route["accessProfiles"][0]
            self.assertIn("predicate.hcm-workforce-visible-person.v1",
                          profile["predicatePolicyKeys"])
            if key in reads:
                self.assertEqual("DATA", route["routeKind"])
                self.assertEqual(
                    "hcm.operations.workforce.read",
                    profile["requiredAccess"]["capabilityContractKey"],
                )
                self.assertEqual(["TARGET_POPULATION"], profile["targetBindingKinds"])
                self.assertNotIn(
                    "predicate.people.object-version.v1",
                    profile["predicatePolicyKeys"],
                )
            else:
                self.assertEqual("ACTION", route["routeKind"])
                self.assertEqual(
                    expected_commands[key],
                    profile["requiredAccess"]["capabilityContractKey"],
                )
                self.assertEqual(
                    ["OBJECT", "TARGET_POPULATION"],
                    profile["targetBindingKinds"],
                )
                self.assertIn(
                    "predicate.people.object-version.v1",
                    profile["predicatePolicyKeys"],
                )

    def test_submit_has_exact_step_up_descriptor(self) -> None:
        route = self.routes[
            "route.hcm.operations.assignment-proposal-submit.action"
        ]
        self.assertEqual(
            [
                {
                    "bindingKey": (
                        "route.hcm.operations.assignment-proposal-submit.action.binding.01"
                    ),
                    "targetType": "ASSIGNMENT_PROPOSAL",
                    "targetIdPathParameter": "proposalId",
                    "expectedObjectVersionSource": "COMMAND_HEADER",
                    "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
                    "ownerServiceKey": "people",
                    "audience": "dwp-people-server",
                }
            ],
            route["stepUpCommandBindings"],
        )

    def test_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v34).encode()
        self.assertEqual(
            rendered, GENERATOR.VERSIONED_CONTRACT_OUTPUTS[34].read_bytes()
        )
        self.assertEqual(
            rendered, GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[34].read_bytes()
        )


if __name__ == "__main__":
    unittest.main()
