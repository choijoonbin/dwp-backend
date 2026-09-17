from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v24_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV24Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v21 = cls.snapshots[20]
        cls.v22 = cls.snapshots[21]
        cls.v23 = cls.snapshots[22]
        cls.v24 = cls.snapshots[23]
        cls.routes = {
            route["routeContractKey"]: route for route in cls.v23["routes"]
        }

    def test_v22_preserves_immutable_v21_and_contains_the_complete_extension(self) -> None:
        self.assertEqual(21, self.v21["version"])
        self.assertEqual(
            "4cd1732df91d197cc47fca94699b0fb702ab1f6f2c557d3d17ce0e069d65af85",
            self.v21["checksum"],
        )
        self.assertEqual(709, len(self.v21["routes"]))
        self.assertEqual(22, self.v22["version"])
        self.assertEqual(
            "1629b75f62c7bb524dc70faaecac73499b9f9b0fb126ab38221b6e4773f35ede",
            self.v22["checksum"],
        )
        self.assertEqual(749, len(self.v22["routes"]))
        added = {route["routeContractKey"] for route in self.v22["routes"]} - {
            route["routeContractKey"] for route in self.v21["routes"]
        }
        self.assertEqual(40, len(added))
        self.assertEqual(
            {
                "route.dwaion.management.control-plane-command.action",
                "route.dwaion.management.control-plane-commands.data",
                "route.dwaion.management.control-plane-snapshots.data",
                "route.dwaion.management.models-routing.page",
                "route.dwaion.work.activity.page",
                "route.dwaion.work.agents.page",
                "route.dwaion.work.artifact-collaboration-access-request.action",
                "route.dwaion.work.artifact-collaboration-edit.action",
                "route.dwaion.work.artifact-collaboration-members.action",
                "route.dwaion.work.artifact-collaboration-preflight.action",
                "route.dwaion.work.artifact-collaboration-resolve.action",
                "route.dwaion.work.artifact-collaboration-share.action",
                "route.dwaion.work.artifact-collaboration-workspace.action",
                "route.dwaion.work.artifact-collaboration.data",
                "route.dwaion.work.attachment-create.action",
                "route.dwaion.work.attachment-delete.action",
                "route.dwaion.work.attachments.data",
                "route.dwaion.work.conversation-detail.page",
                "route.dwaion.work.conversations.page",
                "route.dwaion.work.new.page",
                "route.dwaion.work.personal-deletion-retry.action",
                "route.dwaion.work.personal-deletions.data",
                "route.dwaion.work.proposal-handoff.action",
                "route.dwaion.work.proposal-handoff.data",
                "route.dwaion.work.research-deliveries.data",
                "route.dwaion.work.research-output.action",
                "route.dwaion.work.research-plan-create.action",
                "route.dwaion.work.research-plan-update.action",
                "route.dwaion.work.research-plans.data",
                "route.dwaion.work.research-run-command.action",
                "route.dwaion.work.research-run-execute.action",
                "route.dwaion.work.research-run-start.action",
                "route.dwaion.work.research-runs.data",
                "route.dwaion.work.routine-activation.action",
                "route.dwaion.work.routine-execution.data",
                "route.dwaion.work.routine-run-command.action",
                "route.dwaion.work.routine-run-trigger.action",
                "route.workplace.work.booking-intent-holds-release.action",
                "route.workplace.work.resource-favorite-set.action",
                "route.workplace.work.resource-favorites-get.data",
            },
            added,
        )

    def test_v23_preserves_v22_and_closes_screens_16_20_and_21(self) -> None:
        self.assertEqual(23, self.v23["version"])
        self.assertEqual(
            "4687f384ce79faacf4e2b1eb9c6b8eca3f2109dd62a88ac01c5deda22e1925c7",
            self.v23["checksum"],
        )
        self.assertEqual(768, len(self.v23["routes"]))
        added = set(self.routes) - {
            route["routeContractKey"] for route in self.v22["routes"]
        }
        self.assertEqual(
            {
                "route.workplace.management.safety-emergency-contacts-get.data",
                "route.workplace.management.safety-emergency-contacts-by-contact-id-get.data",
                "route.workplace.management.safety-emergency-contacts-by-contact-id-put.action",
                "route.workplace.management.safety-incidents-by-incident-id-emergency-handoff-previews-by-preview-id-get.data",
                "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-by-command-id-get.data",
                "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-by-command-id-reconcile-post.action",
                "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-post.action",
                "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-preview-post.action",
                "route.workplace.management.space-planning-report-content.data",
                "route.workplace.management.space-planning-report-execute.action",
                "route.workplace.management.space-planning-report-preview.data",
                "route.workplace.management.space-planning-report-receipt.data",
                "route.workplace.work.resource-command-context.data",
                "route.workplace.work.resource-command-execute.action",
                "route.workplace.work.resource-command-preview-get.data",
                "route.workplace.work.resource-command-preview.action",
                "route.workplace.work.resource-command-receipt.data",
                "route.workplace.work.resource-command-reconcile.action",
                "route.workplace.work.safety-incidents-by-incident-id-emergency-contacts-get.data",
            },
            added,
        )

    def test_v24_preserves_v23_and_closes_the_dwaion_extension(self) -> None:
        self.assertEqual(24, self.v24["version"])
        self.assertEqual(
            "be3db891d27cd0b94aa88ac706d9bc87d4b991c9f9d8e505e26b296647728b84",
            self.v24["checksum"],
        )
        self.assertEqual(776, len(self.v24["routes"]))
        added = {
            route["routeContractKey"] for route in self.v24["routes"]
        } - set(self.routes)
        self.assertEqual(
            {
                "route.dwaion.work.research-raw-download.data",
                "route.dwaion.work.research-receipt-download.data",
                "route.dwaion.work.research-audit-download.data",
                "route.dwaion.work.routine-evidence.data",
                "route.dwaion.work.routine-webhook-trigger.action",
                "route.dwaion.work.routine-version-rollback.action",
                "route.dwaion.work.artifact-collaboration-comments.data",
                "route.dwaion.work.artifact-collaboration-comments.action",
            },
            added,
        )
        elevated = {
            "route.workplace.work.resource-command-execute.action",
            "route.workplace.work.resource-command-reconcile.action",
            "route.workplace.management.space-planning-report-execute.action",
            "route.workplace.management.space-planning-report-content.data",
            "route.workplace.management.safety-emergency-contacts-by-contact-id-put.action",
            "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-preview-post.action",
            "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-post.action",
            "route.workplace.management.safety-incidents-by-incident-id-emergency-handoffs-by-command-id-reconcile-post.action",
        }
        for key in elevated:
            self.assertEqual(["ELEVATED"], self.routes[key]["accessProfiles"][0]["activeAccessModes"])

    def test_ai_runtime_bindings_and_permissions_are_exact(self) -> None:
        expected = {
            "route.dwaion.management.ai-control.page": (
                "DATA", "GET", "", {"dwaion.safety.read", "dwaion.safety.manage"}
            ),
            "route.dwaion.management.ai-control-bootstrap.action": (
                "ACTION", "POST", "/bootstrap",
                {"dwaion.safety.update", "dwaion.safety.manage"},
            ),
            "route.dwaion.management.ai-control-update.action": (
                "ACTION", "PUT", "/policy",
                {"dwaion.safety.update", "dwaion.safety.manage"},
            ),
            "route.dwaion.management.ai-control-emergency.action": (
                "ACTION", "POST", "/emergency", {"dwaion.safety.manage"},
            ),
        }
        for key, (kind, method, suffix, capabilities) in expected.items():
            route = self.routes[key]
            self.assertEqual(kind, route["routeKind"])
            self.assertEqual("dwaion.management", route["subject"]["surfaceKey"])
            self.assertEqual(
                (method, f"/api/agent/v1/admin/ai-control{suffix}"),
                (route["gatewayApiBindings"][0]["method"],
                 route["gatewayApiBindings"][0]["path"]),
            )
            self.assertEqual(
                (method, f"/v1/admin/ai-control{suffix}"),
                (route["servicePepBindings"][0]["method"],
                 route["servicePepBindings"][0]["path"]),
            )
            profile = route["accessProfiles"][0]
            self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
            access = profile["requiredAccess"]
            actual = (
                set(access["capabilityContractKeys"])
                if access["type"] == "CAPABILITY_EXPRESSION"
                else {access["capabilityContractKey"]}
            )
            self.assertEqual(capabilities, actual)
        manage = next(
            capability for capability in self.v22["capabilities"]
            if capability["contractKey"] == "dwaion.safety.manage"
        )
        self.assertEqual("HIGH", manage["riskTier"])
        self.assertIn(
            "route.dwaion.management.ai-control-emergency.action",
            manage["routeContractKeys"],
        )

    def test_workplace_extensions_have_exact_authority_and_step_up_boundaries(self) -> None:
        access_read = {
            "route.workplace.work.access-pass-context-get.data",
            "route.workplace.work.access-pass-audit-events-get.data",
            "route.workplace.work.access-pass-command-get.data",
        }
        for key in access_read:
            route = self.routes[key]
            profile = route["accessProfiles"][0]
            self.assertEqual("DATA", route["routeKind"])
            self.assertEqual(["NORMAL", "ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                {"type": "POLICY", "accessPolicyKey": "workplace.work-access.v1"},
                profile["requiredAccess"],
            )
            self.assertEqual(["SELF"], profile["targetBindingKinds"])
            self.assertEqual(["predicate.workplace-resource.v1"], profile["predicatePolicyKeys"])

        preview = self.routes["route.workplace.work.access-pass-preview.action"]
        execute = self.routes["route.workplace.work.access-pass-execute.action"]
        self.assertEqual(["NORMAL", "ELEVATED"], preview["accessProfiles"][0]["activeAccessModes"])
        self.assertEqual(["ELEVATED"], execute["accessProfiles"][0]["activeAccessModes"])
        self.assertEqual(
            "workplace.booking.update",
            execute["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )

        closure_actions = {
            "route.workplace.management.facility-closure-impact-execute.action",
            "route.workplace.management.facility-closure-notifications-reconcile.action",
            "route.workplace.management.facility-closure-notifications-retry.action",
        }
        for key in closure_actions:
            profile = self.routes[key]["accessProfiles"][0]
            self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                "workplace.locations.update",
                profile["requiredAccess"]["capabilityContractKey"],
            )

        exception_actions = {
            "route.workplace.management.exception-recovery-preview.action",
            "route.workplace.management.exception-recovery.action",
            "route.workplace.management.exception-export-preview.action",
            "route.workplace.management.exception-export.action",
        }
        for key in exception_actions:
            profile = self.routes[key]["accessProfiles"][0]
            self.assertEqual(["ELEVATED"], profile["activeAccessModes"])
            self.assertEqual(
                "workplace.operations.manage",
                profile["requiredAccess"]["capabilityContractKey"],
            )
        export_content = self.routes[
            "route.workplace.management.exception-export-content.data"
        ]["accessProfiles"][0]
        self.assertEqual(["ELEVATED"], export_content["activeAccessModes"])
        self.assertEqual(
            "workplace.operations.manage",
            export_content["requiredAccess"]["capabilityContractKey"],
        )

    def test_dwaion_v22_bindings_close_user_and_control_plane_routes(self) -> None:
        attachments = self.routes["route.dwaion.work.attachments.data"]
        self.assertEqual(
            {
                ("GET", "/v1/attachments"),
                ("GET", "/v1/attachments/capabilities"),
                ("GET", "/v1/attachments/{attachmentId}"),
                ("GET", "/v1/attachments/{attachmentId}/evidence"),
            },
            {(item["method"], item["path"]) for item in attachments["servicePepBindings"]},
        )
        research = self.routes["route.dwaion.work.research-plans.data"]
        self.assertEqual(
            {
                ("GET", "/v1/research/plans/{planId}"),
                ("GET", "/v1/research/capabilities"),
            },
            {(item["method"], item["path"]) for item in research["servicePepBindings"]},
        )
        command = self.routes["route.dwaion.management.control-plane-command.action"]
        self.assertEqual(
            "dwaion.control-plane.execute",
            command["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )
        self.assertEqual(
            {
                "/v1/admin/control-plane/commands",
                "/v1/admin/control-plane/commands/{commandId}/decision",
                "/v1/admin/control-plane/commands/{commandId}/cancel",
                "/v1/admin/control-plane/commands/{commandId}/retry",
                "/v1/admin/control-plane/commands/{commandId}/rollback",
            },
            {item["path"] for item in command["servicePepBindings"]},
        )
        capability = next(
            item for item in self.v22["capabilities"]
            if item["contractKey"] == "dwaion.control-plane.execute"
        )
        self.assertEqual("HIGH", capability["riskTier"])
        self.assertEqual("SOD-DWAION-CONTROL-PLANE-V1", capability["sodPolicyId"])

    def test_latest_alias_and_index_point_to_v24(self) -> None:
        alias = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.json")
            .read_text()
        )
        index = json.loads(
            (ROOT / "contracts/product-authorization/product-surfaces-v1.index.json")
            .read_text()
        )
        self.assertEqual(self.v24, alias)
        self.assertEqual(24, index["latestVersion"])
        self.assertEqual(self.v24["checksum"], index["latestChecksum"])


if __name__ == "__main__":
    unittest.main()
