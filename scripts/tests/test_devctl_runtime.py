from __future__ import annotations

import copy
import itertools
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import warnings
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock, call, patch

from scripts import devctl


def process_state(
    service: devctl.Service,
    *,
    pid: int = 1234,
    identity: dict[str, object] | None = None,
    artifacts: dict[str, object] | None = None,
    command: tuple[str, ...] | None = None,
) -> dict[str, object]:
    value: dict[str, object] = {
        "pid": pid,
        "port": service.port,
        "command": list(command or service.command),
        "log": f"/tmp/{service.name}.log",
    }
    if identity is not None:
        value["processIdentity"] = identity
    if artifacts is not None:
        value["runtimeArtifacts"] = artifacts
    return value


class RuntimeArtifactRegistryTest(unittest.TestCase):
    def test_registry_covers_every_shared_artifact_consumer_explicitly(self) -> None:
        core_consumers = {
            "auth",
            "platform",
            "people",
            "provider",
            "approval",
            "space",
            "messaging",
            "notification",
            "meeting",
            "time",
            "payroll",
        }
        self.assertEqual(
            core_consumers | {"gateway"},
            set(devctl.SERVICE_SHARED_RUNTIME_ARTIFACTS),
        )
        for service in core_consumers:
            self.assertEqual(
                devctl.CORE_SHARED_RUNTIME_ARTIFACTS,
                devctl.SERVICE_SHARED_RUNTIME_ARTIFACTS[service],
            )
        self.assertEqual(
            ("dwp-audit", "dwp-observability"),
            devctl.SERVICE_SHARED_RUNTIME_ARTIFACTS["gateway"],
        )

    def test_registry_matches_transitive_shared_gradle_dependencies(self) -> None:
        service_modules = {
            "auth": "dwp-auth-server",
            "platform": "dwp-platform-server",
            "people": "dwp-people-server",
            "provider": "dwp-provider-server",
            "approval": "dwp-approval-server",
            "space": "dwp-space-server",
            "messaging": "dwp-messaging-server",
            "notification": "dwp-notification-server",
            "meeting": "dwp-meeting-server",
            "time": "dwp-time-server",
            "payroll": "dwp-payroll-server",
            "gateway": "dwp-gateway",
        }
        project_reference = re.compile(r"project\(['\"]:([^'\"]+)['\"]\)")

        def direct_dependencies(module: str) -> set[str]:
            build_file = devctl.BACKEND_ROOT / module / "build.gradle"
            return set(project_reference.findall(build_file.read_text(encoding="utf-8")))

        shared = set(devctl.SHARED_RUNTIME_ARTIFACT_DIRECTORIES)
        for service, module in service_modules.items():
            closure: set[str] = set()
            pending = [module]
            while pending:
                current = pending.pop()
                for dependency in direct_dependencies(current):
                    if dependency in shared and dependency not in closure:
                        closure.add(dependency)
                        pending.append(dependency)
            self.assertEqual(
                closure,
                set(devctl.SERVICE_SHARED_RUNTIME_ARTIFACTS[service]),
                service,
            )


class RuntimeArtifactFingerprintTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.libs = self.root / "dwp-core" / "build" / "libs"
        self.libs.mkdir(parents=True)
        self.artifact = self.libs / "dwp-core-1.0.0-plain.jar"
        self.artifact.write_bytes(b"artifact-v1")
        self.patches = (
            patch.object(devctl, "BACKEND_ROOT", self.root),
            patch.dict(
                devctl.SHARED_RUNTIME_ARTIFACT_DIRECTORIES,
                {"dwp-core": self.libs},
                clear=True,
            ),
            patch.dict(
                devctl.SERVICE_SHARED_RUNTIME_ARTIFACTS,
                {"meeting": ("dwp-core",)},
                clear=True,
            ),
        )
        for active_patch in self.patches:
            active_patch.start()

    def tearDown(self) -> None:
        for active_patch in reversed(self.patches):
            active_patch.stop()
        self.temporary_directory.cleanup()

    def test_fingerprint_detects_generation_and_content_changes(self) -> None:
        service = devctl.SERVICES["meeting"]
        baseline = devctl.capture_service_runtime_artifacts(service)
        fingerprint = baseline["dwp-core"]
        metadata = self.artifact.stat()
        self.assertEqual(metadata.st_dev, fingerprint["device"])
        self.assertEqual(metadata.st_ino, fingerprint["inode"])
        self.assertEqual(metadata.st_mtime_ns, fingerprint["mtimeNs"])
        self.assertEqual(metadata.st_ctime_ns, fingerprint["ctimeNs"])
        state = process_state(service, artifacts=baseline)

        self.assertFalse(devctl.service_artifact_staleness(service, state)[0])
        metadata = self.artifact.stat()
        os.utime(
            self.artifact,
            ns=(metadata.st_atime_ns, metadata.st_mtime_ns + 1_000_000),
        )
        stale, reason = devctl.service_artifact_staleness(service, state)
        self.assertTrue(stale)
        self.assertEqual("dwp-core changed", reason)

        baseline = devctl.capture_service_runtime_artifacts(service)
        state["runtimeArtifacts"] = baseline
        metadata = self.artifact.stat()
        self.artifact.write_bytes(b"artifact-v2")
        os.utime(self.artifact, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
        self.assertTrue(devctl.service_artifact_staleness(service, state)[0])

    def test_missing_multiple_symlink_and_unstable_artifacts_fail_closed(self) -> None:
        service = devctl.SERVICES["meeting"]
        self.artifact.unlink()
        with self.assertRaisesRegex(devctl.MissingRuntimeArtifactError, "missing"):
            devctl.capture_service_runtime_artifacts(service)

        self.artifact.write_bytes(b"one")
        (self.libs / "dwp-core-2.0.0-plain.jar").write_bytes(b"two")
        with self.assertRaisesRegex(RuntimeError, "found 2"):
            devctl.capture_service_runtime_artifacts(service)

        (self.libs / "dwp-core-2.0.0-plain.jar").unlink()
        target = self.root / "target.jar"
        target.write_bytes(b"target")
        self.artifact.unlink()
        self.artifact.symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, "regular file"):
            devctl.capture_service_runtime_artifacts(service)

        self.artifact.unlink()
        self.artifact.write_bytes(b"unstable")
        counter = itertools.count()
        with patch.object(
            devctl,
            "_artifact_stat_signature",
            side_effect=lambda _metadata: (0, 0, 0, 0, 0, next(counter)),
        ):
            with self.assertRaisesRegex(RuntimeError, "changed while"):
                devctl.capture_service_runtime_artifacts(service)

    def test_ctime_only_instability_is_retried_then_rejected(self) -> None:
        counter = itertools.count()
        with patch.object(
            devctl,
            "_artifact_stat_signature",
            side_effect=lambda _metadata: (0, 0, 0, 8, 100, next(counter)),
        ):
            with self.assertRaisesRegex(RuntimeError, "changed while"):
                devctl.capture_service_runtime_artifacts(
                    devctl.SERVICES["meeting"]
                )

    def test_untracked_process_is_stale_but_artifact_is_still_validated(self) -> None:
        service = devctl.SERVICES["meeting"]
        stale, reason = devctl.service_artifact_staleness(
            service, process_state(service)
        )
        self.assertTrue(stale)
        self.assertEqual("untracked artifacts", reason)

        self.artifact.unlink()
        stale, reason = devctl.service_artifact_staleness(
            service, process_state(service)
        )
        self.assertTrue(stale)
        self.assertEqual("shared runtime artifact missing", reason)


class RuntimeStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name) / ".dev-runtime"
        self.state_file = self.root / "processes.json"
        self.lock_file = self.root / "devctl.lock"
        self.patches = (
            patch.object(devctl, "RUNTIME_ROOT", self.root),
            patch.object(devctl, "STATE_FILE", self.state_file),
            patch.object(devctl, "STATE_LOCK_FILE", self.lock_file),
        )
        for active_patch in self.patches:
            active_patch.start()

    def tearDown(self) -> None:
        for active_patch in reversed(self.patches):
            active_patch.stop()
        self.temporary_directory.cleanup()

    def test_state_save_is_private_atomic_and_round_trips(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        expected = {"meeting": process_state(meeting)}

        devctl.save_state(expected)

        self.assertEqual(expected, devctl.load_state())
        self.assertEqual(0o600, stat.S_IMODE(self.state_file.stat().st_mode))
        self.assertEqual([], list(self.root.glob(".processes.json.*.tmp")))

    def test_malformed_duplicate_and_non_object_state_fail_closed(self) -> None:
        self.root.mkdir(parents=True)
        invalid_values = (
            "not-json",
            "[]",
            '{"meeting":{},"meeting":{}}',
        )
        for invalid in invalid_values:
            with self.subTest(value=invalid):
                self.state_file.write_text(invalid, encoding="utf-8")
                with self.assertRaises(RuntimeError):
                    devctl.load_state()

    def test_state_lock_is_owner_only_and_reusable(self) -> None:
        with devctl.state_change_lock():
            self.assertTrue(self.lock_file.is_file())
            self.assertEqual(0o600, stat.S_IMODE(self.lock_file.stat().st_mode))
        with devctl.state_change_lock():
            self.assertTrue(self.lock_file.is_file())

    def test_state_with_group_or_other_permissions_is_rejected(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        devctl.save_state({"meeting": process_state(meeting)})
        self.state_file.chmod(0o640)
        with self.assertRaisesRegex(RuntimeError, "owner-controlled"):
            devctl.load_state()

    def test_explicit_migration_accepts_legacy_0644_then_rewrites_0600(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        self.root.mkdir(parents=True)
        self.state_file.write_text(
            json.dumps({"meeting": process_state(meeting)}), encoding="utf-8"
        )
        self.state_file.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, "owner-controlled"):
            devctl.load_state()

        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "capture_process_identity", return_value=identity
            ),
            patch.object(devctl.time, "sleep"),
            patch.object(devctl.os, "killpg") as killpg,
        ):
            devctl.migrate_legacy_process_identities(("meeting",), True)

        migrated = devctl.load_state()
        self.assertEqual(identity, migrated["meeting"]["processIdentity"])
        self.assertEqual(0o600, stat.S_IMODE(self.state_file.stat().st_mode))
        killpg.assert_not_called()

    def test_legacy_migration_rejects_group_writable_state(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        self.root.mkdir(parents=True)
        self.state_file.write_text(
            json.dumps({"meeting": process_state(meeting)}), encoding="utf-8"
        )
        self.state_file.chmod(0o664)
        with patch.object(devctl.os, "killpg") as killpg:
            with self.assertRaisesRegex(RuntimeError, "owner-controlled"):
                devctl.migrate_legacy_process_identities(("meeting",), True)
        killpg.assert_not_called()


class ProcessIdentityTest(unittest.TestCase):
    def test_identity_binds_pid_group_start_time_and_expected_command(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        command = (
            "/usr/bin/java org.gradle.wrapper.GradleWrapperMain "
            "--no-daemon :dwp-meeting-server:bootRun"
        )
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl.os, "getpgid", return_value=1234),
            patch.object(devctl, "_ps_value", side_effect=["started", command]),
        ):
            identity = devctl.capture_process_identity(
                1234, meeting.name, meeting.command
            )

        self.assertEqual(1234, identity["pgid"])
        self.assertEqual("started", identity["startedAt"])
        self.assertEqual(64, len(identity["commandSha256"]))

        state = process_state(
            meeting,
            identity={**identity, "startedAt": "different"},
        )
        with patch.object(devctl, "capture_process_identity", return_value=identity):
            with self.assertRaisesRegex(RuntimeError, "identity changed"):
                devctl.verify_managed_process(meeting, state)

    def test_verification_uses_recorded_launch_spec_not_current_environment(self) -> None:
        cases = (
            (
                devctl.SERVICES["frontend"],
                ("/old/node", "/old/corepack", "yarn", "dev", "--host", "0.0.0.0"),
                ("/new/node", "/new/corepack", "yarn", "dev", "--host", "0.0.0.0"),
            ),
            (
                devctl.SERVICES["agent"],
                (
                    "/old/.venv/bin/python",
                    "-m",
                    "uvicorn",
                    "--app-dir",
                    "src",
                    "dwp_agent.main:app",
                    "--reload",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "8010",
                ),
                (
                    "python3",
                    "-m",
                    "uvicorn",
                    "--app-dir",
                    "src",
                    "dwp_agent.main:app",
                    "--reload",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "8010",
                ),
            ),
        )
        for original, recorded_command, current_command in cases:
            with self.subTest(service=original.name):
                current_service = devctl.Service(
                    original.name,
                    original.cwd,
                    current_command,
                    original.port,
                    original.health_path,
                )
                actual = " ".join(recorded_command)
                expected_identity = {
                    "pgid": 1234,
                    "startedAt": "started",
                    "commandSha256": devctl.hashlib.sha256(
                        actual.encode("utf-8")
                    ).hexdigest(),
                }
                state = process_state(
                    current_service,
                    identity=expected_identity,
                    command=recorded_command,
                )
                with (
                    patch.object(devctl, "process_alive", return_value=True),
                    patch.object(devctl.os, "getpgid", return_value=1234),
                    patch.object(
                        devctl, "_ps_value", side_effect=["started", actual]
                    ),
                ):
                    self.assertEqual(
                        expected_identity,
                        devctl.verify_managed_process(current_service, state),
                    )

    def test_current_command_match_cannot_override_recorded_launch_spec(self) -> None:
        frontend = devctl.SERVICES["frontend"]
        recorded = ("/old/node", "/old/corepack", "yarn", "dev")
        actual = "node /new/corepack yarn dev --host 0.0.0.0"
        state = process_state(frontend, command=recorded)
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl.os, "getpgid", return_value=1234),
            patch.object(devctl, "_ps_value", side_effect=["started", actual]),
        ):
            with self.assertRaisesRegex(RuntimeError, "recorded launch command"):
                devctl.verify_managed_process(frontend, state)

    def test_legacy_identity_is_never_auto_adopted_for_signalling(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "started",
            "commandSha256": "a" * 64,
        }
        state = process_state(meeting)
        with patch.object(
            devctl, "capture_process_identity", return_value=identity
        ):
            with self.assertRaisesRegex(RuntimeError, "legacy/unrecorded"):
                devctl.verify_managed_process(meeting, state)
        self.assertNotIn("processIdentity", state)


class LegacyIdentityMigrationTest(unittest.TestCase):
    def test_confirmation_is_required_before_any_state_read(self) -> None:
        with patch.object(devctl, "load_legacy_migration_state") as load:
            with self.assertRaisesRegex(RuntimeError, "requires --yes"):
                devctl.migrate_legacy_process_identities(("auth",), False)
        load.assert_not_called()

    def test_explicit_migration_stably_records_all_identities_without_signal(self) -> None:
        auth = devctl.SERVICES["auth"]
        notification = devctl.SERVICES["notification"]
        auth_identity = {
            "pgid": 101,
            "startedAt": "auth-start",
            "commandSha256": "a" * 64,
        }
        notification_identity = {
            "pgid": 102,
            "startedAt": "notification-start",
            "commandSha256": "b" * 64,
        }
        state = {
            "auth": process_state(auth, pid=101),
            "notification": process_state(notification, pid=102),
        }
        with (
            patch.object(devctl, "load_legacy_migration_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "capture_process_identity",
                side_effect=[
                    auth_identity,
                    auth_identity,
                    notification_identity,
                    notification_identity,
                ],
            ) as capture,
            patch.object(devctl.time, "sleep") as sleep,
            patch.object(devctl, "save_state") as save,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            devctl.migrate_legacy_process_identities(
                ("auth", "notification"), True
            )

        self.assertEqual(auth_identity, state["auth"]["processIdentity"])
        self.assertEqual(
            notification_identity,
            state["notification"]["processIdentity"],
        )
        self.assertEqual(4, capture.call_count)
        self.assertEqual(2, sleep.call_count)
        save.assert_called_once_with(state)
        killpg.assert_not_called()

    def test_unstable_candidate_prevents_all_migration_and_signalling(self) -> None:
        auth = devctl.SERVICES["auth"]
        meeting = devctl.SERVICES["meeting"]
        stable = {
            "pgid": 101,
            "startedAt": "start",
            "commandSha256": "a" * 64,
        }
        changed = {**stable, "startedAt": "changed"}
        state = {
            "auth": process_state(auth, pid=101),
            "meeting": process_state(meeting, pid=102),
        }
        with (
            patch.object(devctl, "load_legacy_migration_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "capture_process_identity",
                side_effect=[stable, stable, stable, changed],
            ),
            patch.object(devctl.time, "sleep"),
            patch.object(devctl, "save_state") as save,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "no state was updated"):
                devctl.migrate_legacy_process_identities(
                    ("auth", "meeting"), True
                )

        self.assertNotIn("processIdentity", state["auth"])
        self.assertNotIn("processIdentity", state["meeting"])
        save.assert_not_called()
        killpg.assert_not_called()


class FakeResponse:
    def __init__(
        self,
        body: bytes,
        *,
        status_code: int = 200,
        content_type: str = "application/json",
    ) -> None:
        self.status = status_code
        self.body = body
        self.content_type = content_type

    def getheader(self, name: str, default: str = "") -> str:
        return self.content_type if name == "Content-Type" else default

    def read(self, _maximum: int) -> bytes:
        return self.body


class FakeConnection:
    def __init__(self, response: FakeResponse) -> None:
        self.response = response
        self.request_arguments: tuple[object, ...] | None = None
        self.request_keywords: dict[str, object] | None = None
        self.closed = False

    def request(self, *arguments: object, **keywords: object) -> None:
        self.request_arguments = arguments
        self.request_keywords = keywords

    def getresponse(self) -> FakeResponse:
        return self.response

    def close(self) -> None:
        self.closed = True


class MeetingReadinessProbeTest(unittest.TestCase):
    @staticmethod
    def valid_payload() -> bytes:
        return json.dumps(
            {
                "success": True,
                "status": "SUCCESS",
                "data": {
                    "serverNow": "2026-10-27T07:00:00Z",
                    "timeZone": "UTC",
                    "today": [],
                    "recent": [],
                    "metrics": {
                        "meetingsToday": 0,
                        "meetingMinutesToday": 0,
                        "waitingForApproval": 0,
                        "qualityScore": None,
                        "averageJoinSeconds": None,
                    },
                    "capabilities": {
                        "available": False,
                        "provider": "DISABLED",
                    },
                },
            }
        ).encode("utf-8")

    def test_probe_uses_direct_authenticated_home_contract(self) -> None:
        connection = FakeConnection(FakeResponse(self.valid_payload()))
        constructor = Mock(return_value=connection)
        with (
            patch.object(devctl.http.client, "HTTPConnection", constructor),
            patch.object(
                devctl,
                "service_environment",
                return_value={"DWP_MEETING_SERVICE_TOKEN": "local-secret"},
            ),
        ):
            self.assertTrue(devctl.meeting_home_ready(devctl.SERVICES["meeting"]))

        constructor.assert_called_once_with("127.0.0.1", 8009, timeout=2)
        self.assertEqual(("GET", "/v1/home?timeZone=UTC"), connection.request_arguments)
        headers = connection.request_keywords["headers"]  # type: ignore[index]
        self.assertEqual("local-secret", headers["X-DWP-Service-Token"])
        self.assertEqual("900018", headers["X-DWP-User-ID"])
        self.assertEqual("1", headers["X-DWP-Tenant-ID"])
        self.assertEqual("APP.MEETINGS:VIEW", headers["X-DWP-Permissions"])
        self.assertEqual("000", headers["X-DWP-Rollout-State"])
        self.assertTrue(connection.closed)

    def test_probe_rejects_http_content_and_semantic_failures(self) -> None:
        invalid_responses = (
            FakeResponse(self.valid_payload(), status_code=403),
            FakeResponse(self.valid_payload(), status_code=500),
            FakeResponse(self.valid_payload(), content_type="text/html"),
            FakeResponse(b"not-json"),
            FakeResponse(json.dumps({"success": True, "status": "SUCCESS"}).encode()),
            FakeResponse(b"x" * (devctl.MEETING_READINESS_MAXIMUM_RESPONSE_BYTES + 1)),
        )
        for response in invalid_responses:
            with self.subTest(status=response.status, size=len(response.body)):
                connection = FakeConnection(response)
                with (
                    patch.object(
                        devctl.http.client,
                        "HTTPConnection",
                        return_value=connection,
                    ),
                    patch.object(
                        devctl,
                        "service_environment",
                        return_value={"DWP_MEETING_SERVICE_TOKEN": "local-secret"},
                    ),
                ):
                    self.assertFalse(
                        devctl.meeting_home_ready(devctl.SERVICES["meeting"])
                    )

    def test_probe_rejects_structurally_hollow_home_payloads(self) -> None:
        payload = json.loads(self.valid_payload())
        mutations = (
            lambda data: data.update(serverNow="not-a-time"),
            lambda data: data.update(serverNow="2026-10-27T07:00:00"),
            lambda data: data["metrics"].pop("meetingsToday"),
            lambda data: data["metrics"].update(waitingForApproval=True),
            lambda data: data["metrics"].update(meetingsToday=10**400),
            lambda data: data["metrics"].update(meetingMinutesToday=-1),
            lambda data: data["capabilities"].update(available="false"),
            lambda data: data["capabilities"].update(provider=""),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                candidate = json.loads(json.dumps(payload))
                mutate(candidate["data"])
                with (
                    patch.object(
                        devctl.http.client,
                        "HTTPConnection",
                        return_value=FakeConnection(
                            FakeResponse(json.dumps(candidate).encode("utf-8"))
                        ),
                    ),
                    patch.object(
                        devctl,
                        "service_environment",
                        return_value={"DWP_MEETING_SERVICE_TOKEN": "local-secret"},
                    ),
                ):
                    self.assertFalse(
                        devctl.meeting_home_ready(devctl.SERVICES["meeting"])
                    )

    def test_actuator_alone_does_not_make_meeting_ready(self) -> None:
        with (
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready", return_value=False),
        ):
            self.assertFalse(devctl.service_ready(devctl.SERVICES["meeting"]))
            self.assertTrue(devctl.service_ready(devctl.SERVICES["auth"]))


class SpawnFailureCleanupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.log_root = Path(self.temporary_directory.name) / "logs"
        self.log_patch = patch.object(devctl, "LOG_ROOT", self.log_root)
        self.log_patch.start()

    def tearDown(self) -> None:
        self.log_patch.stop()
        self.temporary_directory.cleanup()

    @staticmethod
    def fake_process(pid: int = 4321) -> Mock:
        process = Mock()
        process.pid = pid
        process.poll.return_value = None
        process.wait.return_value = -signal.SIGKILL
        return process

    def test_cleanup_escalates_term_to_kill(self) -> None:
        process = self.fake_process()
        with (
            patch.object(
                devctl,
                "wait_for_process_group_exit",
                side_effect=[False, False, True],
            ) as wait,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            devctl.terminate_spawned_process_group(process, "meeting")

        self.assertEqual(
            [call(4321, signal.SIGTERM), call(4321, signal.SIGKILL)],
            killpg.call_args_list,
        )
        self.assertEqual(3, wait.call_count)

    def test_absent_group_is_confirmed_once_and_leader_is_reaped(self) -> None:
        process = self.fake_process()
        with patch.object(
            devctl, "process_group_alive", side_effect=[False, True]
        ) as group_alive:
            self.assertTrue(devctl.wait_for_process_group_exit(4321, 0, process))

        self.assertEqual(1, group_alive.call_count)
        process.wait.assert_called_once_with(timeout=0)

    def test_identity_capture_failure_cleans_new_process(self) -> None:
        auth = devctl.SERVICES["auth"]
        process = self.fake_process()
        process.poll.return_value = 1
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.subprocess, "Popen", return_value=process),
            patch.object(
                devctl,
                "capture_process_identity",
                side_effect=RuntimeError("no identity"),
            ),
            patch.object(devctl, "terminate_spawned_process_group") as cleanup,
        ):
            with self.assertRaisesRegex(RuntimeError, "establish ownership"):
                devctl.start_service(auth, state)

        cleanup.assert_called_once_with(process, "auth")
        self.assertEqual({}, state)

    def test_state_save_failure_stops_process_and_rolls_back_entry(self) -> None:
        auth = devctl.SERVICES["auth"]
        process = self.fake_process()
        identity = {
            "pgid": 4321,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.subprocess, "Popen", return_value=process),
            patch.object(
                devctl, "capture_process_identity", return_value=identity
            ),
            patch.object(
                devctl,
                "save_state",
                side_effect=[RuntimeError("disk failed"), None],
            ) as save,
            patch.object(devctl, "terminate_spawned_process_group") as cleanup,
        ):
            with self.assertRaisesRegex(RuntimeError, "disk failed"):
                devctl.start_service(auth, state)

        cleanup.assert_called_once_with(process, "auth")
        self.assertEqual({}, state)
        self.assertEqual(2, save.call_count)

    def test_cleanup_failure_keeps_identity_and_reports_possible_leak(self) -> None:
        auth = devctl.SERVICES["auth"]
        process = self.fake_process()
        identity = {
            "pgid": 4321,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.subprocess, "Popen", return_value=process),
            patch.object(
                devctl, "capture_process_identity", return_value=identity
            ),
            patch.object(devctl, "save_state", side_effect=RuntimeError("disk failed")),
            patch.object(
                devctl,
                "terminate_spawned_process_group",
                side_effect=RuntimeError("still alive"),
            ),
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "may still be running.*Ownership state could not be persisted",
            ):
                devctl.start_service(auth, state)

        self.assertIn("auth", state)
        self.assertEqual(identity, state["auth"]["processIdentity"])

    def test_real_posix_cleanup_does_not_signal_unrelated_group(self) -> None:
        command = (sys.executable, "-c", "import time; time.sleep(30)")
        target = subprocess.Popen(command, start_new_session=True)
        survivor = subprocess.Popen(command, start_new_session=True)
        try:
            devctl.terminate_spawned_process_group(target, "test-target")
            self.assertIsNotNone(target.poll())
            self.assertIsNone(survivor.poll())
        finally:
            if target.poll() is None:
                os.killpg(target.pid, signal.SIGKILL)
            if survivor.poll() is None:
                os.killpg(survivor.pid, signal.SIGKILL)
            target.wait(timeout=5)
            survivor.wait(timeout=5)

    def test_real_start_then_stop_reaps_same_invocation_child(self) -> None:
        runtime_root = Path(self.temporary_directory.name) / "runtime"
        service = devctl.Service(
            "agent",
            Path(self.temporary_directory.name),
            ("/bin/sleep", "30"),
            65431,
            "/health",
        )
        state: dict[str, dict[str, object]] = {}
        pid: int | None = None
        started_at = time.monotonic()
        try:
            with (
                patch.dict(devctl.SERVICES, {"agent": service}),
                patch.object(devctl, "RUNTIME_ROOT", runtime_root),
                patch.object(devctl, "STATE_FILE", runtime_root / "processes.json"),
                patch.object(devctl, "LOG_ROOT", runtime_root / "logs"),
                patch.object(devctl, "port_open", return_value=False),
                patch.object(
                    devctl, "service_environment", return_value=dict(os.environ)
                ),
            ):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", ResourceWarning)
                    self.assertTrue(devctl.start_service(service, state))
                pid = int(state["agent"]["pid"])
                devctl.stop_service(service, state)

            self.assertEqual({}, state)
            self.assertLess(time.monotonic() - started_at, 3)
            self.assertFalse(devctl.process_alive(pid))
            with self.assertRaises(ChildProcessError):
                os.waitpid(pid, os.WNOHANG)
        finally:
            if pid is not None:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    os.waitpid(pid, 0)
                except ChildProcessError:
                    pass

    def test_real_exec_transition_is_promoted_only_after_health_and_home(self) -> None:
        root = Path(self.temporary_directory.name)
        launcher = root / "meeting-launcher.sh"
        launcher.write_text(
            "#!/bin/sh\n"
            "sleep 0.8\n"
            "exec \"$TEST_PYTHON\" -c 'import time;time.sleep(30)' \"$0\" \"$1\"\n",
            encoding="utf-8",
        )
        service = devctl.Service(
            "meeting",
            root,
            ("/bin/sh", str(launcher), "meeting-transition-marker"),
            65432,
            "/actuator/health/readiness",
        )
        environment = dict(os.environ)
        environment["TEST_PYTHON"] = sys.executable
        state: dict[str, dict[str, object]] = {}
        pid: int | None = None
        health = Mock(side_effect=[False, True])
        try:
            with (
                patch.object(devctl, "port_open", return_value=False),
                patch.object(devctl, "service_environment", return_value=environment),
                patch.object(devctl, "health_ready", health),
                patch.object(devctl, "meeting_home_ready", return_value=True) as home,
                patch.object(
                    devctl,
                    "capture_service_runtime_artifacts",
                    return_value={"dwp-core": {"sha256": "b" * 64}},
                ),
                patch.object(devctl, "save_state"),
            ):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", ResourceWarning)
                    self.assertTrue(devctl.start_service(service, state))
                pid = int(state["meeting"]["pid"])
                initial_identity = dict(state["meeting"]["processIdentity"])

                devctl.wait_for_services((service,), state, {"meeting"})

                promoted_identity = dict(state["meeting"]["processIdentity"])
                self.assertEqual(
                    initial_identity["pgid"], promoted_identity["pgid"]
                )
                self.assertEqual(
                    initial_identity["startedAt"], promoted_identity["startedAt"]
                )
                self.assertNotEqual(
                    initial_identity["commandSha256"],
                    promoted_identity["commandSha256"],
                )
                promoted_command = devctl._ps_value(pid, "command")
                self.assertNotIn("/bin/sh", promoted_command)
                self.assertIn("meeting-transition-marker", promoted_command)
                self.assertEqual(2, health.call_count)
                home.assert_called_once_with(service)

                devctl.stop_service(service, state)
            self.assertEqual({}, state)
        finally:
            if pid is not None:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    os.waitpid(pid, 0)
                except ChildProcessError:
                    pass

    def test_real_exec_transition_never_ready_is_cleaned_after_timeout(self) -> None:
        root = Path(self.temporary_directory.name)
        launcher = root / "meeting-never-ready.sh"
        launcher.write_text(
            "#!/bin/sh\n"
            "sleep 0.8\n"
            "exec \"$TEST_PYTHON\" -c 'import time;time.sleep(30)' \"$0\" \"$1\"\n",
            encoding="utf-8",
        )
        service = devctl.Service(
            "meeting",
            root,
            ("/bin/sh", str(launcher), "meeting-never-ready-marker"),
            65433,
            "/actuator/health/readiness",
        )
        environment = dict(os.environ)
        environment["TEST_PYTHON"] = sys.executable
        state: dict[str, dict[str, object]] = {}
        persisted: list[dict[str, dict[str, object]]] = []
        spawned_pids: list[int] = []

        def cleanup() -> None:
            for spawned_pid in spawned_pids:
                try:
                    os.killpg(spawned_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    os.waitpid(spawned_pid, 0)
                except ChildProcessError:
                    pass

        self.addCleanup(cleanup)

        def save(value: dict[str, dict[str, object]]) -> None:
            persisted.append(copy.deepcopy(value))
            if "meeting" in value and not spawned_pids:
                spawned_pids.append(int(value["meeting"]["pid"]))

        with (
            patch.object(devctl, "START_PHASES", (("meeting",),)),
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl, "service_environment", return_value=environment),
            patch.object(devctl, "health_ready", return_value=False),
            patch.object(devctl, "meeting_home_ready") as home,
            patch.object(devctl, "service_startup_timeout_seconds", return_value=2),
            patch.object(devctl, "save_state", side_effect=save),
        ):
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", ResourceWarning)
                with self.assertRaisesRegex(RuntimeError, "Startup timed out"):
                    devctl.start_and_wait_services_in_phases((service,), state)

        self.assertEqual({}, state)
        home.assert_not_called()
        process_snapshots = [value["meeting"] for value in persisted if "meeting" in value]
        self.assertGreaterEqual(len(process_snapshots), 2)
        pid = int(process_snapshots[0]["pid"])
        hashes = {
            value["processIdentity"]["commandSha256"]
            for value in process_snapshots
        }
        self.assertGreaterEqual(len(hashes), 2)
        self.assertFalse(devctl.process_alive(pid))
        with self.assertRaises(ChildProcessError):
            os.waitpid(pid, os.WNOHANG)


class SelectiveRestartTest(unittest.TestCase):
    def test_external_service_is_never_adopted_or_signalled(self) -> None:
        auth = devctl.SERVICES["auth"]
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl.subprocess, "Popen") as popen,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            self.assertFalse(devctl.start_service(auth, state))

        self.assertEqual({}, state)
        popen.assert_not_called()
        killpg.assert_not_called()

    def test_external_meeting_owner_never_receives_the_readiness_token(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready") as home_probe,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "external process"):
                devctl.start_service(meeting, state)

        home_probe.assert_not_called()
        killpg.assert_not_called()

    def test_dead_leader_with_live_recorded_group_retains_state_without_signal(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=False),
            patch.object(devctl, "process_group_alive", return_value=True),
            patch.object(devctl.os, "killpg") as killpg,
            patch.object(devctl, "save_state") as save,
        ):
            with self.assertRaisesRegex(RuntimeError, "leader pid.*still alive"):
                devctl.stop_service(meeting, state)

        self.assertIn("meeting", state)
        killpg.assert_not_called()
        save.assert_not_called()

    def test_alive_legacy_state_is_never_adopted_or_signalled(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        state = {"meeting": process_state(meeting)}
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "capture_process_identity", return_value=identity
            ),
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "legacy/unrecorded"):
                devctl.stop_service(meeting, state)

        self.assertIn("meeting", state)
        killpg.assert_not_called()

    def test_only_selected_managed_stale_services_stop_in_reverse_order(self) -> None:
        auth = devctl.SERVICES["auth"]
        notification = devctl.SERVICES["notification"]
        meeting = devctl.SERVICES["meeting"]
        identity = {"pgid": 1, "startedAt": "now", "commandSha256": "a" * 64}
        state = {
            "auth": process_state(auth, pid=101, identity=identity),
            "meeting": process_state(meeting, pid=103, identity=identity),
        }
        stopped: list[str] = []
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(True, "dwp-core changed"),
            ),
            patch.object(
                devctl,
                "verify_managed_process",
                return_value=identity,
            ),
            patch.object(
                devctl,
                "stop_service",
                side_effect=lambda service, _state: stopped.append(service.name),
            ),
        ):
            restarted = devctl.reconcile_stale_services(
                (auth, notification, meeting), state
            )

        self.assertEqual({"auth", "meeting"}, restarted)
        self.assertEqual(["meeting", "auth"], stopped)

    def test_missing_artifact_is_a_recoverable_owned_restart_candidate(self) -> None:
        auth = devctl.SERVICES["auth"]
        identity = {"pgid": 1, "startedAt": "now", "commandSha256": "a" * 64}
        state = {"auth": process_state(auth, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(True, "shared runtime artifact missing"),
            ),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ),
            patch.object(devctl, "stop_service") as stop,
        ):
            restarted = devctl.reconcile_stale_services((auth,), state)

        self.assertEqual({"auth"}, restarted)
        stop.assert_called_once_with(auth, state)

    def test_identity_failure_aborts_before_any_service_is_signalled(self) -> None:
        auth = devctl.SERVICES["auth"]
        meeting = devctl.SERVICES["meeting"]
        identity = {"pgid": 1, "startedAt": "now", "commandSha256": "a" * 64}
        state = {
            "auth": process_state(auth, pid=101, identity=identity),
            "meeting": process_state(meeting, pid=103, identity=identity),
        }
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(True, "changed"),
            ),
            patch.object(
                devctl,
                "verify_managed_process",
                side_effect=[identity, RuntimeError("identity changed")],
            ),
            patch.object(devctl, "stop_service") as stop,
        ):
            with self.assertRaisesRegex(RuntimeError, "identity changed"):
                devctl.reconcile_stale_services((auth, meeting), state)
        stop.assert_not_called()

    def test_stop_retains_state_when_process_group_will_not_terminate(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "verify_managed_process",
                return_value=identity,
            ),
            patch.object(
                devctl,
                "wait_for_process_group_exit",
                side_effect=[False, False],
            ),
            patch.object(devctl.os, "killpg") as killpg,
            patch.object(devctl, "save_state") as save,
        ):
            with self.assertRaisesRegex(RuntimeError, "state was retained"):
                devctl.stop_service(meeting, state)

        self.assertIn("meeting", state)
        self.assertEqual(
            [call(1234, signal.SIGTERM), call(1234, signal.SIGKILL)],
            killpg.call_args_list,
        )
        save.assert_not_called()

    def test_successful_stop_removes_state_only_after_group_exit(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "verify_managed_process",
                return_value=identity,
            ),
            patch.object(
                devctl, "process_group_alive", side_effect=[False, True]
            ) as group_alive,
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.os, "killpg") as killpg,
            patch.object(devctl, "save_state") as save,
        ):
            devctl.stop_service(meeting, state)

        self.assertNotIn("meeting", state)
        killpg.assert_called_once_with(1234, signal.SIGTERM)
        self.assertEqual(1, group_alive.call_count)
        save.assert_called_once_with(state)

    def test_signal_esrch_is_final_absence_and_is_never_rechecked(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ),
            patch.object(
                devctl.os, "killpg", side_effect=ProcessLookupError
            ) as killpg,
            patch.object(devctl, "process_group_alive") as group_alive,
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl, "save_state") as save,
        ):
            devctl.stop_service(meeting, state)

        killpg.assert_called_once_with(1234, signal.SIGTERM)
        group_alive.assert_not_called()
        self.assertNotIn("meeting", state)
        save.assert_called_once_with(state)

    def test_stop_waits_for_delayed_port_release_after_persisting_removal(self) -> None:
        auth = devctl.SERVICES["auth"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"auth": process_state(auth, identity=identity)}
        events: list[str] = []
        observations = iter((True, True, False))

        def port(_port: int) -> bool:
            events.append("port")
            return next(observations)

        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "terminate_verified_managed_process",
                side_effect=lambda *_arguments: events.append("terminate"),
            ),
            patch.object(
                devctl,
                "save_state",
                side_effect=lambda _state: events.append("save"),
            ) as save,
            patch.object(devctl, "port_open", side_effect=port),
            patch.object(devctl.time, "sleep") as sleep,
        ):
            devctl.stop_service(auth, state)

        self.assertEqual(
            ["terminate", "save", "port", "port", "port"], events
        )
        self.assertEqual({}, state)
        save.assert_called_once_with(state)
        self.assertEqual(2, sleep.call_count)

    def test_persistent_port_owner_aborts_after_owned_state_is_removed(self) -> None:
        auth = devctl.SERVICES["auth"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"auth": process_state(auth, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "terminate_verified_managed_process"),
            patch.object(devctl, "save_state") as save,
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl.time, "monotonic", side_effect=[0, 11]),
            patch.object(devctl, "health_ready") as health,
            patch.object(devctl.subprocess, "Popen") as popen,
        ):
            with self.assertRaisesRegex(RuntimeError, "refusing to adopt.*replacement"):
                devctl.stop_service(auth, state)

        self.assertEqual({}, state)
        save.assert_called_once_with(state)
        health.assert_not_called()
        popen.assert_not_called()

    def test_dead_owned_state_port_race_never_reaches_external_classification(self) -> None:
        notification = devctl.SERVICES["notification"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {
            "notification": process_state(
                notification, pid=1234, identity=identity
            )
        }
        with (
            patch.object(devctl, "process_alive", return_value=False),
            patch.object(devctl, "reap_direct_child"),
            patch.object(devctl, "process_group_alive", return_value=False),
            patch.object(devctl, "save_state") as save,
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl.time, "monotonic", side_effect=[0, 11]),
            patch.object(devctl, "health_ready") as health,
            patch.object(devctl.subprocess, "Popen") as popen,
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "port 8008 remains occupied"):
                devctl.start_service(notification, state)

        self.assertEqual({}, state)
        save.assert_called_once_with(state)
        health.assert_not_called()
        popen.assert_not_called()
        killpg.assert_not_called()

    def test_new_owner_after_release_is_rejected_by_restart_orchestration(self) -> None:
        auth = devctl.SERVICES["auth"]
        state: dict[str, dict[str, object]] = {}
        with (
            patch.object(devctl, "START_PHASES", (("auth",),)),
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl, "health_ready") as health,
            patch.object(devctl.subprocess, "Popen") as popen,
        ):
            with self.assertRaisesRegex(RuntimeError, "refusing to adopt the new owner"):
                devctl.start_and_wait_services_in_phases(
                    (auth,), state, {"auth"}
                )

        self.assertEqual({}, state)
        health.assert_not_called()
        popen.assert_not_called()

    def test_up_does_not_start_after_port_release_reconciliation_failure(self) -> None:
        auth = devctl.SERVICES["auth"]
        parser = Mock()
        parser.parse_args.return_value = Mock(command="up", profiles=["auth"])
        with (
            patch.object(devctl, "build_parser", return_value=parser),
            patch.object(devctl, "resolve_services", return_value=[auth]),
            patch.object(devctl, "doctor"),
            patch.object(devctl, "state_change_lock", return_value=nullcontext()),
            patch.object(devctl, "start_infrastructure"),
            patch.object(devctl, "load_state", return_value={}),
            patch.object(
                devctl,
                "reconcile_stale_services",
                side_effect=RuntimeError("port remains occupied"),
            ),
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
            patch.object(devctl, "converge_managed_services") as converge,
        ):
            with self.assertRaisesRegex(RuntimeError, "port remains occupied"):
                devctl.main()

        start.assert_not_called()
        converge.assert_not_called()


class RuntimeConvergenceTest(unittest.TestCase):
    def test_artifact_snapshot_churn_aborts_before_any_signal(self) -> None:
        auth = devctl.SERVICES["auth"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"auth": process_state(auth, identity=identity)}
        old = {"dwp-core": {"sha256": "a" * 64}}
        new = {"dwp-core": {"sha256": "b" * 64}}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "capture_shared_runtime_snapshot",
                side_effect=[old, new, old, new, old, new],
            ) as snapshot,
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(True, "dwp-core changed"),
            ),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ),
            patch.object(devctl.time, "sleep"),
            patch.object(devctl, "stop_service") as stop,
        ):
            with self.assertRaisesRegex(RuntimeError, "no managed process was signalled"):
                devctl.reconcile_stale_services((auth,), state)

        self.assertEqual(2 * devctl.ARTIFACT_SCAN_ATTEMPTS, snapshot.call_count)
        stop.assert_not_called()

    def test_artifact_snapshot_retry_recomputes_candidate_set(self) -> None:
        auth = devctl.SERVICES["auth"]
        meeting = devctl.SERVICES["meeting"]
        old = {"dwp-core": {"sha256": "a" * 64}}
        new = {"dwp-core": {"sha256": "b" * 64}}
        scan = Mock(side_effect=[[(auth, "old")], [(meeting, "new")]])
        with (
            patch.object(
                devctl,
                "capture_shared_runtime_snapshot",
                side_effect=[old, new, new, new],
            ),
            patch.object(devctl.time, "sleep"),
        ):
            candidates = devctl.stable_artifact_candidate_scan(
                (auth, meeting), scan
            )

        self.assertEqual([(meeting, "new")], candidates)
        self.assertEqual([call(old), call(new)], scan.call_args_list)

    def test_later_phase_drift_restarts_only_candidate_once(self) -> None:
        auth = devctl.SERVICES["auth"]
        state = {"auth": process_state(auth)}
        restarted: set[str] = set()
        with (
            patch.object(
                devctl,
                "managed_runtime_drift",
                side_effect=[[(auth, "dwp-core changed")], []],
            ),
            patch.object(devctl, "stop_service") as stop,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
        ):
            devctl.converge_managed_services((auth,), state, restarted)

        self.assertEqual({"auth"}, restarted)
        stop.assert_called_once_with(auth, state)
        started_services = tuple(start.call_args.args[0])
        self.assertEqual((auth,), started_services)

    def test_permanent_drift_fails_without_a_second_restart(self) -> None:
        auth = devctl.SERVICES["auth"]
        state = {"auth": process_state(auth)}
        with (
            patch.object(
                devctl,
                "managed_runtime_drift",
                side_effect=[
                    [(auth, "dwp-core changed")],
                    [(auth, "dwp-core changed")],
                ],
            ),
            patch.object(devctl, "stop_service") as stop,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
        ):
            with self.assertRaisesRegex(RuntimeError, "after one restart"):
                devctl.converge_managed_services((auth,), state, set())

        self.assertEqual(1, stop.call_count)
        self.assertEqual(1, start.call_count)

    def test_initial_restart_budget_blocks_second_signal(self) -> None:
        auth = devctl.SERVICES["auth"]
        state = {"auth": process_state(auth)}
        with (
            patch.object(
                devctl,
                "managed_runtime_drift",
                return_value=[(auth, "dwp-core changed")],
            ),
            patch.object(devctl, "stop_service") as stop,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
        ):
            with self.assertRaisesRegex(RuntimeError, "after one restart"):
                devctl.converge_managed_services((auth,), state, {"auth"})

        stop.assert_not_called()
        start.assert_not_called()

    def test_final_identity_failure_happens_before_any_signal(self) -> None:
        auth = devctl.SERVICES["auth"]
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {
            "auth": process_state(auth, identity=identity),
            "meeting": process_state(meeting, identity=identity),
        }
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "verify_managed_process",
                side_effect=[identity, RuntimeError("identity changed")],
            ),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(True, "changed"),
            ),
            patch.object(devctl, "stop_service") as stop,
        ):
            with self.assertRaisesRegex(RuntimeError, "identity changed"):
                devctl.converge_managed_services((auth, meeting), state, set())

        stop.assert_not_called()

    def test_external_owner_is_outside_convergence(self) -> None:
        auth = devctl.SERVICES["auth"]
        with (
            patch.object(devctl, "verify_managed_process") as verify,
            patch.object(devctl, "service_ready") as ready,
        ):
            self.assertEqual([], devctl.managed_runtime_drift((auth,), {}))
        verify.assert_not_called()
        ready.assert_not_called()

    def test_nonmeeting_degradation_fails_without_expanding_restart_scope(self) -> None:
        auth = devctl.SERVICES["auth"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"auth": process_state(auth, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(False, ""),
            ),
            patch.object(devctl, "health_ready", return_value=False),
            patch.object(devctl, "stop_service") as stop,
        ):
            with self.assertRaisesRegex(RuntimeError, "became degraded"):
                devctl.converge_managed_services((auth,), state, set())
        stop.assert_not_called()

    def test_owned_meeting_home_failure_is_restart_candidate_once(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        home_response = FakeResponse(
            MeetingReadinessProbeTest.valid_payload(), status_code=500
        )
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "service_artifact_staleness",
                return_value=(False, ""),
            ),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ) as verify,
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(
                devctl.http.client,
                "HTTPConnection",
                return_value=FakeConnection(home_response),
            ),
            patch.object(
                devctl,
                "service_environment",
                return_value={"DWP_MEETING_SERVICE_TOKEN": "local-secret"},
            ),
            patch.object(devctl, "stop_service") as stop,
        ):
            restarted = devctl.reconcile_stale_services((meeting,), state)

        self.assertEqual({"meeting"}, restarted)
        verify.assert_called()
        stop.assert_called_once_with(meeting, state)

        with (
            patch.object(
                devctl,
                "managed_runtime_drift",
                return_value=[(meeting, "readiness failed")],
            ),
            patch.object(devctl, "stop_service") as second_stop,
        ):
            with self.assertRaisesRegex(RuntimeError, "after one restart"):
                devctl.converge_managed_services((meeting,), state, restarted)
        second_stop.assert_not_called()


class RuntimeStatusTest(unittest.TestCase):
    def test_status_distinguishes_all_runtime_states(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        auth = devctl.SERVICES["auth"]
        frontend = devctl.SERVICES["frontend"]
        state = {
            "meeting": process_state(meeting, pid=101),
            "auth": process_state(auth, pid=102),
            "frontend": process_state(frontend, pid=103),
        }

        def artifact_state(service: devctl.Service, *_arguments: object) -> tuple[bool, str]:
            if service.name == "auth":
                return True, "dwp-core changed"
            return False, ""

        def ready(
            service: devctl.Service, _process_state: object = None
        ) -> bool:
            return service.name == "meeting"

        with (
            patch.object(
                devctl,
                "START_ORDER",
                ("meeting", "auth", "frontend", "gateway", "people"),
            ),
            patch.object(devctl, "load_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "service_artifact_staleness",
                side_effect=artifact_state,
            ),
            patch.object(
                devctl, "managed_process_identity_matches", return_value=True
            ),
            patch.object(devctl, "managed_service_ready", side_effect=ready),
            patch.object(
                devctl,
                "port_open",
                side_effect=lambda port: port == devctl.SERVICES["gateway"].port,
            ),
            patch.object(devctl, "docker_compose"),
            patch("builtins.print") as output,
        ):
            devctl.print_status()

        lines = [str(arguments[0]) for arguments, _keywords in output.call_args_list]
        self.assertTrue(any("meeting" in line and "ready" in line for line in lines))
        self.assertTrue(any("auth" in line and "stale" in line for line in lines))
        self.assertTrue(any("frontend" in line and "degraded" in line for line in lines))
        self.assertTrue(any("gateway" in line and "external" in line for line in lines))
        self.assertTrue(any("people" in line and "stopped" in line for line in lines))

    def test_orphaned_recorded_group_is_reported_degraded_not_external(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 4321,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "START_ORDER", ("meeting",)),
            patch.object(devctl, "load_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=False),
            patch.object(devctl, "process_group_alive", return_value=True),
            patch.object(devctl, "port_open", return_value=True),
            patch.object(devctl, "docker_compose"),
            patch("builtins.print") as output,
        ):
            devctl.print_status()

        line = str(output.call_args_list[0].args[0])
        self.assertIn("degraded", line)
        self.assertIn("process group 4321", line)
        self.assertNotIn("external", line)


class ReadinessBaselineTest(unittest.TestCase):
    def test_artifact_capture_failure_leaves_durable_promoted_untracked_identity(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        initial = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        promoted = {**initial, "commandSha256": "b" * 64}
        state = {"meeting": process_state(meeting, identity=initial)}
        persisted: list[dict[str, dict[str, object]]] = []

        def save(value: dict[str, dict[str, object]]) -> None:
            persisted.append(copy.deepcopy(value))

        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready", return_value=True),
            patch.object(
                devctl, "capture_process_identity", return_value=promoted
            ),
            patch.object(
                devctl,
                "capture_service_runtime_artifacts",
                side_effect=RuntimeError("artifact I/O failed"),
            ),
            patch.object(devctl, "save_state", side_effect=save),
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "artifact I/O failed"):
                devctl.wait_for_services((meeting,), state, {"meeting"})

        self.assertGreaterEqual(len(persisted), 2)
        self.assertTrue(
            all(
                snapshot["meeting"]["processIdentity"] == promoted
                and "runtimeArtifacts" not in snapshot["meeting"]
                for snapshot in persisted
            )
        )
        self.assertEqual(promoted, state["meeting"]["processIdentity"])
        self.assertNotIn("runtimeArtifacts", state["meeting"])
        killpg.assert_not_called()

    def test_second_artifact_save_failure_retains_first_untracked_checkpoint(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        artifacts = {"dwp-core": {"sha256": "b" * 64}}
        state = {"meeting": process_state(meeting, identity=identity)}
        persisted: list[dict[str, dict[str, object]]] = []

        def save(value: dict[str, dict[str, object]]) -> None:
            persisted.append(copy.deepcopy(value))
            if len(persisted) == 2:
                raise RuntimeError("artifact baseline save failed")

        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready", return_value=True),
            patch.object(devctl, "capture_process_identity", return_value=identity),
            patch.object(
                devctl,
                "capture_service_runtime_artifacts",
                return_value=artifacts,
            ),
            patch.object(devctl, "save_state", side_effect=save),
            patch.object(devctl.os, "killpg") as killpg,
        ):
            with self.assertRaisesRegex(RuntimeError, "baseline save failed"):
                devctl.wait_for_services((meeting,), state, {"meeting"})

        self.assertNotIn("runtimeArtifacts", persisted[0]["meeting"])
        self.assertEqual(identity, persisted[0]["meeting"]["processIdentity"])
        self.assertEqual(artifacts, persisted[1]["meeting"]["runtimeArtifacts"])
        killpg.assert_not_called()

    def test_first_identity_checkpoint_failure_cleans_only_started_service(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        auth = devctl.SERVICES["auth"]
        initial = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        promoted = {**initial, "commandSha256": "b" * 64}
        state = {"auth": process_state(auth, pid=9999, identity=initial)}

        def start(service: devctl.Service, target: dict[str, dict[str, object]]) -> bool:
            target[service.name] = process_state(
                service, pid=1234, identity=initial
            )
            return True

        with (
            patch.object(devctl, "START_PHASES", (("meeting",),)),
            patch.object(devctl, "start_service", side_effect=start),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "capture_process_identity", return_value=promoted
            ),
            patch.object(
                devctl,
                "save_state",
                side_effect=[RuntimeError("identity checkpoint failed"), None],
            ) as save,
            patch.object(devctl, "terminate_process_group") as terminate,
            patch.object(devctl, "wait_for_service_port_release"),
        ):
            with self.assertRaisesRegex(RuntimeError, "identity checkpoint failed"):
                devctl.start_and_wait_services_in_phases((meeting,), state)

        terminate.assert_called_once_with(1234, 1234, "meeting")
        self.assertNotIn("meeting", state)
        self.assertIn("auth", state)
        self.assertEqual(2, save.call_count)

    def test_keyboard_interrupt_cleans_same_invocation_started_service(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state: dict[str, dict[str, object]] = {}

        def start(service: devctl.Service, target: dict[str, dict[str, object]]) -> bool:
            target[service.name] = process_state(
                service, pid=1234, identity=identity
            )
            return True

        with (
            patch.object(devctl, "START_PHASES", (("meeting",),)),
            patch.object(devctl, "start_service", side_effect=start),
            patch.object(
                devctl, "wait_for_services", side_effect=KeyboardInterrupt
            ),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "capture_process_identity", return_value=identity
            ),
            patch.object(devctl, "terminate_process_group") as terminate,
            patch.object(devctl, "wait_for_service_port_release"),
            patch.object(devctl, "save_state") as save,
        ):
            with self.assertRaises(KeyboardInterrupt):
                devctl.start_and_wait_services_in_phases((meeting,), state)

        terminate.assert_called_once_with(1234, 1234, "meeting")
        self.assertEqual({}, state)
        save.assert_called_once_with(state)

    def test_meeting_probe_is_bracketed_by_stable_identity_checks(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        events: list[str] = []

        def verify(*_arguments: object) -> dict[str, object]:
            events.append("verify")
            return identity

        def probe(_service: devctl.Service) -> bool:
            events.append("probe")
            return True

        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "verify_managed_process", side_effect=verify),
            patch.object(devctl, "service_ready", side_effect=probe),
        ):
            devctl.wait_for_services((meeting,), state, set())

        self.assertEqual(["verify", "probe", "verify"], events)

    def test_meeting_identity_change_after_probe_is_not_marked_ready(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        before = {
            "pgid": 1234,
            "startedAt": "before",
            "commandSha256": "a" * 64,
        }
        after = {**before, "startedAt": "after"}
        state = {"meeting": process_state(meeting, identity=before)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "verify_managed_process", side_effect=[before, after]
            ),
            patch.object(devctl, "service_ready", return_value=True) as probe,
        ):
            with self.assertRaisesRegex(RuntimeError, "changed during"):
                devctl.wait_for_services((meeting,), state, set())
        probe.assert_called_once_with(meeting)

    def test_wait_revalidates_meeting_identity_before_authenticated_probe(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "verify_managed_process",
                side_effect=RuntimeError("identity changed"),
            ) as verify,
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready") as home_probe,
        ):
            with self.assertRaisesRegex(RuntimeError, "identity changed"):
                devctl.wait_for_services((meeting,), state, set())

        verify.assert_called_once_with(meeting, state["meeting"])
        home_probe.assert_not_called()

    def test_wait_refuses_external_meeting_before_authenticated_probe(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        with (
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready") as home_probe,
        ):
            with self.assertRaisesRegex(RuntimeError, "no verified managed identity"):
                devctl.wait_for_services((meeting,), {}, set())
        home_probe.assert_not_called()

    def test_new_process_records_identity_and_artifacts_only_after_readiness(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        artifacts = {"dwp-core": {"sha256": "b" * 64}}
        state = {"meeting": process_state(meeting, identity=identity)}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready", return_value=True),
            patch.object(devctl, "capture_process_identity", return_value=identity),
            patch.object(
                devctl,
                "capture_service_runtime_artifacts",
                return_value=artifacts,
            ),
            patch.object(devctl, "save_state") as save,
        ):
            devctl.wait_for_services((meeting,), state, {"meeting"})

        self.assertEqual(identity, state["meeting"]["processIdentity"])
        self.assertEqual(artifacts, state["meeting"]["runtimeArtifacts"])
        self.assertEqual(2, save.call_count)

    def test_existing_ready_process_does_not_rebaseline_current_artifacts(self) -> None:
        meeting = devctl.SERVICES["meeting"]
        identity = {
            "pgid": 1234,
            "startedAt": "now",
            "commandSha256": "a" * 64,
        }
        original = {"dwp-core": {"sha256": "a" * 64}}
        state = {
            "meeting": process_state(
                meeting, identity=identity, artifacts=original
            )
        }
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "verify_managed_process", return_value=identity
            ),
            patch.object(devctl, "service_ready", return_value=True),
            patch.object(devctl, "capture_service_runtime_artifacts") as capture,
            patch.object(devctl, "save_state") as save,
        ):
            devctl.wait_for_services((meeting,), state, set())

        self.assertEqual(original, state["meeting"]["runtimeArtifacts"])
        capture.assert_not_called()
        save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
