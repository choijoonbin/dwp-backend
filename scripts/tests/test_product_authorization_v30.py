from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v30_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


EXPECTED_ROUTE_KEYS = {
    "route.admin.mail.connection-diagnostics.action",
    "route.admin.mail.connection-sync.action",
    "route.admin.mail.connection-test-send.action",
    "route.admin.mail.retention.hold-update.action",
    "route.admin.mail.shared-inbox-update.action",
    "route.admin.mail.writing-asset-create.action",
    "route.dwaion.work.artifact-collaboration-remediation.action",
    "route.dwaion.work.attachment-audit-report.action",
    "route.dwaion.work.attachment-audit-report.data",
    "route.dwaion.work.attachment-detach.action",
    "route.dwaion.work.personal-deletion-evidence.action",
    "route.dwaion.work.personal-deletion-evidence-download.data",
    "route.dwaion.work.personal-deletion-evidence.data",
    "route.dwaion.work.proposal-handoff-draft.action",
    "route.dwaion.work.proposal-handoff-draft.data",
    "route.dwaion.work.research-pdf-download.data",
    "route.dwaion.work.research-recovery.action",
    "route.dwaion.work.routine-advanced-approval.action",
    "route.dwaion.work.routine-advanced.action",
    "route.dwaion.work.routine-advanced.data",
    "route.dwaion.work.routine-advanced-pending-approvals.data",
    "route.mail.work.attachment-create.action",
    "route.mail.work.contact-create.action",
    "route.mail.work.contact-delete.action",
    "route.mail.work.contact-group-create.action",
    "route.mail.work.contact-group-delete.action",
    "route.mail.work.contact-group-members-update.action",
    "route.mail.work.contact-group-update.action",
    "route.mail.work.contact-update.action",
    "route.mail.work.delivery-cancel.action",
    "route.mail.work.delivery-command-retry.action",
    "route.mail.work.delivery-reconcile.action",
    "route.mail.work.delivery-reschedule.action",
    "route.mail.work.folder-archive.action",
    "route.mail.work.folder-create.action",
    "route.mail.work.folder-update.action",
    "route.mail.work.follow-up-create.action",
    "route.mail.work.follow-up-delete.action",
    "route.mail.work.follow-up-update.action",
    "route.mail.work.preferences-update.action",
    "route.mail.work.proposal-handoff-cancel.action",
    "route.mail.work.proposal-update.action",
    "route.mail.work.rule-archive.action",
    "route.mail.work.rule-backfill.action",
    "route.mail.work.rule-create.action",
    "route.mail.work.rule-order.action",
    "route.mail.work.rule-run.action",
    "route.mail.work.rule-update.action",
    "route.mail.work.saved-view-create.action",
    "route.mail.work.saved-view-delete.action",
    "route.mail.work.saved-view-update.action",
    "route.mail.work.signature-create.action",
    "route.mail.work.signature-delete.action",
    "route.mail.work.signature-update.action",
    "route.mail.work.template-create.action",
    "route.mail.work.template-delete.action",
    "route.mail.work.template-update.action",
    "route.mail.work.thread-action.action",
    "route.mail.work.thread-assignment.action",
    "route.mail.work.thread-comment.action",
    "route.mail.work.thread-snooze.action",
}


class ProductAuthorizationV30Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        by_version = {snapshot["version"]: snapshot for snapshot in cls.snapshots}
        cls.v29 = by_version[29]
        cls.v30 = by_version[30]
        prior = {route["routeContractKey"] for route in cls.v29["routes"]}
        cls.added = [
            route for route in cls.v30["routes"]
            if route["routeContractKey"] not in prior
        ]

    def test_v30_is_the_exact_append_only_dwaion_and_mail_completion_wave(self) -> None:
        self.assertEqual(30, self.v30["version"])
        self.assertEqual(
            "7c437bd768225db7dfe1c2491bcdb6ff256a2376946bab6a4ba4a62a7f31ce80",
            self.v30["checksum"],
        )
        self.assertEqual(888, len(self.v30["routes"]))
        self.assertEqual(205, len(self.v30["capabilities"]))
        self.assertEqual(
            EXPECTED_ROUTE_KEYS,
            {route["routeContractKey"] for route in self.added},
        )
        self.assertEqual(61, len(self.added))
        self.assertEqual(7, sum(route["routeKind"] == "DATA" for route in self.added))
        self.assertEqual(54, sum(route["routeKind"] == "ACTION" for route in self.added))

    def test_v30_closes_every_gateway_and_owner_binding_pair(self) -> None:
        method_paths = {
            (binding["method"], binding["path"])
            for route in self.added
            for binding in route["servicePepBindings"]
        }
        self.assertEqual(64, len(method_paths))
        self.assertIn(
            ("GET", "/v1/research/runs/{runId}/downloads/pdf"),
            method_paths,
        )
        self.assertIn(
            ("GET", "/v1/routines/advanced-commands/pending-approvals"),
            method_paths,
        )
        self.assertIn(
            (
                "POST",
                "/v1/personal-data/deletions/{deletionJobId}/evidence-actions/{action}",
            ),
            method_paths,
        )
        self.assertIn(("POST", "/v1/mail/organization/folders"), method_paths)
        self.assertIn(("PUT", "/v1/mail/organization/rules/order"), method_paths)
        self.assertIn(
            ("POST", "/v1/admin/mail/connections/{connectionId}/test-send"),
            method_paths,
        )
        for route in self.added:
            gateway = {
                (binding["bindingKey"], binding["method"], binding["path"])
                for binding in route["gatewayApiBindings"]
            }
            owner = {
                (
                    binding["bindingKey"],
                    binding["method"],
                    {
                        "agent": "/api/agent",
                        "platform": "/api/platform",
                    }[binding["serviceKey"]] + binding["path"],
                )
                for binding in route["servicePepBindings"]
            }
            self.assertEqual(gateway, owner)

    def test_v30_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v30).encode()
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[30].read_bytes(),
        )
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[30].read_bytes(),
        )

    def test_v30_research_and_advanced_routines_require_ask_plus_domain_authority(self) -> None:
        capabilities = {
            item["contractKey"]: item for item in self.v30["capabilities"]
        }
        self.assertEqual(
            "APP.DWAION_RESEARCH:VIEW",
            capabilities["dwaion.work.research.read"]["resolvedCapabilityCode"],
        )
        self.assertEqual(
            "APP.DWAION_RESEARCH:MANAGE",
            capabilities["dwaion.work.research.manage"]["resolvedCapabilityCode"],
        )
        self.assertEqual(
            "APP.DWAION_ROUTINES:APPROVE",
            capabilities["dwaion.work.routines.approve"]["resolvedCapabilityCode"],
        )
        self.assertEqual(
            "APP_RESOURCE_SET:RS_DWAION",
            capabilities["dwaion.work.routines.approve"]["scopeResolver"],
        )
        research = [
            route for route in self.v30["routes"]
            if route["routeContractKey"].startswith("route.dwaion.work.research-")
        ]
        self.assertEqual(14, len(research))
        for route in research:
            capability = (
                "dwaion.work.research.read"
                if route["routeKind"] == "DATA"
                else "dwaion.work.research.manage"
            )
            self.assertTrue(all(
                profile["requiredAccess"] == {
                    "type": "CAPABILITY_EXPRESSION",
                    "mode": "ALL",
                    "capabilityContractKeys": ["dwaion.work.ask.execute", capability],
                }
                for profile in route["accessProfiles"]
            ))
        routes = {item["routeContractKey"]: item for item in self.v30["routes"]}
        for key in (
            "route.dwaion.work.routine-advanced-approval.action",
            "route.dwaion.work.routine-advanced-pending-approvals.data",
        ):
            profile = routes[key]["accessProfiles"][0]
            self.assertEqual(
                ["dwaion.work.ask.execute", "dwaion.work.routines.approve"],
                profile["requiredAccess"]["capabilityContractKeys"],
            )
            self.assertEqual(["CONFIG_SCOPE"], profile["targetBindingKinds"])
            self.assertEqual(
                ["predicate.dwaion-management-scope.v1"],
                profile["predicatePolicyKeys"],
            )

    def test_v30_fails_closed_if_research_authority_loses_ask(self) -> None:
        mutated = copy.deepcopy(self.v30)
        route = next(
            item for item in mutated["routes"]
            if item["routeContractKey"] == "route.dwaion.work.research-plans.data"
        )
        route["accessProfiles"][0]["requiredAccess"]["capabilityContractKeys"] = [
            "dwaion.work.research.read"
        ]

        with self.assertRaisesRegex(
            GENERATOR.ContractError,
            "must require APP.ASK plus its exact domain authority",
        ):
            GENERATOR._validate_v30_dwaion_authority_closure(mutated)

    def test_v30_fails_closed_if_checker_authority_falls_back_to_member_manage(self) -> None:
        mutated = copy.deepcopy(self.v30)
        route = next(
            item for item in mutated["routes"]
            if item["routeContractKey"]
            == "route.dwaion.work.routine-advanced-approval.action"
        )
        route["accessProfiles"][0]["requiredAccess"]["capabilityContractKeys"] = [
            "dwaion.work.ask.execute",
            "dwaion.work.routines.manage",
        ]

        with self.assertRaisesRegex(
            GENERATOR.ContractError,
            "must require APP.ASK plus its exact domain authority",
        ):
            GENERATOR._validate_v30_dwaion_authority_closure(mutated)


if __name__ == "__main__":
    unittest.main()
