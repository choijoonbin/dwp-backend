from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import devctl


class AgentLocalEnvironmentTest(unittest.TestCase):
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

    def test_core006_bootstrap_settings_are_injected_only_into_exact_services(
        self,
    ) -> None:
        with patch.dict(os.environ, {}, clear=True):
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
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_VERSION": "28",
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
    def test_meeting_profile_starts_invitation_delivery_dependencies(self) -> None:
        self.assertEqual(
            [service.name for service in devctl.resolve_services(["meeting"])],
            ["auth", "notification", "meeting"],
        )

        phase_by_service = {
            service_name: phase_index
            for phase_index, phase in enumerate(devctl.START_PHASES)
            for service_name in phase
        }
        self.assertLess(phase_by_service["auth"], phase_by_service["meeting"])
        self.assertLess(phase_by_service["notification"], phase_by_service["meeting"])

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
