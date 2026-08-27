#!/usr/bin/env python3
"""Local process supervisor for the DWP starter workspace."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
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
MIN_AGENT_PYTHON = (3, 11)
MIN_FRONTEND_NODE = (24, 18, 0)
MAX_FRONTEND_NODE_MAJOR = 25
ENVIRONMENT_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")
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
        "/actuator/health",
    ),
    "platform": Service(
        "platform",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-platform-server:bootRun"),
        8002,
        "/actuator/health",
    ),
    "people": Service(
        "people",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-people-server:bootRun"),
        8003,
        "/actuator/health",
    ),
    "provider": Service(
        "provider",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-provider-server:bootRun"),
        8004,
        "/actuator/health",
    ),
    "approval": Service(
        "approval",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-approval-server:bootRun"),
        8005,
        "/actuator/health",
    ),
    "space": Service(
        "space",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-space-server:bootRun"),
        8006,
        "/actuator/health",
    ),
    "messaging": Service(
        "messaging",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-messaging-server:bootRun"),
        8007,
        "/actuator/health",
    ),
    "notification": Service(
        "notification",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-notification-server:bootRun"),
        8008,
        "/actuator/health",
    ),
    "meeting": Service(
        "meeting",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-meeting-server:bootRun"),
        8009,
        "/actuator/health",
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
    "gateway": Service(
        "gateway",
        BACKEND_ROOT,
        ("./gradlew", "--no-daemon", ":dwp-gateway:bootRun"),
        8080,
        "/actuator/health",
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
    "full": CORE_SERVICES | OPTIONAL_RUNTIME_SERVICES | {"agent", "gateway", "frontend"},
    "core": CORE_SERVICES | OPTIONAL_RUNTIME_SERVICES | {"gateway", "frontend"},
    "backend": CORE_SERVICES | OPTIONAL_RUNTIME_SERVICES | {"agent", "gateway"},
    "contracts": CORE_SERVICES | OPTIONAL_RUNTIME_SERVICES | {"gateway"},
    "auth": {"auth"},
    "platform": {"platform"},
    "people": {"people"},
    "provider": {"provider"},
    "agent": {"agent"},
    "gateway": {"gateway"},
    "approval": {"approval"},
    "space": {"space"},
    "web": {"frontend"},
}
for optional_service in OPTIONAL_RUNTIME_SERVICES:
    PROFILES[optional_service] = {optional_service}

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
    "gateway",
    "frontend",
)
START_PHASES = (
    ("platform",),
    ("auth", "people", "provider", "approval", "space", "messaging", "notification", "meeting", "agent"),
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
        "SERVICE_PLATFORM_URL": "http://localhost:8002",
        "SERVICE_PEOPLE_URL": "http://localhost:8003",
        "SERVICE_PROVIDER_URL": "http://localhost:8004",
        "SERVICE_APPROVAL_URL": "http://localhost:8005",
        "SERVICE_SPACE_URL": "http://localhost:8006",
        "SERVICE_MESSAGING_URL": "http://localhost:8007",
        "SERVICE_NOTIFICATION_URL": "http://localhost:8008",
        "SERVICE_MEETING_URL": "http://localhost:8009",
        "DWP_PRODUCT_SURFACE_TOKEN": (
            "dwp-local-product-surface-token-change-outside-local"
        ),
        "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
        "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED": "true",
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
        "DWP_PLATFORM_SERVICE_TOKEN": "dwp-local-platform-service-token",
        "DWP_PLATFORM_RUNTIME_SERVICE_TOKEN": "dwp-local-platform-runtime-token",
        "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED": "true",
        "DWP_PRODUCTIVITY_DATA_KEY": (
            "ZHdwLWxvY2FsLXByb2R1Y3Rpdml0eS1rZXktMzJieXQ="
        ),
        "DWP_PEOPLE_SERVICE_TOKEN": "dwp-local-people-service-token",
        "DWP_PEOPLE_CURSOR_SECRET": "dwp-local-people-cursor-secret-change-outside-local",
        "DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED": "true",
        "DWP_PEOPLE_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
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
        "DWP_APPROVAL_EXTERNAL_SIGNATURE_ENABLED": "false",
        "DWP_APPROVAL_INTEGRATION_RELAY_ENABLED": "true",
        "DWP_SPACE_SERVICE_TOKEN": "dwp-local-space-service-token",
        "DWP_SPACE_FLYWAY_LOCATIONS": (
            "classpath:db/migration,classpath:db/local-seed"
        ),
        "DWP_MESSAGING_SERVICE_TOKEN": "dwp-local-messaging-service-token",
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
            "dwp-messaging-server=dwp-local-messaging-notification-token"
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
        "DWP_HOME_FLOW_ENABLED": "true",
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
    if service_name == "agent":
        for key, value in load_agent_local_environment().items():
            if key not in os.environ:
                environment[key] = value
    else:
        for key in AGENT_MODEL_ENVIRONMENT:
            environment.pop(key, None)
    if service_name not in {"auth", "gateway"}:
        environment.pop("DWP_PRODUCT_SURFACE_TOKEN", None)
    if service_name != "auth":
        environment.pop("DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED", None)
        environment.pop(
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED", None
        )
    if service_name not in {"agent", "gateway"}:
        environment.pop("DWP_AGENT_SERVICE_TOKEN", None)
        environment.pop("DWP_AGENT_IDENTITY_SIGNING_SECRET", None)
        environment.pop("DWP_AGENT_IDENTITY_KEY_ID", None)
    if service_name == "agent":
        environment.pop("DWP_PLATFORM_SERVICE_TOKEN", None)
    elif service_name == "gateway":
        environment.pop("DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", None)
    elif service_name not in {"platform"}:
        environment.pop("DWP_PLATFORM_SERVICE_TOKEN", None)
        environment.pop("DWP_PLATFORM_RUNTIME_SERVICE_TOKEN", None)
    if service_name != "platform":
        environment.pop(
            "DWP_PLATFORM_PRODUCT_AUTHORIZATION_APPROVALS_V2_ENABLED", None
        )
    if service_name != "agent":
        environment.pop("DWP_AGENT_DATABASE_URL", None)
        environment.pop("DWP_AGENT_DATABASE_REQUIRED", None)
        environment.pop("DWP_AGENT_KEY_PROVIDER", None)
        environment.pop("DWP_AGENT_DATA_KEY", None)
        environment.pop("DWP_AGENT_DATA_KEY_VERSION", None)
        environment.pop("DWP_AGENT_PRIVACY_HASH_SECRET", None)
        environment.pop("DWP_AGENT_SAFETY_SECRET", None)
        environment.pop("DWP_AGENT_REGISTRY_MODE", None)
    if service_name != "platform":
        environment.pop("DWP_PRODUCTIVITY_DATA_KEY", None)
    if service_name not in {"gateway", "people"}:
        environment.pop("DWP_PEOPLE_SERVICE_TOKEN", None)
    if service_name != "people":
        environment.pop("DWP_PEOPLE_CURSOR_SECRET", None)
        environment.pop("DWP_PEOPLE_FLYWAY_LOCATIONS", None)
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
        environment.pop("DWP_APPROVAL_INTEGRATION_RELAY_ENABLED", None)
    if service_name != "provider":
        environment.pop("DWP_PROVIDER_SUPPORT_COOKIE_SECURE", None)
        environment.pop("DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED", None)
        environment.pop("DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED", None)
    if service_name not in {"auth", "platform", "people", "provider"}:
        environment.pop("DWP_PROVIDER_PROVISIONING_TOKEN", None)
    if service_name not in {"auth", "platform", "people", "space"}:
        environment.pop("DWP_IDENTITY_SYNC_TOKEN", None)
    if service_name not in {"gateway", "messaging"}:
        environment.pop("DWP_MESSAGING_SERVICE_TOKEN", None)
    if service_name != "messaging":
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
    if selected.intersection({"auth", "platform", "people", "provider", "approval", "space", "messaging", "notification", "meeting", "gateway"}):
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
            ensure_database("dwp_agent")
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


def ensure_database(database_name: str) -> None:
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
    if result.stdout.strip() == "1":
        return
    docker_compose(
        "exec",
        "-T",
        "postgres",
        "createdb",
        "-U",
        "dwp_user",
        database_name,
    )


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
    deadline = time.monotonic() + 180
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
