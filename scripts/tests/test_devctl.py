from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import devctl


def control_receipt(
    service_name: str,
    reference: str,
    *,
    previous: str = "",
) -> dict[str, object]:
    control = devctl.HRIS_CONTROLLED_DATABASES[service_name]
    receipt: dict[str, object] = {
        "schemaVersion": "2.0",
        "mode": "NATIVE_FRESH",
        "service": service_name,
        "database": control.database,
        "migrationPrincipal": control.migration_role,
        "controlReference": reference,
        "previousRunReceiptSha256": previous,
        "postgresVersion": "18.4",
        "temporaryPrivilegeRevoked": True,
        "streams": [
            {
                "streamKey": control.stream_key,
                "historyMaxInstalledRank": 9,
                "historyRowCount": 9,
                "historySha256": "1" * 64,
                "inventoryObjectCount": 42,
                "inventorySha256": "2" * 64,
                "adoptionReceiptSha256": "",
            }
        ],
        "receiptSha256": "",
    }
    receipt["receiptSha256"] = devctl.control_run_receipt_sha256(receipt)
    return receipt


class AgentLocalEnvironmentTest(unittest.TestCase):
    def test_local_home_runtime_enables_all_three_product_modes(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            platform = devctl.service_environment("platform")
            frontend = devctl.service_environment("frontend")

        self.assertEqual(platform["DWP_HOME_MODE_V4_ACTIVATION_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_FLOW_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_MZ_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_PERSONALIZATION_V2_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_VIEWS_DUAL_WRITE_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_VIEWS_SHADOW_COMPARE_ENABLED"], "true")
        self.assertEqual(platform["DWP_HOME_VIEWS_READ_ENABLED"], "true")
        self.assertEqual(frontend["VITE_HOME_PERSONALIZATION_V2_ENABLED"], "true")

    def test_spring_services_wait_for_readiness_instead_of_optional_dependency_health(
        self,
    ) -> None:
        spring_services = set(devctl.SERVICES) - {"agent", "frontend"}
        self.assertTrue(spring_services)
        for name in spring_services:
            self.assertEqual(
                devctl.SERVICES[name].health_path,
                devctl.SPRING_READINESS_PATH,
            )

    def test_approval_local_boot_publishes_signature_contracts_without_enabling_sources(
        self,
    ) -> None:
        with patch.dict(os.environ, {}, clear=True):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        expected = {
            "DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED": "true",
            "DWP_APPROVAL_INTERNAL_SIGNATURES_ENABLED": "true",
            "DWP_APPROVAL_INTERNAL_SIGNATURES_SOURCE_ENABLED": "false",
        }
        for key, value in expected.items():
            self.assertEqual(environments["approval"][key], value)
            self.assertTrue(
                all(
                    key not in environment
                    for name, environment in environments.items()
                    if name != "approval"
                )
            )

        with patch.dict(
            os.environ,
            {
                "DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED": "false",
                "DWP_APPROVAL_INTERNAL_SIGNATURES_ENABLED": "false",
            },
            clear=True,
        ):
            overridden = devctl.service_environment("approval")
        self.assertEqual(overridden["DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED"], "false")
        self.assertEqual(overridden["DWP_APPROVAL_INTERNAL_SIGNATURES_ENABLED"], "false")

    def test_agent_owner_reads_use_explicit_local_gateway_and_preserve_override(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(devctl.service_environment("agent")["SERVICE_GATEWAY_URL"],
                             "http://localhost:8080")
        with patch.dict(os.environ, {"SERVICE_GATEWAY_URL": "http://127.0.0.1:9080"}):
            self.assertEqual(devctl.service_environment("agent")["SERVICE_GATEWAY_URL"],
                             "http://127.0.0.1:9080")

    def test_frontend_uses_stable_heap_default_and_preserves_explicit_override(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }
        self.assertEqual(
            environments["frontend"]["NODE_OPTIONS"],
            devctl.DEFAULT_FRONTEND_NODE_OPTIONS,
        )
        self.assertTrue(
            all(
                "NODE_OPTIONS" not in environment
                for name, environment in environments.items()
                if name != "frontend"
            )
        )

        with patch.dict(
            os.environ,
            {"NODE_OPTIONS": "--max-old-space-size=4096"},
            clear=True,
        ):
            frontend = devctl.service_environment("frontend")
        self.assertEqual(frontend["NODE_OPTIONS"], "--max-old-space-size=4096")

    def test_identity_sync_token_is_scoped_to_registered_consumers(self) -> None:
        with patch.dict(
            os.environ,
            {"DWP_IDENTITY_SYNC_TOKEN": "local-purpose-token"},
            clear=True,
        ):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        consumers = {
            "auth",
            "platform",
            "people",
            "space",
            "approval",
            "notification",
        }
        for name, environment in environments.items():
            self.assertEqual("DWP_IDENTITY_SYNC_TOKEN" in environment, name in consumers)
            if name in consumers:
                self.assertEqual(
                    environment["DWP_IDENTITY_SYNC_TOKEN"],
                    "local-purpose-token",
                )

    def test_people_receives_platform_token_for_trusted_mail_owner_callbacks(self) -> None:
        with patch.dict(
            os.environ,
            {"DWP_PLATFORM_SERVICE_TOKEN": "local-platform-owner-token"},
            clear=True,
        ):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        for name, environment in environments.items():
            expected = name in {"gateway", "platform", "people"}
            self.assertEqual("DWP_PLATFORM_SERVICE_TOKEN" in environment, expected)
            if expected:
                self.assertEqual(
                    environment["DWP_PLATFORM_SERVICE_TOKEN"],
                    "local-platform-owner-token",
                )
        self.assertNotIn(
            "DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", environments["people"]
        )

    def test_people_local_seed_profile_is_explicit_and_owner_scoped(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        people = environments["people"]
        self.assertEqual(
            people["DWP_PEOPLE_FLYWAY_LOCATIONS"],
            "classpath:db/migration,classpath:db/local-seed",
        )
        self.assertEqual(people["DWP_PEOPLE_LOCAL_SEED_ENABLED"], "true")
        self.assertEqual(
            people["DWP_PEOPLE_MIGRATION_PRINCIPAL_POLICY"], "LOCAL_LEGACY"
        )
        self.assertEqual(people["DWP_SERVICE_INSTANCE"], "local")
        self.assertEqual(people["PEOPLE_DB_USERNAME"], "dwp_people_runtime")
        self.assertEqual(people["PEOPLE_DB_PASSWORD"], "dwp_password")
        self.assertEqual(people["DWP_HRIS_PERFORMANCE_WAVE1_ENABLED"], "true")
        self.assertEqual(
            people["DWP_HRIS_PERFORMANCE_COMPENSATION_PLANNING_ENABLED"],
            "true",
        )
        self.assertEqual(people["DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED"], "true")
        self.assertTrue(
            all(
                "DWP_PEOPLE_LOCAL_SEED_ENABLED" not in environment
                for name, environment in environments.items()
                if name != "people"
            )
        )
        for key in (
            "DWP_PEOPLE_MIGRATION_PRINCIPAL_POLICY",
            "PEOPLE_DB_USERNAME",
            "PEOPLE_DB_PASSWORD",
            "DWP_HRIS_PERFORMANCE_WAVE1_ENABLED",
            "DWP_HRIS_PERFORMANCE_COMPENSATION_PLANNING_ENABLED",
            "DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED",
        ):
            self.assertTrue(
                all(
                    key not in environment
                    for name, environment in environments.items()
                    if name != "people"
                )
            )

    def test_people_local_boundary_uses_isolated_runtime_role_and_minimum_ddl(self) -> None:
        with patch.object(devctl, "docker_compose") as compose:
            devctl.ensure_people_local_legacy_boundary()

        self.assertEqual(compose.call_count, 2)
        for call in compose.call_args_list:
            self.assertEqual(call.args[:3], ("exec", "-T", "postgres"))
        role_sql = compose.call_args_list[0].args[-1]
        schema_sql = compose.call_args_list[1].args[-1]
        self.assertIn("CREATE ROLE dwp_people_runtime", role_sql)
        self.assertIn("NOINHERIT", role_sql)
        self.assertIn(
            "REVOKE TEMPORARY, CREATE ON DATABASE dwp_people FROM PUBLIC",
            role_sql,
        )
        self.assertIn("REVOKE TEMPORARY, CREATE", role_sql)
        self.assertIn("GRANT CONNECT ON DATABASE dwp_people", role_sql)
        self.assertIn("CREATE SCHEMA IF NOT EXISTS hris_performance", schema_sql)
        self.assertIn("REVOKE ALL ON SCHEMA public, hris_performance", schema_sql)
        self.assertIn(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES", schema_sql
        )
        self.assertIn("ALTER DEFAULT PRIVILEGES FOR ROLE dwp_user", schema_sql)

    def test_hris_owner_services_are_in_standard_profiles_before_gateway(self) -> None:
        for profile in ("full", "core", "backend", "contracts", "hris"):
            with self.subTest(profile=profile):
                self.assertTrue(
                    {"time", "payroll"}.issubset(devctl.PROFILES[profile])
                )
        self.assertEqual(devctl.PROFILES["time"], {"time"})
        self.assertEqual(devctl.PROFILES["payroll"], {"payroll"})
        gateway_phase = next(
            index
            for index, phase in enumerate(devctl.START_PHASES)
            if "gateway" in phase
        )
        for owner in ("time", "payroll"):
            owner_phase = next(
                index
                for index, phase in enumerate(devctl.START_PHASES)
                if owner in phase
            )
            self.assertLess(owner_phase, gateway_phase)
            self.assertLess(devctl.START_ORDER.index(owner), devctl.START_ORDER.index("gateway"))

    def test_time_and_payroll_environment_is_exactly_owner_scoped(self) -> None:
        receipt_keys = {
            "time": {"DWP_TIME_MIGRATION_CONTROL_REFERENCE": "time-receipt"},
            "payroll": {
                "DWP_PAYROLL_MIGRATION_CONTROL_REFERENCE": "payroll-receipt"
            },
        }
        with patch.object(
            devctl,
            "migration_control_application_environment",
            side_effect=lambda owner: receipt_keys[owner],
        ):
            with patch.dict(os.environ, {}, clear=True):
                environments = {
                    name: devctl.service_environment(name)
                    for name in devctl.SERVICES
                }

        approved = {
            "time": {
                "DWP_TIME_WORK_REGIME_API_ENABLED",
                "DWP_HRIS_TIME_CLOCK_INGESTION_ENABLED",
                "DWP_HRIS_TIME_CLOCK_INGESTION_CURSOR_SECRET",
                "DWP_HRIS_TIME_INTERPRETATION_ENABLED",
                "DWP_HRIS_TIME_INTERPRETATION_CURSOR_SECRET",
            },
            "payroll": {
                "DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED",
                "DWP_HRIS_PAYROLL_CONFIGURATION_WAVE2_ENABLED",
            },
        }
        for owner, keys in approved.items():
            for key in keys:
                self.assertIn(key, environments[owner])
                self.assertTrue(
                    all(
                        key not in environment
                        for name, environment in environments.items()
                        if name != owner
                    )
                )
        for owner, shared in devctl.HRIS_SHARED_ENVIRONMENT.items():
            for key in shared:
                self.assertTrue(
                    all(
                        (key in environment) == (name in {owner, "gateway"})
                        for name, environment in environments.items()
                    )
                )
        self.assertEqual(
            environments["time"]["DWP_TIME_MIGRATION_CONTROL_REFERENCE"],
            "time-receipt",
        )
        self.assertEqual(
            environments["payroll"]["DWP_PAYROLL_MIGRATION_CONTROL_REFERENCE"],
            "payroll-receipt",
        )
        self.assertTrue(
            all(
                "DWP_TIME_MIGRATION_CONTROL_REFERENCE" not in environment
                for name, environment in environments.items()
                if name != "time"
            )
        )
        self.assertTrue(
            all(
                "DWP_PAYROLL_MIGRATION_CONTROL_REFERENCE" not in environment
                for name, environment in environments.items()
                if name != "payroll"
            )
        )
        for owner, keys in devctl.HRIS_DARK_FEATURE_ENVIRONMENT.items():
            for key in keys:
                self.assertNotIn(key, environments[owner])


class HrisMigrationControlTest(unittest.TestCase):
    REFERENCE = "dwp-migration-control-v2:" + "a" * 64

    def test_receipt_is_canonical_private_and_reusable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "migration-control"
            receipt = control_receipt("time", self.REFERENCE)
            with patch.object(devctl, "MIGRATION_CONTROL_RECEIPT_ROOT", root):
                devctl.save_migration_control_receipt(
                    "time", receipt, current_reference=self.REFERENCE
                )
                path = root / "time.json"
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                self.assertEqual(root.stat().st_mode & 0o777, 0o700)
                self.assertEqual(
                    devctl.load_migration_control_receipt("time"), receipt
                )
                with patch.object(
                    devctl, "migration_control_reference", return_value=self.REFERENCE
                ):
                    application = devctl.migration_control_application_environment(
                        "time"
                    )
            self.assertEqual(
                application["DWP_TIME_MIGRATION_CONTROL_RUN_RECEIPT_SHA256"],
                receipt["receiptSha256"],
            )

    def test_existing_database_without_receipt_fails_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(
                    devctl,
                    "MIGRATION_CONTROL_RECEIPT_ROOT",
                    Path(directory) / "migration-control",
                ),
                patch.object(devctl, "database_exists", return_value=True),
                patch.object(devctl, "ensure_database") as ensure_database,
                patch.object(devctl, "ensure_hris_strict_database_boundary") as boundary,
                patch.object(devctl, "run_hris_migration_control") as control,
            ):
                with self.assertRaisesRegex(RuntimeError, "before any role"):
                    devctl.ensure_hris_controlled_database("time")
            ensure_database.assert_not_called()
            boundary.assert_not_called()
            control.assert_not_called()

    def test_fresh_database_orders_create_boundary_then_official_control(self) -> None:
        events: list[str] = []
        receipt = control_receipt("payroll", self.REFERENCE)
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(
                    devctl,
                    "MIGRATION_CONTROL_RECEIPT_ROOT",
                    Path(directory) / "migration-control",
                ),
                patch.object(devctl, "database_exists", return_value=False),
                patch.object(
                    devctl,
                    "ensure_database",
                    side_effect=lambda database: (
                        events.append(f"database:{database}") or True
                    ),
                ),
                patch.object(
                    devctl,
                    "ensure_hris_strict_database_boundary",
                    side_effect=lambda owner: events.append(f"boundary:{owner}"),
                ),
                patch.object(
                    devctl,
                    "migration_control_reference",
                    return_value=self.REFERENCE,
                ),
                patch.object(
                    devctl,
                    "run_hris_migration_control",
                    side_effect=lambda owner, previous, **kwargs: (
                        events.append(f"control:{owner}") or receipt
                    ),
                ),
            ):
                self.assertEqual(
                    devctl.ensure_hris_controlled_database("payroll"), receipt
                )
        self.assertEqual(
            events,
            ["database:dwp_payroll", "boundary:payroll", "control:payroll"],
        )

    def test_failed_fresh_control_discards_only_the_database_created_by_that_run(self) -> None:
        events: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(
                    devctl,
                    "MIGRATION_CONTROL_RECEIPT_ROOT",
                    Path(directory) / "migration-control",
                ),
                patch.object(devctl, "database_exists", return_value=False),
                patch.object(
                    devctl,
                    "ensure_database",
                    side_effect=lambda database: (
                        events.append(f"database:{database}") or True
                    ),
                ),
                patch.object(
                    devctl,
                    "ensure_hris_strict_database_boundary",
                    side_effect=lambda owner: events.append(f"boundary:{owner}"),
                ),
                patch.object(
                    devctl,
                    "migration_control_reference",
                    return_value=self.REFERENCE,
                ),
                patch.object(
                    devctl,
                    "run_hris_migration_control",
                    side_effect=RuntimeError("control rejected the fresh boundary"),
                ),
                patch.object(
                    devctl,
                    "discard_fresh_database",
                    side_effect=lambda database: events.append(f"discard:{database}"),
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "control rejected"):
                    devctl.ensure_hris_controlled_database("time")
        self.assertEqual(
            events,
            ["database:dwp_time", "boundary:time", "discard:dwp_time"],
        )

    def test_fresh_bootstrap_never_discards_database_created_by_another_process(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with (
                patch.object(
                    devctl,
                    "MIGRATION_CONTROL_RECEIPT_ROOT",
                    Path(directory) / "migration-control",
                ),
                patch.object(devctl, "database_exists", return_value=False),
                patch.object(devctl, "ensure_database", return_value=False),
                patch.object(devctl, "ensure_hris_strict_database_boundary") as boundary,
                patch.object(devctl, "discard_fresh_database") as discard,
            ):
                with self.assertRaisesRegex(RuntimeError, "another process"):
                    devctl.ensure_hris_controlled_database("payroll")
            boundary.assert_not_called()
            discard.assert_not_called()

    def test_changed_reference_passes_native_predecessor_to_upgrade(self) -> None:
        old_reference = "dwp-migration-control-v2:" + "b" * 64
        previous = control_receipt("time", old_reference)
        upgraded = control_receipt(
            "time", self.REFERENCE, previous=str(previous["receiptSha256"])
        )
        with tempfile.TemporaryDirectory() as directory:
            receipt_path = Path(directory) / "migration-control" / "time.json"
            receipt_path.parent.mkdir(mode=0o700)
            receipt_path.write_text("{}", encoding="utf-8")
            receipt_path.chmod(0o600)
            with (
                patch.object(
                    devctl,
                    "MIGRATION_CONTROL_RECEIPT_ROOT",
                    receipt_path.parent,
                ),
                patch.object(devctl, "database_exists", return_value=True),
                patch.object(
                    devctl, "load_migration_control_receipt", return_value=previous
                ),
                patch.object(
                    devctl,
                    "migration_control_reference",
                    return_value=self.REFERENCE,
                ),
                patch.object(
                    devctl,
                    "run_hris_migration_control",
                    return_value=upgraded,
                ) as control,
            ):
                self.assertEqual(
                    devctl.ensure_hris_controlled_database("time"), upgraded
                )
            control.assert_called_once_with(
                "time", previous, current_reference=self.REFERENCE
            )

    def test_strict_boundary_provisions_three_roles_and_pinned_extension(self) -> None:
        with patch.object(devctl, "docker_compose") as compose:
            with patch.dict(os.environ, {}, clear=True):
                devctl.ensure_hris_strict_database_boundary("time")
        self.assertEqual(compose.call_count, 2)
        for call in compose.call_args_list:
            self.assertEqual(call.args[:3], ("exec", "-T", "postgres"))
        role_sql = compose.call_args_list[0].args[-1]
        schema_sql = compose.call_args_list[1].args[-1]
        for role in (
            "dwp_time_runtime",
            "dwp_time_migration",
            "dwp_time_projection_publisher",
        ):
            self.assertIn(f"CREATE ROLE {role}", role_sql)
            self.assertIn("NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT", role_sql)
        self.assertIn(
            "CREATE SCHEMA public AUTHORIZATION dwp_time_migration", schema_sql
        )
        self.assertIn(
            "CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'",
            schema_sql,
        )

    def test_core006_bootstrap_settings_are_injected_only_into_exact_services(
        self,
    ) -> None:
        with (
            patch.dict(os.environ, {}, clear=True),
            patch.object(
                devctl,
                "migration_control_application_environment",
                return_value={},
            ),
        ):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        product_token = "DWP_PRODUCT_SURFACE_TOKEN"
        self.assertEqual(
            environments["auth"][product_token], environments["gateway"][product_token]
        )
        for name, environment in environments.items():
            self.assertEqual(product_token in environment, name in {"auth", "gateway"})

        current_authority_token = "DWP_MEETING_FOLLOWUP_AUTHORITY_TOKEN"
        self.assertEqual(
            environments["auth"][current_authority_token],
            environments["meeting"][current_authority_token],
        )
        for name, environment in environments.items():
            self.assertEqual(
                current_authority_token in environment, name in {"auth", "meeting"}
            )
        self.assertEqual(
            environments["meeting"]["DWP_MEETING_FOLLOWUP_AUTHORITY_PROVIDER"],
            "auth-product-surface",
        )
        self.assertEqual(
            environments["meeting"]["DWP_MEETING_FOLLOWUP_AUTHORITY_ALLOW_HTTP"],
            "true",
        )
        for key in (
            "DWP_MEETING_FOLLOWUP_AUTHORITY_PROVIDER",
            "DWP_MEETING_FOLLOWUP_AUTHORITY_ALLOW_HTTP",
        ):
            self.assertTrue(
                all(
                    key not in environment
                    for name, environment in environments.items()
                    if name != "meeting"
                )
            )

        invitation_token = "DWP_MEETING_INVITATION_DELIVERY_TOKEN"
        self.assertEqual(
            environments["meeting"][invitation_token],
            "dwp-local-meeting-notification-token",
        )
        self.assertEqual(
            environments["meeting"]["DWP_MEETING_INVITATION_DELIVERY_BASE_URL"],
            "http://localhost:8008",
        )
        self.assertEqual(
            environments["meeting"]["DWP_MEETING_INVITATION_DELIVERY_ENABLED"],
            "true",
        )
        self.assertEqual(
            environments["meeting"]["DWP_MEETING_INVITATION_DELIVERY_ALLOW_HTTP"],
            "true",
        )
        notification_tokens = environments["notification"][
            "DWP_NOTIFICATION_PRODUCER_TOKENS"
        ]
        self.assertIn(
            "dwp-meeting-server=" + environments["meeting"][invitation_token],
            notification_tokens.split(","),
        )
        self.assertIn(
            "dwp-meeting-server",
            environments["notification"][
                "DWP_NOTIFICATION_ALLOWED_PRODUCERS"
            ].split(","),
        )
        self.assertIn(
            "dwp-meeting-server=meetings",
            environments["notification"][
                "DWP_NOTIFICATION_PRODUCER_APP_BINDINGS"
            ].split(","),
        )
        self.assertIn(
            "dwp-platform-server=platform|workplace",
            environments["notification"][
                "DWP_NOTIFICATION_PRODUCER_APP_BINDINGS"
            ].split(","),
        )
        for name, environment in environments.items():
            self.assertEqual(invitation_token in environment, name == "meeting")
            self.assertEqual(
                "DWP_MEETING_INVITATION_DELIVERY_ENABLED" in environment,
                name == "meeting",
            )
            self.assertEqual(
                "DWP_NOTIFICATION_ALLOWED_PRODUCERS" in environment,
                name == "notification",
            )
            self.assertEqual(
                "DWP_NOTIFICATION_PRODUCER_APP_BINDINGS" in environment,
                name == "notification",
            )

        platform_assertion_secret = "DWP_WORK_MEETING_ASSERTION_SECRET_BASE64"
        meeting_assertion_secret = "DWP_MEETING_WORK_ASSERTION_SECRET_BASE64"
        self.assertEqual(
            environments["platform"][platform_assertion_secret],
            environments["meeting"][meeting_assertion_secret],
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MEETING_SOURCE_BASE_URL"],
            "http://localhost:8009",
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MEETING_ASSERTION_KEY_ID"],
            environments["meeting"]["DWP_MEETING_WORK_ASSERTION_KEY_ID"],
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MEETING_SOURCE_ALLOW_HTTP"],
            "true",
        )
        for name, environment in environments.items():
            self.assertEqual(
                platform_assertion_secret in environment, name == "platform"
            )
            self.assertEqual(
                meeting_assertion_secret in environment, name == "meeting"
            )
            self.assertEqual(
                "DWP_WORK_MEETING_SOURCE_ALLOW_HTTP" in environment,
                name == "platform",
            )

        messaging_source_token = "DWP_MESSAGING_WORK_SOURCE_TOKEN"
        platform_messaging_source_token = "DWP_WORK_MESSAGING_SOURCE_TOKEN"
        self.assertEqual(
            environments["messaging"][messaging_source_token],
            environments["platform"][platform_messaging_source_token],
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN"],
            environments["messaging"]["DWP_MESSAGING_SERVICE_TOKEN"],
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MESSAGING_SOURCE_BASE_URL"],
            "http://localhost:8007",
        )
        self.assertEqual(
            environments["platform"]["DWP_WORK_MESSAGING_SOURCE_ALLOW_HTTP"],
            "true",
        )
        for name, environment in environments.items():
            self.assertEqual(
                messaging_source_token in environment, name == "messaging"
            )
            for platform_only_key in (
                "DWP_WORK_MESSAGING_SOURCE_BASE_URL",
                "DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN",
                platform_messaging_source_token,
                "DWP_WORK_MESSAGING_SOURCE_ALLOW_HTTP",
            ):
                self.assertEqual(
                    platform_only_key in environment,
                    name == "platform",
                    f"{platform_only_key} leaked into {name}",
                )

        with patch.dict(
            os.environ,
            {
                "DWP_MESSAGING_SERVICE_TOKEN": "overridden-messaging-service",
                "DWP_WORK_MESSAGING_SOURCE_TOKEN": "overridden-work-source-purpose",
            },
            clear=True,
        ):
            overridden_platform = devctl.service_environment("platform")
            overridden_messaging = devctl.service_environment("messaging")
        self.assertEqual(
            overridden_platform["DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN"],
            "overridden-messaging-service",
        )
        self.assertEqual(
            overridden_messaging["DWP_MESSAGING_SERVICE_TOKEN"],
            "overridden-messaging-service",
        )
        self.assertEqual(
            overridden_platform[platform_messaging_source_token],
            "overridden-work-source-purpose",
        )
        self.assertEqual(
            overridden_messaging[messaging_source_token],
            "overridden-work-source-purpose",
        )

        auth_only = {
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_VERSION": "39",
        }
        for key, value in auth_only.items():
            self.assertEqual(environments["auth"][key], value)
            self.assertTrue(
                all(key not in environment for name, environment in environments.items()
                    if name != "auth")
            )

        provider_flyway = "DWP_PROVIDER_FLYWAY_LOCATIONS"
        self.assertEqual(
            environments["provider"][provider_flyway],
            "classpath:db/migration,classpath:db/local-seed",
        )
        self.assertTrue(
            all(provider_flyway not in environment
                for name, environment in environments.items() if name != "provider")
        )

        provider_support_local_controls = {
            "DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED": "true",
            "DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED": "true",
        }
        for key, value in provider_support_local_controls.items():
            self.assertEqual(environments["provider"][key], value)
            self.assertTrue(
                all(key not in environment
                    for name, environment in environments.items()
                    if name != "provider")
            )

        exact_latches = (
            ("platform", "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED"),
            ("platform", "DWP_PLATFORM_PRODUCT_AUTHORIZATION_WORKPLACE_V4_ENABLED"),
            ("people", "DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED"),
            ("approval", "DWP_APPROVAL_PRODUCT_AUTHORIZATION_V2_ENABLED"),
        )
        for owner, key in exact_latches:
            self.assertEqual(environments[owner][key], "true")
            self.assertTrue(
                all(key not in environment
                    for name, environment in environments.items() if name != owner)
                )

        agent_key_settings = {
            "DWP_AGENT_KEY_PROVIDER": "local-inline",
            "DWP_AGENT_DATA_KEY_VERSION": "local-v1",
            "DWP_AGENT_LOCAL_GOVERNANCE_SEED_ENABLED": "true",
            "DWP_AGENT_LOCAL_GOVERNANCE_TENANT_IDS": "1",
            "DWP_AGENT_LOCAL_ACTIVITY_SEED_ENABLED": "true",
        }
        for key, value in agent_key_settings.items():
            self.assertEqual(environments["agent"][key], value)
            self.assertTrue(
                all(
                    key not in environment
                    for name, environment in environments.items()
                    if name != "agent"
                )
            )

        activity_platform_flag = "DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED"
        self.assertEqual(environments["platform"][activity_platform_flag], "true")
        self.assertTrue(
            all(
                activity_platform_flag not in environment
                for name, environment in environments.items()
                if name != "platform"
            )
        )

        delegated_identity_settings = {
            "DWP_AGENT_IDENTITY_SIGNING_SECRET": (
                "dwp-local-agent-identity-signing-secret-v1"
            ),
            "DWP_AGENT_IDENTITY_KEY_ID": "gateway-agent-v1",
        }
        for key, value in delegated_identity_settings.items():
            self.assertEqual(environments["agent"][key], value)
            self.assertEqual(environments["gateway"][key], value)
            self.assertTrue(
                all(
                    key not in environment
                    for name, environment in environments.items()
                    if name not in {"agent", "gateway"}
                )
            )

    def test_agent_receives_local_model_settings_without_leaking_to_other_services(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.local"
            path.write_text(
                "\n".join(
                    (
                        "DWP_MODEL_PROVIDER=azure_openai",
                        "AZURE_OPENAI_ENDPOINT=https://dwp.openai.azure.com/",
                        "AZURE_OPENAI_API_KEY='local azure key'",
                        "DWP_OPENAI_MODEL=dwp-gpt",
                    )
                ),
                encoding="utf-8",
            )
            path.chmod(0o600)

            with patch.object(devctl, "AGENT_LOCAL_ENV_FILE", path):
                with patch.dict(os.environ, {}, clear=True):
                    agent_environment = devctl.service_environment("agent")
                    platform_environment = devctl.service_environment("platform")

            self.assertEqual(agent_environment["DWP_MODEL_PROVIDER"], "azure_openai")
            self.assertEqual(agent_environment["AZURE_OPENAI_API_KEY"], "local azure key")
            self.assertNotIn("AZURE_OPENAI_API_KEY", platform_environment)
            self.assertNotIn("DWP_OPENAI_MODEL", platform_environment)

    def test_shell_environment_has_precedence_over_local_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.local"
            path.write_text("DWP_MODEL_PROVIDER=azure_openai\n", encoding="utf-8")
            path.chmod(0o600)

            with patch.object(devctl, "AGENT_LOCAL_ENV_FILE", path):
                with patch.dict(
                    os.environ,
                    {"DWP_MODEL_PROVIDER": "openai"},
                    clear=True,
                ):
                    environment = devctl.service_environment("agent")

            self.assertEqual(environment["DWP_MODEL_PROVIDER"], "openai")

    def test_local_file_rejects_permissions_readable_by_other_users(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.local"
            path.write_text("DWP_MODEL_PROVIDER=azure_openai\n", encoding="utf-8")
            path.chmod(0o644)

            with self.assertRaisesRegex(RuntimeError, "only be readable by its owner"):
                devctl.load_agent_local_environment(path)

    def test_local_file_rejects_unknown_environment_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.local"
            path.write_text("UNRELATED_SECRET=value\n", encoding="utf-8")
            path.chmod(0o600)

            with self.assertRaisesRegex(RuntimeError, "Invalid Agent local environment entry"):
                devctl.load_agent_local_environment(path)


class ApprovalRuntimeKeyEnvironmentTest(unittest.TestCase):
    def test_generated_keys_are_owner_only_persistent_and_disjoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "keys"
            path = root / "approval-runtime-jwks.json"
            first = devctl.load_or_create_local_approval_keys(path)
            first_bytes = path.read_bytes()
            second = devctl.load_or_create_local_approval_keys(path)

            self.assertEqual(first, second)
            self.assertEqual(first_bytes, path.read_bytes())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)
            keys = first["keys"]
            self.assertEqual(set(keys), set(devctl.LOCAL_APPROVAL_KEY_IDS))
            self.assertEqual(
                len({pair["public"]["n"] for pair in keys.values()}),
                len(devctl.LOCAL_APPROVAL_KEY_IDS),
            )

    def test_rejects_readable_or_malformed_persisted_key_material(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "approval-runtime-jwks.json"
            path.write_text('{"version":1,"keys":{}}', encoding="utf-8")
            path.chmod(0o644)
            with self.assertRaisesRegex(RuntimeError, "owner-only regular file"):
                devctl.load_or_create_local_approval_keys(path)

            path.chmod(0o600)
            with self.assertRaisesRegex(RuntimeError, "inventory is incomplete"):
                devctl.load_or_create_local_approval_keys(path)

    def test_planning_and_sla_keys_are_scoped_to_exact_services(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            environments = {
                name: devctl.service_environment(name) for name in devctl.SERVICES
            }

        auth = environments["auth"]
        approval = environments["approval"]
        notification = environments["notification"]
        self.assertEqual(auth["DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ENABLED"], "true")
        self.assertEqual(approval["DWP_APPROVAL_WORKFLOW_PLANNING_ENABLED"], "true")
        self.assertEqual(approval["DWP_APPROVAL_WORKFLOW_QUORUM_SLA_ENABLED"], "true")
        self.assertEqual(notification["DWP_NOTIFICATION_APPROVAL_SLA_ENABLED"], "true")
        self.assertEqual(
            json.loads(notification["DWP_NOTIFICATION_APPROVAL_SLA_TRANSPORT_PRIVATE_JWK"])["kid"],
            "notification-sla-recipient-transport:local",
        )
        self.assertEqual(
            json.loads(approval["DWP_APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_PRIVATE_JWK"])["kid"],
            "approval-sla-recipient-authority:local",
        )

        private_owners = {
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ATTESTATION_PRIVATE_JWK": "auth",
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_ATTESTATION_PRIVATE_JWK": "auth",
            "DWP_APPROVAL_WORKFLOW_PLANNING_OWNER_PRIVATE_KEY": "approval",
            "DWP_APPROVAL_WORKFLOW_PLANNING_TRANSPORT_PRIVATE_KEY": "approval",
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_OWNER_PRIVATE_JWK": "approval",
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_TRANSPORT_PRIVATE_JWK": "approval",
            "DWP_APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_PRIVATE_JWK": "approval",
            "DWP_NOTIFICATION_APPROVAL_SLA_TRANSPORT_PRIVATE_JWK": "notification",
        }
        for name, owner in private_owners.items():
            with self.subTest(name=name):
                parsed = json.loads(environments[owner][name])
                self.assertIn("d", parsed)
                self.assertTrue(
                    all(
                        name not in environment
                        for service, environment in environments.items()
                        if service != owner
                    )
                )

        for service, environment in environments.items():
            if service not in devctl.LOCAL_APPROVAL_RUNTIME_SERVICES:
                self.assertFalse(
                    any(key.startswith(devctl.LOCAL_APPROVAL_RUNTIME_PREFIXES) for key in environment)
                )

    def test_exact_owner_override_is_preserved_without_cross_service_leakage(self) -> None:
        with patch.dict(
            os.environ,
            {"DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ENABLED": "false"},
            clear=True,
        ):
            auth = devctl.service_environment("auth")
            approval = devctl.service_environment("approval")
        self.assertEqual(auth["DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ENABLED"], "false")
        self.assertNotIn("DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ENABLED", approval)


class MeetingInvitationDevctlTest(unittest.TestCase):
    def test_meeting_profile_starts_surface_authority_and_delivery_dependencies(
        self,
    ) -> None:
        self.assertEqual(
            [service.name for service in devctl.resolve_services(["meeting"])],
            ["auth", "people", "provider", "notification", "meeting"],
        )

        phase_by_service = {
            service_name: phase_index
            for phase_index, phase in enumerate(devctl.START_PHASES)
            for service_name in phase
        }
        self.assertLess(phase_by_service["auth"], phase_by_service["meeting"])
        self.assertLess(phase_by_service["people"], phase_by_service["meeting"])
        self.assertLess(phase_by_service["provider"], phase_by_service["meeting"])
        self.assertLess(phase_by_service["notification"], phase_by_service["meeting"])

        self.assertEqual(
            [
                service.name
                for service in devctl.resolve_services(
                    ["meeting", "gateway", "web"]
                )
            ],
            [
                "auth",
                "people",
                "provider",
                "notification",
                "meeting",
                "gateway",
                "frontend",
            ],
        )

    def test_single_meeting_delivery_token_override_is_shared_with_notification(
        self,
    ) -> None:
        custom_token = "custom-meeting-notification-token"
        with patch.dict(
            os.environ,
            {"DWP_MEETING_INVITATION_DELIVERY_TOKEN": custom_token},
            clear=True,
        ):
            meeting_environment = devctl.service_environment("meeting")
            notification_environment = devctl.service_environment("notification")

        self.assertEqual(
            meeting_environment["DWP_MEETING_INVITATION_DELIVERY_TOKEN"],
            custom_token,
        )
        self.assertIn(
            f"dwp-meeting-server={custom_token}",
            notification_environment["DWP_NOTIFICATION_PRODUCER_TOKENS"].split(","),
        )


class ServiceStartupTimeoutTest(unittest.TestCase):
    def test_uses_ci_safe_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                devctl.service_startup_timeout_seconds(),
                devctl.DEFAULT_SERVICE_STARTUP_TIMEOUT_SECONDS,
            )

    def test_accepts_bounded_canonical_override(self) -> None:
        with patch.dict(
            os.environ,
            {devctl.SERVICE_STARTUP_TIMEOUT_ENVIRONMENT: "420"},
            clear=True,
        ):
            self.assertEqual(devctl.service_startup_timeout_seconds(), 420)

    def test_rejects_non_canonical_or_out_of_range_override(self) -> None:
        invalid_values = (
            "",
            "0",
            "060",
            " 300 ",
            "59",
            "901",
            "1.5",
            "-300",
        )
        for value in invalid_values:
            with self.subTest(value=value):
                with patch.dict(
                    os.environ,
                    {devctl.SERVICE_STARTUP_TIMEOUT_ENVIRONMENT: value},
                    clear=True,
                ):
                    with self.assertRaisesRegex(RuntimeError, "must be"):
                        devctl.service_startup_timeout_seconds()


if __name__ == "__main__":
    unittest.main()
