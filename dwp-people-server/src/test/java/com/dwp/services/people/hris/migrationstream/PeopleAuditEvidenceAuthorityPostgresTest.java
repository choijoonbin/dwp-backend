package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.database.RuntimeTablePrivilegeGuard;
import com.dwp.core.database.RuntimeTablePrivilegeGuard.TablePrivilege;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PeopleAuditEvidenceAuthorityPostgresTest {

    private static final String RUNTIME = "people_audit_runtime";
    private static final String RUNTIME_PASSWORD = "people_audit_runtime_password";
    private static final Set<TablePrivilege> DENIED = Set.of(
            new TablePrivilege("public", "sys_people_audit_events", "UPDATE"),
            new TablePrivilege("public", "sys_people_audit_events", "DELETE"));
    private static final List<String> TRIGGER_FUNCTIONS = List.of(
            "sys_people_audit_to_outbox",
            "prevent_ppl_org_scenario_validation_mutation",
            "reject_workforce_export_attempt_mutation",
            "sys_reject_people_audit_event_mutation");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static DataSource ownerDataSource;
    private static DataSource runtimeDataSource;
    private static JdbcTemplate owner;
    private static JdbcTemplate runtime;
    private static UUID preExistingEventId;
    private static int preExistingEvidenceCountAfterHardening;
    private static int preExistingOutboxCountAfterHardening;

    @BeforeAll
    static void migrateAndCreateRuntime() {
        ownerDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        owner.execute("CREATE EXTENSION pgcrypto SCHEMA public");
        Flyway beforeHardening = Flyway.configure()
                .dataSource(ownerDataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("50")
                .load();
        beforeHardening.migrate();
        assertThat(beforeHardening.info().current().getVersion().getVersion()).isEqualTo("50");
        preExistingEventId = owner.queryForObject("""
                INSERT INTO public.sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action,
                    target_type, target_id, outcome)
                VALUES (76, 'SERVICE', 'pre-v51', 'people.pre-v51',
                        'WORKER', 'worker-76', 'SUCCESS')
                RETURNING audit_event_id
                """, UUID.class);

        Flyway afterHardening = Flyway.configure()
                .dataSource(ownerDataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load();
        afterHardening.migrate();
        assertThat(afterHardening.info().current().getVersion().getVersion()).isEqualTo("51");
        preExistingEvidenceCountAfterHardening = owner.queryForObject("""
                SELECT COUNT(*)
                  FROM public.sys_people_audit_events
                 WHERE audit_event_id=?
                """, Integer.class, preExistingEventId);
        preExistingOutboxCountAfterHardening = owner.queryForObject("""
                SELECT COUNT(*)
                  FROM public.sys_audit_outbox
                 WHERE event_id=?
                """, Integer.class, preExistingEventId);

        owner.execute("CREATE ROLE " + RUNTIME + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        owner.execute("REVOKE CREATE, TEMPORARY ON DATABASE " + databaseIdentifier()
                + " FROM PUBLIC, " + RUNTIME);
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC, " + RUNTIME);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME);
        owner.execute("REVOKE ALL ON FUNCTION public.gen_random_uuid() FROM PUBLIC, "
                + RUNTIME);
        runtimeDataSource = dataSource(RUNTIME, RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
    }

    @BeforeEach
    void restoreCanonicalBoundary() {
        owner.execute("TRUNCATE public.sys_audit_outbox, public.sys_people_audit_events");
        owner.execute("GRANT SELECT, INSERT ON public.sys_people_audit_events TO " + RUNTIME);
        owner.execute("REVOKE UPDATE, DELETE ON public.sys_people_audit_events FROM " + RUNTIME);
    }

    @Test
    void v51HardensAnExistingV50DatabaseWithoutRewritingEvidence() {
        assertThat(preExistingEventId).isNotNull();
        assertThat(preExistingEvidenceCountAfterHardening).isEqualTo(1);
        assertThat(preExistingOutboxCountAfterHardening).isEqualTo(1);
        assertThat(owner.queryForObject("""
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version='51'
                   AND success
                """, Integer.class)).isEqualTo(1);

        assertThat(owner.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_trigger trigger_object
                  JOIN pg_catalog.pg_class target
                    ON target.oid=trigger_object.tgrelid
                 WHERE target.relname='sys_people_audit_events'
                   AND trigger_object.tgname='trg_sys_people_audit_events_append_only'
                   AND NOT trigger_object.tgisinternal
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void productionMigrationOwnsTheExactTriggerSecurityContract() {
        List<Map<String, Object>> boundaries = owner.queryForList("""
                SELECT routine.proname AS name,
                       routine.prosecdef AS security_definer,
                       routine.proconfig[1] AS configuration,
                       pg_catalog.has_function_privilege(
                           ?, routine.oid, 'EXECUTE') AS runtime_executable,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 routine.proacl,
                                 pg_catalog.acldefault('f', routine.proowner))) acl
                            WHERE acl.grantee=0
                              AND acl.privilege_type='EXECUTE'
                       ) AS public_executable
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                 WHERE namespace.nspname='public'
                   AND routine.proname = ANY (?::text[])
                 ORDER BY routine.proname
                """, RUNTIME, TRIGGER_FUNCTIONS.toArray(String[]::new));

        assertThat(boundaries).hasSize(TRIGGER_FUNCTIONS.size());
        assertThat(boundaries).allSatisfy(boundary -> {
            assertThat(boundary.get("security_definer")).isEqualTo(true);
            assertThat(boundary.get("configuration"))
                    .isEqualTo("search_path=pg_catalog, public, pg_temp");
            assertThat(boundary.get("runtime_executable")).isEqualTo(false);
            assertThat(boundary.get("public_executable")).isEqualTo(false);
        });
    }

    @Test
    void runtimeCanAppendAndOutboxButCannotRewriteOrDeleteAuditEvidence() {
        UUID eventId = runtime.queryForObject("""
                INSERT INTO public.sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action,
                    target_type, target_id, outcome)
                VALUES (77, 'SERVICE', 'hris-test', 'people.hris-test',
                        'WORKER', 'worker-77', 'SUCCESS')
                RETURNING audit_event_id
                """, UUID.class);
        assertThat(eventId).isNotNull();
        assertThat(owner.queryForObject(
                "SELECT COUNT(*) FROM public.sys_audit_outbox WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);

        assertThatThrownBy(() -> runtime.update("""
                UPDATE public.sys_people_audit_events SET target_id='forged'
                 WHERE audit_event_id=?
                """, eventId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runtime.update(
                "DELETE FROM public.sys_people_audit_events WHERE audit_event_id=?", eventId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void evenOwnerCannotMutateEvidenceBehindThePublishedProjection() {
        UUID eventId = UUID.randomUUID();
        owner.update("""
                INSERT INTO public.sys_people_audit_events (
                    audit_event_id, tenant_id, actor_type, actor_id, action,
                    target_type, target_id, outcome)
                VALUES (?, 78, 'SERVICE', 'owner-test', 'people.hris-test',
                        'WORKER', 'worker-78', 'SUCCESS')
                """, eventId);
        assertThatThrownBy(() -> owner.update("""
                UPDATE public.sys_people_audit_events SET target_id='forged'
                 WHERE audit_event_id=?
                """, eventId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("sys_people_audit_events is append-only");
        assertThatThrownBy(() -> owner.update(
                "DELETE FROM public.sys_people_audit_events WHERE audit_event_id=?", eventId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("sys_people_audit_events is append-only");
    }

    @Test
    void strictStartupGuardsExactTableAndTriggerBoundaries() {
        assertThatCode(() -> RuntimeTablePrivilegeGuard.verifyDenied(
                "People", runtimeDataSource, DENIED)).doesNotThrowAnyException();
        assertThatCode(() -> OwnerTriggerExecutionBoundaryGuard.verify(
                "People", runtimeDataSource, List.of("public"), POSTGRES.getUsername()))
                .doesNotThrowAnyException();

        try {
            owner.execute("GRANT UPDATE ON public.sys_people_audit_events TO " + RUNTIME);
            assertThatThrownBy(() -> RuntimeTablePrivilegeGuard.verifyDenied(
                    "People", runtimeDataSource, DENIED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UPDATE");
        } finally {
            owner.execute("REVOKE UPDATE ON public.sys_people_audit_events FROM " + RUNTIME);
        }

        try {
            owner.execute("ALTER FUNCTION public.sys_people_audit_to_outbox() SECURITY INVOKER");
            assertThatThrownBy(() -> OwnerTriggerExecutionBoundaryGuard.verify(
                    "People", runtimeDataSource, List.of("public"), POSTGRES.getUsername()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sys_people_audit_to_outbox");
        } finally {
            owner.execute("ALTER FUNCTION public.sys_people_audit_to_outbox() SECURITY DEFINER");
        }
    }

    @Test
    void uuidDefaultsUseThePgCatalogBuiltinWithoutExtensionExecute() {
        assertThat(pgcryptoUuidDefaultCount(owner)).isZero();
        assertThat(owner.queryForObject(
                "SELECT has_function_privilege(?, "
                        + "'public.gen_random_uuid()', 'EXECUTE')",
                Boolean.class,
                RUNTIME)).isFalse();
    }

    private static int pgcryptoUuidDefaultCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_attrdef default_value
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid=default_value.adrelid
                  JOIN pg_catalog.pg_namespace table_namespace
                    ON table_namespace.oid=relation.relnamespace
                  JOIN pg_catalog.pg_depend dependency
                    ON dependency.classid='pg_attrdef'::pg_catalog.regclass
                   AND dependency.objid=default_value.oid
                   AND dependency.refclassid='pg_proc'::pg_catalog.regclass
                 WHERE table_namespace.nspname='public'
                   AND dependency.refobjid='public.gen_random_uuid()'::pg_catalog.regprocedure
                """, Integer.class);
    }

    private static DataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String databaseIdentifier() {
        return "\"" + POSTGRES.getDatabaseName().replace("\"", "\"\"") + "\"";
    }
}
