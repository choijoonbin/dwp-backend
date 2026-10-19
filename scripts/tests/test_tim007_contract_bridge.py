from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/time.json"
GATEWAY = ROOT / "contracts/openapi/gateway-public.json"
BRIDGE = (
    ROOT / "contracts/in-process/hris/tim-payroll-handoff-outcome-bridge.v1.json"
)
XCON = (
    ROOT
    / "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json"
)
XCON_001_022_NORMALIZED_SHA256 = (
    "1b3199f8383a54b9873bbbb6569bbe1adfe8a521f623a6c4b9225d99c9c8ab89"
)

POST_OPERATIONS = {
    "/v1/close-periods/{periodId}/validations": (
        "hrisTimeValidateClosePeriod",
        "TimeCloseValidationCommandV1",
        "WORK_TIME_PERIOD_CLOSURE",
        False,
    ),
    "/v1/close-periods/{periodId}/close": (
        "hrisTimeClosePeriod",
        "TimeCloseCommandV1",
        "WORK_TIME_PERIOD_CLOSURE",
        True,
    ),
    "/v1/close-periods/{periodId}/reopen": (
        "hrisTimeReopenPeriod",
        "TimeReopenCommandV1",
        "WORK_TIME_PERIOD_CLOSURE",
        True,
    ),
    "/v1/close-periods/{periodId}/payroll-handoffs": (
        "hrisTimeCreatePayrollHandoff",
        "PayrollTimeHandoffCommandV1",
        "CLOSED_TIME_PAYROLL_TRANSFER",
        False,
    ),
}

ENTRY_QUERIES = {
    "/v1/hris/workbench/time-command-center": (
        "IAQ-039",
        "TimeCommandCenterProjectionV1",
        "TimeCommandCenterFilterV1",
        {"commandReceipt", "effectiveOn", "exceptionSummary", "timePeriod"},
        "9d9cdcc8167d834c225201a85171688cbc4abab9cffea93cf402e45dc0c16882",
        "b6ec3f072228de6e2cbd26ef7cd280342d02dae807d6a712ab4227cf65c266d8",
    ),
    "/v1/hris/workbench/time-daily-close": (
        "IAQ-043",
        "TimeDailyCloseProjectionV1",
        "TimeDailyCloseFilterV1",
        {"closeStatus", "effectiveOn", "impactSummary", "timePeriod"},
        "88e86bfced9b5f766de79d3937a0fa99061d517c4a4a6bc34d31c3a66e8a4e92",
        "2f630f7e883e9889552f12345d8c9c7c3a758e998fddef5bac58e55daae14a7c",
    ),
    "/v1/hris/workbench/time-monthly-close": (
        "IAQ-044",
        "TimeMonthlyCloseProjectionV1",
        "TimeMonthlyCloseFilterV1",
        {"closeStatus", "effectiveOn", "payrollHandoff", "timePeriod"},
        "12d54d5ef6749483c1d22eecb110199dedd6a8cac609e73c47af2f0ef4fb652f",
        "b89467f686abde5193b310ee2f9c4e0c967df2d31534a2bb471311ff63ede492",
    ),
}


class Tim007ContractBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.owner = json.loads(OPENAPI.read_text(encoding="utf-8"))
        cls.gateway = json.loads(GATEWAY.read_text(encoding="utf-8"))
        cls.bridge = json.loads(BRIDGE.read_text(encoding="utf-8"))

    def test_public_mutations_are_strict_default_off_and_step_up_is_exact(self) -> None:
        common_headers = {
            "Idempotency-Key",
            "If-Match",
            "X-Correlation-ID",
            "X-Purpose-Code",
            "X-DWP-Expected-Decision-Revision",
        }
        for path, (operation_id, request_schema, purpose, step_up) in (
            POST_OPERATIONS.items()
        ):
            with self.subTest(path=path):
                operation = self.owner["paths"][path]["post"]
                self.assertEqual(operation_id, operation["operationId"])
                self.assertEqual("OFF", operation["x-dwp-runtime-default"])
                self.assertEqual(
                    "TIM-007-CENTRAL-BRIDGE-R1",
                    operation["x-dwp-reviewed-design-time-contract"],
                )
                self.assertEqual(purpose, operation["x-dwp-purpose-code"])
                headers = {
                    parameter["name"]: parameter
                    for parameter in operation["parameters"]
                    if parameter["in"] == "header"
                }
                expected_headers = set(common_headers)
                if step_up:
                    expected_headers.add("X-DWP-Step-Up-Challenge")
                self.assertEqual(expected_headers, set(headers))
                self.assertTrue(all(value["required"] for value in headers.values()))
                self.assertEqual(
                    {"type": "string", "pattern": '^"[0-9]+"$'},
                    headers["If-Match"]["schema"],
                )
                self.assertEqual(
                    {"type": "string", "pattern": "^psr-[0-9a-f]{64}$"},
                    headers["X-DWP-Expected-Decision-Revision"]["schema"],
                )
                self.assertEqual(
                    {"$ref": f"#/components/schemas/{request_schema}"},
                    operation["requestBody"]["content"]["application/json"]["schema"],
                )
                self.assertEqual(
                    {"$ref": "#/components/schemas/TimeCloseCommandReceiptV1"},
                    operation["responses"]["202"]["content"]["application/json"][
                        "schema"
                    ],
                )
                self.assertNotIn("ETag", operation["responses"]["202"]["headers"])

                expected_precondition_headers = {
                    "Cache-Control",
                    "ETag",
                    "X-Correlation-ID",
                }
                self.assertEqual(
                    expected_precondition_headers,
                    set(operation["responses"]["412"]["headers"]),
                )
                self.assertEqual(
                    {
                        "required": True,
                        "schema": {"type": "string", "pattern": '^"[0-9]+"$'},
                    },
                    operation["responses"]["412"]["headers"]["ETag"],
                )

                gateway_operation = self.gateway["paths"][f"/api/time{path}"]["post"]
                self.assertEqual("OFF", gateway_operation["x-dwp-runtime-default"])
                self.assertEqual(
                    {name for name in expected_headers},
                    {
                        parameter["name"]
                        for parameter in gateway_operation["parameters"]
                        if parameter["in"] == "header"
                    },
                )
                self.assertEqual(
                    expected_precondition_headers,
                    set(gateway_operation["responses"]["412"]["headers"]),
                )
                self.assertEqual(
                    {
                        "required": True,
                        "schema": {"type": "string", "pattern": '^"[0-9]+"$'},
                    },
                    gateway_operation["responses"]["412"]["headers"]["ETag"],
                )

    def test_handoff_lookup_receipts_and_problem_shapes_are_closed(self) -> None:
        schemas = self.owner["components"]["schemas"]
        lookup = schemas["TimeCommandReceiptLookupV1"]
        self.assertEqual(
            [
                {"$ref": "#/components/schemas/TimeCommandReceiptV1"},
                {"$ref": "#/components/schemas/TimeCloseCommandReceiptV1"},
            ],
            lookup["oneOf"],
        )
        self.assertNotIn("oneOf", schemas["TimeCommandReceiptV1"])
        self.assertFalse(schemas["TimeCommandReceiptV1"]["additionalProperties"])
        self.assertEqual(
            {"$ref": "#/components/schemas/TimeCommandReceiptLookupV1"},
            self.owner["paths"]["/v1/command-receipts/{receiptId}"]["get"]
            ["responses"]["200"]["content"]["application/json"]["schema"],
        )

        handoff = schemas["PayrollTimeHandoffStatusV1"]
        self.assertFalse(handoff["additionalProperties"])
        self.assertEqual(set(handoff["properties"]), set(handoff["required"]))
        self.assertEqual(
            ["PENDING", "ACKNOWLEDGED", "REJECTED", "RESULT_UNKNOWN"],
            handoff["properties"]["status"]["enum"],
        )
        self.assertEqual(4, len(handoff["allOf"]))
        handoff_get = self.owner["paths"]["/v1/payroll-handoffs/{handoffId}"]["get"]
        self.assertEqual("OFF", handoff_get["x-dwp-runtime-default"])
        self.assertEqual(
            {"Cache-Control", "ETag", "X-Correlation-ID", "X-DWP-Decision-Revision"},
            set(handoff_get["responses"]["200"]["headers"]),
        )

        problem = schemas["HrisTimeProblemV1"]
        self.assertFalse(problem["additionalProperties"])
        self.assertEqual(
            {"type", "title", "status", "code", "correlationId", "retryable"},
            set(problem["required"]),
        )
        self.assertEqual(
            {400, 401, 403, 404, 409, 412, 422, 428, 429, 503},
            set(problem["properties"]["status"]["enum"]),
        )

    def test_entry_query_shapes_retain_the_approved_supplement_lineage(self) -> None:
        required = {"limit", "filter", "sort", "locale", "timeZone", "correlationId"}
        optional = {"asOf", "cursor"}
        schemas = self.owner["components"]["schemas"]
        for path, (binding, response, filter_name, filter_fields, request_sha, response_sha) in (
            ENTRY_QUERIES.items()
        ):
            with self.subTest(binding=binding):
                operation = self.owner["paths"][path]["get"]
                self.assertEqual(binding, operation["x-dwp-entry-query-id"])
                self.assertEqual(request_sha, operation["x-dwp-request-shape-sha256"])
                self.assertEqual(response_sha, operation["x-dwp-response-shape-sha256"])
                self.assertEqual("OFF", operation["x-dwp-runtime-default"])
                parameters = {
                    value["name"]: value
                    for value in operation["parameters"]
                    if value["in"] == "query"
                }
                self.assertEqual(required | optional, set(parameters))
                self.assertTrue(all(parameters[name]["required"] for name in required))
                self.assertTrue(all(not parameters[name]["required"] for name in optional))
                self.assertEqual("deepObject", parameters["filter"]["style"])
                self.assertTrue(parameters["filter"]["explode"])
                self.assertEqual("form", parameters["sort"]["style"])
                self.assertTrue(parameters["sort"]["explode"])
                self.assertEqual(filter_fields, set(schemas[filter_name]["properties"]))
                self.assertFalse(schemas[filter_name]["additionalProperties"])
                self.assertEqual(
                    {"$ref": f"#/components/schemas/{response}"},
                    operation["responses"]["200"]["content"]["application/json"][
                        "schema"
                    ],
                )

    def test_internal_outcome_bridge_is_exact_fail_closed_and_not_activated(self) -> None:
        binding = self.bridge["binding"]
        self.assertEqual(
            (
                "DRAFT_DEFAULT_OFF",
                "SVC-PEP-TIM-003",
                "dwp-payroll-server",
                "spiffe://dwp/service/dwp-payroll-server",
                "POST",
                "/internal/hris-time/v1/payroll-handoffs/{handoffId}/outcomes",
                "HRIS_CLOSED_TIME_HANDOFF_OUTCOME",
            ),
            (
                self.bridge["lifecycleState"],
                binding["bindingId"],
                binding["callerWorkloadId"],
                binding["callerSpiffeId"],
                binding["method"],
                binding["path"],
                binding["purpose"],
            ),
        )
        proof = self.bridge["workloadProof"]
        self.assertEqual(
            {"alg": "RS256", "typ": "JWT"}, proof["joseHeader"]["fixed"]
        )
        self.assertEqual(2048, proof["joseHeader"]["minimumRsaBits"])
        self.assertEqual(17, len(proof["claims"]["exactFields"]))
        request = self.bridge["$defs"]["PayrollTimeHandoffOutcomeV1"]
        self.assertFalse(request["additionalProperties"])
        self.assertEqual(set(request["properties"]), set(request["required"]))
        self.assertEqual(
            ["ACKNOWLEDGED", "REJECTED", "RESULT_UNKNOWN"],
            request["properties"]["outcome"]["enum"],
        )
        self.assertEqual(
            ["ACKNOWLEDGED", "REJECTED"],
            self.bridge["stateMachine"]["terminal"],
        )
        self.assertEqual(
            {"400", "401", "403", "404", "409", "429", "503"},
            set(self.bridge["errorProfile"]["responses"]),
        )
        ownership = self.bridge["ownership"]
        self.assertFalse(ownership["fourthAsyncApiOutcomeEvent"])
        self.assertFalse(ownership["payRuntimeMigrationAuthorized"])
        self.assertFalse(ownership["featureOrTenantActivationAuthorized"])
        self.assertIn(
            "dwp.hris.time.timecard.enabled",
            " ".join(ownership["successorRuntimePrerequisites"]),
        )
        self.assertIn(
            "Timecard authority evidence",
            " ".join(ownership["successorRuntimePrerequisites"]),
        )

    def test_predecessor_xcon_definitions_are_immutable_under_append_only_growth(self) -> None:
        document = json.loads(XCON.read_text(encoding="utf-8"))
        definitions = {
            f"XCON_{index:03d}": document["$defs"][f"XCON_{index:03d}"]
            for index in range(1, 23)
        }
        normalized = json.dumps(
            definitions,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        self.assertEqual(
            XCON_001_022_NORMALIZED_SHA256,
            hashlib.sha256(normalized).hexdigest(),
        )


if __name__ == "__main__":
    unittest.main()
