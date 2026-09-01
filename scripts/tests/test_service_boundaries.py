from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-service-boundaries.py"
SPEC = importlib.util.spec_from_file_location("service_boundary_checker", CHECKER)
assert SPEC is not None and SPEC.loader is not None
CHECKER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER_MODULE)


class ApplicationLayerBoundaryTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.original_root = CHECKER_MODULE.ROOT
        CHECKER_MODULE.ROOT = self.root

    def tearDown(self) -> None:
        CHECKER_MODULE.ROOT = self.original_root
        self.temporary_directory.cleanup()

    def write_source(self, name: str, source: str) -> None:
        path = (
            self.root
            / "dwp-platform-server/src/main/java/com/dwp/services/platform/example"
            / name
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    def test_controller_must_use_an_application_service_instead_of_repository(self) -> None:
        self.write_source(
            "OrderController.java",
            """package com.dwp.services.platform.example;
               final class OrderController {
                   private final OrderRepository repository;
               }
            """,
        )

        violations = CHECKER_MODULE.application_layer_violations()

        self.assertEqual(1, len(violations))
        self.assertIn("OrderRepository", violations[0])
        self.assertIn("application service", violations[0])

    def test_comments_and_strings_do_not_create_layer_dependencies(self) -> None:
        self.write_source(
            "OrderController.java",
            """package com.dwp.services.platform.example;
               final class OrderController {
                   // HistoricalOrderRepository was replaced.
                   private static final String NOTE = "Do not use OldOrderRepository";
                   private final OrderQueryService service;
               }
            """,
        )

        self.assertEqual([], CHECKER_MODULE.application_layer_violations())

    def test_external_workload_adapter_requires_explicit_origin_validation(self) -> None:
        relative = (
            "dwp-meeting-server/src/main/java/com/dwp/services/meeting/"
            "provider/MeetingWorkloadClient.java"
        )
        self.write_source("Unused.java", "package com.dwp.services.platform.example;")
        workload = self.root / relative
        workload.parent.mkdir(parents=True, exist_ok=True)
        workload.write_text("final class MeetingWorkloadClient {}", encoding="utf-8")
        policy = {
            "version": 2,
            "resilienceDefaults": {
                "connectTimeoutMs": 1_000,
                "readTimeoutMs": 5_000,
                "bulkheadMaxConcurrentCalls": 10,
                "maximumRetryAttempts": 1,
                "circuitBreaker": True,
            },
            "httpClients": [{
                "id": "meeting-workload",
                "classification": "governed-workload",
                "interfaceType": "external-connector",
                "sourceService": "dwp-meeting-server",
                "targetServices": ["dwp-agent"],
                "path": relative,
                "purpose": "Call a separately deployed governed workload.",
                "auth": "Signed workload assertion.",
                "retryMode": "none",
                "failureMode": "fail-closed",
                "requiredMarkers": ["validatedOrigin"],
                "forbiddenMarkers": ["X-DWP-Service-Token"],
            }],
            "crossDatabaseExceptions": [],
            "metadataScanners": [],
        }

        self.assertEqual([], CHECKER_MODULE.policy_manifest_violations(policy))


if __name__ == "__main__":
    unittest.main()
