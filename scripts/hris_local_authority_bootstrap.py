#!/usr/bin/env python3
"""Explicit, loopback-only HRIS authority projection bootstrap for local development.

An existing projection with a different Gateway decision or rollout revision is a
conflict. This command never widens, supersedes, or implicitly refreshes authority.
"""

from __future__ import annotations

import hashlib
import http.cookiejar
import json
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence


TENANT_ID = 1
ACTOR_ID = 900018
ACTOR_EMAIL = "joonbin@sk.com"
PERSON_PUBLIC_ID = "8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b"
ACTOR_WORKER_PUBLIC_ID = "da7aeb92-baaf-403c-8dfa-073c6bb3f29e"
ACTOR_ASSIGNMENT_PUBLIC_ID = "6f800b34-2fa4-4050-b2aa-61cf5e7735dc"
LOCAL_PASSWORD = "admin1234!"
BACKEND_ROOT = Path(__file__).resolve().parents[1]
POSTGRES_CONTAINER = "dwp-postgres"
FIXTURE_INSTANT = "2026-01-01T00:00:00Z"
FIXTURE_DATE = "2026-01-01"
SCOPE = re.compile(r"^hcm-scope-[0-9a-f]{40}$")
DECISION_REVISION = re.compile(r"^psr-[0-9a-f]{64}$")
ROLLOUT_REVISION = re.compile(r"^rollout-[0-9a-f]{64}$")
UUID_TEXT = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)


@dataclass(frozen=True)
class BootstrapConfig:
    gateway_url: str
    postgres_container: str
    source_password: str
    time_publisher_password: str
    payroll_publisher_password: str


@dataclass(frozen=True)
class AuthoritySnapshot:
    time_scope: str
    time_revision: str
    payroll_scope: str
    payroll_revision: str
    rollout_revision: str


@dataclass(frozen=True)
class TimeMember:
    worker_public_id: str
    assignment_public_id: str
    assignment_revision: int


@dataclass(frozen=True)
class PeopleFixture:
    members: tuple[TimeMember, ...]
    legal_entity_ids: tuple[str, ...]


Psql = Callable[[str, str, str, str, str], str]
DockerOutput = Callable[[Sequence[str]], str]


def require_local_opt_in(environment: Mapping[str, str], confirmed: bool) -> None:
    if environment.get("DWP_ENVIRONMENT") != "local":
        raise RuntimeError("HRIS authority bootstrap is permitted only in DWP_ENVIRONMENT=local.")
    if environment.get("DWP_LOCAL_HRIS_AUTHORITY_BOOTSTRAP_ENABLED") != "true":
        raise RuntimeError(
            "HRIS authority bootstrap is disabled; explicit local opt-in is required."
        )
    if not confirmed:
        raise RuntimeError("HRIS authority bootstrap requires --yes.")


def config_from(environment: Mapping[str, str], confirmed: bool) -> BootstrapConfig:
    require_local_opt_in(environment, confirmed)
    gateway_url = environment.get("SERVICE_GATEWAY_URL", "http://localhost:8080")
    parsed = urllib.parse.urlsplit(gateway_url)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise RuntimeError("The bootstrap Gateway URL must be an uncredentialed loopback HTTP URL.")
    expected = {
        "TIME_PROJECTION_PUBLISHER_DB_USERNAME": "dwp_time_projection_publisher",
        "PAYROLL_PROJECTION_PUBLISHER_DB_USERNAME": "dwp_payroll_projection_publisher",
        "PEOPLE_DB_USERNAME": "dwp_people_runtime",
    }
    for key, value in expected.items():
        if environment.get(key, value) != value:
            raise RuntimeError(f"Local bootstrap rejects noncanonical database role {key}.")
    required = {
        "PEOPLE_DB_PASSWORD": environment.get("PEOPLE_DB_PASSWORD", ""),
        "TIME_PROJECTION_PUBLISHER_DB_PASSWORD": environment.get(
            "TIME_PROJECTION_PUBLISHER_DB_PASSWORD", ""
        ),
        "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD": environment.get(
            "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD", ""
        ),
    }
    missing = [key for key, value in required.items() if not value]
    if missing:
        raise RuntimeError("Missing local database credentials: " + ", ".join(missing))
    container = environment.get("DWP_POSTGRES_CONTAINER", POSTGRES_CONTAINER)
    if container != POSTGRES_CONTAINER:
        raise RuntimeError("Local bootstrap requires the canonical dwp-postgres container.")
    return BootstrapConfig(
        gateway_url=gateway_url.rstrip("/"),
        postgres_container=container,
        source_password=required["PEOPLE_DB_PASSWORD"],
        time_publisher_password=required["TIME_PROJECTION_PUBLISHER_DB_PASSWORD"],
        payroll_publisher_password=required[
            "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD"
        ],
    )


def docker_output(arguments: Sequence[str]) -> str:
    result = subprocess.run(
        tuple(arguments), text=True, capture_output=True, check=False, timeout=15
    )
    if result.returncode != 0:
        raise RuntimeError("Local Docker boundary inspection failed.")
    return result.stdout.strip()


def verify_local_postgres_container(
    config: BootstrapConfig,
    output: DockerOutput = docker_output,
    socket_checker: Callable[[Path], bool] = lambda path: path.is_socket(),
) -> None:
    if config.postgres_container != POSTGRES_CONTAINER:
        raise RuntimeError("Local bootstrap refuses a noncanonical PostgreSQL container.")
    try:
        endpoint = json.loads(output((
            "docker", "context", "inspect", "--format",
            "{{json .Endpoints.docker.Host}}",
        )))
        labels = json.loads(output((
            "docker", "inspect", POSTGRES_CONTAINER, "--format",
            "{{json .Config.Labels}}",
        )))
    except (json.JSONDecodeError, TypeError) as error:
        raise RuntimeError("Local Docker boundary metadata is invalid.") from error
    parsed = urllib.parse.urlsplit(endpoint) if isinstance(endpoint, str) else None
    socket_path = Path(parsed.path) if parsed and parsed.path else None
    if (
        parsed is None
        or parsed.scheme != "unix"
        or parsed.netloc
        or parsed.query
        or parsed.fragment
        or socket_path is None
        or not socket_path.is_absolute()
        or not socket_checker(socket_path)
    ):
        raise RuntimeError("Docker must use an existing local Unix socket.")
    expected_config = str((BACKEND_ROOT / "docker-compose.yml").resolve())
    expected_labels = {
        "com.docker.compose.project": "dwp-backend",
        "com.docker.compose.service": "postgres",
        "com.docker.compose.project.working_dir": str(BACKEND_ROOT.resolve()),
        "com.docker.compose.project.config_files": expected_config,
    }
    if not isinstance(labels, dict) or any(
        labels.get(key) != value for key, value in expected_labels.items()
    ):
        raise RuntimeError(
            "dwp-postgres is not the PostgreSQL service from this backend checkout."
        )


class GatewaySession:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )
        self.csrf_header = ""
        self.csrf_token = ""

    def request(self, method: str, path: str, payload: object | None = None) -> dict:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Accept": "application/json", "X-Tenant-ID": str(TENANT_ID)}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method not in {"GET", "HEAD", "OPTIONS"} and self.csrf_token:
            headers[self.csrf_header] = self.csrf_token
        request = urllib.request.Request(
            self.base_url + path, data=body, headers=headers, method=method
        )
        try:
            with self.opener.open(request, timeout=10) as response:
                value = json.load(response)
        except (OSError, ValueError, urllib.error.HTTPError) as error:
            raise RuntimeError(f"Local Gateway request failed: {method} {path}") from error
        if not isinstance(value, dict) or not isinstance(value.get("data"), dict):
            raise RuntimeError(f"Local Gateway returned an invalid envelope: {path}")
        return value["data"]

    def refresh_csrf(self) -> None:
        value = self.request("GET", "/api/auth/csrf")
        header, token = value.get("headerName"), value.get("token")
        if header != "X-XSRF-TOKEN" or not isinstance(token, str) or len(token) < 16:
            raise RuntimeError("Local Gateway CSRF contract is unavailable.")
        self.csrf_header, self.csrf_token = header, token


def require_tenant_population_scope(
    context: Mapping[str, object], scope_key: str, capability: str
) -> Mapping[str, object]:
    scopes = [
        value
        for value in context.get("scopes", [])  # type: ignore[union-attr]
        if isinstance(value, dict) and value.get("key") == scope_key
    ]
    if (
        len(scopes) != 1
        or scopes[0].get("kind") != "TARGET_POPULATION"
        or scopes[0].get("displayName") != "TENANT"
    ):
        raise RuntimeError(
            f"{capability} does not resolve the exact local TENANT target population."
        )
    return scopes[0]


def read_gateway_authority(
    config: BootstrapConfig, password: str = LOCAL_PASSWORD
) -> AuthoritySnapshot:
    session = GatewaySession(config.gateway_url)
    session.refresh_csrf()
    login = session.request(
        "POST",
        "/api/auth/login",
        {"tenantId": str(TENANT_ID), "email": ACTOR_EMAIL, "password": password},
    )
    if login.get("tenantId") != str(TENANT_ID) or login.get("userId") != str(ACTOR_ID):
        raise RuntimeError("Local Gateway login did not resolve the canonical SKAX actor.")
    session.refresh_csrf()
    contexts = session.request("GET", "/api/auth/product-surface-contexts")
    matches = [
        value
        for value in contexts.get("contexts", [])
        if value.get("productKey") == "hcm"
        and value.get("surfaceKey") == "hcm.operations"
        and value.get("accessMode") == "NORMAL"
    ]
    rollouts = [
        value
        for value in contexts.get("rollouts", [])
        if value.get("productKey") == "hcm"
        and value.get("authorityStatus") == "AVAILABLE"
        and value.get("state") == "111"
    ]
    if len(matches) != 1 or len(rollouts) != 1:
        raise RuntimeError("A unique active HCM operations context and rollout are required.")
    rollout = rollouts[0].get("opaqueRevision")
    if not isinstance(rollout, str) or not ROLLOUT_REVISION.fullmatch(rollout):
        raise RuntimeError("The HCM rollout revision is invalid.")

    def evaluate(capability: str, route: str) -> tuple[str, str]:
        grants = [
            value
            for value in matches[0].get("effectiveGrants", [])
            if value.get("grantKind") == "CAPABILITY"
            and value.get("capabilityContractKey") == capability
            and value.get("activationState") == "ACTIVE"
        ]
        scopes = grants[0].get("scopeKeys") if len(grants) == 1 else None
        if not isinstance(scopes, list) or len(scopes) != 1 or not SCOPE.fullmatch(scopes[0]):
            raise RuntimeError(f"No unique active owner scope exists for {capability}.")
        require_tenant_population_scope(matches[0], scopes[0], capability)
        decision = session.request(
            "POST",
            "/api/auth/product-surface-access/evaluate",
            {
                "subject": {
                    "type": "PRODUCT",
                    "productKey": "hcm",
                    "surfaceKey": "hcm.operations",
                },
                "routeContractKey": route,
                "contextKey": None,
                "contextScopeKey": scopes[0],
            },
        )
        revision = decision.get("decisionRevision")
        selected_scope = decision.get("scope") or {}
        selected = selected_scope.get("key")
        if (
            decision.get("decision") != "ALLOWED"
            or selected != scopes[0]
            or selected_scope.get("kind") != "TARGET_POPULATION"
            or selected_scope.get("displayName") != "TENANT"
            or not isinstance(revision, str)
            or not DECISION_REVISION.fullmatch(revision)
        ):
            raise RuntimeError(f"Gateway owner authority was not allowed for {route}.")
        return scopes[0], revision

    time_scope, time_revision = evaluate(
        "hcm.operations.time.read", "route.hcm.operations.work-plans-list.data"
    )
    payroll_scope, payroll_revision = evaluate(
        "hcm.operations.pay.read",
        "route.hcm.operations.payroll-foundation-configurations.data",
    )
    return AuthoritySnapshot(
        time_scope, time_revision, payroll_scope, payroll_revision, rollout
    )


def run_psql(
    container: str, database: str, role: str, password: str, sql: str
) -> str:
    for label, value in (("database", database), ("role", role), ("container", container)):
        if not re.fullmatch(r"[a-z][a-z0-9_-]{1,62}", value):
            raise RuntimeError(f"Unsafe local {label} name.")
    result = subprocess.run(
        (
            "docker", "exec", "-i", container, "sh", "-c",
            'IFS= read -r PGPASSWORD; export PGPASSWORD; exec "$@"',
            "dwp-local-authority-psql", "psql", "-X", "-q", "-A", "-t",
            "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-U", role,
            "-d", database, "-f", "-",
        ),
        input=password + "\n" + sql,
        text=True,
        capture_output=True,
        check=False,
        timeout=90,
    )
    if result.returncode != 0:
        detail = next(
            (line.strip() for line in result.stderr.splitlines() if line.strip()),
            "database command rejected",
        )
        raise RuntimeError(f"{database} authority bootstrap failed: {detail}")
    return result.stdout.strip()


def read_people_fixture(config: BootstrapConfig, psql: Psql = run_psql) -> PeopleFixture:
    raw = psql(
        config.postgres_container,
        "dwp_people",
        "dwp_people_runtime",
        config.source_password,
        f"""
        BEGIN READ ONLY;
        SET LOCAL dwp.tenant_id='{TENANT_ID}';
        DO $guard$
        BEGIN
          IF current_database() <> 'dwp_people' OR current_user <> 'dwp_people_runtime'
             OR current_setting('transaction_read_only') <> 'on' THEN
            RAISE EXCEPTION 'People fixture source must be the local read-only bootstrap session';
          END IF;
        END
        $guard$;
        WITH actor AS MATERIALIZED (
          SELECT person.public_id AS person_public_id,
                 worker.worker_id, worker.public_id AS worker_public_id,
                 assignment.public_id AS assignment_public_id,
                 assignment.version AS assignment_revision
            FROM ppl_persons person
            JOIN ppl_workers worker
              ON worker.tenant_id=person.tenant_id AND worker.person_id=person.person_id
             AND worker.worker_status IN ('ACTIVE','LEAVE')
            JOIN ppl_work_relationships relationship
              ON relationship.tenant_id=worker.tenant_id
             AND relationship.worker_id=worker.worker_id
             AND relationship.start_date <= CURRENT_DATE
             AND (relationship.end_date IS NULL OR relationship.end_date >= CURRENT_DATE)
            JOIN ppl_assignments assignment
              ON assignment.tenant_id=relationship.tenant_id
             AND assignment.work_relationship_id=relationship.work_relationship_id
             AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
             AND assignment.effective_start_date <= CURRENT_DATE
             AND (assignment.effective_end_date IS NULL
                  OR assignment.effective_end_date >= CURRENT_DATE)
           WHERE person.tenant_id={TENANT_ID}
             AND person.public_id='{PERSON_PUBLIC_ID}'::uuid
           ORDER BY assignment.primary_assignment DESC,
                    assignment.effective_start_date DESC,
                    assignment.effective_sequence DESC
           LIMIT 1
        ), members AS MATERIALIZED (
          SELECT DISTINCT worker.public_id AS worker_public_id,
                 assignment.public_id AS assignment_public_id,
                 assignment.version AS assignment_revision
            FROM ppl_persons person
            JOIN ppl_workers worker
              ON worker.tenant_id=person.tenant_id AND worker.person_id=person.person_id
             AND worker.worker_status IN ('ACTIVE','LEAVE')
            JOIN ppl_work_relationships relationship
              ON relationship.tenant_id=worker.tenant_id
             AND relationship.worker_id=worker.worker_id
             AND relationship.start_date <= CURRENT_DATE
             AND (relationship.end_date IS NULL OR relationship.end_date >= CURRENT_DATE)
            JOIN ppl_assignments assignment
              ON assignment.tenant_id=relationship.tenant_id
             AND assignment.work_relationship_id=relationship.work_relationship_id
             AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
             AND assignment.effective_start_date <= CURRENT_DATE
             AND (assignment.effective_end_date IS NULL
                  OR assignment.effective_end_date >= CURRENT_DATE)
           WHERE person.tenant_id={TENANT_ID}
             AND worker.worker_id <> (SELECT worker_id FROM actor)
        ), legal_entities AS MATERIALIZED (
          SELECT public_id
            FROM ppl_legal_employers
           WHERE tenant_id={TENANT_ID} AND lifecycle_state='ACTIVE'
        )
        SELECT jsonb_build_object(
          'identity', (SELECT jsonb_build_object(
              'personPublicId', person_public_id,
              'workerPublicId', worker_public_id,
              'assignmentPublicId', assignment_public_id,
              'assignmentRevision', assignment_revision) FROM actor),
          'members', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
              'workerPublicId', worker_public_id,
              'assignmentPublicId', assignment_public_id,
              'assignmentRevision', assignment_revision)
              ORDER BY assignment_public_id, worker_public_id), '[]'::jsonb) FROM members),
          'legalEntityIds', (SELECT COALESCE(jsonb_agg(public_id ORDER BY public_id),
              '[]'::jsonb) FROM legal_entities))::text;
        COMMIT;
        """,
    )
    try:
        value = json.loads(raw.splitlines()[-1])
    except (IndexError, json.JSONDecodeError) as error:
        raise RuntimeError("People returned no canonical local authority fixture.") from error
    identity = value.get("identity") if isinstance(value, dict) else None
    expected_identity = {
        "personPublicId": PERSON_PUBLIC_ID,
        "workerPublicId": ACTOR_WORKER_PUBLIC_ID,
        "assignmentPublicId": ACTOR_ASSIGNMENT_PUBLIC_ID,
        "assignmentRevision": 0,
    }
    if identity != expected_identity:
        raise RuntimeError("People canonical SKAX workforce identity is missing or changed.")
    members: list[TimeMember] = []
    assignments: set[str] = set()
    for row in value.get("members", []):
        worker = str(row.get("workerPublicId", ""))
        assignment = str(row.get("assignmentPublicId", ""))
        revision = row.get("assignmentRevision")
        if (
            not UUID_TEXT.fullmatch(worker)
            or not UUID_TEXT.fullmatch(assignment)
            or not isinstance(revision, int)
            or isinstance(revision, bool)
            or revision < 0
            or assignment in assignments
        ):
            raise RuntimeError("People returned a noncanonical target-population member.")
        assignments.add(assignment)
        members.append(TimeMember(worker, assignment, revision))
    legal_entities = tuple(str(value) for value in value.get("legalEntityIds", []))
    if not members or not legal_entities or any(
        not UUID_TEXT.fullmatch(value) for value in legal_entities
    ) or len(set(legal_entities)) != len(legal_entities):
        raise RuntimeError("People local target population or legal-entity scope is empty/invalid.")
    return PeopleFixture(tuple(members), legal_entities)


def _digest(*parts: object) -> str:
    return hashlib.sha256("\n".join(str(value) for value in parts).encode()).hexdigest()


def _literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _uuid_array(values: Sequence[str]) -> str:
    return "ARRAY[" + ",".join(_literal(value) + "::uuid" for value in values) + "]"


def _text_array(values: Sequence[str]) -> str:
    return "ARRAY[" + ",".join(_literal(value) for value in values) + "]::text[]"


def _json_result(raw: str, owner: str) -> dict:
    try:
        value = json.loads(raw.splitlines()[-1])
    except (IndexError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{owner} publisher returned no verification receipt.") from error
    if not isinstance(value, dict) or value.get("state") != "ACTIVE":
        raise RuntimeError(f"{owner} publisher returned an invalid verification receipt.")
    return value


def build_time_sql(authority: AuthoritySnapshot, fixture: PeopleFixture) -> tuple[str, str]:
    population = str(uuid.uuid5(
        uuid.NAMESPACE_URL,
        f"dwp:local-authority:v1:time:{TENANT_ID}:{ACTOR_ID}:{authority.time_scope}",
    ))
    population_digest = _digest(
        "time-population-v1", TENANT_ID, population,
        *[f"{m.worker_public_id}:{m.assignment_public_id}:{m.assignment_revision}"
          for m in fixture.members],
    )
    grant_digest = _digest("time-grant-v1", TENANT_ID, ACTOR_ID, authority.time_scope, population)
    member_digests = [
        _digest("time-member-v1", TENANT_ID, population, member.worker_public_id,
                member.assignment_public_id, member.assignment_revision)
        for member in fixture.members
    ]
    workers = _uuid_array([value.worker_public_id for value in fixture.members])
    assignments = _uuid_array([value.assignment_public_id for value in fixture.members])
    revisions = "ARRAY[" + ",".join(str(value.assignment_revision) for value in fixture.members) + "]::bigint[]"
    digests = _text_array(member_digests)
    count = len(fixture.members)
    sql = f"""
    BEGIN;
    DO $bootstrap$
    DECLARE related_count BIGINT; exact_state BOOLEAN;
    BEGIN
      PERFORM set_config('dwp.tenant_id','{TENANT_ID}',true);
      PERFORM pg_advisory_xact_lock(hashtext('dwp-local-hris-time-authority'),{TENANT_ID});
      IF current_user <> 'dwp_time_projection_publisher'
         OR session_user <> 'dwp_time_projection_publisher'
         OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user
                    AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
         OR has_schema_privilege(current_user,'public','CREATE')
         OR NOT has_table_privilege(current_user,'tim_target_population_projections','INSERT')
         OR NOT has_table_privilege(current_user,'tim_target_population_actor_grants','INSERT')
         OR NOT has_table_privilege(current_user,'tim_target_population_members','INSERT')
         OR has_table_privilege(current_user,'tim_target_population_projections','DELETE') THEN
        RAISE EXCEPTION 'Time publisher role/ACL contract is invalid';
      END IF;
      SELECT (SELECT count(*) FROM tim_target_population_projections
               WHERE tenant_id={TENANT_ID} AND (population_public_id='{population}'::uuid
                    OR scope_public_ref='population:{population}'))
           + (SELECT count(*) FROM tim_target_population_actor_grants
               WHERE tenant_id={TENANT_ID} AND ((actor_id={ACTOR_ID}
                    AND gateway_scope_key={_literal(authority.time_scope)})
                    OR population_public_id='{population}'::uuid))
           + (SELECT count(*) FROM tim_target_population_members
               WHERE tenant_id={TENANT_ID} AND population_public_id='{population}'::uuid)
        INTO related_count;
      WITH expected AS (
        SELECT * FROM unnest({workers},{assignments},{revisions},{digests})
          AS value(worker_id,assignment_id,assignment_revision,digest)
      )
      SELECT EXISTS (SELECT 1 FROM tim_target_population_projections
              WHERE tenant_id={TENANT_ID} AND population_public_id='{population}'::uuid
                AND scope_public_ref='population:{population}' AND projection_revision=1
                AND lifecycle_state='ACTIVE' AND effective_from=DATE '{FIXTURE_DATE}'
                AND effective_to IS NULL AND source_digest='{population_digest}' AND updated_by={ACTOR_ID})
         AND EXISTS (SELECT 1 FROM tim_target_population_actor_grants
              WHERE tenant_id={TENANT_ID} AND actor_id={ACTOR_ID}
                AND gateway_scope_key={_literal(authority.time_scope)}
                AND population_public_id='{population}'::uuid AND population_revision=1
                AND grant_revision=1 AND lifecycle_state='ACTIVE'
                AND valid_from=TIMESTAMPTZ '{FIXTURE_INSTANT}' AND valid_to IS NULL
                AND source_digest='{grant_digest}' AND updated_by={ACTOR_ID})
         AND (SELECT count(*)=1 FROM tim_target_population_actor_grants
              WHERE tenant_id={TENANT_ID} AND population_public_id='{population}'::uuid)
         AND (SELECT count(*)={count} FROM tim_target_population_members
              WHERE tenant_id={TENANT_ID} AND population_public_id='{population}'::uuid)
         AND NOT EXISTS (
              SELECT 1 FROM tim_target_population_members actual
               WHERE actual.tenant_id={TENANT_ID} AND actual.population_public_id='{population}'::uuid
                 AND NOT EXISTS (SELECT 1 FROM expected
                      WHERE worker_id=actual.worker_public_id
                        AND assignment_id=actual.people_assignment_public_id
                        AND assignment_revision=actual.people_assignment_revision
                        AND digest=actual.source_digest
                        AND actual.population_revision=1 AND actual.membership_revision=1
                        AND actual.lifecycle_state='ACTIVE'
                        AND actual.effective_from=DATE '{FIXTURE_DATE}'
                        AND actual.effective_to IS NULL AND actual.updated_by={ACTOR_ID}))
        INTO exact_state;
      IF related_count > 0 THEN
        IF exact_state THEN RETURN; END IF;
        RAISE EXCEPTION 'Time authority projection is partial or conflicting';
      END IF;
      INSERT INTO tim_target_population_projections (
        tenant_id,population_public_id,scope_public_ref,projection_revision,lifecycle_state,
        effective_from,effective_to,source_digest,updated_by)
      VALUES ({TENANT_ID},'{population}'::uuid,'population:{population}',1,'ACTIVE',
        DATE '{FIXTURE_DATE}',NULL,'{population_digest}',{ACTOR_ID})
      ON CONFLICT (tenant_id,population_public_id) DO UPDATE
        SET projection_revision=EXCLUDED.projection_revision
        WHERE tim_target_population_projections.scope_public_ref=EXCLUDED.scope_public_ref
          AND tim_target_population_projections.source_digest=EXCLUDED.source_digest;
      INSERT INTO tim_target_population_actor_grants (
        tenant_id,actor_id,gateway_scope_key,population_public_id,population_revision,
        grant_revision,lifecycle_state,valid_from,valid_to,source_digest,updated_by)
      VALUES ({TENANT_ID},{ACTOR_ID},{_literal(authority.time_scope)},'{population}'::uuid,
        1,1,'ACTIVE',TIMESTAMPTZ '{FIXTURE_INSTANT}',NULL,'{grant_digest}',{ACTOR_ID})
      ON CONFLICT (tenant_id,actor_id,gateway_scope_key) DO UPDATE
        SET population_revision=EXCLUDED.population_revision
        WHERE tim_target_population_actor_grants.population_public_id=EXCLUDED.population_public_id
          AND tim_target_population_actor_grants.source_digest=EXCLUDED.source_digest;
      INSERT INTO tim_target_population_members (
        tenant_id,population_public_id,population_revision,worker_public_id,
        people_assignment_public_id,people_assignment_revision,membership_revision,
        lifecycle_state,effective_from,effective_to,source_digest,updated_by)
      SELECT {TENANT_ID},'{population}'::uuid,1,worker_id,assignment_id,
             assignment_revision,1,'ACTIVE',DATE '{FIXTURE_DATE}',NULL,digest,{ACTOR_ID}
        FROM unnest({workers},{assignments},{revisions},{digests})
          AS value(worker_id,assignment_id,assignment_revision,digest)
      ON CONFLICT (tenant_id,population_public_id,worker_public_id,people_assignment_public_id)
      DO UPDATE SET membership_revision=EXCLUDED.membership_revision
        WHERE tim_target_population_members.people_assignment_revision=EXCLUDED.people_assignment_revision
          AND tim_target_population_members.source_digest=EXCLUDED.source_digest;
    END
    $bootstrap$;
    SELECT jsonb_build_object('owner','TIME','state',lifecycle_state,
      'scopeKey',gateway_scope_key,'populationPublicId',population_public_id,
      'memberCount',(SELECT count(*) FROM tim_target_population_members m
        WHERE m.tenant_id=g.tenant_id AND m.population_public_id=g.population_public_id),
      'updatedAt',updated_at)::text
      FROM tim_target_population_actor_grants g
     WHERE tenant_id={TENANT_ID} AND actor_id={ACTOR_ID}
       AND gateway_scope_key={_literal(authority.time_scope)};
    COMMIT;
    """
    return population, sql


def build_payroll_sql(
    authority: AuthoritySnapshot, fixture: PeopleFixture
) -> tuple[str, str]:
    projection = str(uuid.uuid5(
        uuid.NAMESPACE_URL,
        "dwp:local-authority:v1:payroll:"
        f"{TENANT_ID}:{ACTOR_ID}:{authority.payroll_scope}:"
        f"{authority.rollout_revision}:{authority.payroll_revision}",
    ))
    revision = _digest(
        "payroll-scope-v1", TENANT_ID, ACTOR_ID, authority.payroll_scope,
        authority.rollout_revision, authority.payroll_revision, *fixture.legal_entity_ids,
    )
    legal_entities = _uuid_array(fixture.legal_entity_ids)
    count = len(fixture.legal_entity_ids)
    sql = f"""
    BEGIN;
    DO $bootstrap$
    DECLARE related_count BIGINT; exact_state BOOLEAN; inserted_count BIGINT;
    BEGIN
      PERFORM set_config('dwp.payroll_tenant_id','{TENANT_ID}',true);
      PERFORM pg_advisory_xact_lock(hashtext('dwp-local-hris-payroll-authority'),{TENANT_ID});
      IF current_user <> 'dwp_payroll_projection_publisher'
         OR session_user <> 'dwp_payroll_projection_publisher'
         OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user
                    AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
         OR has_schema_privilege(current_user,'public','CREATE')
         OR NOT has_table_privilege(current_user,'pay_legal_entity_scope_projections','INSERT')
         OR NOT has_table_privilege(current_user,'pay_legal_entity_scope_members','INSERT')
         OR NOT has_column_privilege(current_user,'pay_legal_entity_scope_projections','status','UPDATE')
         OR has_table_privilege(current_user,'pay_legal_entity_scope_projections','DELETE') THEN
        RAISE EXCEPTION 'Payroll publisher role/ACL contract is invalid';
      END IF;
      SELECT (SELECT count(*) FROM pay_legal_entity_scope_projections
               WHERE tenant_id={TENANT_ID} AND (projection_id='{projection}'::uuid
                    OR (actor_id={ACTOR_ID} AND context_scope_key={_literal(authority.payroll_scope)})))
           + (SELECT count(*) FROM pay_legal_entity_scope_members
               WHERE tenant_id={TENANT_ID} AND projection_id='{projection}'::uuid)
        INTO related_count;
      WITH expected AS (SELECT unnest({legal_entities}) AS legal_entity_id)
      SELECT EXISTS (SELECT 1 FROM pay_legal_entity_scope_projections
              WHERE tenant_id={TENANT_ID} AND projection_id='{projection}'::uuid
                AND actor_id={ACTOR_ID} AND context_scope_key={_literal(authority.payroll_scope)}
                AND policy_revision={_literal(authority.rollout_revision)}
                AND authorization_revision={_literal(authority.payroll_revision)}
                AND projection_revision='{revision}' AND status='ACTIVE'
                AND valid_from=TIMESTAMPTZ '{FIXTURE_INSTANT}' AND valid_until IS NULL
                AND recorded_at=TIMESTAMPTZ '{FIXTURE_INSTANT}')
         AND (SELECT count(*)={count} FROM pay_legal_entity_scope_members
              WHERE tenant_id={TENANT_ID} AND projection_id='{projection}'::uuid)
         AND NOT EXISTS (SELECT 1 FROM pay_legal_entity_scope_members actual
              WHERE actual.tenant_id={TENANT_ID} AND actual.projection_id='{projection}'::uuid
                AND NOT EXISTS (SELECT 1 FROM expected
                     WHERE expected.legal_entity_id=actual.legal_entity_id))
        INTO exact_state;
      IF related_count > 0 THEN
        IF exact_state THEN RETURN; END IF;
        RAISE EXCEPTION 'Payroll authority projection is partial or conflicting';
      END IF;
      INSERT INTO pay_legal_entity_scope_projections (
        tenant_id,projection_id,actor_id,context_scope_key,policy_revision,
        authorization_revision,projection_revision,status,valid_from,valid_until,recorded_at)
      VALUES ({TENANT_ID},'{projection}'::uuid,{ACTOR_ID},{_literal(authority.payroll_scope)},
        {_literal(authority.rollout_revision)},{_literal(authority.payroll_revision)},
        '{revision}','BUILDING',TIMESTAMPTZ '{FIXTURE_INSTANT}',NULL,
        TIMESTAMPTZ '{FIXTURE_INSTANT}')
      ON CONFLICT (tenant_id,projection_id) DO NOTHING;
      IF NOT FOUND THEN RAISE EXCEPTION 'Payroll projection identity conflict'; END IF;
      INSERT INTO pay_legal_entity_scope_members (tenant_id,projection_id,legal_entity_id)
      SELECT {TENANT_ID},'{projection}'::uuid,unnest({legal_entities})
      ON CONFLICT (tenant_id,projection_id,legal_entity_id) DO NOTHING;
      GET DIAGNOSTICS inserted_count = ROW_COUNT;
      IF inserted_count <> {count} THEN
        RAISE EXCEPTION 'Payroll projection member set was not inserted atomically';
      END IF;
      UPDATE pay_legal_entity_scope_projections SET status='ACTIVE'
       WHERE tenant_id={TENANT_ID} AND projection_id='{projection}'::uuid
         AND status='BUILDING';
      IF NOT FOUND THEN RAISE EXCEPTION 'Payroll projection could not be sealed'; END IF;
    END
    $bootstrap$;
    SELECT jsonb_build_object('owner','PAYROLL','state',status,
      'scopeKey',context_scope_key,'projectionId',projection_id,
      'memberCount',(SELECT count(*) FROM pay_legal_entity_scope_members m
        WHERE m.tenant_id=p.tenant_id AND m.projection_id=p.projection_id),
      'recordedAt',recorded_at)::text
      FROM pay_legal_entity_scope_projections p
     WHERE tenant_id={TENANT_ID} AND projection_id='{projection}'::uuid;
    COMMIT;
    """
    return projection, sql


def bootstrap_local_authority(
    environment: Mapping[str, str],
    confirmed: bool,
    *,
    psql: Psql = run_psql,
    authority_reader: Callable[[BootstrapConfig, str], AuthoritySnapshot] = (
        read_gateway_authority
    ),
    docker_verifier: Callable[[BootstrapConfig], None] = (
        verify_local_postgres_container
    ),
) -> dict:
    config = config_from(environment, confirmed)
    docker_verifier(config)
    authority = authority_reader(
        config, environment.get("DWP_SMOKE_PASSWORD", LOCAL_PASSWORD)
    )
    fixture = read_people_fixture(config, psql)
    refetched_authority = authority_reader(
        config, environment.get("DWP_SMOKE_PASSWORD", LOCAL_PASSWORD)
    )
    if refetched_authority != authority:
        raise RuntimeError(
            "Gateway authority changed while the local projection fixture was read."
        )
    population, time_sql = build_time_sql(authority, fixture)
    time = _json_result(
        psql(
            config.postgres_container,
            "dwp_time",
            "dwp_time_projection_publisher",
            config.time_publisher_password,
            time_sql,
        ),
        "Time",
    )
    projection, payroll_sql = build_payroll_sql(authority, fixture)
    payroll = _json_result(
        psql(
            config.postgres_container,
            "dwp_payroll",
            "dwp_payroll_projection_publisher",
            config.payroll_publisher_password,
            payroll_sql,
        ),
        "Payroll",
    )
    if (
        time.get("populationPublicId") != population
        or time.get("scopeKey") != authority.time_scope
        or time.get("memberCount") != len(fixture.members)
        or payroll.get("projectionId") != projection
        or payroll.get("scopeKey") != authority.payroll_scope
        or payroll.get("memberCount") != len(fixture.legal_entity_ids)
    ):
        raise RuntimeError("Local authority projection verification did not match its fixture.")
    evidence = {
        "schemaVersion": 1,
        "environment": "local",
        "conflictPolicy": "FAIL_CLOSED_NO_IMPLICIT_AUTHORITY_REVISION_REPLACEMENT",
        "tenantId": TENANT_ID,
        "actorId": ACTOR_ID,
        "time": time,
        "payroll": payroll,
        "gatewayAuthority": {
            "timeDecisionRevision": authority.time_revision,
            "payrollDecisionRevision": authority.payroll_revision,
            "rolloutRevision": authority.rollout_revision,
        },
    }
    evidence["evidenceSha256"] = _digest(
        json.dumps(evidence, separators=(",", ":"), sort_keys=True)
    )
    return evidence


