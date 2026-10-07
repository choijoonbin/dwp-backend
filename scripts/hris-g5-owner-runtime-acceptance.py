#!/usr/bin/env python3
"""Run the narrow G5 People/PAY/TIM owner-runtime restart acceptance.

The harness owns one disposable PostgreSQL container, creates only the three
HRIS owner databases, obtains native receipts from dwp-migration-control, and
boots every owner twice against the same sealed database.  Both boots must
publish the approved OpenAPI contract and leave the sealed Flyway histories
unchanged.  It deliberately does not import or invoke the W1 acceptance runner.
"""

from __future__ import annotations

import argparse
import copy
import difflib
import hashlib
import json
import os
import re
import runpy
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
RECEIPT_PREFIX = "DWP_MIGRATION_CONTROL_RECEIPT="
APPROVED_POSTGRES_IMAGES = ("postgres:18.4-alpine", "postgres:16-alpine")
HOST_ENVIRONMENT_ALLOWLIST = ("HOME", "JAVA_HOME", "LANG", "LC_ALL", "PATH", "TMPDIR")


class GateFailure(RuntimeError):
    """A fail-closed acceptance result."""


@dataclass(frozen=True)
class ServiceSpec:
    name: str
    module: str
    database: str
    mode: str
    control_suffix: str
    migration_role: str
    runtime_role: str
    publisher_role: str = ""
    metadata_role: str = ""
    history_tables: tuple[tuple[str, str], ...] = (("public", "flyway_schema_history"),)
    feature_environment: tuple[tuple[str, str], ...] = ()

    @property
    def expected_streams(self) -> tuple[str, ...]:
        if self.name == "people":
            return ("people-main", "people-performance")
        return (f"{self.name}-main",)


SERVICES = (
    ServiceSpec(
        "people",
        "dwp-people-server",
        "dwp_people_g5_runtime",
        "PEOPLE_FRESH",
        "People",
        "dwp_people_migration",
        "dwp_people_runtime",
        metadata_role="dwp_provider_metadata_people",
        history_tables=(
            ("public", "flyway_schema_history"),
            ("hris_performance", "flyway_performance_schema_history"),
        ),
        feature_environment=(
            ("DWP_HRIS_PERFORMANCE_WAVE1_ENABLED", "true"),
            ("DWP_HCM_PRODUCT_AUTHORIZATION_V3_ENABLED", "true"),
            ("DWP_PEOPLE_PEOPLE360_RUNTIME_ENABLED", "true"),
        ),
    ),
    ServiceSpec(
        "payroll",
        "dwp-payroll-server",
        "dwp_payroll_g5_runtime",
        "STRICT_FRESH",
        "Payroll",
        "dwp_payroll_migration",
        "dwp_payroll_runtime",
        publisher_role="dwp_payroll_projection_publisher",
        feature_environment=(("DWP_HRIS_PAYROLL_FOUNDATION_WAVE1_ENABLED", "true"),),
    ),
    ServiceSpec(
        "time",
        "dwp-time-server",
        "dwp_time_g5_runtime",
        "STRICT_FRESH",
        "Time",
        "dwp_time_migration",
        "dwp_time_runtime",
        publisher_role="dwp_time_projection_publisher",
        feature_environment=(("DWP_TIME_WORK_REGIME_API_ENABLED", "true"),),
    ),
)

STABLE_OPENAPI_INFO = {
    "payroll": {"title": "DWP Payroll Service API", "version": "1.0.0"},
    "time": {"title": "DWP Time Service API", "version": "1.0.0"},
}
PARAMETER_CONTRACT_KEYS = (
    "allowEmptyValue",
    "allowReserved",
    "content",
    "deprecated",
    "explode",
    "schema",
    "style",
)


def status(message: str) -> None:
    print(f"[g5-owner-runtime] {message}", file=sys.stderr, flush=True)


def host_environment() -> dict[str, str]:
    environment = {
        name: os.environ[name]
        for name in HOST_ENVIRONMENT_ALLOWLIST
        if name in os.environ
    }
    environment.setdefault("PATH", os.defpath)
    return environment


def run_checked(
    command: Iterable[str],
    *,
    environment: dict[str, str] | None = None,
    timeout: float = 300.0,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        tuple(command),
        cwd=ROOT,
        env=environment,
        check=False,
        text=True,
        capture_output=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        diagnostic = "\n".join((result.stdout + "\n" + result.stderr).splitlines()[-80:])
        raise GateFailure(
            f"Command failed with exit {result.returncode}: {diagnostic}"
        )
    return result


def docker(*arguments: str, timeout: float = 180.0) -> str:
    return run_checked(("docker", *arguments), timeout=timeout).stdout.strip()


def psql(container: str, database: str, sql: str) -> str:
    return docker(
        "exec",
        container,
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-A",
        "-t",
        "-U",
        "postgres",
        "-d",
        database,
        "-c",
        sql,
    )


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def allocate_port() -> int:
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
    raise GateFailure("Disposable PostgreSQL did not become ready")


def mapped_postgres_port(container: str) -> int:
    output = docker("port", container, "5432/tcp")
    match = re.search(r"127[.]0[.]0[.]1:([0-9]+)$", output)
    if not match:
        raise GateFailure(f"PostgreSQL did not publish a loopback-only port: {output}")
    return int(match.group(1))


def role_passwords() -> dict[str, str]:
    roles = {
        service.migration_role
        for service in SERVICES
    } | {
        service.runtime_role
        for service in SERVICES
    } | {
        service.publisher_role
        for service in SERVICES
        if service.publisher_role
    } | {
        service.metadata_role
        for service in SERVICES
        if service.metadata_role
    }
    return {role: secrets.token_urlsafe(36) for role in roles}


def provision_databases(container: str, passwords: dict[str, str]) -> None:
    for service in SERVICES:
        psql(container, "postgres", f'CREATE DATABASE "{service.database}";')
    for role in sorted(passwords):
        psql(
            container,
            "postgres",
            f'CREATE ROLE "{role}" WITH LOGIN NOSUPERUSER NOCREATEDB '
            f'NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS '
            f'CONNECTION LIMIT -1 PASSWORD {sql_literal(passwords[role])};',
        )
    databases = ("postgres", "template0", "template1", *(item.database for item in SERVICES))
    for database in databases:
        psql(container, "postgres", f'REVOKE CONNECT ON DATABASE "{database}" FROM PUBLIC;')
    for service in SERVICES:
        principals = [service.migration_role, service.runtime_role]
        if service.publisher_role:
            principals.append(service.publisher_role)
        if service.metadata_role:
            principals.append(service.metadata_role)
        grants = ", ".join(f'"{role}"' for role in principals)
        psql(
            container,
            "postgres",
            f'REVOKE TEMPORARY, CREATE ON DATABASE "{service.database}" FROM PUBLIC; '
            f'GRANT CONNECT ON DATABASE "{service.database}" TO {grants};',
        )
        for role in principals:
            search_path = "pg_catalog" if role == service.metadata_role else "pg_catalog, public"
            psql(
                container,
                "postgres",
                f'REVOKE TEMPORARY, CREATE ON DATABASE "{service.database}" FROM "{role}"; '
                f'ALTER ROLE "{role}" IN DATABASE "{service.database}" '
                f'SET search_path TO {search_path};',
            )
        schema_sql = (
            "DROP SCHEMA public CASCADE; "
            f'CREATE SCHEMA public AUTHORIZATION "{service.migration_role}"; '
            "REVOKE ALL ON SCHEMA public FROM PUBLIC; "
            f'GRANT USAGE ON SCHEMA public TO "{service.runtime_role}";'
        )
        if service.publisher_role:
            schema_sql += f' GRANT USAGE ON SCHEMA public TO "{service.publisher_role}";'
        if service.name == "people":
            schema_sql += (
                " DROP SCHEMA IF EXISTS hris_performance CASCADE;"
                f' CREATE SCHEMA hris_performance AUTHORIZATION "{service.migration_role}";'
                " REVOKE ALL ON SCHEMA hris_performance FROM PUBLIC;"
                f' GRANT USAGE ON SCHEMA hris_performance TO "{service.runtime_role}";'
            )
        psql(container, service.database, schema_sql)
        extensions = (("btree_gist", "1.7"),)
        if service.name == "people":
            extensions += (("pgcrypto", "1.3"),)
        for extension, version in extensions:
            psql(
                container,
                service.database,
                f'CREATE EXTENSION "{extension}" WITH SCHEMA public VERSION '
                f'{sql_literal(version)};',
            )


def attestation_files() -> tuple[Path, ...]:
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
    for directory in (
        ROOT / "dwp-migration-control" / "src" / "main" / "java",
        ROOT / "dwp-core" / "src" / "main" / "java",
        ROOT / "dwp-core" / "src" / "main" / "resources",
    ):
        files.update(path for path in directory.rglob("*") if path.is_file())
    for service in SERVICES:
        module = ROOT / service.module
        files.add(module / "build.gradle")
        files.add(module / "src" / "main" / "resources" / "application.yml")
        files.update(
            path
            for path in (module / "src" / "main" / "resources").rglob("*.sql")
            if path.is_file()
        )
        files.update(
            path
            for path in (module / "src" / "main" / "java").rglob(
                "*DatabaseMigrationConfiguration.java"
            )
            if path.is_file()
        )
    return tuple(sorted(files, key=lambda path: path.relative_to(ROOT).as_posix()))


def canonical_field(digest: Any, value: object) -> None:
    canonical = "true" if value is True else "false" if value is False else str(value)
    encoded = canonical.encode("utf-8")
    digest.update(str(len(encoded)).encode("ascii"))
    digest.update(b":")
    digest.update(encoded)


def control_reference() -> str:
    digest = hashlib.sha256()
    canonical_field(digest, "dwp-migration-control-attestation-v2")
    for path in attestation_files():
        if path.is_symlink() or not path.is_file():
            raise GateFailure(f"Migration Control attestation input is not regular: {path}")
        canonical_field(digest, path.relative_to(ROOT).as_posix())
        canonical_field(digest, path.read_bytes().hex())
    return "dwp-migration-control-v2:" + digest.hexdigest()


def validate_receipt(
    receipt: object,
    service: ServiceSpec,
    reference: str,
) -> dict[str, Any]:
    top_fields = {
        "schemaVersion", "mode", "service", "database", "migrationPrincipal",
        "controlReference", "previousRunReceiptSha256", "postgresVersion",
        "temporaryPrivilegeRevoked", "streams", "receiptSha256",
    }
    stream_fields = {
        "streamKey", "historyMaxInstalledRank", "historyRowCount", "historySha256",
        "inventoryObjectCount", "inventorySha256", "adoptionReceiptSha256",
    }
    if not isinstance(receipt, dict) or set(receipt) != top_fields:
        raise GateFailure(f"{service.name} Control receipt shape is not exact")
    expected = {
        "schemaVersion": "2.0",
        "mode": "NATIVE_FRESH",
        "service": service.name,
        "database": service.database,
        "migrationPrincipal": service.migration_role,
        "controlReference": reference,
        "previousRunReceiptSha256": "",
        "temporaryPrivilegeRevoked": True,
    }
    if any(receipt.get(key) != value for key, value in expected.items()):
        raise GateFailure(f"{service.name} Control receipt identity drift")
    streams = receipt.get("streams")
    if (
        not isinstance(streams, list)
        or [stream.get("streamKey") for stream in streams if isinstance(stream, dict)]
        != list(service.expected_streams)
        or any(not isinstance(stream, dict) or set(stream) != stream_fields for stream in streams)
    ):
        raise GateFailure(f"{service.name} Control receipt stream set is not exact")
    # Recompute in the same field order as ControlRunReceipt without retaining secrets.
    exact = hashlib.sha256()
    canonical_field(exact, "migration-control-run-receipt-v2")
    for field in (
        "mode", "service", "database", "migrationPrincipal", "controlReference",
        "previousRunReceiptSha256", "postgresVersion", "temporaryPrivilegeRevoked",
    ):
        canonical_field(exact, receipt[field])
    for stream in streams:
        for field in (
            "streamKey", "historyMaxInstalledRank", "historyRowCount", "historySha256",
            "inventoryObjectCount", "inventorySha256", "adoptionReceiptSha256",
        ):
            canonical_field(exact, stream[field])
    if exact.hexdigest() != receipt.get("receiptSha256"):
        raise GateFailure(f"{service.name} Control receipt digest is invalid")
    return receipt


def run_controls(
    port: int,
    bootstrap_password: str,
    passwords: dict[str, str],
    reference: str,
    timeout: float,
) -> dict[str, dict[str, Any]]:
    receipts: dict[str, dict[str, Any]] = {}
    for service in SERVICES:
        status(f"sealing {service.name} with official Migration Control")
        environment = host_environment()
        environment.update({
            "DWP_MIGRATION_CONTROL_MODE": service.mode,
            "DWP_MIGRATION_CONTROL_SERVICE": service.name,
            "DWP_MIGRATION_CONTROL_JDBC_URL": (
                f"jdbc:postgresql://127.0.0.1:{port}/{service.database}"
            ),
            "DWP_MIGRATION_CONTROL_DATABASE": service.database,
            "DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL": "postgres",
            "DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD": bootstrap_password,
            "DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL": service.migration_role,
            "DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD": passwords[service.migration_role],
            "DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL": service.runtime_role,
            "DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD": passwords[service.runtime_role],
            "DWP_MIGRATION_CONTROL_REFERENCE": reference,
        })
        if service.publisher_role:
            environment.update({
                "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PRINCIPAL": service.publisher_role,
                "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD": (
                    passwords[service.publisher_role]
                ),
            })
        result = run_checked(
            (
                str(ROOT / "gradlew"),
                "--no-daemon",
                "--console=plain",
                f":dwp-migration-control:run{service.control_suffix}MigrationControl",
            ),
            environment=environment,
            timeout=timeout,
        )
        lines = [
            line.removeprefix(RECEIPT_PREFIX)
            for line in result.stdout.splitlines()
            if line.startswith(RECEIPT_PREFIX)
        ]
        if len(lines) != 1:
            raise GateFailure(
                f"{service.name} Migration Control emitted {len(lines)} receipts"
            )
        try:
            receipt = json.loads(lines[0])
        except json.JSONDecodeError as error:
            raise GateFailure(f"{service.name} Migration Control receipt is not JSON") from error
        receipts[service.name] = validate_receipt(receipt, service, reference)
    return receipts


def build_owner_jars(timeout: float) -> dict[str, Path]:
    status("building the three owner boot jars")
    run_checked(
        (
            str(ROOT / "gradlew"),
            "--no-daemon",
            "--console=plain",
            *(f":{service.module}:bootJar" for service in SERVICES),
        ),
        environment=host_environment(),
        timeout=timeout,
    )
    jars: dict[str, Path] = {}
    for service in SERVICES:
        candidates = sorted(
            path
            for path in (ROOT / service.module / "build" / "libs").glob("*.jar")
            if not path.name.endswith("-plain.jar")
        )
        if len(candidates) != 1:
            raise GateFailure(
                f"Expected one executable {service.name} jar, found {len(candidates)}"
            )
        jars[service.name] = candidates[0]
    return jars


def history_digest(container: str, service: ServiceSpec) -> str:
    rows: list[str] = []
    for schema, table in service.history_tables:
        output = psql(
            container,
            service.database,
            f"SELECT {sql_literal(schema)} || '|' || installed_rank || '|' || "
            "COALESCE(version, '') || '|' || description || '|' || type || '|' || "
            "COALESCE(checksum::text, '') || '|' || success::text "
            f'FROM "{schema}"."{table}" ORDER BY installed_rank;',
        )
        rows.extend(line for line in output.splitlines() if line)
    if not rows:
        raise GateFailure(f"{service.name} has no sealed Flyway history")
    return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest()


def runtime_environment(
    service: ServiceSpec,
    port: int,
    postgres_port: int,
    passwords: dict[str, str],
    receipt: dict[str, Any],
) -> dict[str, str]:
    environment = host_environment()
    prefix = service.name.upper()
    environment.update({
        "DB_HOST": "127.0.0.1",
        "DB_PORT": str(postgres_port),
        "SERVER_PORT": str(port),
        "DWP_OPENAPI_ENABLED": "true",
        "DWP_ENVIRONMENT": "local",
        "DWP_SERVICE_INSTANCE": f"g5-{service.name}-same-db",
        "DWP_API_HISTORY_ENABLED": "false",
        "DWP_AUDIT_COLLECTOR_URL": "",
        "DWP_AUDIT_INGEST_TOKEN": "",
        "DWP_IDENTITY_SYNC_ENABLED": "false",
        "DWP_WORKFORCE_EXPORT_EXECUTION_ENABLED": "false",
        "KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:1",
        "OTEL_SDK_DISABLED": "true",
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
        "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "0",
        "DWP_SHUTDOWN_TIMEOUT": "3s",
        f"{prefix}_DB_NAME": service.database,
        f"{prefix}_DB_USERNAME": service.runtime_role,
        f"{prefix}_DB_PASSWORD": passwords[service.runtime_role],
        f"{prefix}_MIGRATION_DB_USERNAME": service.migration_role,
        f"{prefix}_MIGRATION_DB_PASSWORD": passwords[service.migration_role],
        f"DWP_{prefix}_SERVICE_TOKEN": secrets.token_urlsafe(32),
        f"DWP_{prefix}_MIGRATION_CONTROL_RUN_RECEIPT_JSON": json.dumps(
            receipt, separators=(",", ":"), sort_keys=True
        ),
        f"DWP_{prefix}_MIGRATION_CONTROL_RUN_RECEIPT_SHA256": str(
            receipt["receiptSha256"]
        ),
        f"DWP_{prefix}_MIGRATION_CONTROL_REFERENCE": str(
            receipt["controlReference"]
        ),
    })
    environment.update(dict(service.feature_environment))
    if service.name == "people":
        environment.update({
            "DWP_PEOPLE_CURSOR_SECRET": secrets.token_urlsafe(36),
            "DWP_PEOPLE_HRIS_DATABASE_RUNTIME_ENVIRONMENT": "local",
            "DWP_PEOPLE_HRIS_DATABASE_SERVICE_INSTANCE": "g5-people-same-db",
            "DWP_SYNTHETIC_IMPORT_ENABLED": "false",
        })
    if service.publisher_role:
        environment[f"{prefix}_PROJECTION_PUBLISHER_DB_USERNAME"] = service.publisher_role
        environment[f"{prefix}_PROJECTION_PUBLISHER_DB_PASSWORD"] = passwords[
            service.publisher_role
        ]
    if service.name == "payroll":
        environment["DWP_PAYROLL_SERVER_PORT"] = str(port)
        environment["DWP_PAYROLL_DB_POOL_SIZE"] = "3"
    if service.name == "time":
        environment["DWP_TIME_SERVER_PORT"] = str(port)
        environment["DWP_TIME_DB_POOL_SIZE"] = "3"
    return environment


def log_tail(path: Path, lines: int = 100) -> str:
    try:
        return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])
    except OSError:
        return "runtime log unavailable"


def wait_for_runtime(process: subprocess.Popen[bytes], port: int, log: Path, timeout: float) -> None:
    url = f"http://127.0.0.1:{port}/actuator/health/readiness"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise GateFailure(f"Owner runtime exited during startup:\n{log_tail(log)}")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                payload = json.load(response)
                if response.status == 200 and payload.get("status") == "UP":
                    return
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(0.5)
    raise GateFailure(f"Owner runtime readiness timed out:\n{log_tail(log)}")


def stop_runtime(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=12)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def fetch_openapi(service: ServiceSpec, port: int) -> dict[str, Any]:
    url = f"http://127.0.0.1:{port}/v3/api-docs"
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            document = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise GateFailure(f"Unable to fetch {service.name} OpenAPI: {error}") from error
    if not isinstance(document, dict) or not str(document.get("openapi", "")).startswith("3."):
        raise GateFailure(f"{service.name} did not publish OpenAPI 3")
    if not isinstance(document.get("paths"), dict) or not document["paths"]:
        raise GateFailure(f"{service.name} published no OpenAPI paths")
    document.pop("servers", None)
    return document


def parity_diff(service: ServiceSpec, document: dict[str, Any]) -> str:
    exporter, approved, live = canonical_parity_documents(service, document)
    approved_paths = approved.get("paths", {})
    live_paths = live.get("paths", {})
    approved_schemas = approved.get("components", {}).get("schemas", {})
    live_schemas = live.get("components", {}).get("schemas", {})
    http_methods = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
    approved_operations = sum(
        method in http_methods
        for path_item in approved_paths.values()
        for method in path_item
    )
    live_operations = sum(
        method in http_methods
        for path_item in live_paths.values()
        for method in path_item
    )
    added_schemas = sorted(set(live_schemas) - set(approved_schemas))
    removed_schemas = sorted(set(approved_schemas) - set(live_schemas))
    changed_schemas = sorted(
        name
        for name in set(approved_schemas) & set(live_schemas)
        if approved_schemas[name] != live_schemas[name]
    )
    changed_paths = sorted(
        path
        for path in set(approved_paths) | set(live_paths)
        if approved_paths.get(path) != live_paths.get(path)
    )
    summary = json.dumps(
        {
            "approvedPathCount": len(approved_paths),
            "livePathCount": len(live_paths),
            "approvedOperationCount": approved_operations,
            "liveOperationCount": live_operations,
            "pathsExact": approved_paths == live_paths,
            "changedPaths": changed_paths,
            "approvedSchemaCount": len(approved_schemas),
            "liveSchemaCount": len(live_schemas),
            "addedSchemas": added_schemas,
            "removedSchemas": removed_schemas,
            "changedSchemas": changed_schemas,
        },
        separators=(",", ":"),
        sort_keys=True,
    )
    before = exporter["rendered"]({"paths": approved_paths}).splitlines()
    after = exporter["rendered"]({"paths": live_paths}).splitlines()
    detail = "\n".join(
        list(difflib.unified_diff(before, after, fromfile="approved", tofile="live"))[:160]
    )
    return summary + "\n" + detail


def canonical_parity_documents(
    service: ServiceSpec,
    document: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    exporter = runpy.run_path(str(ROOT / "scripts" / "export-openapi-contracts.py"))
    contract = next(
        item for item in exporter["SERVICES"] if item.name == service.name
    )
    live = exporter["apply_design_time_overlay"](contract, copy.deepcopy(document))
    approved = exporter["load_snapshot"](contract)
    return exporter, approved, live


def operation_parameter_contracts(
    operation: dict[str, Any],
    operation_label: str,
) -> dict[tuple[str, str], dict[str, Any]]:
    parameters = operation.get("parameters", [])
    if not isinstance(parameters, list):
        raise GateFailure(f"{operation_label} parameters are not an array")
    contracts: dict[tuple[str, str], dict[str, Any]] = {}
    for parameter in parameters:
        if not isinstance(parameter, dict):
            raise GateFailure(f"{operation_label} parameter is not an object")
        reference = parameter.get("$ref")
        if isinstance(reference, str) and reference:
            identity = ("$ref", reference)
        else:
            location = parameter.get("in")
            name = parameter.get("name")
            if not isinstance(location, str) or not location or not isinstance(name, str) or not name:
                raise GateFailure(f"{operation_label} parameter identity is invalid")
            identity = (location.lower(), name.lower())
        if identity in contracts:
            raise GateFailure(f"{operation_label} repeats parameter {identity}")
        contract = {"required": parameter.get("required") is True}
        for key in PARAMETER_CONTRACT_KEYS:
            if key in parameter:
                contract[key] = copy.deepcopy(parameter[key])
        contracts[identity] = contract
    return contracts


def require_safe_parameter_refresh(
    service_name: str,
    operation_label: str,
    approved_operation: dict[str, Any],
    live_operation: dict[str, Any],
) -> None:
    approved = operation_parameter_contracts(approved_operation, operation_label)
    live = operation_parameter_contracts(live_operation, operation_label)
    removed = sorted(set(approved) - set(live))
    changed = sorted(
        identity for identity in set(approved) & set(live)
        if approved[identity] != live[identity]
    )
    added_required = sorted(
        identity for identity in set(live) - set(approved)
        if live[identity]["required"]
    )
    if removed or changed or added_required:
        raise GateFailure(
            f"{service_name} snapshot refresh changes parameter constraints for "
            f"{operation_label}: removed={removed}, changed={changed}, "
            f"addedRequired={added_required}"
        )


def require_safe_snapshot_refresh(
    service: ServiceSpec,
    document: dict[str, Any],
) -> None:
    _, approved, live = canonical_parity_documents(service, document)
    expected_info = STABLE_OPENAPI_INFO.get(service.name, approved.get("info"))
    if live.get("info") != expected_info:
        raise GateFailure(
            f"{service.name} snapshot refresh changes stable OpenAPI info: "
            f"expected={expected_info}, live={live.get('info')}"
        )
    approved_paths = approved.get("paths", {})
    live_paths = live.get("paths", {})
    if set(approved_paths) != set(live_paths):
        raise GateFailure(f"{service.name} snapshot refresh changes the path set")
    methods = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
    approved_operations = {
        (path, method): operation
        for path, item in approved_paths.items()
        for method, operation in item.items()
        if method in methods
    }
    live_operations = {
        (path, method): operation
        for path, item in live_paths.items()
        for method, operation in item.items()
        if method in methods
    }
    if set(approved_operations) != set(live_operations):
        raise GateFailure(f"{service.name} snapshot refresh changes the operation set")
    changed_ids = {
        f"{method.upper()} {path}": (
            approved_operations[(path, method)].get("operationId"),
            live_operations[(path, method)].get("operationId"),
        )
        for path, method in approved_operations
        if approved_operations[(path, method)].get("operationId")
        != live_operations[(path, method)].get("operationId")
    }
    if changed_ids:
        raise GateFailure(
            f"{service.name} snapshot refresh changes consumer operationIds: {changed_ids}"
        )
    for (path, method), approved_operation in approved_operations.items():
        live_operation = live_operations[(path, method)]
        if not isinstance(approved_operation, dict) or not isinstance(live_operation, dict):
            raise GateFailure(
                f"{service.name} snapshot refresh contains an invalid operation: "
                f"{method.upper()} {path}"
            )
        require_safe_parameter_refresh(
            service.name,
            f"{method.upper()} {path}",
            approved_operation,
            live_operation,
        )
    approved_components = approved.get("components", {})
    live_components = live.get("components", {})
    approved_schemas = approved_components.get("schemas", {})
    live_schemas = live_components.get("schemas", {})
    removed = set(approved_schemas) - set(live_schemas)
    changed = {
        name
        for name in set(approved_schemas) & set(live_schemas)
        if approved_schemas[name] != live_schemas[name]
    }
    if removed or changed:
        raise GateFailure(
            f"{service.name} snapshot refresh removes or changes schemas: "
            f"removed={sorted(removed)}, changed={sorted(changed)}"
        )
    other_approved = {
        key: value for key, value in approved_components.items() if key != "schemas"
    }
    other_live = {
        key: value for key, value in live_components.items() if key != "schemas"
    }
    if other_approved != other_live:
        raise GateFailure(
            f"{service.name} snapshot refresh changes non-schema components"
        )


def require_approved_parity(
    service: ServiceSpec,
    port: int,
    document: dict[str, Any],
    timeout: float,
    *,
    write: bool,
) -> None:
    environment = host_environment()
    environment[f"DWP_OPENAPI_{service.name.upper()}_URL"] = (
        f"http://127.0.0.1:{port}/v3/api-docs"
    )
    if write:
        require_safe_snapshot_refresh(service, document)
        run_checked(
            (
                sys.executable,
                str(ROOT / "scripts" / "export-openapi-contracts.py"),
                "--write",
                "--service",
                service.name,
            ),
            environment=environment,
            timeout=timeout,
        )
    try:
        run_checked(
            (
                sys.executable,
                str(ROOT / "scripts" / "export-openapi-contracts.py"),
                "--check",
                "--service",
                service.name,
            ),
            environment=environment,
            timeout=timeout,
        )
    except GateFailure as error:
        raise GateFailure(f"{error}\n{parity_diff(service, document)}") from error


def run_owner_twice(
    service: ServiceSpec,
    jar: Path,
    container: str,
    postgres_port: int,
    passwords: dict[str, str],
    receipt: dict[str, Any],
    evidence: Path,
    timeout: float,
    write_openapi: bool,
) -> dict[str, Any]:
    port = allocate_port()
    environment = runtime_environment(service, port, postgres_port, passwords, receipt)
    sealed_history = history_digest(container, service)
    documents: list[dict[str, Any]] = []
    for boot in (1, 2):
        status(f"starting {service.name} boot {boot}/2 against the same sealed database")
        log = evidence / f"{service.name}-boot-{boot}.log"
        with log.open("wb") as output:
            process = subprocess.Popen(
                (shutil.which("java") or "java", "-Xmx512m", "-jar", str(jar)),
                cwd=ROOT,
                env=environment,
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        try:
            wait_for_runtime(process, port, log, timeout)
            documents.append(fetch_openapi(service, port))
            require_approved_parity(
                service,
                port,
                documents[-1],
                timeout,
                write=write_openapi,
            )
        finally:
            stop_runtime(process)
        if history_digest(container, service) != sealed_history:
            raise GateFailure(f"{service.name} runtime changed its sealed Flyway history")
    rendered = [
        json.dumps(document, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
        for document in documents
    ]
    if rendered[0] != rendered[1]:
        raise GateFailure(f"{service.name} OpenAPI changed across same-DB restart")
    return {
        "database": service.database,
        "receiptSha256": receipt["receiptSha256"],
        "historySha256": sealed_history,
        "openapiSha256": hashlib.sha256(rendered[0].encode("utf-8")).hexdigest(),
        "pathCount": len(documents[0]["paths"]),
        "approvedParityChecks": 2,
        "sameDatabaseRestart": True,
    }


def execute(args: argparse.Namespace) -> dict[str, Any]:
    if shutil.which("docker") is None or shutil.which("java") is None:
        raise GateFailure("docker and java are required")
    run_checked(("docker", "version"), timeout=30)
    reference = control_reference()
    jars = build_owner_jars(args.command_timeout)
    bootstrap_password = secrets.token_urlsafe(36)
    passwords = role_passwords()
    container = "dwp-g5-owner-" + secrets.token_hex(6)
    with tempfile.TemporaryDirectory(prefix="dwp-g5-owner-runtime-") as directory:
        evidence = Path(directory)
        started = False
        try:
            status("starting disposable PostgreSQL")
            docker(
                "run", "-d", "--rm", "--name", container,
                "--label", "dwp.g5-owner-runtime=true",
                "-e", "POSTGRES_USER=postgres",
                "-e", f"POSTGRES_PASSWORD={bootstrap_password}",
                "-e", "POSTGRES_DB=postgres",
                "-p", "127.0.0.1::5432",
                "--tmpfs", "/var/lib/postgresql:rw,nosuid,size=1g",
                args.postgres_image,
            )
            started = True
            wait_for_postgres(container, args.startup_timeout)
            postgres_port = mapped_postgres_port(container)
            provision_databases(container, passwords)
            receipts = run_controls(
                postgres_port,
                bootstrap_password,
                passwords,
                reference,
                args.command_timeout,
            )
            results = {
                service.name: run_owner_twice(
                    service,
                    jars[service.name],
                    container,
                    postgres_port,
                    passwords,
                    receipts[service.name],
                    evidence,
                    args.startup_timeout,
                    args.write_openapi,
                )
                for service in SERVICES
            }
        finally:
            if started:
                subprocess.run(
                    ("docker", "rm", "-f", container),
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=30,
                )
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "syntheticOnly": True,
        "migrationControlReference": reference,
        "services": results,
    }


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument(
        "--postgres-image",
        choices=APPROVED_POSTGRES_IMAGES,
        default=APPROVED_POSTGRES_IMAGES[0],
    )
    value.add_argument("--startup-timeout", type=float, default=180.0)
    value.add_argument("--command-timeout", type=float, default=600.0)
    value.add_argument(
        "--plan",
        action="store_true",
        help="print the fixed narrow service/control plan without starting anything",
    )
    value.add_argument(
        "--write-openapi",
        action="store_true",
        help=(
            "refresh approved snapshots only after path/operation/operationId closure "
            "and additive-only schema checks, then immediately run exact --check"
        ),
    )
    return value


def main(arguments: list[str] | None = None) -> int:
    args = parser().parse_args(arguments)
    if args.startup_timeout < 30 or args.command_timeout < 60:
        raise GateFailure("timeouts are below the fail-closed minimum")
    if args.plan:
        print(json.dumps({
            "services": [
                {
                    "service": service.name,
                    "module": service.module,
                    "controlMode": service.mode,
                    "controlTask": (
                        f":dwp-migration-control:run{service.control_suffix}MigrationControl"
                    ),
                    "boots": 2,
                }
                for service in SERVICES
            ],
            "usesW1Runner": False,
        }, separators=(",", ":"), sort_keys=True))
        return 0
    result = execute(args)
    print(json.dumps(result, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (GateFailure, subprocess.TimeoutExpired) as error:
        print(f"FAIL G5 owner runtime acceptance: {error}", file=sys.stderr)
        raise SystemExit(1) from error
