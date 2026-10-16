#!/usr/bin/env python3
"""Local process supervisor for the DWP starter workspace."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
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
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


BACKEND_ROOT = Path(__file__).resolve().parents[1]
WORKSPACE_ROOT = BACKEND_ROOT.parent
FRONTEND_ROOT = WORKSPACE_ROOT / "dwp-frontend"
AGENT_ROOT = WORKSPACE_ROOT / "dwp_agent"
AGENT_LOCAL_ENV_FILE = AGENT_ROOT / ".env.local"
RUNTIME_ROOT = BACKEND_ROOT / ".dev-runtime"
LOG_ROOT = RUNTIME_ROOT / "logs"
STATE_FILE = RUNTIME_ROOT / "processes.json"
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
SERVICE_STARTUP_TIMEOUT_ENVIRONMENT = "DWP_DEVCTL_STARTUP_TIMEOUT_SECONDS"
SPRING_READINESS_PATH = "/actuator/health/readiness"
DEFAULT_SERVICE_STARTUP_TIMEOUT_SECONDS = 300
MIN_SERVICE_STARTUP_TIMEOUT_SECONDS = 60
MAX_SERVICE_STARTUP_TIMEOUT_SECONDS = 900
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


def load_state() -> dict[str, dict[str, object]]:
    if not STATE_FILE.exists():
        return {}
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}


def save_state(state: dict[str, dict[str, object]]) -> None:
    if not state:
        STATE_FILE.unlink(missing_ok=True)
        return
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


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


def start_service(service: Service, state: dict[str, dict[str, object]]) -> None:
    current = state.get(service.name)
    if current and process_alive(int(current["pid"])):
        print(f"{service.name:10} already running (pid={current['pid']})")
        return
    if port_open(service.port):
        if health_ready(service):
            print(f"{service.name:10} already available (external, port={service.port})")
            return
        raise RuntimeError(
            f"Port {service.port} for {service.name} is occupied by an unhealthy process."
        )

    LOG_ROOT.mkdir(parents=True, exist_ok=True)
    log_path = LOG_ROOT / f"{service.name}.log"
    with log_path.open("ab", buffering=0) as log_file:
        process = subprocess.Popen(
            service.command,
            cwd=service.cwd,
            env=service_environment(service.name),
            stdin=subprocess.DEVNULL,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    state[service.name] = {
        "pid": process.pid,
        "port": service.port,
        "command": list(service.command),
        "log": str(log_path),
    }
    save_state(state)
    print(f"{service.name:10} starting (pid={process.pid}, port={service.port})")


def wait_for_services(
    services: Iterable[Service], state: dict[str, dict[str, object]]
) -> None:
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
            if health_ready(service):
                print(f"{name:10} ready at http://localhost:{service.port}")
                pending.pop(name)
        if pending:
            time.sleep(1)
    if pending:
        raise RuntimeError(
            "Startup timed out waiting for: " + ", ".join(sorted(pending))
        )


def stop_services() -> None:
    state = load_state()
    for name in reversed(START_ORDER):
        process_state = state.pop(name, None)
        if not process_state:
            continue
        pid = int(process_state["pid"])
        if not process_alive(pid):
            continue
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            continue
        deadline = time.monotonic() + 10
        while process_alive(pid) and time.monotonic() < deadline:
            time.sleep(0.2)
        if process_alive(pid):
            os.killpg(pid, signal.SIGKILL)
        print(f"{name:10} stopped")
    save_state(state)


def print_status() -> None:
    state = load_state()
    for name in START_ORDER:
        process_state = state.get(name)
        pid = int(process_state["pid"]) if process_state else 0
        managed = bool(pid and process_alive(pid))
        port = SERVICES[name].port
        status = "running" if managed else "external" if port_open(port) else "stopped"
        print(f"{name:10} {status:8} port={port}" + (f" pid={pid}" if managed else ""))
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
    if not confirmed:
        raise RuntimeError("Database reset requires --yes because it deletes the Docker volume.")
    stop_services()
    docker_compose(
        "--profile", "events", "--profile", "media",
        "down", "-v", "--remove-orphans"
    )
    shutil.rmtree(RUNTIME_ROOT, ignore_errors=True)
    print("Local PostgreSQL and Redis data was removed. Run './dev up full' for a fresh schema.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("doctor", help="check local development dependencies")

    up_parser = commands.add_parser("up", help="start infrastructure and services")
    up_parser.add_argument("profiles", nargs="*", default=["full"])

    commands.add_parser("stop", help="stop managed application processes")
    commands.add_parser("down", help="stop applications and PostgreSQL")
    commands.add_parser("status", help="show application and infrastructure status")

    logs_parser = commands.add_parser("logs", help="show managed service logs")
    logs_parser.add_argument("service", nargs="?", choices=START_ORDER)
    logs_parser.add_argument("--follow", "-f", action="store_true")

    reset_parser = commands.add_parser("reset", help="delete the local PostgreSQL volume")
    reset_parser.add_argument("--yes", action="store_true")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if args.command == "doctor":
        doctor()
    elif args.command == "up":
        services = resolve_services(args.profiles)
        doctor(services)
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
        selected = {service.name: service for service in services}
        for phase_names in START_PHASES:
            phase = [selected[name] for name in phase_names if name in selected]
            for service in phase:
                start_service(service, state)
            wait_for_services(phase, state)
    elif args.command == "stop":
        stop_services()
    elif args.command == "down":
        stop_services()
        docker_compose(
            "--profile", "events", "--profile", "media",
            "down", "--remove-orphans"
        )
    elif args.command == "status":
        print_status()
    elif args.command == "logs":
        show_logs(args.service, args.follow)
    elif args.command == "reset":
        reset_database(args.yes)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
