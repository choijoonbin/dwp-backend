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
import uuid
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "hris_w1_synthetic_acceptance.py"
SPEC = importlib.util.spec_from_file_location("hris_w1_synthetic_acceptance", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)


class HrisW1SyntheticAcceptanceTest(unittest.TestCase):
    SERVICE_NAMES = (
        "auth",
        "platform",
        "people",
        "provider",
        "payroll",
        "time",
        "gateway",
    )

    def checkpoint_state(self, output: Path, base_port: int = 20000):
        state = gate.GateState(
            "w1-20261001t050403z-0123abcd",
            output,
            "2026-10-01T05:04:03Z",
        )
        state.ports.update(
            {
                name: base_port + index
                for index, name in enumerate(self.SERVICE_NAMES)
            }
        )
        state.active_version = 33
        state.active_revision = 2
        state.synthetic_tenants["A"] = {
            "tenantId": 101,
            "administratorUserId": 1001,
        }
        return state

    def expected_endpoints(self, state):
        return {
            name: f"http://127.0.0.1:{state.ports[name]}"
            for name in self.SERVICE_NAMES
        }

    def checkpoint_credentials(self):
        return (
            gate.SyntheticTenantCredential(
                lane="A",
                provider_tenant_id="00000000-0000-0000-0000-00000000000a",
                tenant_id=101,
                administrator_user_id=1001,
                person_public_id="10000000-0000-0000-0000-00000000000a",
                worker_public_id="20000000-0000-0000-0000-00000000000a",
                assignment_public_id="30000000-0000-0000-0000-00000000000a",
                actor_legal_employer_public_id="40000000-0000-0000-0000-00000000000a",
                target_person_public_id="50000000-0000-0000-0000-00000000000a",
                target_worker_public_id="60000000-0000-0000-0000-00000000000a",
                target_assignment_public_id="70000000-0000-0000-0000-00000000000a",
                target_population_revision=(
                    "a" * 32
                    + ":true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ"
                ),
                target_population_count=2,
                tenant_key="w1-a-0123abcd",
                email="hris-w1-a-0123abcd@dwp.test",
                password="Aa1!synthetic-checkpoint-password-a",
            ),
            gate.SyntheticTenantCredential(
                lane="B",
                provider_tenant_id="00000000-0000-0000-0000-00000000000b",
                tenant_id=102,
                administrator_user_id=1002,
                person_public_id="10000000-0000-0000-0000-00000000000b",
                worker_public_id="20000000-0000-0000-0000-00000000000b",
                assignment_public_id="30000000-0000-0000-0000-00000000000b",
                actor_legal_employer_public_id="40000000-0000-0000-0000-00000000000b",
                target_person_public_id="50000000-0000-0000-0000-00000000000b",
                target_worker_public_id="60000000-0000-0000-0000-00000000000b",
                target_assignment_public_id="70000000-0000-0000-0000-00000000000b",
                target_population_revision=(
                    "b" * 32
                    + ":true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ"
                ),
                target_population_count=2,
                tenant_key="w1-b-0123abcd",
                email="hris-w1-b-0123abcd@dwp.test",
                password="Bb2!synthetic-checkpoint-password-b",
            ),
        )

    def negative_observation(self, state, name, index):
        evidence_state = gate.NEGATIVE_OBSERVATION_STATES[name]
        transition, database_status, database_validity = (
            gate.NEGATIVE_PROJECTION_STATES[evidence_state]
        )
        projection_material = {
            "tenantId": 101,
            "actorId": 1001,
            "evidenceState": evidence_state,
            "projectionId": str(uuid.UUID(int=index + 1)),
            "projectionRevision": f"{index + 10:064x}",
            "contextScopeKey": "hcm-scope-" + f"{index + 20:040x}",
            "policyRevision": "rollout-" + f"{index + 30:064x}",
            "authorizationRevision": "psr-" + f"{index + 40:064x}",
            "databaseTransition": transition,
            "databaseStatus": database_status,
            "databaseValidity": database_validity,
            "databaseMemberCount": 1,
        }
        observation = {
            "assertionName": name,
            "source": "LIVE_GATEWAY_OWNER_REQUEST",
            "method": "GET",
            "path": f"/api/payroll/synthetic-negative/{index}",
            **projection_material,
            "projectionObservationSha256": gate.canonical_json_sha256(
                projection_material
            ),
            "status": gate.NEGATIVE_OWNER_STATUS,
            "errorCode": gate.NEGATIVE_OWNER_ERROR_CODE,
            "ownerErrorMessage": gate.NEGATIVE_OWNER_ERROR_MESSAGE,
            "observedAt": "2026-10-01T05:04:03Z",
            "responseBodySha256": f"{index:064x}",
        }
        observation["observationSha256"] = gate.canonical_json_sha256(
            observation
        )
        return observation

    def control_receipt(
        self,
        *,
        service="payroll",
        stream_keys=None,
    ):
        if stream_keys is None:
            stream_keys = (f"{service}-main",)
        receipt = {
            "schemaVersion": "2.0",
            "mode": "NATIVE_FRESH",
            "service": service,
            "database": f"dwp_{service}",
            "migrationPrincipal": f"dwp_{service}_migration",
            "controlReference": "dwp-migration-control-v2:" + "a" * 64,
            "previousRunReceiptSha256": "",
            "postgresVersion": "18.4",
            "temporaryPrivilegeRevoked": True,
            "streams": [
                {
                    "streamKey": stream_key,
                    "historyMaxInstalledRank": 3,
                    "historyRowCount": 3,
                    "historySha256": "b" * 64,
                    "inventoryObjectCount": 17,
                    "inventorySha256": "c" * 64,
                    "adoptionReceiptSha256": "",
                }
                for stream_key in stream_keys
            ],
        }
        digest = hashlib.sha256()
        for value in (
            "migration-control-run-receipt-v2",
            receipt["mode"],
            receipt["service"],
            receipt["database"],
            receipt["migrationPrincipal"],
            receipt["controlReference"],
            receipt["previousRunReceiptSha256"],
            receipt["postgresVersion"],
            receipt["temporaryPrivilegeRevoked"],
        ):
            gate._canonical_control_field(digest, value)
        for stream in receipt["streams"]:
            for field in (
                "streamKey",
                "historyMaxInstalledRank",
                "historyRowCount",
                "historySha256",
                "inventoryObjectCount",
                "inventorySha256",
                "adoptionReceiptSha256",
            ):
                gate._canonical_control_field(digest, stream[field])
        receipt["receiptSha256"] = digest.hexdigest()
        return receipt

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

    def test_gateway_login_bootstraps_csrf_before_each_mutation(self):
        state = self.checkpoint_state(Path("/tmp/w1-gateway-session"))
        calls = []

        def request(_state, session, **arguments):
            calls.append((arguments["name"], arguments["method"]))
            if arguments["path"] == "/api/auth/csrf":
                refresh = arguments["name"].endswith("csrf-refresh")
                self.assertEqual(
                    "csrf-token-for-login" if refresh else "",
                    session.csrf_token,
                )
                return 200, {
                    "data": {
                        "headerName": "X-XSRF-TOKEN",
                        "token": (
                            "csrf-token-after-login"
                            if refresh
                            else "csrf-token-for-login"
                        ),
                    }
                }, b""
            self.assertEqual("X-XSRF-TOKEN", session.csrf_header)
            self.assertEqual("csrf-token-for-login", session.csrf_token)
            return 200, {
                "data": {
                    "tenantId": str(session.credential.tenant_id),
                    "userId": str(session.credential.administrator_user_id),
                }
            }, b""

        with mock.patch.object(gate, "gateway_session_request", side_effect=request):
            sessions = gate.login_gateway_sessions(
                state, self.checkpoint_credentials()
            )

        self.assertEqual({"A", "B"}, set(sessions))
        self.assertEqual(
            [
                ("27-tenant-a-gateway-csrf", "GET"),
                ("28-tenant-a-gateway-login", "POST"),
                ("29-tenant-a-gateway-csrf-refresh", "GET"),
                ("27-tenant-b-gateway-csrf", "GET"),
                ("28-tenant-b-gateway-login", "POST"),
                ("29-tenant-b-gateway-csrf-refresh", "GET"),
            ],
            calls,
        )
        self.assertTrue(all(
            session.csrf_token == "csrf-token-after-login"
            for session in sessions.values()
        ))

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

        specs = gate.service_specs(secrets, "w1-20261001t050403z-0123abcd")

        self.assertEqual("true", specs["auth"].extra_environment["DWP_HRIS_SYSTEM_WAVE1_ENABLED"])
        self.assertEqual("true", specs["platform"].extra_environment["DWP_HRIS_SYSTEM_WAVE1_ENABLED"])
        self.assertEqual("true", specs["people"].extra_environment["DWP_HRIS_PERFORMANCE_WAVE1_ENABLED"])
        self.assertEqual("true", specs["payroll"].extra_environment["DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED"])
        self.assertEqual("true", specs["time"].extra_environment["DWP_TIME_WORK_REGIME_API_ENABLED"])
        self.assertEqual("false", specs["auth"].extra_environment["DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED"])
        self.assertEqual(
            secrets.gateway_agent_service_token,
            specs["gateway"].extra_environment["DWP_AGENT_SERVICE_TOKEN"],
        )
        self.assertEqual(
            secrets.gateway_agent_identity_signing_secret,
            specs["gateway"].extra_environment[
                "DWP_AGENT_IDENTITY_SIGNING_SECRET"
            ],
        )
        self.assertEqual(
            secrets.gateway_provider_support_validation_token,
            specs["gateway"].extra_environment[
                "DWP_PROVIDER_SUPPORT_VALIDATION_TOKEN"
            ],
        )
        self.assertEqual(
            secrets.provider_provisioning_token,
            specs["platform"].extra_environment["DWP_PROVIDER_PROVISIONING_TOKEN"],
        )
        self.assertEqual(
            "true",
            specs["people"].extra_environment["DWP_SYNTHETIC_IMPORT_ENABLED"],
        )
        self.assertEqual(
            secrets.synthetic_people_workforce_bootstrap_token,
            specs["people"].extra_environment[
                "DWP_HRIS_PEOPLE_WORKFORCE_SYNTHETIC_BOOTSTRAP_TOKEN"
            ],
        )
        self.assertEqual(
            "w1-20261001t050403z-0123abcd",
            specs["people"].extra_environment[
                "DWP_HRIS_PEOPLE_WORKFORCE_SYNTHETIC_BOOTSTRAP_RUN_ID"
            ],
        )

    def test_service_specs_do_not_mint_or_inject_trusted_control_evidence(self):
        specs = gate.service_specs(
            gate.RuntimeSecrets.generate(), "w1-20261001t050403z-0123abcd"
        )

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

        full = gate.parse_args(
            [
                "--skip-build",
                "--checkpoint-executable-sha256",
                "0" * 64,
                "--checkpoint-command",
                "/usr/bin/false",
            ]
        )
        self.assertFalse(full.auth_only)
        self.assertEqual("0" * 64, full.checkpoint_executable_sha256)

    def test_run_checkpoint_fails_closed_without_command(self):
        state = gate.GateState(
            "w1-20261001t050403z-0123abcd",
            Path("/tmp/hris-w1-synthetic-20261001t050403z-0123abcd"),
            "2026-10-01T05:04:03Z",
        )

        with self.assertRaises(gate.GateFailure):
            gate.run_checkpoint(
                "test-postgres", state, None, 10.0, self.checkpoint_credentials()
            )

    def test_successful_noop_command_cannot_pass_without_checkpoint_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "logs").mkdir()
            state = self.checkpoint_state(output)
            executable = Path("/usr/bin/true")
            executable_sha256 = gate.sha256_file(executable)
            state.provenance["checkpoint"] = {
                "executable": gate.validate_checkpoint_executable(
                    [str(executable)], executable_sha256
                )
            }

            with self.assertRaises(gate.GateFailure):
                gate.run_checkpoint(
                    "test-postgres",
                    state,
                    [str(executable)],
                    10.0,
                    self.checkpoint_credentials(),
                )

    def test_checkpoint_executable_requires_absolute_regular_non_symlink_hash_pin(self):
        executable = Path("/usr/bin/false")
        digest = gate.sha256_file(executable)

        provenance = gate.validate_checkpoint_executable(
            [str(executable)], digest
        )

        self.assertEqual(str(executable), provenance["path"])
        self.assertEqual(digest, provenance["sha256"])
        with self.assertRaises(gate.GateFailure):
            gate.validate_checkpoint_executable(["false"], digest)
        with self.assertRaises(gate.GateFailure):
            gate.validate_checkpoint_executable([str(executable)], "0" * 64)
        with tempfile.TemporaryDirectory() as temporary:
            symlink = Path(temporary) / "false-link"
            symlink.symlink_to(executable)
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_executable([str(symlink)], digest)

    def test_checkpoint_process_group_is_clean_on_success_timeout_and_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            state = self.checkpoint_state(output)
            success = gate.run_checkpoint_process(
                state,
                ["/usr/bin/true"],
                gate.allowlisted_host_environment(),
                output / "success.log",
                5.0,
            )
            self.assertFalse(success["residualDetected"])
            self.assertTrue(success["cleanupVerified"])

            child_code = (
                "import subprocess,sys;"
                "subprocess.Popen([sys.executable,'-c',"
                "'import time; time.sleep(60)'])"
            )
            with self.assertRaises(gate.GateFailure):
                gate.run_checkpoint_process(
                    state,
                    [sys.executable, "-c", child_code],
                    gate.allowlisted_host_environment(),
                    output / "descendant.log",
                    5.0,
                )
            descendant = state.provenance["checkpoint"]["processGroup"]
            self.assertTrue(descendant["residualDetected"])
            self.assertTrue(descendant["cleanupVerified"])
            self.assertFalse(
                gate.process_group_alive(descendant["processGroupId"])
            )

            with self.assertRaises(gate.GateFailure):
                gate.run_checkpoint_process(
                    state,
                    [sys.executable, "-c", "import time; time.sleep(60)"],
                    gate.allowlisted_host_environment(),
                    output / "timeout.log",
                    0.1,
                )
            timeout = state.provenance["checkpoint"]["processGroup"]
            self.assertTrue(timeout["timedOut"])
            self.assertTrue(timeout["cleanupVerified"])
            self.assertFalse(gate.process_group_alive(timeout["processGroupId"]))
            self.assertEqual([], state.checkpoint_process_groups)

    def test_checkpoint_command_receives_only_allowlisted_synthetic_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "logs").mkdir()
            state = self.checkpoint_state(output, 20500)
            executable = Path("/usr/bin/false")
            state.provenance["checkpoint"] = {
                "executable": gate.validate_checkpoint_executable(
                    [str(executable)], gate.sha256_file(executable)
                )
            }
            captured: dict[str, str] = {}

            def stop_after_capture(_state, _command, environment, _log_path, _timeout):
                captured.update(environment)
                raise gate.GateFailure("captured")

            ambient = {
                "PATH": os.environ.get("PATH", os.defpath),
                "DWP_PRODUCTION_ENDPOINT": "https://customer.example.invalid",
                "AWS_SECRET_ACCESS_KEY": "must-not-propagate",
            }
            with mock.patch.dict(os.environ, ambient, clear=True):
                with mock.patch.object(
                    gate, "run_checkpoint_process", side_effect=stop_after_capture
                ):
                    with self.assertRaises(gate.GateFailure):
                        gate.run_checkpoint(
                            "test-postgres",
                            state,
                            [str(executable)],
                            10.0,
                            self.checkpoint_credentials(),
                        )

            self.assertNotIn("DWP_PRODUCTION_ENDPOINT", captured)
            self.assertNotIn("AWS_SECRET_ACCESS_KEY", captured)
            self.assertEqual(state.run_id, captured["DWP_W1_RUN_ID"])
            self.assertIn("DWP_W1_CHECKPOINT_MANIFEST", captured)
            self.assertEqual("101", captured["DWP_W1_TENANT_A_ID"])
            self.assertEqual(
                "10000000-0000-0000-0000-00000000000a",
                captured["DWP_W1_TENANT_A_PERSON_PUBLIC_ID"],
            )
            self.assertEqual(
                "hris-w1-a-0123abcd@dwp.test",
                captured["DWP_W1_TENANT_A_EMAIL"],
            )
            self.assertEqual(
                "Aa1!synthetic-checkpoint-password-a",
                captured["DWP_W1_TENANT_A_PASSWORD"],
            )
            self.assertEqual(
                "40000000-0000-0000-0000-00000000000a",
                captured[
                    "DWP_W1_TENANT_A_ACTOR_LEGAL_EMPLOYER_PUBLIC_ID"
                ],
            )
            self.assertEqual(
                "60000000-0000-0000-0000-00000000000a",
                captured["DWP_W1_TENANT_A_TARGET_WORKER_PUBLIC_ID"],
            )
            self.assertEqual(
                "a" * 32
                + ":true|[]|[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS]|READ",
                captured["DWP_W1_TENANT_A_TARGET_POPULATION_REVISION"],
            )
            self.assertNotIn("HRIS_W1_TENANT_A_PASSWORD", captured)

    def test_control_receipt_requires_exact_canonical_shape_order_and_digest(self):
        receipt = self.control_receipt()
        validated = gate.validate_control_receipt(
            receipt,
            service="payroll",
            database="dwp_payroll",
            migration_principal="dwp_payroll_migration",
            mode="NATIVE_FRESH",
            control_reference="dwp-migration-control-v2:" + "a" * 64,
        )
        self.assertEqual(receipt, validated)

        mutations = []
        with_extra = json.loads(json.dumps(receipt))
        with_extra["unexpected"] = True
        mutations.append(with_extra)
        tampered = json.loads(json.dumps(receipt))
        tampered["streams"][0]["historyRowCount"] = 4
        mutations.append(tampered)
        wrong_mode = json.loads(json.dumps(receipt))
        wrong_mode["mode"] = "STRICT_FRESH"
        mutations.append(wrong_mode)
        for candidate in mutations:
            with self.subTest(candidate=candidate):
                with self.assertRaises(gate.GateFailure):
                    gate.validate_control_receipt(
                        candidate,
                        service="payroll",
                        database="dwp_payroll",
                        migration_principal="dwp_payroll_migration",
                        mode="NATIVE_FRESH",
                        control_reference="dwp-migration-control-v2:" + "a" * 64,
                    )

        people = self.control_receipt(
            service="people",
            stream_keys=("people-main", "people-performance"),
        )
        self.assertEqual(
            people,
            gate.validate_control_receipt(
                people,
                service="people",
                database="dwp_people",
                migration_principal="dwp_people_migration",
                mode="NATIVE_FRESH",
                control_reference="dwp-migration-control-v2:" + "a" * 64,
            ),
        )
        people["streams"].reverse()
        with self.assertRaises(gate.GateFailure):
            gate.validate_control_receipt(
                people,
                service="people",
                database="dwp_people",
                migration_principal="dwp_people_migration",
                mode="NATIVE_FRESH",
                control_reference="dwp-migration-control-v2:" + "a" * 64,
            )

    def test_checkpoint_manifest_requires_bound_digest_backed_assertions(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            checkpoint = output / "checkpoint"
            checkpoint.mkdir()
            state = self.checkpoint_state(output, 21000)
            configuration_id = "80000000-0000-0000-0000-000000000001"
            state.projection_feed["payrollFoundationDatabaseObservation"] = {
                "configurationId": configuration_id,
                "observationSha256": "a" * 64,
            }
            state.projection_feed["payrollFoundation"] = {
                "createCommandId": "80000000-0000-0000-0000-000000000002",
                "simulateCommandId": "80000000-0000-0000-0000-000000000003",
            }
            provenance = {
                "frontend": {},
                "runtimeManifest": {
                    "path": "runtime.json",
                    "sha256": "9" * 64,
                    "byteCount": 1024,
                    "negativeObservationAggregateSha256": "8" * 64,
                    "payrollFoundationDatabaseObservationSha256": "a" * 64,
                },
                "browser": {},
            }
            endpoints = self.expected_endpoints(state)
            assertions = []
            negative_observations = []
            for index, name in enumerate(gate.REQUIRED_CHECKPOINT_ASSERTIONS):
                evidence = checkpoint / f"assertion-{index:02d}.json"
                observations = [{"observed": True}]
                if name in gate.NEGATIVE_OBSERVATION_STATES:
                    observation = self.negative_observation(state, name, index)
                    observations = [observation]
                    negative_observations.append(observation)
                if name == "path.browser-gateway-owner-db":
                    observation = {
                        "source": "BROWSER_GATEWAY_OWNER_DB",
                        "tenantId": 101,
                        "configurationId": configuration_id,
                        "preflightObservationSha256": "a" * 64,
                        "payrollConfigurationIds": [configuration_id],
                        "workspaceResponseSha256": "b" * 64,
                        "updateCommandId": (
                            "80000000-0000-0000-0000-000000000004"
                        ),
                        "updateResponseSha256": "c" * 64,
                        "simulateCommandId": (
                            "80000000-0000-0000-0000-000000000005"
                        ),
                        "simulateResponseSha256": "d" * 64,
                        "expectedFinalVersion": 4,
                    }
                    observation["observationSha256"] = (
                        gate.canonical_json_sha256(observation)
                    )
                    observations = [observation]
                evidence.write_text(
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "runId": state.run_id,
                            "syntheticOnly": True,
                            "assertionName": name,
                            "status": "PASS",
                            "activeBundle": {"version": 33, "revision": 2},
                            "endpoints": endpoints,
                            "provenance": provenance,
                            "observations": observations,
                        }
                    ),
                    encoding="utf-8",
                )
                assertions.append(
                    {
                        "name": name,
                        "status": "PASS",
                        "evidencePath": str(evidence.relative_to(output)),
                        "evidenceSha256": hashlib.sha256(
                            evidence.read_bytes()
                        ).hexdigest(),
                    }
                )
            state.projection_feed["negativeObservations"] = {
                "schemaVersion": 1,
                "observations": negative_observations,
                "aggregateSha256": gate.canonical_json_sha256(
                    negative_observations
                ),
            }
            manifest = {
                "schemaVersion": 1,
                "runId": state.run_id,
                "syntheticOnly": True,
                "status": "PASS",
                "activeBundle": {"version": 33, "revision": 2},
                "endpoints": endpoints,
                "provenance": provenance,
                "assertions": assertions,
            }
            manifest_path = checkpoint / "manifest.json"
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )

            provenance = gate.validate_checkpoint_manifest(state, manifest_path)
            self.assertEqual(
                set(gate.REQUIRED_CHECKPOINT_ASSERTIONS),
                set(provenance["assertionEvidence"]),
            )
            self.assertEqual(
                hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                provenance["manifest"]["sha256"],
            )

            manifest["assertions"][0]["evidenceSha256"] = "0" * 64
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_manifest(state, manifest_path)

            manifest["assertions"][1]["evidencePath"] = (
                "checkpoint/assertion-01.json"
            )
            first_evidence = output / manifest["assertions"][0]["evidencePath"]
            evidence_json = json.loads(first_evidence.read_text(encoding="utf-8"))
            evidence_json["assertionName"] = "negative.wrong-binding"
            first_evidence.write_text(
                json.dumps(evidence_json), encoding="utf-8"
            )
            manifest["assertions"][0]["evidenceSha256"] = hashlib.sha256(
                first_evidence.read_bytes()
            ).hexdigest()
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_manifest(state, manifest_path)

            evidence_json["assertionName"] = gate.REQUIRED_CHECKPOINT_ASSERTIONS[0]
            first_evidence.write_text(
                json.dumps(evidence_json), encoding="utf-8"
            )
            manifest["assertions"] = assertions
            manifest["assertions"][0]["evidenceSha256"] = hashlib.sha256(
                first_evidence.read_bytes()
            ).hexdigest()
            manifest["assertions"][1]["evidencePath"] = manifest["assertions"][0][
                "evidencePath"
            ]
            manifest_path.write_text(
                json.dumps(manifest), encoding="utf-8"
            )
            with self.assertRaises(gate.GateFailure):
                gate.validate_checkpoint_manifest(state, manifest_path)

    def test_negative_observation_rejects_generic_failure_and_projection_drift(self):
        state = self.checkpoint_state(Path("/tmp/w1-negative-observation"))
        name = "negative.stale-evidence-denied"
        observation = self.negative_observation(state, name, 1)

        generic = dict(observation)
        generic["status"] = 403
        generic["errorCode"] = "E2001"
        generic["ownerErrorMessage"] = "Forbidden"
        generic["observationSha256"] = gate.canonical_json_sha256(
            {key: value for key, value in generic.items()
             if key != "observationSha256"}
        )
        with self.assertRaises(gate.GateFailure):
            gate.validate_negative_observation(
                generic, assertion_name=name, state=state
            )

        drifted = dict(observation)
        drifted["databaseStatus"] = "ACTIVE"
        drifted["observationSha256"] = gate.canonical_json_sha256(
            {key: value for key, value in drifted.items()
             if key != "observationSha256"}
        )
        with self.assertRaises(gate.GateFailure):
            gate.validate_negative_observation(
                drifted, assertion_name=name, state=state
            )

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

    def test_secret_scan_never_follows_non_regular_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "evidence"
            output.mkdir()
            external = Path(temporary) / "outside.txt"
            external.write_text("outside-must-not-be-read", encoding="utf-8")
            linked = output / "linked.log"
            linked.symlink_to(external)
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )

            findings = gate.scan_evidence_for_generated_secrets(
                state, gate.RuntimeSecrets.generate()
            )

            self.assertEqual(["linked.log:non_regular_evidence"], findings)
            self.assertFalse(linked.exists())
            self.assertEqual(
                "outside-must-not-be-read",
                external.read_text(encoding="utf-8"),
            )

    def test_teardown_raw_browser_artifact_scan_deletes_har_without_reading(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            raw = output / "crash-raw.har"
            raw.write_text("raw browser credentials", encoding="utf-8")
            linked = output / "crash-copy-raw.har.zip"
            outside = output.parent / "outside-har"
            outside.write_text("outside", encoding="utf-8")
            linked.symlink_to(outside)
            sanitized = output / "tenant-a.sanitized.har"
            sanitized.write_text("sanitized browser evidence", encoding="utf-8")
            state = gate.GateState(
                "w1-20261001t050403z-0123abcd",
                output,
                "2026-10-01T05:04:03Z",
            )

            result = gate.remove_raw_browser_artifacts(state)

            self.assertEqual([], result["errors"])
            self.assertEqual(
                ["crash-copy-raw.har.zip", "crash-raw.har"],
                sorted(result["deleted"]),
            )
            self.assertFalse(raw.exists())
            self.assertFalse(linked.exists())
            self.assertEqual(
                "sanitized browser evidence",
                sanitized.read_text(encoding="utf-8"),
            )
            self.assertEqual("outside", outside.read_text(encoding="utf-8"))

    def test_runtime_secrets_are_distinct_and_not_fixed_fixtures(self):
        first = gate.RuntimeSecrets.generate()
        second = gate.RuntimeSecrets.generate()

        first_values = set(first.__dict__.values())
        self.assertEqual(29, len(first.__dict__))
        self.assertEqual(len(first.__dict__), len(first_values))
        self.assertTrue(all(len(value) >= 30 for value in first_values))
        self.assertNotEqual(first, second)


if __name__ == "__main__":
    unittest.main()
