from __future__ import annotations

import hashlib
import json
import runpy
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "hris-g5-owner-runtime-acceptance.py"
MODULE = runpy.run_path(str(SCRIPT))


class HrisG5OwnerRuntimeAcceptanceTest(unittest.TestCase):
    def test_plan_is_narrow_and_runs_every_owner_twice(self) -> None:
        result = subprocess.run(
            (sys.executable, str(SCRIPT), "--plan"),
            cwd=ROOT,
            check=True,
            text=True,
            capture_output=True,
        )
        plan = json.loads(result.stdout)

        self.assertFalse(plan["usesW1Runner"])
        self.assertEqual(
            ["people", "payroll", "time"],
            [service["service"] for service in plan["services"]],
        )
        self.assertEqual([2, 2, 2], [service["boots"] for service in plan["services"]])
        self.assertEqual(
            ["PEOPLE_FRESH", "STRICT_FRESH", "STRICT_FRESH"],
            [service["controlMode"] for service in plan["services"]],
        )

    def test_runtime_environment_binds_receipt_and_enabled_owner(self) -> None:
        reference = "dwp-migration-control-v2:" + "a" * 64
        passwords = MODULE["role_passwords"]()
        for service in MODULE["SERVICES"]:
            receipt = {
                "receiptSha256": "b" * 64,
                "controlReference": reference,
            }
            environment = MODULE["runtime_environment"](
                service, 18000, 15432, passwords, receipt
            )
            prefix = service.name.upper()
            self.assertEqual(
                "b" * 64,
                environment[f"DWP_{prefix}_MIGRATION_CONTROL_RUN_RECEIPT_SHA256"],
            )
            self.assertEqual(
                reference,
                environment[f"DWP_{prefix}_MIGRATION_CONTROL_REFERENCE"],
            )
            self.assertEqual(service.database, environment[f"{prefix}_DB_NAME"])
            self.assertEqual("true", environment["DWP_OPENAPI_ENABLED"])
            for name, value in service.feature_environment:
                self.assertEqual(value, environment[name])

    def test_receipt_validator_recomputes_official_digest(self) -> None:
        canonical_field = MODULE["canonical_field"]
        reference = "dwp-migration-control-v2:" + "c" * 64
        service = MODULE["SERVICES"][1]
        stream = {
            "streamKey": "payroll-main",
            "historyMaxInstalledRank": 6,
            "historyRowCount": 6,
            "historySha256": "d" * 64,
            "inventoryObjectCount": 42,
            "inventorySha256": "e" * 64,
            "adoptionReceiptSha256": "",
        }
        receipt = {
            "schemaVersion": "2.0",
            "mode": "NATIVE_FRESH",
            "service": "payroll",
            "database": service.database,
            "migrationPrincipal": service.migration_role,
            "controlReference": reference,
            "previousRunReceiptSha256": "",
            "postgresVersion": "18.4",
            "temporaryPrivilegeRevoked": True,
            "streams": [stream],
            "receiptSha256": "",
        }
        digest = hashlib.sha256()
        canonical_field(digest, "migration-control-run-receipt-v2")
        for field in (
            "mode", "service", "database", "migrationPrincipal", "controlReference",
            "previousRunReceiptSha256", "postgresVersion", "temporaryPrivilegeRevoked",
        ):
            canonical_field(digest, receipt[field])
        for field in (
            "streamKey", "historyMaxInstalledRank", "historyRowCount", "historySha256",
            "inventoryObjectCount", "inventorySha256", "adoptionReceiptSha256",
        ):
            canonical_field(digest, stream[field])
        receipt["receiptSha256"] = digest.hexdigest()

        self.assertEqual(
            receipt,
            MODULE["validate_receipt"](receipt, service, reference),
        )
        receipt["receiptSha256"] = "f" * 64
        with self.assertRaises(MODULE["GateFailure"]):
            MODULE["validate_receipt"](receipt, service, reference)

    def test_backend_smoke_step_binds_every_required_input(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "backend-quality-gates.yml").read_text(
            encoding="utf-8"
        )
        for binding in (
            "DWP_SMOKE_TENANT_ID: '1'",
            "DWP_SMOKE_EMAIL: hyunwoo.park@sk.com",
            "DWP_SMOKE_PASSWORD: admin1234!",
            "DWP_SMOKE_CORRELATION_ID: backend-quality-runtime-smoke",
            "DWP_SMOKE_EXPECTED_ROLES: TENANT_ADMIN",
        ):
            self.assertIn(binding, workflow)

    def test_safe_refresh_rejects_parameter_constraint_drift(self) -> None:
        approved = {
            "parameters": [{
                "in": "path",
                "name": "action",
                "required": True,
                "schema": {
                    "enum": ["apply-approval", "publish", "submit-review", "validate"],
                    "type": "string",
                },
            }]
        }
        live = {
            "parameters": [{
                "in": "path",
                "name": "action",
                "required": True,
                "schema": {"type": "string"},
            }]
        }

        with self.assertRaisesRegex(
                MODULE["GateFailure"], "changes parameter constraints"):
            MODULE["require_safe_parameter_refresh"](
                "time", "POST /v1/hris/work-plans/{workPlanId}/actions/{action}",
                approved, live,
            )

        live["parameters"][0]["schema"]["enum"] = approved["parameters"][0][
            "schema"
        ]["enum"]
        live["parameters"].append({
            "in": "query",
            "name": "view",
            "required": False,
            "schema": {"type": "string"},
        })
        MODULE["require_safe_parameter_refresh"](
            "time", "POST /v1/hris/work-plans/{workPlanId}/actions/{action}",
            approved, live,
        )

    def test_safe_refresh_requires_stable_owner_openapi_info(self) -> None:
        service = next(
            service for service in MODULE["SERVICES"] if service.name == "time"
        )
        approved = {
            "info": {"title": "OpenAPI definition", "version": "v0"},
            "paths": {
                "/v1/example": {
                    "get": {"operationId": "example", "parameters": []}
                }
            },
            "components": {"schemas": {}},
        }
        live = {
            **approved,
            "info": {"title": "DWP Time Service API", "version": "1.0.0"},
        }
        require_refresh = MODULE["require_safe_snapshot_refresh"]
        namespace = require_refresh.__globals__
        original = namespace["canonical_parity_documents"]
        try:
            namespace["canonical_parity_documents"] = (
                lambda _service, _document: ({}, approved, live)
            )
            require_refresh(service, {})

            live["info"] = {"title": "OpenAPI definition", "version": "v0"}
            with self.assertRaisesRegex(
                    MODULE["GateFailure"], "changes stable OpenAPI info"):
                require_refresh(service, {})
        finally:
            namespace["canonical_parity_documents"] = original


if __name__ == "__main__":
    unittest.main()
