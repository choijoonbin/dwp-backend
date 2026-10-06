#!/usr/bin/env python3
"""Run the isolated HRIS Wave 1 backend synthetic-acceptance lane.

The runner intentionally owns every mutable resource that it creates.  It uses
new, uniquely named Docker containers backed by tmpfs, binds only loopback
ports, uses synthetic actors and credentials, and removes the containers in a
finally block.  It never connects to an existing DWP database.

The lifecycle exercised here is the production-shaped, token-separated HTTP
lane. A seedless clean database first proves the current Auth migration head.
Flyway then stops a separate database at V232, Auth imports only v32 and
activates it at revision 1, and that database is upgraded to the exact V233
boundary. After proving the active v32 pointer survived, the runner upgrades to
the current migration head, imports and activates v33 with CAS, and rolls the
active pointer back to v32 while retaining exact governance evidence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import http.cookiejar
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
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence


ROOT = Path(__file__).resolve().parents[1]
BUNDLE_KEY = "product-surfaces"
V32_CHECKSUM = "9e4e274bf457d1a5947c8b54e83299d28fb9fe128d9f1100991bc30634b54344"
V33_CHECKSUM = "254ead674e1126d50e8dcf1011486ea1127fb2479f7a82d832cdf0466995bc49"
CURRENT_AUTH_FLYWAY_VERSION = "234.1"
CURRENT_AUTH_MIGRATION_TAIL = ("232", "233", "234", CURRENT_AUTH_FLYWAY_VERSION)
RUN_ID_PATTERN = re.compile(r"^w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}$")
OUTPUT_BASENAME_PATTERN = re.compile(
    r"^hris-w1-synthetic-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}$"
)
OPERATIONS_PATH = "/internal/auth/v1/product-authorization/operations/bundles"
APPROVAL_IDENTITY = "dwp-provider-server"
ACTIVATION_IDENTITY = "dwp-platform-server"
MAX_PERSISTED_HTTP_BODY_BYTES = 256 * 1024
MAX_CHECKPOINT_MANIFEST_BYTES = 1024 * 1024
MAX_CHECKPOINT_EVIDENCE_BYTES = 4 * 1024 * 1024
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
NEGATIVE_OBSERVATION_STATES = {
    "negative.stale-evidence-denied": "STALE",
    "negative.expired-evidence-denied": "EXPIRED",
    "negative.revoked-evidence-denied": "REVOKED",
}
NEGATIVE_OWNER_STATUS = 503
NEGATIVE_OWNER_ERROR_CODE = "AUTHORITY_RESOLUTION_UNAVAILABLE"
NEGATIVE_OWNER_ERROR_MESSAGE = "Authority resolution is temporarily unavailable."
NEGATIVE_PROJECTION_STATES = {
    "STALE": ("BUILDING->ACTIVE->SUPERSEDED", "SUPERSEDED", "EXPIRED"),
    "EXPIRED": ("BUILDING->ACTIVE", "ACTIVE", "EXPIRED"),
    "REVOKED": ("BUILDING->ACTIVE->REVOKED", "REVOKED", "EXPIRED"),
}


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
    control_receipts: dict[str, dict[str, Any]] = field(default_factory=dict)
    synthetic_tenants: dict[str, dict[str, Any]] = field(default_factory=dict)
    projection_feed: dict[str, Any] = field(default_factory=dict)

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
    people_metadata_password: str
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
    provider_provisioning_token: str
    synthetic_identity_bootstrap_token: str
    synthetic_rollout_bootstrap_token: str
    synthetic_payroll_foundation_bootstrap_token: str
    synthetic_people_workforce_bootstrap_token: str
    payroll_projection_publisher_password: str
    time_projection_publisher_password: str
    tenant_a_password: str
    tenant_b_password: str

    @classmethod
    def generate(cls) -> "RuntimeSecrets":
        values = {
            name: secrets.token_urlsafe(30) for name in cls.__dataclass_fields__
        }
        values["tenant_a_password"] = "Aa1!" + secrets.token_urlsafe(28)
        values["tenant_b_password"] = "Bb2!" + secrets.token_urlsafe(28)
        return cls(**values)


@dataclass(frozen=True)
class SyntheticTenantCredential:
    lane: str
    provider_tenant_id: str
    tenant_id: int
    administrator_user_id: int
    person_public_id: str
    worker_public_id: str
    assignment_public_id: str
    actor_legal_employer_public_id: str
    target_person_public_id: str
    target_worker_public_id: str
    target_assignment_public_id: str
    target_population_revision: str
    target_population_count: int
    tenant_key: str
    email: str
    password: str


@dataclass(frozen=True)
class ServiceSpec:
    name: str
    module: str
    database: str | None
    extra_environment: dict[str, str]

    @property
    def jar(self) -> Path:
        return ROOT / self.module / "build" / "libs" / f"{self.module}-1.0.0.jar"


@dataclass
class GatewayBrowserSession:
    credential: SyntheticTenantCredential
    opener: urllib.request.OpenerDirector
    csrf_header: str = ""
    csrf_token: str = ""


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
    return hashlib.sha256(_attested_regular_bytes(path)).hexdigest()


def tracked_source_provenance() -> dict[str, Any]:
    status = run_checked(
        ("git", "status", "--porcelain", "--untracked-files=all"),
        timeout=30,
    ).stdout.strip()
    if status:
        raise GateFailure(
            "Worktree, including untracked files, must be clean before a "
            "provenance-bearing W1 run: "
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
        "worktreeClean": True,
        "trackedWorktreeClean": True,
        "untrackedFilesIncludedInCleanFence": True,
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
    if not re.fullmatch(
        r"(?:dwp_(?:people|payroll|time)_(?:runtime|migration)"
        r"|dwp_provider_metadata_people"
        r"|dwp_(?:payroll|time)_projection_publisher)",
        role,
    ):
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


def gateway_session_request(
    state: GateState,
    session: GatewayBrowserSession,
    *,
    name: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
    expected_status: int | Iterable[int] = 200,
    persist_body: bool = True,
) -> tuple[int, dict[str, Any] | None, bytes]:
    data = None
    headers: dict[str, str] = {
        "X-Tenant-ID": str(session.credential.tenant_id),
        "Accept-Language": "en",
    }
    if payload is not None:
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if method not in {"GET", "HEAD", "OPTIONS"} and session.csrf_token:
        headers[session.csrf_header] = session.csrf_token
    request = urllib.request.Request(
        f"http://127.0.0.1:{state.ports['gateway']}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with session.opener.open(request, timeout=30) as response:
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
            pass
    body_sha256 = hashlib.sha256(body).hexdigest()
    body_path = state.output_dir / "http" / f"{name}.json"
    if persist_body:
        if len(body) > MAX_PERSISTED_HTTP_BODY_BYTES:
            raise GateFailure(f"{name} response exceeds the evidence body size limit")
        body_path.write_bytes(body)
    state.http_evidence.append(
        HttpEvidence(
            name=name,
            status=status,
            path=(
                str(body_path.relative_to(state.output_dir))
                if persist_body
                else ""
            ),
            sha256=body_sha256,
            byte_count=len(body),
            summary=summarize_http_body(parsed),
        )
    )
    if status not in allowed:
        detail = body.decode("utf-8", errors="replace")[:1000]
        raise GateFailure(
            f"{name} returned HTTP {status}, expected {sorted(allowed)}: {detail}"
        )
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
    process = state.processes.get(name)
    if process is not None:
        process.stop()
        state.processes.pop(name, None)


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


def service_specs(secrets_: RuntimeSecrets, run_id: str) -> dict[str, ServiceSpec]:
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
                "DWP_AUTH_STEP_UP_REQUIRED_ACR": "urn:dwp:assurance:high",
                "DWP_PROVIDER_PROVISIONING_TOKEN": secrets_.provider_provisioning_token,
                "DWP_LOCAL_SYNTHETIC_IDENTITY_BOOTSTRAP_ENABLED": "true",
                "DWP_LOCAL_SYNTHETIC_IDENTITY_BOOTSTRAP_TOKEN": (
                    secrets_.synthetic_identity_bootstrap_token
                ),
                "DWP_LOCAL_SYNTHETIC_IDENTITY_BOOTSTRAP_RUN_ID": run_id,
            },
        ),
        "platform": ServiceSpec(
            "platform",
            "dwp-platform-server",
            "dwp_platform",
            {
                "PLATFORM_DB_NAME": "dwp_platform",
                "DWP_HRIS_SYSTEM_WAVE1_ENABLED": "true",
                "DWP_PROVIDER_PROVISIONING_TOKEN": secrets_.provider_provisioning_token,
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
                "DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED": "true",
                "DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED": "true",
                "DWP_IDENTITY_SYNC_ENABLED": "false",
                "DWP_PROVIDER_PROVISIONING_TOKEN": secrets_.provider_provisioning_token,
                "DWP_SYNTHETIC_IMPORT_ENABLED": "true",
                "DWP_HRIS_PEOPLE_WORKFORCE_SYNTHETIC_BOOTSTRAP_ENABLED": "true",
                "DWP_HRIS_PEOPLE_WORKFORCE_SYNTHETIC_BOOTSTRAP_TOKEN": (
                    secrets_.synthetic_people_workforce_bootstrap_token
                ),
                "DWP_HRIS_PEOPLE_WORKFORCE_SYNTHETIC_BOOTSTRAP_RUN_ID": run_id,
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
                "DWP_PEOPLE_METADATA_DB_USERNAME": "dwp_provider_metadata_people",
                "DWP_PEOPLE_METADATA_DB_PASSWORD": secrets_.people_metadata_password,
                "DWP_PROVIDER_SUPPORT_ACTIVATION_ENABLED": "false",
                "DWP_PROVIDER_SUPPORT_AUTHORITY_RECONCILIATION_ENABLED": "false",
                "DWP_PROVIDER_LOCAL_APPROVAL_FIXTURES_ENABLED": "false",
                "DWP_PRODUCT_SURFACE_ROLLOUT_RELAY_ENABLED": "false",
                "DWP_PRODUCT_SURFACE_ROLLOUT_PUBLISHER_ENABLED": "false",
                "DWP_LOCAL_SYNTHETIC_PRODUCT_SURFACE_BOOTSTRAP_ENABLED": "true",
                "DWP_LOCAL_SYNTHETIC_PRODUCT_SURFACE_BOOTSTRAP_TOKEN": (
                    secrets_.synthetic_rollout_bootstrap_token
                ),
                "DWP_LOCAL_SYNTHETIC_PRODUCT_SURFACE_BOOTSTRAP_RUN_ID": run_id,
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
                "PAYROLL_PROJECTION_PUBLISHER_DB_USERNAME": (
                    "dwp_payroll_projection_publisher"
                ),
                "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD": (
                    secrets_.payroll_projection_publisher_password
                ),
                "DWP_PAYROLL_DB_POOL_SIZE": "3",
                "DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED": "true",
                "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_ENABLED": "true",
                "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_TOKEN": (
                    secrets_.synthetic_payroll_foundation_bootstrap_token
                ),
                "DWP_LOCAL_SYNTHETIC_PAYROLL_FOUNDATION_BOOTSTRAP_RUN_ID": run_id,
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
                "TIME_PROJECTION_PUBLISHER_DB_USERNAME": (
                    "dwp_time_projection_publisher"
                ),
                "TIME_PROJECTION_PUBLISHER_DB_PASSWORD": (
                    secrets_.time_projection_publisher_password
                ),
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
        ("dwp_provider_metadata_people", secrets_.people_metadata_password),
        (
            "dwp_payroll_projection_publisher",
            secrets_.payroll_projection_publisher_password,
        ),
        (
            "dwp_time_projection_publisher",
            secrets_.time_projection_publisher_password,
        ),
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
        read_only_roles=("dwp_provider_metadata_people",),
    )
    configure_strict_database_roles(
        postgres,
        database="dwp_payroll",
        runtime_role="dwp_payroll_runtime",
        migration_role="dwp_payroll_migration",
        publisher_role="dwp_payroll_projection_publisher",
    )
    configure_strict_database_roles(
        postgres,
        database="dwp_time",
        runtime_role="dwp_time_runtime",
        migration_role="dwp_time_migration",
        publisher_role="dwp_time_projection_publisher",
    )
    provision_required_extensions(postgres)


def configure_strict_database_roles(
    postgres: str,
    *,
    database: str,
    runtime_role: str,
    migration_role: str,
    auxiliary_schemas: Sequence[str] = (),
    read_only_roles: Sequence[str] = (),
    publisher_role: str | None = None,
) -> None:
    optional_roles = (*read_only_roles, *((publisher_role,) if publisher_role else ()))
    identifiers = (
        database,
        runtime_role,
        migration_role,
        *auxiliary_schemas,
        *optional_roles,
    )
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
        for role in optional_roles:
            psql(
                postgres,
                "postgres",
                f'REVOKE CONNECT ON DATABASE "{candidate}" FROM "{role}";',
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
        f'SET search_path TO pg_catalog, public;'
        + "".join(
            f' ALTER ROLE "{role}" IN DATABASE "{database}" '
            f'SET search_path TO pg_catalog;'
            f' REVOKE TEMPORARY, CREATE ON DATABASE "{database}" FROM "{role}";'
            f' GRANT CONNECT ON DATABASE "{database}" TO "{role}";'
            for role in read_only_roles
        )
        + "".join(
            f' ALTER ROLE "{role}" IN DATABASE "{database}" '
            f'SET search_path TO pg_catalog, public;'
            f' REVOKE TEMPORARY, CREATE ON DATABASE "{database}" FROM "{role}";'
            f' GRANT CONNECT ON DATABASE "{database}" TO "{role}";'
            for role in ((publisher_role,) if publisher_role else ())
        ),
    )
    schema_statements = [
        "DROP SCHEMA public CASCADE",
        f'CREATE SCHEMA public AUTHORIZATION "{migration_role}"',
        "REVOKE ALL ON SCHEMA public FROM PUBLIC",
        f'GRANT USAGE ON SCHEMA public TO "{runtime_role}"',
    ]
    if publisher_role:
        schema_statements.append(
            f'GRANT USAGE ON SCHEMA public TO "{publisher_role}"'
        )
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


def provision_required_extensions(postgres: str) -> None:
    expected = {
        "dwp_people": (("btree_gist", "1.7"), ("pgcrypto", "1.3")),
        "dwp_payroll": (("btree_gist", "1.7"),),
        "dwp_time": (("btree_gist", "1.7"),),
    }
    for database, extensions in expected.items():
        statements = "; ".join(
            f'CREATE EXTENSION "{extension}" WITH SCHEMA public VERSION {sql_literal(version)}'
            for extension, version in extensions
        )
        psql(postgres, database, statements + ";")
        actual = psql(
            postgres,
            database,
            "SELECT extname || '|' || extversion || '|' || namespace.nspname || '|' || "
            "pg_get_userbyid(extension.extowner) "
            "FROM pg_extension extension "
            "JOIN pg_namespace namespace ON namespace.oid=extension.extnamespace "
            "WHERE extname <> 'plpgsql' ORDER BY extname;",
        ).splitlines()
        wanted = [
            f"{extension}|{version}|public|postgres"
            for extension, version in sorted(extensions)
        ]
        if actual != wanted:
            raise GateFailure(
                f"Required extension inventory mismatch for {database}: "
                f"expected={wanted}, actual={actual}"
            )


def _canonical_control_field(digest: Any, value: object) -> None:
    canonical = "true" if value is True else "false" if value is False else str(value)
    encoded = canonical.encode("utf-8")
    digest.update(str(len(encoded)).encode("ascii"))
    digest.update(b":")
    digest.update(encoded)


def canonical_json_sha256(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    ).hexdigest()


def java_name_uuid_from_bytes(value: str) -> uuid.UUID:
    digest = bytearray(hashlib.md5(
        value.encode("utf-8"), usedforsecurity=False
    ).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def people_workforce_receipt_sha256(workforce: dict[str, Any]) -> str:
    binding = workforce["authWorkforceBinding"]
    event = binding["event"]
    fields = (
        ("runId", workforce["runId"]),
        ("providerTenantId", workforce["providerTenantId"]),
        ("tenantId", workforce["tenantId"]),
        ("administratorActorId", workforce["administratorActorId"]),
        ("actorPersonPublicId", workforce["actorPersonPublicId"]),
        ("actorWorkerPublicId", workforce["actorWorkerPublicId"]),
        ("actorAssignmentPublicId", workforce["actorAssignmentPublicId"]),
        ("actorLegalEmployerPublicId", workforce["actorLegalEmployerPublicId"]),
        ("actorWorkerNumber", workforce["actorWorkerNumber"]),
        ("targetPersonPublicId", workforce["targetPersonPublicId"]),
        ("targetWorkerPublicId", workforce["targetWorkerPublicId"]),
        ("targetAssignmentPublicId", workforce["targetAssignmentPublicId"]),
        ("syncRunId", workforce["syncRunId"]),
        ("importReplayed", workforce["importReplayed"]),
        ("importedWorkerCount", workforce["importedWorkerCount"]),
        ("workforceAccessPolicyId", workforce["workforceAccessPolicyId"]),
        ("workforceAccessPolicyVersion", workforce["workforceAccessPolicyVersion"]),
        ("targetPopulationCount", workforce["targetPopulationCount"]),
        ("targetPopulationRevision", workforce["targetPopulationRevision"]),
        ("auth.endpoint", binding["endpoint"]),
        ("auth.tokenHeader", binding["tokenHeader"]),
        ("auth.expectedAdministratorUserId", binding["expectedAdministratorUserId"]),
        ("auth.event.eventId", event["eventId"]),
        ("auth.event.providerTenantId", event["providerTenantId"]),
        ("auth.event.personPublicId", event["personPublicId"]),
        ("auth.event.externalId", event["externalId"]),
        ("auth.event.workerNumber", event["workerNumber"]),
        ("auth.event.displayName", event["displayName"]),
        ("auth.event.givenName", event["givenName"]),
        ("auth.event.familyName", event["familyName"]),
        ("auth.event.workEmail", event["workEmail"]),
        ("auth.event.jobTitle", event["jobTitle"]),
        ("auth.event.preferredLocale", event["preferredLocale"]),
        ("auth.event.workerStatus", event["workerStatus"]),
        ("auth.event.sourceVersion", event["sourceVersion"]),
    )
    material = []
    for name, raw in fields:
        if raw is None:
            value = "N"
        elif raw is True:
            value = "Vtrue"
        elif raw is False:
            value = "Vfalse"
        else:
            value = "V" + str(raw)
        material.append(f"{len(name)}:{name}={len(value)}:{value}\n")
    return hashlib.sha256("".join(material).encode("utf-8")).hexdigest()


def validate_people_workforce_bootstrap_response(
    value: Any,
    *,
    run_id: str,
    lane: str,
    provider_tenant_id: uuid.UUID,
    tenant_id: int,
    administrator_actor_id: int,
) -> dict[str, Any]:
    expected_fields = {
        "runId",
        "providerTenantId",
        "tenantId",
        "administratorActorId",
        "actorPersonPublicId",
        "actorWorkerPublicId",
        "actorAssignmentPublicId",
        "actorLegalEmployerPublicId",
        "actorWorkerNumber",
        "targetPersonPublicId",
        "targetWorkerPublicId",
        "targetAssignmentPublicId",
        "syncRunId",
        "importReplayed",
        "importedWorkerCount",
        "workforceAccessPolicyId",
        "workforceAccessPolicyVersion",
        "targetPopulationCount",
        "targetPopulationRevision",
        "authWorkforceBinding",
        "receiptSha256",
    }
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise GateFailure(
            f"Tenant {lane} People workforce bootstrap response shape is invalid"
        )
    binding = value.get("authWorkforceBinding")
    event = binding.get("event") if isinstance(binding, dict) else None
    expected_event = {
        "eventId": str(java_name_uuid_from_bytes("|".join((
            run_id,
            "auth-workforce-event",
            str(provider_tenant_id),
            str(tenant_id),
            str(administrator_actor_id),
        )))),
        "providerTenantId": str(provider_tenant_id),
        "personPublicId": value.get("actorPersonPublicId"),
        "externalId": "WD-WORKER-0001",
        "workerNumber": "E100001",
        "displayName": "Minseo Kim",
        "givenName": "Minseo",
        "familyName": "Kim",
        "workEmail": "minseo.kim@sk.com",
        "jobTitle": "Network Operations Lead",
        "preferredLocale": "ko-KR",
        "workerStatus": "ACTIVE",
        "sourceVersion": "2026-08-10T00:00:01Z",
    }
    public_id_fields = (
        "actorPersonPublicId",
        "actorWorkerPublicId",
        "actorAssignmentPublicId",
        "actorLegalEmployerPublicId",
        "targetPersonPublicId",
        "targetWorkerPublicId",
        "targetAssignmentPublicId",
        "syncRunId",
        "workforceAccessPolicyId",
    )
    try:
        parsed_ids = {field: uuid.UUID(str(value.get(field))) for field in public_id_fields}
        uuid.UUID(str(expected_event["eventId"]))
    except (TypeError, ValueError) as error:
        raise GateFailure(
            f"Tenant {lane} People workforce bootstrap contains an invalid public id"
        ) from error
    if (
        value.get("runId") != run_id
        or value.get("providerTenantId") != str(provider_tenant_id)
        or value.get("tenantId") != tenant_id
        or value.get("administratorActorId") != administrator_actor_id
        or value.get("actorWorkerNumber") != "E100001"
        or value.get("importReplayed") is not False
        or type(value.get("importedWorkerCount")) is not int
        or value["importedWorkerCount"] != 3
        or type(value.get("targetPopulationCount")) is not int
        or value["targetPopulationCount"] != 2
        or not re.fullmatch(
            r"[0-9a-f]{32}:true\|\[\]\|"
            r"\[DIRECTORY, EMPLOYMENT, WORKER_IDENTIFIERS\]\|READ",
            str(value.get("targetPopulationRevision", "")),
        )
        or type(value.get("workforceAccessPolicyVersion")) is not int
        or value["workforceAccessPolicyVersion"] < 0
        or parsed_ids["actorPersonPublicId"] == parsed_ids["targetPersonPublicId"]
        or parsed_ids["actorWorkerPublicId"] == parsed_ids["targetWorkerPublicId"]
        or parsed_ids["actorAssignmentPublicId"] == parsed_ids["targetAssignmentPublicId"]
        or not isinstance(binding, dict)
        or set(binding) != {
            "endpoint", "tokenHeader", "expectedAdministratorUserId", "event"
        }
        or binding.get("expectedAdministratorUserId") != administrator_actor_id
        or binding.get("endpoint") != "/internal/identity/v1/workforce-events"
        or binding.get("tokenHeader") != "X-DWP-Identity-Sync-Token"
        or not isinstance(event, dict)
        or event != expected_event
        or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("receiptSha256", "")))
        or value.get("receiptSha256") != people_workforce_receipt_sha256(value)
    ):
        raise GateFailure(
            f"Tenant {lane} People workforce bootstrap response is invalid"
        )
    return value


def validate_negative_observation(
    value: Any,
    *,
    assertion_name: str,
    state: GateState,
) -> dict[str, Any]:
    expected_fields = {
        "assertionName",
        "source",
        "method",
        "path",
        "tenantId",
        "actorId",
        "evidenceState",
        "projectionId",
        "projectionRevision",
        "contextScopeKey",
        "policyRevision",
        "authorizationRevision",
        "databaseTransition",
        "databaseStatus",
        "databaseValidity",
        "databaseMemberCount",
        "projectionObservationSha256",
        "status",
        "errorCode",
        "ownerErrorMessage",
        "observedAt",
        "responseBodySha256",
        "observationSha256",
    }
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise GateFailure(
            f"Negative checkpoint observation shape is not exact: {assertion_name}"
        )
    tenant = state.synthetic_tenants.get("A")
    if not isinstance(tenant, dict):
        raise GateFailure("Negative checkpoint observations require tenant A lineage")
    method = value.get("method")
    path = value.get("path")
    error_code = value.get("errorCode")
    observed_at = value.get("observedAt")
    evidence_state = NEGATIVE_OBSERVATION_STATES[assertion_name]
    expected_transition, expected_database_status, expected_validity = (
        NEGATIVE_PROJECTION_STATES[evidence_state]
    )
    projection_material = {
        "tenantId": value.get("tenantId"),
        "actorId": value.get("actorId"),
        "evidenceState": value.get("evidenceState"),
        "projectionId": value.get("projectionId"),
        "projectionRevision": value.get("projectionRevision"),
        "contextScopeKey": value.get("contextScopeKey"),
        "policyRevision": value.get("policyRevision"),
        "authorizationRevision": value.get("authorizationRevision"),
        "databaseTransition": value.get("databaseTransition"),
        "databaseStatus": value.get("databaseStatus"),
        "databaseValidity": value.get("databaseValidity"),
        "databaseMemberCount": value.get("databaseMemberCount"),
    }
    if (
        value.get("assertionName") != assertion_name
        or value.get("source") != "LIVE_GATEWAY_OWNER_REQUEST"
        or value.get("tenantId") != tenant.get("tenantId")
        or value.get("actorId") != tenant.get("administratorUserId")
        or value.get("evidenceState") != evidence_state
        or value.get("databaseTransition") != expected_transition
        or value.get("databaseStatus") != expected_database_status
        or value.get("databaseValidity") != expected_validity
        or value.get("databaseMemberCount") != 1
        or value.get("projectionObservationSha256")
        != canonical_json_sha256(projection_material)
        or type(value.get("status")) is not int
        or value["status"] != NEGATIVE_OWNER_STATUS
        or value.get("errorCode") != NEGATIVE_OWNER_ERROR_CODE
        or value.get("ownerErrorMessage") != NEGATIVE_OWNER_ERROR_MESSAGE
        or not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
            str(value.get("projectionId", "")),
        )
        or not re.fullmatch(
            r"[0-9a-f]{64}", str(value.get("projectionRevision", ""))
        )
        or not re.fullmatch(
            r"hcm-scope-[0-9a-f]{40}", str(value.get("contextScopeKey", ""))
        )
        or not re.fullmatch(
            r"rollout-[0-9a-f]{64}", str(value.get("policyRevision", ""))
        )
        or not re.fullmatch(
            r"psr-[0-9a-f]{64}", str(value.get("authorizationRevision", ""))
        )
        or not isinstance(method, str)
        or not re.fullmatch(r"[A-Z]{3,8}", method)
        or not isinstance(path, str)
        or not path.startswith("/")
        or len(path) > 500
        or path != path.strip()
        or not isinstance(observed_at, str)
        or not observed_at.endswith("Z")
        or not re.fullmatch(
            r"[0-9a-f]{64}", str(value.get("responseBodySha256", ""))
        )
    ):
        raise GateFailure(
            f"Negative checkpoint observation binding is invalid: {assertion_name}"
        )
    try:
        parsed_at = dt.datetime.fromisoformat(observed_at.removesuffix("Z") + "+00:00")
    except ValueError as error:
        raise GateFailure(
            f"Negative checkpoint observation time is invalid: {assertion_name}"
        ) from error
    if parsed_at.tzinfo != dt.timezone.utc:
        raise GateFailure(
            f"Negative checkpoint observation time is not UTC: {assertion_name}"
        )
    unsigned = {key: item for key, item in value.items() if key != "observationSha256"}
    if value["observationSha256"] != canonical_json_sha256(unsigned):
        raise GateFailure(
            f"Negative checkpoint observation digest is invalid: {assertion_name}"
        )
    return value


def validate_payroll_browser_database_observation(
    value: Any,
    *,
    state: GateState,
) -> dict[str, Any]:
    expected_fields = {
        "source",
        "tenantId",
        "configurationId",
        "preflightObservationSha256",
        "payrollConfigurationIds",
        "workspaceResponseSha256",
        "updateCommandId",
        "updateResponseSha256",
        "simulateCommandId",
        "simulateResponseSha256",
        "expectedFinalVersion",
        "observationSha256",
    }
    if not isinstance(value, dict) or set(value) != expected_fields:
        raise GateFailure(
            "Browser-to-PAY database checkpoint observation shape is not exact"
        )
    tenant = state.synthetic_tenants.get("A")
    preflight = state.projection_feed.get("payrollFoundationDatabaseObservation")
    fixture = state.projection_feed.get("payrollFoundation")
    if not all(isinstance(item, dict) for item in (tenant, preflight, fixture)):
        raise GateFailure(
            "Browser-to-PAY database checkpoint requires backend preflight lineage"
        )
    configuration_id = preflight.get("configurationId")
    update_command_id = value.get("updateCommandId")
    simulate_command_id = value.get("simulateCommandId")
    unsigned = {key: item for key, item in value.items() if key != "observationSha256"}
    if (
        value.get("source") != "BROWSER_GATEWAY_OWNER_DB"
        or value.get("tenantId") != tenant.get("tenantId")
        or value.get("configurationId") != configuration_id
        or value.get("preflightObservationSha256")
        != preflight.get("observationSha256")
        or value.get("payrollConfigurationIds") != [configuration_id]
        or value.get("expectedFinalVersion") != 4
        or update_command_id == simulate_command_id
        or update_command_id in {
            fixture.get("createCommandId"), fixture.get("simulateCommandId")
        }
        or simulate_command_id in {
            fixture.get("createCommandId"), fixture.get("simulateCommandId")
        }
        or any(not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", str(item or "")
        ) for item in (configuration_id, update_command_id, simulate_command_id))
        or any(not re.fullmatch(r"[0-9a-f]{64}", str(value.get(field, "")))
               for field in (
                   "preflightObservationSha256",
                   "workspaceResponseSha256",
                   "updateResponseSha256",
                   "simulateResponseSha256",
               ))
        or value.get("observationSha256") != canonical_json_sha256(unsigned)
    ):
        raise GateFailure(
            "Browser-to-PAY database checkpoint observation binding is invalid"
        )
    return value


def _read_attested_descriptor(
    descriptor: int,
    path: Path,
    *,
    max_bytes: int | None = None,
    require_executable: bool = False,
) -> bytes:
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode):
        raise GateFailure(f"Attestation input is not a regular file: {path}")
    if require_executable and before.st_mode & 0o111 == 0:
        raise GateFailure(f"Attestation input is not executable: {path}")
    if max_bytes is not None and before.st_size > max_bytes:
        raise GateFailure(f"Attestation input is too large: {path}")
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = os.read(descriptor, 1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if max_bytes is not None and total > max_bytes:
            raise GateFailure(f"Attestation input is too large: {path}")
        chunks.append(chunk)
    after = os.fstat(descriptor)
    fingerprint = lambda item: (
        item.st_dev,
        item.st_ino,
        item.st_size,
        item.st_mtime_ns,
        item.st_ctime_ns,
    )
    content = b"".join(chunks)
    if fingerprint(before) != fingerprint(after) or len(content) != before.st_size:
        raise GateFailure(f"Attestation input changed while being read: {path}")
    return content


def _attested_regular_bytes(
    path: Path,
    *,
    max_bytes: int | None = None,
    require_executable: bool = False,
) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise GateFailure(f"Attestation input cannot be opened safely: {path}: {error}") from error
    try:
        return _read_attested_descriptor(
            descriptor,
            path,
            max_bytes=max_bytes,
            require_executable=require_executable,
        )
    finally:
        os.close(descriptor)


def _attested_output_regular_bytes(
    output_dir: Path,
    relative: Path,
    *,
    max_bytes: int,
) -> bytes:
    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
        raise GateFailure(f"Unsafe evidence path: {relative}")
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_DIRECTORY", 0)
    )
    file_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptors: list[int] = []
    try:
        descriptors.append(os.open(output_dir, directory_flags))
        for component in relative.parts[:-1]:
            descriptors.append(
                os.open(component, directory_flags, dir_fd=descriptors[-1])
            )
        descriptor = os.open(
            relative.parts[-1], file_flags, dir_fd=descriptors[-1]
        )
        descriptors.append(descriptor)
        return _read_attested_descriptor(
            descriptor, output_dir / relative, max_bytes=max_bytes
        )
    except OSError as error:
        raise GateFailure(
            f"Evidence path cannot be opened without following links: {relative}: {error}"
        ) from error
    finally:
        for descriptor in reversed(descriptors):
            os.close(descriptor)


def migration_control_attestation_files() -> tuple[Path, ...]:
    files = {
        Path(__file__).resolve(),
        ROOT / "build.gradle",
        ROOT / "settings.gradle",
        ROOT / "gradlew",
        ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar",
        ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties",
        ROOT / "gradle" / "verification-metadata.xml",
        ROOT / "dwp-core" / "build.gradle",
        ROOT / "dwp-migration-control" / "build.gradle",
        ROOT / "dwp-migration-control" / "README.md",
    }
    for root in (
        ROOT / "dwp-migration-control" / "src" / "main" / "java",
        ROOT / "dwp-core" / "src" / "main" / "java",
        ROOT / "dwp-core" / "src" / "main" / "resources",
    ):
        files.update(path for path in root.rglob("*") if path.is_file())
    for module in ("dwp-people-server", "dwp-payroll-server", "dwp-time-server"):
        module_root = ROOT / module
        files.add(module_root / "build.gradle")
        files.add(module_root / "src" / "main" / "resources" / "application.yml")
        files.update(
            path
            for path in (module_root / "src" / "main" / "resources").rglob("*.sql")
            if path.is_file()
        )
        files.update(
            path
            for path in (module_root / "src" / "main" / "java").rglob(
                "*DatabaseMigrationConfiguration.java"
            )
            if path.is_file()
        )
    missing = sorted(str(path) for path in files if not path.is_file())
    if missing:
        raise GateFailure(
            "Migration Control attestation boundary is incomplete: " + ", ".join(missing)
        )
    return tuple(sorted(files, key=lambda path: path.relative_to(ROOT).as_posix()))


def migration_control_reference() -> str:
    digest = hashlib.sha256()
    _canonical_control_field(digest, "dwp-migration-control-attestation-v2")
    for path in migration_control_attestation_files():
        _canonical_control_field(digest, path.relative_to(ROOT).as_posix())
        _canonical_control_field(digest, _attested_regular_bytes(path).hex())
    return "dwp-migration-control-v2:" + digest.hexdigest()


def validate_control_receipt(
    receipt: Any,
    *,
    service: str,
    database: str,
    migration_principal: str,
    mode: str,
    control_reference: str,
) -> dict[str, Any]:
    if not isinstance(receipt, dict):
        raise GateFailure(f"Migration Control {service} receipt is not an object")
    top_level_fields = {
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
    if set(receipt) != top_level_fields:
        raise GateFailure(
            f"Migration Control {service} receipt fields are not exact"
        )
    expected = {
        "schemaVersion": "2.0",
        "mode": mode,
        "service": service,
        "database": database,
        "migrationPrincipal": migration_principal,
        "controlReference": control_reference,
        "previousRunReceiptSha256": "",
        "temporaryPrivilegeRevoked": True,
    }
    drift = {
        key: {"expected": value, "actual": receipt.get(key)}
        for key, value in expected.items()
        if receipt.get(key) != value
    }
    if drift:
        raise GateFailure(f"Migration Control {service} receipt drift: {drift}")
    if not re.fullmatch(r"[0-9a-f]{64}", str(receipt.get("receiptSha256", ""))):
        raise GateFailure(f"Migration Control {service} receipt digest is invalid")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", str(receipt.get("postgresVersion", ""))):
        raise GateFailure(f"Migration Control {service} PostgreSQL version is invalid")
    streams = receipt.get("streams")
    expected_streams = {
        "people": ["people-main", "people-performance"],
        "payroll": ["payroll-main"],
        "time": ["time-main"],
    }[service]
    if (
        not isinstance(streams, list)
        or len(streams) != len(expected_streams)
        or any(not isinstance(item, dict) or set(item) != stream_fields for item in streams)
        or [item["streamKey"] for item in streams] != expected_streams
    ):
        raise GateFailure(f"Migration Control {service} stream set is invalid")
    for stream in streams:
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", str(stream["streamKey"])):
            raise GateFailure(f"Migration Control {service} stream key is invalid")
        for field, minimum in (
            ("historyMaxInstalledRank", 0),
            ("historyRowCount", 0),
            ("inventoryObjectCount", 1),
        ):
            if type(stream[field]) is not int or stream[field] < minimum:
                raise GateFailure(
                    f"Migration Control {service} stream {field} is invalid"
                )
        if stream.get("adoptionReceiptSha256") != "":
            raise GateFailure(
                f"Migration Control {service} fresh receipt contains adoption evidence"
            )
        for field in ("historySha256", "inventorySha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", str(stream.get(field, ""))):
                raise GateFailure(
                    f"Migration Control {service} stream {field} is invalid"
                )
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
    if digest.hexdigest() != receipt["receiptSha256"]:
        raise GateFailure(
            f"Migration Control {service} receipt canonical digest is invalid"
        )
    return receipt


def run_migration_controls(
    state: GateState,
    secrets_: RuntimeSecrets,
    postgres_port: int,
    gradle_executable: str,
    timeout: float,
) -> None:
    control_reference = migration_control_reference()
    configurations = (
        (
            "people",
            "dwp_people",
            "PEOPLE_FRESH",
            "People",
            "dwp_people_migration",
            secrets_.people_migration_password,
            "dwp_people_runtime",
            secrets_.people_password,
            "",
            "",
        ),
        (
            "payroll",
            "dwp_payroll",
            "STRICT_FRESH",
            "Payroll",
            "dwp_payroll_migration",
            secrets_.payroll_migration_password,
            "dwp_payroll_runtime",
            secrets_.payroll_password,
            "dwp_payroll_projection_publisher",
            secrets_.payroll_projection_publisher_password,
        ),
        (
            "time",
            "dwp_time",
            "STRICT_FRESH",
            "Time",
            "dwp_time_migration",
            secrets_.time_migration_password,
            "dwp_time_runtime",
            secrets_.time_password,
            "dwp_time_projection_publisher",
            secrets_.time_projection_publisher_password,
        ),
    )
    for (
        service,
        database,
        mode,
        task_suffix,
        migration_principal,
        migration_password,
        runtime_principal,
        runtime_password,
        publisher_principal,
        publisher_password,
    ) in configurations:
        environment = allowlisted_host_environment()
        environment.update(
            {
                "DWP_MIGRATION_CONTROL_MODE": mode,
                "DWP_MIGRATION_CONTROL_SERVICE": service,
                "DWP_MIGRATION_CONTROL_JDBC_URL": (
                    f"jdbc:postgresql://127.0.0.1:{postgres_port}/{database}"
                ),
                "DWP_MIGRATION_CONTROL_DATABASE": database,
                "DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL": "postgres",
                "DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD": secrets_.postgres_password,
                "DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL": migration_principal,
                "DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD": migration_password,
                "DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL": runtime_principal,
                "DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD": runtime_password,
                "DWP_MIGRATION_CONTROL_REFERENCE": control_reference,
            }
        )
        if publisher_principal:
            environment.update(
                {
                    "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PRINCIPAL": (
                        publisher_principal
                    ),
                    "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD": (
                        publisher_password
                    ),
                }
            )
        log_path = state.output_dir / "logs" / f"migration-control-{service}.log"
        result = run_checked(
            (
                gradle_executable,
                "--no-daemon",
                "--console=plain",
                f":dwp-migration-control:run{task_suffix}MigrationControl",
            ),
            timeout=timeout,
            log_path=log_path,
            environment=environment,
        )
        prefix = "DWP_MIGRATION_CONTROL_RECEIPT="
        receipt_lines = [
            line.removeprefix(prefix)
            for line in result.stdout.splitlines()
            if line.startswith(prefix)
        ]
        if len(receipt_lines) != 1:
            raise GateFailure(
                f"Migration Control {service} produced {len(receipt_lines)} receipt lines"
            )
        try:
            decoded = json.loads(receipt_lines[0])
        except json.JSONDecodeError as error:
            raise GateFailure(
                f"Migration Control {service} receipt is invalid JSON"
            ) from error
        receipt = validate_control_receipt(
            decoded,
            service=service,
            database=database,
            migration_principal=migration_principal,
            mode="NATIVE_FRESH",
            control_reference=control_reference,
        )
        receipt_path = state.output_dir / "db" / f"migration-control-{service}.json"
        atomic_write_json(receipt_path, receipt)
        state.control_receipts[service] = receipt
        state.phase(
            f"migration-control-{service}",
            "PASS",
            receiptSha256=receipt["receiptSha256"],
            controlReference=control_reference,
            evidencePath=str(receipt_path.relative_to(state.output_dir)),
        )


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
         WHERE version IS NOT NULL
           AND installed_rank >= (
               SELECT installed_rank
                 FROM flyway_schema_history
                WHERE version = '232')
         ORDER BY installed_rank;
        SELECT 'flyway-head|' || version
          FROM flyway_schema_history
         WHERE version IS NOT NULL AND success
         ORDER BY installed_rank DESC
         LIMIT 1;
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
        *(f"{version}|true" for version in CURRENT_AUTH_MIGRATION_TAIL),
        f"flyway-head|{CURRENT_AUTH_FLYWAY_VERSION}",
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
        "latest-clean-install-through-v234.1",
        "PASS",
        isolatedDatabase="dwp_auth_latest_clean",
        flywayVersion=CURRENT_AUTH_FLYWAY_VERSION,
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
         WHERE version IS NOT NULL
           AND installed_rank >= (
               SELECT installed_rank
                 FROM flyway_schema_history
                WHERE version = '232')
         ORDER BY installed_rank;
        SELECT 'flyway-head|' || version
          FROM flyway_schema_history
         WHERE version IS NOT NULL AND success
         ORDER BY installed_rank DESC
         LIMIT 1;
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
        "flyway-head|232",
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


def start_auth_at_v233(
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
            "SPRING_FLYWAY_TARGET": "233",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "true",
            "DWP_PRODUCT_AUTHORIZATION_SEED_ONLY_VERSION": "33",
        }
    )
    managed = start_jar(
        state, spec, environment, timeout, runtime_name="auth-v233"
    )
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        count = psql(
            postgres,
            "dwp_auth",
            "SELECT count(*) FROM auth_product_authorization_bundle;",
        ).strip()
        if count == "2":
            break
        if managed.process.poll() is not None:
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
         WHERE version IS NOT NULL
           AND installed_rank >= (
               SELECT installed_rank
                 FROM flyway_schema_history
                WHERE version = '232')
         ORDER BY installed_rank;
        SELECT 'flyway-head|' || version
          FROM flyway_schema_history
         WHERE version IS NOT NULL AND success
         ORDER BY installed_rank DESC
         LIMIT 1;
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
        "flyway-head|233",
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
            "DWP_PRODUCT_AUTHORIZATION_SEED_ENABLED": "false",
        }
    )
    start_jar(state, spec, environment, timeout)
    declaration = psql(
        postgres,
        "dwp_auth",
        """
        SELECT version || '|' || success::text
          FROM flyway_schema_history
         WHERE version IS NOT NULL
           AND installed_rank >= (
               SELECT installed_rank
                 FROM flyway_schema_history
                WHERE version = '232')
         ORDER BY installed_rank;
        SELECT 'flyway-head|' || version
          FROM flyway_schema_history
         WHERE version IS NOT NULL AND success
         ORDER BY installed_rank DESC
         LIMIT 1;
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
    (state.output_dir / "db" / "current-latest-state.txt").write_text(
        declaration + "\n", encoding="utf-8"
    )
    expected = [
        *(f"{version}|true" for version in CURRENT_AUTH_MIGRATION_TAIL),
        f"flyway-head|{CURRENT_AUTH_FLYWAY_VERSION}",
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
        raise GateFailure(
            "Current Auth upgrade state mismatch at v234.1: "
            f"expected {expected}, got {actual}"
        )
    _, active, _ = http_request(
        state,
        name="04-current-latest-active-v32",
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
        "active v32 pointer preserved from V233 through current Auth migrations",
    )
    state.phase(
        "upgrade-v233-state-to-current-v234.1",
        "PASS",
        flywayVersion=CURRENT_AUTH_FLYWAY_VERSION,
        seedImportEnabled=False,
        bundleCount=2,
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


def bootstrap_synthetic_identities(
    state: GateState, secrets_: RuntimeSecrets
) -> tuple[SyntheticTenantCredential, SyntheticTenantCredential]:
    token_header = {"X-DWP-Provisioning-Token": secrets_.provider_provisioning_token}
    identity_header = {
        "X-DWP-Synthetic-Bootstrap-Token": (
            secrets_.synthetic_identity_bootstrap_token
        )
    }
    people_header = {
        "X-DWP-Synthetic-People-Bootstrap-Token": (
            secrets_.synthetic_people_workforce_bootstrap_token
        )
    }
    suffix = state.run_id.rsplit("-", 1)[-1]
    credentials: list[SyntheticTenantCredential] = []
    for lane, password, entitlements in (
        ("a", secrets_.tenant_a_password, ["core.workspace", "core.people"]),
        ("b", secrets_.tenant_b_password, ["core.workspace"]),
    ):
        provider_tenant_id = uuid.uuid5(
            uuid.NAMESPACE_URL, f"dwp:{state.run_id}:tenant-{lane}"
        )
        tenant_key = f"w1-{lane}-{suffix}"
        email = f"hris-w1-{lane}-{suffix}@dwp.test"
        display_name = f"HRIS W1 synthetic tenant {lane.upper()}"
        _, provisioned, _ = http_request(
            state,
            name=f"20-tenant-{lane}-auth-provision",
            port=state.ports["auth"],
            method="POST",
            path="/internal/provider/v1/tenants",
            headers=token_header,
            payload={
                "providerTenantId": str(provider_tenant_id),
                "tenantKey": tenant_key,
                "displayName": display_name,
                "dataRegion": "local",
                "isolationModel": "POOL",
                "defaultLocale": "ko",
                "timeZone": "Asia/Seoul",
                "administratorDisplayName": f"W1 {lane.upper()} Administrator",
                "administratorEmail": email,
                "entitlementKeys": entitlements,
            },
        )
        if provisioned is None:
            raise GateFailure(f"Tenant {lane} Auth provisioning returned no object")
        tenant_id = provisioned.get("tenantId")
        administrator_user_id = provisioned.get("administratorUserId")
        if (
            provisioned.get("providerTenantId") != str(provider_tenant_id)
            or provisioned.get("lifecycleState") != "PROVISIONING"
            or not isinstance(tenant_id, int)
            or tenant_id <= 0
            or not isinstance(administrator_user_id, int)
            or administrator_user_id <= 0
            or provisioned.get("administratorEmail") != email
        ):
            raise GateFailure(f"Tenant {lane} Auth provisioning response is invalid")

        _, platform_provisioned, _ = http_request(
            state,
            name=f"21-tenant-{lane}-platform-provision",
            port=state.ports["platform"],
            method="POST",
            path="/internal/provider/v1/tenants",
            headers=token_header,
            payload={
                "providerTenantId": str(provider_tenant_id),
                "tenantId": tenant_id,
                "tenantKey": tenant_key,
                "displayName": display_name,
                "dataRegion": "local",
                "isolationModel": "POOL",
                "defaultLocale": "ko",
                "entitlementKeys": entitlements,
            },
        )
        if platform_provisioned is None or any(
            (
                platform_provisioned.get("providerTenantId") != str(provider_tenant_id),
                platform_provisioned.get("tenantId") != tenant_id,
                platform_provisioned.get("lifecycleState") != "PROVISIONING",
            )
        ):
            raise GateFailure(f"Tenant {lane} Platform provisioning response is invalid")

        _, people_provisioned, _ = http_request(
            state,
            name=f"21-tenant-{lane}-people-provision",
            port=state.ports["people"],
            method="POST",
            path="/internal/provider/v1/tenants",
            headers=token_header,
            payload={
                "providerTenantId": str(provider_tenant_id),
                "tenantId": tenant_id,
                "tenantKey": tenant_key,
                "displayName": display_name,
                "dataRegion": "local",
                "isolationModel": "POOL",
            },
        )
        if people_provisioned is None or any(
            (
                people_provisioned.get("providerTenantId")
                != str(provider_tenant_id),
                people_provisioned.get("tenantId") != tenant_id,
                people_provisioned.get("lifecycleState") != "PROVISIONING",
            )
        ):
            raise GateFailure(f"Tenant {lane} People provisioning response is invalid")

        for service in ("auth", "platform", "people"):
            _, activated_tenant, _ = http_request(
                state,
                name=f"22-tenant-{lane}-{service}-lifecycle-active",
                port=state.ports[service],
                method="PATCH",
                path=(
                    f"/internal/provider/v1/tenants/{provider_tenant_id}/lifecycle"
                ),
                headers=token_header,
                payload={"lifecycleState": "ACTIVE"},
            )
            if (
                activated_tenant is None
                or activated_tenant.get("providerTenantId") != str(provider_tenant_id)
                or activated_tenant.get("tenantId") != tenant_id
                or activated_tenant.get("lifecycleState") != "ACTIVE"
            ):
                raise GateFailure(
                    f"Tenant {lane} {service} lifecycle activation is invalid"
                )

        people_payload = {
            "runId": state.run_id,
            "providerTenantId": str(provider_tenant_id),
            "tenantId": tenant_id,
            "administratorActorId": administrator_user_id,
        }
        if lane == "a":
            http_request(
                state,
                name="23-synthetic-people-wrong-token-denied",
                port=state.ports["people"],
                method="POST",
                path="/internal/synthetic/v1/people-workforce/bootstrap",
                headers={
                    "X-DWP-Synthetic-People-Bootstrap-Token": "x" * 40
                },
                payload=people_payload,
                expected_status=401,
            )
            wrong_run = state.run_id[:-8] + (
                "feedface" if not state.run_id.endswith("feedface") else "deadbeef"
            )
            http_request(
                state,
                name="24-synthetic-people-wrong-run-denied",
                port=state.ports["people"],
                method="POST",
                path="/internal/synthetic/v1/people-workforce/bootstrap",
                headers=people_header,
                payload={**people_payload, "runId": wrong_run},
                expected_status=403,
            )
        _, workforce, _ = http_request(
            state,
            name=f"25-tenant-{lane}-people-workforce-bootstrap",
            port=state.ports["people"],
            method="POST",
            path="/internal/synthetic/v1/people-workforce/bootstrap",
            headers=people_header,
            payload=people_payload,
        )
        workforce = validate_people_workforce_bootstrap_response(
            workforce,
            run_id=state.run_id,
            lane=lane,
            provider_tenant_id=provider_tenant_id,
            tenant_id=tenant_id,
            administrator_actor_id=administrator_user_id,
        )
        person_public_id = workforce["actorPersonPublicId"]
        worker_public_id = workforce["actorWorkerPublicId"]
        assignment_public_id = workforce["actorAssignmentPublicId"]
        actor_legal_employer_public_id = workforce["actorLegalEmployerPublicId"]
        target_person_public_id = workforce["targetPersonPublicId"]
        target_worker_public_id = workforce["targetWorkerPublicId"]
        target_assignment_public_id = workforce["targetAssignmentPublicId"]
        population_revision = workforce["targetPopulationRevision"]
        population_count = workforce["targetPopulationCount"]
        http_request(
            state,
            name=f"25-tenant-{lane}-people-workforce-one-shot",
            port=state.ports["people"],
            method="POST",
            path="/internal/synthetic/v1/people-workforce/bootstrap",
            headers=people_header,
            payload=people_payload,
            expected_status=409,
        )

        activation_payload = {
            "runId": state.run_id,
            "providerTenantId": str(provider_tenant_id),
            "administratorUserId": administrator_user_id,
            "personPublicId": person_public_id,
            "administratorEmail": email,
            "password": password,
            "roleCodes": (
                ["HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN"]
                if lane == "a"
                else []
            ),
        }
        if lane == "a":
            http_request(
                state,
                name="23-synthetic-identity-wrong-token-denied",
                port=state.ports["auth"],
                method="POST",
                path="/internal/synthetic/v1/identity/activate",
                headers={"X-DWP-Synthetic-Bootstrap-Token": "x" * 40},
                payload=activation_payload,
                expected_status=401,
            )
            wrong_run = (
                state.run_id[:-8]
                + ("feedface" if not state.run_id.endswith("feedface") else "deadbeef")
            )
            http_request(
                state,
                name="24-synthetic-identity-wrong-run-denied",
                port=state.ports["auth"],
                method="POST",
                path="/internal/synthetic/v1/identity/activate",
                headers=identity_header,
                payload={**activation_payload, "runId": wrong_run},
                expected_status=403,
            )
        _, identity, _ = http_request(
            state,
            name=f"25-tenant-{lane}-synthetic-identity-activate",
            port=state.ports["auth"],
            method="POST",
            path="/internal/synthetic/v1/identity/activate",
            headers=identity_header,
            payload=activation_payload,
            persist_body=False,
        )
        if (
            identity is None
            or identity.get("runId") != state.run_id
            or identity.get("providerTenantId") != str(provider_tenant_id)
            or identity.get("tenantId") != tenant_id
            or identity.get("administratorUserId") != administrator_user_id
            or identity.get("personPublicId") != activation_payload["personPublicId"]
            or identity.get("administratorEmail") != email
            or identity.get("lifecycleState") != "ACTIVE"
            or identity.get("roleCodes") != activation_payload["roleCodes"]
            or not re.fullmatch(r"[0-9a-f]{64}", str(identity.get("receiptSha256", "")))
        ):
            raise GateFailure(f"Tenant {lane} synthetic identity activation is invalid")
        http_request(
            state,
            name=f"26-tenant-{lane}-synthetic-identity-one-shot",
            port=state.ports["auth"],
            method="POST",
            path="/internal/synthetic/v1/identity/activate",
            headers=identity_header,
            payload=activation_payload,
            expected_status=409,
            persist_body=False,
        )
        credential = SyntheticTenantCredential(
            lane.upper(),
            str(provider_tenant_id),
            tenant_id,
            administrator_user_id,
            str(person_public_id),
            str(worker_public_id),
            str(assignment_public_id),
            str(actor_legal_employer_public_id),
            str(target_person_public_id),
            str(target_worker_public_id),
            str(target_assignment_public_id),
            population_revision,
            population_count,
            tenant_key,
            email,
            password,
        )
        credentials.append(credential)
        state.synthetic_tenants[credential.lane] = {
            "providerTenantId": credential.provider_tenant_id,
            "tenantId": credential.tenant_id,
            "administratorUserId": credential.administrator_user_id,
            "personPublicId": credential.person_public_id,
            "workerPublicId": credential.worker_public_id,
            "assignmentPublicId": credential.assignment_public_id,
            "actorLegalEmployerPublicId": (
                credential.actor_legal_employer_public_id
            ),
            "targetPersonPublicId": credential.target_person_public_id,
            "targetWorkerPublicId": credential.target_worker_public_id,
            "targetAssignmentPublicId": credential.target_assignment_public_id,
            "targetPopulationRevision": credential.target_population_revision,
            "targetPopulationCount": credential.target_population_count,
            "peopleWorkforceReceiptSha256": workforce["receiptSha256"],
            "authBindingExecution": {
                "mode": "LOCAL_SYNTHETIC_ACTIVATE",
                "officialWorkforceEventContractValidated": True,
                "officialWorkforceEventExecuted": False,
                "gap": (
                    "The official workforce event cannot select the already-provisioned "
                    "LOCAL administrator; this run binds it only through the run-bound "
                    "local synthetic activation boundary."
                ),
            },
            "tenantKey": credential.tenant_key,
            "administratorEmail": credential.email,
            "identityReceiptSha256": identity["receiptSha256"],
            "hcmState": "111" if credential.lane == "A" else "000",
        }
    state.phase(
        "synthetic-tenant-identities",
        "PASS",
        tenantStates={"A": "ACTIVE", "B": "ACTIVE"},
        credentialLoginVerified=False,
        peopleWorkforceBound=True,
        authBindingMode="LOCAL_SYNTHETIC_ACTIVATE",
        officialWorkforceEventExecuted=False,
        secretsPersisted=False,
    )
    return credentials[0], credentials[1]


def bootstrap_synthetic_rollouts(
    state: GateState,
    secrets_: RuntimeSecrets,
    credentials: Sequence[SyntheticTenantCredential],
) -> None:
    bootstrap_header = {
        "X-DWP-Synthetic-Rollout-Token": secrets_.synthetic_rollout_bootstrap_token
    }
    evaluation_header = {
        "X-DWP-Service-Token": secrets_.provider_token,
        "X-DWP-Service-Identity": "dwp-gateway",
    }
    flag_keys = (
        "access.product-surfaces.context-shadow.v1",
        "access.product-surfaces.capability-enforcement.hcm.v1",
        "ux.product-surfaces.hcm.v1",
    )
    rollout_by_lane: dict[str, dict[str, Any]] = {}
    for credential in credentials:
        hcm_state = "111" if credential.lane == "A" else "000"
        payload = {
            "runId": state.run_id,
            "providerTenantId": credential.provider_tenant_id,
            "authTenantId": credential.tenant_id,
            "tenantKey": credential.tenant_key,
            "displayName": f"HRIS W1 synthetic tenant {credential.lane}",
            "hcmState": hcm_state,
        }
        if credential.lane == "A":
            http_request(
                state,
                name="30-synthetic-rollout-wrong-token-denied",
                port=state.ports["provider"],
                method="POST",
                path="/internal/synthetic/v1/product-surface/bootstrap",
                headers={"X-DWP-Synthetic-Rollout-Token": "x" * 40},
                payload=payload,
                expected_status=401,
            )
            wrong_run = (
                state.run_id[:-8]
                + ("feedface" if not state.run_id.endswith("feedface") else "deadbeef")
            )
            http_request(
                state,
                name="31-synthetic-rollout-wrong-run-denied",
                port=state.ports["provider"],
                method="POST",
                path="/internal/synthetic/v1/product-surface/bootstrap",
                headers=bootstrap_header,
                payload={**payload, "runId": wrong_run},
                expected_status=403,
            )
        _, bootstrapped, _ = http_request(
            state,
            name=f"32-tenant-{credential.lane.lower()}-rollout-bootstrap",
            port=state.ports["provider"],
            method="POST",
            path="/internal/synthetic/v1/product-surface/bootstrap",
            headers=bootstrap_header,
            payload=payload,
        )
        if (
            bootstrapped is None
            or bootstrapped.get("runId") != state.run_id
            or bootstrapped.get("providerTenantId") != credential.provider_tenant_id
            or bootstrapped.get("authTenantId") != credential.tenant_id
            or bootstrapped.get("tenantKey") != credential.tenant_key
            or bootstrapped.get("hcmState") != hcm_state
            or set((bootstrapped.get("rolloutRevisions") or {}).keys())
            != set(flag_keys)
            or not re.fullmatch(
                r"[0-9a-f]{64}", str(bootstrapped.get("receiptSha256", ""))
            )
        ):
            raise GateFailure(
                f"Tenant {credential.lane} rollout bootstrap response is invalid"
            )
        http_request(
            state,
            name=f"33-tenant-{credential.lane.lower()}-rollout-one-shot",
            port=state.ports["provider"],
            method="POST",
            path="/internal/synthetic/v1/product-surface/bootstrap",
            headers=bootstrap_header,
            payload=payload,
            expected_status=409,
        )
        decisions: list[dict[str, Any]] = []
        for index, flag_key in enumerate(flag_keys):
            _, envelope, _ = http_request(
                state,
                name=(
                    f"34-tenant-{credential.lane.lower()}-rollout-evaluate-{index}"
                ),
                port=state.ports["provider"],
                method="POST",
                path="/internal/provider/v1/feature-rollouts/evaluate",
                headers=evaluation_header,
                payload={
                    "authTenantId": credential.tenant_id,
                    "flagKey": flag_key,
                },
            )
            decision = envelope.get("data") if isinstance(envelope, dict) else None
            enabled = hcm_state[index] == "1"
            if (
                not isinstance(decision, dict)
                or decision.get("flagKey") != flag_key
                or decision.get("enabled") is not enabled
                or not re.fullmatch(
                    r"rev-[0-9]{20}", str(decision.get("opaqueRevision", ""))
                )
                or decision.get("cohort") != ("full" if enabled else "baseline")
            ):
                raise GateFailure(
                    f"Tenant {credential.lane} rollout decision is invalid: {flag_key}"
                )
            decisions.append(decision)
        material = "hcm" + "".join(
            "\n"
            + decision["flagKey"]
            + "="
            + decision["opaqueRevision"]
            + ":"
            + str(decision["enabled"]).lower()
            + ":true"
            for decision in decisions
        )
        combined = "rollout-" + hashlib.sha256(material.encode("utf-8")).hexdigest()
        rollout_by_lane[credential.lane] = {
            "state": hcm_state,
            "cohort": "full" if hcm_state == "111" else "baseline",
            "revision": combined,
            "flags": {
                decision["flagKey"]: decision["opaqueRevision"]
                for decision in decisions
            },
            "receiptSha256": bootstrapped["receiptSha256"],
        }
        state.synthetic_tenants[credential.lane]["rollout"] = rollout_by_lane[
            credential.lane
        ]
    state.projection_feed["rollouts"] = rollout_by_lane
    state.phase(
        "synthetic-product-surface-rollouts",
        "PASS",
        states={lane: value["state"] for lane, value in rollout_by_lane.items()},
        evaluatedByTrustedProviderPath=True,
    )


def login_gateway_sessions(
    state: GateState,
    credentials: Sequence[SyntheticTenantCredential],
) -> dict[str, GatewayBrowserSession]:
    sessions: dict[str, GatewayBrowserSession] = {}
    for credential in credentials:
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        session = GatewayBrowserSession(credential, opener)
        _, csrf, _ = gateway_session_request(
            state,
            session,
            name=f"27-tenant-{credential.lane.lower()}-gateway-csrf",
            method="GET",
            path="/api/auth/csrf",
            persist_body=False,
        )
        csrf_data = csrf.get("data") if isinstance(csrf, dict) else None
        if (
            not isinstance(csrf_data, dict)
            or csrf_data.get("headerName") != "X-XSRF-TOKEN"
            or not isinstance(csrf_data.get("token"), str)
            or len(csrf_data["token"]) < 16
        ):
            raise GateFailure(
                f"Tenant {credential.lane} Gateway CSRF bootstrap failed"
            )
        session.csrf_header = csrf_data["headerName"]
        session.csrf_token = csrf_data["token"]
        _, login, _ = gateway_session_request(
            state,
            session,
            name=f"28-tenant-{credential.lane.lower()}-gateway-login",
            method="POST",
            path="/api/auth/login",
            payload={
                "email": credential.email,
                "password": credential.password,
                "tenantId": str(credential.tenant_id),
            },
            persist_body=False,
        )
        login_data = login.get("data") if isinstance(login, dict) else None
        if (
            not isinstance(login_data, dict)
            or login_data.get("tenantId") != str(credential.tenant_id)
            or login_data.get("userId")
            != str(credential.administrator_user_id)
        ):
            raise GateFailure(
                f"Tenant {credential.lane} Gateway credential login failed"
            )
        _, refreshed_csrf, _ = gateway_session_request(
            state,
            session,
            name=f"29-tenant-{credential.lane.lower()}-gateway-csrf-refresh",
            method="GET",
            path="/api/auth/csrf",
            persist_body=False,
        )
        refreshed_csrf_data = (
            refreshed_csrf.get("data")
            if isinstance(refreshed_csrf, dict)
            else None
        )
        if (
            not isinstance(refreshed_csrf_data, dict)
            or refreshed_csrf_data.get("headerName") != "X-XSRF-TOKEN"
            or not isinstance(refreshed_csrf_data.get("token"), str)
            or len(refreshed_csrf_data["token"]) < 16
        ):
            raise GateFailure(
                f"Tenant {credential.lane} authenticated CSRF refresh failed"
            )
        session.csrf_header = refreshed_csrf_data["headerName"]
        session.csrf_token = refreshed_csrf_data["token"]
        sessions[credential.lane] = session
    state.phase(
        "synthetic-gateway-sessions",
        "PASS",
        lanes=sorted(sessions),
        credentialLoginVerified=True,
        csrfBound=True,
        secretsPersisted=False,
    )
    return sessions


def gateway_evaluate(
    state: GateState,
    session: GatewayBrowserSession,
    *,
    name: str,
    route_contract_key: str,
    context_key: str | None,
    scope_key: str | None,
) -> dict[str, Any]:
    _, envelope, _ = gateway_session_request(
        state,
        session,
        name=name,
        method="POST",
        path="/api/auth/product-surface-access/evaluate",
        payload={
            "subject": {
                "type": "PRODUCT",
                "productKey": "hcm",
                "surfaceKey": "hcm.operations",
            },
            "routeContractKey": route_contract_key,
            "contextKey": context_key,
            "contextScopeKey": scope_key,
        },
    )
    data = envelope.get("data") if isinstance(envelope, dict) else None
    if not isinstance(data, dict):
        raise GateFailure(f"{name} returned no Gateway decision object")
    return data


def resolve_gateway_authorities(
    state: GateState,
    sessions: dict[str, GatewayBrowserSession],
) -> dict[str, dict[str, Any]]:
    if set(sessions) != {"A", "B"}:
        raise GateFailure("Gateway evaluation requires exact tenant A/B sessions")
    session_a = sessions["A"]
    _, envelope, _ = gateway_session_request(
        state,
        session_a,
        name="40-tenant-a-gateway-product-surface-contexts",
        method="GET",
        path="/api/auth/product-surface-contexts",
    )
    data = envelope.get("data") if isinstance(envelope, dict) else None
    contexts = data.get("contexts") if isinstance(data, dict) else None
    matches = [
        value
        for value in contexts or []
        if isinstance(value, dict)
        and value.get("productKey") == "hcm"
        and value.get("surfaceKey") == "hcm.operations"
        and value.get("accessMode") == "NORMAL"
    ]
    if len(matches) != 1:
        raise GateFailure("Tenant A has no unique live HCM operations context")
    context = matches[0]
    entry_context_key = context.get("contextKey")
    if not isinstance(entry_context_key, str) or not re.fullmatch(
        r"psc-[0-9a-f]{64}", entry_context_key
    ):
        raise GateFailure("Tenant A HCM operations context key is invalid")

    def capability_scope(capability_key: str) -> str:
        grants = [
            grant
            for grant in context.get("effectiveGrants") or []
            if isinstance(grant, dict)
            and grant.get("grantKind") == "CAPABILITY"
            and grant.get("capabilityContractKey") == capability_key
        ]
        keys = grants[0].get("scopeKeys") if len(grants) == 1 else None
        if (
            not isinstance(keys, list)
            or len(keys) != 1
            or not re.fullmatch(r"hcm-scope-[0-9a-f]{40}", str(keys[0]))
        ):
            raise GateFailure(
                f"Tenant A has no unique derived scope for {capability_key}"
            )
        return str(keys[0])

    requests = {
        "payroll": (
            "hcm.operations.pay.read",
            "route.hcm.operations.payroll-foundation-configurations.data",
            "ALLOWED",
        ),
        "time": (
            "hcm.operations.time.read",
            "route.hcm.operations.work-plans-list.data",
            "ALLOWED",
        ),
        "page": (
            "hcm.operations.pay.read",
            "route.hcm.operations.overview.page",
            "ALLOWED",
        ),
        "payrollPublish": (
            "hcm.operations.payroll-foundation.publish",
            "route.hcm.operations.payroll-foundation-publish.action",
            "STEP_UP_REQUIRED",
        ),
        "payrollStale": (
            "hcm.operations.pay.read",
            "route.hcm.operations.payroll-foundation-configuration.data",
            "ALLOWED",
        ),
        "payrollExpired": (
            "hcm.operations.pay.read",
            "route.hcm.operations.payroll-foundation-versions.data",
            "ALLOWED",
        ),
        "payrollRevoked": (
            "hcm.operations.pay.read",
            "route.hcm.operations.payroll-foundation-receipt.data",
            "ALLOWED",
        ),
    }
    results: dict[str, dict[str, Any]] = {}
    for key, (capability, route, expected) in requests.items():
        scope_key = capability_scope(capability)
        decision = gateway_evaluate(
            state,
            session_a,
            name=f"41-tenant-a-gateway-{key}",
            route_contract_key=route,
            # Navigation and direct route contexts are independently recomputed.
            # The selected owner-derived scope is reusable; the entry context key is not.
            context_key=None,
            scope_key=scope_key,
        )
        revision = decision.get("decisionRevision")
        revalidate_at = decision.get("revalidateAt")
        if (
            decision.get("decision") != expected
            or not isinstance(revision, str)
            or not re.fullmatch(r"psr-[0-9a-f]{64}", revision)
            or not isinstance(revalidate_at, str)
            or not revalidate_at
        ):
            raise GateFailure(f"Tenant A Gateway {key} decision is invalid")
        route_context_key: str | None = None
        recorded_scope_key: str | None = None
        if expected == "ALLOWED":
            selected = decision.get("scope")
            selected_context = decision.get("context")
            if (
                not isinstance(selected, dict)
                or selected.get("key") != scope_key
                or not isinstance(selected_context, dict)
                or not re.fullmatch(
                    r"psc-[0-9a-f]{64}",
                    str(selected_context.get("contextKey", "")),
                )
            ):
                raise GateFailure(
                    f"Tenant A Gateway {key} lost its selected context/scope"
                )
            route_context_key = str(selected_context["contextKey"])
            recorded_scope_key = scope_key
            rebound = gateway_evaluate(
                state,
                session_a,
                name=f"41-tenant-a-gateway-{key}-rebound",
                route_contract_key=route,
                context_key=route_context_key,
                scope_key=scope_key,
            )
            if (
                rebound.get("decision") != expected
                or rebound.get("decisionRevision") != revision
                or not isinstance(rebound.get("context"), dict)
                or rebound["context"].get("contextKey") != route_context_key
                or not isinstance(rebound.get("scope"), dict)
                or rebound["scope"].get("key") != scope_key
            ):
                raise GateFailure(
                    f"Tenant A Gateway {key} did not revalidate its direct context"
                )
        elif (
            decision.get("reasonCode") != "STEP_UP_REQUIRED"
            or decision.get("requiredAssurance") != "urn:dwp:assurance:high"
            or decision.get("context") is not None
            or decision.get("scope") is not None
        ):
            raise GateFailure(
                f"Tenant A Gateway {key} exposed or weakened its HIGH challenge"
            )
        result: dict[str, Any] = {
            "routeContractKey": route,
            "contextKey": route_context_key,
            "scopeKey": recorded_scope_key,
            "decision": expected,
            "decisionRevision": revision,
            "revalidateAt": revalidate_at,
        }
        if expected == "STEP_UP_REQUIRED":
            result.update(
                {
                    "reasonCode": decision["reasonCode"],
                    "requiredAssurance": decision["requiredAssurance"],
                }
            )
        results[key] = result

    denied = gateway_evaluate(
        state,
        sessions["B"],
        name="42-tenant-b-gateway-payroll-feature-off",
        route_contract_key=(
            "route.hcm.operations.payroll-foundation-configurations.data"
        ),
        context_key=None,
        scope_key=None,
    )
    if denied.get("decision") not in {
        "APP_DENIED",
        "SURFACE_DENIED",
        "ROUTE_DENIED",
    }:
        raise GateFailure("Tenant B Gateway feature-off decision did not deny HCM")
    state.projection_feed["gatewayAuthorities"] = {
        "A": results,
        "B": {
            "payroll": {
                "decision": denied["decision"],
                "reasonCode": denied.get("reasonCode"),
                "decisionRevision": denied.get("decisionRevision"),
            }
        },
    }
    state.phase(
        "live-gateway-hcm-authority",
        "PASS",
        tenantA={key: value["decision"] for key, value in results.items()},
        tenantB=denied["decision"],
        derivedScopes=True,
        directAuthShortcut=False,
    )
    return results


def psql_as(
    container: str,
    database: str,
    role: str,
    password: str,
    sql: str,
    *,
    expect_success: bool = True,
    expected_sqlstate: str | None = None,
) -> str:
    for label, value in (("database", database), ("role", role)):
        if not re.fullmatch(r"[a-z][a-z0-9_]{1,62}", value):
            raise GateFailure(f"Unsafe {label} for service-principal SQL: {value}")
    result = subprocess.run(
        (
            "docker",
            "exec",
            "-i",
            container,
            "sh",
            "-c",
            "IFS= read -r PGPASSWORD; export PGPASSWORD; exec \"$@\"",
            "dwp-psql-as",
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "VERBOSITY=verbose",
            "-A",
            "-t",
            "-h",
            "127.0.0.1",
            "-U",
            role,
            "-d",
            database,
            "-f",
            "-",
        ),
        input=password + "\n" + sql,
        check=False,
        text=True,
        capture_output=True,
        timeout=180,
    )
    succeeded = result.returncode == 0
    if succeeded != expect_success:
        detail = (result.stderr or result.stdout or "").strip()[-1200:]
        expectation = "success" if expect_success else "failure"
        raise GateFailure(
            f"Service-principal SQL expected {expectation} for {role}: {detail}"
        )
    if not expect_success:
        if not isinstance(expected_sqlstate, str) or not re.fullmatch(
            r"[0-9A-Z]{5}", expected_sqlstate
        ):
            raise GateFailure(
                "A failed service-principal SQL assertion requires an exact SQLSTATE."
            )
        diagnostic = (result.stderr or "") + "\n" + (result.stdout or "")
        if not re.search(
            rf"(?:ERROR|FATAL):\s+{re.escape(expected_sqlstate)}\b", diagnostic
        ):
            raise GateFailure(
                f"Service-principal SQL for {role} failed with an unexpected "
                f"SQLSTATE; required {expected_sqlstate}."
            )
    return (result.stdout or "").strip()


def require_exact_publisher_acl(
    postgres: str,
    database: str,
    role: str,
    password: str,
    *,
    table_privileges: Sequence[str],
    update_columns: Sequence[str],
) -> None:
    actual_tables = psql_as(
        postgres,
        database,
        role,
        password,
        """
        SELECT table_name || '|' || privilege_type
          FROM information_schema.table_privileges
         WHERE grantee=current_user AND table_schema='public'
         ORDER BY table_name, privilege_type;
        """,
    ).splitlines()
    actual_columns = psql_as(
        postgres,
        database,
        role,
        password,
        """
        SELECT table_name || '|' || column_name || '|UPDATE'
          FROM information_schema.column_privileges
         WHERE grantee=current_user AND table_schema='public'
           AND privilege_type='UPDATE'
         ORDER BY table_name, column_name;
        """,
    ).splitlines()
    if actual_tables != sorted(table_privileges) or actual_columns != sorted(update_columns):
        raise GateFailure(
            f"Publisher ACL inventory mismatch for {role}: "
            f"tables={actual_tables}, updateColumns={actual_columns}"
        )


def bootstrap_payroll_foundation_fixture(
    state: GateState,
    secrets_: RuntimeSecrets,
    tenant: SyntheticTenantCredential,
    payroll_entry: dict[str, str],
    session: GatewayBrowserSession,
) -> dict[str, Any]:
    author_actor_id = tenant.administrator_user_id + 1_000_000_000
    if author_actor_id == tenant.administrator_user_id:
        raise GateFailure("Synthetic payroll author must differ from the browser administrator")
    payload = {
        "runId": state.run_id,
        "tenantId": tenant.tenant_id,
        "authorActorId": author_actor_id,
        "legalEntityId": payroll_entry["legalEntityId"],
    }
    header = {
        "X-DWP-Synthetic-Payroll-Bootstrap-Token": (
            secrets_.synthetic_payroll_foundation_bootstrap_token
        )
    }
    http_request(
        state,
        name="41-synthetic-payroll-foundation-wrong-token-denied",
        port=state.ports["payroll"],
        method="POST",
        path="/internal/synthetic/v1/payroll-foundation/bootstrap",
        headers={"X-DWP-Synthetic-Payroll-Bootstrap-Token": "x" * 40},
        payload=payload,
        expected_status=401,
    )
    wrong_run = (
        state.run_id[:-8]
        + ("feedface" if not state.run_id.endswith("feedface") else "deadbeef")
    )
    http_request(
        state,
        name="42-synthetic-payroll-foundation-wrong-run-denied",
        port=state.ports["payroll"],
        method="POST",
        path="/internal/synthetic/v1/payroll-foundation/bootstrap",
        headers=header,
        payload={**payload, "runId": wrong_run},
        expected_status=403,
    )
    _, fixture, _ = http_request(
        state,
        name="43-synthetic-payroll-foundation-bootstrap",
        port=state.ports["payroll"],
        method="POST",
        path="/internal/synthetic/v1/payroll-foundation/bootstrap",
        headers=header,
        payload=payload,
    )
    expected_fixture_receipt = ""
    if isinstance(fixture, dict):
        expected_fixture_receipt = hashlib.sha256("|".join((
            state.run_id,
            str(tenant.tenant_id),
            str(author_actor_id),
            payroll_entry["legalEntityId"],
            str(fixture.get("configurationId")),
            str(fixture.get("version")),
            str(fixture.get("createCommandId")),
            str(fixture.get("simulateCommandId")),
        )).encode("utf-8")).hexdigest()
    if (
        not isinstance(fixture, dict)
        or fixture.get("runId") != state.run_id
        or fixture.get("tenantId") != tenant.tenant_id
        or fixture.get("authorActorId") != author_actor_id
        or fixture.get("legalEntityId") != payroll_entry["legalEntityId"]
        or fixture.get("lifecycleState") != "SIMULATED"
        or fixture.get("dependencyFreshness") != "LIVE"
        or fixture.get("version") != 2
        or not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
            str(fixture.get("configurationId", "")),
        )
        or not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
            str(fixture.get("createCommandId", "")),
        )
        or not re.fullmatch(
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
            str(fixture.get("simulateCommandId", "")),
        )
        or fixture.get("createCommandId") == fixture.get("simulateCommandId")
        or fixture.get("receiptSha256") != expected_fixture_receipt
    ):
        raise GateFailure("Synthetic payroll foundation fixture response is invalid")
    http_request(
        state,
        name="44-synthetic-payroll-foundation-one-shot",
        port=state.ports["payroll"],
        method="POST",
        path="/internal/synthetic/v1/payroll-foundation/bootstrap",
        headers=header,
        payload=payload,
        expected_status=409,
    )

    _, workspace_envelope, _ = gateway_session_request(
        state,
        session,
        name="45-tenant-a-payroll-publish-preview-readiness",
        method="GET",
        path=(
            "/api/payroll/v1/hris/payroll/foundation/configurations"
            f"?contextScopeKey={payroll_entry['scopeKey']}"
        ),
    )
    workspace = (
        workspace_envelope.get("data")
        if isinstance(workspace_envelope, dict)
        else None
    )
    configurations = workspace.get("configurations") if isinstance(workspace, dict) else None
    matching = [
        item
        for item in configurations or []
        if isinstance(item, dict)
        and item.get("configurationId") == fixture["configurationId"]
    ]
    if len(matching) != 1:
        raise GateFailure("Synthetic payroll foundation is absent from the owner read model")
    view = matching[0]
    access = view.get("access")
    freshness = view.get("freshness")
    if (
        view.get("status") != "SIMULATED"
        or view.get("authorId") == tenant.administrator_user_id
        or not isinstance(access, dict)
        or access.get("canPublish") is not True
        or access.get("publishDenialCode") is not None
        or not isinstance(freshness, dict)
        or freshness.get("state") != "LIVE"
    ):
        raise GateFailure(
            "Tenant A payroll foundation does not satisfy HIGH preview prerequisites"
        )
    return {
        "configurationId": fixture["configurationId"],
        "version": fixture["version"],
        "lifecycleState": fixture["lifecycleState"],
        "dependencyFreshness": fixture["dependencyFreshness"],
        "authorActorId": author_actor_id,
        "browserActorId": tenant.administrator_user_id,
        "canPublish": True,
        "createCommandId": fixture["createCommandId"],
        "simulateCommandId": fixture["simulateCommandId"],
        "receiptSha256": fixture["receiptSha256"],
    }


def observe_payroll_foundation_preflight(
    postgres: str,
    state: GateState,
    tenant: SyntheticTenantCredential,
    fixture: dict[str, Any],
) -> dict[str, Any]:
    raw = psql(
        postgres,
        "dwp_payroll",
        f"""
        SELECT jsonb_build_object(
            'schemaVersion', 1,
            'phase', 'PREFLIGHT',
            'tenantId', configuration.tenant_id,
            'configurationId', configuration.configuration_id,
            'currentVersion', configuration.current_version,
            'lifecycleState', configuration.lifecycle_state,
            'legalEntityId', configuration.legal_entity_id,
            'authorId', configuration.author_id,
            'publisherId', configuration.publisher_id,
            'lastCommandId', configuration.last_command_id,
            'fixtureReceiptSha256', {sql_literal(fixture['receiptSha256'])},
            'definitionDigest', version.definition_digest,
            'dependencyDigest', version.dependency_digest,
            'lastCommandReceipt', jsonb_build_object(
                'commandId', receipt.command_id,
                'commandType', receipt.command_type,
                'receiptStatus', receipt.receipt_status,
                'resultVersion', receipt.result_version,
                'requestDigest', receipt.request_digest))::text
          FROM pay_foundation_configurations configuration
          JOIN pay_foundation_versions version
            ON version.tenant_id=configuration.tenant_id
           AND version.configuration_id=configuration.configuration_id
           AND version.version=configuration.current_version
          JOIN pay_foundation_command_receipts receipt
            ON receipt.tenant_id=configuration.tenant_id
           AND receipt.command_id=configuration.last_command_id
         WHERE configuration.tenant_id={tenant.tenant_id}
           AND configuration.configuration_id=
               {sql_literal(fixture['configurationId'])}::uuid;
        """,
    ).strip()
    try:
        observation = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as error:
        raise GateFailure(
            "PAY foundation preflight database observation is not exact JSON"
        ) from error
    if not isinstance(observation, dict):
        raise GateFailure("PAY foundation preflight database observation is absent")
    receipt = observation.get("lastCommandReceipt")
    expected = {
        "schemaVersion": 1,
        "phase": "PREFLIGHT",
        "tenantId": tenant.tenant_id,
        "configurationId": fixture["configurationId"],
        "currentVersion": 2,
        "lifecycleState": "SIMULATED",
        "legalEntityId": tenant.actor_legal_employer_public_id,
        "authorId": fixture["authorActorId"],
        "publisherId": None,
        "lastCommandId": fixture["simulateCommandId"],
        "fixtureReceiptSha256": fixture["receiptSha256"],
    }
    drift = {
        key: {"expected": value, "actual": observation.get(key)}
        for key, value in expected.items()
        if not isinstance(observation, dict) or observation.get(key) != value
    }
    if (
        drift
        or set(observation) != {
            *expected,
            "definitionDigest",
            "dependencyDigest",
            "lastCommandReceipt",
        }
        or not re.fullmatch(r"[0-9a-f]{64}", str(observation.get("definitionDigest", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(observation.get("dependencyDigest", "")))
        or not isinstance(receipt, dict)
        or set(receipt) != {
            "commandId", "commandType", "receiptStatus", "resultVersion", "requestDigest"
        }
        or receipt.get("commandId") != fixture["simulateCommandId"]
        or receipt.get("commandType") != "SIMULATE"
        or receipt.get("receiptStatus") != "SUCCEEDED"
        or receipt.get("resultVersion") != 2
        or not re.fullmatch(r"[0-9a-f]{64}", str(receipt.get("requestDigest", "")))
    ):
        raise GateFailure(
            f"PAY foundation preflight database lineage is invalid: {drift}"
        )
    observation["observationSha256"] = canonical_json_sha256(observation)
    atomic_write_json(
        state.output_dir / "db" / "payroll-foundation-preflight.json",
        observation,
    )
    return observation


def seed_trusted_projection_feeds(
    postgres: str,
    state: GateState,
    secrets_: RuntimeSecrets,
    credentials: Sequence[SyntheticTenantCredential],
    authorities: dict[str, dict[str, Any]],
    sessions: dict[str, GatewayBrowserSession],
) -> None:
    by_lane = {credential.lane: credential for credential in credentials}
    tenant_a = by_lane["A"]
    required_authorities = {
        "payroll",
        "time",
        "payrollStale",
        "payrollExpired",
        "payrollRevoked",
    }
    if (
        set(by_lane) != {"A", "B"}
        or set(sessions) != {"A", "B"}
        or not required_authorities.issubset(authorities)
    ):
        raise GateFailure("Projection feeds require exact A/B and live PAY/TIM authority")
    payroll_authority = authorities["payroll"]
    payroll_scope_a = payroll_authority["scopeKey"]
    negative_revisions = [
        authorities[key]["decisionRevision"]
        for key in ("payrollStale", "payrollExpired", "payrollRevoked")
    ]
    if len(set([payroll_authority["decisionRevision"], *negative_revisions])) != 4:
        raise GateFailure(
            "PAY positive and negative observations require four distinct governed decisions"
        )
    rollout_revision = state.projection_feed["rollouts"][tenant_a.lane]["revision"]
    legal_entity_id = uuid.UUID(tenant_a.actor_legal_employer_public_id)

    def publish_negative_projection(
        evidence_state: str,
        authority: dict[str, str],
        *,
        valid_until: str,
        terminal_state: str | None,
    ) -> dict[str, Any]:
        projection_id = uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"dwp:{state.run_id}:payroll:negative:{evidence_state.lower()}",
        )
        projection_revision = hashlib.sha256(
            f"{state.run_id}|payroll|negative|{evidence_state}".encode("utf-8")
        ).hexdigest()
        transition = (
            "UPDATE pay_legal_entity_scope_projections SET status='ACTIVE' "
            f"WHERE tenant_id={tenant_a.tenant_id} "
            f"AND projection_id={sql_literal(str(projection_id))}::uuid;"
        )
        if terminal_state:
            transition += (
                " UPDATE pay_legal_entity_scope_projections "
                f"SET status={sql_literal(terminal_state)}, "
                "valid_until=CURRENT_TIMESTAMP "
                f"WHERE tenant_id={tenant_a.tenant_id} "
                f"AND projection_id={sql_literal(str(projection_id))}::uuid;"
            )
        psql_as(
            postgres,
            "dwp_payroll",
            "dwp_payroll_projection_publisher",
            secrets_.payroll_projection_publisher_password,
            f"""
            BEGIN;
            SELECT set_config('dwp.payroll_tenant_id',
                              {sql_literal(str(tenant_a.tenant_id))}, true);
            INSERT INTO pay_legal_entity_scope_projections (
                tenant_id, projection_id, actor_id, context_scope_key,
                policy_revision, authorization_revision, projection_revision,
                status, valid_from, valid_until, recorded_at)
            VALUES (
                {tenant_a.tenant_id}, {sql_literal(str(projection_id))}::uuid,
                {tenant_a.administrator_user_id},
                {sql_literal(authority['scopeKey'])},
                {sql_literal(rollout_revision)},
                {sql_literal(authority['decisionRevision'])},
                {sql_literal(projection_revision)},
                'BUILDING', CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                {valid_until}, CURRENT_TIMESTAMP);
            INSERT INTO pay_legal_entity_scope_members (
                tenant_id, projection_id, legal_entity_id)
            VALUES (
                {tenant_a.tenant_id}, {sql_literal(str(projection_id))}::uuid,
                {sql_literal(str(legal_entity_id))}::uuid);
            {transition}
            COMMIT;
            """,
        )
        observed = psql_as(
            postgres,
            "dwp_payroll",
            "dwp_payroll_projection_publisher",
            secrets_.payroll_projection_publisher_password,
            f"""
            SELECT set_config('dwp.payroll_tenant_id',
                              {sql_literal(str(tenant_a.tenant_id))}, false);
            SELECT projection.projection_id::text || '|' ||
                   projection.projection_revision || '|' || projection.status || '|' ||
                   CASE WHEN projection.valid_until <= CURRENT_TIMESTAMP
                        THEN 'EXPIRED' ELSE 'CURRENT' END || '|' ||
                   projection.context_scope_key || '|' || projection.policy_revision || '|' ||
                   projection.authorization_revision || '|' ||
                   count(member.projection_id)::text
              FROM pay_legal_entity_scope_projections projection
              LEFT JOIN pay_legal_entity_scope_members member
                ON member.tenant_id=projection.tenant_id
               AND member.projection_id=projection.projection_id
             WHERE projection.tenant_id={tenant_a.tenant_id}
               AND projection.projection_id={sql_literal(str(projection_id))}::uuid
             GROUP BY projection.projection_id, projection.projection_revision,
                      projection.status, projection.valid_until,
                      projection.context_scope_key, projection.policy_revision,
                      projection.authorization_revision;
            """,
        ).splitlines()
        if len(observed) < 2:
            raise GateFailure(
                f"PAY {evidence_state} projection was not observed after transition"
            )
        row = observed[-1].split("|")
        if len(row) != 8:
            raise GateFailure(
                f"PAY {evidence_state} projection observation is malformed"
            )
        transition = NEGATIVE_PROJECTION_STATES[evidence_state][0]
        material: dict[str, Any] = {
            "tenantId": tenant_a.tenant_id,
            "actorId": tenant_a.administrator_user_id,
            "evidenceState": evidence_state,
            "projectionId": row[0],
            "projectionRevision": row[1],
            "contextScopeKey": row[4],
            "policyRevision": row[5],
            "authorizationRevision": row[6],
            "databaseTransition": transition,
            "databaseStatus": row[2],
            "databaseValidity": row[3],
            "databaseMemberCount": int(row[7]),
        }
        expected = {
            "projectionId": str(projection_id),
            "projectionRevision": projection_revision,
            "contextScopeKey": authority["scopeKey"],
            "policyRevision": rollout_revision,
            "authorizationRevision": authority["decisionRevision"],
            "databaseStatus": NEGATIVE_PROJECTION_STATES[evidence_state][1],
            "databaseValidity": NEGATIVE_PROJECTION_STATES[evidence_state][2],
            "databaseMemberCount": 1,
        }
        drift = {
            key: {"expected": value, "actual": material.get(key)}
            for key, value in expected.items()
            if material.get(key) != value
        }
        if drift:
            raise GateFailure(
                f"PAY {evidence_state} projection transition drift: {drift}"
            )
        material["projectionObservationSha256"] = canonical_json_sha256(material)
        return material

    negative_specs = (
        (
            "negative.stale-evidence-denied",
            "STALE",
            "payrollStale",
            "/api/payroll/v1/hris/payroll/foundation/configurations/"
            + str(uuid.uuid5(uuid.NAMESPACE_URL, f"dwp:{state.run_id}:negative:stale")),
            "NULL",
            "SUPERSEDED",
        ),
        (
            "negative.expired-evidence-denied",
            "EXPIRED",
            "payrollExpired",
            "/api/payroll/v1/hris/payroll/foundation/configurations/"
            + str(uuid.uuid5(uuid.NAMESPACE_URL, f"dwp:{state.run_id}:negative:expired"))
            + "/versions",
            "CURRENT_TIMESTAMP - INTERVAL '1 minute'",
            None,
        ),
        (
            "negative.revoked-evidence-denied",
            "REVOKED",
            "payrollRevoked",
            "/api/payroll/v1/hris/payroll/foundation/receipts/"
            + str(uuid.uuid5(uuid.NAMESPACE_URL, f"dwp:{state.run_id}:negative:revoked")),
            "NULL",
            "REVOKED",
        ),
    )
    observations: list[dict[str, Any]] = []
    negative_projections: dict[str, dict[str, Any]] = {}
    for assertion_name, evidence_state, authority_key, path, valid_until, terminal in negative_specs:
        projection = publish_negative_projection(
            evidence_state,
            authorities[authority_key],
            valid_until=valid_until,
            terminal_state=terminal,
        )
        request_path = (
            f"{path}?contextScopeKey={projection['contextScopeKey']}"
        )
        status, error, body = gateway_session_request(
            state,
            sessions["A"],
            name=f"46-payroll-{evidence_state.lower()}-owner-denied",
            method="GET",
            path=request_path,
            expected_status=NEGATIVE_OWNER_STATUS,
        )
        error_code = None
        if isinstance(error, dict):
            error_code = error.get("errorCode") or error.get("code")
        if (
            not isinstance(error, dict)
            or error.get("status") != "ERROR"
            or error.get("success") is not False
            or error_code != NEGATIVE_OWNER_ERROR_CODE
            or error.get("message") != NEGATIVE_OWNER_ERROR_MESSAGE
        ):
            raise GateFailure(
                f"PAY {evidence_state} denial did not originate from owner projection resolution"
            )
        observation: dict[str, Any] = {
            "assertionName": assertion_name,
            "source": "LIVE_GATEWAY_OWNER_REQUEST",
            "method": "GET",
            "path": request_path,
            "tenantId": tenant_a.tenant_id,
            "actorId": tenant_a.administrator_user_id,
            "evidenceState": evidence_state,
            **projection,
            "status": status,
            "errorCode": error_code,
            "ownerErrorMessage": error["message"],
            "observedAt": utc_now(),
            "responseBodySha256": hashlib.sha256(body).hexdigest(),
        }
        observation["observationSha256"] = canonical_json_sha256(observation)
        validate_negative_observation(
            observation,
            assertion_name=assertion_name,
            state=state,
        )
        observations.append(observation)
        negative_projections[evidence_state] = projection
        if evidence_state == "EXPIRED":
            psql_as(
                postgres,
                "dwp_payroll",
                "dwp_payroll_projection_publisher",
                secrets_.payroll_projection_publisher_password,
                f"""
                BEGIN;
                SELECT set_config('dwp.payroll_tenant_id',
                                  {sql_literal(str(tenant_a.tenant_id))}, true);
                UPDATE pay_legal_entity_scope_projections
                   SET status='SUPERSEDED'
                 WHERE tenant_id={tenant_a.tenant_id}
                   AND projection_id={sql_literal(projection['projectionId'])}::uuid;
                COMMIT;
                """,
            )
    state.projection_feed["negativeObservations"] = {
        "schemaVersion": 1,
        "observations": observations,
        "aggregateSha256": canonical_json_sha256(observations),
    }
    state.projection_feed["negativeObservationProjections"] = {
        "schemaVersion": 1,
        "disposable": True,
        "restoredPositiveAfterObservations": True,
        "projections": negative_projections,
        "aggregateSha256": canonical_json_sha256(negative_projections),
    }

    payroll_entries: dict[str, dict[str, str]] = {}
    for credential, scope_key, status, authority_revision in ((
        tenant_a,
        payroll_scope_a,
        "ACTIVE",
        payroll_authority["decisionRevision"],
    ),):
        projection_id = uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"dwp:{state.run_id}:payroll:{credential.lane}:projection",
        )
        legal_entity_id = uuid.UUID(credential.actor_legal_employer_public_id)
        rollout_revision = state.projection_feed["rollouts"][credential.lane][
            "revision"
        ]
        projection_revision = hashlib.sha256(
            f"{state.run_id}|payroll|{credential.lane}|1".encode("utf-8")
        ).hexdigest()
        terminal = (
            "UPDATE pay_legal_entity_scope_projections "
            "SET status='ACTIVE' "
            f"WHERE tenant_id={credential.tenant_id} "
            f"AND projection_id={sql_literal(str(projection_id))}::uuid;"
        )
        if status == "REVOKED":
            terminal += (
                " UPDATE pay_legal_entity_scope_projections "
                "SET status='REVOKED', valid_until=CURRENT_TIMESTAMP "
                f"WHERE tenant_id={credential.tenant_id} "
                f"AND projection_id={sql_literal(str(projection_id))}::uuid;"
            )
        sql = f"""
            BEGIN;
            SELECT set_config('dwp.payroll_tenant_id',
                              {sql_literal(str(credential.tenant_id))}, true);
            INSERT INTO pay_legal_entity_scope_projections (
                tenant_id, projection_id, actor_id, context_scope_key,
                policy_revision, authorization_revision, projection_revision,
                status, valid_from, valid_until, recorded_at)
            VALUES (
                {credential.tenant_id}, {sql_literal(str(projection_id))}::uuid,
                {credential.administrator_user_id}, {sql_literal(scope_key)},
                {sql_literal(rollout_revision)}, {sql_literal(authority_revision)},
                {sql_literal(projection_revision)}, 'BUILDING',
                CURRENT_TIMESTAMP - INTERVAL '5 minutes', NULL, CURRENT_TIMESTAMP);
            INSERT INTO pay_legal_entity_scope_members (
                tenant_id, projection_id, legal_entity_id)
            VALUES (
                {credential.tenant_id}, {sql_literal(str(projection_id))}::uuid,
                {sql_literal(str(legal_entity_id))}::uuid);
            {terminal}
            COMMIT;
        """
        psql_as(
            postgres,
            "dwp_payroll",
            "dwp_payroll_projection_publisher",
            secrets_.payroll_projection_publisher_password,
            sql,
        )
        payroll_entries[credential.lane] = {
            "scopeKey": scope_key,
            "projectionId": str(projection_id),
            "legalEntityId": str(legal_entity_id),
            "status": status,
            "policyRevision": rollout_revision,
            "authorizationRevision": authority_revision,
        }

    time_entries: dict[str, dict[str, Any]] = {}
    for credential, lifecycle in ((tenant_a, "ACTIVE"),):
        scope_key = authorities["time"]["scopeKey"]
        population_id = uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"dwp:{state.run_id}:time:{credential.lane}:population",
        )
        worker_id = uuid.UUID(credential.target_worker_public_id)
        assignment_id = uuid.UUID(credential.target_assignment_public_id)
        digest = lambda kind: hashlib.sha256(
            (
                f"{state.run_id}|time|{credential.lane}|{kind}|"
                f"{credential.target_population_revision}"
            ).encode("utf-8")
        ).hexdigest()
        valid_to = (
            "CURRENT_TIMESTAMP + INTERVAL '1 day'"
            if lifecycle == "ACTIVE"
            else "CURRENT_TIMESTAMP + INTERVAL '5 minutes'"
        )
        effective_to = "CURRENT_DATE + 1"
        sql = f"""
            BEGIN;
            SELECT set_config('dwp.tenant_id',
                              {sql_literal(str(credential.tenant_id))}, true);
            INSERT INTO tim_target_population_projections (
                tenant_id, population_public_id, scope_public_ref,
                projection_revision, lifecycle_state, effective_from, effective_to,
                source_digest, updated_by)
            VALUES (
                {credential.tenant_id}, {sql_literal(str(population_id))}::uuid,
                {sql_literal('population:' + str(population_id))}, 1,
                {sql_literal(lifecycle)}, CURRENT_DATE - 1, {effective_to},
                {sql_literal(digest('population'))},
                {credential.administrator_user_id});
            INSERT INTO tim_target_population_actor_grants (
                tenant_id, actor_id, gateway_scope_key, population_public_id,
                population_revision, grant_revision, lifecycle_state,
                valid_from, valid_to, source_digest, updated_by)
            VALUES (
                {credential.tenant_id}, {credential.administrator_user_id},
                {sql_literal(scope_key)}, {sql_literal(str(population_id))}::uuid,
                1, 1, {sql_literal(lifecycle)},
                CURRENT_TIMESTAMP - INTERVAL '5 minutes', {valid_to},
                {sql_literal(digest('grant'))}, {credential.administrator_user_id});
            INSERT INTO tim_target_population_members (
                tenant_id, population_public_id, population_revision,
                worker_public_id, people_assignment_public_id,
                people_assignment_revision, membership_revision, lifecycle_state,
                effective_from, effective_to, source_digest, updated_by)
            VALUES (
                {credential.tenant_id}, {sql_literal(str(population_id))}::uuid, 1,
                {sql_literal(str(worker_id))}::uuid,
                {sql_literal(str(assignment_id))}::uuid,
                1, 1, {sql_literal(lifecycle)}, CURRENT_DATE - 1,
                {effective_to}, {sql_literal(digest('membership'))},
                {credential.administrator_user_id});
            COMMIT;
        """
        psql_as(
            postgres,
            "dwp_time",
            "dwp_time_projection_publisher",
            secrets_.time_projection_publisher_password,
            sql,
        )
        time_entries[credential.lane] = {
            "scopeKey": scope_key,
            "populationPublicId": str(population_id),
            "workerPublicId": str(worker_id),
            "peopleAssignmentPublicId": str(assignment_id),
            "peopleAssignmentRevision": 1,
            "lifecycleState": lifecycle,
            "peopleTargetPersonPublicId": credential.target_person_public_id,
            "peopleTargetPopulationRevision": (
                credential.target_population_revision
            ),
            "populationIdentityKind": "TIM_PROJECTION_RUN_BOUND",
        }

    denied_payroll_projection_id = uuid.uuid5(
        uuid.NAMESPACE_URL, f"dwp:{state.run_id}:payroll:runtime-denied"
    )
    payroll_runtime_insert = f"""
        BEGIN;
        SELECT set_config('dwp.payroll_tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        INSERT INTO pay_legal_entity_scope_projections (
            tenant_id, projection_id, actor_id, context_scope_key,
            policy_revision, authorization_revision, projection_revision,
            status, valid_from, valid_until, recorded_at)
        VALUES (
            {tenant_a.tenant_id},
            {sql_literal(str(denied_payroll_projection_id))}::uuid,
            {tenant_a.administrator_user_id},
            {sql_literal(payroll_entries['A']['scopeKey'])},
            {sql_literal(payroll_entries['A']['policyRevision'])},
            {sql_literal(payroll_entries['A']['authorizationRevision'])},
            'runtime-denied-1', 'BUILDING', CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP);
        ROLLBACK;
    """
    psql_as(
        postgres,
        "dwp_payroll",
        "dwp_payroll_runtime",
        secrets_.payroll_password,
        payroll_runtime_insert,
        expect_success=False,
        expected_sqlstate="42501",
    )
    payroll_a_projection = payroll_entries["A"]["projectionId"]
    for statement in (
        f"""
        BEGIN;
        SELECT set_config('dwp.payroll_tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        UPDATE pay_legal_entity_scope_projections
           SET valid_until=CURRENT_TIMESTAMP + INTERVAL '1 day'
         WHERE tenant_id={tenant_a.tenant_id}
           AND projection_id={sql_literal(payroll_a_projection)}::uuid;
        ROLLBACK;
        """,
        f"""
        BEGIN;
        SELECT set_config('dwp.payroll_tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        DELETE FROM pay_legal_entity_scope_projections
         WHERE tenant_id={tenant_a.tenant_id}
           AND projection_id={sql_literal(payroll_a_projection)}::uuid;
        ROLLBACK;
        """,
    ):
        psql_as(
            postgres,
            "dwp_payroll",
            "dwp_payroll_runtime",
            secrets_.payroll_password,
            statement,
            expect_success=False,
            expected_sqlstate="42501",
        )
    psql_as(
        postgres,
        "dwp_payroll",
        "dwp_payroll_projection_publisher",
        secrets_.payroll_projection_publisher_password,
        f"""
        BEGIN;
        SELECT set_config('dwp.payroll_tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        SELECT 1 FROM pay_foundation_configurations
         WHERE tenant_id={tenant_a.tenant_id} LIMIT 1;
        ROLLBACK;
        """,
        expect_success=False,
        expected_sqlstate="42501",
    )
    psql_as(
        postgres,
        "dwp_time",
        "dwp_time_runtime",
        secrets_.time_password,
        f"""
        BEGIN;
        SELECT set_config('dwp.tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        INSERT INTO tim_target_population_projections (
            tenant_id, population_public_id, scope_public_ref,
            projection_revision, lifecycle_state, effective_from, effective_to,
            source_digest, updated_by)
        VALUES (
            {tenant_a.tenant_id},
            {sql_literal(str(uuid.uuid5(uuid.NAMESPACE_URL, f'dwp:{state.run_id}:time:runtime-denied')))}::uuid,
            {sql_literal('population:' + str(uuid.uuid5(uuid.NAMESPACE_URL, f'dwp:{state.run_id}:time:runtime-denied')))},
            1, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 1,
            {sql_literal(hashlib.sha256(f'{state.run_id}|time|runtime-denied'.encode('utf-8')).hexdigest())},
            {tenant_a.administrator_user_id});
        ROLLBACK;
        """,
        expect_success=False,
        expected_sqlstate="42501",
    )
    time_a_population = time_entries["A"]["populationPublicId"]
    for statement in (
        f"""
        BEGIN;
        SELECT set_config('dwp.tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        UPDATE tim_target_population_projections
           SET projection_revision=projection_revision + 1
         WHERE tenant_id={tenant_a.tenant_id}
           AND population_public_id={sql_literal(time_a_population)}::uuid;
        ROLLBACK;
        """,
        f"""
        BEGIN;
        SELECT set_config('dwp.tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        DELETE FROM tim_target_population_projections
         WHERE tenant_id={tenant_a.tenant_id}
           AND population_public_id={sql_literal(time_a_population)}::uuid;
        ROLLBACK;
        """,
    ):
        psql_as(
            postgres,
            "dwp_time",
            "dwp_time_runtime",
            secrets_.time_password,
            statement,
            expect_success=False,
            expected_sqlstate="42501",
        )
    psql_as(
        postgres,
        "dwp_time",
        "dwp_time_projection_publisher",
        secrets_.time_projection_publisher_password,
        f"""
        BEGIN;
        SELECT set_config('dwp.tenant_id',
                          {sql_literal(str(tenant_a.tenant_id))}, true);
        SELECT 1 FROM tim_work_regime_versions
         WHERE tenant_id={tenant_a.tenant_id} LIMIT 1;
        ROLLBACK;
        """,
        expect_success=False,
        expected_sqlstate="42501",
    )
    require_exact_publisher_acl(
        postgres,
        "dwp_payroll",
        "dwp_payroll_projection_publisher",
        secrets_.payroll_projection_publisher_password,
        table_privileges=(
            "pay_legal_entity_scope_members|INSERT",
            "pay_legal_entity_scope_members|SELECT",
            "pay_legal_entity_scope_projections|INSERT",
            "pay_legal_entity_scope_projections|SELECT",
        ),
        update_columns=(
            "pay_legal_entity_scope_projections|status|UPDATE",
            "pay_legal_entity_scope_projections|valid_until|UPDATE",
        ),
    )
    require_exact_publisher_acl(
        postgres,
        "dwp_time",
        "dwp_time_projection_publisher",
        secrets_.time_projection_publisher_password,
        table_privileges=(
            "tim_target_population_actor_grants|INSERT",
            "tim_target_population_actor_grants|SELECT",
            "tim_target_population_members|INSERT",
            "tim_target_population_members|SELECT",
            "tim_target_population_projections|INSERT",
            "tim_target_population_projections|SELECT",
        ),
        update_columns=(
            "tim_target_population_actor_grants|grant_revision|UPDATE",
            "tim_target_population_actor_grants|lifecycle_state|UPDATE",
            "tim_target_population_actor_grants|population_public_id|UPDATE",
            "tim_target_population_actor_grants|population_revision|UPDATE",
            "tim_target_population_actor_grants|source_digest|UPDATE",
            "tim_target_population_actor_grants|updated_at|UPDATE",
            "tim_target_population_actor_grants|updated_by|UPDATE",
            "tim_target_population_actor_grants|valid_from|UPDATE",
            "tim_target_population_actor_grants|valid_to|UPDATE",
            "tim_target_population_members|effective_from|UPDATE",
            "tim_target_population_members|effective_to|UPDATE",
            "tim_target_population_members|lifecycle_state|UPDATE",
            "tim_target_population_members|membership_revision|UPDATE",
            "tim_target_population_members|people_assignment_revision|UPDATE",
            "tim_target_population_members|population_revision|UPDATE",
            "tim_target_population_members|source_digest|UPDATE",
            "tim_target_population_members|updated_at|UPDATE",
            "tim_target_population_members|updated_by|UPDATE",
            "tim_target_population_projections|effective_from|UPDATE",
            "tim_target_population_projections|effective_to|UPDATE",
            "tim_target_population_projections|lifecycle_state|UPDATE",
            "tim_target_population_projections|projection_revision|UPDATE",
            "tim_target_population_projections|source_digest|UPDATE",
            "tim_target_population_projections|updated_at|UPDATE",
            "tim_target_population_projections|updated_by|UPDATE",
        ),
    )
    payroll_foundation = bootstrap_payroll_foundation_fixture(
        state,
        secrets_,
        tenant_a,
        payroll_entries["A"],
        sessions["A"],
    )
    payroll_foundation_database = observe_payroll_foundation_preflight(
        postgres,
        state,
        tenant_a,
        payroll_foundation,
    )
    state.projection_feed.update(
        {
            "payroll": payroll_entries,
            "time": time_entries,
            "payrollAuthority": payroll_authority,
            "payrollFoundation": payroll_foundation,
            "payrollFoundationDatabaseObservation": payroll_foundation_database,
            "runtimeMutationDenied": True,
            "publisherForbiddenTableDenied": True,
        }
    )
    state.phase(
        "trusted-payroll-time-projection-feeds",
        "PASS",
        payrollStates={lane: item["status"] for lane, item in payroll_entries.items()},
        timeStates={
            lane: item["lifecycleState"] for lane, item in time_entries.items()
        },
        runtimeMutationDenied=True,
        publisherForbiddenTableDenied=True,
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
        if name in ("people", "payroll", "time"):
            receipt = state.control_receipts.get(name)
            if receipt is None:
                raise GateFailure(
                    f"{name} cannot start without its Migration Control receipt"
                )
            prefix = f"DWP_{name.upper()}"
            environment.update(
                {
                    f"{prefix}_MIGRATION_CONTROL_RUN_RECEIPT_JSON": json.dumps(
                        receipt, separators=(",", ":"), sort_keys=True
                    ),
                    f"{prefix}_MIGRATION_CONTROL_RUN_RECEIPT_SHA256": str(
                        receipt["receiptSha256"]
                    ),
                    f"{prefix}_MIGRATION_CONTROL_REFERENCE": str(
                        receipt["controlReference"]
                    ),
                }
            )
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
        "migrationControl": {
            service: {
                "receiptSha256": receipt["receiptSha256"],
                "controlReference": receipt["controlReference"],
                "mode": receipt["mode"],
            }
            for service, receipt in sorted(state.control_receipts.items())
        },
        "syntheticTenants": state.synthetic_tenants,
        "projectionFeed": state.projection_feed,
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
    try:
        metadata = executable.lstat()
    except OSError as error:
        raise GateFailure(f"Checkpoint executable is unavailable: {error}") from error
    if (
        stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISREG(metadata.st_mode)
        or not os.access(executable, os.X_OK)
    ):
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
    executable_bytes = _attested_regular_bytes(
        executable, require_executable=True
    )
    actual_sha256 = hashlib.sha256(executable_bytes).hexdigest()
    if actual_sha256 != expected_sha256:
        raise GateFailure(
            "Checkpoint executable SHA-256 does not match the supplied pin."
        )
    return {
        "path": str(executable),
        "sha256": actual_sha256,
        "byteCount": len(executable_bytes),
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
    try:
        manifest_relative = manifest_path.relative_to(state.output_dir)
    except ValueError as error:
        raise GateFailure(
            "External live checkpoint manifest escapes the run directory."
        ) from error
    manifest_bytes = _attested_output_regular_bytes(
        state.output_dir,
        manifest_relative,
        max_bytes=MAX_CHECKPOINT_MANIFEST_BYTES,
    )
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
    provenance = manifest.get("provenance")
    runtime_provenance = (
        provenance.get("runtimeManifest") if isinstance(provenance, dict) else None
    )
    preflight = state.projection_feed.get("payrollFoundationDatabaseObservation")
    if (
        not isinstance(provenance, dict)
        or set(provenance) != {"frontend", "runtimeManifest", "browser"}
        or not isinstance(provenance.get("frontend"), dict)
        or not isinstance(provenance.get("browser"), dict)
        or not isinstance(runtime_provenance, dict)
        or set(runtime_provenance) != {
            "path",
            "sha256",
            "byteCount",
            "negativeObservationAggregateSha256",
            "payrollFoundationDatabaseObservationSha256",
        }
        or runtime_provenance.get("path") != "runtime.json"
        or not re.fullmatch(r"[0-9a-f]{64}", str(runtime_provenance.get("sha256", "")))
        or type(runtime_provenance.get("byteCount")) is not int
        or runtime_provenance["byteCount"] <= 0
        or not re.fullmatch(
            r"[0-9a-f]{64}",
            str(runtime_provenance.get("negativeObservationAggregateSha256", "")),
        )
        or not isinstance(preflight, dict)
        or runtime_provenance.get("payrollFoundationDatabaseObservationSha256")
        != preflight.get("observationSha256")
    ):
        raise GateFailure("Checkpoint runtime provenance is not exact or DB-bound")

    assertions = manifest.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        raise GateFailure(
            "External live checkpoint manifest requires evidence-backed assertions."
        )
    names: list[str] = []
    evidence_paths: set[str] = set()
    evidence_candidates: set[Path] = set()
    evidence_bindings: dict[str, dict[str, Any]] = {}
    checkpoint_negative_observations: dict[str, dict[str, Any]] = {}
    payroll_browser_database_observation: dict[str, Any] | None = None
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
        if evidence_relative == manifest_relative:
            raise GateFailure(
                f"Checkpoint assertion evidence must be a separate regular file: {name}"
            )
        if evidence_relative in evidence_candidates:
            raise GateFailure(
                "Every checkpoint assertion requires a distinct evidence file: "
                f"{evidence_relative}"
            )
        evidence_candidates.add(evidence_relative)
        if not isinstance(evidence_sha256, str) or not re.fullmatch(
            r"[0-9a-f]{64}", evidence_sha256
        ):
            raise GateFailure(f"Checkpoint assertion digest is invalid: {name}")
        evidence_bytes = _attested_output_regular_bytes(
            state.output_dir,
            evidence_relative,
            max_bytes=MAX_CHECKPOINT_EVIDENCE_BYTES,
        )
        if not evidence_bytes:
            raise GateFailure(f"Checkpoint assertion evidence is empty: {name}")
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
            "provenance": provenance,
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
        if name in NEGATIVE_OBSERVATION_STATES:
            if len(observations) != 1:
                raise GateFailure(
                    f"Negative checkpoint assertion requires one observation: {name}"
                )
            checkpoint_negative_observations[name] = validate_negative_observation(
                observations[0], assertion_name=name, state=state
            )
        if name == "path.browser-gateway-owner-db":
            if len(observations) != 1:
                raise GateFailure(
                    "Browser-to-PAY database checkpoint requires one observation"
                )
            payroll_browser_database_observation = (
                validate_payroll_browser_database_observation(
                    observations[0], state=state
                )
            )
        evidence_bindings[name] = {
            "path": evidence_path,
            "sha256": actual_sha256,
            "byteCount": len(evidence_bytes),
        }
        names.append(name)
    missing = sorted(set(REQUIRED_CHECKPOINT_ASSERTIONS) - set(names))
    unexpected = sorted(set(names) - set(REQUIRED_CHECKPOINT_ASSERTIONS))
    if missing or unexpected or len(names) != len(REQUIRED_CHECKPOINT_ASSERTIONS):
        raise GateFailure(
            "External live checkpoint manifest assertion set mismatch: "
            f"missing={missing}, unexpected={unexpected}"
        )
    runtime_negative = state.projection_feed.get("negativeObservations")
    ordered_negative = [
        checkpoint_negative_observations[name]
        for name in NEGATIVE_OBSERVATION_STATES
        if name in checkpoint_negative_observations
    ]
    expected_negative = {
        "schemaVersion": 1,
        "observations": ordered_negative,
        "aggregateSha256": canonical_json_sha256(ordered_negative),
    }
    if (
        len(ordered_negative) != len(NEGATIVE_OBSERVATION_STATES)
        or runtime_negative != expected_negative
    ):
        raise GateFailure(
            "Checkpoint negative observations are not the exact runner-observed "
            "Gateway-to-owner evidence bound in runtime.json."
        )
    if payroll_browser_database_observation is None:
        raise GateFailure(
            "Checkpoint omitted browser-to-PAY database lineage observation"
        )
    return {
        "manifest": {
            "path": str(manifest_path.relative_to(state.output_dir)),
            "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "byteCount": len(manifest_bytes),
        },
        "assertionEvidence": evidence_bindings,
        "payrollFoundationBrowserObservation": payroll_browser_database_observation,
    }


def observe_payroll_foundation_postflight(
    postgres: str,
    state: GateState,
    browser: dict[str, Any],
) -> dict[str, Any]:
    preflight = state.projection_feed["payrollFoundationDatabaseObservation"]
    update_command_id = browser["updateCommandId"]
    simulate_command_id = browser["simulateCommandId"]
    tenant_id = preflight["tenantId"]
    configuration_id = preflight["configurationId"]
    raw = psql(
        postgres,
        "dwp_payroll",
        f"""
        SELECT jsonb_build_object(
            'schemaVersion', 1,
            'phase', 'POSTFLIGHT',
            'tenantId', configuration.tenant_id,
            'configurationId', configuration.configuration_id,
            'currentVersion', configuration.current_version,
            'lifecycleState', configuration.lifecycle_state,
            'legalEntityId', configuration.legal_entity_id,
            'authorId', configuration.author_id,
            'publisherId', configuration.publisher_id,
            'lastCommandId', configuration.last_command_id,
            'definitionDigest', current_version.definition_digest,
            'dependencyDigest', current_version.dependency_digest,
            'preflightObservationSha256',
                {sql_literal(preflight['observationSha256'])},
            'browserObservationSha256',
                {sql_literal(browser['observationSha256'])},
            'workspaceResponseSha256',
                {sql_literal(browser['workspaceResponseSha256'])},
            'updateResponseSha256',
                {sql_literal(browser['updateResponseSha256'])},
            'simulateResponseSha256',
                {sql_literal(browser['simulateResponseSha256'])},
            'versionRows', jsonb_build_array(
                jsonb_build_object(
                    'version', 3,
                    'rowCount', (SELECT count(*) FROM pay_foundation_versions item
                                  WHERE item.tenant_id=configuration.tenant_id
                                    AND item.configuration_id=configuration.configuration_id
                                    AND item.version=3),
                    'commandId', (SELECT item.command_id FROM pay_foundation_versions item
                                   WHERE item.tenant_id=configuration.tenant_id
                                     AND item.configuration_id=configuration.configuration_id
                                     AND item.version=3),
                    'lifecycleState', (SELECT item.lifecycle_state
                                         FROM pay_foundation_versions item
                                        WHERE item.tenant_id=configuration.tenant_id
                                          AND item.configuration_id=configuration.configuration_id
                                          AND item.version=3),
                    'authoredBy', (SELECT item.authored_by
                                     FROM pay_foundation_versions item
                                    WHERE item.tenant_id=configuration.tenant_id
                                      AND item.configuration_id=configuration.configuration_id
                                      AND item.version=3),
                    'definitionDigest', (SELECT item.definition_digest
                                           FROM pay_foundation_versions item
                                          WHERE item.tenant_id=configuration.tenant_id
                                            AND item.configuration_id=configuration.configuration_id
                                            AND item.version=3),
                    'dependencyDigest', (SELECT item.dependency_digest
                                           FROM pay_foundation_versions item
                                          WHERE item.tenant_id=configuration.tenant_id
                                            AND item.configuration_id=configuration.configuration_id
                                            AND item.version=3)),
                jsonb_build_object(
                    'version', 4,
                    'rowCount', (SELECT count(*) FROM pay_foundation_versions item
                                  WHERE item.tenant_id=configuration.tenant_id
                                    AND item.configuration_id=configuration.configuration_id
                                    AND item.version=4),
                    'commandId', (SELECT item.command_id FROM pay_foundation_versions item
                                   WHERE item.tenant_id=configuration.tenant_id
                                     AND item.configuration_id=configuration.configuration_id
                                     AND item.version=4),
                    'lifecycleState', (SELECT item.lifecycle_state
                                         FROM pay_foundation_versions item
                                        WHERE item.tenant_id=configuration.tenant_id
                                          AND item.configuration_id=configuration.configuration_id
                                          AND item.version=4),
                    'authoredBy', (SELECT item.authored_by
                                     FROM pay_foundation_versions item
                                    WHERE item.tenant_id=configuration.tenant_id
                                      AND item.configuration_id=configuration.configuration_id
                                      AND item.version=4),
                    'definitionDigest', (SELECT item.definition_digest
                                           FROM pay_foundation_versions item
                                          WHERE item.tenant_id=configuration.tenant_id
                                            AND item.configuration_id=configuration.configuration_id
                                            AND item.version=4),
                    'dependencyDigest', (SELECT item.dependency_digest
                                           FROM pay_foundation_versions item
                                          WHERE item.tenant_id=configuration.tenant_id
                                            AND item.configuration_id=configuration.configuration_id
                                            AND item.version=4))),
            'updateReceipt', jsonb_build_object(
                'rowCount', (SELECT count(*) FROM pay_foundation_command_receipts receipt
                              WHERE receipt.tenant_id=configuration.tenant_id
                                AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'commandId', (SELECT receipt.command_id FROM pay_foundation_command_receipts receipt
                               WHERE receipt.tenant_id=configuration.tenant_id
                                 AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'commandType', (SELECT receipt.command_type FROM pay_foundation_command_receipts receipt
                                 WHERE receipt.tenant_id=configuration.tenant_id
                                   AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'receiptStatus', (SELECT receipt.receipt_status FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'configurationId', (SELECT receipt.configuration_id FROM pay_foundation_command_receipts receipt
                                     WHERE receipt.tenant_id=configuration.tenant_id
                                       AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'resultVersion', (SELECT receipt.result_version FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'actorId', (SELECT receipt.actor_id FROM pay_foundation_command_receipts receipt
                             WHERE receipt.tenant_id=configuration.tenant_id
                               AND receipt.command_id={sql_literal(update_command_id)}::uuid),
                'requestDigest', (SELECT receipt.request_digest FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(update_command_id)}::uuid)),
            'simulateReceipt', jsonb_build_object(
                'rowCount', (SELECT count(*) FROM pay_foundation_command_receipts receipt
                              WHERE receipt.tenant_id=configuration.tenant_id
                                AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'commandId', (SELECT receipt.command_id FROM pay_foundation_command_receipts receipt
                               WHERE receipt.tenant_id=configuration.tenant_id
                                 AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'commandType', (SELECT receipt.command_type FROM pay_foundation_command_receipts receipt
                                 WHERE receipt.tenant_id=configuration.tenant_id
                                   AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'receiptStatus', (SELECT receipt.receipt_status FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'configurationId', (SELECT receipt.configuration_id FROM pay_foundation_command_receipts receipt
                                     WHERE receipt.tenant_id=configuration.tenant_id
                                       AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'resultVersion', (SELECT receipt.result_version FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'actorId', (SELECT receipt.actor_id FROM pay_foundation_command_receipts receipt
                             WHERE receipt.tenant_id=configuration.tenant_id
                               AND receipt.command_id={sql_literal(simulate_command_id)}::uuid),
                'requestDigest', (SELECT receipt.request_digest FROM pay_foundation_command_receipts receipt
                                   WHERE receipt.tenant_id=configuration.tenant_id
                                     AND receipt.command_id={sql_literal(simulate_command_id)}::uuid)))::text
          FROM pay_foundation_configurations configuration
          JOIN pay_foundation_versions current_version
            ON current_version.tenant_id=configuration.tenant_id
           AND current_version.configuration_id=configuration.configuration_id
           AND current_version.version=configuration.current_version
         WHERE configuration.tenant_id={tenant_id}
           AND configuration.configuration_id={sql_literal(configuration_id)}::uuid;
        """,
    ).strip()
    try:
        observation = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as error:
        raise GateFailure(
            "PAY foundation postflight database observation is not exact JSON"
        ) from error
    if not isinstance(observation, dict):
        raise GateFailure("PAY foundation postflight database observation is absent")
    expected = {
        "schemaVersion": 1,
        "phase": "POSTFLIGHT",
        "tenantId": tenant_id,
        "configurationId": configuration_id,
        "currentVersion": 4,
        "lifecycleState": "SIMULATED",
        "legalEntityId": preflight["legalEntityId"],
        "authorId": state.synthetic_tenants["A"]["administratorUserId"],
        "publisherId": preflight["publisherId"],
        "lastCommandId": simulate_command_id,
        "preflightObservationSha256": preflight["observationSha256"],
        "browserObservationSha256": browser["observationSha256"],
        "workspaceResponseSha256": browser["workspaceResponseSha256"],
        "updateResponseSha256": browser["updateResponseSha256"],
        "simulateResponseSha256": browser["simulateResponseSha256"],
    }
    drift = {
        key: {"expected": value, "actual": observation.get(key)}
        for key, value in expected.items()
        if observation.get(key) != value
    }
    versions = observation.get("versionRows")
    receipts = (observation.get("updateReceipt"), observation.get("simulateReceipt"))
    if (
        drift
        or set(observation) != {
            *expected,
            "definitionDigest",
            "dependencyDigest",
            "versionRows",
            "updateReceipt",
            "simulateReceipt",
        }
        or not re.fullmatch(r"[0-9a-f]{64}", str(observation.get("definitionDigest", "")))
        or not re.fullmatch(r"[0-9a-f]{64}", str(observation.get("dependencyDigest", "")))
        or not isinstance(versions, list)
        or len(versions) != 2
        or any(not isinstance(item, dict) for item in versions)
        or any(set(item) != {
            "version", "rowCount", "commandId", "lifecycleState", "authoredBy",
            "definitionDigest", "dependencyDigest"
        } for item in versions)
        or versions[0].get("version") != 3
        or versions[0].get("rowCount") != 1
        or versions[0].get("commandId") != update_command_id
        or versions[0].get("lifecycleState") != "DRAFT"
        or versions[0].get("authoredBy")
        != state.synthetic_tenants["A"]["administratorUserId"]
        or versions[1].get("version") != 4
        or versions[1].get("rowCount") != 1
        or versions[1].get("commandId") != simulate_command_id
        or versions[1].get("lifecycleState") != "SIMULATED"
        or versions[1].get("authoredBy")
        != state.synthetic_tenants["A"]["administratorUserId"]
        or versions[0].get("definitionDigest") != versions[1].get("definitionDigest")
        or versions[0].get("dependencyDigest") != versions[1].get("dependencyDigest")
        or observation.get("definitionDigest") != versions[1].get("definitionDigest")
        or observation.get("dependencyDigest") != versions[1].get("dependencyDigest")
        or any(not isinstance(receipt, dict) for receipt in receipts)
    ):
        raise GateFailure(
            f"PAY foundation postflight database lineage is invalid: {drift}"
        )
    for receipt, command_id, command_type, result_version in (
        (receipts[0], update_command_id, "UPDATE", 3),
        (receipts[1], simulate_command_id, "SIMULATE", 4),
    ):
        if (
            set(receipt) != {
                "rowCount", "commandId", "commandType", "receiptStatus",
                "configurationId", "resultVersion", "actorId", "requestDigest"
            }
            or receipt.get("rowCount") != 1
            or receipt.get("commandId") != command_id
            or receipt.get("commandType") != command_type
            or receipt.get("receiptStatus") != "SUCCEEDED"
            or receipt.get("configurationId") != configuration_id
            or receipt.get("resultVersion") != result_version
            or receipt.get("actorId") != state.synthetic_tenants["A"][
                "administratorUserId"
            ]
            or not re.fullmatch(
                r"[0-9a-f]{64}", str(receipt.get("requestDigest", ""))
            )
        ):
            raise GateFailure(
                f"PAY foundation {command_type} receipt lineage is invalid"
            )
    observation["observationSha256"] = canonical_json_sha256(observation)
    atomic_write_json(
        state.output_dir / "db" / "payroll-foundation-postflight.json",
        observation,
    )
    return observation


def run_checkpoint(
    postgres: str,
    state: GateState,
    command: Sequence[str] | None,
    timeout: float,
    credentials: Sequence[SyntheticTenantCredential],
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
    credentials_by_lane = {credential.lane: credential for credential in credentials}
    if set(credentials_by_lane) != {"A", "B"} or len(credentials) != 2:
        raise GateFailure(
            "Live checkpoint requires exactly one in-memory credential for tenant A and B."
        )
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
    for lane, credential in sorted(credentials_by_lane.items()):
        prefix = f"DWP_W1_TENANT_{lane}"
        environment.update(
            {
                f"{prefix}_PROVIDER_TENANT_ID": credential.provider_tenant_id,
                f"{prefix}_ID": str(credential.tenant_id),
                f"{prefix}_USER_ID": str(credential.administrator_user_id),
                f"{prefix}_PERSON_PUBLIC_ID": credential.person_public_id,
                f"{prefix}_WORKER_PUBLIC_ID": credential.worker_public_id,
                f"{prefix}_ASSIGNMENT_PUBLIC_ID": credential.assignment_public_id,
                f"{prefix}_ACTOR_LEGAL_EMPLOYER_PUBLIC_ID": (
                    credential.actor_legal_employer_public_id
                ),
                f"{prefix}_TARGET_PERSON_PUBLIC_ID": (
                    credential.target_person_public_id
                ),
                f"{prefix}_TARGET_WORKER_PUBLIC_ID": (
                    credential.target_worker_public_id
                ),
                f"{prefix}_TARGET_ASSIGNMENT_PUBLIC_ID": (
                    credential.target_assignment_public_id
                ),
                f"{prefix}_TARGET_POPULATION_REVISION": (
                    credential.target_population_revision
                ),
                f"{prefix}_TARGET_POPULATION_COUNT": str(
                    credential.target_population_count
                ),
                f"{prefix}_KEY": credential.tenant_key,
                f"{prefix}_EMAIL": credential.email,
                f"{prefix}_PASSWORD": credential.password,
            }
        )
    process_outcome = run_checkpoint_process(
        state,
        command,
        environment,
        state.output_dir / "logs" / "external-live-checkpoint.log",
        timeout,
    )
    evidence_provenance = validate_checkpoint_manifest(state, manifest_path)
    postflight = observe_payroll_foundation_postflight(
        postgres,
        state,
        evidence_provenance["payrollFoundationBrowserObservation"],
    )
    state.projection_feed["payrollFoundationDatabasePostflight"] = postflight
    postflight_path = state.output_dir / "db" / "payroll-foundation-postflight.json"
    postflight_bytes = _attested_regular_bytes(postflight_path)
    evidence_provenance["payrollFoundationDatabasePostflight"] = {
        "path": str(postflight_path.relative_to(state.output_dir)),
        "sha256": hashlib.sha256(postflight_bytes).hexdigest(),
        "byteCount": len(postflight_bytes),
        "observationSha256": postflight["observationSha256"],
    }
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
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISDIR(metadata.st_mode):
            continue
        relative = path.relative_to(state.output_dir)
        if not stat.S_ISREG(metadata.st_mode):
            findings.append(f"{relative}:non_regular_evidence")
            try:
                path.unlink()
            except OSError as error:
                raise GateFailure(
                    "Non-regular evidence could not be removed without reading it: "
                    f"{relative}: {error}"
                ) from error
            continue
        try:
            content = _attested_output_regular_bytes(
                state.output_dir,
                relative,
                max_bytes=max(metadata.st_size, 1),
            )
        except GateFailure as error:
            try:
                path.unlink()
            except OSError as quarantine_error:
                raise GateFailure(
                    "Mutating evidence could not be quarantined: "
                    f"{relative}: {quarantine_error}"
                ) from error
            findings.append(f"{relative}:unsafe_or_mutating_evidence")
            continue
        path_findings: list[str] = []
        for name, value in secret_values.items():
            if value in content:
                path_findings.append(name)
        if path_findings:
            findings.extend(f"{relative}:{name}" for name in path_findings)
            try:
                path.unlink()
            except OSError as error:
                raise GateFailure(
                    "Evidence containing generated secrets could not be deleted: "
                    f"{relative}: {error}"
                ) from error
    return findings


def remove_raw_browser_artifacts(state: GateState) -> dict[str, Any]:
    deleted: list[str] = []
    errors: list[str] = []
    for path in sorted(state.output_dir.rglob("*")):
        lowered = path.name.lower()
        if not (
            lowered.endswith("-raw.har")
            or lowered.endswith("-raw.har.zip")
        ):
            continue
        relative = str(path.relative_to(state.output_dir))
        try:
            metadata = path.lstat()
            if stat.S_ISDIR(metadata.st_mode):
                errors.append(f"raw-browser-artifact-is-directory:{relative}")
                continue
            path.unlink()
            deleted.append(relative)
        except FileNotFoundError:
            continue
        except OSError as error:
            errors.append(f"raw-browser-artifact:{relative}:{error}")
    return {"deleted": deleted, "errors": errors}


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
    raw_browser_artifacts = remove_raw_browser_artifacts(state)
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
    errors.extend(raw_browser_artifacts["errors"])
    return {
        "checkpointProcessGroups": checkpoint_group_results,
        "processes": process_results,
        "containers": container_results,
        "network": {state.network or "": network_status},
        "residuals": residuals,
        "rawBrowserArtifacts": raw_browser_artifacts,
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
    specs = service_specs(secrets_, run_id)
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
            "worktreeCleanAfterBuild": True,
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
        if not args.auth_only:
            run_migration_controls(
                state,
                secrets_,
                postgres_port,
                args.gradle_executable,
                args.build_timeout,
            )
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
        start_auth_at_v233(
            state,
            specs["auth"],
            base_environment,
            postgres,
            secrets_,
            args.startup_timeout,
        )
        stop_process(state, "auth-v233")
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
            tenant_credentials = bootstrap_synthetic_identities(state, secrets_)
            gateway_sessions = login_gateway_sessions(state, tenant_credentials)
            bootstrap_synthetic_rollouts(state, secrets_, tenant_credentials)
            gateway_authorities = resolve_gateway_authorities(
                state, gateway_sessions
            )
            seed_trusted_projection_feeds(
                postgres,
                state,
                secrets_,
                tenant_credentials,
                gateway_authorities,
                gateway_sessions,
            )
            write_runtime_manifest(state)
            try:
                run_checkpoint(
                    postgres,
                    state,
                    args.checkpoint_command,
                    args.checkpoint_timeout,
                    tenant_credentials,
                )
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
