#!/usr/bin/env python3
"""Run the isolated HRIS Wave 1 backend synthetic-acceptance lane.

The runner intentionally owns every mutable resource that it creates.  It uses
new, uniquely named Docker containers backed by tmpfs, binds only loopback
ports, uses synthetic actors and credentials, and removes the containers in a
finally block.  It never connects to an existing DWP database.

The lifecycle exercised here is the production-shaped, token-separated HTTP
lane. Flyway first stops at V232, Auth imports only v32 and activates it at
revision 1, then the same database is upgraded to V233. The runner proves that
the active v32 pointer survived the upgrade, imports and activates v33 with
CAS, and rolls the active pointer back to v32 while retaining exact governance
evidence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
import re
import secrets
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence


ROOT = Path(__file__).resolve().parents[1]
BUNDLE_KEY = "product-surfaces"
V32_CHECKSUM = "9e4e274bf457d1a5947c8b54e83299d28fb9fe128d9f1100991bc30634b54344"
V33_CHECKSUM = "254ead674e1126d50e8dcf1011486ea1127fb2479f7a82d832cdf0466995bc49"
RUN_ID_PATTERN = re.compile(r"^w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}$")
OUTPUT_BASENAME_PATTERN = re.compile(
    r"^hris-w1-synthetic-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}$"
)
OPERATIONS_PATH = "/internal/auth/v1/product-authorization/operations/bundles"
APPROVAL_IDENTITY = "dwp-provider-server"
ACTIVATION_IDENTITY = "dwp-platform-server"
MAX_PERSISTED_HTTP_BODY_BYTES = 256 * 1024
MAX_CHECKPOINT_MANIFEST_BYTES = 1024 * 1024
RUNTIME_ENVIRONMENT_ALLOWLIST = (
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
    "PATH",
    "TMPDIR",
    "TZ",
)
REQUIRED_CHECKPOINT_ASSERTIONS = (
    "tenant-a.general-owner-api",
    "tenant-a.high-assurance-step-up",
    "tenant-a.receipt-lineage",
    "tenant-a.idempotency-replay",
    "tenant-a.separation-of-duties",
    "tenant-b.feature-off",
    "tenant-b.denied",
    "isolation.cross-tenant-denied",
    "isolation.population-boundary",
    "negative.stale-evidence-denied",
    "negative.expired-evidence-denied",
    "negative.revoked-evidence-denied",
    "negative.unmapped-route-denied",
    "path.browser-gateway-owner-db",
    "rollout.flag-off",
)


class GateFailure(RuntimeError):
    """A fail-closed synthetic-acceptance failure."""


class ExternalControlEvidenceRequired(GateFailure):
    """The runtime correctly refused to replace an external trust artifact."""


@dataclass(frozen=True)
class HttpEvidence:
    name: str
    status: int
    path: str
    sha256: str
    byte_count: int
    summary: dict[str, Any] | None


@dataclass
class ManagedProcess:
    name: str
    process: subprocess.Popen[bytes]
    log_handle: Any
    log_path: Path

    def stop(self, timeout: float = 20.0) -> None:
        if self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                self.process.wait(timeout=10)
        self.log_handle.close()


@dataclass
class GateState:
    run_id: str
    output_dir: Path
    started_at: str
    phases: list[dict[str, Any]] = field(default_factory=list)
    http_evidence: list[HttpEvidence] = field(default_factory=list)
    processes: dict[str, ManagedProcess] = field(default_factory=dict)
    containers: list[str] = field(default_factory=list)
    checkpoint_process_groups: list[int] = field(default_factory=list)
    network: str | None = None
    ports: dict[str, int] = field(default_factory=dict)
    provenance: dict[str, Any] = field(default_factory=dict)
    active_revision: int = 0
    active_version: int | None = None
    rollback_complete: bool = False

    def phase(self, name: str, status: str, **details: Any) -> None:
        item = {
            "name": name,
            "status": status,
            "at": utc_now(),
        }
        if details:
            item["details"] = details
        self.phases.append(item)
        print(f"[{status}] {name}", flush=True)


@dataclass(frozen=True)
class RuntimeSecrets:
    postgres_password: str
    redis_password: str
    people_password: str
    people_migration_password: str
    payroll_password: str
    payroll_migration_password: str
    time_password: str
    time_migration_password: str
    approval_token: str
    activation_token: str
    product_surface_token: str
    platform_token: str
    people_token: str
    provider_token: str
    payroll_token: str
    time_token: str
    gateway_agent_service_token: str
    gateway_agent_identity_signing_secret: str
    gateway_provider_support_validation_token: str

    @classmethod
    def generate(cls) -> "RuntimeSecrets":
        return cls(
            *(secrets.token_urlsafe(30) for _ in range(len(cls.__dataclass_fields__)))
        )


@dataclass(frozen=True)
class ServiceSpec:
    name: str
    module: str
    database: str | None
    extra_environment: dict[str, str]

    @property
    def jar(self) -> Path:
        return ROOT / self.module / "build" / "libs" / f"{self.module}-1.0.0.jar"


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def make_run_id(now: dt.datetime | None = None, suffix: str | None = None) -> str:
    instant = (now or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
    token = suffix or secrets.token_hex(4)
    if not re.fullmatch(r"[0-9a-f]{8}", token):
        raise ValueError("run-id suffix must be exactly eight lowercase hexadecimal characters")
    return f"w1-{instant:%Y%m%dt%H%M%Sz}-{token}"


def output_basename(run_id: str) -> str:
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise GateFailure(f"Unsafe W1 run id: {run_id}")
    return "hris-w1-synthetic-" + run_id.removeprefix("w1-")


def validate_new_output_dir(path: Path, run_id: str) -> Path:
    resolved = path.expanduser().resolve()
    if not resolved.is_absolute():
        raise GateFailure("Evidence directory must be absolute.")
    if resolved.name != output_basename(run_id):
        raise GateFailure(
            "Evidence directory basename must exactly match the generated W1 run id."
        )
    if not OUTPUT_BASENAME_PATTERN.fullmatch(resolved.name):
        raise GateFailure(f"Unsafe evidence directory name: {resolved.name}")
    if resolved.exists() or resolved.is_symlink():
        raise GateFailure(f"Evidence directory must not already exist: {resolved}")
    return resolved


def resource_name(run_id: str, kind: str) -> str:
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise GateFailure(f"Unsafe W1 run id: {run_id}")
    if not re.fullmatch(r"[a-z][a-z0-9-]{1,30}", kind):
        raise GateFailure(f"Unsafe resource kind: {kind}")
    return f"dwp-hris-{run_id}-{kind}"


def run_checked(
    command: Sequence[str],
    *,
    timeout: float = 120.0,
    capture_output: bool = True,
    log_path: Path | None = None,
    environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        tuple(command),
        cwd=ROOT,
        env=environment,
        check=False,
        text=True,
        capture_output=capture_output,
        timeout=timeout,
    )
    if log_path is not None:
        log_path.write_text(
            (result.stdout or "") + (result.stderr or ""), encoding="utf-8"
        )
    if result.returncode != 0:
        command_digest = hashlib.sha256(
            "\0".join(command).encode("utf-8")
        ).hexdigest()
        detail = (result.stderr or result.stdout or "").strip()[-2000:]
        raise GateFailure(
            f"Command failed ({result.returncode}): executable={command[0]!r}, "
            f"argumentsSha256={command_digest}\n{detail}"
        )
    return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tracked_source_provenance() -> dict[str, Any]:
    status = run_checked(
        ("git", "status", "--porcelain", "--untracked-files=no"),
        timeout=30,
    ).stdout.strip()
    if status:
        raise GateFailure(
            "Tracked worktree must be clean before a provenance-bearing W1 run: "
            + status.replace("\n", "; ")
        )
    head = run_checked(("git", "rev-parse", "HEAD"), timeout=30).stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise GateFailure(f"Unable to bind W1 run to a Git commit: {head!r}")
    run_checked(
        ("git", "ls-files", "--error-unmatch", str(Path(__file__).resolve().relative_to(ROOT))),
        timeout=30,
    )
    return {
        "gitHead": head,
        "trackedWorktreeClean": True,
        "runnerPath": str(Path(__file__).resolve().relative_to(ROOT)),
        "runnerSha256": sha256_file(Path(__file__).resolve()),
    }


def verify_source_provenance(expected: dict[str, Any]) -> dict[str, Any]:
    actual = tracked_source_provenance()
    for key in ("gitHead", "runnerPath", "runnerSha256"):
        if actual[key] != expected[key]:
            raise GateFailure(
                f"Source provenance changed during the W1 build: {key} "
                f"expected={expected[key]!r} actual={actual[key]!r}"
            )
    return actual


def docker(*arguments: str, timeout: float = 120.0) -> str:
    return run_checked(("docker", *arguments), timeout=timeout).stdout.strip()


def docker_port(container: str, internal_port: int) -> int:
    value = docker("port", container, f"{internal_port}/tcp")
    match = re.search(r"127\.0\.0\.1:([0-9]+)$", value)
    if not match:
        raise GateFailure(f"Docker did not return a loopback port for {container}: {value}")
    return int(match.group(1))


def allocate_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_for_postgres(container: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = subprocess.run(
            ("docker", "exec", container, "pg_isready", "-U", "postgres"),
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode == 0:
            return
        time.sleep(0.5)
    raise GateFailure(f"Postgres did not become ready: {container}")


def psql(
    container: str,
    database: str,
    sql: str,
    *,
    tuples_only: bool = True,
) -> str:
    arguments = ["exec", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres"]
    if tuples_only:
        arguments.extend(("-A", "-t"))
    arguments.extend(("-d", database, "-c", sql))
    return docker(*arguments, timeout=180.0)


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def create_database(container: str, database: str) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9_]{1,40}", database):
        raise GateFailure(f"Unsafe synthetic database name: {database}")
    psql(container, "postgres", f'CREATE DATABASE "{database}";')


def create_role(container: str, role: str, password: str) -> None:
    if not re.fullmatch(r"dwp_[a-z]+_(runtime|migration)", role):
        raise GateFailure(f"Unsafe synthetic service role: {role}")
    psql(
        container,
        "postgres",
        f'CREATE ROLE "{role}" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
        f'NOINHERIT NOBYPASSRLS PASSWORD {sql_literal(password)};',
    )


def http_request(
    state: GateState,
    *,
    name: str,
    port: int,
    method: str,
    path: str,
    headers: dict[str, str] | None = None,
    payload: dict[str, Any] | None = None,
    expected_status: int | Iterable[int] = 200,
    persist_body: bool = True,
) -> tuple[int, dict[str, Any] | None, bytes]:
    data = None
    request_headers = dict(headers or {})
    if payload is not None:
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=data,
        headers=request_headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
            body = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        body = error.read()
    allowed = {expected_status} if isinstance(expected_status, int) else set(expected_status)
    parsed: dict[str, Any] | None = None
    if body:
        try:
            decoded = json.loads(body)
            if isinstance(decoded, dict):
                parsed = decoded
        except json.JSONDecodeError:
            parsed = None
    summary = summarize_http_body(parsed)
    body_path = state.output_dir / "http" / f"{name}.json"
    body_sha256 = hashlib.sha256(body).hexdigest()
    if persist_body:
        if len(body) <= MAX_PERSISTED_HTTP_BODY_BYTES:
            body_path.write_bytes(body)
        else:
            body_path.write_text(
                json.dumps(
                    {
                        "bodyOmitted": True,
                        "reason": "response exceeds the evidence body size limit",
                        "originalByteCount": len(body),
                        "originalSha256": body_sha256,
                        "summary": summary,
                    },
                    indent=2,
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
    evidence = HttpEvidence(
        name=name,
        status=status,
        path=str(body_path.relative_to(state.output_dir)) if persist_body else "",
        sha256=body_sha256,
        byte_count=len(body),
        summary=summary,
    )
    state.http_evidence.append(evidence)
    if status not in allowed:
        text = body.decode("utf-8", errors="replace")[:1000]
        raise GateFailure(f"{name} returned HTTP {status}, expected {sorted(allowed)}: {text}")
    return status, parsed, body


def summarize_http_body(body: dict[str, Any] | None) -> dict[str, Any] | None:
    if body is None:
        return None
    allowed = (
        "bundleKey",
        "version",
        "bundleStatus",
        "activeRevision",
        "operation",
        "revision",
        "checksum",
        "approvedBy",
        "approvedAt",
        "activatedAt",
        "code",
        "message",
        "status",
    )
    return {key: body[key] for key in allowed if key in body}


def wait_for_health(
    state: GateState,
    service: str,
    port: int,
    process: ManagedProcess,
    timeout: float,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last_error = "no response"
    while time.monotonic() < deadline:
        return_code = process.process.poll()
        if return_code is not None:
            tail = tail_text(process.log_path, 80)
            if (
                "expectedControlReference must be a canonical Control reference" in tail
                or "Migration Control run receipt JSON is required" in tail
            ):
                raise ExternalControlEvidenceRequired(
                    f"{service} requires an externally issued Migration Control run "
                    "receipt and canonical Control reference; the synthetic runner "
                    "refuses to mint or substitute trusted deployment evidence. "
                    f"See {process.log_path}."
                )
            raise GateFailure(f"{service} exited with {return_code}.\n{tail}")
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/actuator/health", method="GET"
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                body = json.loads(response.read())
            if response.status == 200 and body.get("status") == "UP":
                (state.output_dir / "health" / f"{service}.json").write_text(
                    json.dumps(body, indent=2, sort_keys=True) + "\n", encoding="utf-8"
                )
                return body
            last_error = f"HTTP {response.status}: {body!r}"
        except (OSError, ValueError, urllib.error.URLError, http.client.HTTPException) as error:
            last_error = str(error)
        time.sleep(0.5)
    raise GateFailure(f"{service} health did not become UP: {last_error}")


def tail_text(path: Path, line_count: int) -> str:
    try:
        return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-line_count:])
    except OSError:
        return "<log unavailable>"


def start_jar(
    state: GateState,
    spec: ServiceSpec,
    environment: dict[str, str],
    timeout: float,
    *,
    runtime_name: str | None = None,
) -> ManagedProcess:
    if not spec.jar.is_file():
        raise GateFailure(f"Missing executable bootJar: {spec.jar}")
    expected_jar = state.provenance.get("jars", {}).get(spec.name)
    if not isinstance(expected_jar, dict):
        raise GateFailure(f"Missing bootJar provenance for service: {spec.name}")
    actual_jar_sha256 = sha256_file(spec.jar)
    if actual_jar_sha256 != expected_jar.get("sha256"):
        raise GateFailure(
            f"BootJar changed after provenance capture for service {spec.name}."
        )
    process_name = runtime_name or spec.name
    log_path = state.output_dir / "logs" / f"{process_name}.log"
    log_handle = log_path.open("wb")
    process = subprocess.Popen(
        ("java", "-jar", str(spec.jar)),
        cwd=ROOT,
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    managed = ManagedProcess(process_name, process, log_handle, log_path)
    state.processes[process_name] = managed
    wait_for_health(state, process_name, state.ports[spec.name], managed, timeout)
    return managed


def stop_process(state: GateState, name: str) -> None:
    process = state.processes.pop(name, None)
    if process is not None:
        process.stop()


def allowlisted_host_environment() -> dict[str, str]:
    environment = {
        name: os.environ[name]
        for name in RUNTIME_ENVIRONMENT_ALLOWLIST
        if name in os.environ
    }
    environment.setdefault("PATH", os.defpath)
    return environment


def common_environment(
    state: GateState,
    secrets_: RuntimeSecrets,
    postgres_port: int,
    redis_port: int,
) -> dict[str, str]:
    # Never let an ambient SPRING_*, DB_*, DWP_* or cloud credential silently
    # redirect a synthetic service toward a shared or customer environment.
    environment = allowlisted_host_environment()
    environment.update(
        {
            "DB_HOST": "127.0.0.1",
            "DB_PORT": str(postgres_port),
            "DB_USERNAME": "postgres",
            "DB_PASSWORD": secrets_.postgres_password,
            "REDIS_HOST": "127.0.0.1",
            "REDIS_PORT": str(redis_port),
            "REDIS_PASSWORD": secrets_.redis_password,
            "KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:1",
            "OTEL_SDK_DISABLED": "true",
            "DWP_ENVIRONMENT": "local",
            "DWP_SERVICE_INSTANCE": state.run_id,
            "DWP_API_HISTORY_ENABLED": "false",
            "DWP_AUDIT_COLLECTOR_URL": "",
            "DWP_AUDIT_INGEST_TOKEN": "",
            "DWP_PRODUCT_SURFACE_TOKEN": secrets_.product_surface_token,
            "DWP_PLATFORM_SERVICE_TOKEN": secrets_.platform_token,
            "DWP_PEOPLE_SERVICE_TOKEN": secrets_.people_token,
            "DWP_PROVIDER_SERVICE_TOKEN": secrets_.provider_token,
            "DWP_PAYROLL_SERVICE_TOKEN": secrets_.payroll_token,
            "DWP_TIME_SERVICE_TOKEN": secrets_.time_token,
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
            "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "0",
        }
    )
    return environment


def service_specs(secrets_: RuntimeSecrets) -> dict[str, ServiceSpec]:
    return {
        "auth": ServiceSpec(
            "auth",
            "dwp-auth-server",
            "dwp_auth",
            {
                "DB_NAME": "dwp_auth",
                "DWP_PRODUCT_AUTHORIZATION_PROVIDER_APPROVAL_TOKEN": secrets_.approval_token,
                "DWP_PRODUCT_AUTHORIZATION_PLATFORM_ACTIVATION_TOKEN": secrets_.activation_token,
                "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED": "false",
                "DWP_HRIS_SYSTEM_WAVE1_ENABLED": "true",
            },
        ),
        "platform": ServiceSpec(
            "platform",
            "dwp-platform-server",
            "dwp_platform",
            {
                "PLATFORM_DB_NAME": "dwp_platform",
                "DWP_HRIS_SYSTEM_WAVE1_ENABLED": "true",
                "DWP_WORKPLACE_VISIT_PROVIDER_WORKER_ENABLED": "false",
                "DWP_WORKPLACE_SERVICE_PROVIDER_WORKER_ENABLED": "false",
                "DWP_WORKPLACE_NAVIGATION_COMMAND_WORKER_ENABLED": "false",
                "DWP_WORKPLACE_SAFETY_DISPATCH_WORKER_ENABLED": "false",
                "DWP_WORKPLACE_CONNECTOR_REPLAY_WORKER_ENABLED": "false",
                "DWP_WORKPLACE_CONNECTOR_RETENTION_ENABLED": "false",
            },
        ),
        "people": ServiceSpec(
            "people",
            "dwp-people-server",
            "dwp_people",
            {
                "PEOPLE_DB_NAME": "dwp_people",
                "PEOPLE_DB_USERNAME": "dwp_people_runtime",
                "PEOPLE_DB_PASSWORD": secrets_.people_password,
                "PEOPLE_MIGRATION_DB_USERNAME": "dwp_people_migration",
                "PEOPLE_MIGRATION_DB_PASSWORD": secrets_.people_migration_password,
                "DWP_PEOPLE_HRIS_DATABASE_RUNTIME_ENVIRONMENT": "local",
                "DWP_PEOPLE_HRIS_DATABASE_SERVICE_INSTANCE": "w1-synthetic-people",
                "DWP_HRIS_PERFORMANCE_WAVE1_ENABLED": "true",
                "DWP_IDENTITY_SYNC_ENABLED": "false",
            },
        ),
        "provider": ServiceSpec(
            "provider",
            "dwp-provider-server",
            "dwp_provider",
            {
                "PROVIDER_DB_NAME": "dwp_provider",
                "AUTH_DB_NAME": "dwp_auth",
                "PEOPLE_DB_NAME": "dwp_people",
                "PLATFORM_DB_NAME": "dwp_platform",
                "DWP_METADATA_DB_USERNAME": "postgres",
                "DWP_METADATA_DB_PASSWORD": secrets_.postgres_password,
                "DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED": "false",
                "DWP_PROVIDER_SUPPORT_AUTHORITY_RECONCILIATION_ENABLED": "false",
                "DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED": "false",
                "DWP_PRODUCT_SURFACE_ROLLOUT_RELAY_ENABLED": "false",
                "DWP_PRODUCT_SURFACE_ROLLOUT_PUBLISHER_ENABLED": "false",
            },
        ),
        "payroll": ServiceSpec(
            "payroll",
            "dwp-payroll-server",
            "dwp_payroll",
            {
                "PAYROLL_DB_NAME": "dwp_payroll",
                "PAYROLL_DB_USERNAME": "dwp_payroll_runtime",
                "PAYROLL_DB_PASSWORD": secrets_.payroll_password,
                "PAYROLL_MIGRATION_DB_USERNAME": "dwp_payroll_migration",
                "PAYROLL_MIGRATION_DB_PASSWORD": secrets_.payroll_migration_password,
                "DWP_PAYROLL_DB_POOL_SIZE": "3",
                "DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED": "true",
            },
        ),
        "time": ServiceSpec(
            "time",
            "dwp-time-server",
            "dwp_time",
            {
                "TIME_DB_NAME": "dwp_time",
                "TIME_DB_USERNAME": "dwp_time_runtime",
                "TIME_DB_PASSWORD": secrets_.time_password,
                "TIME_MIGRATION_DB_USERNAME": "dwp_time_migration",
                "TIME_MIGRATION_DB_PASSWORD": secrets_.time_migration_password,
                "DWP_TIME_DB_POOL_SIZE": "3",
                "DWP_TIME_WORK_REGIME_API_ENABLED": "true",
            },
        ),
        "gateway": ServiceSpec(
            "gateway",
            "dwp-gateway",
            None,
            {
                "DWP_AGENT_SERVICE_TOKEN": secrets_.gateway_agent_service_token,
                "DWP_AGENT_IDENTITY_SIGNING_SECRET": (
                    secrets_.gateway_agent_identity_signing_secret
                ),
                "DWP_PROVIDER_SUPPORT_VALIDATION_TOKEN": (
                    secrets_.gateway_provider_support_validation_token
                ),
            },
        ),
    }


def build_jars(args: argparse.Namespace, state: GateState, specs: dict[str, ServiceSpec]) -> None:
    started_at = utc_now()
    tasks = [f":{spec.module}:bootJar" for spec in specs.values()]
    if args.skip_build:
        missing = [str(spec.jar) for spec in specs.values() if not spec.jar.is_file()]
        if missing:
            raise GateFailure("--skip-build was used but bootJars are missing: " + ", ".join(missing))
        build_provenance: dict[str, Any] = {
            "freshBuild": False,
            "diagnosticOnly": True,
            "startedAt": started_at,
            "finishedAt": utc_now(),
            "tasks": tasks,
        }
        state.phase(
            "build-bootjars",
            "SKIPPED",
            reason="--skip-build diagnostics can never produce a subgate PASS",
        )
    else:
        command = (
            args.gradle_executable,
            "--no-daemon",
            "--rerun-tasks",
            *tasks,
        )
        build_log = state.output_dir / "logs" / "build.log"
        run_checked(
            command,
            timeout=args.build_timeout,
            log_path=build_log,
        )
        build_provenance = {
            "freshBuild": True,
            "diagnosticOnly": False,
            "startedAt": started_at,
            "finishedAt": utc_now(),
            "rerunTasks": True,
            "tasks": tasks,
            "gradleExecutable": args.gradle_executable,
            "buildLogPath": str(build_log.relative_to(state.output_dir)),
            "buildLogSha256": sha256_file(build_log),
        }
        state.phase("build-bootjars", "PASS", tasks=tasks, rerunTasks=True)

    jars: dict[str, dict[str, Any]] = {}
    for name, spec in specs.items():
        if (
            not spec.jar.is_file()
            or spec.jar.is_symlink()
            or not stat.S_ISREG(spec.jar.stat().st_mode)
        ):
            raise GateFailure(f"Executable bootJar is not a regular file: {spec.jar}")
        jars[name] = {
            "path": str(spec.jar.relative_to(ROOT)),
            "sha256": sha256_file(spec.jar),
            "byteCount": spec.jar.stat().st_size,
        }
    state.provenance["build"] = build_provenance
    state.provenance["jars"] = jars


def provision_infrastructure(
    state: GateState, secrets_: RuntimeSecrets, args: argparse.Namespace
) -> tuple[str, str, int, int]:
    run_checked(("docker", "version"), timeout=30)
    network = resource_name(state.run_id, "network")
    postgres = resource_name(state.run_id, "postgres")
    redis = resource_name(state.run_id, "redis")
    docker("network", "create", "--label", f"dwp.synthetic-run={state.run_id}", network)
    state.network = network
    docker(
        "run",
        "-d",
        "--rm",
        "--name",
        postgres,
        "--network",
        network,
        "--label",
        f"dwp.synthetic-run={state.run_id}",
        "-e",
        "POSTGRES_USER=postgres",
        "-e",
        f"POSTGRES_PASSWORD={secrets_.postgres_password}",
        "-e",
        "POSTGRES_DB=postgres",
        "-p",
        "127.0.0.1::5432",
        "--tmpfs",
        "/var/lib/postgresql:rw,nosuid,size=1g",
        args.postgres_image,
    )
    state.containers.append(postgres)
    docker(
        "run",
        "-d",
        "--rm",
        "--name",
        redis,
        "--network",
        network,
        "--label",
        f"dwp.synthetic-run={state.run_id}",
        "-p",
        "127.0.0.1::6379",
        args.redis_image,
        "redis-server",
        "--save",
        "",
        "--appendonly",
        "no",
        "--requirepass",
        secrets_.redis_password,
    )
    state.containers.append(redis)
    wait_for_postgres(postgres, args.startup_timeout)
    postgres_port = docker_port(postgres, 5432)
    redis_port = docker_port(redis, 6379)
    state.ports.update({"postgres": postgres_port, "redis": redis_port})
    state.phase(
        "provision-disposable-infrastructure",
        "PASS",
        postgres=postgres,
        redis=redis,
        storage="tmpfs/no named volumes",
        bind="127.0.0.1",
    )
    return postgres, redis, postgres_port, redis_port


def provision_databases(postgres: str, secrets_: RuntimeSecrets) -> None:
    for database in (
        "dwp_auth",
        "dwp_auth_latest_clean",
        "dwp_platform",
        "dwp_people",
        "dwp_provider",
        "dwp_payroll",
        "dwp_time",
    ):
        create_database(postgres, database)
    for role, password in (
        ("dwp_people_runtime", secrets_.people_password),
        ("dwp_people_migration", secrets_.people_migration_password),
        ("dwp_payroll_runtime", secrets_.payroll_password),
        ("dwp_payroll_migration", secrets_.payroll_migration_password),
        ("dwp_time_runtime", secrets_.time_password),
        ("dwp_time_migration", secrets_.time_migration_password),
    ):
        create_role(postgres, role, password)
    psql(
        postgres,
        "postgres",
        """
        REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
        REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
        REVOKE CONNECT ON DATABASE template0 FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_auth FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_auth_latest_clean FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_platform FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_people FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_provider FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_payroll FROM PUBLIC;
        REVOKE CONNECT ON DATABASE dwp_time FROM PUBLIC;
        """,
    )
    configure_strict_database_roles(
        postgres,
        database="dwp_people",
        runtime_role="dwp_people_runtime",
        migration_role="dwp_people_migration",
        auxiliary_schemas=("hris_performance",),
    )
    configure_strict_database_roles(
        postgres,
        database="dwp_payroll",
        runtime_role="dwp_payroll_runtime",
        migration_role="dwp_payroll_migration",
    )
    configure_strict_database_roles(
        postgres,
        database="dwp_time",
        runtime_role="dwp_time_runtime",
        migration_role="dwp_time_migration",
    )


def configure_strict_database_roles(
    postgres: str,
    *,
    database: str,
    runtime_role: str,
    migration_role: str,
    auxiliary_schemas: Sequence[str] = (),
) -> None:
    identifiers = (database, runtime_role, migration_role, *auxiliary_schemas)
    if not all(re.fullmatch(r"[a-z][a-z0-9_]{1,62}", value) for value in identifiers):
        raise GateFailure(f"Unsafe strict database boundary identifiers: {identifiers}")
    for candidate in (
        "dwp_auth",
        "dwp_auth_latest_clean",
        "dwp_platform",
        "dwp_people",
        "dwp_provider",
        "dwp_payroll",
        "dwp_time",
    ):
        psql(
            postgres,
            "postgres",
            f'REVOKE CONNECT ON DATABASE "{candidate}" FROM "{runtime_role}"; '
            f'REVOKE CONNECT ON DATABASE "{candidate}" FROM "{migration_role}";',
        )
    psql(
        postgres,
        "postgres",
        f'REVOKE CONNECT, TEMPORARY, CREATE ON DATABASE "{database}" FROM PUBLIC; '
        f'REVOKE TEMPORARY, CREATE ON DATABASE "{database}" FROM "{runtime_role}"; '
        f'REVOKE TEMPORARY, CREATE ON DATABASE "{database}" FROM "{migration_role}"; '
        f'GRANT CONNECT ON DATABASE "{database}" TO "{runtime_role}"; '
        f'GRANT CONNECT ON DATABASE "{database}" TO "{migration_role}"; '
        f'ALTER ROLE "{runtime_role}" IN DATABASE "{database}" '
        f'SET search_path TO pg_catalog, public; '
        f'ALTER ROLE "{migration_role}" IN DATABASE "{database}" '
        f'SET search_path TO pg_catalog, public;',
    )
    schema_statements = [
        "DROP SCHEMA public CASCADE",
        f'CREATE SCHEMA public AUTHORIZATION "{migration_role}"',
        "REVOKE ALL ON SCHEMA public FROM PUBLIC",
        f'GRANT USAGE ON SCHEMA public TO "{runtime_role}"',
    ]
    for schema in auxiliary_schemas:
        schema_statements.extend(
            (
                f'DROP SCHEMA IF EXISTS "{schema}" CASCADE',
                f'CREATE SCHEMA "{schema}" AUTHORIZATION "{migration_role}"',
                f'REVOKE ALL ON SCHEMA "{schema}" FROM PUBLIC',
                f'GRANT USAGE ON SCHEMA "{schema}" TO "{runtime_role}"',
            )
        )
    psql(postgres, database, "; ".join(schema_statements) + ";")


def auth_headers(secrets_: RuntimeSecrets, lane: str) -> dict[str, str]:
    if lane == "approval":
        return {
            "X-DWP-Service-Identity": APPROVAL_IDENTITY,
            "X-DWP-Product-Authorization-Approval-Token": secrets_.approval_token,
        }
    if lane == "activation":
        return {
            "X-DWP-Service-Identity": ACTIVATION_IDENTITY,
            "X-DWP-Product-Authorization-Activation-Token": secrets_.activation_token,
        }
    raise AssertionError(f"Unknown operations lane: {lane}")


def assert_fields(value: dict[str, Any] | None, expected: dict[str, Any], label: str) -> None:
    if value is None:
        raise GateFailure(f"{label} did not return a JSON object")
    mismatches = {
        key: {"expected": expected_value, "actual": value.get(key)}
        for key, expected_value in expected.items()
        if value.get(key) != expected_value
    }
    if mismatches:
        raise GateFailure(f"{label} response mismatch: {mismatches}")


def start_auth_at_v232(
    state: GateState,
    spec: ServiceSpec,
    base_environment: dict[str, str],
    timeout: float,
) -> None:
    environment = base_environment.copy()
    environment.update(spec.extra_environment)
    environment.update(
        {
            "SERVER_PORT": str(state.ports["auth"]),
            "SPRING_FLYWAY_TARGET": "232",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ONLY_VERSION": "32",
        }
    )
    start_jar(state, spec, environment, timeout, runtime_name="auth-v232")


def start_and_verify_latest_clean_auth(
    state: GateState,
    spec: ServiceSpec,
    base_environment: dict[str, str],
    postgres: str,
    timeout: float,
) -> None:
    environment = base_environment.copy()
    environment.update(spec.extra_environment)
    environment.update(
        {
            "SERVER_PORT": str(state.ports["auth"]),
            "DB_NAME": "dwp_auth_latest_clean",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "false",
        }
    )
    start_jar(
        state,
        spec,
        environment,
        timeout,
        runtime_name="auth-latest-clean",
    )
    raw = psql(
        postgres,
        "dwp_auth_latest_clean",
        """
        SELECT version || '|' || success::text
          FROM flyway_schema_history
         WHERE version IN ('232', '233')
         ORDER BY installed_rank;
        SELECT version || '|' || checksum
          FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version IN (32, 33)
         ORDER BY version;
        SELECT 'bundle-total|' || count(*)::text
          FROM auth_product_authorization_bundle;
        SELECT 'active-total|' || count(*)::text
          FROM auth_product_authorization_active;
        """,
    )
    (state.output_dir / "db" / "latest-clean-state.txt").write_text(
        raw + "\n", encoding="utf-8"
    )
    expected = [
        "232|true",
        "233|true",
        f"32|{V32_CHECKSUM}",
        f"33|{V33_CHECKSUM}",
        "bundle-total|0",
        "active-total|0",
    ]
    actual = [line.strip() for line in raw.splitlines() if line.strip()]
    if actual != expected:
        raise GateFailure(
            f"Latest clean-install state mismatch: expected {expected}, got {actual}"
        )
    state.phase(
        "latest-clean-install-through-v233",
        "PASS",
        isolatedDatabase="dwp_auth_latest_clean",
        seedsImported=False,
        bundleCount=0,
        activePointerCount=0,
    )


def verify_v232(postgres: str, state: GateState) -> None:
    raw = psql(
        postgres,
        "dwp_auth",
        """
        SELECT version || '|' || success::text
          FROM flyway_schema_history
         WHERE version IN ('232', '233')
         ORDER BY installed_rank;
        SELECT version || '|' || checksum
          FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version IN (32, 33)
         ORDER BY version;
        SELECT 'bundle-total|' || count(*)::text
          FROM auth_product_authorization_bundle;
        SELECT 'bundle|' || bundle_key || '|' || version::text || '|' ||
               bundle_status || '|' || checksum
          FROM auth_product_authorization_bundle
         ORDER BY bundle_key, version;
        SELECT 'active-total|' || count(*)::text
          FROM auth_product_authorization_active;
        """,
    )
    (state.output_dir / "db" / "v232-state.txt").write_text(raw + "\n", encoding="utf-8")
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    expected = [
        "232|true",
        f"32|{V32_CHECKSUM}",
        "bundle-total|1",
        f"bundle|product-surfaces|32|DRAFT|{V32_CHECKSUM}",
        "active-total|0",
    ]
    if lines != expected:
        raise GateFailure(f"V232 clean-install state mismatch: expected {expected}, got {lines}")
    state.phase(
        "clean-install-through-v232-with-v32-draft",
        "PASS",
        imported=[32],
        bundleCount=1,
        exactBundles=[{"bundleKey": BUNDLE_KEY, "version": 32}],
        v33Absent=True,
        activePointerAbsent=True,
    )


def start_auth_latest(
    state: GateState,
    spec: ServiceSpec,
    base_environment: dict[str, str],
    postgres: str,
    secrets_: RuntimeSecrets,
    timeout: float,
) -> None:
    environment = base_environment.copy()
    environment.update(spec.extra_environment)
    environment.update(
        {
            "SERVER_PORT": str(state.ports["auth"]),
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ONLY_VERSION": "33",
        }
    )
    start_jar(state, spec, environment, timeout)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        count = psql(
            postgres,
            "dwp_auth",
            "SELECT count(*) FROM auth_product_authorization_bundle;",
        ).strip()
        if count == "2":
            break
        process = state.processes["auth"].process
        if process.poll() is not None:
            raise GateFailure("Auth stopped while importing immutable product authorization seeds.")
        time.sleep(0.5)
    else:
        raise GateFailure("Auth did not import v32 and v33 before the startup timeout.")

    declaration = psql(
        postgres,
        "dwp_auth",
        """
        SELECT version || '|' || success::text
          FROM flyway_schema_history
         WHERE version IN ('232', '233')
         ORDER BY installed_rank;
        SELECT version || '|' || checksum
          FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version IN (32, 33)
         ORDER BY version;
        SELECT 'bundle-total|' || count(*)::text
          FROM auth_product_authorization_bundle;
        SELECT 'bundle|' || bundle_key || '|' || version::text || '|' ||
               bundle_status || '|' || checksum
          FROM auth_product_authorization_bundle
         ORDER BY bundle_key, version;
        SELECT 'active-total|' || count(*)::text
          FROM auth_product_authorization_active;
        SELECT 'active|' || bundle.version::text || '|' || active.revision::text ||
               '|' || active.activated_by
          FROM auth_product_authorization_active active
          JOIN auth_product_authorization_bundle bundle
            ON bundle.bundle_id = active.bundle_id
           AND bundle.bundle_key = active.bundle_key
         WHERE active.bundle_key = 'product-surfaces';
        """,
    )
    (state.output_dir / "db" / "v233-state.txt").write_text(
        declaration + "\n", encoding="utf-8"
    )
    expected = [
        "232|true",
        "233|true",
        f"32|{V32_CHECKSUM}",
        f"33|{V33_CHECKSUM}",
        "bundle-total|2",
        f"bundle|product-surfaces|32|ACTIVE|{V32_CHECKSUM}",
        f"bundle|product-surfaces|33|DRAFT|{V33_CHECKSUM}",
        "active-total|1",
        "active|32|1|w1-synthetic-release-v32",
    ]
    actual = [line.strip() for line in declaration.splitlines() if line.strip()]
    if actual != expected:
        raise GateFailure(f"V233 upgrade state mismatch: expected {expected}, got {actual}")
    _, active, _ = http_request(
        state,
        name="04-active-v32-preserved-after-v233-upgrade",
        port=state.ports["auth"],
        method="GET",
        path=f"{OPERATIONS_PATH}/{BUNDLE_KEY}/active",
        headers=auth_headers(secrets_, "activation"),
    )
    assert_fields(
        active,
        {
            "version": 32,
            "bundleStatus": "ACTIVE",
            "activeRevision": 1,
            "checksum": V32_CHECKSUM,
        },
        "active v32 pointer preserved across V232 to V233 upgrade",
    )
    state.phase(
        "upgrade-active-v32-r1-from-v232-to-v233",
        "PASS",
        imported=[33],
        bundleCount=2,
        exactBundles=[
            {"bundleKey": BUNDLE_KEY, "version": 32},
            {"bundleKey": BUNDLE_KEY, "version": 33},
        ],
        preservedVersion=32,
        preservedRevision=1,
    )


def lifecycle_approve_activate_v32_at_v232(
    state: GateState, secrets_: RuntimeSecrets
) -> None:
    port = state.ports["auth"]
    base = f"{OPERATIONS_PATH}/{BUNDLE_KEY}"

    http_request(
        state,
        name="00-unauthorized-v32-approval-denied",
        port=port,
        method="POST",
        path=f"{base}/versions/32/approval",
        payload={
            "checksum": V32_CHECKSUM,
            "requestedBy": "w1-synthetic-maker-v32",
            "approvedBy": "w1-synthetic-checker-v32",
            "changeRef": "CHG-W1-SYNTHETIC-V32",
        },
        expected_status=401,
    )

    _, v32_approval, _ = http_request(
        state,
        name="01-approve-v32",
        port=port,
        method="POST",
        path=f"{base}/versions/32/approval",
        headers=auth_headers(secrets_, "approval"),
        payload={
            "checksum": V32_CHECKSUM,
            "requestedBy": "w1-synthetic-maker-v32",
            "approvedBy": "w1-synthetic-checker-v32",
            "changeRef": "CHG-W1-SYNTHETIC-V32",
        },
    )
    assert_fields(
        v32_approval,
        {"version": 32, "bundleStatus": "APPROVED", "checksum": V32_CHECKSUM},
        "v32 approval",
    )

    _, v32_activation, _ = http_request(
        state,
        name="02-activate-v32",
        port=port,
        method="POST",
        path=f"{base}/versions/32/activation",
        headers=auth_headers(secrets_, "activation"),
        payload={
            "checksum": V32_CHECKSUM,
            "expectedRevision": 0,
            "activatedBy": "w1-synthetic-release-v32",
            "changeRef": "CHG-W1-SYNTHETIC-V32",
        },
    )
    assert_fields(
        v32_activation,
        {"version": 32, "operation": "ACTIVATE", "revision": 1, "checksum": V32_CHECKSUM},
        "v32 activation",
    )
    state.active_version = 32
    state.active_revision = 1

    _, active, _ = http_request(
        state,
        name="03-active-v32-before-v233-upgrade",
        port=port,
        method="GET",
        path=f"{base}/active",
        headers=auth_headers(secrets_, "activation"),
    )
    assert_fields(
        active,
        {
            "version": 32,
            "bundleStatus": "ACTIVE",
            "activeRevision": 1,
            "checksum": V32_CHECKSUM,
        },
        "active v32 pointer before V233 upgrade",
    )
    state.phase(
        "governed-v32-http-activation-at-v232",
        "PASS",
        maker="w1-synthetic-maker-v32",
        checker="w1-synthetic-checker-v32",
        activator="w1-synthetic-release-v32",
        revision=1,
    )


def lifecycle_approve_activate_v33_after_upgrade(
    state: GateState, secrets_: RuntimeSecrets
) -> None:
    if state.active_version != 32 or state.active_revision != 1:
        raise GateFailure(
            "V33 activation requires the preserved v32 revision 1 active pointer."
        )
    port = state.ports["auth"]
    base = f"{OPERATIONS_PATH}/{BUNDLE_KEY}"

    http_request(
        state,
        name="05-unauthorized-v33-approval-denied",
        port=port,
        method="POST",
        path=f"{base}/versions/33/approval",
        payload={
            "checksum": V33_CHECKSUM,
            "requestedBy": "w1-synthetic-maker-v33",
            "approvedBy": "w1-synthetic-checker-v33",
            "changeRef": "CHG-W1-SYNTHETIC-V33",
        },
        expected_status=401,
    )

    _, v33_approval, _ = http_request(
        state,
        name="06-approve-v33",
        port=port,
        method="POST",
        path=f"{base}/versions/33/approval",
        headers=auth_headers(secrets_, "approval"),
        payload={
            "checksum": V33_CHECKSUM,
            "requestedBy": "w1-synthetic-maker-v33",
            "approvedBy": "w1-synthetic-checker-v33",
            "changeRef": "CHG-W1-SYNTHETIC-V33",
        },
    )
    assert_fields(
        v33_approval,
        {"version": 33, "bundleStatus": "APPROVED", "checksum": V33_CHECKSUM},
        "v33 approval",
    )

    http_request(
        state,
        name="07-stale-v33-cas-denied",
        port=port,
        method="POST",
        path=f"{base}/versions/33/activation",
        headers=auth_headers(secrets_, "activation"),
        payload={
            "checksum": V33_CHECKSUM,
            "expectedRevision": 0,
            "activatedBy": "w1-synthetic-stale-release-v33",
            "changeRef": "CHG-W1-SYNTHETIC-V33",
        },
        expected_status=409,
    )

    _, v33_activation, _ = http_request(
        state,
        name="08-activate-v33",
        port=port,
        method="POST",
        path=f"{base}/versions/33/activation",
        headers=auth_headers(secrets_, "activation"),
        payload={
            "checksum": V33_CHECKSUM,
            "expectedRevision": 1,
            "activatedBy": "w1-synthetic-release-v33",
            "changeRef": "CHG-W1-SYNTHETIC-V33",
        },
    )
    assert_fields(
        v33_activation,
        {"version": 33, "operation": "ACTIVATE", "revision": 2, "checksum": V33_CHECKSUM},
        "v33 activation",
    )
    state.active_version = 33
    state.active_revision = 2

    _, active, _ = http_request(
        state,
        name="09-active-v33",
        port=port,
        method="GET",
        path=f"{base}/active",
        headers=auth_headers(secrets_, "activation"),
    )
    assert_fields(
        active,
        {"version": 33, "bundleStatus": "ACTIVE", "activeRevision": 2, "checksum": V33_CHECKSUM},
        "active v33 pointer",
    )
    state.phase(
        "governed-v33-http-activation",
        "PASS",
        maker="w1-synthetic-maker-v33",
        checker="w1-synthetic-checker-v33",
        activator="w1-synthetic-release-v33",
        revision=2,
        stale_cas_denied=True,
    )


def rollback_v33_to_v32(state: GateState, secrets_: RuntimeSecrets) -> None:
    if state.rollback_complete:
        return
    if state.active_version != 33 or state.active_revision != 2:
        raise GateFailure(
            f"Refusing rollback from unexpected pointer: v{state.active_version} r{state.active_revision}"
        )
    port = state.ports["auth"]
    base = f"{OPERATIONS_PATH}/{BUNDLE_KEY}"
    _, rollback, _ = http_request(
        state,
        name="90-rollback-to-v32",
        port=port,
        method="POST",
        path=f"{base}/versions/32/rollback",
        headers=auth_headers(secrets_, "activation"),
        payload={
            "checksum": V32_CHECKSUM,
            "expectedRevision": 2,
            "rolledBackBy": "w1-synthetic-incident-operator",
            "changeRef": "INC-W1-SYNTHETIC-V33",
            "reason": "Synthetic acceptance rollback from v33 to the proven v32 baseline.",
        },
    )
    assert_fields(
        rollback,
        {"version": 32, "operation": "ROLLBACK", "revision": 3, "checksum": V32_CHECKSUM},
        "v33 to v32 rollback",
    )
    state.active_version = 32
    state.active_revision = 3
    _, active, _ = http_request(
        state,
        name="91-active-v32-after-rollback",
        port=port,
        method="GET",
        path=f"{base}/active",
        headers=auth_headers(secrets_, "activation"),
    )
    assert_fields(
        active,
        {"version": 32, "bundleStatus": "ACTIVE", "activeRevision": 3, "checksum": V32_CHECKSUM},
        "active v32 pointer after rollback",
    )
    state.rollback_complete = True
    state.phase("cas-rollback-v33-to-v32", "PASS", revision=3)


def start_remaining_services(
    state: GateState,
    specs: dict[str, ServiceSpec],
    base_environment: dict[str, str],
    args: argparse.Namespace,
) -> None:
    urls = {
        name: f"http://127.0.0.1:{state.ports[name]}"
        for name in ("auth", "platform", "people", "provider", "payroll", "time", "gateway")
    }
    shared_urls = {
        "SERVICE_AUTH_URL": urls["auth"],
        "SERVICE_PLATFORM_URL": urls["platform"],
        "SERVICE_PEOPLE_URL": urls["people"],
        "SERVICE_PROVIDER_URL": urls["provider"],
        "SERVICE_PAYROLL_URL": urls["payroll"],
        "SERVICE_TIME_URL": urls["time"],
        "SERVICE_GATEWAY_URL": urls["gateway"],
    }
    for name in ("platform", "people", "provider", "payroll", "time", "gateway"):
        spec = specs[name]
        environment = base_environment.copy()
        environment.update(shared_urls)
        environment.update(spec.extra_environment)
        environment["SERVER_PORT"] = str(state.ports[name])
        if name == "time":
            environment["DWP_TIME_SERVER_PORT"] = str(state.ports[name])
        elif name == "payroll":
            environment["DWP_PAYROLL_SERVER_PORT"] = str(state.ports[name])
        start_jar(state, spec, environment, args.startup_timeout)
    state.phase(
        "required-services-up",
        "PASS",
        services=["auth", "platform", "people", "provider", "payroll", "time", "gateway"],
    )


def write_runtime_manifest(state: GateState) -> None:
    manifest = {
        "schemaVersion": 1,
        "runId": state.run_id,
        "syntheticOnly": True,
        "secretsPersisted": False,
        "activeBundle": {"version": state.active_version, "revision": state.active_revision},
        "endpoints": {
            name: f"http://127.0.0.1:{port}"
            for name, port in state.ports.items()
            if name not in {"postgres", "redis"}
        },
    }
    atomic_write_json(state.output_dir / "runtime.json", manifest)


def validate_checkpoint_executable(
    command: Sequence[str] | None, expected_sha256: str | None
) -> dict[str, Any]:
    if not command:
        raise GateFailure(
            "Full W1 backend mode requires an external live checkpoint command."
        )
    executable = Path(command[0])
    if not executable.is_absolute():
        raise GateFailure("Checkpoint executable must be an absolute path.")
    if executable.is_symlink():
        raise GateFailure("Checkpoint executable must not be a symlink.")
    try:
        metadata = executable.stat()
    except OSError as error:
        raise GateFailure(f"Checkpoint executable is unavailable: {error}") from error
    if not stat.S_ISREG(metadata.st_mode) or not os.access(executable, os.X_OK):
        raise GateFailure(
            "Checkpoint executable must be an executable regular file."
        )
    if not isinstance(expected_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", expected_sha256
    ):
        raise GateFailure(
            "Full W1 backend mode requires a lowercase SHA-256 pin for the "
            "checkpoint executable."
        )
    actual_sha256 = sha256_file(executable)
    if actual_sha256 != expected_sha256:
        raise GateFailure(
            "Checkpoint executable SHA-256 does not match the supplied pin."
        )
    return {
        "path": str(executable),
        "sha256": actual_sha256,
        "byteCount": metadata.st_size,
        "nonSymlinkRegularExecutable": True,
    }


def process_group_alive(process_group_id: int) -> bool:
    try:
        os.killpg(process_group_id, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def wait_for_process_group_exit(process_group_id: int, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not process_group_alive(process_group_id):
            return True
        time.sleep(0.05)
    return not process_group_alive(process_group_id)


def cleanup_checkpoint_process_group(
    process_group_id: int,
    parent: subprocess.Popen[bytes] | None = None,
) -> dict[str, Any]:
    residual_detected = process_group_alive(process_group_id)
    term_sent = False
    kill_sent = False
    if residual_detected:
        try:
            os.killpg(process_group_id, signal.SIGTERM)
            term_sent = True
        except ProcessLookupError:
            pass
        if parent is not None and parent.poll() is None:
            try:
                parent.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass
        if not wait_for_process_group_exit(process_group_id, 2):
            try:
                os.killpg(process_group_id, signal.SIGKILL)
                kill_sent = True
            except ProcessLookupError:
                pass
            if parent is not None and parent.poll() is None:
                try:
                    parent.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
            wait_for_process_group_exit(process_group_id, 3)
    if parent is not None and parent.poll() is None:
        try:
            parent.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
    return {
        "processGroupId": process_group_id,
        "residualDetected": residual_detected,
        "termSent": term_sent,
        "killSent": kill_sent,
        "cleanupVerified": not process_group_alive(process_group_id),
    }


def run_checkpoint_process(
    state: GateState,
    command: Sequence[str],
    environment: dict[str, str],
    log_path: Path,
    timeout: float,
) -> dict[str, Any]:
    timed_out = False
    with log_path.open("wb") as log_handle:
        process = subprocess.Popen(
            tuple(command),
            cwd=ROOT,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        process_group_id = process.pid
        state.checkpoint_process_groups.append(process_group_id)
        try:
            return_code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            return_code = None
        cleanup = cleanup_checkpoint_process_group(process_group_id, process)
        log_handle.flush()

    outcome = {
        "exitCode": return_code,
        "timedOut": timed_out,
        **cleanup,
    }
    state.provenance.setdefault("checkpoint", {})["processGroup"] = outcome
    if cleanup["cleanupVerified"]:
        state.checkpoint_process_groups.remove(process_group_id)
    if timed_out:
        raise GateFailure(
            "External live checkpoint timed out; process-group cleanup "
            f"verified={cleanup['cleanupVerified']}."
        )
    if return_code != 0:
        raise GateFailure(
            f"External live checkpoint exited with {return_code}; process-group "
            f"cleanup verified={cleanup['cleanupVerified']}."
        )
    if cleanup["residualDetected"]:
        raise GateFailure(
            "External live checkpoint left descendant processes after exit; "
            f"cleanup verified={cleanup['cleanupVerified']}."
        )
    if not cleanup["cleanupVerified"]:
        raise GateFailure(
            "External live checkpoint process-group cleanup was not verified."
        )
    return outcome


def validate_checkpoint_manifest(
    state: GateState, manifest_path: Path
) -> dict[str, Any]:
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise GateFailure(
            "External live checkpoint did not create its required regular-file manifest: "
            f"{manifest_path}"
        )
    manifest_bytes = manifest_path.read_bytes()
    if not manifest_bytes or len(manifest_bytes) > MAX_CHECKPOINT_MANIFEST_BYTES:
        raise GateFailure("External live checkpoint manifest is empty or too large.")
    try:
        manifest = json.loads(manifest_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GateFailure(
            f"External live checkpoint manifest is not valid UTF-8 JSON: {error}"
        ) from error
    if not isinstance(manifest, dict):
        raise GateFailure("External live checkpoint manifest must be a JSON object.")

    expected_endpoints = {
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
    }
    expected_fields = {
        "schemaVersion": 1,
        "runId": state.run_id,
        "syntheticOnly": True,
        "status": "PASS",
        "activeBundle": {"version": 33, "revision": 2},
        "endpoints": expected_endpoints,
    }
    mismatches = {
        key: {"expected": expected, "actual": manifest.get(key)}
        for key, expected in expected_fields.items()
        if manifest.get(key) != expected
    }
    if mismatches:
        raise GateFailure(
            f"External live checkpoint manifest binding mismatch: {mismatches}"
        )

    assertions = manifest.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        raise GateFailure(
            "External live checkpoint manifest requires evidence-backed assertions."
        )
    names: list[str] = []
    evidence_paths: set[str] = set()
    evidence_candidates: set[Path] = set()
    evidence_bindings: dict[str, dict[str, Any]] = {}
    for index, assertion in enumerate(assertions):
        if not isinstance(assertion, dict):
            raise GateFailure(f"Checkpoint assertion {index} must be a JSON object.")
        name = assertion.get("name")
        status = assertion.get("status")
        evidence_path = assertion.get("evidencePath")
        evidence_sha256 = assertion.get("evidenceSha256")
        if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9._-]{2,119}", name):
            raise GateFailure(f"Checkpoint assertion {index} has an unsafe name.")
        if name in names:
            raise GateFailure(f"Checkpoint assertion name is duplicated: {name}")
        if status != "PASS":
            raise GateFailure(f"Checkpoint assertion did not pass: {name}")
        if not isinstance(evidence_path, str) or not evidence_path:
            raise GateFailure(f"Checkpoint assertion has no evidence path: {name}")
        if evidence_path in evidence_paths:
            raise GateFailure(
                "Every checkpoint assertion requires a distinct evidence path: "
                f"{evidence_path}"
            )
        evidence_paths.add(evidence_path)
        evidence_relative = Path(evidence_path)
        if evidence_relative.is_absolute() or ".." in evidence_relative.parts:
            raise GateFailure(f"Checkpoint assertion evidence path is unsafe: {name}")
        unresolved_candidate = state.output_dir / evidence_relative
        if unresolved_candidate.is_symlink():
            raise GateFailure(
                f"Checkpoint assertion evidence cannot be a symlink: {name}"
            )
        candidate = unresolved_candidate.resolve()
        try:
            candidate.relative_to(state.output_dir.resolve())
        except ValueError as error:
            raise GateFailure(
                f"Checkpoint assertion evidence escapes the run directory: {name}"
            ) from error
        if candidate == manifest_path.resolve() or not candidate.is_file():
            raise GateFailure(
                f"Checkpoint assertion evidence must be a separate regular file: {name}"
            )
        if candidate in evidence_candidates:
            raise GateFailure(
                "Every checkpoint assertion requires a distinct evidence file: "
                f"{candidate}"
            )
        evidence_candidates.add(candidate)
        if candidate.stat().st_size == 0:
            raise GateFailure(f"Checkpoint assertion evidence is empty: {name}")
        if not isinstance(evidence_sha256, str) or not re.fullmatch(
            r"[0-9a-f]{64}", evidence_sha256
        ):
            raise GateFailure(f"Checkpoint assertion digest is invalid: {name}")
        evidence_bytes = candidate.read_bytes()
        actual_sha256 = hashlib.sha256(evidence_bytes).hexdigest()
        if actual_sha256 != evidence_sha256:
            raise GateFailure(f"Checkpoint assertion digest mismatch: {name}")
        try:
            evidence = json.loads(evidence_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise GateFailure(
                f"Checkpoint assertion evidence is not valid UTF-8 JSON: {name}: {error}"
            ) from error
        if not isinstance(evidence, dict):
            raise GateFailure(
                f"Checkpoint assertion evidence must be a JSON object: {name}"
            )
        expected_evidence_fields = {
            "schemaVersion": 1,
            "runId": state.run_id,
            "syntheticOnly": True,
            "assertionName": name,
            "status": "PASS",
            "activeBundle": {"version": 33, "revision": 2},
            "endpoints": expected_endpoints,
        }
        evidence_mismatches = {
            key: {"expected": expected, "actual": evidence.get(key)}
            for key, expected in expected_evidence_fields.items()
            if evidence.get(key) != expected
        }
        if evidence_mismatches:
            raise GateFailure(
                f"Checkpoint assertion evidence binding mismatch for {name}: "
                f"{evidence_mismatches}"
            )
        observations = evidence.get("observations")
        if (
            not isinstance(observations, list)
            or not observations
            or any(not isinstance(item, dict) or not item for item in observations)
        ):
            raise GateFailure(
                f"Checkpoint assertion evidence requires non-empty object observations: {name}"
            )
        evidence_bindings[name] = {
            "path": evidence_path,
            "sha256": actual_sha256,
            "byteCount": candidate.stat().st_size,
        }
        names.append(name)
    missing = sorted(set(REQUIRED_CHECKPOINT_ASSERTIONS) - set(names))
    unexpected = sorted(set(names) - set(REQUIRED_CHECKPOINT_ASSERTIONS))
    if missing or unexpected or len(names) != len(REQUIRED_CHECKPOINT_ASSERTIONS):
        raise GateFailure(
            "External live checkpoint manifest assertion set mismatch: "
            f"missing={missing}, unexpected={unexpected}"
        )
    return {
        "manifest": {
            "path": str(manifest_path.relative_to(state.output_dir)),
            "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "byteCount": len(manifest_bytes),
        },
        "assertionEvidence": evidence_bindings,
    }


def run_checkpoint(
    state: GateState, command: Sequence[str] | None, timeout: float
) -> None:
    if not command:
        raise GateFailure(
            "Full W1 backend mode requires an external live checkpoint command."
        )
    checkpoint_provenance = state.provenance.setdefault("checkpoint", {})
    pinned_executable = checkpoint_provenance.get("executable")
    if not isinstance(pinned_executable, dict):
        raise GateFailure("Checkpoint executable provenance was not established.")
    immediate_executable = validate_checkpoint_executable(
        command, pinned_executable.get("sha256")
    )
    if immediate_executable != pinned_executable:
        raise GateFailure(
            "Checkpoint executable provenance changed before execution."
        )
    checkpoint_provenance["verifiedImmediatelyBeforeExecution"] = True
    checkpoint_dir = state.output_dir / "checkpoint"
    checkpoint_dir.mkdir(mode=0o700, exist_ok=False)
    manifest_path = checkpoint_dir / "manifest.json"
    environment = allowlisted_host_environment()
    environment.update(
        {
            "DWP_W1_RUN_ID": state.run_id,
            "DWP_W1_EVIDENCE_DIR": str(state.output_dir),
            "DWP_W1_CHECKPOINT_MANIFEST": str(manifest_path),
            "DWP_W1_ACTIVE_BUNDLE_VERSION": str(state.active_version),
            "DWP_W1_ACTIVE_BUNDLE_REVISION": str(state.active_revision),
        }
    )
    for name in (
        "auth",
        "platform",
        "people",
        "provider",
        "payroll",
        "time",
        "gateway",
    ):
        environment[f"DWP_W1_{name.upper()}_URL"] = (
            f"http://127.0.0.1:{state.ports[name]}"
        )
    process_outcome = run_checkpoint_process(
        state,
        command,
        environment,
        state.output_dir / "logs" / "external-live-checkpoint.log",
        timeout,
    )
    evidence_provenance = validate_checkpoint_manifest(state, manifest_path)
    checkpoint_provenance.update(evidence_provenance)
    checkpoint_provenance["processGroup"] = process_outcome
    command_arguments_sha256 = hashlib.sha256(
        "\0".join(command).encode("utf-8")
    ).hexdigest()
    checkpoint_provenance["commandArgumentsSha256"] = command_arguments_sha256
    state.phase(
        "external-live-checkpoint",
        "PASS",
        executable=checkpoint_provenance.get("executable"),
        commandArgumentsSha256=command_arguments_sha256,
        manifest=evidence_provenance["manifest"],
        assertionEvidence=evidence_provenance["assertionEvidence"],
        processGroup=process_outcome,
    )


def capture_governance_evidence(postgres: str, state: GateState) -> None:
    governance = psql(
        postgres,
        "dwp_auth",
        """
        SELECT operation || '|' || version::text || '|' || checksum || '|' ||
               COALESCE(expected_revision::text, '') || '|' ||
               COALESCE(resulting_revision::text, '') || '|' || requester_ref || '|' ||
               decision_actor_ref || '|' || change_ref || '|' ||
               COALESCE(reason, '') || '|' || caller_service_identity
          FROM auth_product_authorization_governance_event
         WHERE bundle_key = 'product-surfaces' AND version IN (32, 33)
         ORDER BY occurred_at, governance_event_id;
        """,
    )
    activation = psql(
        postgres,
        "dwp_auth",
        """
        SELECT operation || '|' || expected_revision::text || '|' ||
               resulting_revision::text || '|' || actor_ref
          FROM auth_product_authorization_activation_event
         WHERE bundle_key = 'product-surfaces'
         ORDER BY resulting_revision;
        """,
    )
    (state.output_dir / "db" / "governance-events.txt").write_text(
        governance + "\n", encoding="utf-8"
    )
    (state.output_dir / "db" / "activation-events.txt").write_text(
        activation + "\n", encoding="utf-8"
    )
    governance_lines = [line for line in governance.splitlines() if line.strip()]
    activation_lines = [line for line in activation.splitlines() if line.strip()]
    expected_governance = [
        f"APPROVE|32|{V32_CHECKSUM}|||w1-synthetic-maker-v32|"
        "w1-synthetic-checker-v32|CHG-W1-SYNTHETIC-V32||dwp-provider-server",
        f"ACTIVATE|32|{V32_CHECKSUM}|0|1|w1-synthetic-maker-v32|"
        "w1-synthetic-release-v32|CHG-W1-SYNTHETIC-V32||dwp-platform-server",
        f"APPROVE|33|{V33_CHECKSUM}|||w1-synthetic-maker-v33|"
        "w1-synthetic-checker-v33|CHG-W1-SYNTHETIC-V33||dwp-provider-server",
        f"ACTIVATE|33|{V33_CHECKSUM}|1|2|w1-synthetic-maker-v33|"
        "w1-synthetic-release-v33|CHG-W1-SYNTHETIC-V33||dwp-platform-server",
        f"ROLLBACK|32|{V32_CHECKSUM}|2|3|w1-synthetic-maker-v32|"
        "w1-synthetic-incident-operator|INC-W1-SYNTHETIC-V33|"
        "Synthetic acceptance rollback from v33 to the proven v32 baseline.|"
        "dwp-platform-server",
    ]
    if sorted(governance_lines) != sorted(expected_governance):
        raise GateFailure(
            "Unexpected governed checksum/actor/change/caller lineage: "
            f"expected {expected_governance}, got {governance_lines}"
        )
    expected_activation = [
        "ACTIVATE|0|1|w1-synthetic-release-v32",
        "ACTIVATE|1|2|w1-synthetic-release-v33",
        "ROLLBACK|2|3|w1-synthetic-incident-operator",
    ]
    if activation_lines != expected_activation:
        raise GateFailure(
            f"Unexpected activation lineage: expected {expected_activation}, got {activation_lines}"
        )
    state.phase(
        "immutable-governance-and-activation-evidence",
        "PASS",
        governanceEventCount=len(governance_lines),
        activationEventCount=len(activation_lines),
    )


def scan_evidence_for_generated_secrets(
    state: GateState, secrets_: RuntimeSecrets
) -> list[str]:
    secret_values = {
        name: value.encode("utf-8")
        for name, value in secrets_.__dict__.items()
    }
    findings: list[str] = []
    for path in sorted(state.output_dir.rglob("*")):
        if not path.is_file():
            continue
        content = path.read_bytes()
        path_findings: list[str] = []
        for name, value in secret_values.items():
            if value in content:
                path_findings.append(name)
        if path_findings:
            relative = path.relative_to(state.output_dir)
            findings.extend(f"{relative}:{name}" for name in path_findings)
            try:
                path.unlink()
            except OSError as error:
                try:
                    path.write_bytes(b"")
                    path.unlink()
                except OSError as quarantine_error:
                    raise GateFailure(
                        "Evidence containing generated secrets could not be "
                        f"quarantined or deleted: {relative}: {quarantine_error}"
                    ) from error
    return findings


def redact_generated_secrets(value: Any, secrets_: RuntimeSecrets) -> Any:
    replacements = tuple(
        (secret, f"<redacted-generated-{name}>")
        for name, secret in secrets_.__dict__.items()
    )
    if isinstance(value, str):
        redacted = value
        for secret, replacement in replacements:
            redacted = redacted.replace(secret, replacement)
        return redacted
    if isinstance(value, list):
        return [redact_generated_secrets(item, secrets_) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_generated_secrets(item, secrets_) for item in value)
    if isinstance(value, dict):
        return {
            key: redact_generated_secrets(item, secrets_)
            for key, item in value.items()
        }
    return value


def atomic_write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True, default=str) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def result_payload(
    state: GateState,
    status: str,
    *,
    scope: str,
    failure: str | None = None,
    teardown: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "gate": "W1_SYNTHETIC_ACCEPTANCE_BACKEND",
        "status": status,
        "scope": scope,
        "runId": state.run_id,
        "syntheticOnly": True,
        "productionActivationPerformed": False,
        "customerDataUsed": False,
        "startedAt": state.started_at,
        "finishedAt": utc_now(),
        "activeBundleAtFinish": {
            "version": state.active_version,
            "revision": state.active_revision,
        },
        "rollbackComplete": state.rollback_complete,
        "provenance": state.provenance,
        "phases": state.phases,
        "httpEvidence": [item.__dict__ for item in state.http_evidence],
        "failure": failure,
        "teardown": teardown,
    }


def teardown(state: GateState) -> dict[str, Any]:
    checkpoint_group_results: dict[str, dict[str, Any]] = {}
    for process_group_id in state.checkpoint_process_groups:
        checkpoint_group_results[str(process_group_id)] = (
            cleanup_checkpoint_process_group(process_group_id)
        )
    process_results: dict[str, str] = {}
    managed_processes = {
        name: state.processes[name] for name in tuple(state.processes)
    }
    for name in reversed(tuple(state.processes)):
        try:
            stop_process(state, name)
            process_results[name] = "stopped"
        except Exception as error:  # teardown must continue
            process_results[name] = f"error: {error}"
    container_results: dict[str, str] = {}
    for container in reversed(state.containers):
        try:
            result = subprocess.run(
                ("docker", "rm", "-f", container),
                check=False,
                text=True,
                capture_output=True,
                timeout=30,
            )
            combined = (result.stdout + result.stderr).lower()
            if result.returncode == 0 or "no such container" in combined:
                container_results[container] = "removed"
            else:
                container_results[container] = (
                    f"error: {(result.stderr or result.stdout).strip()}"
                )
        except Exception as error:  # teardown must continue
            container_results[container] = (
                f"error: {type(error).__name__}: {error}"
            )
    network_status = "not-created"
    if state.network:
        try:
            result = subprocess.run(
                ("docker", "network", "rm", state.network),
                check=False,
                text=True,
                capture_output=True,
                timeout=30,
            )
            combined = (result.stdout + result.stderr).lower()
            network_status = (
                "removed"
                if result.returncode == 0 or "not found" in combined
                else f"error: {(result.stderr or result.stdout).strip()}"
            )
        except Exception as error:  # teardown must continue
            network_status = f"error: {type(error).__name__}: {error}"
    residuals: list[str] = []
    for name, managed in managed_processes.items():
        if managed.process.poll() is None:
            residuals.append(f"process:{name}:pid={managed.process.pid}")
    for container in state.containers:
        try:
            inspection = subprocess.run(
                ("docker", "container", "inspect", container),
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
            )
            if inspection.returncode == 0:
                residuals.append(f"container:{container}")
        except Exception as error:
            residuals.append(
                f"container-inspection:{container}:{type(error).__name__}:{error}"
            )
    if state.network:
        try:
            inspection = subprocess.run(
                ("docker", "network", "inspect", state.network),
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
            )
            if inspection.returncode == 0:
                residuals.append(f"network:{state.network}")
        except Exception as error:
            residuals.append(
                f"network-inspection:{state.network}:{type(error).__name__}:{error}"
            )
    errors = [
        f"process:{name}:{value}"
        for name, value in process_results.items()
        if value != "stopped"
    ]
    errors.extend(
        f"checkpoint-process-group:{process_group_id}:{outcome}"
        for process_group_id, outcome in checkpoint_group_results.items()
        if outcome["residualDetected"] or not outcome["cleanupVerified"]
    )
    errors.extend(
        f"container:{name}:{value}"
        for name, value in container_results.items()
        if value != "removed"
    )
    if network_status not in {"removed", "not-created"}:
        errors.append(f"network:{state.network}:{network_status}")
    errors.extend(f"residual:{value}" for value in residuals)
    return {
        "checkpointProcessGroups": checkpoint_group_results,
        "processes": process_results,
        "containers": container_results,
        "network": {state.network or "": network_status},
        "residuals": residuals,
        "errors": errors,
        "verifiedClean": not errors,
    }


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", help="Safe generated id; primarily for deterministic tests.")
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="New absolute evidence directory; basename must match the run id.",
    )
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--auth-only", action="store_true", help="Run the full Auth lifecycle without starting the other six services.")
    parser.add_argument("--gradle-executable", default="./gradlew")
    parser.add_argument("--postgres-image", default="postgres:18.4-alpine")
    parser.add_argument("--redis-image", default="redis:7.4-alpine")
    parser.add_argument("--startup-timeout", type=float, default=180.0)
    parser.add_argument("--build-timeout", type=float, default=1200.0)
    parser.add_argument("--checkpoint-timeout", type=float, default=1800.0)
    parser.add_argument(
        "--checkpoint-executable-sha256",
        help="Required lowercase SHA-256 pin for the full-mode checkpoint executable.",
    )
    parser.add_argument(
        "--checkpoint-command",
        nargs=argparse.REMAINDER,
        help="Command run with v33 active and every required service healthy, before rollback.",
    )
    arguments = parser.parse_args(argv)
    if arguments.startup_timeout <= 0 or arguments.build_timeout <= 0 or arguments.checkpoint_timeout <= 0:
        parser.error("timeouts must be positive")
    if arguments.auth_only and (
        arguments.checkpoint_command or arguments.checkpoint_executable_sha256
    ):
        parser.error("checkpoint options require the full service runtime")
    if not arguments.auth_only and (
        not arguments.checkpoint_command
        or not arguments.checkpoint_executable_sha256
    ):
        parser.error(
            "full mode requires --checkpoint-executable-sha256 and "
            "--checkpoint-command; use --auth-only for the limited Auth lifecycle subgate"
        )
    return arguments


def execute(args: argparse.Namespace) -> tuple[GateState, str, str | None, dict[str, Any]]:
    run_id = args.run_id or make_run_id()
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise GateFailure(f"Unsafe W1 run id: {run_id}")
    default_output = ROOT / "output" / output_basename(run_id)
    output_dir = validate_new_output_dir(args.output_dir or default_output, run_id)
    output_dir.mkdir(parents=True, mode=0o700)
    for child in ("db", "health", "http", "logs"):
        (output_dir / child).mkdir(mode=0o700)
    state = GateState(run_id, output_dir, utc_now())
    secrets_ = RuntimeSecrets.generate()
    specs = service_specs(secrets_)
    selected_specs = (
        {"auth": specs["auth"]} if args.auth_only else specs
    )
    failure: str | None = None
    status = "HOLD"
    postgres = ""
    try:
        source_provenance = tracked_source_provenance()
        state.provenance["source"] = source_provenance
        if not args.auth_only:
            state.provenance["checkpoint"] = {
                "executable": validate_checkpoint_executable(
                    args.checkpoint_command,
                    args.checkpoint_executable_sha256,
                )
            }
        build_jars(args, state, selected_specs)
        verified_source = verify_source_provenance(source_provenance)
        state.provenance["source"] = {
            **source_provenance,
            "verifiedAfterBuild": True,
            "trackedWorktreeCleanAfterBuild": True,
            "postBuildGitHead": verified_source["gitHead"],
            "postBuildRunnerSha256": verified_source["runnerSha256"],
        }
        state.phase(
            "source-and-jar-provenance",
            "HOLD" if args.skip_build else "PASS",
            gitHead=source_provenance["gitHead"],
            runnerSha256=source_provenance["runnerSha256"],
            freshBuild=not args.skip_build,
            jars=state.provenance["jars"],
        )
        postgres, _, postgres_port, redis_port = provision_infrastructure(state, secrets_, args)
        provision_databases(postgres, secrets_)
        state.phase("provision-synthetic-databases-and-runtime-roles", "PASS")
        for name in ("auth", "platform", "people", "provider", "payroll", "time", "gateway"):
            state.ports[name] = allocate_loopback_port()
        base_environment = common_environment(state, secrets_, postgres_port, redis_port)

        start_and_verify_latest_clean_auth(
            state,
            specs["auth"],
            base_environment,
            postgres,
            args.startup_timeout,
        )
        stop_process(state, "auth-latest-clean")
        start_auth_at_v232(
            state, specs["auth"], base_environment, args.startup_timeout
        )
        verify_v232(postgres, state)
        lifecycle_approve_activate_v32_at_v232(state, secrets_)
        stop_process(state, "auth-v232")
        start_auth_latest(
            state,
            specs["auth"],
            base_environment,
            postgres,
            secrets_,
            args.startup_timeout,
        )
        lifecycle_approve_activate_v33_after_upgrade(state, secrets_)

        checkpoint_failure: Exception | None = None
        if not args.auth_only:
            start_remaining_services(state, specs, base_environment, args)
            write_runtime_manifest(state)
            try:
                run_checkpoint(state, args.checkpoint_command, args.checkpoint_timeout)
            except Exception as error:
                checkpoint_failure = error
                state.phase("external-live-checkpoint", "FAIL", error=str(error))
        else:
            state.phase("required-services-up", "SKIPPED", reason="--auth-only")

        rollback_v33_to_v32(state, secrets_)
        capture_governance_evidence(postgres, state)
        if checkpoint_failure is not None:
            raise checkpoint_failure
        if args.skip_build:
            failure = (
                "Diagnostic --skip-build run completed but cannot produce an "
                "AUTH/BACKEND subgate PASS without a fresh --rerun-tasks build."
            )
            state.phase("fresh-build-pass-eligibility", "HOLD", diagnosticOnly=True)
        else:
            status = (
                "AUTH_SUBGATE_PASS"
                if args.auth_only
                else "BACKEND_RUNTIME_SUBGATE_PASS"
            )
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
        if isinstance(error, ExternalControlEvidenceRequired):
            state.phase(
                "external-migration-control-evidence",
                "HOLD",
                error=failure,
                substitutionAllowed=False,
            )
        else:
            state.phase("gate", "FAIL", error=failure)
        if (
            postgres
            and state.active_version == 33
            and state.active_revision == 2
            and "auth" in state.processes
            and state.processes["auth"].process.poll() is None
        ):
            try:
                rollback_v33_to_v32(state, secrets_)
                capture_governance_evidence(postgres, state)
                state.phase("failure-path-rollback", "PASS")
            except Exception as rollback_error:
                state.phase(
                    "failure-path-rollback",
                    "FAIL",
                    error=f"{type(rollback_error).__name__}: {rollback_error}",
                )
    teardown_result = teardown(state)
    if teardown_result["verifiedClean"]:
        state.phase("teardown-owned-resources", "PASS", **teardown_result)
    else:
        status = "HOLD"
        teardown_failure = "Owned resource teardown was not verified clean: " + "; ".join(
            teardown_result["errors"]
        )
        failure = f"{failure}; {teardown_failure}" if failure else teardown_failure
        state.phase("teardown-owned-resources", "FAIL", **teardown_result)
    try:
        secret_findings = scan_evidence_for_generated_secrets(state, secrets_)
    except Exception as error:
        status = "HOLD"
        scan_failure = f"{type(error).__name__}: {error}"
        failure = f"{failure}; {scan_failure}" if failure else scan_failure
        state.phase("evidence-secret-scan", "FAIL", error=scan_failure)
    else:
        if secret_findings:
            status = "HOLD"
            scan_failure = (
                "Evidence containing generated secrets was deleted: "
                + ", ".join(secret_findings)
            )
            failure = f"{failure}; {scan_failure}" if failure else scan_failure
            state.phase(
                "evidence-secret-scan",
                "FAIL",
                generatedSecretCount=len(secrets_.__dict__),
                deleted=secret_findings,
            )
        else:
            state.phase(
                "evidence-secret-scan",
                "PASS",
                generatedSecretCount=len(secrets_.__dict__),
                findings=0,
            )

    scope = (
        "AUTH_LIFECYCLE_SUBGATE"
        if args.auth_only
        else "FULL_BACKEND_WITH_EXTERNAL_CHECKPOINT"
    )
    result_path = state.output_dir / "result.json"

    def persist_result() -> None:
        payload = result_payload(
            state,
            status,
            scope=scope,
            failure=failure,
            teardown=teardown_result,
        )
        atomic_write_json(
            result_path,
            redact_generated_secrets(payload, secrets_),
        )

    persist_result()
    final_findings = scan_evidence_for_generated_secrets(state, secrets_)
    if final_findings:
        status = "HOLD"
        final_failure = (
            "Final evidence scan deleted files containing generated secrets: "
            + ", ".join(final_findings)
        )
        failure = f"{failure}; {final_failure}" if failure else final_failure
        state.phase(
            "final-result-secret-scan",
            "FAIL",
            deleted=final_findings,
        )
    else:
        state.phase(
            "final-result-secret-scan",
            "PASS",
            resultIncluded=True,
            findings=0,
        )
    persist_result()

    # The second pass validates the exact final result after its scan phase was
    # added. Redaction plus deletion keeps a failed run from retaining a leak.
    post_write_findings = scan_evidence_for_generated_secrets(state, secrets_)
    if post_write_findings:
        status = "HOLD"
        post_write_failure = (
            "Post-write evidence scan deleted files containing generated secrets: "
            + ", ".join(post_write_findings)
        )
        failure = (
            f"{failure}; {post_write_failure}"
            if failure
            else post_write_failure
        )
        state.phase(
            "post-write-secret-scan",
            "FAIL",
            deleted=post_write_findings,
        )
        persist_result()
        terminal_findings = scan_evidence_for_generated_secrets(state, secrets_)
        if terminal_findings:
            raise GateFailure(
                "Unable to persist a generated-secret-free final result after deletion: "
                + ", ".join(terminal_findings)
            )
    return state, status, failure, teardown_result


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        state, status, failure, _ = execute(args)
    except GateFailure as error:
        print(f"HOLD: {error}", file=sys.stderr)
        return 2
    print(f"Evidence: {state.output_dir}")
    if status not in {"AUTH_SUBGATE_PASS", "BACKEND_RUNTIME_SUBGATE_PASS"}:
        print(f"HOLD: {failure}", file=sys.stderr)
        return 1
    if status == "AUTH_SUBGATE_PASS":
        print(
            "AUTH_SUBGATE_PASS: governed Auth lifecycle completed and rolled back "
            "to v32; this is not a full W1 acceptance result."
        )
    else:
        print(
            "BACKEND_RUNTIME_SUBGATE_PASS: required backend services and the supplied "
            "live checkpoint passed, then Auth rolled back to v32; the overall W1 "
            "decision remains the caller's responsibility."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
