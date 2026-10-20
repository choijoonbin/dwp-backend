from __future__ import annotations

import json
import os
import unittest

from scripts import devctl
from scripts import hris_local_authority_bootstrap as bootstrap


TIME_SCOPE = "hcm-scope-" + "a" * 40
PAYROLL_SCOPE = "hcm-scope-" + "b" * 40
AUTHORITY = bootstrap.AuthoritySnapshot(
    TIME_SCOPE,
    "psr-" + "c" * 64,
    PAYROLL_SCOPE,
    "psr-" + "d" * 64,
    "rollout-" + "e" * 64,
)
MEMBER = bootstrap.TimeMember(
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    0,
)
FIXTURE = bootstrap.PeopleFixture(
    (MEMBER,), ("9db55a8b-6dd1-409b-b8b5-289ada9403b7",)
)


def local_environment() -> dict[str, str]:
    return {
        "DWP_ENVIRONMENT": "local",
        "DWP_LOCAL_HRIS_AUTHORITY_BOOTSTRAP_ENABLED": "true",
        "SERVICE_GATEWAY_URL": "http://localhost:8080",
        "PEOPLE_DB_USERNAME": "dwp_people_runtime",
        "PEOPLE_DB_PASSWORD": "people-secret",
        "TIME_PROJECTION_PUBLISHER_DB_USERNAME": "dwp_time_projection_publisher",
        "TIME_PROJECTION_PUBLISHER_DB_PASSWORD": "time-secret",
        "PAYROLL_PROJECTION_PUBLISHER_DB_USERNAME": "dwp_payroll_projection_publisher",
        "PAYROLL_PROJECTION_PUBLISHER_DB_PASSWORD": "payroll-secret",
    }


def people_json() -> str:
    return json.dumps(
        {
            "identity": {
                "personPublicId": bootstrap.PERSON_PUBLIC_ID,
                "workerPublicId": bootstrap.ACTOR_WORKER_PUBLIC_ID,
                "assignmentPublicId": bootstrap.ACTOR_ASSIGNMENT_PUBLIC_ID,
                "assignmentRevision": 0,
            },
            "members": [
                {
                    "workerPublicId": MEMBER.worker_public_id,
                    "assignmentPublicId": MEMBER.assignment_public_id,
                    "assignmentRevision": MEMBER.assignment_revision,
                }
            ],
            "legalEntityIds": list(FIXTURE.legal_entity_ids),
        }
    )


class LocalAuthorityBootstrapTest(unittest.TestCase):
    def test_requires_explicit_local_opt_in_and_confirmation(self) -> None:
        cases = (
            ({**local_environment(), "DWP_ENVIRONMENT": "production"}, True),
            ({**local_environment(), "DWP_LOCAL_HRIS_AUTHORITY_BOOTSTRAP_ENABLED": "false"}, True),
            (local_environment(), False),
            ({**local_environment(), "SERVICE_GATEWAY_URL": "https://example.com"}, True),
        )
        for environment, confirmed in cases:
            with self.subTest(environment=environment, confirmed=confirmed):
                with self.assertRaises(RuntimeError):
                    bootstrap.config_from(environment, confirmed)

    def test_positive_bootstrap_uses_read_only_people_and_publishers_only(self) -> None:
        calls: list[tuple[str, str, str, str]] = []
        population, _ = bootstrap.build_time_sql(AUTHORITY, FIXTURE)
        projection, _ = bootstrap.build_payroll_sql(AUTHORITY, FIXTURE)

        def psql(container: str, database: str, role: str, password: str, sql: str) -> str:
            calls.append((database, role, password, sql))
            if database == "dwp_people":
                return people_json()
            if database == "dwp_time":
                return json.dumps(
                    {"owner": "TIME", "state": "ACTIVE", "scopeKey": TIME_SCOPE,
                     "populationPublicId": population, "memberCount": 1,
                     "updatedAt": bootstrap.FIXTURE_INSTANT}
                )
            return json.dumps(
                {"owner": "PAYROLL", "state": "ACTIVE", "scopeKey": PAYROLL_SCOPE,
                 "projectionId": projection, "memberCount": 1,
                 "recordedAt": bootstrap.FIXTURE_INSTANT}
            )

        result = bootstrap.bootstrap_local_authority(
            local_environment(), True, psql=psql,
            authority_reader=lambda _config, _password: AUTHORITY,
            docker_verifier=lambda _config: None,
        )

        self.assertEqual(result["time"]["memberCount"], 1)
        self.assertEqual(result["payroll"]["memberCount"], 1)
        self.assertEqual(
            [(database, role) for database, role, _password, _sql in calls],
            [
                ("dwp_people", "dwp_people_runtime"),
                ("dwp_time", "dwp_time_projection_publisher"),
                ("dwp_payroll", "dwp_payroll_projection_publisher"),
            ],
        )
        self.assertIn("BEGIN READ ONLY", calls[0][3])
        self.assertNotIn("INSERT INTO", calls[0][3])

    def test_projection_sql_is_deterministic_idempotent_upsert(self) -> None:
        first_time = bootstrap.build_time_sql(AUTHORITY, FIXTURE)
        second_time = bootstrap.build_time_sql(AUTHORITY, FIXTURE)
        first_payroll = bootstrap.build_payroll_sql(AUTHORITY, FIXTURE)
        second_payroll = bootstrap.build_payroll_sql(AUTHORITY, FIXTURE)

        self.assertEqual(first_time, second_time)
        self.assertEqual(first_payroll, second_payroll)
        self.assertIn("ON CONFLICT", first_time[1])
        self.assertIn("IF exact_state THEN RETURN", first_time[1])
        self.assertIn("ON CONFLICT", first_payroll[1])
        self.assertIn("IF exact_state THEN RETURN", first_payroll[1])

    def test_partial_or_conflicting_projection_is_explicitly_rejected(self) -> None:
        time_sql = bootstrap.build_time_sql(AUTHORITY, FIXTURE)[1]
        payroll_sql = bootstrap.build_payroll_sql(AUTHORITY, FIXTURE)[1]

        self.assertIn("related_count > 0", time_sql)
        self.assertIn("Time authority projection is partial or conflicting", time_sql)
        self.assertIn(
            "SELECT count(*)=1 FROM tim_target_population_actor_grants", time_sql
        )
        self.assertIn("related_count > 0", payroll_sql)
        self.assertIn("Payroll authority projection is partial or conflicting", payroll_sql)

    def test_publisher_acl_guards_exclude_owner_runtime_roles(self) -> None:
        time_sql = bootstrap.build_time_sql(AUTHORITY, FIXTURE)[1]
        payroll_sql = bootstrap.build_payroll_sql(AUTHORITY, FIXTURE)[1]

        self.assertIn("current_user <> 'dwp_time_projection_publisher'", time_sql)
        self.assertIn("has_table_privilege", time_sql)
        self.assertNotIn("dwp_time_runtime", time_sql)
        self.assertIn("current_user <> 'dwp_payroll_projection_publisher'", payroll_sql)
        self.assertIn("has_column_privilege", payroll_sql)
        self.assertNotIn("dwp_payroll_runtime", payroll_sql)

    def test_devctl_exposes_only_explicit_bootstrap_command(self) -> None:
        args = devctl.build_parser().parse_args(["bootstrap-hris-authority", "--yes"])
        self.assertEqual(args.command, "bootstrap-hris-authority")
        self.assertTrue(args.yes)

    def test_scope_key_is_bound_to_exact_tenant_population_semantics(self) -> None:
        valid = {
            "scopes": [
                {"key": TIME_SCOPE, "kind": "TARGET_POPULATION", "displayName": "TENANT"}
            ]
        }
        self.assertEqual(
            bootstrap.require_tenant_population_scope(valid, TIME_SCOPE, "time")["key"],
            TIME_SCOPE,
        )
        invalid = (
            {"scopes": []},
            {"scopes": [{"key": TIME_SCOPE, "kind": "ORG_UNIT", "displayName": "TENANT"}]},
            {"scopes": [{"key": TIME_SCOPE, "kind": "TARGET_POPULATION", "displayName": "조직"}]},
            {"scopes": [valid["scopes"][0], valid["scopes"][0]]},
        )
        for context in invalid:
            with self.subTest(context=context), self.assertRaises(RuntimeError):
                bootstrap.require_tenant_population_scope(context, TIME_SCOPE, "time")

    def test_docker_boundary_is_canonical_local_compose_postgres(self) -> None:
        config = bootstrap.config_from(local_environment(), True)
        labels = {
            "com.docker.compose.project": "dwp-backend",
            "com.docker.compose.service": "postgres",
            "com.docker.compose.project.working_dir": str(bootstrap.BACKEND_ROOT),
            "com.docker.compose.project.config_files": str(
                bootstrap.BACKEND_ROOT / "docker-compose.yml"
            ),
        }

        def output(arguments: tuple[str, ...]) -> str:
            return json.dumps(
                "unix:///tmp/canonical-docker.sock"
                if arguments[1:3] == ("context", "inspect")
                else labels
            )

        bootstrap.verify_local_postgres_container(
            config, output, lambda path: str(path) == "/tmp/canonical-docker.sock"
        )
        remote = lambda arguments: json.dumps(
            "tcp://remote.example:2376"
            if arguments[1:3] == ("context", "inspect")
            else labels
        )
        with self.assertRaises(RuntimeError):
            bootstrap.verify_local_postgres_container(config, remote, lambda _path: True)
        changed = {**local_environment(), "DWP_POSTGRES_CONTAINER": "other-postgres"}
        with self.assertRaises(RuntimeError):
            bootstrap.config_from(changed, True)

    def test_gateway_authority_is_refetched_before_publish(self) -> None:
        changed = bootstrap.AuthoritySnapshot(
            TIME_SCOPE,
            "psr-" + "f" * 64,
            PAYROLL_SCOPE,
            AUTHORITY.payroll_revision,
            AUTHORITY.rollout_revision,
        )
        authorities = iter((AUTHORITY, changed))
        calls: list[str] = []

        def psql(_container: str, database: str, _role: str, _password: str, _sql: str) -> str:
            calls.append(database)
            return people_json()

        with self.assertRaisesRegex(RuntimeError, "Gateway authority changed"):
            bootstrap.bootstrap_local_authority(
                local_environment(), True, psql=psql,
                authority_reader=lambda _config, _password: next(authorities),
                docker_verifier=lambda _config: None,
            )
        self.assertEqual(calls, ["dwp_people"])

    @unittest.skipUnless(
        os.getenv("DWP_RUN_LOCAL_HRIS_AUTHORITY_INTEGRATION") == "true",
        "explicit local PostgreSQL/Gateway integration opt-in is required",
    )
    def test_live_postgres_publish_replay_is_exactly_idempotent(self) -> None:
        environment = devctl.local_environment()
        environment["DWP_LOCAL_HRIS_AUTHORITY_BOOTSTRAP_ENABLED"] = "true"

        first = bootstrap.bootstrap_local_authority(environment, True)
        second = bootstrap.bootstrap_local_authority(environment, True)

        self.assertEqual(first, second)
        self.assertEqual(first["time"]["state"], "ACTIVE")
        self.assertGreater(first["time"]["memberCount"], 0)
        self.assertEqual(first["payroll"]["state"], "ACTIVE")
        self.assertGreater(first["payroll"]["memberCount"], 0)

    @unittest.skipUnless(
        os.getenv("DWP_RUN_LOCAL_HRIS_AUTHORITY_INTEGRATION") == "true",
        "explicit local PostgreSQL/Gateway integration opt-in is required",
    )
    def test_live_time_extra_grant_is_rejected_and_rolled_back(self) -> None:
        environment = devctl.local_environment()
        environment["DWP_LOCAL_HRIS_AUTHORITY_BOOTSTRAP_ENABLED"] = "true"
        baseline = bootstrap.bootstrap_local_authority(environment, True)
        self.assertEqual(baseline["time"]["state"], "ACTIVE")

        config = bootstrap.config_from(environment, True)
        bootstrap.verify_local_postgres_container(config)
        authority = bootstrap.read_gateway_authority(
            config, environment.get("DWP_SMOKE_PASSWORD", bootstrap.LOCAL_PASSWORD)
        )
        fixture = bootstrap.read_people_fixture(config)
        population, sql = bootstrap.build_time_sql(authority, fixture)
        lock = (
            "PERFORM pg_advisory_xact_lock("
            "hashtext('dwp-local-hris-time-authority'),1);"
        )
        conflicting_scope = "hcm-scope-" + "c" * 40
        injected = lock + f"""
          INSERT INTO tim_target_population_actor_grants (
            tenant_id,actor_id,gateway_scope_key,population_public_id,
            population_revision,grant_revision,lifecycle_state,valid_from,valid_to,
            source_digest,updated_by)
          VALUES (1,900019,'{conflicting_scope}','{population}'::uuid,
            1,1,'ACTIVE',TIMESTAMPTZ '{bootstrap.FIXTURE_INSTANT}',NULL,
            '{'f' * 64}',{bootstrap.ACTOR_ID});
        """
        self.assertEqual(sql.count(lock), 1)
        conflicting_sql = sql.replace(lock, injected, 1)

        with self.assertRaisesRegex(RuntimeError, "partial or conflicting"):
            bootstrap.run_psql(
                config.postgres_container,
                "dwp_time",
                "dwp_time_projection_publisher",
                config.time_publisher_password,
                conflicting_sql,
            )
        remaining = bootstrap.run_psql(
            config.postgres_container,
            "dwp_time",
            "dwp_time_projection_publisher",
            config.time_publisher_password,
            f"""
            BEGIN READ ONLY;
            SET LOCAL dwp.tenant_id='1';
            SELECT count(*) FROM tim_target_population_actor_grants
             WHERE tenant_id=1 AND population_public_id='{population}'::uuid
               AND actor_id=900019 AND gateway_scope_key='{conflicting_scope}';
            ROLLBACK;
            """,
        )
        self.assertEqual(remaining, "0")


if __name__ == "__main__":
    unittest.main()
