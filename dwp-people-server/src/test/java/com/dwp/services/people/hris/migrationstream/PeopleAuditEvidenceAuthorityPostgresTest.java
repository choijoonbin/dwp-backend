package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

    @BeforeAll
    static void migrateAndCreateRuntime() {
        ownerDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(ownerDataSource).execute("CREATE EXTENSION pgcrypto SCHEMA public");
        Flyway.configure()
                .dataSource(ownerDataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
        owner = new JdbcTemplate(ownerDataSource);
        installIsolatedAppendOnlyCandidate();
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

    /**
     * Test-only candidate for the separately governed database-hardening change.
     * V1-V50 do not publish this function or trigger, so this fixture must not be
     * interpreted as production migration coverage.
     */
    private static void installIsolatedAppendOnlyCandidate() {
        owner.execute("""
                CREATE OR REPLACE FUNCTION public.sys_reject_people_audit_event_mutation()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                SECURITY DEFINER
                SET search_path = pg_catalog, public, pg_temp
                AS $function$
                BEGIN
                    RAISE EXCEPTION 'sys_people_audit_events is append-only';
                END;
                $function$
                """);
        owner.execute("REVOKE ALL ON FUNCTION "
                + "public.sys_reject_people_audit_event_mutation() FROM PUBLIC");
        owner.execute("""
                CREATE TRIGGER trg_sys_people_audit_events_append_only
                BEFORE UPDATE OR DELETE ON public.sys_people_audit_events
                FOR EACH ROW EXECUTE FUNCTION public.sys_reject_people_audit_event_mutation()
                """);
    }

    @BeforeEach
    void restoreCanonicalBoundary() {
        owner.execute("TRUNCATE public.sys_audit_outbox, public.sys_people_audit_events");
        for (String function : TRIGGER_FUNCTIONS) {
            owner.execute("ALTER FUNCTION public." + function + "() SECURITY DEFINER");
            owner.execute("ALTER FUNCTION public." + function
                    + "() SET search_path = pg_catalog, public, pg_temp");
            owner.execute("REVOKE ALL ON FUNCTION public." + function
                    + "() FROM PUBLIC, " + RUNTIME);
        }
        owner.execute("GRANT SELECT, INSERT ON public.sys_people_audit_events TO " + RUNTIME);
        owner.execute("REVOKE UPDATE, DELETE ON public.sys_people_audit_events FROM " + RUNTIME);
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

        owner.execute("GRANT UPDATE ON public.sys_people_audit_events TO " + RUNTIME);
        assertThatThrownBy(() -> RuntimeTablePrivilegeGuard.verifyDenied(
                "People", runtimeDataSource, DENIED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UPDATE");
        owner.execute("REVOKE UPDATE ON public.sys_people_audit_events FROM " + RUNTIME);

        owner.execute("ALTER FUNCTION public.sys_people_audit_to_outbox() SECURITY INVOKER");
        assertThatThrownBy(() -> OwnerTriggerExecutionBoundaryGuard.verify(
                "People", runtimeDataSource, List.of("public"), POSTGRES.getUsername()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sys_people_audit_to_outbox");
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
