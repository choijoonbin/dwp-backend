import contextlib
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "hris_w1_synthetic_acceptance.py"
SPEC = importlib.util.spec_from_file_location("hris_w1_synthetic_acceptance", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class HrisW1SyntheticAcceptanceTest(unittest.TestCase):
    def test_run_id_and_owned_resource_names_are_deterministic_and_scoped(self):
        instant = dt.datetime(2026, 10, 1, 5, 4, 3, tzinfo=dt.timezone.utc)

        run_id = gate.make_run_id(instant, "0123abcd")

        self.assertEqual("w1-20261001t050403z-0123abcd", run_id)
        self.assertEqual(
            "hris-w1-synthetic-20261001t050403z-0123abcd",
            gate.output_basename(run_id),
        )
        self.assertEqual(
            "dwp-hris-w1-20261001t050403z-0123abcd-postgres",
            gate.resource_name(run_id, "postgres"),
        )

    def test_resource_name_rejects_broad_or_unscoped_targets(self):
        for run_id in ("", "w1-latest", "../../shared", "w1-20261001t050403z-UPPER"):
            with self.subTest(run_id=run_id):
                with self.assertRaises(gate.GateFailure):
                    gate.resource_name(run_id, "postgres")

        with self.assertRaises(gate.GateFailure):
            gate.resource_name("w1-20261001t050403z-0123abcd", "../postgres")

    def test_output_directory_must_be_new_and_exactly_bound_to_run(self):
        run_id = "w1-20261001t050403z-0123abcd"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            valid = root / gate.output_basename(run_id)
            self.assertEqual(valid, gate.validate_new_output_dir(valid, run_id))

            valid.mkdir()
            with self.assertRaises(gate.GateFailure):
                gate.validate_new_output_dir(valid, run_id)

            wrong = root / "hris-w1-synthetic-unbound"
            with self.assertRaises(gate.GateFailure):
                gate.validate_new_output_dir(wrong, run_id)

    def test_http_summary_excludes_contract_body_and_unknown_fields(self):
        summary = gate.summarize_http_body(
            {
                "version": 33,
                "bundleStatus": "ACTIVE",
                "activeRevision": 2,
                "checksum": gate.V33_CHECKSUM,
                "contract": {"routes": ["large"]},
                "syntheticSecret": "must-not-be-copied",
            }
        )

        self.assertEqual(
            {
                "version": 33,
                "bundleStatus": "ACTIVE",
                "activeRevision": 2,
                "checksum": gate.V33_CHECKSUM,
            },
            summary,
        )

    def test_service_specs_enable_only_the_explicit_w1_owner_slices(self):
        secrets = gate.RuntimeSecrets.generate()

        specs = gate.service_specs(secrets)

        self.assertEqual("true", specs["auth"].extra_environment["DWP_HRIS_SYSTEM_WAVE1_ENABLED"])
        self.assertEqual("true", specs["platform"].extra_environment["DWP_HRIS_SYSTEM_WAVE1_ENABLED"])
        self.assertEqual("true", specs["people"].extra_environment["DWP_HRIS_PERFORMANCE_WAVE1_ENABLED"])
        self.assertEqual("true", specs["payroll"].extra_environment["DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED"])
        self.assertEqual("true", specs["time"].extra_environment["DWP_TIME_WORK_REGIME_API_ENABLED"])
        self.assertEqual("false", specs["auth"].extra_environment["DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED"])

    def test_service_specs_do_not_mint_or_inject_trusted_control_evidence(self):
        specs = gate.service_specs(gate.RuntimeSecrets.generate())

        configured_names = {
            name
            for spec in specs.values()
            for name in spec.extra_environment
        }
        forbidden_fragments = (
            "ADOPTION_RECEIPT",
            "ADOPTION_CONTROL_REFERENCE",
            "MIGRATION_CONTROL_RUN_RECEIPT",
            "MIGRATION_CONTROL_REFERENCE",
        )
        self.assertFalse(
            {
                name
                for name in configured_names
                if any(fragment in name for fragment in forbidden_fragments)
            }
        )

    def test_runtime_environment_does_not_inherit_ambient_dwp_or_spring_values(self):
        state = gate.GateState(
            "w1-20261001t050403z-0123abcd",
            Path("/tmp/hris-w1-synthetic-20261001t050403z-0123abcd"),
            "2026-10-01T05:04:03Z",
        )
        secrets = gate.RuntimeSecrets.generate()
        ambient = {
            "DWP_PRODUCTION_ENDPOINT": "https://customer.example.invalid",
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://shared.invalid/customer",
            "AWS_SECRET_ACCESS_KEY": "must-not-propagate",
            "PATH": os.environ.get("PATH", os.defpath),
        }

        with mock.patch.dict(os.environ, ambient, clear=True):
            environment = gate.common_environment(state, secrets, 54321, 63791)

        self.assertNotIn("DWP_PRODUCTION_ENDPOINT", environment)
        self.assertNotIn("SPRING_DATASOURCE_URL", environment)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", environment)
        self.assertEqual("127.0.0.1", environment["DB_HOST"])
        self.assertEqual("127.0.0.1", environment["REDIS_HOST"])

    def test_full_mode_requires_checkpoint_but_auth_only_does_not(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                gate.parse_args(["--skip-build"])

        arguments = gate.parse_args(["--auth-only", "--skip-build"])
        self.assertTrue(arguments.auth_only)
        self.assertIsNone(arguments.checkpoint_command)

    def test_run_checkpoint_fails_closed_without_command(self):
        state = gate.GateState(
            "w1-20261001t050403z-0123abcd",
            Path("/tmp/hris-w1-synthetic-20261001t050403z-0123abcd"),
            "2026-10-01T05:04:03Z",
        )

        with self.assertRaises(gate.GateFailure):
            gate.run_checkpoint(state, None, 10.0)

    def test_successful_noop_command_cannot_pass_without_checkpoint_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "logs").mkdir()
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )
            state.ports.update(
                {
                    name: 20000 + index
                    for index, name in enumerate(
                        (
                            "auth",
                            "platform",
                            "people",
                            "provider",
                            "payroll",
                            "time",
                            "gateway",
                        )
                    )
                }
            )
            state.active_version = 33
            state.active_revision = 2

            with self.assertRaises(gate.GateFailure):
                gate.run_checkpoint(state, ["/usr/bin/true"], 10.0)

    def test_checkpoint_command_receives_only_allowlisted_synthetic_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "logs").mkdir()
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )
            state.ports.update(
                {
                    name: 20500 + index
                    for index, name in enumerate(
                        (
                            "auth",
                            "platform",
                            "people",
                            "provider",
                            "payroll",
                            "time",
                            "gateway",
                        )
                    )
                }
            )
            state.active_version = 33
            state.active_revision = 2
            captured: dict[str, str] = {}

            def stop_after_capture(*_args, **kwargs):
                captured.update(kwargs["environment"])
                raise gate.GateFailure("captured")

            ambient = {
                "PATH": os.environ.get("PATH", os.defpath),
                "DWP_PRODUCTION_ENDPOINT": "https://customer.example.invalid",
                "AWS_SECRET_ACCESS_KEY": "must-not-propagate",
            }
            with mock.patch.dict(os.environ, ambient, clear=True):
                with mock.patch.object(
                    gate, "run_checked", side_effect=stop_after_capture
                ):
                    with self.assertRaises(gate.GateFailure):
                        gate.run_checkpoint(state, ["checkpoint"], 10.0)

            self.assertNotIn("DWP_PRODUCTION_ENDPOINT", captured)
            self.assertNotIn("AWS_SECRET_ACCESS_KEY", captured)
            self.assertEqual(state.run_id, captured["DWP_W1_RUN_ID"])
            self.assertIn("DWP_W1_CHECKPOINT_MANIFEST", captured)

    def test_checkpoint_manifest_requires_bound_digest_backed_assertions(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            checkpoint = output / "checkpoint"
            checkpoint.mkdir()
            evidence = checkpoint / "owner-api.json"
            evidence.write_text('{"status":"ok"}\n', encoding="utf-8")
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )
            state.ports.update(
                {
                    name: 21000 + index
                    for index, name in enumerate(
                        (
                            "auth",
                            "platform",
                            "people",
                            "provider",
                            "payroll",
                            "time",
                            "gateway",
                        )
                    )
                }
            )
            manifest = {
                "schemaVersion": 1,
                "runId": state.run_id,
                "syntheticOnly": True,
                "status": "PASS",
                "activeBundle": {"version": 33, "revision": 2},
                "endpoints": {
                    name: f"http://127.0.0.1:{state.ports[name]}"
                    for name in (
                        "auth",
                        "platform",
                        "people",
                        "provider",
                        "payroll",
                        "time",
                        "gateway",
                    )
                },
                "assertions": [
                    {
                        "name": name,
                        "status": "PASS",
                        "evidencePath": "checkpoint/owner-api.json",
                        "evidenceSha256": hashlib.sha256(
                            evidence.read_bytes()
                        ).hexdigest(),
                    }
                    for name in gate.REQUIRED_CHECKPOINT_ASSERTIONS
                ],
            }
            manifest_path = checkpoint / "manifest.json"
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )

            self.assertEqual(
                list(gate.REQUIRED_CHECKPOINT_ASSERTIONS),
                gate.validate_checkpoint_manifest(state, manifest_path),
            )

            manifest["assertions"][0]["evidenceSha256"] = "0" * 64
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_manifest(state, manifest_path)

            manifest["assertions"] = manifest["assertions"][:1]
            manifest["assertions"][0]["evidenceSha256"] = hashlib.sha256(
                evidence.read_bytes()
            ).hexdigest()
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_manifest(state, manifest_path)

    def test_secret_scan_deletes_leaking_evidence_and_reports_only_field_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )
            secrets = gate.RuntimeSecrets.generate()
            leaked = output / "leaked.log"
            leaked.write_text(
                f"token={secrets.activation_token}\n", encoding="utf-8"
            )

            findings = gate.scan_evidence_for_generated_secrets(state, secrets)

            self.assertEqual(["leaked.log:activation_token"], findings)
            self.assertFalse(leaked.exists())
            self.assertNotIn(secrets.activation_token, json.dumps(findings))

    def test_runtime_secrets_are_distinct_and_not_fixed_fixtures(self):
        first = gate.RuntimeSecrets.generate()
        second = gate.RuntimeSecrets.generate()

        first_values = set(first.__dict__.values())
        self.assertEqual(len(first.__dict__), len(first_values))
        self.assertTrue(all(len(value) >= 30 for value in first_values))
        self.assertNotEqual(first, second)


if __name__ == "__main__":
    unittest.main()
