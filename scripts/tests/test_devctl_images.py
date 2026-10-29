from __future__ import annotations

import copy
import hashlib
import json
import os
import stat
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from contextlib import ExitStack, contextmanager, nullcontext
from pathlib import Path
from unittest.mock import Mock, patch

from scripts import devctl


def nested_jar_bytes(
    entries: list[tuple[str, bytes]] | None = None,
) -> bytes:
    with tempfile.SpooledTemporaryFile() as target:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(
                target, "w", compression=zipfile.ZIP_DEFLATED
            ) as archive:
                for name, content in entries or []:
                    archive.writestr(name, content)
        target.seek(0)
        return target.read()


def write_boot_jar(
    path: Path,
    *,
    migration: bytes = b"create table example(id bigint);\n",
    valid_manifest: bool = True,
    nested_library: bytes | None = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    manifest = (
        b"Manifest-Version: 1.0\r\n"
        b"Main-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
        b"Start-Class: com.dwp.ExampleApplication\r\n\r\n"
        if valid_manifest
        else b"Manifest-Version: 1.0\r\n\r\n"
    )
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", manifest)
        archive.writestr("BOOT-INF/classes/com/dwp/ExampleApplication.class", b"class")
        archive.writestr(
            "BOOT-INF/lib/dependency.jar",
            nested_jar_bytes() if nested_library is None else nested_library,
        )
        archive.writestr("BOOT-INF/classes/db/migration/V1__example.sql", migration)


class RuntimeImageSandbox(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.runtime = self.root / ".dev-runtime"
        self.image_root = self.runtime / "images" / "sha256"
        self.build_root = self.runtime / "image-build"
        self.stack = ExitStack()
        for name, value in {
            "BACKEND_ROOT": self.root,
            "RUNTIME_ROOT": self.runtime,
            "LOG_ROOT": self.runtime / "logs",
            "STATE_FILE": self.runtime / "processes.json",
            "STATE_LOCK_FILE": self.runtime / "devctl.lock",
            "RUNTIME_IMAGE_BUILD_LOCK_FILE": self.runtime / "runtime-image-build.lock",
            "RUNTIME_WATCH_LOCK_FILE": self.runtime / "runtime-watch.lock",
            "RUNTIME_IMAGE_BUILD_ROOT": self.build_root,
            "RUNTIME_IMAGE_ROOT": self.image_root,
        }.items():
            self.stack.enter_context(patch.object(devctl, name, value))

    def tearDown(self) -> None:
        self.stack.close()
        self.temporary_directory.cleanup()

    def built_jar(self, service_name: str = "meeting") -> Path:
        module = devctl.JAVA_SERVICE_MODULES[service_name]
        path = devctl._gradle_project_build_directory(module) / "libs" / f"{module}-1.0.0.jar"
        write_boot_jar(path)
        return path

    def publish(self, service_name: str = "meeting") -> dict[str, object]:
        service = devctl.SERVICES[service_name]
        source = self.built_jar(service_name)
        fingerprint, migration_digest = devctl._boot_jar_metadata(source)
        return devctl._publish_runtime_image(
            service, source, fingerprint, migration_digest
        )

    def process_state(
        self,
        service_name: str,
        envelope: dict[str, object],
    ) -> dict[str, object]:
        service = devctl.SERVICES[service_name]
        image_path = self.root / str(envelope["path"])
        return {
            "pid": 1234,
            "port": service.port,
            "command": ["java", "-jar", str(image_path)],
            "log": str(self.runtime / "logs" / f"{service_name}.log"),
            "runtimeImage": copy.deepcopy(envelope),
        }


class RuntimeImageRegistryTest(unittest.TestCase):
    def test_registry_covers_all_and_only_spring_java_services(self) -> None:
        self.assertEqual(
            set(devctl.SERVICES) - {"agent", "frontend"},
            set(devctl.JAVA_SERVICE_MODULES),
        )
        self.assertEqual(12, len(devctl.JAVA_SERVICE_MODULES))
        self.assertEqual(
            "dwp-meeting-server", devctl.JAVA_SERVICE_MODULES["meeting"]
        )

    def test_watch_parser_accepts_profiles(self) -> None:
        parsed = devctl.build_parser().parse_args(["watch", "meeting"])
        self.assertEqual("watch", parsed.command)
        self.assertEqual(["meeting"], parsed.profiles)


class MeetingReadinessTimestampTest(unittest.TestCase):
    def test_rfc3339_parser_accepts_java_instant_fraction_widths_and_offsets(self) -> None:
        valid = (
            "2026-10-28T08:36:55Z",
            "2026-10-28T08:36:55.5Z",
            "2026-10-28T08:36:55.529Z",
            "2026-10-28T08:36:55.52907Z",
            "2026-10-28T08:36:55.529070Z",
            "2026-10-28T08:36:55.529070123Z",
            "2026-10-28T17:36:55.52907+09:00",
        )
        for value in valid:
            with self.subTest(value=value):
                parsed = devctl.parse_rfc3339_instant(value)
                self.assertIsNotNone(parsed.tzinfo)
                self.assertIsNotNone(parsed.utcoffset())

    def test_rfc3339_parser_rejects_naive_overprecision_and_invalid_dates(self) -> None:
        invalid = (
            "2026-10-28T08:36:55",
            "2026-10-28T08:36:55.1234567890Z",
            "2026-02-30T08:36:55.52907Z",
            "2026-10-28T25:36:55Z",
            "2026-10-28 08:36:55Z",
            None,
        )
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                devctl.parse_rfc3339_instant(value)

    def test_meeting_home_accepts_five_digit_java_instant(self) -> None:
        payload = {
            "success": True,
            "status": "SUCCESS",
            "data": {
                "serverNow": "2026-10-28T08:36:55.52907Z",
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
                "capabilities": {"available": True, "provider": "LOCAL"},
            },
        }
        response = Mock(status=200)
        response.getheader.return_value = "application/json; charset=utf-8"
        response.read.return_value = json.dumps(payload).encode("utf-8")
        connection = Mock()
        connection.getresponse.return_value = response
        with (
            patch.object(
                devctl,
                "service_environment",
                return_value={"DWP_MEETING_SERVICE_TOKEN": "local-token"},
            ),
            patch.object(
                devctl.http.client,
                "HTTPConnection",
                return_value=connection,
            ) as http_connection,
        ):
            self.assertTrue(devctl.meeting_home_ready(devctl.SERVICES["meeting"]))
        http_connection.assert_called_once_with(
            "127.0.0.1",
            devctl.SERVICES["meeting"].port,
            timeout=devctl.MEETING_READINESS_REQUEST_TIMEOUT_SECONDS,
        )
        connection.close.assert_called_once()


class RuntimeImageResetLockTest(unittest.TestCase):
    def test_reset_acquires_build_watch_then_state_lock(self) -> None:
        events: list[str] = []

        @contextmanager
        def recorded(name: str):
            events.append(f"enter:{name}")
            try:
                yield
            finally:
                events.append(f"exit:{name}")

        parser = Mock()
        parser.parse_args.return_value = Mock(command="reset", yes=True)
        with (
            patch.object(devctl, "build_parser", return_value=parser),
            patch.object(
                devctl,
                "runtime_image_build_lock",
                side_effect=lambda: recorded("build"),
            ),
            patch.object(
                devctl, "runtime_watch_lock", side_effect=lambda: recorded("watch")
            ),
            patch.object(
                devctl, "state_change_lock", side_effect=lambda: recorded("state")
            ),
            patch.object(
                devctl,
                "reset_database",
                side_effect=lambda confirmed: events.append(f"reset:{confirmed}"),
            ),
        ):
            devctl.main()
        self.assertEqual(
            [
                "enter:build",
                "enter:watch",
                "enter:state",
                "reset:True",
                "exit:state",
                "exit:watch",
                "exit:build",
            ],
            events,
        )

    def test_reset_build_lock_contention_fails_before_any_mutation(self) -> None:
        parser = Mock()
        parser.parse_args.return_value = Mock(command="reset", yes=True)
        with (
            patch.object(devctl, "build_parser", return_value=parser),
            patch.object(
                devctl,
                "runtime_image_build_lock",
                side_effect=RuntimeError("image build is active"),
            ),
            patch.object(devctl, "runtime_watch_lock") as watch_lock,
            patch.object(devctl, "state_change_lock") as state_lock,
            patch.object(devctl, "reset_database") as reset,
            self.assertRaisesRegex(RuntimeError, "image build is active"),
        ):
            devctl.main()
        watch_lock.assert_not_called()
        state_lock.assert_not_called()
        reset.assert_not_called()


class RuntimeImageUpGenerationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = devctl.SERVICES["meeting"]
        self.prepared = devctl.PreparedRuntimeImages({}, "a" * 64)
        self.parser = Mock()
        self.parser.parse_args.return_value = Mock(
            command="up", profiles=["meeting"]
        )

    def test_first_generation_check_aborts_before_state_lock_or_signal(self) -> None:
        with (
            patch.object(devctl, "build_parser", return_value=self.parser),
            patch.object(devctl, "resolve_services", return_value=[self.service]),
            patch.object(devctl, "doctor"),
            patch.object(devctl, "stage_runtime_images", return_value=self.prepared),
            patch.object(
                devctl, "capture_runtime_source_digest", return_value="b" * 64
            ),
            patch.object(devctl, "state_change_lock") as state_lock,
            patch.object(devctl, "start_infrastructure") as infrastructure,
            patch.object(devctl, "reconcile_stale_services") as reconcile,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
            patch.object(devctl, "stop_service") as stop,
            self.assertRaisesRegex(RuntimeError, "sources changed"),
        ):
            devctl.main()
        state_lock.assert_not_called()
        infrastructure.assert_not_called()
        reconcile.assert_not_called()
        start.assert_not_called()
        stop.assert_not_called()

    def test_second_generation_check_aborts_before_reconcile_or_signal(self) -> None:
        with (
            patch.object(devctl, "build_parser", return_value=self.parser),
            patch.object(devctl, "resolve_services", return_value=[self.service]),
            patch.object(devctl, "doctor"),
            patch.object(devctl, "stage_runtime_images", return_value=self.prepared),
            patch.object(
                devctl,
                "capture_runtime_source_digest",
                side_effect=["a" * 64, "b" * 64],
            ),
            patch.object(
                devctl, "state_change_lock", side_effect=lambda: nullcontext()
            ),
            patch.object(devctl, "start_infrastructure") as infrastructure,
            patch.object(devctl, "load_state") as load_state,
            patch.object(devctl, "reconcile_stale_services") as reconcile,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
            patch.object(devctl, "converge_managed_services") as converge,
            patch.object(devctl, "stop_service") as stop,
            self.assertRaisesRegex(RuntimeError, "sources changed"),
        ):
            devctl.main()
        infrastructure.assert_called_once()
        load_state.assert_not_called()
        reconcile.assert_not_called()
        start.assert_not_called()
        converge.assert_not_called()
        stop.assert_not_called()


class RuntimeImageStateTest(RuntimeImageSandbox):
    def test_state_binds_image_service_module_path_digest_and_command(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        self.assertEqual(state, devctl._validate_state(copy.deepcopy(state)))

        mutations = []
        for key, value in (
            ("module", "dwp-auth-server"),
            ("service", "auth"),
            ("sha256", "A" * 64),
            ("migrationDigest", "not-a-digest"),
            ("path", "../outside.jar"),
        ):
            changed = copy.deepcopy(state)
            changed["meeting"]["runtimeImage"][key] = value
            mutations.append(changed)
        changed_command = copy.deepcopy(state)
        changed_command["meeting"]["command"] = ["java", "-jar", "/tmp/other.jar"]
        mutations.append(changed_command)

        for changed in mutations:
            with self.subTest(changed=changed), self.assertRaises(RuntimeError):
                devctl._validate_state(changed)

    def test_non_java_service_cannot_claim_runtime_image(self) -> None:
        envelope = self.publish()
        envelope["service"] = "frontend"
        envelope["module"] = None
        state = self.process_state("meeting", self.publish())
        state["port"] = devctl.SERVICES["frontend"].port
        state["runtimeImage"] = envelope
        with self.assertRaises(RuntimeError):
            devctl._validate_state({"frontend": state})


class RuntimeImageArtifactTest(RuntimeImageSandbox):
    def test_isolated_gradle_output_mapping_is_collision_free(self) -> None:
        meeting = devctl._gradle_project_build_directory("dwp-meeting-server")
        auth = devctl._gradle_project_build_directory("dwp-auth-server")
        self.assertEqual(
            self.build_root / ":dwp-meeting-server".encode("utf-8").hex(),
            meeting,
        )
        self.assertNotEqual(meeting, auth)

    def test_boot_jar_validation_and_migration_digest_are_content_bound(self) -> None:
        first = self.built_jar()
        fingerprint, migration_digest = devctl._boot_jar_metadata(first)
        self.assertRegex(str(fingerprint["sha256"]), r"^[0-9a-f]{64}$")
        self.assertRegex(migration_digest, r"^[0-9a-f]{64}$")

        second = self.root / "second.jar"
        write_boot_jar(second, migration=b"create table changed(id bigint);\n")
        _, changed_digest = devctl._boot_jar_metadata(second)
        self.assertNotEqual(migration_digest, changed_digest)

        invalid = self.root / "invalid.jar"
        write_boot_jar(invalid, valid_manifest=False)
        with self.assertRaisesRegex(RuntimeError, "manifest"):
            devctl._boot_jar_metadata(invalid)

    def test_nested_library_migration_locations_are_digest_bound(self) -> None:
        locations = (
            "db/migration/V2__nested.sql",
            "db/local-seed/V2__seed.sql",
            "db/performance-migration/V2__performance.sql",
        )
        for index, location in enumerate(locations):
            with self.subTest(location=location):
                first = self.root / f"nested-first-{index}.jar"
                second = self.root / f"nested-second-{index}.jar"
                write_boot_jar(
                    first,
                    nested_library=nested_jar_bytes([(location, b"first")]),
                )
                write_boot_jar(
                    second,
                    nested_library=nested_jar_bytes([(location, b"second")]),
                )
                _, first_digest = devctl._boot_jar_metadata(first)
                _, second_digest = devctl._boot_jar_metadata(second)
                self.assertNotEqual(first_digest, second_digest)

    def test_corrupt_or_duplicate_nested_library_entries_fail_closed(self) -> None:
        corrupt = self.root / "nested-corrupt.jar"
        write_boot_jar(corrupt, nested_library=b"not-a-zip")
        with self.assertRaisesRegex(RuntimeError, "nested|library|bootJar"):
            devctl._boot_jar_metadata(corrupt)

        duplicate_nested = nested_jar_bytes(
            [
                ("db/migration/V2__duplicate.sql", b"first"),
                ("db/migration/V2__duplicate.sql", b"second"),
            ]
        )
        duplicate = self.root / "nested-duplicate.jar"
        write_boot_jar(duplicate, nested_library=duplicate_nested)
        with self.assertRaisesRegex(RuntimeError, "duplicate"):
            devctl._boot_jar_metadata(duplicate)

    def test_publish_is_read_only_content_addressed_and_idempotent(self) -> None:
        source = self.built_jar()
        fingerprint, migration_digest = devctl._boot_jar_metadata(source)
        first = devctl._publish_runtime_image(
            devctl.SERVICES["meeting"], source, fingerprint, migration_digest
        )
        image = self.root / str(first["path"])
        self.assertTrue(image.is_file())
        self.assertEqual(devctl.RUNTIME_IMAGE_MODE, stat.S_IMODE(image.stat().st_mode))
        self.assertEqual(hashlib.sha256(image.read_bytes()).hexdigest(), first["sha256"])

        second = devctl._publish_runtime_image(
            devctl.SERVICES["meeting"], source, fingerprint, migration_digest
        )
        self.assertEqual(first, second)
        self.assertEqual(image, devctl.validate_published_runtime_image(
            devctl.SERVICES["meeting"], second
        ))

    def test_existing_symlink_or_corrupt_content_is_never_replaced(self) -> None:
        source = self.built_jar()
        fingerprint, migration_digest = devctl._boot_jar_metadata(source)
        envelope = devctl._runtime_image_envelope(
            "meeting", fingerprint, migration_digest
        )
        destination = self.root / str(envelope["path"])
        destination.parent.mkdir(parents=True, mode=0o700)
        os.chmod(self.runtime, 0o700)
        os.chmod(self.runtime / "images", 0o700)
        os.chmod(self.image_root, 0o700)
        target = self.root / "outside.jar"
        target.write_bytes(b"outside")
        destination.symlink_to(target)
        with self.assertRaises(RuntimeError):
            devctl._publish_runtime_image(
                devctl.SERVICES["meeting"], source, fingerprint, migration_digest
            )
        self.assertTrue(destination.is_symlink())
        self.assertEqual(b"outside", target.read_bytes())

        destination.unlink()
        destination.write_bytes(b"corrupt")
        os.chmod(destination, devctl.RUNTIME_IMAGE_MODE)
        with self.assertRaises(RuntimeError):
            devctl._publish_runtime_image(
                devctl.SERVICES["meeting"], source, fingerprint, migration_digest
            )
        self.assertEqual(b"corrupt", destination.read_bytes())

    def test_stage_uses_one_gradle_invocation_and_publishes_all_images(self) -> None:
        services = [devctl.SERVICES["auth"], devctl.SERVICES["meeting"]]

        def build_outputs(*_args: object, **_kwargs: object) -> Mock:
            for service in services:
                self.built_jar(service.name)
            return Mock(returncode=0)

        with (
            patch.object(devctl, "capture_runtime_source_digest", return_value="1" * 64),
            patch.object(devctl.subprocess, "run", side_effect=build_outputs) as run,
        ):
            images = devctl.stage_runtime_images(services)

        self.assertEqual({"auth", "meeting"}, set(images))
        run.assert_called_once()
        command = run.call_args.args[0]
        self.assertIn(f"-PdwpDevRuntimeBuildRoot={self.build_root}", command)
        self.assertEqual(1, command.count(":dwp-auth-server:bootJar"))
        self.assertEqual(1, command.count(":dwp-meeting-server:bootJar"))

    def test_stage_build_failure_does_not_publish_or_touch_state(self) -> None:
        self.runtime.mkdir(mode=0o700)
        state_file = self.runtime / "processes.json"
        state_file.write_text('{"sentinel":true}\n', encoding="utf-8")
        with (
            patch.object(devctl, "capture_runtime_source_digest", return_value="1" * 64),
            patch.object(
                devctl.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(1, ("gradle",)),
            ),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            devctl.stage_runtime_images([devctl.SERVICES["meeting"]])
        self.assertEqual('{"sentinel":true}\n', state_file.read_text(encoding="utf-8"))
        self.assertFalse(self.image_root.exists())

    def test_source_generation_digest_changes_and_rejects_symlinks(self) -> None:
        module_root = self.root / "dwp-meeting-server"
        source = module_root / "src" / "main" / "java" / "Example.java"
        source.parent.mkdir(parents=True)
        source.write_text("final class Example {}\n", encoding="utf-8")
        (module_root / "build.gradle").write_text("plugins { id 'java' }\n")
        (self.root / "build.gradle").write_text("plugins { id 'java' }\n")
        with patch.object(devctl, "WATCH_SHARED_MODULES", ()):
            first = devctl.capture_runtime_source_digest(
                [devctl.SERVICES["meeting"]]
            )
            source.write_text("final class Example { int value; }\n", encoding="utf-8")
            second = devctl.capture_runtime_source_digest(
                [devctl.SERVICES["meeting"]]
            )
            self.assertNotEqual(first, second)

            source.unlink()
            target = self.root / "outside.java"
            target.write_text("final class Outside {}\n", encoding="utf-8")
            source.symlink_to(target)
            with self.assertRaisesRegex(RuntimeError, "symlink"):
                devctl.capture_runtime_source_digest(
                    [devctl.SERVICES["meeting"]]
                )


class RuntimeImageExecutionTest(RuntimeImageSandbox):
    def test_immutable_state_ignores_mutable_build_libs_but_legacy_is_stale(self) -> None:
        envelope = self.publish()
        state = self.process_state("meeting", envelope)
        stale, reason = devctl.service_runtime_staleness(
            devctl.SERVICES["meeting"], state, {}, envelope
        )
        self.assertFalse(stale, reason)

        mutable = self.root / "dwp-core" / "build" / "libs" / "dwp-core.jar"
        mutable.parent.mkdir(parents=True)
        mutable.write_bytes(b"replaced")
        stale, reason = devctl.service_runtime_staleness(
            devctl.SERVICES["meeting"], state, {}, envelope
        )
        self.assertFalse(stale, reason)

        legacy = {
            "pid": 1234,
            "port": devctl.SERVICES["meeting"].port,
            "command": list(devctl.SERVICES["meeting"].command),
            "log": "/tmp/meeting.log",
        }
        stale, reason = devctl.service_runtime_staleness(
            devctl.SERVICES["meeting"], legacy, {}, envelope
        )
        self.assertTrue(stale)
        self.assertEqual("legacy mutable runtime", reason)

    def test_java_start_requires_prepared_image_when_image_mode_is_requested(self) -> None:
        with (
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.subprocess, "Popen") as popen,
            self.assertRaisesRegex(RuntimeError, "No prepared immutable runtime image"),
        ):
            devctl.start_service(
                devctl.SERVICES["meeting"], {}, prepared_images={}
            )
        popen.assert_not_called()

    def test_start_launches_canonical_java_jar_and_records_image(self) -> None:
        envelope = self.publish()
        state: dict[str, dict[str, object]] = {}
        process = Mock(pid=4567)
        process.poll.return_value = None
        identity = {"pgid": 4567, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(devctl, "port_open", return_value=False),
            patch.object(devctl.subprocess, "Popen", return_value=process) as popen,
            patch.object(devctl, "capture_process_identity", return_value=identity),
            patch.object(devctl, "save_state"),
        ):
            self.assertTrue(devctl.start_service(
                devctl.SERVICES["meeting"],
                state,
                prepared_images={"meeting": envelope},
            ))
        expected = ("java", "-jar", str(self.root / str(envelope["path"])))
        self.assertEqual(expected, popen.call_args.args[0])
        self.assertEqual(envelope, state["meeting"]["runtimeImage"])
        self.assertEqual(list(expected), state["meeting"]["command"])

    def test_ready_image_never_rebinds_to_mutable_shared_artifacts(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        state["meeting"]["processIdentity"] = identity
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "refresh_started_process_identity", return_value=identity
            ),
            patch.object(devctl, "health_ready", return_value=True),
            patch.object(devctl, "meeting_home_ready", return_value=True),
            patch.object(devctl, "capture_started_process_identity", return_value=identity),
            patch.object(devctl, "validate_published_runtime_image") as validate,
            patch.object(
                devctl,
                "capture_service_runtime_artifacts",
                side_effect=AssertionError("mutable artifact baseline must not be read"),
            ),
            patch.object(devctl, "save_state"),
        ):
            devctl.wait_for_services(
                [devctl.SERVICES["meeting"]], state, {"meeting"}
            )
        validate.assert_called_once_with(devctl.SERVICES["meeting"], envelope)
        self.assertNotIn("runtimeArtifacts", state["meeting"])

    def test_watcher_refuses_migration_change_without_signalling(self) -> None:
        current = self.publish()
        desired = copy.deepcopy(current)
        desired["migrationDigest"] = "2" * 64
        state = {"meeting": self.process_state("meeting", current)}
        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "validate_published_runtime_image"),
            patch.object(devctl, "verify_managed_process", return_value=identity),
            patch.object(devctl, "reconcile_stale_services") as reconcile,
            self.assertRaisesRegex(RuntimeError, "migration content changed"),
        ):
            devctl.apply_watched_runtime_images(
                [devctl.SERVICES["meeting"]], state, {"meeting": desired}
            )
        reconcile.assert_not_called()

    def test_existing_meeting_false_then_true_readiness_is_not_restarted(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "service_runtime_staleness", return_value=(False, "")
            ),
            patch.object(
                devctl, "managed_service_ready", side_effect=[False, True]
            ) as ready,
            patch.object(devctl, "verify_managed_process", return_value=identity),
            patch.object(devctl.time, "sleep"),
            patch.object(devctl, "stop_service") as stop,
        ):
            restarted = devctl.reconcile_stale_services(
                [devctl.SERVICES["meeting"]], state, {"meeting": envelope}
            )
        self.assertEqual(set(), restarted)
        self.assertEqual(2, ready.call_count)
        stop.assert_not_called()

    def test_existing_meeting_requires_three_failed_readiness_probes(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl, "service_runtime_staleness", return_value=(False, "")
            ),
            patch.object(devctl, "managed_service_ready", return_value=False) as ready,
            patch.object(devctl, "verify_managed_process", return_value=identity),
            patch.object(devctl.time, "sleep"),
            patch.object(devctl, "stop_service") as stop,
        ):
            restarted = devctl.reconcile_stale_services(
                [devctl.SERVICES["meeting"]], state, {"meeting": envelope}
            )
        self.assertEqual({"meeting"}, restarted)
        self.assertEqual(3, ready.call_count)
        stop.assert_called_once_with(devctl.SERVICES["meeting"], state)

    def test_meeting_identity_drift_or_verification_error_never_signals(self) -> None:
        envelope = self.publish()
        first = {"pgid": 1234, "startedAt": "first", "commandSha256": "a" * 64}
        second = {"pgid": 1234, "startedAt": "second", "commandSha256": "b" * 64}
        cases = (
            ([first, second], "identity changed"),
            (RuntimeError("identity verification failed"), "verification failed"),
        )
        for verify_effect, message in cases:
            with self.subTest(message=message):
                state = {"meeting": self.process_state("meeting", envelope)}
                with (
                    patch.object(devctl, "process_alive", return_value=True),
                    patch.object(
                        devctl,
                        "service_runtime_staleness",
                        return_value=(False, ""),
                    ),
                    patch.object(devctl, "service_ready", return_value=False),
                    patch.object(
                        devctl,
                        "verify_managed_process",
                        side_effect=verify_effect,
                    ),
                    patch.object(devctl, "stop_service") as stop,
                    self.assertRaisesRegex(RuntimeError, message),
                ):
                    devctl.reconcile_stale_services(
                        [devctl.SERVICES["meeting"]],
                        state,
                        {"meeting": envelope},
                    )
                stop.assert_not_called()

    def test_watcher_never_starts_a_selected_but_stopped_service(self) -> None:
        envelope = self.publish()
        with (
            patch.object(devctl, "reconcile_stale_services") as reconcile,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
            patch.object(devctl, "converge_managed_services") as converge,
        ):
            devctl.apply_watched_runtime_images(
                [devctl.SERVICES["meeting"]], {}, {"meeting": envelope}
            )
        reconcile.assert_not_called()
        start.assert_not_called()
        converge.assert_not_called()

    def test_watcher_preflight_failure_does_not_restart_unchanged_previous_image(self) -> None:
        current = self.publish()
        desired = copy.deepcopy(current)
        desired["sha256"] = "3" * 64
        desired["path"] = devctl._runtime_image_relative_path("meeting", "3" * 64)
        state = {"meeting": self.process_state("meeting", current)}
        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "validate_published_runtime_image"),
            patch.object(devctl, "verify_managed_process", return_value=identity),
            patch.object(
                devctl,
                "reconcile_stale_services",
                side_effect=RuntimeError("preflight failed"),
            ),
            patch.object(devctl, "stop_service") as stop,
            patch.object(devctl, "start_and_wait_services_in_phases") as start,
            self.assertRaisesRegex(RuntimeError, "previous immutable generation"),
        ):
            devctl.apply_watched_runtime_images(
                [devctl.SERVICES["meeting"]], state, {"meeting": desired}
            )
        stop.assert_not_called()
        start.assert_not_called()

    def test_watch_singleton_lock_is_owner_only_and_non_reentrant(self) -> None:
        with devctl.runtime_watch_lock():
            self.assertEqual(
                0o600,
                stat.S_IMODE(devctl.RUNTIME_WATCH_LOCK_FILE.stat().st_mode),
            )
            with self.assertRaisesRegex(RuntimeError, "already held"):
                with devctl.runtime_watch_lock():
                    pass

    def test_watcher_latches_a_failed_source_generation(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        installed: dict[int, object] = {}
        signal_default = object()
        sleep_calls = 0

        def install_handler(signum: int, handler: object) -> object:
            installed[signum] = handler
            return signal_default

        def sleep_then_stop(_seconds: float) -> None:
            nonlocal sleep_calls
            sleep_calls += 1
            if sleep_calls == 3:
                handler = installed[devctl.signal.SIGINT]
                assert callable(handler)
                handler(devctl.signal.SIGINT, None)

        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        with (
            patch.object(
                devctl,
                "resolve_services",
                return_value=[devctl.SERVICES["meeting"]],
            ),
            patch.object(devctl, "doctor"),
            patch.object(
                devctl, "runtime_watch_lock", side_effect=lambda: nullcontext()
            ),
            patch.object(
                devctl, "state_change_lock", side_effect=lambda: nullcontext()
            ),
            patch.object(
                devctl,
                "_managed_watch_snapshot",
                return_value={"meeting": identity},
            ),
            patch.object(devctl, "load_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(
                devctl,
                "capture_runtime_source_digest",
                side_effect=["a" * 64, "b" * 64, "b" * 64],
            ),
            patch.object(
                devctl,
                "stage_runtime_images",
                side_effect=RuntimeError("compile failed"),
            ) as stage,
            patch.object(devctl.time, "monotonic", side_effect=[0.0, 2.0]),
            patch.object(devctl.time, "sleep", side_effect=sleep_then_stop),
            patch.object(devctl.signal, "getsignal", return_value=signal_default),
            patch.object(devctl.signal, "signal", side_effect=install_handler),
        ):
            devctl.watch_runtime_services(["meeting"])
        stage.assert_called_once()

    def test_watcher_rebuilds_when_sources_change_after_staging_before_apply(self) -> None:
        envelope = self.publish()
        state = {"meeting": self.process_state("meeting", envelope)}
        desired = copy.deepcopy(envelope)
        desired["sha256"] = "4" * 64
        desired["path"] = devctl._runtime_image_relative_path("meeting", "4" * 64)
        installed: dict[int, object] = {}
        signal_default = object()
        digest_calls = 0
        sleep_calls = 0

        def source_digest(_services: object) -> str:
            nonlocal digest_calls
            digest_calls += 1
            if digest_calls == 1:
                return "a" * 64
            if digest_calls in {2, 3}:
                return "b" * 64
            return "c" * 64

        def install_handler(signum: int, handler: object) -> object:
            installed[signum] = handler
            return signal_default

        def bounded_sleep(_seconds: float) -> None:
            nonlocal sleep_calls
            sleep_calls += 1
            if sleep_calls > 8:
                handler = installed[devctl.signal.SIGINT]
                assert callable(handler)
                handler(devctl.signal.SIGINT, None)

        def apply_second_generation(*_args: object, **_kwargs: object) -> None:
            handler = installed[devctl.signal.SIGINT]
            assert callable(handler)
            handler(devctl.signal.SIGINT, None)

        identity = {"pgid": 1234, "startedAt": "now", "commandSha256": "f" * 64}
        monotonic_values = iter((0.0, 2.0, 3.0, 5.0))
        with (
            patch.object(
                devctl,
                "resolve_services",
                return_value=[devctl.SERVICES["meeting"]],
            ),
            patch.object(devctl, "doctor"),
            patch.object(
                devctl, "runtime_watch_lock", side_effect=lambda: nullcontext()
            ),
            patch.object(
                devctl, "state_change_lock", side_effect=lambda: nullcontext()
            ),
            patch.object(
                devctl,
                "_managed_watch_snapshot",
                return_value={"meeting": identity},
            ),
            patch.object(devctl, "load_state", return_value=state),
            patch.object(devctl, "process_alive", return_value=True),
            patch.object(devctl, "verify_managed_process", return_value=identity),
            patch.object(
                devctl, "capture_runtime_source_digest", side_effect=source_digest
            ),
            patch.object(
                devctl,
                "stage_runtime_images",
                side_effect=[{"meeting": desired}, {"meeting": desired}],
            ) as stage,
            patch.object(
                devctl,
                "apply_watched_runtime_images",
                side_effect=apply_second_generation,
            ) as apply,
            patch.object(
                devctl.time, "monotonic", side_effect=lambda: next(monotonic_values)
            ),
            patch.object(devctl.time, "sleep", side_effect=bounded_sleep),
            patch.object(devctl.signal, "getsignal", return_value=signal_default),
            patch.object(devctl.signal, "signal", side_effect=install_handler),
        ):
            devctl.watch_runtime_services(["meeting"])
        self.assertEqual(2, stage.call_count)
        apply.assert_called_once()


if __name__ == "__main__":
    unittest.main()
