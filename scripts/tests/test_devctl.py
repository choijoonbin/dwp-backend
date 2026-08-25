from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import devctl


class AgentLocalEnvironmentTest(unittest.TestCase):
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

        auth_only = {
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED": "true",
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

        exact_latches = {
            "platform": "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED",
            "people": "DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED",
            "approval": "DWP_APPROVAL_PRODUCT_AUTHORIZATION_V2_ENABLED",
        }
        for owner, key in exact_latches.items():
            self.assertEqual(environments[owner][key], "true")
            self.assertTrue(
                all(key not in environment
                    for name, environment in environments.items() if name != owner)
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


if __name__ == "__main__":
    unittest.main()
