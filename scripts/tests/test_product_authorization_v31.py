from __future__ import annotations

import importlib.util
import json
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v31_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

EXPECTED_ROUTE_KEYS = {
    "route.approvals.admin.audit-export-verifications.data",
    "route.approvals.admin.audit-export-verify.action",
    "route.approvals.admin.deployment-canary.data",
    "route.approvals.admin.deployment-canary-control.action",
    "route.approvals.admin.deployment-canary-telemetry.action",
    "route.approvals.admin.deployment-ledger.data",
    "route.approvals.admin.form-studio-field-update.action",
    "route.approvals.admin.form-studio-version-diff.data",
    "route.approvals.admin.incident-dead-letters.data",
    "route.approvals.admin.incident-report-create.action",
    "route.approvals.admin.incident-report.data",
    "route.approvals.admin.policy-delegation-audit.data",
    "route.approvals.admin.policy-delegation-kill-switch.action",
    "route.approvals.admin.policy-delegation-update.action",
    "route.approvals.admin.policy-governance-publish.action",
    "route.approvals.admin.policy-governance.data",
    "route.approvals.admin.policy-simulation.action",
    "route.approvals.admin.template-package-import.action",
    "route.approvals.admin.template-version-preview.data",
    "route.approvals.admin.workflow-studio-retire.action",
    "route.approvals.admin.workflow-studio-update.action",
    "route.approvals.admin.workflow-studio.data",
    "route.approvals.work.workflow-template.data",
}


def path_shape(path: str) -> str:
    return re.sub(r"\{[^}]+\}", "{}", path)


class ProductAuthorizationV31Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v30 = cls.snapshots[-2]
        cls.v31 = cls.snapshots[-1]
        prior = {route["routeContractKey"] for route in cls.v30["routes"]}
        cls.added = [
            route for route in cls.v31["routes"]
            if route["routeContractKey"] not in prior
        ]

    def test_v31_is_an_append_only_approval_runtime_closure(self) -> None:
        self.assertEqual(31, self.v31["version"])
        self.assertEqual(
            "be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8",
            self.v31["checksum"],
        )
        self.assertEqual(EXPECTED_ROUTE_KEYS, {
            route["routeContractKey"] for route in self.added
        })
        self.assertEqual(23, len(self.added))
        self.assertEqual(38, sum(
            len(route["servicePepBindings"]) for route in self.added
        ))
        self.assertEqual(11, sum(
            route["routeKind"] == "DATA" for route in self.added
        ))
        self.assertEqual(12, sum(
            route["routeKind"] == "ACTION" for route in self.added
        ))
        self.assertEqual(
            {item["contractKey"] for item in self.v30["capabilities"]},
            {item["contractKey"] for item in self.v31["capabilities"]},
        )

    def test_every_public_approval_operation_has_an_exact_v31_owner_binding(self) -> None:
        openapi = json.loads((
            ROOT / "contracts" / "openapi" / "approval.json"
        ).read_text())
        governed = {
            (binding["method"], path_shape(binding["path"]))
            for route in self.v31["routes"]
            for binding in route.get("servicePepBindings", [])
            if binding.get("serviceKey") == "approval"
        }
        missing = set()
        for path, item in openapi["paths"].items():
            if path.startswith("/internal/"):
                continue
            for method in item:
                if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                    continue
                operation = (method.upper(), path_shape(path))
                if operation not in governed:
                    missing.add(operation)
        self.assertEqual(set(), missing)

    def test_high_risk_commands_have_exact_step_up_binding_closure(self) -> None:
        capabilities = {
            capability["contractKey"]: capability
            for capability in self.v31["capabilities"]
        }
        for route in self.added:
            access = route["accessProfiles"][0]["requiredAccess"]
            capability = capabilities[access["capabilityContractKey"]]
            high_risk = capability["riskTier"] in {"HIGH", "CRITICAL"}
            step_up = route.get("stepUpCommandBindings", [])
            if high_risk:
                self.assertEqual(
                    {item["bindingKey"] for item in route["servicePepBindings"]},
                    {item["bindingKey"] for item in step_up},
                    route["routeContractKey"],
                )
            else:
                self.assertEqual([], step_up, route["routeContractKey"])

    def test_v31_generated_contract_and_auth_seed_are_byte_identical(self) -> None:
        rendered = GENERATOR.render(self.v31).encode()
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_CONTRACT_OUTPUTS[31].read_bytes(),
        )
        self.assertEqual(
            rendered,
            GENERATOR.VERSIONED_AUTH_SEED_OUTPUTS[31].read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
