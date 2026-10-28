#!/usr/bin/env python3
"""Local process supervisor for the DWP starter workspace."""

from __future__ import annotations

import argparse
import base64
import fcntl
import hashlib
import http.client
import io
import json
import math
import os
import re
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable, Iterator, Optional


BACKEND_ROOT = Path(__file__).resolve().parents[1]
WORKSPACE_ROOT = BACKEND_ROOT.parent
FRONTEND_ROOT = WORKSPACE_ROOT / "dwp-frontend"
AGENT_ROOT = WORKSPACE_ROOT / "dwp_agent"
AGENT_LOCAL_ENV_FILE = AGENT_ROOT / ".env.local"
RUNTIME_ROOT = BACKEND_ROOT / ".dev-runtime"
LOG_ROOT = RUNTIME_ROOT / "logs"
STATE_FILE = RUNTIME_ROOT / "processes.json"
STATE_LOCK_FILE = RUNTIME_ROOT / "devctl.lock"
RUNTIME_IMAGE_BUILD_LOCK_FILE = RUNTIME_ROOT / "runtime-image-build.lock"
RUNTIME_WATCH_LOCK_FILE = RUNTIME_ROOT / "runtime-watch.lock"
RUNTIME_IMAGE_BUILD_ROOT = RUNTIME_ROOT / "image-build"
RUNTIME_IMAGE_ROOT = RUNTIME_ROOT / "images" / "sha256"
LOCAL_APPROVAL_KEYS_ROOT = RUNTIME_ROOT / "keys"
LOCAL_APPROVAL_KEYS_FILE = LOCAL_APPROVAL_KEYS_ROOT / "approval-runtime-jwks.json"
LOCAL_JWK_GENERATOR = BACKEND_ROOT / "scripts" / "DevRuntimeJwkGenerator.java"
LOCAL_APPROVAL_KEY_IDS = (
    "approval_planning_owner_local",
    "approval_planning_transport_local",
    "auth_planning_attestation_local",
    "approval_planning_prohibited_local",
    "approval_sla_owner_local",
    "approval_sla_transport_local",
    "auth_sla_attestation_local",
    "notification-sla-recipient-transport:local",
    "approval-sla-recipient-authority:local",
)
LOCAL_APPROVAL_RUNTIME_SERVICES = frozenset({"auth", "approval", "notification"})
LOCAL_APPROVAL_RUNTIME_PREFIXES = (
    "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_",
    "DWP_APPROVAL_WORKFLOW_PLANNING_",
    "DWP_AUTH_APPROVAL_SYSTEM_SLA_",
    "DWP_APPROVAL_WORKFLOW_QUORUM_SLA_",
    "DWP_APPROVAL_SYSTEM_SLA_",
    "DWP_NOTIFICATION_APPROVAL_SLA_",
    "DWP_APPROVAL_INTEGRATION_",
)
MIN_AGENT_PYTHON = (3, 11)
MIN_FRONTEND_NODE = (24, 18, 0)
MAX_FRONTEND_NODE_MAJOR = 25
DEFAULT_FRONTEND_NODE_OPTIONS = "--max-old-space-size=8192"
ENVIRONMENT_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")
CANONICAL_POSITIVE_INTEGER = re.compile(r"^[1-9][0-9]*$")
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")
SERVICE_STARTUP_TIMEOUT_ENVIRONMENT = "DWP_DEVCTL_STARTUP_TIMEOUT_SECONDS"
SPRING_READINESS_PATH = "/actuator/health/readiness"
DEFAULT_SERVICE_STARTUP_TIMEOUT_SECONDS = 300
MIN_SERVICE_STARTUP_TIMEOUT_SECONDS = 60
MAX_SERVICE_STARTUP_TIMEOUT_SECONDS = 900
ARTIFACT_FINGERPRINT_ATTEMPTS = 3
ARTIFACT_SCAN_ATTEMPTS = 3
ARTIFACT_FINGERPRINT_CHUNK_BYTES = 1024 * 1024
NEW_PROCESS_TERM_TIMEOUT_SECONDS = 5.0
NEW_PROCESS_KILL_TIMEOUT_SECONDS = 3.0
PROCESS_GROUP_POLL_INTERVAL_SECONDS = 0.05
PORT_RELEASE_TIMEOUT_SECONDS = 10.0
PORT_RELEASE_POLL_INTERVAL_SECONDS = 0.1
RUNTIME_IMAGE_SCHEMA_VERSION = 1
RUNTIME_IMAGE_MODE = 0o400
RUNTIME_DIRECTORY_MODE = 0o700
RUNTIME_IMAGE_MAX_NESTED_ZIP_DEPTH = 3
RUNTIME_IMAGE_MAX_ZIP_ENTRIES = 250_000
RUNTIME_IMAGE_MAX_UNCOMPRESSED_ZIP_BYTES = 4 * 1024 * 1024 * 1024
WATCH_POLL_INTERVAL_SECONDS = 1.0
WATCH_DEBOUNCE_SECONDS = 1.0
LEGACY_IDENTITY_CONFIRMATION_INTERVAL_SECONDS = 0.1
MEETING_READINESS_MAXIMUM_RESPONSE_BYTES = 1024 * 1024
MEETING_READINESS_MAXIMUM_METRIC_VALUE = (1 << 63) - 1
MEETING_READINESS_USER_ID = "900018"
MEETING_READINESS_TENANT_ID = "1"
MEETING_BASELINE_ROLLOUT_REVISION = "rollout-" + "0" * 64
AGENT_MODEL_ENVIRONMENT = frozenset(
    {
        "DWP_MODEL_PROVIDER",
        "DWP_OPENAI_MODEL",
        "DWP_OPENAI_BASE_URL",
        "DWP_OPENAI_TIMEOUT_SECONDS",
        "DWP_OPENAI_MAX_OUTPUT_TOKENS",
        "AZURE_OPENAI_ENDPOINT",
        "AZURE_OPENAI_API_KEY",
        "OPENAI_API_KEY",
    }
)


@dataclass(frozen=True)
class Service:
    name: str
    cwd: Path
    command: tuple[str, ...]
    port: int
    health_path: str


class PreparedRuntimeImages(dict[str, dict[str, object]]):
    """Immutable-image mapping bound to the exact source generation it built."""

    def __init__(
        self,
        images: dict[str, dict[str, object]],
        source_digest: str,
    ) -> None:
        super().__init__(images)
        if not SHA256_HEX.fullmatch(source_digest):
            raise RuntimeError("Prepared runtime source digest is not canonical.")
        self.source_digest = source_digest


class MissingRuntimeArtifactError(RuntimeError):
    """A missing build output that a controlled restart may regenerate."""


@dataclass(frozen=True)
class HrisControlledDatabase:
    service: str
    module: str
    database: str
    runtime_role: str
    runtime_password_key: str
    migration_role: str
    migration_password_key: str
    publisher_role: str
    publisher_password_key: str
    control_task: str
    stream_key: str


HRIS_CONTROLLED_DATABASES = {
    "time": HrisControlledDatabase(
        "time",
        "dwp-time-server",
        "dwp_time",
        "dwp_time_runtime",
        "TIME_DB_PASSWORD",
        "dwp_time_migration",
        "TIME_MIGRATION_DB_PASSWORD",
        "dwp_time_projection_publisher",
        "TIME_PROJECTION_PUBLISHER_DB_PASSWORD",
        ":dwp-migration-control:runTimeMigrationControl",
        "time-main",
    ),
    "payroll": HrisControlledDatabase(
        "payroll",
        "dwp-payroll-server",
        "dwp_payroll",
        "dwp_payroll_runtime",
        "PAYROLL_DB_PASSWORD",
        "dwp_payroll_migration",
        "PAYROLL_MIGRATION_DB_PASSWORD",
        "dwp_payroll_projection_publisher",
        "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD",
        ":dwp-migration-control:runPayrollMigrationControl",
        "payroll-main",
    ),
}
MIGRATION_CONTROL_RECEIPT_ROOT = RUNTIME_ROOT / "migration-control"
MIGRATION_CONTROL_OUTPUT_PREFIX = "DWP_MIGRATION_CONTROL_RECEIPT="
HRIS_SHARED_ENVIRONMENT = {
    "time": {"SERVICE_TIME_URL", "DWP_TIME_SERVICE_TOKEN"},
    "payroll": {"SERVICE_PAYROLL_URL", "DWP_PAYROLL_SERVICE_TOKEN"},
}
HRIS_OWNER_ENVIRONMENT = {
    "time": {
        "TIME_DB_NAME",
        "TIME_DB_USERNAME",
        "TIME_DB_PASSWORD",
        "TIME_MIGRATION_DB_USERNAME",
        "TIME_MIGRATION_DB_PASSWORD",
        "TIME_PROJECTION_PUBLISHER_DB_USERNAME",
        "TIME_PROJECTION_PUBLISHER_DB_PASSWORD",
        "DWP_TIME_DB_POOL_SIZE",
        "DWP_TIME_WORK_REGIME_API_ENABLED",
        "DWP_HRIS_TIME_CLOCK_INGESTION_ENABLED",
        "DWP_HRIS_TIME_CLOCK_INGESTION_CURSOR_SECRET",
        "DWP_HRIS_TIME_INTERPRETATION_ENABLED",
        "DWP_HRIS_TIME_INTERPRETATION_CURSOR_SECRET",
    },
    "payroll": {
        "PAYROLL_DB_NAME",
        "PAYROLL_DB_USERNAME",
        "PAYROLL_DB_PASSWORD",
        "PAYROLL_MIGRATION_DB_USERNAME",
        "PAYROLL_MIGRATION_DB_PASSWORD",
        "PAYROLL_PROJECTION_PUBLISHER_DB_USERNAME",
        "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD",
        "DWP_PAYROLL_DB_POOL_SIZE",
        "DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED",
        "DWP_HRIS_PAYROLL_CONFIGURATION_WAVE2_ENABLED",
    },
}
HRIS_RECEIPT_ENVIRONMENT = {
    service_name: {
        f"DWP_{service_name.upper()}_MIGRATION_CONTROL_RUN_RECEIPT_JSON",
        f"DWP_{service_name.upper()}_MIGRATION_CONTROL_RUN_RECEIPT_SHA256",
        f"DWP_{service_name.upper()}_MIGRATION_CONTROL_REFERENCE",
    }
    for service_name in HRIS_CONTROLLED_DATABASES
}
HRIS_DARK_FEATURE_ENVIRONMENT = {
    "time": {"DWP_HRIS_TIME_TIMECARD_ENABLED"},
    "payroll": {
        "DWP_HRIS_PAYROLL_CALCULATION_ENABLED",
        "DWP_HRIS_PAYROLL_COMPENSATION_PLAN_INPUT_ENABLED",
        "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_ENABLED",
        "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_TOKEN",
        "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_RUN_ID",
    },
}


def agent_python() -> str:
    virtualenv_python = AGENT_ROOT / ".venv" / "bin" / "python"
    return str(virtualenv_python) if virtualenv_python.exists() else "python3"


def frontend_node() -> str:
    return os.environ.get("DWP_NODE_BIN", "node")


def frontend_corepack_command(*arguments: str) -> tuple[str, ...]:
    corepack = shutil.which("corepack") or "corepack"
    configured_node = os.environ.get("DWP_NODE_BIN")
    if configured_node:
        return (configured_node, corepack, *arguments)
    return (corepack, *arguments)


def frontend_node_version() -> tuple[int, int, int]:
    result = subprocess.run(
        (frontend_node(), "--version"),
        check=True,
        text=True,
        capture_output=True,
    )
    raw_version = result.stdout.strip().removeprefix("v")
    try:
        major, minor, patch = raw_version.split(".", maxsplit=2)
        return int(major), int(minor), int(patch.split("-", maxsplit=1)[0])
    except (TypeError, ValueError) as error:
        raise RuntimeError(f"Unable to parse Node.js version: {raw_version}") from error


def spring_boot_module_ready(module_name: str) -> bool:
    module_root = BACKEND_ROOT / module_name
    source_root = module_root / "src" / "main" / "java"
    return (
        (module_root / "build.gradle").is_file()
        and (module_root / "src" / "main" / "resources" / "application.yml").is_file()
        and source_root.is_dir()
        and any(source_root.rglob("*Application.java"))
    )


SERVICES = {
    "auth": Service(
        "auth",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-auth-server:bootRun"),
        8001,
        SPRING_READINESS_PATH,
    ),
    "platform": Service(
        "platform",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-platform-server:bootRun"),
        8002,
        SPRING_READINESS_PATH,
    ),
    "people": Service(
        "people",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-people-server:bootRun"),
        8003,
        SPRING_READINESS_PATH,
    ),
    "provider": Service(
        "provider",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-provider-server:bootRun"),
        8004,
        SPRING_READINESS_PATH,
    ),
    "approval": Service(
        "approval",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-approval-server:bootRun"),
        8005,
        SPRING_READINESS_PATH,
    ),
    "space": Service(
        "space",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-space-server:bootRun"),
        8006,
        SPRING_READINESS_PATH,
    ),
    "messaging": Service(
        "messaging",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-messaging-server:bootRun"),
        8007,
        SPRING_READINESS_PATH,
    ),
    "notification": Service(
        "notification",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-notification-server:bootRun"),
        8008,
        SPRING_READINESS_PATH,
    ),
    "meeting": Service(
        "meeting",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-meeting-server:bootRun"),
        8009,
        SPRING_READINESS_PATH,
    ),
    "agent": Service(
        "agent",
        AGENT_ROOT,
        (
            agent_python(),
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
        8010,
        "/health",
    ),
    "time": Service(
        "time",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-time-server:bootRun"),
        8011,
        SPRING_READINESS_PATH,
    ),
    "payroll": Service(
        "payroll",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-payroll-server:bootRun"),
        8012,
        SPRING_READINESS_PATH,
    ),
    "gateway": Service(
        "gateway",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-gateway:bootRun"),
        8080,
        SPRING_READINESS_PATH,
    ),
    "frontend": Service(
        "frontend",
        FRONTEND_ROOT,
        frontend_corepack_command("yarn", "dev", "--host", "0.0.0.0"),
        4200,
        "/",
    ),
}

JAVA_SERVICE_MODULES = {
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
JAVA_SERVICE_NAMES = frozenset(JAVA_SERVICE_MODULES)
WATCH_SHARED_MODULES = (
    "dwp-core",
    "dwp-audit",
    "dwp-observability",
    "dwp-platform-contracts",
)

SHARED_RUNTIME_ARTIFACT_DIRECTORIES = {
    "dwp-core": BACKEND_ROOT / "dwp-core" / "build" / "libs",
    "dwp-audit": BACKEND_ROOT / "dwp-audit" / "build" / "libs",
    "dwp-observability": BACKEND_ROOT / "dwp-observability" / "build" / "libs",
    "dwp-platform-contracts": (
        BACKEND_ROOT / "dwp-platform-contracts" / "build" / "libs"
    ),
}
CORE_SHARED_RUNTIME_ARTIFACTS = (
    "dwp-core",
    "dwp-audit",
    "dwp-observability",
    "dwp-platform-contracts",
)
SERVICE_SHARED_RUNTIME_ARTIFACTS = {
    "auth": CORE_SHARED_RUNTIME_ARTIFACTS,
    "platform": CORE_SHARED_RUNTIME_ARTIFACTS,
    "people": CORE_SHARED_RUNTIME_ARTIFACTS,
    "provider": CORE_SHARED_RUNTIME_ARTIFACTS,
    "approval": CORE_SHARED_RUNTIME_ARTIFACTS,
    "space": CORE_SHARED_RUNTIME_ARTIFACTS,
    "messaging": CORE_SHARED_RUNTIME_ARTIFACTS,
    "notification": CORE_SHARED_RUNTIME_ARTIFACTS,
    "meeting": CORE_SHARED_RUNTIME_ARTIFACTS,
    "time": CORE_SHARED_RUNTIME_ARTIFACTS,
    "payroll": CORE_SHARED_RUNTIME_ARTIFACTS,
    "gateway": ("dwp-audit", "dwp-observability"),
}

CORE_SERVICES = {"auth", "platform", "people", "provider", "approval", "space"}
HRIS_RUNTIME_SERVICES = {"time", "payroll"}
OPTIONAL_RUNTIME_SERVICES = {
    service_name
    for service_name, module_name in {
        "messaging": "dwp-messaging-server",
        "notification": "dwp-notification-server",
        "meeting": "dwp-meeting-server",
    }.items()
    if spring_boot_module_ready(module_name)
}

PROFILES = {
    "full": CORE_SERVICES | HRIS_RUNTIME_SERVICES | OPTIONAL_RUNTIME_SERVICES
    | {"agent", "gateway", "frontend"},
    "core": CORE_SERVICES | HRIS_RUNTIME_SERVICES | OPTIONAL_RUNTIME_SERVICES
    | {"gateway", "frontend"},
    "backend": CORE_SERVICES | HRIS_RUNTIME_SERVICES | OPTIONAL_RUNTIME_SERVICES
    | {"agent", "gateway"},
    "contracts": CORE_SERVICES | HRIS_RUNTIME_SERVICES | OPTIONAL_RUNTIME_SERVICES
    | {"gateway"},
    "hris": CORE_SERVICES | HRIS_RUNTIME_SERVICES | OPTIONAL_RUNTIME_SERVICES
    | {"gateway", "frontend"},
    "auth": {"auth"},
    "platform": {"platform"},
    "people": {"people"},
    "provider": {"provider"},
    "agent": {"agent"},
    "gateway": {"gateway"},
    "approval": {"approval"},
    "space": {"space"},
    "time": {"time"},
    "payroll": {"payroll"},
    "web": {"frontend"},
}
for optional_service in OPTIONAL_RUNTIME_SERVICES:
    PROFILES[optional_service] = {optional_service}
if "meeting" in OPTIONAL_RUNTIME_SERVICES:
    PROFILES["meeting"] = {"auth", "notification", "meeting"}

START_ORDER = (
    "auth",
    "platform",
    "people",
    "provider",
    "approval",
    "space",
    "messaging",
    "notification",
    "meeting",
    "agent",
    "time",
    "payroll",
    "gateway",
    "frontend",
)
START_PHASES = (
    ("platform",),
    ("auth", "people", "provider", "approval", "space", "messaging", "notification", "agent"),
    ("meeting",),
    ("time", "payroll"),
    ("gateway",),
    ("frontend",),
)


def _state_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise RuntimeError(f"Duplicate JSON member in {STATE_FILE}: {key}")
        value[key] = item
    return value


def _valid_state_integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _runtime_image_relative_path(service_name: str, digest: str) -> str:
    return (
        RUNTIME_IMAGE_ROOT.relative_to(BACKEND_ROOT) / digest / f"{service_name}.jar"
    ).as_posix()


def _valid_runtime_image_envelope(service_name: str, value: object) -> bool:
    if not isinstance(value, dict) or set(value) != {
        "version",
        "service",
        "module",
        "path",
        "sha256",
        "size",
        "migrationDigest",
    }:
        return False
    digest = value.get("sha256")
    migration_digest = value.get("migrationDigest")
    return bool(
        value.get("version") == RUNTIME_IMAGE_SCHEMA_VERSION
        and not isinstance(value.get("version"), bool)
        and value.get("service") == service_name
        and value.get("module") == JAVA_SERVICE_MODULES.get(service_name)
        and isinstance(digest, str)
        and SHA256_HEX.fullmatch(digest)
        and isinstance(migration_digest, str)
        and SHA256_HEX.fullmatch(migration_digest)
        and _valid_state_integer(value.get("size"))
        and value.get("path") == _runtime_image_relative_path(service_name, digest)
    )


def _validate_state(value: object) -> dict[str, dict[str, object]]:
    if not isinstance(value, dict):
        raise RuntimeError(f"Managed process state must be a JSON object: {STATE_FILE}")
    state: dict[str, dict[str, object]] = {}
    for name, process_state in value.items():
        if name not in SERVICES or not isinstance(process_state, dict):
            raise RuntimeError(f"Invalid managed process state entry: {name}")
        pid = process_state.get("pid")
        port = process_state.get("port")
        command = process_state.get("command")
        log_path = process_state.get("log")
        if (
            not _valid_state_integer(pid)
            or not _valid_state_integer(port)
            or port != SERVICES[name].port
            or not isinstance(command, list)
            or not command
            or any(not isinstance(argument, str) for argument in command)
            or not isinstance(log_path, str)
            or not log_path
        ):
            raise RuntimeError(f"Invalid managed process state entry: {name}")
        runtime_image = process_state.get("runtimeImage")
        if runtime_image is not None and not _valid_runtime_image_envelope(
            name, runtime_image
        ):
            raise RuntimeError(f"Invalid managed runtime image state entry: {name}")
        if runtime_image is not None and name not in JAVA_SERVICE_NAMES:
            raise RuntimeError(f"Non-Java service has a managed runtime image: {name}")
        if runtime_image is not None:
            expected_command = [
                "java",
                "-jar",
                str(BACKEND_ROOT / str(runtime_image["path"])),
            ]
            if command != expected_command:
                raise RuntimeError(
                    f"Managed runtime image command is not canonical: {name}"
                )
        state[name] = process_state
    return state


def _load_state(*, allow_legacy_read_permissions: bool) -> dict[str, dict[str, object]]:
    if not STATE_FILE.exists() and not STATE_FILE.is_symlink():
        return {}
    try:
        metadata = STATE_FILE.lstat()
        unsafe_permissions = (
            metadata.st_mode & 0o033
            if allow_legacy_read_permissions
            else metadata.st_mode & 0o077
        )
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or unsafe_permissions
        ):
            raise RuntimeError(
                f"Managed process state must be an owner-controlled regular file: "
                f"{STATE_FILE}"
            )
        value = json.loads(
            STATE_FILE.read_text(encoding="utf-8"),
            object_pairs_hook=_state_json_object,
        )
    except RuntimeError:
        raise
    except (json.JSONDecodeError, OSError) as error:
        raise RuntimeError(f"Managed process state is unreadable: {STATE_FILE}") from error
    return _validate_state(value)


def load_state() -> dict[str, dict[str, object]]:
    return _load_state(allow_legacy_read_permissions=False)


def load_legacy_migration_state() -> dict[str, dict[str, object]]:
    """Read old owner-controlled state while allowing group/other read bits only."""
    return _load_state(allow_legacy_read_permissions=True)


def _fsync_runtime_directory() -> None:
    try:
        descriptor = os.open(RUNTIME_ROOT, os.O_RDONLY)
    except OSError as error:
        raise RuntimeError(
            f"Unable to open runtime state directory for fsync: {RUNTIME_ROOT}"
        ) from error
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def save_state(state: dict[str, dict[str, object]]) -> None:
    _validate_state(state)
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    if not state:
        STATE_FILE.unlink(missing_ok=True)
        _fsync_runtime_directory()
        return
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{STATE_FILE.name}.", suffix=".tmp", dir=RUNTIME_ROOT
    )
    temporary_path = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(state, target, ensure_ascii=False, indent=2, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        descriptor = -1
        os.replace(temporary_path, STATE_FILE)
        _fsync_runtime_directory()
    finally:
        if descriptor >= 0:
            try:
                os.close(descriptor)
            except OSError:
                pass
        temporary_path.unlink(missing_ok=True)


@contextmanager
def state_change_lock() -> Iterator[None]:
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    flags = os.O_CREAT | os.O_RDWR
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(STATE_LOCK_FILE, flags, 0o600)
    except OSError as error:
        raise RuntimeError(f"Unable to open development state lock: {STATE_LOCK_FILE}") from error
    locked = False
    try:
        os.fchmod(descriptor, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
        except OSError as error:
            raise RuntimeError(
                f"Unable to acquire development state lock: {STATE_LOCK_FILE}"
            ) from error
        locked = True
        yield
    finally:
        try:
            if locked:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def _ensure_owner_directory(path: Path) -> None:
    if path != RUNTIME_ROOT:
        _ensure_owner_directory(path.parent)
    created = False
    try:
        path.mkdir(mode=RUNTIME_DIRECTORY_MODE)
        created = True
    except FileExistsError:
        pass
    metadata = path.lstat()
    if (
        stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.getuid()
    ):
        raise RuntimeError(f"Runtime path must be an owner-controlled directory: {path}")
    if stat.S_IMODE(metadata.st_mode) != RUNTIME_DIRECTORY_MODE:
        os.chmod(path, RUNTIME_DIRECTORY_MODE)
    if created:
        _fsync_directory(path.parent)


@contextmanager
def _runtime_file_lock(path: Path, *, nonblocking: bool = False) -> Iterator[None]:
    _ensure_owner_directory(RUNTIME_ROOT)
    flags = os.O_CREAT | os.O_RDWR
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError as error:
        raise RuntimeError(f"Unable to open runtime lock: {path}") from error
    locked = False
    try:
        os.fchmod(descriptor, 0o600)
        operation = fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblocking else 0)
        try:
            fcntl.flock(descriptor, operation)
        except BlockingIOError as error:
            raise RuntimeError(f"Runtime lock is already held: {path}") from error
        except OSError as error:
            raise RuntimeError(f"Unable to acquire runtime lock: {path}") from error
        locked = True
        yield
    finally:
        try:
            if locked:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def runtime_image_build_lock() -> Iterator[None]:
    return _runtime_file_lock(RUNTIME_IMAGE_BUILD_LOCK_FILE)


def runtime_watch_lock() -> Iterator[None]:
    return _runtime_file_lock(RUNTIME_WATCH_LOCK_FILE, nonblocking=True)


def _strict_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise RuntimeError(f"Duplicate JSON member in {LOCAL_APPROVAL_KEYS_FILE}: {key}")
        value[key] = item
    return value


def _canonical_base64url(value: object, *, minimum_bytes: int = 1) -> bool:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, TypeError):
        return False
    return (
        len(decoded) >= minimum_bytes
        and base64.urlsafe_b64encode(decoded).rstrip(b"=").decode("ascii") == value
    )


def _validate_local_approval_keys(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != {"version", "keys"}:
        raise RuntimeError("Local Approval runtime key file has an invalid envelope.")
    if value["version"] != 1 or isinstance(value["version"], bool):
        raise RuntimeError("Local Approval runtime key file has an unsupported version.")
    keys = value["keys"]
    if not isinstance(keys, dict) or set(keys) != set(LOCAL_APPROVAL_KEY_IDS):
        raise RuntimeError("Local Approval runtime key inventory is incomplete.")

    public_fields = {"kty", "kid", "use", "alg", "n", "e"}
    private_fields = public_fields | {"d", "p", "q", "dp", "dq", "qi"}
    moduli: set[str] = set()
    for key_id in LOCAL_APPROVAL_KEY_IDS:
        pair = keys[key_id]
        if not isinstance(pair, dict) or set(pair) != {"private", "public"}:
            raise RuntimeError(f"Local Approval key pair is malformed: {key_id}")
        private = pair["private"]
        public = pair["public"]
        if (
            not isinstance(private, dict)
            or set(private) != private_fields
            or not isinstance(public, dict)
            or set(public) != public_fields
        ):
            raise RuntimeError(f"Local Approval JWK fields are malformed: {key_id}")
        for jwk in (private, public):
            if (
                jwk["kty"] != "RSA"
                or jwk["kid"] != key_id
                or jwk["use"] != "sig"
                or jwk["alg"] != "RS256"
                or not _canonical_base64url(jwk["n"], minimum_bytes=256)
                or not _canonical_base64url(jwk["e"])
            ):
                raise RuntimeError(f"Local Approval JWK metadata is invalid: {key_id}")
        for field in private_fields - public_fields:
            if not _canonical_base64url(private[field], minimum_bytes=32):
                raise RuntimeError(f"Local Approval private JWK is invalid: {key_id}")
        if any(private[field] != public[field] for field in public_fields):
            raise RuntimeError(f"Local Approval public/private JWK mismatch: {key_id}")
        if private["n"] in moduli:
            raise RuntimeError("Local Approval runtime keys must be cryptographically disjoint.")
        moduli.add(str(private["n"]))
    return value


def load_or_create_local_approval_keys(
    path: Path | None = None,
) -> dict[str, object]:
    path = path or LOCAL_APPROVAL_KEYS_FILE
    if path.exists() or path.is_symlink():
        metadata = path.lstat()
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or metadata.st_mode & 0o077
        ):
            raise RuntimeError(
                f"Local Approval runtime keys must be an owner-only regular file: {path}"
            )
        try:
            parsed = json.loads(
                path.read_text(encoding="utf-8"),
                object_pairs_hook=_strict_json_object,
            )
        except (json.JSONDecodeError, OSError) as error:
            raise RuntimeError(f"Unable to read local Approval runtime keys: {path}") from error
        return _validate_local_approval_keys(parsed)

    parent = path.parent
    if parent.exists() or parent.is_symlink():
        metadata = parent.lstat()
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or metadata.st_mode & 0o077
        ):
            raise RuntimeError(
                f"Local Approval runtime key directory must be owner-only: {parent}"
            )
    else:
        parent.mkdir(parents=True, mode=0o700)
    if not LOCAL_JWK_GENERATOR.is_file():
        raise RuntimeError(f"Missing local Approval JWK generator: {LOCAL_JWK_GENERATOR}")
    generated = subprocess.run(
        ("java", str(LOCAL_JWK_GENERATOR), *LOCAL_APPROVAL_KEY_IDS),
        cwd=BACKEND_ROOT,
        check=True,
        text=True,
        capture_output=True,
        timeout=120,
    ).stdout
    try:
        parsed = json.loads(generated, object_pairs_hook=_strict_json_object)
    except json.JSONDecodeError as error:
        raise RuntimeError("Local Approval JWK generator returned invalid JSON.") from error
    material = _validate_local_approval_keys(parsed)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError:
        return load_or_create_local_approval_keys(path)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        json.dump(material, target, separators=(",", ":"), sort_keys=True)
        target.write("\n")
        target.flush()
        os.fsync(target.fileno())
    return material


def _compact_json(value: object) -> str:
    return json.dumps(value, separators=(",", ":"), sort_keys=True)


def _local_approval_runtime_defaults(service_name: str) -> dict[str, str]:
    if service_name not in LOCAL_APPROVAL_RUNTIME_SERVICES:
        return {}
    material = load_or_create_local_approval_keys()
    keys = material["keys"]
    assert isinstance(keys, dict)

    def private(key_id: str) -> str:
        pair = keys[key_id]
        assert isinstance(pair, dict)
        return _compact_json(pair["private"])

    def public_set(*key_ids: str) -> str:
        values = []
        for key_id in key_ids:
            pair = keys[key_id]
            assert isinstance(pair, dict)
            values.append(pair["public"])
        return _compact_json({"keys": values})

    planning_owner = "approval_planning_owner_local"
    planning_transport = "approval_planning_transport_local"
    planning_attestation = "auth_planning_attestation_local"
    planning_prohibited = "approval_planning_prohibited_local"
    sla_owner = "approval_sla_owner_local"
    sla_transport = "approval_sla_transport_local"
    sla_attestation = "auth_sla_attestation_local"
    notification_transport = "notification-sla-recipient-transport:local"
    notification_attestation = "approval-sla-recipient-authority:local"

    by_service = {
        "auth": {
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ENABLED": "true",
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_OWNER_PUBLIC_JWKS": public_set(planning_owner),
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_TRANSPORT_PUBLIC_JWKS": public_set(planning_transport),
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ATTESTATION_PRIVATE_JWK": private(planning_attestation),
            "DWP_AUTH_APPROVAL_WORKFLOW_PLANNING_ATTESTATION_PUBLIC_JWKS": public_set(planning_attestation),
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_ENABLED": "true",
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_OWNER_PUBLIC_JWKS": public_set(sla_owner),
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_TRANSPORT_PUBLIC_JWKS": public_set(sla_transport),
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_ATTESTATION_PRIVATE_JWK": private(sla_attestation),
            "DWP_AUTH_APPROVAL_SYSTEM_SLA_ATTESTATION_PUBLIC_JWKS": public_set(sla_attestation),
        },
        "approval": {
            "DWP_APPROVAL_WORKFLOW_PLANNING_ENABLED": "true",
            "DWP_APPROVAL_WORKFLOW_PLANNING_OWNER_PRIVATE_KEY": private(planning_owner),
            "DWP_APPROVAL_WORKFLOW_PLANNING_TRANSPORT_PRIVATE_KEY": private(planning_transport),
            "DWP_APPROVAL_WORKFLOW_PLANNING_AUTH_TRUSTED_KEYS": public_set(planning_attestation),
            "DWP_APPROVAL_WORKFLOW_PLANNING_PROHIBITED_KEY_CATALOGUE": public_set(planning_prohibited),
            "DWP_APPROVAL_WORKFLOW_PLANNING_ENDPOINT": "http://localhost:8001/internal/approval-workflow/admin-planning",
            "DWP_APPROVAL_WORKFLOW_QUORUM_SLA_ENABLED": "true",
            "DWP_APPROVAL_WORKFLOW_QUORUM_SLA_BATCH_SIZE": "50",
            "DWP_APPROVAL_WORKFLOW_QUORUM_SLA_LEASE_SECONDS": "30",
            "DWP_APPROVAL_WORKFLOW_QUORUM_SLA_POLL_DELAY_MS": "2000",
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_ENABLED": "true",
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_AUTH_BASE_URL": "http://localhost:8001",
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_OWNER_PRIVATE_JWK": private(sla_owner),
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_TRANSPORT_PRIVATE_JWK": private(sla_transport),
            "DWP_APPROVAL_SYSTEM_SLA_SOURCE_ATTESTATION_PUBLIC_JWKS": public_set(sla_attestation),
            "DWP_APPROVAL_SYSTEM_SLA_NOTIFICATION_TRANSPORT_PUBLIC_JWKS": public_set(notification_transport),
            "DWP_APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_PRIVATE_JWK": private(notification_attestation),
            "DWP_APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_PUBLIC_JWKS": public_set(notification_attestation),
            "DWP_APPROVAL_INTEGRATION_RELAY_ENABLED": "true",
            "DWP_APPROVAL_INTEGRATION_TOPIC": "dwp.approval.events.v1",
            "DWP_APPROVAL_INTEGRATION_BATCH_SIZE": "50",
            "DWP_APPROVAL_INTEGRATION_MAXIMUM_ATTEMPTS": "10",
            "DWP_APPROVAL_INTEGRATION_POLL_DELAY_MS": "2000",
        },
        "notification": {
            "DWP_NOTIFICATION_APPROVAL_SLA_ENABLED": "true",
            "DWP_NOTIFICATION_APPROVAL_SLA_ENDPOINT": "http://localhost:8005/internal/approval/v1/quorum-sla/recipient-authority/evaluate",
            "DWP_NOTIFICATION_APPROVAL_SLA_TOPIC": "dwp.approval.events.v1",
            "DWP_NOTIFICATION_APPROVAL_SLA_GROUP_ID": "dwp-notification-approval-system-sla-v1",
            "DWP_NOTIFICATION_APPROVAL_SLA_DEAD_LETTER_TOPIC": "dwp.approval.events.v1.DLT",
            "DWP_NOTIFICATION_APPROVAL_SLA_TRANSPORT_PRIVATE_JWK": private(notification_transport),
            "DWP_NOTIFICATION_APPROVAL_SLA_RECIPIENT_AUTHORITY_JWKS": public_set(notification_attestation),
            "DWP_NOTIFICATION_APPROVAL_SLA_PROHIBITED_JWKS": public_set(sla_owner, sla_transport, sla_attestation),
        },
    }
    return by_service[service_name]


def process_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    status = subprocess.run(
        ("ps", "-o", "stat=", "-p", str(pid)),
        check=False,
        text=True,
        capture_output=True,
    ).stdout.strip()
    return bool(status) and not status.startswith("Z")


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.4):
            return True
    except OSError:
        return False


def wait_for_service_port_release(
    service: Service,
    timeout_seconds: float = PORT_RELEASE_TIMEOUT_SECONDS,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while port_open(service.port):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(
                f"Managed {service.name} process group stopped and its state was "
                f"removed, but port {service.port} remains occupied; refusing to "
                "adopt the port owner or start a replacement."
            )
        time.sleep(min(PORT_RELEASE_POLL_INTERVAL_SECONDS, remaining))


def _ps_value(pid: int, field: str) -> str:
    result = subprocess.run(
        ("ps", "-ww", "-o", f"{field}=", "-p", str(pid)),
        check=False,
        text=True,
        capture_output=True,
    )
    value = result.stdout.strip()
    if result.returncode != 0 or not value:
        raise RuntimeError(f"Unable to inspect managed process {pid}.")
    return value


def _command_matches_launch_spec(
    actual_command: str, launch_command: Iterable[str]
) -> bool:
    try:
        actual = shlex.split(actual_command)
    except ValueError:
        return False
    expected = list(launch_command)
    expected_tail = expected[1:]
    return bool(expected_tail) and actual[-len(expected_tail):] == expected_tail


def capture_process_identity(
    pid: int,
    service_name: str,
    launch_command: Iterable[str],
) -> dict[str, object]:
    if not process_alive(pid):
        raise RuntimeError(f"Managed {service_name} process {pid} is not running.")
    try:
        pgid = os.getpgid(pid)
    except OSError as error:
        raise RuntimeError(
            f"Unable to inspect managed {service_name} process {pid}."
        ) from error
    if pgid != pid:
        raise RuntimeError(
            f"Managed {service_name} process {pid} is not its process-group owner."
        )
    started_at = _ps_value(pid, "lstart")
    actual_command = _ps_value(pid, "command")
    if not _command_matches_launch_spec(actual_command, launch_command):
        raise RuntimeError(
            f"Managed {service_name} process {pid} does not match its recorded "
            "launch command."
        )
    return {
        "pgid": pgid,
        "startedAt": started_at,
        "commandSha256": hashlib.sha256(actual_command.encode("utf-8")).hexdigest(),
    }


def verify_managed_process(
    service: Service,
    process_state: dict[str, object],
) -> dict[str, object]:
    launch_command = process_state.get("command")
    if (
        not isinstance(launch_command, list)
        or not launch_command
        or any(not isinstance(argument, str) for argument in launch_command)
    ):
        raise RuntimeError(
            f"Managed {service.name} state has no valid recorded launch command."
        )
    pid = int(process_state["pid"])
    current = capture_process_identity(pid, service.name, launch_command)
    recorded = process_state.get("processIdentity")
    if recorded is None:
        raise RuntimeError(
            f"Managed {service.name} process ownership is legacy/unrecorded; "
            f"refusing automatic adoption or signalling. Stop pid {pid} outside "
            "devctl, verify its process group is gone, then rerun devctl."
        )
    if not isinstance(recorded, dict) or recorded != current:
        raise RuntimeError(
            f"Managed {service.name} process identity changed; refusing to signal pid {pid}."
        )
    return current


def capture_started_process_identity(
    service: Service, process_state: dict[str, object]
) -> dict[str, object]:
    """Allow command exec transitions only within the recorded process generation."""
    launch_command = process_state.get("command")
    recorded = process_state.get("processIdentity")
    if not isinstance(launch_command, list) or not isinstance(recorded, dict):
        raise RuntimeError(
            f"Managed {service.name} startup ownership is not recorded."
        )
    pid = int(process_state["pid"])
    current = capture_process_identity(pid, service.name, launch_command)
    if (
        recorded.get("pgid") != current["pgid"]
        or recorded.get("startedAt") != current["startedAt"]
    ):
        raise RuntimeError(
            f"Managed {service.name} process generation changed during startup."
        )
    return current


def refresh_started_process_identity(
    service: Service,
    process_state: dict[str, object],
    state: dict[str, dict[str, object]],
) -> dict[str, object]:
    current = capture_started_process_identity(service, process_state)
    if process_state.get("processIdentity") != current:
        process_state["processIdentity"] = current
        process_state.pop("runtimeArtifacts", None)
        save_state(state)
    return current


def managed_process_identity_matches(
    service: Service, process_state: dict[str, object]
) -> bool:
    try:
        verify_managed_process(service, process_state)
    except (RuntimeError, KeyError, TypeError, ValueError):
        return False
    return True


def migrate_legacy_process_identities(
    service_names: Iterable[str], confirmed: bool
) -> None:
    """Explicitly bind legacy state to a stable live identity without signalling it."""
    if not confirmed:
        raise RuntimeError(
            "Legacy process identity migration requires --yes because a later up/stop "
            "may use the recorded identity for signalling."
        )
    names = list(dict.fromkeys(service_names))
    if not names:
        raise RuntimeError("Select at least one service for legacy identity migration.")
    state = load_legacy_migration_state()
    migrations: dict[str, dict[str, object]] = {}
    already_recorded: list[str] = []
    for name in names:
        service = SERVICES[name]
        process_state = state.get(name)
        if process_state is None:
            raise RuntimeError(f"No managed state exists for {name}; nothing to migrate.")
        pid = int(process_state["pid"])
        if not process_alive(pid):
            _ensure_dead_state_has_no_live_group(service, process_state)
            raise RuntimeError(
                f"Managed {name} pid {pid} is not running; remove the dead state via "
                "a normal up/stop instead of migrating it."
            )
        if process_state.get("processIdentity") is not None:
            verify_managed_process(service, process_state)
            already_recorded.append(name)
            continue
        launch_command = process_state.get("command")
        if not isinstance(launch_command, list):
            raise RuntimeError(f"Managed {name} has no recorded launch command.")
        first = capture_process_identity(pid, name, launch_command)
        time.sleep(LEGACY_IDENTITY_CONFIRMATION_INTERVAL_SECONDS)
        second = capture_process_identity(pid, name, launch_command)
        if first != second:
            raise RuntimeError(
                f"Managed {name} identity changed during explicit migration; "
                "no state was updated."
            )
        migrations[name] = second

    for name, identity in migrations.items():
        state[name]["processIdentity"] = identity
    # Always rewrite through the atomic 0600 path so a validated legacy 0644
    # file is normalized even when every selected identity was already present.
    save_state(state)
    for name in already_recorded:
        print(f"{name:10} identity already recorded and verified")
    for name in migrations:
        print(f"{name:10} legacy identity explicitly migrated (no signal sent)")


def _artifact_stat_signature(
    metadata: os.stat_result,
) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_mode,
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _resolve_shared_runtime_artifact(module: str) -> Path:
    artifact_directory = SHARED_RUNTIME_ARTIFACT_DIRECTORIES[module]
    candidates = sorted(artifact_directory.glob(f"{module}-*-plain.jar"))
    if not candidates:
        raise MissingRuntimeArtifactError(
            f"Shared runtime artifact is missing for {module} in {artifact_directory}."
        )
    if len(candidates) != 1:
        raise RuntimeError(
            f"Expected exactly one {module} runtime artifact, found {len(candidates)} "
            f"in {artifact_directory}."
        )
    return candidates[0]


def _stable_artifact_fingerprint(module: str, path: Path) -> dict[str, object]:
    last_error = f"Shared runtime artifact changed while being fingerprinted: {path}"
    for attempt in range(ARTIFACT_FINGERPRINT_ATTEMPTS):
        try:
            path_metadata = path.lstat()
        except OSError as error:
            raise RuntimeError(f"Shared runtime artifact is unreadable: {path}") from error
        if (
            stat.S_ISLNK(path_metadata.st_mode)
            or not stat.S_ISREG(path_metadata.st_mode)
            or path_metadata.st_uid != os.getuid()
        ):
            raise RuntimeError(
                f"Shared runtime artifact must be an owner-controlled regular file: {path}"
            )

        flags = os.O_RDONLY
        if hasattr(os, "O_CLOEXEC"):
            flags |= os.O_CLOEXEC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            descriptor = os.open(path, flags)
        except OSError as error:
            raise RuntimeError(f"Shared runtime artifact is unreadable: {path}") from error
        try:
            before = os.fstat(descriptor)
            digest = hashlib.sha256()
            while True:
                chunk = os.read(descriptor, ARTIFACT_FINGERPRINT_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
            after = os.fstat(descriptor)
        finally:
            os.close(descriptor)
        try:
            final_path_metadata = path.lstat()
        except OSError:
            final_path_metadata = None

        signature = _artifact_stat_signature(before)
        if (
            stat.S_ISREG(before.st_mode)
            and before.st_uid == os.getuid()
            and signature == _artifact_stat_signature(after)
            and final_path_metadata is not None
            and signature == _artifact_stat_signature(final_path_metadata)
        ):
            try:
                relative_path = path.relative_to(BACKEND_ROOT)
            except ValueError as error:
                raise RuntimeError(
                    f"Shared runtime artifact is outside the backend workspace: {path}"
                ) from error
            return {
                "path": relative_path.as_posix(),
                "sha256": digest.hexdigest(),
                "size": before.st_size,
                "mtimeNs": before.st_mtime_ns,
                "ctimeNs": before.st_ctime_ns,
                "device": before.st_dev,
                "inode": before.st_ino,
            }
        if attempt + 1 < ARTIFACT_FINGERPRINT_ATTEMPTS:
            time.sleep(0.02)
    raise RuntimeError(last_error)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _gradle_project_build_directory(module: str) -> Path:
    project_path_key = f":{module}".encode("utf-8").hex()
    return RUNTIME_IMAGE_BUILD_ROOT / project_path_key


def _runtime_image_path(envelope: dict[str, object]) -> Path:
    path = BACKEND_ROOT / str(envelope["path"])
    expected = RUNTIME_IMAGE_ROOT / str(envelope["sha256"]) / (
        f"{envelope['service']}.jar"
    )
    if path != expected:
        raise RuntimeError("Runtime image path is not canonical.")
    return path


def _database_resource_entry(name: str) -> bool:
    return name.startswith("db/") or name.startswith("BOOT-INF/classes/db/")


def _regular_zip_entry(info: zipfile.ZipInfo) -> bool:
    mode = (info.external_attr >> 16) & 0xFFFF
    return not info.is_dir() and stat.S_IFMT(mode) in {0, stat.S_IFREG}


def _collect_database_resources(
    archive: zipfile.ZipFile,
    container_path: str,
    depth: int,
    budget: dict[str, int],
    resources: list[tuple[str, str, bytes]],
) -> None:
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        raise RuntimeError(
            f"Runtime image archive has duplicate entries: {container_path}"
        )
    budget["entries"] += len(entries)
    budget["bytes"] += sum(entry.file_size for entry in entries)
    if budget["entries"] > RUNTIME_IMAGE_MAX_ZIP_ENTRIES:
        raise RuntimeError("Runtime image nested archive entry limit was exceeded.")
    if budget["bytes"] > RUNTIME_IMAGE_MAX_UNCOMPRESSED_ZIP_BYTES:
        raise RuntimeError("Runtime image nested archive size limit was exceeded.")
    try:
        corrupt_entry = archive.testzip()
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        raise RuntimeError(
            f"Runtime image nested archive is corrupt: {container_path}"
        ) from error
    if corrupt_entry is not None:
        raise RuntimeError(
            f"Runtime image archive contains a corrupt entry {corrupt_entry}: "
            f"{container_path}"
        )

    for entry in sorted(entries, key=lambda candidate: candidate.filename):
        if entry.is_dir():
            continue
        if _database_resource_entry(entry.filename):
            if not _regular_zip_entry(entry):
                raise RuntimeError(
                    f"Database resource is not a regular archive entry: "
                    f"{container_path}!/{entry.filename}"
                )
            try:
                content = archive.read(entry)
            except (OSError, RuntimeError, zipfile.BadZipFile) as error:
                raise RuntimeError(
                    f"Database resource is unreadable: "
                    f"{container_path}!/{entry.filename}"
                ) from error
            resources.append((container_path, entry.filename, content))

        if (
            entry.filename.startswith("BOOT-INF/lib/")
            and entry.filename.endswith(".jar")
        ):
            if not _regular_zip_entry(entry):
                raise RuntimeError(
                    f"Nested runtime library is not a regular archive entry: "
                    f"{container_path}!/{entry.filename}"
                )
            if depth >= RUNTIME_IMAGE_MAX_NESTED_ZIP_DEPTH:
                raise RuntimeError("Runtime image nested archive depth limit was exceeded.")
            try:
                nested_content = archive.read(entry)
                with zipfile.ZipFile(io.BytesIO(nested_content)) as nested_archive:
                    _collect_database_resources(
                        nested_archive,
                        f"{container_path}!/{entry.filename}",
                        depth + 1,
                        budget,
                        resources,
                    )
            except RuntimeError:
                raise
            except (OSError, EOFError, zipfile.BadZipFile) as error:
                raise RuntimeError(
                    f"Nested runtime library is not a valid archive: "
                    f"{container_path}!/{entry.filename}"
                ) from error


def _database_resource_digest(archive: zipfile.ZipFile) -> str:
    resources: list[tuple[str, str, bytes]] = []
    _collect_database_resources(
        archive,
        "application.jar",
        0,
        {"entries": 0, "bytes": 0},
        resources,
    )
    digest = hashlib.sha256()
    for container_path, entry_path, content in sorted(resources):
        for value in (
            container_path.encode("utf-8"),
            entry_path.encode("utf-8"),
            content,
        ):
            digest.update(len(value).to_bytes(8, "big"))
            digest.update(value)
    return digest.hexdigest()


def _boot_jar_metadata(path: Path) -> tuple[dict[str, object], str]:
    before = _stable_artifact_fingerprint("runtime-image", path)
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise RuntimeError(f"Executable bootJar has duplicate entries: {path}")
            if "META-INF/MANIFEST.MF" not in names:
                raise RuntimeError(f"Executable bootJar has no manifest: {path}")
            manifest = archive.read("META-INF/MANIFEST.MF")
            if (
                b"Main-Class: org.springframework.boot.loader.launch.JarLauncher"
                not in manifest
                or not re.search(rb"(?:^|\r?\n)Start-Class: [^\r\n]+", manifest)
            ):
                raise RuntimeError(f"Executable bootJar manifest is incomplete: {path}")
            if (
                not any(name.startswith("BOOT-INF/classes/") for name in names)
                or not any(name.startswith("BOOT-INF/lib/") for name in names)
            ):
                raise RuntimeError(f"Executable bootJar layout is incomplete: {path}")
            migration_digest = _database_resource_digest(archive)
    except RuntimeError:
        raise
    except (OSError, zipfile.BadZipFile) as error:
        raise RuntimeError(f"Executable bootJar is unreadable: {path}") from error
    after = _stable_artifact_fingerprint("runtime-image", path)
    if before != after:
        raise RuntimeError(f"Executable bootJar changed during validation: {path}")
    return before, migration_digest


def _validate_isolated_output_directory(path: Path, *, missing_ok: bool) -> bool:
    try:
        relative_parts = path.relative_to(RUNTIME_IMAGE_BUILD_ROOT).parts
    except ValueError as error:
        raise RuntimeError(
            f"Isolated Gradle output escaped its build root: {path}"
        ) from error
    try:
        root_metadata = RUNTIME_IMAGE_BUILD_ROOT.lstat()
    except OSError as error:
        if missing_ok:
            return False
        raise RuntimeError(
            f"Isolated Gradle build root is missing: {RUNTIME_IMAGE_BUILD_ROOT}"
        ) from error
    if (
        stat.S_ISLNK(root_metadata.st_mode)
        or not stat.S_ISDIR(root_metadata.st_mode)
        or root_metadata.st_uid != os.getuid()
        or stat.S_IMODE(root_metadata.st_mode) != RUNTIME_DIRECTORY_MODE
    ):
        raise RuntimeError(
            f"Isolated Gradle build root must be owner-only and non-symlink: "
            f"{RUNTIME_IMAGE_BUILD_ROOT}"
        )
    current = RUNTIME_IMAGE_BUILD_ROOT
    for part in relative_parts:
        current = current / part
        try:
            metadata = current.lstat()
        except OSError as error:
            if missing_ok:
                return False
            raise RuntimeError(
                f"Isolated Gradle output directory is missing: {current}"
            ) from error
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.getuid()
        ):
            raise RuntimeError(
                f"Isolated Gradle output must stay in owner-controlled directories: "
                f"{current}"
            )
    return True


def _prepare_isolated_boot_jar_outputs(services: Iterable[Service]) -> None:
    for service in services:
        module = JAVA_SERVICE_MODULES[service.name]
        artifact_directory = _gradle_project_build_directory(module) / "libs"
        if not _validate_isolated_output_directory(
            artifact_directory, missing_ok=True
        ):
            continue
        for path in artifact_directory.glob("*.jar"):
            if path.name.endswith("-plain.jar"):
                continue
            metadata = path.lstat()
            if (
                stat.S_ISLNK(metadata.st_mode)
                or not stat.S_ISREG(metadata.st_mode)
                or metadata.st_uid != os.getuid()
            ):
                raise RuntimeError(
                    f"Refusing to replace unsafe isolated bootJar output: {path}"
                )
            path.unlink()
        _fsync_directory(artifact_directory)


def _discover_built_boot_jar(service_name: str) -> Path:
    module = JAVA_SERVICE_MODULES[service_name]
    artifact_directory = _gradle_project_build_directory(module) / "libs"
    _validate_isolated_output_directory(artifact_directory, missing_ok=False)
    candidates = sorted(
        path
        for path in artifact_directory.glob("*.jar")
        if not path.name.endswith("-plain.jar")
    )
    if len(candidates) != 1:
        raise RuntimeError(
            f"Expected exactly one isolated executable bootJar for {service_name}, "
            f"found {len(candidates)} in {artifact_directory}."
        )
    return candidates[0]


def _runtime_image_envelope(
    service_name: str,
    fingerprint: dict[str, object],
    migration_digest: str,
) -> dict[str, object]:
    digest = str(fingerprint["sha256"])
    return {
        "version": RUNTIME_IMAGE_SCHEMA_VERSION,
        "service": service_name,
        "module": JAVA_SERVICE_MODULES[service_name],
        "path": _runtime_image_relative_path(service_name, digest),
        "sha256": digest,
        "size": int(fingerprint["size"]),
        "migrationDigest": migration_digest,
    }


def validate_published_runtime_image(
    service: Service, envelope: object
) -> Path:
    if not _valid_runtime_image_envelope(service.name, envelope):
        raise RuntimeError(f"Invalid runtime image metadata for {service.name}.")
    assert isinstance(envelope, dict)
    path = _runtime_image_path(envelope)
    for parent in (
        RUNTIME_ROOT,
        RUNTIME_ROOT / "images",
        RUNTIME_IMAGE_ROOT,
        path.parent,
    ):
        try:
            parent_metadata = parent.lstat()
        except OSError as error:
            raise RuntimeError(f"Runtime image directory is missing: {parent}") from error
        if (
            stat.S_ISLNK(parent_metadata.st_mode)
            or not stat.S_ISDIR(parent_metadata.st_mode)
            or parent_metadata.st_uid != os.getuid()
            or stat.S_IMODE(parent_metadata.st_mode) != RUNTIME_DIRECTORY_MODE
        ):
            raise RuntimeError(
                f"Runtime image directory must be owner-only and non-symlink: {parent}"
            )
    try:
        metadata = path.lstat()
    except OSError as error:
        raise RuntimeError(f"Runtime image is missing for {service.name}: {path}") from error
    if (
        stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.getuid()
        or stat.S_IMODE(metadata.st_mode) != RUNTIME_IMAGE_MODE
    ):
        raise RuntimeError(
            f"Runtime image must be an owner-only read-only regular file: {path}"
        )
    fingerprint, migration_digest = _boot_jar_metadata(path)
    if (
        fingerprint["sha256"] != envelope["sha256"]
        or fingerprint["size"] != envelope["size"]
        or migration_digest != envelope["migrationDigest"]
    ):
        raise RuntimeError(f"Runtime image integrity check failed for {service.name}.")
    return path


def _publish_runtime_image(
    service: Service,
    source: Path,
    fingerprint: dict[str, object],
    migration_digest: str,
) -> dict[str, object]:
    envelope = _runtime_image_envelope(service.name, fingerprint, migration_digest)
    _ensure_owner_directory(RUNTIME_IMAGE_ROOT)
    destination_directory = RUNTIME_IMAGE_ROOT / str(envelope["sha256"])
    _ensure_owner_directory(destination_directory)
    destination = _runtime_image_path(envelope)
    if destination.exists() or destination.is_symlink():
        validate_published_runtime_image(service, envelope)
        return envelope

    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{service.name}.", suffix=".jar.tmp", dir=destination_directory
    )
    temporary_path = Path(temporary_name)
    source_flags = os.O_RDONLY
    if hasattr(os, "O_CLOEXEC"):
        source_flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        source_flags |= os.O_NOFOLLOW
    source_descriptor = -1
    try:
        os.fchmod(descriptor, 0o600)
        source_descriptor = os.open(source, source_flags)
        before = os.fstat(source_descriptor)
        digest = hashlib.sha256()
        with os.fdopen(descriptor, "wb") as target:
            descriptor = -1
            while True:
                chunk = os.read(source_descriptor, ARTIFACT_FINGERPRINT_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())
        after = os.fstat(source_descriptor)
        final_source = source.lstat()
        if (
            _artifact_stat_signature(before) != _artifact_stat_signature(after)
            or _artifact_stat_signature(before) != _artifact_stat_signature(final_source)
            or digest.hexdigest() != envelope["sha256"]
        ):
            raise RuntimeError(
                f"Executable bootJar changed while publishing {service.name}."
            )
        copied_fingerprint, copied_migration_digest = _boot_jar_metadata(temporary_path)
        if (
            copied_fingerprint["sha256"] != envelope["sha256"]
            or copied_fingerprint["size"] != envelope["size"]
            or copied_migration_digest != migration_digest
        ):
            raise RuntimeError(f"Published runtime image changed for {service.name}.")
        os.chmod(temporary_path, RUNTIME_IMAGE_MODE)
        try:
            os.link(temporary_path, destination, follow_symlinks=False)
        except FileExistsError:
            validate_published_runtime_image(service, envelope)
        _fsync_directory(destination_directory)
    finally:
        if source_descriptor >= 0:
            os.close(source_descriptor)
        if descriptor >= 0:
            os.close(descriptor)
        temporary_path.unlink(missing_ok=True)
    validate_published_runtime_image(service, envelope)
    return envelope


def _runtime_source_files(services: Iterable[Service]) -> tuple[Path, ...]:
    module_names = set(WATCH_SHARED_MODULES)
    module_names.update(
        JAVA_SERVICE_MODULES[service.name]
        for service in services
        if service.name in JAVA_SERVICE_NAMES
    )
    files: set[Path] = set()

    def add_tree(root: Path) -> None:
        if not root.exists() and not root.is_symlink():
            return
        root_metadata = root.lstat()
        if stat.S_ISLNK(root_metadata.st_mode) or not stat.S_ISDIR(
            root_metadata.st_mode
        ):
            raise RuntimeError(
                f"Runtime build source tree must be a regular directory: {root}"
            )
        for directory, directory_names, file_names in os.walk(root):
            directory_path = Path(directory)
            metadata = directory_path.lstat()
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
                raise RuntimeError(
                    f"Runtime build source directory is not a regular directory: "
                    f"{directory_path}"
                )
            directory_names.sort()
            file_names.sort()
            for directory_name in directory_names:
                child_directory = directory_path / directory_name
                if stat.S_ISLNK(child_directory.lstat().st_mode):
                    raise RuntimeError(
                        f"Runtime build source must not contain a symlink directory: "
                        f"{child_directory}"
                    )
            for file_name in file_names:
                path = directory_path / file_name
                path_metadata = path.lstat()
                if stat.S_ISLNK(path_metadata.st_mode):
                    raise RuntimeError(
                        f"Runtime build source must not be a symlink: {path}"
                    )
                if stat.S_ISREG(path_metadata.st_mode):
                    files.add(path)

    for name in (
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
    ):
        candidate = BACKEND_ROOT / name
        if candidate.is_file():
            files.add(candidate)
    add_tree(BACKEND_ROOT / "gradle")
    build_src_root = BACKEND_ROOT / "buildSrc"
    for name in (
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
    ):
        candidate = build_src_root / name
        if candidate.is_file():
            files.add(candidate)
    add_tree(build_src_root / "src")
    for module in sorted(module_names):
        module_root = BACKEND_ROOT / module
        for build_name in ("build.gradle", "build.gradle.kts"):
            build_file = module_root / build_name
            if build_file.is_file():
                files.add(build_file)
        add_tree(module_root / "src")
    return tuple(sorted(files))


def capture_runtime_source_digest(services: Iterable[Service]) -> str:
    selected = list(services)
    digest = hashlib.sha256()
    for path in _runtime_source_files(selected):
        fingerprint = _stable_artifact_fingerprint("runtime-source", path)
        relative = path.relative_to(BACKEND_ROOT).as_posix().encode("utf-8")
        content_digest = str(fingerprint["sha256"]).encode("ascii")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(content_digest)
    return digest.hexdigest()


def stage_runtime_images(
    services: Iterable[Service],
) -> PreparedRuntimeImages:
    selected = [service for service in services if service.name in JAVA_SERVICE_NAMES]
    if not selected:
        return PreparedRuntimeImages({}, hashlib.sha256(b"").hexdigest())
    selected_by_name = {service.name: service for service in selected}
    ordered = [
        selected_by_name[name] for name in START_ORDER if name in selected_by_name
    ]
    with runtime_image_build_lock():
        _ensure_owner_directory(RUNTIME_IMAGE_BUILD_ROOT)
        tasks = [
            f":{JAVA_SERVICE_MODULES[service.name]}:bootJar" for service in ordered
        ]
        for attempt in range(ARTIFACT_SCAN_ATTEMPTS):
            source_before = capture_runtime_source_digest(ordered)
            _prepare_isolated_boot_jar_outputs(ordered)
            subprocess.run(
                (
                    "./gradlew",
                    "--no-daemon",
                    "--console=plain",
                    f"-PdwpDevRuntimeBuildRoot={RUNTIME_IMAGE_BUILD_ROOT}",
                    *tasks,
                ),
                cwd=BACKEND_ROOT,
                check=True,
            )

            candidates: dict[str, tuple[Path, dict[str, object], str]] = {}
            for service in ordered:
                source = _discover_built_boot_jar(service.name)
                fingerprint, migration_digest = _boot_jar_metadata(source)
                candidates[service.name] = (source, fingerprint, migration_digest)
            if source_before != capture_runtime_source_digest(ordered):
                if attempt + 1 < ARTIFACT_SCAN_ATTEMPTS:
                    continue
                raise RuntimeError(
                    "Runtime sources changed throughout the isolated image build; "
                    "no managed process was signalled."
                )

            images: dict[str, dict[str, object]] = {}
            for service in ordered:
                source, fingerprint, migration_digest = candidates[service.name]
                images[service.name] = _publish_runtime_image(
                    service, source, fingerprint, migration_digest
                )
            if source_before == capture_runtime_source_digest(ordered):
                return PreparedRuntimeImages(images, source_before)
            if attempt + 1 >= ARTIFACT_SCAN_ATTEMPTS:
                raise RuntimeError(
                    "Runtime sources changed while publishing immutable images; "
                    "no managed process was signalled."
                )
        raise RuntimeError("Runtime image build exceeded its bounded retry count.")


def runtime_image_launch_command(
    service: Service, envelope: dict[str, object]
) -> tuple[str, ...]:
    path = validate_published_runtime_image(service, envelope)
    return ("java", "-jar", str(path))


def capture_service_runtime_artifacts(
    service: Service,
    cache: dict[str, dict[str, object]] | None = None,
) -> dict[str, dict[str, object]]:
    cache = cache if cache is not None else {}
    fingerprints: dict[str, dict[str, object]] = {}
    for module in SERVICE_SHARED_RUNTIME_ARTIFACTS.get(service.name, ()):
        if module not in cache:
            path = _resolve_shared_runtime_artifact(module)
            cache[module] = _stable_artifact_fingerprint(module, path)
        fingerprints[module] = dict(cache[module])
    return fingerprints


ArtifactSnapshot = dict[str, Optional[dict[str, object]]]


def capture_shared_runtime_snapshot(services: Iterable[Service]) -> ArtifactSnapshot:
    modules = {
        module
        for service in services
        for module in SERVICE_SHARED_RUNTIME_ARTIFACTS.get(service.name, ())
    }
    snapshot: ArtifactSnapshot = {}
    for module in sorted(modules):
        try:
            path = _resolve_shared_runtime_artifact(module)
        except MissingRuntimeArtifactError:
            snapshot[module] = None
        else:
            snapshot[module] = _stable_artifact_fingerprint(module, path)
    return snapshot


def stable_artifact_candidate_scan(
    services: Iterable[Service],
    scan: Callable[[ArtifactSnapshot], list[tuple[Service, str]]],
) -> list[tuple[Service, str]]:
    selected = list(services)
    for attempt in range(ARTIFACT_SCAN_ATTEMPTS):
        before = capture_shared_runtime_snapshot(selected)
        candidates = scan(before)
        after = capture_shared_runtime_snapshot(selected)
        if before == after:
            return candidates
        if attempt + 1 < ARTIFACT_SCAN_ATTEMPTS:
            time.sleep(0.02)
    raise RuntimeError(
        "Shared runtime artifacts changed throughout candidate scanning; "
        "no managed process was signalled."
    )


def service_artifact_staleness(
    service: Service,
    process_state: dict[str, object],
    cache: ArtifactSnapshot | None = None,
) -> tuple[bool, str]:
    expected_modules = SERVICE_SHARED_RUNTIME_ARTIFACTS.get(service.name, ())
    if not expected_modules:
        return False, ""
    if cache is None:
        try:
            current = capture_service_runtime_artifacts(service)
        except MissingRuntimeArtifactError:
            return True, "shared runtime artifact missing"
    else:
        current: dict[str, dict[str, object]] = {}
        for module in expected_modules:
            if module not in cache:
                raise RuntimeError(
                    f"Shared runtime snapshot omitted required module {module}."
                )
            fingerprint = cache[module]
            if fingerprint is None:
                return True, "shared runtime artifact missing"
            current[module] = fingerprint
    recorded = process_state.get("runtimeArtifacts")
    if not isinstance(recorded, dict):
        return True, "untracked artifacts"
    if set(recorded) != set(expected_modules):
        return True, "artifact registry changed"
    for module in expected_modules:
        if recorded.get(module) != current[module]:
            return True, f"{module} changed"
    return False, ""


def service_runtime_staleness(
    service: Service,
    process_state: dict[str, object],
    cache: ArtifactSnapshot | None = None,
    desired_image: dict[str, object] | None = None,
) -> tuple[bool, str]:
    recorded_image = process_state.get("runtimeImage")
    if service.name in JAVA_SERVICE_NAMES and (
        desired_image is not None or recorded_image is not None
    ):
        if not isinstance(recorded_image, dict):
            return True, "legacy mutable runtime"
        try:
            image_path = validate_published_runtime_image(service, recorded_image)
        except RuntimeError as error:
            return True, str(error)
        expected_command = ["java", "-jar", str(image_path)]
        if process_state.get("command") != expected_command:
            return True, "runtime image launch command changed"
        if desired_image is not None and recorded_image != desired_image:
            return True, "runtime image generation changed"
        return False, ""
    return service_artifact_staleness(service, process_state, cache)


def load_agent_local_environment(path: Path | None = None) -> dict[str, str]:
    path = path or AGENT_LOCAL_ENV_FILE
    if not path.exists():
        return {}
    if not path.is_file():
        raise RuntimeError(f"Agent local environment is not a file: {path}")
    if path.stat().st_mode & 0o077:
        raise RuntimeError(
            f"Agent local environment must only be readable by its owner: {path}"
        )

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line.removeprefix("export ").lstrip()
        name, separator, raw_value = line.partition("=")
        name = name.strip()
        if (
            separator != "="
            or not ENVIRONMENT_NAME.fullmatch(name)
            or name not in AGENT_MODEL_ENVIRONMENT
            or name in values
        ):
            raise RuntimeError(
                f"Invalid Agent local environment entry at {path}:{line_number}"
            )
        try:
            parsed_value = shlex.split(raw_value, comments=True, posix=True)
        except ValueError as error:
            raise RuntimeError(
                f"Invalid Agent local environment value at {path}:{line_number}"
            ) from error
        if len(parsed_value) > 1:
            raise RuntimeError(
                f"Agent local environment values with spaces must be quoted: "
                f"{path}:{line_number}"
            )
        values[name] = parsed_value[0] if parsed_value else ""
    return values


def local_environment() -> dict[str, str]:
    environment = os.environ.copy()
    meeting_notification_token = environment.get(
        "DWP_MEETING_INVITATION_DELIVERY_TOKEN",
        "dwp-local-meeting-notification-token",
    )
    messaging_service_token = environment.get(
        "DWP_MESSAGING_SERVICE_TOKEN",
        "dwp-local-messaging-service-token",
    )
    messaging_work_source_token = environment.get(
        "DWP_WORK_MESSAGING_SOURCE_TOKEN",
        environment.get(
            "DWP_MESSAGING_WORK_SOURCE_TOKEN",
            "dwp-local-work-messaging-source-token",
        ),
    )
    defaults = {
        "DB_HOST": "localhost",
        "DB_PORT": "5432",
        "DB_NAME": "dwp_auth",
        "DB_USERNAME": "dwp_user",
        "DB_PASSWORD": "dwp_password",
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "4",
        "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "0",
        "DWP_NOTIFICATION_DB_POOL_SIZE": "4",
        "NOTIFICATION_DB_USERNAME": "dwp_notification_runtime",
        "NOTIFICATION_DB_PASSWORD": "dwp_notification_runtime_password",
        "NOTIFICATION_MIGRATION_DB_USERNAME": "dwp_user",
        "NOTIFICATION_MIGRATION_DB_PASSWORD": "dwp_password",
        "REDIS_HOST": "localhost",
        "REDIS_PORT": "6379",
        "REDIS_PASSWORD": "dwp_redis_password",
        "SERVICE_AUTH_URL": "http://localhost:8001",
        "SERVICE_GATEWAY_URL": "http://localhost:8080",
        "SERVICE_PLATFORM_URL": "http://localhost:8002",
        "SERVICE_PEOPLE_URL": "http://localhost:8003",
        "SERVICE_PROVIDER_URL": "http://localhost:8004",
        "SERVICE_APPROVAL_URL": "http://localhost:8005",
        "SERVICE_SPACE_URL": "http://localhost:8006",
        "SERVICE_MESSAGING_URL": "http://localhost:8007",
        "SERVICE_NOTIFICATION_URL": "http://localhost:8008",
        "SERVICE_MEETING_URL": "http://localhost:8009",
        "SERVICE_TIME_URL": "http://localhost:8011",
        "SERVICE_PAYROLL_URL": "http://localhost:8012",
        "DWP_PRODUCT_SURFACE_TOKEN": (
            "dwp-local-product-surface-token-change-outside-local"
        ),
        "DWP_MEETING_FOLLOWUP_AUTHORITY_PROVIDER": "auth-product-surface",
        "DWP_MEETING_FOLLOWUP_AUTHORITY_TOKEN": (
            "dwp-local-meeting-followup-authority-token-change-outside-local"
        ),
        "DWP_MEETING_FOLLOWUP_AUTHORITY_ALLOW_HTTP": "true",
        "DWP_MEETING_INVITATION_DELIVERY_ENABLED": "true",
        "DWP_MEETING_INVITATION_DELIVERY_BASE_URL": "http://localhost:8008",
        "DWP_MEETING_INVITATION_DELIVERY_TOKEN": meeting_notification_token,
        "DWP_MEETING_INVITATION_DELIVERY_ALLOW_HTTP": "true",
        "DWP_WORK_MEETING_SOURCE_BASE_URL": "http://localhost:8009",
        "DWP_WORK_MEETING_ASSERTION_KEY_ID": "local-work-meeting-v1",
        "DWP_WORK_MEETING_ASSERTION_SECRET_BASE64": (
            "ZHdwLWxvY2FsLXdvcmstbWVldGluZy1zZWNyZXQtdjEh"
        ),
        "DWP_WORK_MEETING_SOURCE_ALLOW_HTTP": "true",
        "DWP_WORK_MESSAGING_SOURCE_BASE_URL": "http://localhost:8007",
        "DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN": messaging_service_token,
        "DWP_WORK_MESSAGING_SOURCE_TOKEN": messaging_work_source_token,
        "DWP_WORK_MESSAGING_SOURCE_ALLOW_HTTP": "true",
        "DWP_MEETING_WORK_ASSERTION_KEY_ID": "local-work-meeting-v1",
        "DWP_MEETING_WORK_ASSERTION_SECRET_BASE64": (
            "ZHdwLWxvY2FsLXdvcmstbWVldGluZy1zZWNyZXQtdjEh"
        ),
        "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
        "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED": "true",
        "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_VERSION": "39",
        "DWP_AGENT_SERVICE_TOKEN": "dwp-local-agent-service-token",
        "DWP_AGENT_IDENTITY_SIGNING_SECRET": (
            "dwp-local-agent-identity-signing-secret-v1"
        ),
        "DWP_AGENT_IDENTITY_KEY_ID": "gateway-agent-v1",
        "DWP_AGENT_DATABASE_URL": (
            "postgresql://dwp_user:dwp_password@localhost:5432/dwp_agent"
        ),
        "DWP_AGENT_DATABASE_REQUIRED": "true",
        "DWP_AGENT_KEY_PROVIDER": "local-inline",
        "DWP_AGENT_DATA_KEY": (
            "ZHdwLWxvY2FsLWFnZW50LWRhdGEta2V5LTMyYnl0ZSE="
        ),
        "DWP_AGENT_DATA_KEY_VERSION": "local-v1",
        "DWP_AGENT_PRIVACY_HASH_SECRET": (
            "dwp-local-agent-privacy-secret-change-outside-local"
        ),
        "DWP_AGENT_SAFETY_SECRET": (
            "dwp-local-agent-safety-secret-change-outside-local"
        ),
        "DWP_AGENT_REGISTRY_MODE": "enforced",
        "DWP_AGENT_LOCAL_GOVERNANCE_SEED_ENABLED": "true",
        "DWP_AGENT_LOCAL_GOVERNANCE_TENANT_IDS": "1",
        "DWP_AGENT_LOCAL_ACTIVITY_SEED_ENABLED": "true",
        "DWP_PLATFORM_SERVICE_TOKEN": "dwp-local-platform-service-token",
        "DWP_PLATFORM_RUNTIME_SERVICE_TOKEN": "dwp-local-platform-runtime-token",
        "DWP_WIDGET_REGISTRY_PROVIDER_TOKEN": (
            "dwp-local-widget-registry-provider-token-change-outside-local"
        ),
        "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED": "true",
        "DWP_PLATFORM_PRODUCT_AUTHORIZATION_WORKPLACE_V4_ENABLED": "true",
        "DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED": "true",
        "DWP_PRODUCTIVITY_DATA_KEY": (
            "ZHdwLWxvY2FsLXByb2R1Y3Rpdml0eS1rZXktMzJieXQ="
        ),
        "DWP_PEOPLE_SERVICE_TOKEN": "dwp-local-people-service-token",
        "DWP_PEOPLE_CURSOR_SECRET": "dwp-local-people-cursor-secret-change-outside-local",
        "PEOPLE_DB_USERNAME": "dwp_people_runtime",
        "PEOPLE_DB_PASSWORD": "dwp_password",
        "DWP_PEOPLE_MIGRATION_PRINCIPAL_POLICY": "LOCAL_LEGACY",
        "DWP_HRIS_PERFORMANCE_WAVE1_ENABLED": "true",
        "DWP_HRIS_PERFORMANCE_COMPENSATION_PLANNING_ENABLED": "true",
        "DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED": "true",
        "DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED": "true",
        "DWP_PEOPLE_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        "DWP_PEOPLE_LOCAL_SEED_ENABLED": "true",
        "DWP_TIME_SERVICE_TOKEN": "dwp-local-time-service-token",
        "TIME_DB_NAME": "dwp_time",
        "TIME_DB_USERNAME": "dwp_time_runtime",
        "TIME_DB_PASSWORD": "dwp_time_runtime_password",
        "TIME_MIGRATION_DB_USERNAME": "dwp_time_migration",
        "TIME_MIGRATION_DB_PASSWORD": "dwp_time_migration_password",
        "TIME_PROJECTION_PUBLISHER_DB_USERNAME": (
            "dwp_time_projection_publisher"
        ),
        "TIME_PROJECTION_PUBLISHER_DB_PASSWORD": (
            "dwp_time_projection_publisher_password"
        ),
        "DWP_TIME_DB_POOL_SIZE": "3",
        "DWP_TIME_WORK_REGIME_API_ENABLED": "true",
        "DWP_HRIS_TIME_CLOCK_INGESTION_ENABLED": "true",
        "DWP_HRIS_TIME_CLOCK_INGESTION_CURSOR_SECRET": (
            "dwp-local-time-clock-ingestion-cursor-secret-change-outside-local"
        ),
        "DWP_HRIS_TIME_INTERPRETATION_ENABLED": "true",
        "DWP_HRIS_TIME_INTERPRETATION_CURSOR_SECRET": (
            "dwp-local-time-interpretation-cursor-secret-change-outside-local"
        ),
        "DWP_PAYROLL_SERVICE_TOKEN": "dwp-local-payroll-service-token",
        "PAYROLL_DB_NAME": "dwp_payroll",
        "PAYROLL_DB_USERNAME": "dwp_payroll_runtime",
        "PAYROLL_DB_PASSWORD": "dwp_payroll_runtime_password",
        "PAYROLL_MIGRATION_DB_USERNAME": "dwp_payroll_migration",
        "PAYROLL_MIGRATION_DB_PASSWORD": "dwp_payroll_migration_password",
        "PAYROLL_PROJECTION_PUBLISHER_DB_USERNAME": (
            "dwp_payroll_projection_publisher"
        ),
        "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD": (
            "dwp_payroll_projection_publisher_password"
        ),
        "DWP_PAYROLL_DB_POOL_SIZE": "3",
        "DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED": "true",
        "DWP_HRIS_PAYROLL_CONFIGURATION_WAVE2_ENABLED": "true",
        "DWP_PROVIDER_SERVICE_TOKEN": "dwp-local-provider-service-token",
        "DWP_PROVIDER_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        "DWP_APPROVAL_SERVICE_TOKEN": "dwp-local-approval-service-token",
        "DWP_APPROVAL_RUNTIME_SERVICE_TOKEN": "dwp-local-approval-runtime-token",
        "DWP_APPROVAL_PRODUCT_AUTHORIZATION_V2_ENABLED": "true",
        "DWP_APPROVAL_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        # Publish both signature controller sets in local and contract-export boots.
        # Provider credentials and the internal signing source remain unavailable, so
        # commands still fail closed until real runtime evidence is provisioned.
        "DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED": "true",
        "DWP_APPROVAL_INTERNAL_SIGNATURES_ENABLED": "true",
        "DWP_APPROVAL_INTERNAL_SIGNATURES_SOURCE_ENABLED": "false",
        "DWP_APPROVAL_INTEGRATION_RELAY_ENABLED": "true",
        "DWP_SPACE_SERVICE_TOKEN": "dwp-local-space-service-token",
        "DWP_SPACE_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        "DWP_MESSAGING_SERVICE_TOKEN": messaging_service_token,
        "DWP_MESSAGING_WORK_SOURCE_TOKEN": messaging_work_source_token,
        "DWP_MESSAGING_EVENT_TRANSPORT": "kafka",
        "DWP_MESSAGING_EVENT_TRANSPORT_ENABLED": "true",
        "DWP_MESSAGING_MEETING_PROVIDER": "livekit",
        "DWP_MESSAGING_MEETING_TOKEN_TTL": "PT5M",
        "LIVEKIT_API_URL": "http://localhost:7880",
        "LIVEKIT_URL": "ws://localhost:7880",
        "LIVEKIT_API_KEY": "devkey",
        "LIVEKIT_API_SECRET": "secret",
        "DWP_NOTIFICATION_SERVICE_TOKEN": "dwp-local-notification-service-token",
        "DWP_MEETING_SERVICE_TOKEN": "dwp-local-meeting-service-token",
        "DWP_MEETING_PROVIDER": "livekit",
        "DWP_MEETING_TOKEN_TTL": "PT5M",
        "DWP_NOTIFICATION_CURSOR_SECRET": (
            "dwp-local-notification-cursor-secret-change-outside-local"
        ),
        "DWP_NOTIFICATION_PRODUCER_TOKENS": (
            "dwp-approval-server=dwp-local-approval-notification-token,"
            "dwp-people-server=dwp-local-people-notification-token,"
            "dwp-platform-server=dwp-local-platform-notification-token,"
            "dwp-space-server=dwp-local-space-notification-token,"
            "dwp-messaging-server=dwp-local-messaging-notification-token,"
            f"dwp-meeting-server={meeting_notification_token}"
        ),
        "DWP_NOTIFICATION_ALLOWED_PRODUCERS": (
            "dwp-approval-server,dwp-people-server,dwp-platform-server,"
            "dwp-space-server,dwp-messaging-server,dwp-meeting-server"
        ),
        "DWP_NOTIFICATION_PRODUCER_APP_BINDINGS": (
            "dwp-approval-server=approvals,dwp-people-server=hcm|people,"
            "dwp-platform-server=platform|workplace,dwp-space-server=space,"
            "dwp-messaging-server=messaging,dwp-meeting-server=meetings"
        ),
        "DWP_NOTIFICATION_APPROVAL_PILOT_ENABLED": "true",
        "DWP_NOTIFICATION_DOMAIN_EVENTS_ENABLED": "true",
        "DWP_NOTIFICATION_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        "DWP_PROVIDER_SUPPORT_VALIDATION_TOKEN": (
            "dwp-local-provider-support-validation-token"
        ),
        "DWP_PROVIDER_SUPPORT_COOKIE_SECURE": "false",
        "DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED": "true",
        "DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED": "true",
        "DWP_PROVIDER_PROVISIONING_TOKEN": (
            "dwp-local-provider-provisioning-token-change-outside-local"
        ),
        "DWP_IDENTITY_SYNC_ENABLED": "true",
        "DWP_SYNTHETIC_IMPORT_ENABLED": "true",
        "DWP_IDENTITY_SYNC_TOKEN": (
            "dwp-local-identity-sync-token-change-outside-local"
        ),
        "DWP_API_HISTORY_COLLECTOR_URL": (
            "http://localhost:8002/internal/observability/api-history"
        ),
        "DWP_API_HISTORY_INGEST_TOKEN": (
            "dwp-local-api-history-ingest-token-change-outside-local"
        ),
        "DWP_API_HISTORY_PRIVACY_HASH_SECRET": (
            "dwp-local-api-history-privacy-secret-change-outside-local"
        ),
        "DWP_API_HISTORY_CURSOR_SECRET": (
            "dwp-local-api-history-cursor-secret-change-outside-local"
        ),
        "DWP_AUDIT_COLLECTOR_URL": "http://localhost:8002/internal/audit/events",
        "DWP_AUDIT_INGEST_TOKEN": (
            "dwp-local-audit-ingest-token-change-outside-local"
        ),
        "DWP_AUDIT_PRIVACY_HASH_SECRET": (
            "dwp-local-audit-privacy-secret-change-outside-local"
        ),
        "DWP_AUDIT_INTEGRITY_SECRET": (
            "dwp-local-audit-integrity-secret-change-outside-local"
        ),
        "DWP_ENVIRONMENT": "local",
        "DWP_OPENAPI_ENABLED": "true",
        "DWP_HOME_MODE_V4_ACTIVATION_ENABLED": "true",
        "DWP_HOME_FLOW_ENABLED": "true",
        "DWP_HOME_MZ_ENABLED": "true",
        "DWP_HOME_PERSONALIZATION_V2_ENABLED": "true",
        "DWP_HOME_COMPOSER_ENABLED": "true",
        "DWP_HOME_VIEWS_DUAL_WRITE_ENABLED": "true",
        "DWP_HOME_VIEWS_SHADOW_COMPARE_ENABLED": "true",
        "DWP_HOME_VIEWS_READ_ENABLED": "true",
        "VITE_API_URL": "http://localhost:8080",
        "VITE_HOME_PERSONALIZATION_V2_ENABLED": "true",
    }
    for key, value in defaults.items():
        environment.setdefault(key, value)
    return environment


def service_environment(service_name: str) -> dict[str, str]:
    environment = local_environment()
    if service_name == "people":
        environment.setdefault("DWP_SERVICE_INSTANCE", "local")
    if service_name == "frontend":
        environment.setdefault("NODE_OPTIONS", DEFAULT_FRONTEND_NODE_OPTIONS)
    approval_runtime_defaults = _local_approval_runtime_defaults(service_name)
    for key in tuple(environment):
        if (
            key not in approval_runtime_defaults
            and key.startswith(LOCAL_APPROVAL_RUNTIME_PREFIXES)
        ):
            environment.pop(key, None)
    for key, value in approval_runtime_defaults.items():
        environment.setdefault(key, value)
    if service_name == "agent":
        for key, value in load_agent_local_environment().items():
            if key not in os.environ:
                environment[key] = value
    else:
        for key in AGENT_MODEL_ENVIRONMENT:
            environment.pop(key, None)
    for owner, shared_keys in HRIS_SHARED_ENVIRONMENT.items():
        if service_name not in {"gateway", owner}:
            for key in shared_keys:
                environment.pop(key, None)
    for owner, owner_keys in HRIS_OWNER_ENVIRONMENT.items():
        if service_name != owner:
            for key in owner_keys:
                environment.pop(key, None)
        for key in HRIS_RECEIPT_ENVIRONMENT[owner]:
            environment.pop(key, None)
        for key in HRIS_DARK_FEATURE_ENVIRONMENT[owner]:
            environment.pop(key, None)
    if service_name in HRIS_CONTROLLED_DATABASES:
        environment.update(migration_control_application_environment(service_name))
    if service_name not in {"auth", "gateway"}:
        environment.pop("DWP_PRODUCT_SURFACE_TOKEN", None)
    if service_name not in {"auth", "meeting"}:
        environment.pop("DWP_MEETING_FOLLOWUP_AUTHORITY_TOKEN", None)
    if service_name != "meeting":
        environment.pop("DWP_MEETING_FOLLOWUP_AUTHORITY_PROVIDER", None)
        environment.pop("DWP_MEETING_FOLLOWUP_AUTHORITY_ALLOW_HTTP", None)
        environment.pop("DWP_MEETING_INVITATION_DELIVERY_ENABLED", None)
        environment.pop("DWP_MEETING_INVITATION_DELIVERY_BASE_URL", None)
        environment.pop("DWP_MEETING_INVITATION_DELIVERY_TOKEN", None)
        environment.pop("DWP_MEETING_INVITATION_DELIVERY_ALLOW_HTTP", None)
    if service_name != "platform":
        environment.pop("DWP_WORK_MEETING_SOURCE_BASE_URL", None)
        environment.pop("DWP_WORK_MEETING_ASSERTION_KEY_ID", None)
        environment.pop("DWP_WORK_MEETING_ASSERTION_SECRET_BASE64", None)
        environment.pop("DWP_WORK_MEETING_SOURCE_ALLOW_HTTP", None)
        environment.pop("DWP_WORK_MESSAGING_SOURCE_BASE_URL", None)
        environment.pop("DWP_WORK_MESSAGING_SOURCE_SERVICE_TOKEN", None)
        environment.pop("DWP_WORK_MESSAGING_SOURCE_TOKEN", None)
        environment.pop("DWP_WORK_MESSAGING_SOURCE_ALLOW_HTTP", None)
    if service_name != "meeting":
        environment.pop("DWP_MEETING_WORK_ASSERTION_KEY_ID", None)
        environment.pop("DWP_MEETING_WORK_ASSERTION_SECRET_BASE64", None)
    if service_name != "auth":
        environment.pop("DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED", None)
        environment.pop(
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED", None
        )
        environment.pop(
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_VERSION", None
        )
    if service_name not in {"agent", "gateway"}:
        environment.pop("DWP_AGENT_SERVICE_TOKEN", None)
        environment.pop("DWP_AGENT_IDENTITY_SIGNING_SECRET", None)
        environment.pop("DWP_AGENT_IDENTITY_KEY_ID", None)
    if service_name == "agent":
        environment.pop("DWP_PLATFORM_SERVICE_TOKEN", None)
    elif service_name == "gateway":
        environment.pop("DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", None)
    elif service_name == "people":
        environment.pop("DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", None)
    elif service_name not in {"platform"}:
        environment.pop("DWP_PLATFORM_SERVICE_TOKEN", None)
        environment.pop("DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", None)
    if service_name not in {"platform", "provider"}:
        environment.pop("DWP_WIDGET_REGISTRY_PROVIDER_TOKEN", None)
    if service_name != "platform":
        environment.pop(
            "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED", None
        )
        environment.pop(
            "DWP_PLATFORM_PRODUCT_AUTHORIZATION_WORKPLACE_V4_ENABLED", None
        )
        environment.pop("DWP_ACTIVITY_LOCAL_FIXTURES_ENABLED", None)
    if service_name != "agent":
        environment.pop("DWP_AGENT_DATABASE_URL", None)
        environment.pop("DWP_AGENT_DATABASE_REQUIRED", None)
        environment.pop("DWP_AGENT_KEY_PROVIDER", None)
        environment.pop("DWP_AGENT_DATA_KEY", None)
        environment.pop("DWP_AGENT_DATA_KEY_VERSION", None)
        environment.pop("DWP_AGENT_PRIVACY_HASH_SECRET", None)
        environment.pop("DWP_AGENT_SAFETY_SECRET", None)
        environment.pop("DWP_AGENT_REGISTRY_MODE", None)
        environment.pop("DWP_AGENT_LOCAL_GOVERNANCE_SEED_ENABLED", None)
        environment.pop("DWP_AGENT_LOCAL_GOVERNANCE_TENANT_IDS", None)
        environment.pop("DWP_AGENT_LOCAL_ACTIVITY_SEED_ENABLED", None)
    if service_name != "platform":
        environment.pop("DWP_PRODUCTIVITY_DATA_KEY", None)
    if service_name not in {"gateway", "people"}:
        environment.pop("DWP_PEOPLE_SERVICE_TOKEN", None)
    if service_name != "people":
        environment.pop("DWP_PEOPLE_CURSOR_SECRET", None)
        environment.pop("PEOPLE_DB_USERNAME", None)
        environment.pop("PEOPLE_DB_PASSWORD", None)
        environment.pop("DWP_PEOPLE_MIGRATION_PRINCIPAL_POLICY", None)
        environment.pop("DWP_HRIS_PERFORMANCE_WAVE1_ENABLED", None)
        environment.pop(
            "DWP_HRIS_PERFORMANCE_COMPENSATION_PLANNING_ENABLED", None
        )
        environment.pop("DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED", None)
        environment.pop("DWP_PEOPLE_FLYWAY_LOCATIONS", None)
        environment.pop("DWP_PEOPLE_LOCAL_SEED_ENABLED", None)
        environment.pop("DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED", None)
    if service_name not in {"gateway", "provider"}:
        environment.pop("DWP_PROVIDER_SERVICE_TOKEN", None)
        environment.pop("DWP_PROVIDER_SUPPORT_VALIDATION_TOKEN", None)
    if service_name != "provider":
        environment.pop("DWP_PROVIDER_FLYWAY_LOCATIONS", None)
    if service_name not in {"gateway", "approval"}:
        environment.pop("DWP_APPROVAL_SERVICE_TOKEN", None)
    if service_name not in {"agent", "approval"}:
        environment.pop("DWP_APPROVAL_RUNTIME_SERVICE_TOKEN", None)
    if service_name != "approval":
        environment.pop("DWP_APPROVAL_FLYWAY_LOCATIONS", None)
        environment.pop("DWP_APPROVAL_PRODUCT_AUTHORIZATION_V2_ENABLED", None)
        environment.pop("DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED", None)
        environment.pop("DWP_APPROVAL_INTERNAL_SIGNATURES_ENABLED", None)
        environment.pop("DWP_APPROVAL_INTERNAL_SIGNATURES_SOURCE_ENABLED", None)
        environment.pop("DWP_APPROVAL_INTEGRATION_RELAY_ENABLED", None)
    if service_name != "provider":
        environment.pop("DWP_PROVIDER_SUPPORT_COOKIE_SECURE", None)
        environment.pop("DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED", None)
        environment.pop("DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED", None)
    if service_name not in {"auth", "platform", "people", "provider"}:
        environment.pop("DWP_PROVIDER_PROVISIONING_TOKEN", None)
    if service_name not in {
        "auth",
        "platform",
        "people",
        "space",
        "approval",
        "notification",
    }:
        environment.pop("DWP_IDENTITY_SYNC_TOKEN", None)
    if service_name not in {"gateway", "messaging"}:
        environment.pop("DWP_MESSAGING_SERVICE_TOKEN", None)
    if service_name != "messaging":
        environment.pop("DWP_MESSAGING_WORK_SOURCE_TOKEN", None)
        environment.pop("DWP_MESSAGING_EVENT_TRANSPORT", None)
        environment.pop("DWP_MESSAGING_EVENT_TRANSPORT_ENABLED", None)
        environment.pop("DWP_MESSAGING_MEETING_PROVIDER", None)
        environment.pop("DWP_MESSAGING_MEETING_TOKEN_TTL", None)
    if service_name not in {"messaging", "meeting"}:
        environment.pop("LIVEKIT_API_URL", None)
        environment.pop("LIVEKIT_URL", None)
        environment.pop("LIVEKIT_API_KEY", None)
        environment.pop("LIVEKIT_API_SECRET", None)
    if service_name not in {"gateway", "notification"}:
        environment.pop("DWP_NOTIFICATION_SERVICE_TOKEN", None)
    if service_name != "notification":
        environment.pop("DWP_NOTIFICATION_FLYWAY_LOCATIONS", None)
        environment.pop("DWP_NOTIFICATION_CURSOR_SECRET", None)
        environment.pop("DWP_NOTIFICATION_PRODUCER_TOKENS", None)
        environment.pop("DWP_NOTIFICATION_ALLOWED_PRODUCERS", None)
        environment.pop("DWP_NOTIFICATION_PRODUCER_APP_BINDINGS", None)
        environment.pop("DWP_NOTIFICATION_APPROVAL_PILOT_ENABLED", None)
        environment.pop("DWP_NOTIFICATION_DOMAIN_EVENTS_ENABLED", None)
        environment.pop("NOTIFICATION_DB_USERNAME", None)
        environment.pop("NOTIFICATION_DB_PASSWORD", None)
        environment.pop("NOTIFICATION_MIGRATION_DB_USERNAME", None)
        environment.pop("NOTIFICATION_MIGRATION_DB_PASSWORD", None)
    if service_name not in {"gateway", "meeting"}:
        environment.pop("DWP_MEETING_SERVICE_TOKEN", None)
    if service_name != "meeting":
        environment.pop("DWP_MEETING_PROVIDER", None)
        environment.pop("DWP_MEETING_TOKEN_TTL", None)
    if service_name != "people":
        environment.pop("DWP_IDENTITY_SYNC_ENABLED", None)
    return environment


def migration_control_file(service_name: str) -> Path:
    if service_name not in HRIS_CONTROLLED_DATABASES:
        raise RuntimeError(f"Unsupported HRIS Migration Control service: {service_name}")
    return MIGRATION_CONTROL_RECEIPT_ROOT / f"{service_name}.json"


def _read_private_json(path: Path, *, description: str) -> object:
    try:
        metadata = path.lstat()
    except FileNotFoundError as error:
        raise RuntimeError(f"{description} is missing: {path}") from error
    if (
        stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.getuid()
        or metadata.st_mode & 0o077
    ):
        raise RuntimeError(f"{description} must be an owner-only 0600 regular file: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_strict_json_object)
    except (json.JSONDecodeError, OSError) as error:
        raise RuntimeError(f"{description} is unreadable: {path}") from error


def _write_private_json(path: Path, value: object) -> None:
    parent = path.parent
    if parent.exists() or parent.is_symlink():
        metadata = parent.lstat()
        if (
            stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or metadata.st_mode & 0o077
        ):
            raise RuntimeError(
                f"Migration Control receipt directory must be owner-only: {parent}"
            )
    else:
        parent.mkdir(parents=True, mode=0o700)
    if path.exists() or path.is_symlink():
        _read_private_json(path, description="Migration Control predecessor receipt")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=parent
    )
    temporary_path = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(value, target, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary_path, path)
    finally:
        try:
            os.close(descriptor)
        except OSError:
            pass
        temporary_path.unlink(missing_ok=True)


def _canonical_control_field(digest: object, value: object) -> None:
    canonical = "true" if value is True else "false" if value is False else str(value)
    encoded = canonical.encode("utf-8")
    digest.update(str(len(encoded)).encode("ascii"))
    digest.update(b":")
    digest.update(encoded)


def control_run_receipt_sha256(receipt: dict[str, object]) -> str:
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
        _canonical_control_field(digest, value)
    streams = receipt["streams"]
    if not isinstance(streams, list):
        raise RuntimeError("Migration Control receipt streams must be a list.")
    for stream in streams:
        for field in (
            "streamKey",
            "historyMaxInstalledRank",
            "historyRowCount",
            "historySha256",
            "inventoryObjectCount",
            "inventorySha256",
            "adoptionReceiptSha256",
        ):
            _canonical_control_field(digest, stream[field])
    return digest.hexdigest()


def migration_control_attestation_files() -> tuple[Path, ...]:
    files = {
        Path(__file__).resolve(),
        BACKEND_ROOT / "build.gradle",
        BACKEND_ROOT / "settings.gradle",
        BACKEND_ROOT / "gradlew",
        BACKEND_ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar",
        BACKEND_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties",
        BACKEND_ROOT / "gradle" / "verification-metadata.xml",
        BACKEND_ROOT / "dwp-core" / "build.gradle",
        BACKEND_ROOT / "dwp-migration-control" / "build.gradle",
        BACKEND_ROOT / "dwp-migration-control" / "README.md",
    }
    for directory in (
        BACKEND_ROOT / "dwp-migration-control" / "src" / "main" / "java",
        BACKEND_ROOT / "dwp-core" / "src" / "main" / "java",
        BACKEND_ROOT / "dwp-core" / "src" / "main" / "resources",
    ):
        files.update(path for path in directory.rglob("*") if path.is_file())
    for control in HRIS_CONTROLLED_DATABASES.values():
        module = BACKEND_ROOT / control.module
        files.add(module / "build.gradle")
        files.add(module / "src" / "main" / "resources" / "application.yml")
        files.update(
            path
            for path in (module / "src" / "main" / "resources").rglob("*.sql")
            if path.is_file()
        )
        guards = tuple(
            path
            for path in (module / "src" / "main" / "java").rglob(
                "*DatabaseMigrationConfiguration.java"
            )
            if path.is_file()
        )
        if not guards:
            raise RuntimeError(
                f"Migration Control application guard is missing: {control.module}"
            )
        files.update(guards)
    ordered = tuple(sorted(files, key=lambda path: path.relative_to(BACKEND_ROOT).as_posix()))
    for path in ordered:
        if path.is_symlink() or not path.is_file():
            raise RuntimeError(f"Migration Control attestation input is not regular: {path}")
    return ordered


def migration_control_reference() -> str:
    digest = hashlib.sha256()
    _canonical_control_field(digest, "dwp-migration-control-attestation-v2")
    for path in migration_control_attestation_files():
        _canonical_control_field(digest, path.relative_to(BACKEND_ROOT).as_posix())
        _canonical_control_field(digest, path.read_bytes().hex())
    return "dwp-migration-control-v2:" + digest.hexdigest()


def validate_control_run_receipt(
    service_name: str,
    receipt: object,
    *,
    require_current_reference: bool = True,
    current_reference: str | None = None,
) -> dict[str, object]:
    control = HRIS_CONTROLLED_DATABASES.get(service_name)
    if control is None:
        raise RuntimeError(f"Unsupported HRIS Migration Control service: {service_name}")
    top_fields = {
        "schemaVersion",
        "mode",
        "service",
        "database",
        "migrationPrincipal",
        "controlReference",
        "previousRunReceiptSha256",
        "postgresVersion",
        "temporaryPrivilegeRevoked",
        "streams",
        "receiptSha256",
    }
    stream_fields = {
        "streamKey",
        "historyMaxInstalledRank",
        "historyRowCount",
        "historySha256",
        "inventoryObjectCount",
        "inventorySha256",
        "adoptionReceiptSha256",
    }
    if not isinstance(receipt, dict) or set(receipt) != top_fields:
        raise RuntimeError("Migration Control run receipt shape is invalid.")
    reference = str(receipt.get("controlReference", ""))
    if (
        receipt.get("schemaVersion") != "2.0"
        or receipt.get("mode") != "NATIVE_FRESH"
        or receipt.get("service") != service_name
        or receipt.get("database") != control.database
        or receipt.get("migrationPrincipal") != control.migration_role
        or re.fullmatch(r"dwp-migration-control-v2:[0-9a-f]{64}", reference) is None
        or (
            require_current_reference
            and reference != (current_reference or migration_control_reference())
        )
        or re.fullmatch(
            r"[0-9]+(?:\.[0-9]+){1,2}", str(receipt.get("postgresVersion", ""))
        ) is None
        or receipt.get("temporaryPrivilegeRevoked") is not True
        or re.fullmatch(r"[0-9a-f]{64}", str(receipt.get("receiptSha256", ""))) is None
        or (
            receipt.get("previousRunReceiptSha256") != ""
            and re.fullmatch(
                r"[0-9a-f]{64}",
                str(receipt.get("previousRunReceiptSha256", "")),
            ) is None
        )
    ):
        raise RuntimeError("Migration Control run receipt identity is invalid.")
    streams = receipt.get("streams")
    if not isinstance(streams, list) or len(streams) != 1:
        raise RuntimeError("Migration Control run receipt stream is incomplete.")
    stream = streams[0]
    if (
        not isinstance(stream, dict)
        or set(stream) != stream_fields
        or stream.get("streamKey") != control.stream_key
        or type(stream.get("historyMaxInstalledRank")) is not int
        or stream["historyMaxInstalledRank"] < 0
        or type(stream.get("historyRowCount")) is not int
        or stream["historyRowCount"] < 0
        or type(stream.get("inventoryObjectCount")) is not int
        or stream["inventoryObjectCount"] < 1
        or re.fullmatch(r"[0-9a-f]{64}", str(stream.get("historySha256", ""))) is None
        or re.fullmatch(r"[0-9a-f]{64}", str(stream.get("inventorySha256", ""))) is None
        or stream.get("adoptionReceiptSha256") != ""
    ):
        raise RuntimeError("Migration Control stream receipt is invalid.")
    if receipt["receiptSha256"] != control_run_receipt_sha256(receipt):
        raise RuntimeError("Migration Control run receipt digest is invalid.")
    return receipt


def load_migration_control_receipt(service_name: str) -> dict[str, object]:
    receipt = _read_private_json(
        migration_control_file(service_name),
        description=f"Migration Control receipt for {service_name}",
    )
    return validate_control_run_receipt(
        service_name, receipt, require_current_reference=False
    )


def save_migration_control_receipt(
    service_name: str,
    receipt: dict[str, object],
    *,
    current_reference: str,
) -> None:
    validated = validate_control_run_receipt(
        service_name,
        receipt,
        current_reference=current_reference,
    )
    path = migration_control_file(service_name)
    predecessor = ""
    if path.exists() or path.is_symlink():
        predecessor = str(load_migration_control_receipt(service_name)["receiptSha256"])
    if validated["previousRunReceiptSha256"] != predecessor:
        raise RuntimeError(
            "Migration Control run receipt predecessor does not match external state."
        )
    _write_private_json(path, validated)


def migration_control_application_environment(service_name: str) -> dict[str, str]:
    path = migration_control_file(service_name)
    if not path.exists() and not path.is_symlink():
        return {}
    receipt = validate_control_run_receipt(service_name, load_migration_control_receipt(service_name))
    prefix = f"DWP_{service_name.upper()}_MIGRATION_CONTROL"
    return {
        f"{prefix}_RUN_RECEIPT_JSON": json.dumps(
            receipt, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ),
        f"{prefix}_RUN_RECEIPT_SHA256": str(receipt["receiptSha256"]),
        f"{prefix}_REFERENCE": str(receipt["controlReference"]),
    }


def docker_compose(
    *arguments: str,
    check: bool = True,
    capture_output: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ("docker", "compose", *arguments),
        cwd=BACKEND_ROOT,
        check=check,
        text=True,
        capture_output=capture_output,
    )


def require_command(command: str) -> None:
    if shutil.which(command) is None:
        raise RuntimeError(f"Required command is not available: {command}")


def doctor(required_services: Iterable[Service] | None = None) -> None:
    selected = (
        set(SERVICES)
        if required_services is None
        else {service.name for service in required_services}
    )
    commands = {"docker", "python3"}
    if selected.intersection({"auth", "platform", "people", "provider", "approval", "space", "messaging", "notification", "meeting", "time", "payroll", "gateway"}):
        commands.add("java")
    if "frontend" in selected:
        commands.add("corepack")
    for command in sorted(commands):
        require_command(command)

    projects = [BACKEND_ROOT]
    if "frontend" in selected:
        projects.append(FRONTEND_ROOT)
    if "agent" in selected:
        projects.append(AGENT_ROOT)
    for project in projects:
        if not project.exists():
            raise RuntimeError(f"Missing project: {project}")

    if "agent" in selected:
        version_check = subprocess.run(
            (
                agent_python(),
                "-c",
                "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)",
            ),
            check=False,
        )
        if version_check.returncode != 0:
            raise RuntimeError("The agent runtime requires Python 3.11 or newer.")

        subprocess.run(
            (agent_python(), "-c", "import cryptography, fastapi, psycopg, uvicorn"),
            cwd=AGENT_ROOT,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    if "frontend" in selected:
        node_version = frontend_node_version()
        if not (
            node_version >= MIN_FRONTEND_NODE
            and node_version[0] < MAX_FRONTEND_NODE_MAJOR
        ):
            current = ".".join(str(part) for part in node_version)
            required = ".".join(str(part) for part in MIN_FRONTEND_NODE)
            raise RuntimeError(
                f"The frontend runtime requires Node.js {required} or newer and "
                f"older than {MAX_FRONTEND_NODE_MAJOR}; found {current}. "
                "Activate .node-version or set DWP_NODE_BIN."
            )
        subprocess.run(
            frontend_corepack_command("yarn", "--version"),
            cwd=FRONTEND_ROOT,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    subprocess.run(
        ("docker", "info"),
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    print("Development environment is ready.")


def start_infrastructure(
    include_events: bool = False,
    include_media: bool = False,
    include_people: bool = False,
    include_time: bool = False,
    include_payroll: bool = False,
) -> None:
    profile_arguments: list[str] = []
    services = ["postgres", "redis"]
    if include_events:
        profile_arguments.extend(("--profile", "events"))
        services.extend(("kafka", "kafka-init"))
    if include_media:
        profile_arguments.extend(("--profile", "media"))
        services.append("livekit")
    docker_compose(*profile_arguments, "up", "-d", *services)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        postgres = docker_compose(
            "exec",
            "-T",
            "postgres",
            "pg_isready",
            "-U",
            "dwp_user",
            "-d",
            "dwp_auth",
            check=False,
            capture_output=True,
        )
        redis = docker_compose(
            "exec",
            "-T",
            "redis",
            "redis-cli",
            "-a",
            "dwp_redis_password",
            "ping",
            check=False,
            capture_output=True,
        )
        if postgres.returncode == 0 and redis.stdout.strip() == "PONG":
            ensure_database("dwp_platform")
            ensure_database("dwp_people")
            ensure_database("dwp_provider")
            ensure_database("dwp_approval")
            ensure_database("dwp_space")
            ensure_database("dwp_messaging")
            ensure_database("dwp_notification")
            ensure_database("dwp_meetings")
            ensure_notification_runtime_role()
            if include_people:
                ensure_people_local_legacy_boundary()
            ensure_database("dwp_agent")
            if include_time:
                ensure_hris_controlled_database("time")
            if include_payroll:
                ensure_hris_controlled_database("payroll")
            print("postgres   ready at localhost:5432")
            print("redis      ready at localhost:6379")
            if include_events:
                kafka_deadline = time.monotonic() + 90
                while time.monotonic() < kafka_deadline:
                    kafka = docker_compose(
                        "--profile", "events", "ps", "--status", "running", "kafka",
                        check=False,
                        capture_output=True,
                    )
                    init = docker_compose(
                        "--profile", "events", "ps", "-a", "kafka-init",
                        check=False,
                        capture_output=True,
                    )
                    if "dwp-kafka" in kafka.stdout and "Exited (0)" in init.stdout:
                        print("kafka      ready at localhost:9092")
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError("Kafka topics did not become ready within 90 seconds.")
            if include_media:
                media_deadline = time.monotonic() + 60
                while time.monotonic() < media_deadline:
                    if port_open(7880):
                        print("livekit    ready at ws://localhost:7880")
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError("LiveKit did not become ready within 60 seconds.")
            return
        time.sleep(1)
    raise RuntimeError("PostgreSQL and Redis did not become ready within 60 seconds.")


def database_exists(database_name: str) -> bool:
    if re.fullmatch(r"[a-z][a-z0-9_]{1,62}", database_name) is None:
        raise RuntimeError(f"Invalid database name: {database_name}")
    result = docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-U",
        "dwp_user",
        "-d",
        "postgres",
        "-tAc",
        f"SELECT 1 FROM pg_database WHERE datname = '{database_name}'",
        capture_output=True,
    )
    answer = result.stdout.strip()
    if answer not in {"", "1"}:
        raise RuntimeError(f"Database existence probe was not canonical: {database_name}")
    return answer == "1"


def ensure_database(database_name: str) -> bool:
    if database_exists(database_name):
        return False
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "createdb",
        "-U",
        "dwp_user",
        database_name,
    )
    return True


def discard_fresh_database(database_name: str) -> None:
    """Remove only a database created by the current failed bootstrap attempt."""
    if re.fullmatch(r"[a-z][a-z0-9_]{1,62}", database_name) is None:
        raise RuntimeError(f"Invalid database name: {database_name}")
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "dropdb",
        "--force",
        "--if-exists",
        "-U",
        "dwp_user",
        database_name,
    )


def ensure_hris_strict_database_boundary(service_name: str) -> None:
    """Provision a pristine TIM/PAY database for the official Control runner."""
    control = HRIS_CONTROLLED_DATABASES.get(service_name)
    if control is None:
        raise RuntimeError(f"Unsupported HRIS Migration Control service: {service_name}")
    environment = local_environment()
    role_passwords = {
        control.runtime_role: environment[control.runtime_password_key],
        control.migration_role: environment[control.migration_password_key],
        control.publisher_role: environment[control.publisher_password_key],
    }
    if any(
        re.fullmatch(r"[A-Za-z0-9_-]{16,128}", password) is None
        for password in role_passwords.values()
    ):
        raise RuntimeError("HRIS strict development role password is invalid.")

    role_ddl = "\n".join(
        f"""
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{role}') THEN
        CREATE ROLE {role} LOGIN PASSWORD '{password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE {role} LOGIN PASSWORD '{password}'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    ALTER ROLE {role} RESET ALL;
    FOR database_record IN SELECT datname FROM pg_database WHERE datallowconn LOOP
        EXECUTE format('ALTER ROLE %I IN DATABASE %I RESET ALL',
            '{role}', database_record.datname);
        EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM %I',
            database_record.datname, '{role}');
    END LOOP;
    FOR related_role IN
        SELECT parent_role.rolname
          FROM pg_auth_members membership
          JOIN pg_roles parent_role ON parent_role.oid=membership.roleid
          JOIN pg_roles member_role ON member_role.oid=membership.member
         WHERE member_role.rolname='{role}'
    LOOP
        EXECUTE format('REVOKE %I FROM %I', related_role, '{role}');
    END LOOP;
    FOR related_role IN
        SELECT member_role.rolname
          FROM pg_auth_members membership
          JOIN pg_roles parent_role ON parent_role.oid=membership.roleid
          JOIN pg_roles member_role ON member_role.oid=membership.member
         WHERE parent_role.rolname='{role}'
    LOOP
        EXECUTE format('REVOKE %I FROM %I', '{role}', related_role);
    END LOOP;
"""
        for role, password in role_passwords.items()
    )
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "dwp_user",
        "-d",
        "postgres",
        "-c",
        f"""
DO $roles$
DECLARE
    database_record RECORD;
    related_role TEXT;
BEGIN
    FOR database_record IN SELECT datname FROM pg_database WHERE datallowconn LOOP
        EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC',
            database_record.datname);
    END LOOP;
{role_ddl}
END
$roles$;
REVOKE ALL PRIVILEGES ON PARAMETER session_replication_role
    FROM PUBLIC, {control.runtime_role}, {control.migration_role}, {control.publisher_role};
REVOKE CONNECT, TEMPORARY, CREATE ON DATABASE {control.database} FROM PUBLIC;
REVOKE TEMPORARY, CREATE ON DATABASE {control.database}
    FROM {control.runtime_role}, {control.migration_role}, {control.publisher_role};
GRANT CONNECT ON DATABASE {control.database}
    TO {control.runtime_role}, {control.migration_role}, {control.publisher_role};
ALTER ROLE {control.runtime_role} IN DATABASE {control.database}
    SET search_path TO pg_catalog, public;
ALTER ROLE {control.migration_role} IN DATABASE {control.database}
    SET search_path TO pg_catalog, public;
ALTER ROLE {control.publisher_role} IN DATABASE {control.database}
    SET search_path TO pg_catalog, public;
""",
    )
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "dwp_user",
        "-d",
        control.database,
        "-c",
        f"""
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION {control.migration_role};
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO {control.migration_role};
GRANT USAGE ON SCHEMA public TO {control.runtime_role}, {control.publisher_role};
CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7';
DO $extension_inventory$
BEGIN
    IF (
        SELECT COUNT(*)
          FROM pg_extension extension
          JOIN pg_namespace namespace ON namespace.oid=extension.extnamespace
         WHERE extension.extname='btree_gist'
           AND extension.extversion='1.7'
           AND namespace.nspname='public'
           AND pg_get_userbyid(extension.extowner)='dwp_user'
    ) <> 1 OR EXISTS (
        SELECT 1 FROM pg_extension WHERE extname NOT IN ('plpgsql', 'btree_gist')
    ) THEN
        RAISE EXCEPTION 'HRIS strict extension inventory is not exact';
    END IF;
END
$extension_inventory$;
""",
    )


def run_hris_migration_control(
    service_name: str,
    previous: dict[str, object] | None,
    *,
    current_reference: str,
) -> dict[str, object]:
    control = HRIS_CONTROLLED_DATABASES.get(service_name)
    if control is None:
        raise RuntimeError(f"Unsupported HRIS Migration Control service: {service_name}")
    values = local_environment()
    expected_identities = {
        f"{service_name.upper()}_DB_USERNAME": control.runtime_role,
        f"{service_name.upper()}_MIGRATION_DB_USERNAME": control.migration_role,
        f"{service_name.upper()}_PROJECTION_PUBLISHER_DB_USERNAME": (
            control.publisher_role
        ),
    }
    for key, expected in expected_identities.items():
        if values.get(key) != expected:
            raise RuntimeError(f"HRIS strict role identity is not canonical: {key}")
    environment = os.environ.copy()
    for key in tuple(environment):
        if key.startswith("DWP_MIGRATION_CONTROL_"):
            environment.pop(key, None)
    environment.update(
        {
            "DWP_MIGRATION_CONTROL_MODE": "STRICT_FRESH",
            "DWP_MIGRATION_CONTROL_SERVICE": service_name,
            "DWP_MIGRATION_CONTROL_JDBC_URL": (
                f"jdbc:postgresql://localhost:5432/{control.database}"
            ),
            "DWP_MIGRATION_CONTROL_DATABASE": control.database,
            "DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL": "dwp_user",
            "DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD": "dwp_password",
            "DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL": control.runtime_role,
            "DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD": values[control.runtime_password_key],
            "DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL": control.migration_role,
            "DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD": values[
                control.migration_password_key
            ],
            "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PRINCIPAL": (
                control.publisher_role
            ),
            "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD": values[
                control.publisher_password_key
            ],
            "DWP_MIGRATION_CONTROL_REFERENCE": current_reference,
        }
    )
    if previous is not None:
        stream = previous["streams"][0]
        environment["DWP_MIGRATION_CONTROL_PREVIOUS_NATIVE_SEALS"] = (
            f'{stream["streamKey"]}:{stream["historyMaxInstalledRank"]}:'
            f'{stream["historyRowCount"]}:{stream["historySha256"]}:'
            f'{stream["inventoryObjectCount"]}:{stream["inventorySha256"]}'
        )
        environment["DWP_MIGRATION_CONTROL_PREVIOUS_RUN_RECEIPT_SHA256"] = str(
            previous["receiptSha256"]
        )
    try:
        result = subprocess.run(
            (
                "./gradlew",
                "--no-daemon",
                "--console=plain",
                control.control_task,
            ),
            cwd=BACKEND_ROOT,
            env=environment,
            check=True,
            text=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError as error:
        diagnostic = "\n".join(
            (error.stderr or error.stdout or "Migration Control failed").splitlines()[-40:]
        )
        for secret_key in (
            "DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD",
            "DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD",
            "DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD",
            "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD",
        ):
            diagnostic = diagnostic.replace(environment[secret_key], "[REDACTED]")
        raise RuntimeError(
            f"Migration Control failed for {service_name}:\n{diagnostic}"
        ) from error
    receipt_lines = [
        line.removeprefix(MIGRATION_CONTROL_OUTPUT_PREFIX)
        for line in result.stdout.splitlines()
        if line.startswith(MIGRATION_CONTROL_OUTPUT_PREFIX)
    ]
    if len(receipt_lines) != 1:
        raise RuntimeError(
            f"Migration Control emitted {len(receipt_lines)} canonical receipts."
        )
    try:
        receipt = json.loads(receipt_lines[0], object_pairs_hook=_strict_json_object)
    except json.JSONDecodeError as error:
        raise RuntimeError("Migration Control emitted invalid receipt JSON.") from error
    validated = validate_control_run_receipt(
        service_name,
        receipt,
        current_reference=current_reference,
    )
    expected_predecessor = "" if previous is None else previous["receiptSha256"]
    if validated["previousRunReceiptSha256"] != expected_predecessor:
        raise RuntimeError(
            "Migration Control receipt predecessor does not match the invoked state."
        )
    save_migration_control_receipt(
        service_name,
        validated,
        current_reference=current_reference,
    )
    return validated


def ensure_hris_controlled_database(service_name: str) -> dict[str, object]:
    """Prepare fresh state or upgrade only through a sealed native predecessor."""
    control = HRIS_CONTROLLED_DATABASES.get(service_name)
    if control is None:
        raise RuntimeError(f"Unsupported HRIS Migration Control service: {service_name}")
    receipt_path = migration_control_file(service_name)
    exists = database_exists(control.database)
    if exists:
        if not receipt_path.exists() and not receipt_path.is_symlink():
            raise RuntimeError(
                f"{service_name} existing database requires a valid external Control "
                "receipt before any role, ACL, owner or extension mutation."
            )
        previous = load_migration_control_receipt(service_name)
        current_reference = migration_control_reference()
        if previous["controlReference"] == current_reference:
            return validate_control_run_receipt(
                service_name,
                previous,
                current_reference=current_reference,
            )
        return run_hris_migration_control(
            service_name,
            previous,
            current_reference=current_reference,
        )

    if receipt_path.exists() or receipt_path.is_symlink():
        raise RuntimeError(
            f"{service_name} missing database has a stale external Control receipt."
        )
    created_by_this_run = ensure_database(control.database)
    if not created_by_this_run:
        raise RuntimeError(
            f"{service_name} database appeared during fresh bootstrap; refusing "
            "to mutate or discard state created by another process."
        )
    try:
        ensure_hris_strict_database_boundary(service_name)
        return run_hris_migration_control(
            service_name,
            None,
            current_reference=migration_control_reference(),
        )
    except BaseException as failure:
        try:
            discard_fresh_database(control.database)
        except Exception as cleanup_error:
            raise RuntimeError(
                f"{service_name} fresh bootstrap failed and its newly created "
                f"database could not be discarded: {failure}"
            ) from cleanup_error
        raise


def ensure_notification_runtime_role() -> None:
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "dwp_user",
        "-d",
        "postgres",
        "-c",
        """
DO $runtime_role$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'dwp_notification_runtime'
    ) THEN
        CREATE ROLE dwp_notification_runtime
            LOGIN PASSWORD 'dwp_notification_runtime_password'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE dwp_notification_runtime
        LOGIN PASSWORD 'dwp_notification_runtime_password'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
END
$runtime_role$;
GRANT CONNECT ON DATABASE dwp_notification TO dwp_notification_runtime;
""",
    )


def ensure_people_local_legacy_boundary() -> None:
    """Provision the isolated People runtime identity for local-only Flyway boot."""
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "dwp_user",
        "-d",
        "postgres",
        "-c",
        """
DO $runtime_role$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'dwp_people_runtime'
    ) THEN
        CREATE ROLE dwp_people_runtime
            LOGIN PASSWORD 'dwp_password'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
    ALTER ROLE dwp_people_runtime
        LOGIN PASSWORD 'dwp_password'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
END
$runtime_role$;
REVOKE TEMPORARY, CREATE ON DATABASE dwp_people FROM PUBLIC;
REVOKE TEMPORARY, CREATE ON DATABASE dwp_people FROM dwp_people_runtime;
GRANT CONNECT ON DATABASE dwp_people TO dwp_people_runtime;
ALTER ROLE dwp_people_runtime IN DATABASE dwp_people
    SET search_path TO pg_catalog, public;
""",
    )
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "dwp_user",
        "-d",
        "dwp_people",
        "-c",
        """
CREATE SCHEMA IF NOT EXISTS hris_performance AUTHORIZATION dwp_user;
REVOKE ALL ON SCHEMA public, hris_performance FROM PUBLIC;
GRANT USAGE ON SCHEMA public, hris_performance TO dwp_people_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public, hris_performance
    TO dwp_people_runtime;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public, hris_performance
    TO dwp_people_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE dwp_user IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dwp_people_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE dwp_user IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO dwp_people_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE dwp_user IN SCHEMA hris_performance
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dwp_people_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE dwp_user IN SCHEMA hris_performance
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO dwp_people_runtime;
""",
    )


def resolve_services(profile_names: Iterable[str]) -> list[Service]:
    selected: set[str] = set()
    for profile_name in profile_names or ["full"]:
        profile = PROFILES.get(profile_name)
        if profile is None:
            choices = ", ".join(sorted(PROFILES))
            raise RuntimeError(
                f"Unknown profile '{profile_name}'. Available profiles: {choices}"
            )
        selected.update(profile)
    return [SERVICES[name] for name in START_ORDER if name in selected]


def health_ready(service: Service) -> bool:
    try:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{service.port}{service.health_path}", timeout=2
        ) as response:
            return 200 <= response.status < 400
    except (OSError, urllib.error.URLError):
        return False


def meeting_home_ready(service: Service) -> bool:
    token = service_environment("meeting").get("DWP_MEETING_SERVICE_TOKEN", "").strip()
    if not token:
        return False
    headers = {
        "Accept": "application/json",
        "Cache-Control": "no-store",
        "X-DWP-Service-Token": token,
        "X-DWP-User-ID": MEETING_READINESS_USER_ID,
        "X-DWP-Tenant-ID": MEETING_READINESS_TENANT_ID,
        "X-DWP-Permissions": "APP.MEETINGS:VIEW",
        "X-DWP-Rollout-State": "000",
        "X-DWP-Rollout-Revision": MEETING_BASELINE_ROLLOUT_REVISION,
        "X-DWP-Rollout-Cohort": "baseline",
    }
    connection = http.client.HTTPConnection("127.0.0.1", service.port, timeout=2)
    try:
        connection.request("GET", "/v1/home?timeZone=UTC", headers=headers)
        response = connection.getresponse()
        if response.status != 200:
            return False
        content_type = response.getheader("Content-Type", "") or ""
        if content_type.split(";", 1)[0].strip().lower() != "application/json":
            return False
        body = response.read(MEETING_READINESS_MAXIMUM_RESPONSE_BYTES + 1)
        if len(body) > MEETING_READINESS_MAXIMUM_RESPONSE_BYTES:
            return False
        payload = json.loads(body.decode("utf-8"))
    except (
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        http.client.HTTPException,
    ):
        return False
    finally:
        connection.close()
    if not isinstance(payload, dict):
        return False
    data = payload.get("data")
    if not isinstance(data, dict):
        return False
    server_now = data.get("serverNow")
    if not isinstance(server_now, str):
        return False
    try:
        parsed_server_now = datetime.fromisoformat(server_now.replace("Z", "+00:00"))
    except ValueError:
        return False
    if parsed_server_now.tzinfo is None or parsed_server_now.utcoffset() is None:
        return False
    metrics = data.get("metrics")
    capabilities = data.get("capabilities")
    if not isinstance(metrics, dict) or not isinstance(capabilities, dict):
        return False

    def numeric(value: object) -> bool:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return False
        if isinstance(value, int):
            return 0 <= value <= MEETING_READINESS_MAXIMUM_METRIC_VALUE
        return (
            math.isfinite(value)
            and 0 <= value <= MEETING_READINESS_MAXIMUM_METRIC_VALUE
        )

    if any(
        not numeric(metrics.get(key))
        for key in ("meetingsToday", "meetingMinutesToday", "waitingForApproval")
    ):
        return False
    for key in ("qualityScore", "averageJoinSeconds"):
        if metrics.get(key) is not None and not numeric(metrics[key]):
            return False
    provider = capabilities.get("provider")
    return (
        payload.get("success") is True
        and payload.get("status") == "SUCCESS"
        and data.get("timeZone") == "UTC"
        and isinstance(data.get("today"), list)
        and isinstance(data.get("recent"), list)
        and type(capabilities.get("available")) is bool
        and isinstance(provider, str)
        and bool(provider.strip())
    )


def service_ready(service: Service) -> bool:
    if not health_ready(service):
        return False
    return service.name != "meeting" or meeting_home_ready(service)


def managed_service_ready(
    service: Service, process_state: dict[str, object]
) -> bool:
    if service.name != "meeting":
        return service_ready(service)
    before = verify_managed_process(service, process_state)
    ready = service_ready(service)
    after = verify_managed_process(service, process_state)
    if before != after:
        raise RuntimeError(
            "Managed meeting process identity changed during the "
            "functional-readiness probe."
        )
    return ready


def service_startup_timeout_seconds() -> int:
    raw_value = os.environ.get(SERVICE_STARTUP_TIMEOUT_ENVIRONMENT)
    if raw_value is None:
        return DEFAULT_SERVICE_STARTUP_TIMEOUT_SECONDS
    if not CANONICAL_POSITIVE_INTEGER.fullmatch(raw_value):
        raise RuntimeError(
            f"{SERVICE_STARTUP_TIMEOUT_ENVIRONMENT} must be a canonical positive integer."
        )

    timeout_seconds = int(raw_value)
    if not (
        MIN_SERVICE_STARTUP_TIMEOUT_SECONDS
        <= timeout_seconds
        <= MAX_SERVICE_STARTUP_TIMEOUT_SECONDS
    ):
        raise RuntimeError(
            f"{SERVICE_STARTUP_TIMEOUT_ENVIRONMENT} must be between "
            f"{MIN_SERVICE_STARTUP_TIMEOUT_SECONDS} and "
            f"{MAX_SERVICE_STARTUP_TIMEOUT_SECONDS} seconds."
        )
    return timeout_seconds


def start_service(
    service: Service,
    state: dict[str, dict[str, object]],
    *,
    reject_external_owner: bool = False,
    prepared_images: dict[str, dict[str, object]] | None = None,
) -> bool:
    current = state.get(service.name)
    if current and process_alive(int(current["pid"])):
        verify_managed_process(service, current)
        print(f"{service.name:10} already running (pid={current['pid']})")
        return False
    if current:
        _ensure_dead_state_has_no_live_group(service, current)
        state.pop(service.name, None)
        save_state(state)
        wait_for_service_port_release(service)
        reject_external_owner = True
    if port_open(service.port):
        if reject_external_owner:
            raise RuntimeError(
                f"Port {service.port} for {service.name} became occupied after its "
                "managed owner stopped; refusing to adopt the new owner or start "
                "a replacement."
            )
        if health_ready(service):
            if service.name == "meeting":
                raise RuntimeError(
                    "Meeting is owned by an external process; refusing to disclose the "
                    "functional-readiness service token."
                )
            print(f"{service.name:10} already available (external, port={service.port})")
            return False
        raise RuntimeError(
            f"Port {service.port} for {service.name} is occupied by an unhealthy process."
        )

    runtime_image: dict[str, object] | None = None
    if prepared_images is not None and service.name in JAVA_SERVICE_NAMES:
        prepared_image = prepared_images.get(service.name)
        if prepared_image is None:
            raise RuntimeError(
                f"No prepared immutable runtime image exists for {service.name}."
            )
        runtime_image = dict(prepared_image)
        launch_command = runtime_image_launch_command(service, runtime_image)
    else:
        launch_command = service.command

    LOG_ROOT.mkdir(parents=True, exist_ok=True)
    log_path = LOG_ROOT / f"{service.name}.log"
    with log_path.open("ab", buffering=0) as log_file:
        process = subprocess.Popen(
            launch_command,
            cwd=service.cwd,
            env=service_environment(service.name),
            stdin=subprocess.DEVNULL,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    state_recorded = False
    try:
        identity: dict[str, object] | None = None
        identity_error: RuntimeError | None = None
        for _ in range(20):
            try:
                identity = capture_process_identity(
                    process.pid, service.name, launch_command
                )
                break
            except RuntimeError as error:
                identity_error = error
                if process.poll() is not None:
                    break
                time.sleep(0.05)
        if identity is None:
            raise RuntimeError(
                f"Unable to establish ownership of the new {service.name} process."
            ) from identity_error

        process_state: dict[str, object] = {
            "pid": process.pid,
            "port": service.port,
            "command": list(launch_command),
            "log": str(log_path),
            "processIdentity": identity,
        }
        if runtime_image is not None:
            process_state["runtimeImage"] = runtime_image
        state[service.name] = process_state
        state_recorded = True
        save_state(state)
    except BaseException as start_error:
        try:
            terminate_spawned_process_group(process, service.name)
        except RuntimeError as cleanup_error:
            if state_recorded:
                try:
                    save_state(state)
                except Exception:
                    persistence_detail = " Ownership state could not be persisted."
                else:
                    persistence_detail = " Ownership state was retained."
            else:
                persistence_detail = " No verified ownership state was available."
            raise RuntimeError(
                f"New {service.name} process group may still be running after "
                f"startup bookkeeping failed: {cleanup_error}."
                f"{persistence_detail}"
            ) from start_error
        if state_recorded:
            state.pop(service.name, None)
            try:
                save_state(state)
            except Exception as rollback_error:
                raise RuntimeError(
                    f"New {service.name} process was stopped, but its failed-start "
                    "state could not be rolled back."
                ) from rollback_error
        raise
    print(f"{service.name:10} starting (pid={process.pid}, port={service.port})")
    return True


def wait_for_services(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    started_names: set[str] | None = None,
) -> None:
    started_names = started_names or set()
    pending = {service.name: service for service in services}
    deadline = time.monotonic() + service_startup_timeout_seconds()
    while pending and time.monotonic() < deadline:
        for name, service in list(pending.items()):
            process_state = state.get(name)
            pid = int(process_state["pid"]) if process_state else 0
            if pid and not process_alive(pid):
                log_path = Path(str(process_state["log"]))
                tail = "\n".join(log_path.read_text(errors="replace").splitlines()[-20:])
                raise RuntimeError(f"{name} exited during startup.\n{tail}")
            started = name in started_names
            if started:
                if process_state is None:
                    raise RuntimeError(
                        f"Managed {name} state disappeared during startup."
                    )
                refresh_started_process_identity(service, process_state, state)
            meeting_ready_identity: dict[str, object] | None = None
            if service.name == "meeting":
                if process_state is None:
                    raise RuntimeError(
                        "Meeting has no verified managed identity; refusing to send "
                        "the functional-readiness service token."
                    )
                if started:
                    # The Gradle launcher may exec from its shell into the Java
                    # wrapper under the same pid. Do not require the initial
                    # command hash until the unauthenticated actuator is ready.
                    if not health_ready(service):
                        ready = False
                    else:
                        before_probe = refresh_started_process_identity(
                            service, process_state, state
                        )
                        ready = meeting_home_ready(service)
                        after_probe = capture_started_process_identity(
                            service, process_state
                        )
                        if before_probe != after_probe:
                            raise RuntimeError(
                                "Managed meeting process identity changed during "
                                "the functional-readiness probe."
                            )
                        if ready:
                            meeting_ready_identity = after_probe
                else:
                    ready = managed_service_ready(service, process_state)
            else:
                ready = service_ready(service)
            if ready:
                if started:
                    if service.name == "meeting":
                        if meeting_ready_identity is None:
                            raise RuntimeError(
                                "Managed meeting readiness identity was not captured."
                            )
                        ready_identity = meeting_ready_identity
                    else:
                        ready_identity = refresh_started_process_identity(
                            service, process_state, state
                        )
                    process_state["processIdentity"] = ready_identity
                    process_state.pop("runtimeArtifacts", None)
                    # Persist the stable command generation before artifact I/O.
                    # If baseline capture fails, future devctl runs can still
                    # authenticate and safely reconcile the untracked process.
                    save_state(state)
                    if process_state.get("runtimeImage") is not None:
                        validate_published_runtime_image(
                            service, process_state["runtimeImage"]
                        )
                    elif name in SERVICE_SHARED_RUNTIME_ARTIFACTS:
                        process_state["runtimeArtifacts"] = (
                            capture_service_runtime_artifacts(service)
                        )
                        save_state(state)
                print(f"{name:10} ready at http://localhost:{service.port}")
                pending.pop(name)
        if pending:
            time.sleep(1)
    if pending:
        raise RuntimeError(
            "Startup timed out waiting for: " + ", ".join(sorted(pending))
        )


def process_group_alive(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def reap_direct_child(pid: int) -> None:
    """Reap pid only when it is a direct child; existing external parents are untouched."""
    try:
        os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        pass


def wait_for_process_group_exit(
    pgid: int,
    timeout_seconds: float,
    process: subprocess.Popen[bytes] | None = None,
    *,
    leader_pid: int | None = None,
) -> bool:
    """Return on the first absent observation; never re-query a gone PGID."""
    deadline = time.monotonic() + timeout_seconds
    while True:
        if process is not None:
            process.poll()
        elif leader_pid is not None:
            reap_direct_child(leader_pid)
        if not process_group_alive(pgid):
            if process is not None:
                try:
                    process.wait(timeout=0)
                except subprocess.TimeoutExpired:
                    return False
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(PROCESS_GROUP_POLL_INTERVAL_SECONDS, remaining))


def terminate_spawned_process_group(
    process: subprocess.Popen[bytes], service_name: str
) -> None:
    """Boundedly terminate only the process group created by this Popen call."""
    pgid = process.pid
    process.poll()
    if wait_for_process_group_exit(pgid, 0, process):
        return
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        try:
            process.wait(timeout=PROCESS_GROUP_POLL_INTERVAL_SECONDS)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(
                f"newly spawned {service_name} group disappeared, but its leader "
                "could not be reaped"
            ) from error
        return
    except PermissionError as error:
        raise RuntimeError(
            f"unable to terminate newly spawned {service_name} process group {pgid}"
        ) from error
    if wait_for_process_group_exit(
        pgid, NEW_PROCESS_TERM_TIMEOUT_SECONDS, process
    ):
        return
    try:
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        try:
            process.wait(timeout=PROCESS_GROUP_POLL_INTERVAL_SECONDS)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(
                f"newly spawned {service_name} group disappeared after SIGTERM, "
                "but its leader could not be reaped"
            ) from error
        return
    except PermissionError as error:
        raise RuntimeError(
            f"unable to kill newly spawned {service_name} process group {pgid}"
        ) from error
    if not wait_for_process_group_exit(
        pgid, NEW_PROCESS_KILL_TIMEOUT_SECONDS, process
    ):
        raise RuntimeError(
            f"newly spawned {service_name} process group {pgid} did not exit "
            "after SIGTERM and SIGKILL"
        )


def _recorded_process_group(
    process_state: dict[str, object], *, infer_legacy: bool
) -> int | None:
    identity = process_state.get("processIdentity")
    if isinstance(identity, dict) and _valid_state_integer(identity.get("pgid")):
        return int(identity["pgid"])
    if infer_legacy and _valid_state_integer(process_state.get("pid")):
        # Historical devctl launches also used start_new_session=True, so the
        # leader pid was the group id. It is safe to observe this id, never to
        # signal it without a recorded identity.
        return int(process_state["pid"])
    return None


def _ensure_dead_state_has_no_live_group(
    service: Service, process_state: dict[str, object]
) -> None:
    reap_direct_child(int(process_state["pid"]))
    pgid = _recorded_process_group(process_state, infer_legacy=True)
    if pgid is not None and process_group_alive(pgid):
        raise RuntimeError(
            f"Managed {service.name} leader pid {process_state['pid']} exited, but "
            f"process group {pgid} is still alive; state was retained and the group "
            "was not signalled."
        )


def terminate_process_group(pgid: int, pid: int, service_name: str) -> None:
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        reap_direct_child(pid)
        return
    except PermissionError as error:
        raise RuntimeError(
            f"Managed {service_name} process group {pgid} could not be signalled; "
            "state was retained."
        ) from error
    if wait_for_process_group_exit(pgid, 10, leader_pid=pid):
        return
    try:
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        reap_direct_child(pid)
        return
    except PermissionError as error:
        raise RuntimeError(
            f"Managed {service_name} process group {pgid} could not be killed; "
            "state was retained."
        ) from error
    if not wait_for_process_group_exit(pgid, 3, leader_pid=pid):
        raise RuntimeError(
            f"Managed {service_name} process group {pgid} did not stop; "
            "state was retained."
        )


def terminate_verified_managed_process(
    service: Service, process_state: dict[str, object]
) -> None:
    pid = int(process_state["pid"])
    if not process_alive(pid):
        _ensure_dead_state_has_no_live_group(service, process_state)
        return
    verify_managed_process(service, process_state)
    identity = process_state.get("processIdentity")
    if not isinstance(identity, dict) or not _valid_state_integer(identity.get("pgid")):
        raise RuntimeError(
            f"Managed {service.name} process ownership is not recorded."
        )
    terminate_process_group(int(identity["pgid"]), pid, service.name)


def stop_service(
    service: Service,
    state: dict[str, dict[str, object]],
) -> None:
    process_state = state.get(service.name)
    if process_state is None:
        return
    pid = int(process_state["pid"])
    if not process_alive(pid):
        _ensure_dead_state_has_no_live_group(service, process_state)
        state.pop(service.name, None)
        save_state(state)
        wait_for_service_port_release(service)
        return
    terminate_verified_managed_process(service, process_state)
    state.pop(service.name, None)
    save_state(state)
    wait_for_service_port_release(service)
    print(f"{service.name:10} stopped")


def reconcile_stale_services(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    prepared_images: dict[str, dict[str, object]] | None = None,
) -> set[str]:
    selected = list(services)
    for service in selected:
        process_state = state.get(service.name)
        if process_state is not None and not process_alive(int(process_state["pid"])):
            _ensure_dead_state_has_no_live_group(service, process_state)

    def scan(snapshot: ArtifactSnapshot) -> list[tuple[Service, str]]:
        candidates: list[tuple[Service, str]] = []
        for service in selected:
            process_state = state.get(service.name)
            if process_state is None or not process_alive(int(process_state["pid"])):
                continue
            desired_image = (
                prepared_images.get(service.name)
                if prepared_images is not None
                else None
            )
            stale, reason = service_runtime_staleness(
                service, process_state, snapshot, desired_image
            )
            if service.name == "meeting":
                if stale:
                    verify_managed_process(service, process_state)
                elif not managed_service_ready(service, process_state):
                    stale, reason = True, "functional readiness failed"
            if stale:
                verify_managed_process(service, process_state)
                candidates.append((service, reason))
        return candidates

    candidates = (
        scan({})
        if prepared_images is not None
        else stable_artifact_candidate_scan(selected, scan)
    )

    restarted = {service.name for service, _ in candidates}
    for service, reason in reversed(candidates):
        print(f"{service.name:10} stale ({reason}); restarting")
        stop_service(service, state)
    return restarted


def cleanup_started_services(
    selected: dict[str, Service],
    state: dict[str, dict[str, object]],
    started_names: set[str],
) -> None:
    ordered = [name for name in START_ORDER if name in started_names]
    ordered.extend(sorted(started_names - set(ordered)))
    errors: list[str] = []
    state_changed = False
    stopped_services: list[Service] = []
    for name in reversed(ordered):
        process_state = state.get(name)
        if process_state is None:
            continue
        service = selected[name]
        pid = int(process_state["pid"])
        try:
            if process_alive(pid):
                current = capture_started_process_identity(service, process_state)
                process_state["processIdentity"] = current
                process_state.pop("runtimeArtifacts", None)
                state_changed = True
                terminate_process_group(int(current["pgid"]), pid, name)
            else:
                _ensure_dead_state_has_no_live_group(service, process_state)
        except (KeyError, OSError, RuntimeError, TypeError, ValueError) as error:
            errors.append(f"{name}: {error}")
            continue
        state.pop(name, None)
        state_changed = True
        stopped_services.append(service)

    state_persisted = True
    if state_changed:
        try:
            save_state(state)
        except Exception as error:
            state_persisted = False
            errors.append(f"state rollback: {error}")
    if state_persisted:
        for service in stopped_services:
            try:
                wait_for_service_port_release(service)
            except RuntimeError as error:
                errors.append(f"{service.name}: {error}")
            else:
                print(f"{service.name:10} stopped after startup failure")
    if errors:
        raise RuntimeError("Startup cleanup incomplete: " + "; ".join(errors))


def start_and_wait_services_in_phases(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    require_owned_start_names: set[str] | None = None,
    prepared_images: dict[str, dict[str, object]] | None = None,
) -> set[str]:
    selected = {service.name: service for service in services}
    require_owned_start_names = require_owned_start_names or set()
    started: set[str] = set()
    visited: set[str] = set()
    try:
        for phase_names in START_PHASES:
            phase = [selected[name] for name in phase_names if name in selected]
            visited.update(service.name for service in phase)
            phase_started: set[str] = set()
            for service in phase:
                if service.name in require_owned_start_names:
                    if prepared_images is None:
                        was_started = start_service(
                            service, state, reject_external_owner=True
                        )
                    else:
                        was_started = start_service(
                            service,
                            state,
                            reject_external_owner=True,
                            prepared_images=prepared_images,
                        )
                else:
                    if prepared_images is None:
                        was_started = start_service(service, state)
                    else:
                        was_started = start_service(
                            service, state, prepared_images=prepared_images
                        )
                if was_started:
                    phase_started.add(service.name)
                    started.add(service.name)
            wait_for_services(phase, state, phase_started)
        uncovered = set(selected) - visited
        if uncovered:
            raise RuntimeError(
                "Selected services are missing from startup phases: "
                + ", ".join(sorted(uncovered))
            )
        return started
    except BaseException as startup_error:
        try:
            cleanup_started_services(selected, state, started)
        except RuntimeError as cleanup_error:
            raise RuntimeError(
                f"Startup failed and cleanup was incomplete: {cleanup_error}"
            ) from startup_error
        raise


def managed_runtime_drift(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    prepared_images: dict[str, dict[str, object]] | None = None,
) -> list[tuple[Service, str]]:
    """Inspect all selected managed services before any convergence signal."""
    selected = list(services)

    def scan(snapshot: ArtifactSnapshot) -> list[tuple[Service, str]]:
        candidates: list[tuple[Service, str]] = []
        for service in selected:
            process_state = state.get(service.name)
            if process_state is None:
                # An available external owner is deliberately outside devctl's
                # reconciliation and signalling authority.
                continue
            pid = int(process_state["pid"])
            if not process_alive(pid):
                _ensure_dead_state_has_no_live_group(service, process_state)
                candidates.append((service, "managed process exited"))
                continue
            verify_managed_process(service, process_state)
            desired_image = (
                prepared_images.get(service.name)
                if prepared_images is not None
                else None
            )
            stale, reason = service_runtime_staleness(
                service, process_state, snapshot, desired_image
            )
            if stale:
                candidates.append((service, reason))
            elif service.name == "meeting":
                if not managed_service_ready(service, process_state):
                    candidates.append((service, "readiness failed"))
            elif not health_ready(service):
                raise RuntimeError(
                    f"Managed {service.name} became degraded during final validation."
                )
        return candidates

    if prepared_images is not None:
        return scan({})
    return stable_artifact_candidate_scan(selected, scan)


def converge_managed_services(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    restarted_names: set[str],
    prepared_images: dict[str, dict[str, object]] | None = None,
) -> None:
    selected = list(services)
    maximum_passes = len(selected) + 1
    for _ in range(maximum_passes):
        candidates = managed_runtime_drift(selected, state, prepared_images)
        if not candidates:
            return
        exhausted = sorted(
            service.name
            for service, _reason in candidates
            if service.name in restarted_names
        )
        if exhausted:
            raise RuntimeError(
                "Runtime did not converge after one restart for: "
                + ", ".join(exhausted)
            )

        candidate_names = {service.name for service, _reason in candidates}
        reasons = {service.name: reason for service, reason in candidates}
        for service in reversed(selected):
            if service.name not in candidate_names:
                continue
            print(f"{service.name:10} {reasons[service.name]}; restarting")
            stop_service(service, state)
        restarted_names.update(candidate_names)
        start_and_wait_services_in_phases(
            (service for service in selected if service.name in candidate_names),
            state,
            candidate_names,
            prepared_images,
        )
    raise RuntimeError("Runtime artifact/readiness convergence exceeded its bound.")


def _managed_watch_snapshot(
    services: Iterable[Service],
) -> dict[str, dict[str, object]]:
    state = load_state()
    snapshot: dict[str, dict[str, object]] = {}
    for service in services:
        process_state = state.get(service.name)
        if process_state is None or not process_alive(int(process_state["pid"])):
            continue
        identity = verify_managed_process(service, process_state)
        runtime_image = process_state.get("runtimeImage")
        if not isinstance(runtime_image, dict):
            raise RuntimeError(
                f"Managed {service.name} still uses the legacy mutable runtime; "
                f"run './dev up {service.name}' before watching it."
            )
        validate_published_runtime_image(service, runtime_image)
        snapshot[service.name] = identity
    return snapshot


def _rollback_runtime_generation(
    services: list[Service],
    state: dict[str, dict[str, object]],
    previous_images: dict[str, dict[str, object]],
    previous_identities: dict[str, dict[str, object]],
) -> None:
    restore: list[Service] = []
    for service in reversed(services):
        process_state = state.get(service.name)
        if process_state is None:
            restore.append(service)
            continue
        if process_alive(int(process_state["pid"])):
            current_identity = verify_managed_process(service, process_state)
            if (
                current_identity == previous_identities[service.name]
                and process_state.get("runtimeImage")
                == previous_images[service.name]
            ):
                continue
            stop_service(service, state)
            restore.append(service)
        else:
            _ensure_dead_state_has_no_live_group(service, process_state)
            state.pop(service.name, None)
            save_state(state)
            wait_for_service_port_release(service)
            restore.append(service)
    if restore:
        restore.reverse()
        restore_names = {service.name for service in restore}
        start_and_wait_services_in_phases(
            restore,
            state,
            restore_names,
            previous_images,
        )


def apply_watched_runtime_images(
    services: Iterable[Service],
    state: dict[str, dict[str, object]],
    prepared_images: dict[str, dict[str, object]],
) -> None:
    selected = list(services)
    previous_images: dict[str, dict[str, object]] = {}
    previous_identities: dict[str, dict[str, object]] = {}
    changed: list[Service] = []
    for service in selected:
        process_state = state.get(service.name)
        if process_state is None or not process_alive(int(process_state["pid"])):
            continue
        recorded = process_state.get("runtimeImage")
        desired = prepared_images.get(service.name)
        if not isinstance(recorded, dict) or desired is None:
            raise RuntimeError(
                f"Watcher runtime image state is incomplete for {service.name}."
            )
        validate_published_runtime_image(service, recorded)
        validate_published_runtime_image(service, desired)
        previous_images[service.name] = dict(recorded)
        if recorded != desired:
            if recorded.get("migrationDigest") != desired.get("migrationDigest"):
                raise RuntimeError(
                    f"Database migration content changed for {service.name}; watcher "
                    "left the current generation running. Apply it explicitly with "
                    f"'./dev up {service.name}'."
                )
            previous_identities[service.name] = verify_managed_process(
                service, process_state
            )
            changed.append(service)
    if not changed:
        return

    changed_names = {service.name for service in changed}
    changed_images = {
        name: prepared_images[name]
        for name in changed_names
    }
    previous_changed_images = {
        name: previous_images[name]
        for name in changed_names
    }
    previous_changed_identities = {
        name: previous_identities[name]
        for name in changed_names
    }
    try:
        restarted = reconcile_stale_services(changed, state, changed_images)
        start_and_wait_services_in_phases(
            changed, state, restarted, changed_images
        )
        converge_managed_services(changed, state, restarted, changed_images)
    except BaseException as generation_error:
        try:
            _rollback_runtime_generation(
                changed,
                state,
                previous_changed_images,
                previous_changed_identities,
            )
        except BaseException as rollback_error:
            raise RuntimeError(
                "Watched runtime generation failed and rollback was incomplete: "
                f"{rollback_error}"
            ) from generation_error
        raise RuntimeError(
            "Watched runtime generation failed; the previous immutable generation "
            "was restored."
        ) from generation_error


def watch_runtime_services(profile_names: Iterable[str]) -> None:
    resolved = resolve_services(profile_names)
    selected = [service for service in resolved if service.name in JAVA_SERVICE_NAMES]
    if not selected:
        raise RuntimeError("The selected profiles contain no Java services to watch.")
    doctor(selected)

    stop_requested = False

    def request_stop(_signum: int, _frame: object) -> None:
        nonlocal stop_requested
        stop_requested = True

    previous_sigint = signal.getsignal(signal.SIGINT)
    previous_sigterm = signal.getsignal(signal.SIGTERM)
    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    try:
        with runtime_watch_lock():
            with state_change_lock():
                active_snapshot = _managed_watch_snapshot(selected)
            if not active_snapshot:
                raise RuntimeError(
                    "No selected immutable managed services are running; run './dev up' "
                    "for the desired profile first."
                )
            active_names = set(active_snapshot)
            selected_by_name = {service.name: service for service in selected}
            observed_digest = capture_runtime_source_digest(
                selected_by_name[name]
                for name in START_ORDER
                if name in active_names
            )
            pending_since: float | None = None
            pending_digest: str | None = None
            failed_digest: str | None = None
            print(
                "watching immutable runtime sources for: "
                + ", ".join(sorted(active_names))
            )

            while not stop_requested and active_names:
                time.sleep(WATCH_POLL_INTERVAL_SECONDS)
                if stop_requested:
                    break
                current_state = load_state()
                active_names = {
                    name
                    for name in active_names
                    if name in current_state
                    and process_alive(int(current_state[name]["pid"]))
                }
                if not active_names:
                    break
                active_services = [
                    selected_by_name[name]
                    for name in START_ORDER
                    if name in active_names
                ]
                current_digest = capture_runtime_source_digest(active_services)
                if current_digest == observed_digest:
                    pending_since = None
                    pending_digest = None
                    continue
                if current_digest == failed_digest:
                    continue
                now = time.monotonic()
                if pending_since is None or pending_digest != current_digest:
                    pending_since = now
                    pending_digest = current_digest
                    continue
                if now - pending_since < WATCH_DEBOUNCE_SECONDS:
                    continue

                with state_change_lock():
                    build_snapshot = _managed_watch_snapshot(active_services)
                build_names = active_names & set(build_snapshot)
                if not build_names:
                    active_names.clear()
                    break
                build_services = [
                    selected_by_name[name]
                    for name in START_ORDER
                    if name in build_names
                ]
                try:
                    prepared_images = stage_runtime_images(build_services)
                except (RuntimeError, subprocess.CalledProcessError) as error:
                    failed_digest = current_digest
                    pending_since = None
                    pending_digest = None
                    print(f"watch build failed: {error}", file=sys.stderr)
                    continue
                if stop_requested:
                    break
                built_source_digest = (
                    prepared_images.source_digest
                    if isinstance(prepared_images, PreparedRuntimeImages)
                    else current_digest
                )
                if (
                    capture_runtime_source_digest(build_services)
                    != built_source_digest
                ):
                    pending_since = None
                    pending_digest = None
                    continue

                generation_applied = False
                with state_change_lock():
                    state = load_state()
                    stable_services: list[Service] = []
                    for service in build_services:
                        process_state = state.get(service.name)
                        if (
                            process_state is None
                            or not process_alive(int(process_state["pid"]))
                        ):
                            active_names.discard(service.name)
                            continue
                        try:
                            current_identity = verify_managed_process(
                                service, process_state
                            )
                        except RuntimeError:
                            active_names.discard(service.name)
                            continue
                        if current_identity != build_snapshot.get(service.name):
                            active_names.discard(service.name)
                            continue
                        stable_services.append(service)
                    if {service.name for service in stable_services} == build_names:
                        pre_apply_digest = capture_runtime_source_digest(
                            stable_services
                        )
                        if pre_apply_digest == built_source_digest:
                            try:
                                apply_watched_runtime_images(
                                    stable_services, state, prepared_images
                                )
                            except RuntimeError as error:
                                failed_digest = built_source_digest
                                print(
                                    f"watch generation failed: {error}",
                                    file=sys.stderr,
                                )
                            else:
                                failed_digest = None
                                generation_applied = True
                pending_since = None
                pending_digest = None
                post_generation_digest = capture_runtime_source_digest(
                    selected_by_name[name]
                    for name in START_ORDER
                    if name in active_names
                )
                if generation_applied and post_generation_digest == built_source_digest:
                    observed_digest = built_source_digest
                    print("watch generation applied")
    finally:
        signal.signal(signal.SIGINT, previous_sigint)
        signal.signal(signal.SIGTERM, previous_sigterm)
    print("runtime watcher stopped; managed services were left running")


def stop_services() -> None:
    state = load_state()
    for name in START_ORDER:
        process_state = state.get(name)
        if process_state is None:
            continue
        pid = int(process_state["pid"])
        if not process_alive(pid):
            _ensure_dead_state_has_no_live_group(SERVICES[name], process_state)
            continue
        verify_managed_process(SERVICES[name], process_state)

    for name in reversed(START_ORDER):
        process_state = state.get(name)
        if process_state is None:
            continue
        stop_service(SERVICES[name], state)


def print_status() -> None:
    state = load_state()
    for name in START_ORDER:
        service = SERVICES[name]
        process_state = state.get(name)
        pid = int(process_state["pid"]) if process_state else 0
        managed = bool(pid and process_alive(pid))
        recorded_pgid = (
            _recorded_process_group(process_state, infer_legacy=True)
            if process_state is not None
            else None
        )
        orphaned_group = bool(
            process_state is not None
            and not managed
            and recorded_pgid is not None
            and process_group_alive(recorded_pgid)
        )
        port = service.port
        detail = ""
        if managed:
            try:
                stale, detail = service_runtime_staleness(
                    service, process_state  # type: ignore[arg-type]
                )
            except RuntimeError as error:
                stale, detail = True, str(error)
            if stale or not managed_process_identity_matches(
                service, process_state  # type: ignore[arg-type]
            ):
                status = "stale"
                detail = detail or "process identity is untracked"
            else:
                try:
                    ready = managed_service_ready(service, process_state)
                except RuntimeError as error:
                    status = "stale"
                    detail = str(error)
                else:
                    status = "ready" if ready else "degraded"
        elif orphaned_group:
            status = "degraded"
            detail = f"leader exited; process group {recorded_pgid} is still alive"
        elif port_open(port):
            status = "external"
        else:
            status = "stopped"
        suffix = f" pid={pid}" if managed else ""
        if managed and isinstance(process_state, dict):
            runtime_image = process_state.get("runtimeImage")
            if isinstance(runtime_image, dict) and isinstance(
                runtime_image.get("sha256"), str
            ):
                suffix += f" image={runtime_image['sha256'][:12]}"
        if detail and status in {"stale", "degraded"}:
            suffix += f" ({detail})"
        print(f"{name:10} {status:10} port={port}{suffix}")
    docker_compose(
        "--profile", "events", "--profile", "media", "ps", check=False
    )


def show_logs(service_name: str | None, follow: bool) -> None:
    state = load_state()
    names = [service_name] if service_name else list(START_ORDER)
    log_paths = [Path(str(state[name]["log"])) for name in names if name in state]
    if not log_paths:
        raise RuntimeError("No managed service logs are available.")
    command = ["tail", "-n", "100"]
    if follow:
        command.append("-f")
    subprocess.run((*command, *(str(path) for path in log_paths)), check=False)


def reset_database(confirmed: bool) -> None:
    """Reset runtime data while caller holds build, watch, then state locks."""
    if not confirmed:
        raise RuntimeError("Database reset requires --yes because it deletes the Docker volume.")
    stop_services()
    docker_compose(
        "--profile", "events", "--profile", "media",
        "down", "-v", "--remove-orphans"
    )
    if RUNTIME_ROOT.exists():
        for runtime_entry in RUNTIME_ROOT.iterdir():
            if runtime_entry in {
                STATE_LOCK_FILE,
                RUNTIME_IMAGE_BUILD_LOCK_FILE,
                RUNTIME_WATCH_LOCK_FILE,
            }:
                continue
            if runtime_entry.is_dir() and not runtime_entry.is_symlink():
                shutil.rmtree(runtime_entry)
            else:
                runtime_entry.unlink(missing_ok=True)
    print("Local PostgreSQL and Redis data was removed. Run './dev up full' for a fresh schema.")


def bootstrap_hris_authority(confirmed: bool) -> None:
    try:
        from scripts.hris_local_authority_bootstrap import bootstrap_local_authority
    except ModuleNotFoundError:
        from hris_local_authority_bootstrap import bootstrap_local_authority

    evidence = bootstrap_local_authority(local_environment(), confirmed)
    print(json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("doctor", help="check local development dependencies")

    up_parser = commands.add_parser("up", help="start infrastructure and services")
    up_parser.add_argument("profiles", nargs="*", default=["full"])

    watch_parser = commands.add_parser(
        "watch",
        help="watch source changes and replace running immutable Java images",
    )
    watch_parser.add_argument("profiles", nargs="*", default=["full"])

    commands.add_parser("stop", help="stop managed application processes")
    commands.add_parser("down", help="stop applications and PostgreSQL")
    commands.add_parser("status", help="show application and infrastructure status")

    migration_parser = commands.add_parser(
        "migrate-legacy-state",
        help="explicitly bind legacy managed state to live identities without signalling",
    )
    migration_parser.add_argument("services", nargs="+", choices=START_ORDER)
    migration_parser.add_argument("--yes", action="store_true")

    logs_parser = commands.add_parser("logs", help="show managed service logs")
    logs_parser.add_argument("service", nargs="?", choices=START_ORDER)
    logs_parser.add_argument("--follow", "-f", action="store_true")

    reset_parser = commands.add_parser("reset", help="delete the local PostgreSQL volume")
    reset_parser.add_argument("--yes", action="store_true")
    bootstrap_help = (
        "publish local Time/Payroll authority; conflicting revisions fail closed "
        "and are never implicitly replaced"
    )
    bootstrap_parser = commands.add_parser(
        "bootstrap-hris-authority",
        help=bootstrap_help,
        description=bootstrap_help,
    )
    bootstrap_parser.add_argument("--yes", action="store_true")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.command == "doctor":
        doctor()
    elif args.command == "up":
        services = resolve_services(args.profiles)
        doctor(services)
        prepared_images = stage_runtime_images(services)
        with state_change_lock():
            start_infrastructure(
                include_events=any(
                    service.name == "notification" for service in services
                ),
                include_media=any(
                    service.name in {"messaging", "meeting"} for service in services
                ),
                include_people=any(service.name == "people" for service in services),
                include_time=any(service.name == "time" for service in services),
                include_payroll=any(service.name == "payroll" for service in services),
            )
            state = load_state()
            restarted_names = reconcile_stale_services(
                services, state, prepared_images
            )
            tracked_before_start = set(state)
            started_names = start_and_wait_services_in_phases(
                services, state, restarted_names, prepared_images
            )
            restarted_names.update(started_names & tracked_before_start)
            converge_managed_services(
                services, state, restarted_names, prepared_images
            )
    elif args.command == "watch":
        watch_runtime_services(args.profiles)
    elif args.command == "stop":
        with state_change_lock():
            stop_services()
    elif args.command == "down":
        with state_change_lock():
            stop_services()
            docker_compose(
                "--profile", "events", "--profile", "media",
                "down", "--remove-orphans"
            )
    elif args.command == "status":
        print_status()
    elif args.command == "migrate-legacy-state":
        with state_change_lock():
            migrate_legacy_process_identities(args.services, args.yes)
    elif args.command == "logs":
        show_logs(args.service, args.follow)
    elif args.command == "reset":
        with runtime_image_build_lock():
            with runtime_watch_lock():
                with state_change_lock():
                    reset_database(args.yes)
    elif args.command == "bootstrap-hris-authority":
        bootstrap_hris_authority(args.yes)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
