from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-home-runtime-boundaries.py"
SPEC = importlib.util.spec_from_file_location("home_runtime_boundary_checker", CHECKER)
assert SPEC is not None and SPEC.loader is not None
CHECKER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER_MODULE)


class HomeRuntimeBoundaryTest(unittest.TestCase):

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.runtime = self.root / CHECKER_MODULE.RUNTIME_RELATIVE
        self.runtime.mkdir(parents=True)
        self.write_runtime(
            "SafeProviderClient.java",
            """package com.dwp.services.platform.home.runtime;
               import com.dwp.contracts.home.HomeWidgetProviderContract;
               final class SafeProviderClient {}
            """,
        )
        build = self.root / CHECKER_MODULE.PLATFORM_BUILD
        build.parent.mkdir(parents=True, exist_ok=True)
        build.write_text(
            "implementation project(':dwp-core')\n"
            "implementation project(':dwp-platform-contracts')\n",
            encoding="utf-8",
        )
        config = self.root / CHECKER_MODULE.PLATFORM_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(
            "url: jdbc:postgresql://localhost/${PLATFORM_DB_NAME:dwp_platform}\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.directory.cleanup()

    def write_runtime(self, name: str, source: str) -> None:
        (self.runtime / name).write_text(source, encoding="utf-8")

    def test_provider_contract_client_passes_without_persistence(self) -> None:
        self.assertEqual([], CHECKER_MODULE.violations(self.root))

    def test_missing_runtime_fails_closed(self) -> None:
        for source in self.runtime.glob("*.java"):
            source.unlink()

        self.assertIn("has no runtime Java sources", CHECKER_MODULE.violations(self.root)[0])

    def test_rejects_owner_implementation_and_persistence_access(self) -> None:
        self.write_runtime(
            "UnsafeBroker.java",
            """package com.dwp.services.platform.home.runtime;
               import com.dwp.services.approval.domain.ApprovalService;
               import org.springframework.jdbc.core.JdbcTemplate;
               final class UnsafeBroker { private JdbcTemplate jdbc; }
            """,
        )

        result = "\n".join(CHECKER_MODULE.violations(self.root))

        self.assertIn("imports an owner-service implementation", result)
        self.assertIn("imports persistence APIs", result)
        self.assertIn("uses persistence symbol", result)

    def test_rejects_owner_server_dependency_and_database_configuration(self) -> None:
        (self.root / CHECKER_MODULE.PLATFORM_BUILD).write_text(
            "implementation project(':dwp-approval-server')\n", encoding="utf-8"
        )
        (self.root / CHECKER_MODULE.PLATFORM_CONFIG).write_text(
            "url: jdbc:postgresql://localhost/${APPROVAL_DB_NAME:dwp_approval}\n",
            encoding="utf-8",
        )

        result = "\n".join(CHECKER_MODULE.violations(self.root))

        self.assertIn("directly depends on owner module dwp-approval-server", result)
        self.assertIn("references sibling database configuration", result)

    def test_rejects_sensitive_log_and_metric_arguments_but_ignores_comments(self) -> None:
        self.write_runtime(
            "UnsafeTelemetry.java",
            """package com.dwp.services.platform.home.runtime;
               final class UnsafeTelemetry {
                   void record(long tenantId, Object payload) {
                       // logger.info(tenantId, payload);
                       logger.info("runtime failed", tenantId);
                       meterRegistry.counter("home", "payload", payload.toString());
                   }
               }
            """,
        )

        result = "\n".join(CHECKER_MODULE.violations(self.root))

        self.assertIn("'tenantId' to a logger", result)
        self.assertIn("'payload' to a metric", result)


if __name__ == "__main__":
    unittest.main()
