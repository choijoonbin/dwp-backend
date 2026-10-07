import copy
import importlib.util
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "hris_w1_synthetic_acceptance.py"
SPEC = importlib.util.spec_from_file_location("hris_w1_synthetic_lineage", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class HrisW1SyntheticLineageTest(unittest.TestCase):

    RUN_ID = "w1-20261001t050403z-0123abcd"
    PROVIDER_ID = uuid.UUID("00000000-0000-0000-0000-00000000000a")

    def test_authorization_successor_uses_current_keep_v34_lineage(self):
        self.assertEqual("KEEP_V34", gate.G3_AUTHORIZATION_DECISION)
        self.assertEqual(33, gate.BASE_AUTHORIZATION_VERSION)
        self.assertEqual(34, gate.SELECTED_AUTHORIZATION_VERSION)
        self.assertEqual("240", gate.BASE_AUTH_FLYWAY_VERSION)
        self.assertEqual("241", gate.SELECTED_AUTH_FLYWAY_VERSION)
        self.assertEqual("242", gate.CURRENT_AUTH_FLYWAY_VERSION)
        self.assertEqual(
            "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639",
            gate.V33_CHECKSUM,
        )
        self.assertEqual(
            "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b",
            gate.V34_CHECKSUM,
        )

    def workforce_response(self):
        response = {
            "runId": self.RUN_ID,
            "providerTenantId": str(self.PROVIDER_ID),
            "tenantId": 101,
            "administratorActorId": 1001,
            "actorPersonPublicId": "10000000-0000-0000-0000-00000000000a",
            "actorWorkerPublicId": "20000000-0000-0000-0000-00000000000a",
            "actorAssignmentPublicId": "30000000-0000-0000-0000-00000000000a",
            "actorLegalEmployerPublicId": "40000000-0000-0000-0000-00000000000a",
            "actorWorkerNumber": "E100001",
            "targetPersonPublicId": "50000000-0000-0000-0000-00000000000a",
            "targetWorkerPublicId": "60000000-0000-0000-0000-00000000000a",
            "targetAssignmentPublicId": "70000000-0000-0000-0000-00000000000a",
            "syncRunId": "80000000-0000-0000-0000-00000000000a",
            "importReplayed": False,
            "importedWorkerCount": 3,
            "workforceAccessPolicyId": "90000000-0000-0000-0000-00000000000a",
            "workforceAccessPolicyVersion": 0,
            "targetPopulationCount": 2,
            "targetPopulationRevision": (
                "a" * 32
                + ":true|[]|[DIRECTORY, EMPLOYMENT, JOB_GRADE, WORKER_IDENTIFIERS]|READ"
            ),
            "plannedIdentityRoleCodes": [
                "HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN"
            ],
            "authWorkforceBinding": {
                "endpoint": "/internal/identity/v1/workforce-events",
                "tokenHeader": "X-DWP-Identity-Sync-Token",
                "expectedAdministratorUserId": 1001,
                "event": {
                    "eventId": str(gate.java_name_uuid_from_bytes("|".join((
                        self.RUN_ID, "auth-workforce-event", str(self.PROVIDER_ID),
                        "101", "1001",
                    )))),
                    "providerTenantId": str(self.PROVIDER_ID),
                    "personPublicId": "10000000-0000-0000-0000-00000000000a",
                    "externalId": "WD-WORKER-0001",
                    "workerNumber": "E100001",
                    "displayName": "Minseo Kim",
                    "givenName": "Minseo",
                    "familyName": "Kim",
                    "workEmail": "minseo.kim@sk.com",
                    "jobTitle": "Network Operations Lead",
                    "preferredLocale": "ko-KR",
                    "workerStatus": "ACTIVE",
                    "sourceVersion": "2026-08-10T00:00:01Z",
                },
            },
            "receiptSha256": "",
        }
        response["receiptSha256"] = gate.people_workforce_receipt_sha256(response)
        return response

    def test_people_response_requires_exact_three_two_event_and_receipt(self):
        response = self.workforce_response()
        self.assertEqual(response, gate.validate_people_workforce_bootstrap_response(
            response,
            run_id=self.RUN_ID,
            lane="a",
            provider_tenant_id=self.PROVIDER_ID,
            tenant_id=101,
            administrator_actor_id=1001,
        ))

        for path, invalid in (
            (("importedWorkerCount",), 2),
            (("targetPopulationCount",), 1),
            (("targetPopulationRevision",), "not-owner-revision"),
            (("plannedIdentityRoleCodes",), []),
            (("authWorkforceBinding", "event", "workEmail"), "tampered@dwp.test"),
        ):
            candidate = copy.deepcopy(response)
            target = candidate
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = invalid
            candidate["receiptSha256"] = gate.people_workforce_receipt_sha256(candidate)
            with self.subTest(path=path), self.assertRaises(gate.GateFailure):
                gate.validate_people_workforce_bootstrap_response(
                    candidate,
                    run_id=self.RUN_ID,
                    lane="a",
                    provider_tenant_id=self.PROVIDER_ID,
                    tenant_id=101,
                    administrator_actor_id=1001,
                )

        response["receiptSha256"] = "0" * 64
        with self.assertRaises(gate.GateFailure):
            gate.validate_people_workforce_bootstrap_response(
                response,
                run_id=self.RUN_ID,
                lane="a",
                provider_tenant_id=self.PROVIDER_ID,
                tenant_id=101,
                administrator_actor_id=1001,
            )

    def test_people_response_binds_lane_b_foundation_roles_without_job_grade(self):
        response = self.workforce_response()
        response["plannedIdentityRoleCodes"] = []
        response["targetPopulationRevision"] = (
            "b" * 32
            + ":true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ"
        )
        response["receiptSha256"] = gate.people_workforce_receipt_sha256(response)

        self.assertEqual(response, gate.validate_people_workforce_bootstrap_response(
            response,
            run_id=self.RUN_ID,
            lane="b",
            provider_tenant_id=self.PROVIDER_ID,
            tenant_id=101,
            administrator_actor_id=1001,
        ))

    def test_payroll_preflight_and_postflight_bind_exact_database_lineage(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = gate.GateState(self.RUN_ID, Path(temporary), "2026-10-01T05:04:03Z")
            state.synthetic_tenants["A"] = {
                "tenantId": 101,
                "administratorUserId": 1001,
            }
            tenant = gate.SyntheticTenantCredential(
                "A", str(self.PROVIDER_ID), 101, 1001,
                "10000000-0000-0000-0000-00000000000a",
                "20000000-0000-0000-0000-00000000000a",
                "30000000-0000-0000-0000-00000000000a",
                "40000000-0000-0000-0000-00000000000a",
                "50000000-0000-0000-0000-00000000000a",
                "60000000-0000-0000-0000-00000000000a",
                "70000000-0000-0000-0000-00000000000a",
                "a" * 32, 2, "tenant-a", "admin@dwp.test", "secret",
            )
            fixture = {
                "configurationId": "80000000-0000-0000-0000-000000000001",
                "authorActorId": 1000001001,
                "simulateCommandId": "80000000-0000-0000-0000-000000000002",
                "receiptSha256": "a" * 64,
            }
            preflight = {
                "schemaVersion": 1, "phase": "PREFLIGHT", "tenantId": 101,
                "configurationId": fixture["configurationId"], "currentVersion": 2,
                "lifecycleState": "SIMULATED",
                "legalEntityId": tenant.actor_legal_employer_public_id,
                "authorId": fixture["authorActorId"], "publisherId": None,
                "lastCommandId": fixture["simulateCommandId"],
                "fixtureReceiptSha256": fixture["receiptSha256"],
                "definitionDigest": "b" * 64, "dependencyDigest": "c" * 64,
                "lastCommandReceipt": {
                    "commandId": fixture["simulateCommandId"],
                    "commandType": "SIMULATE", "receiptStatus": "SUCCEEDED",
                    "resultVersion": 2, "requestDigest": "d" * 64,
                },
            }
            with mock.patch.object(gate, "psql", return_value=json.dumps(preflight)), \
                    mock.patch.object(gate, "atomic_write_json"):
                observed = gate.observe_payroll_foundation_preflight(
                    "postgres", state, tenant, fixture
                )
            state.projection_feed["payrollFoundationDatabaseObservation"] = observed
            browser = {
                "updateCommandId": "80000000-0000-0000-0000-000000000003",
                "simulateCommandId": "80000000-0000-0000-0000-000000000004",
                "observationSha256": "e" * 64,
                "workspaceResponseSha256": "f" * 64,
                "updateResponseSha256": "1" * 64,
                "simulateResponseSha256": "2" * 64,
            }
            postflight = self.postflight_observation(observed, browser)
            with mock.patch.object(gate, "psql", return_value=json.dumps(postflight)), \
                    mock.patch.object(gate, "atomic_write_json"):
                result = gate.observe_payroll_foundation_postflight(
                    "postgres", state, browser
                )
            self.assertEqual(4, result["currentVersion"])
            self.assertEqual(1001, result["authorId"])

            postflight["authorId"] = observed["authorId"]
            with mock.patch.object(gate, "psql", return_value=json.dumps(postflight)), \
                    mock.patch.object(gate, "atomic_write_json"), \
                    self.assertRaises(gate.GateFailure):
                gate.observe_payroll_foundation_postflight("postgres", state, browser)

    def postflight_observation(self, preflight, browser):
        configuration_id = preflight["configurationId"]
        versions = [
            {
                "version": 3, "rowCount": 1,
                "commandId": browser["updateCommandId"], "lifecycleState": "DRAFT",
                "authoredBy": 1001, "definitionDigest": "3" * 64,
                "dependencyDigest": "4" * 64,
            },
            {
                "version": 4, "rowCount": 1,
                "commandId": browser["simulateCommandId"],
                "lifecycleState": "SIMULATED", "authoredBy": 1001,
                "definitionDigest": "3" * 64, "dependencyDigest": "4" * 64,
            },
        ]
        def receipt(command_id, command_type, result_version, digest):
            return {
                "rowCount": 1, "commandId": command_id,
                "commandType": command_type, "receiptStatus": "SUCCEEDED",
                "configurationId": configuration_id, "resultVersion": result_version,
                "actorId": 1001, "requestDigest": digest * 64,
            }
        return {
            "schemaVersion": 1, "phase": "POSTFLIGHT", "tenantId": 101,
            "configurationId": configuration_id, "currentVersion": 4,
            "lifecycleState": "SIMULATED", "legalEntityId": preflight["legalEntityId"],
            "authorId": 1001, "publisherId": None,
            "lastCommandId": browser["simulateCommandId"],
            "definitionDigest": "3" * 64, "dependencyDigest": "4" * 64,
            "preflightObservationSha256": preflight["observationSha256"],
            "browserObservationSha256": browser["observationSha256"],
            "workspaceResponseSha256": browser["workspaceResponseSha256"],
            "updateResponseSha256": browser["updateResponseSha256"],
            "simulateResponseSha256": browser["simulateResponseSha256"],
            "versionRows": versions,
            "updateReceipt": receipt(browser["updateCommandId"], "UPDATE", 3, "5"),
            "simulateReceipt": receipt(browser["simulateCommandId"], "SIMULATE", 4, "6"),
        }


if __name__ == "__main__":
    unittest.main()
