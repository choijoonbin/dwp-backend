package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import com.dwp.core.database.ReadOnlyMetadataDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PeopleMigrationControlPostgresTest {
    private static final String ADMIN = "people_control_admin";
    private static final String ADMIN_PASSWORD = "a".repeat(43);
    private static final String MIGRATION = "dwp_people_migration";
    private static final String MIGRATION_PASSWORD = "m".repeat(43);
    private static final String RUNTIME = "dwp_people_runtime";
    private static final String RUNTIME_PASSWORD = "r".repeat(43);
    private static final String METADATA = "dwp_provider_metadata_people";
    private static final String METADATA_PASSWORD = "i".repeat(43);
    private static final String DATABASE = "dwp_people_w1";
    private static final String CONTROL_REFERENCE =
            "dwp-migration-control-v2:" + "d".repeat(64);

    @TempDir
    Path stagingRoot;

    @Test
    void peopleFreshRunSealsBothStreamsAndMetadataReader() throws Exception {
        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:18.4-alpine")
                        .withDatabaseName(DATABASE)
                        .withUsername(ADMIN)
                        .withPassword(ADMIN_PASSWORD)) {
            postgres.start();
            provision(postgres);
            Path primary = stage(
                    "people-main",
                    "dwp-people-server/src/main/resources/db/migration",
                    true);
            Path performance = stage(
                    "people-performance",
                    "dwp-people-server/src/main/resources/db/performance-migration",
                    false);
            ControlPlan standard = ControlPlan.forService("people");
            ControlPlan plan = new ControlPlan(
                    "people",
                    List.of(
                            new StreamPlan(
                                    "people-main", "public", "flyway_schema_history",
                                    "filesystem:" + primary.toAbsolutePath(), true, false),
                            new StreamPlan(
                                    "people-performance", "hris_performance",
                                    "flyway_performance_schema_history",
                                    "filesystem:" + performance.toAbsolutePath(), false, false)),
                    standard.runtimePlaceholder(),
                    standard.runtimeRoutineAllowlist(),
                    standard.runtimeTableDenials(),
                    standard.temporaryMigrationVersions());
            ControlEnvironment configured = new ControlEnvironment(
                    ControlEnvironment.Mode.PEOPLE_FRESH,
                    plan,
                    postgres.getJdbcUrl(),
                    DATABASE,
                    ADMIN,
                    ADMIN_PASSWORD,
                    MIGRATION,
                    MIGRATION_PASSWORD,
                    RUNTIME,
                    RUNTIME_PASSWORD,
                    CONTROL_REFERENCE,
                    Map.of(),
                    "",
                    Map.of(),
                    "");

            ControlRunReceipt receipt;
            try (Connection fence = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                DatabaseControl.acquireExclusiveControlLock(fence, configured);
                ControlPreflight.verify(fence, configured);
                ControlCredentials temporary =
                        ServiceConnectionControl.activateExclusiveControlFence(
                                fence, configured);
                ControlEnvironment active = configured.withControlCredentials(temporary);
                try {
                    receipt = MigrationControlMain.runPeopleFresh(active);
                    ServiceConnectionControl.restoreServiceConnections(
                            fence, configured, temporary);
                    DatabaseControl.requireTemporaryDenied(fence, configured);
                } catch (Exception failure) {
                    ServiceConnectionControl.failClosedServiceConnections(
                            fence, configured);
                    throw failure;
                }
            }

            List<MigrationAdoptionGuard.Contract> contracts = List.of(
                    new MigrationAdoptionGuard.Contract(
                            "people-main", "public", "flyway_schema_history",
                            List.of("public")),
                    new MigrationAdoptionGuard.Contract(
                            "people-performance", "hris_performance",
                            "flyway_performance_schema_history",
                            List.of("hris_performance")));
            DataSource migrationDataSource = dataSource(
                    postgres.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD);
            MigrationControlRunReceiptGuard.verify(
                    "people",
                    contracts,
                    migrationDataSource,
                    MIGRATION,
                    CONTROL_REFERENCE,
                    receipt.toJson(),
                    receipt.receiptSha256());

            try (Connection runtime = connection(
                    postgres.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD)) {
                DatabaseControl.execute(runtime, """
                        INSERT INTO public.ppl_organization_type_catalog (
                            tenant_id, type_key, display_name)
                        VALUES (900001, 'RUNTIME_FIXTURE', 'Runtime fixture')
                        """);
            }

            ReadOnlyMetadataDatabaseGuard.Source metadata =
                    new ReadOnlyMetadataDatabaseGuard.Source(
                            "Provider", "people", DATABASE, postgres.getJdbcUrl(),
                            METADATA, METADATA_PASSWORD);
            ReadOnlyMetadataDatabaseGuard.verify(metadata);

            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                assertPer008FinalBoundary(admin);
                assertEquals(0L, DatabaseControl.scalarLong(admin, """
                        SELECT CASE WHEN pg_catalog.has_function_privilege(
                            'dwp_people_runtime',
                            'public.gen_random_uuid()',
                            'EXECUTE') THEN 1 ELSE 0 END
                        """));
                assertEquals(0L, DatabaseControl.scalarLong(admin, """
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
                           AND dependency.refobjid=
                               'public.gen_random_uuid()'::pg_catalog.regprocedure
                        """));
                DatabaseControl.execute(
                        admin, "GRANT SELECT ON TABLE public.ppl_persons TO " + METADATA);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> ReadOnlyMetadataDatabaseGuard.verify(metadata));
            assertThrows(
                    IllegalStateException.class,
                    () -> MigrationControlRunReceiptGuard.verify(
                            "people",
                            contracts,
                            migrationDataSource,
                            MIGRATION,
                            CONTROL_REFERENCE,
                            receipt.toJson(),
                            receipt.receiptSha256()));
        }
    }

    @Test
    void adoptionPendingV20AttestsConfiguredRuntimeAndReconcilesFinalAcl()
            throws Exception {
        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:18.4-alpine")
                        .withDatabaseName(DATABASE)
                        .withUsername(ADMIN)
                        .withPassword(ADMIN_PASSWORD)) {
            postgres.start();
            provision(postgres);
            Path throughV19 = stage(
                    "people-performance-v19",
                    "dwp-people-server/src/main/resources/db/performance-migration",
                    false);
            Files.delete(throughV19.resolve("V20__per_base_tfr_per_008.sql"));
            Path throughV20 = stage(
                    "people-performance-v20",
                    "dwp-people-server/src/main/resources/db/performance-migration",
                    false);

            StreamPlan v19Stream = performanceStream(throughV19);
            ControlEnvironment v19Environment = performanceEnvironment(
                    postgres, v19Stream, ControlEnvironment.Mode.ADOPT_OR_UPGRADE);
            assertEquals(
                    false,
                    PeoplePerformanceV20BoundaryControl.stagePendingV20(
                            v19Environment, v19Stream));
            FlywayControl.migrateAndValidate(FlywayControl.load(
                    v19Environment, v19Stream, null));

            StreamPlan v20Stream = performanceStream(throughV20);
            ControlEnvironment v20Environment = performanceEnvironment(
                    postgres, v20Stream, ControlEnvironment.Mode.ADOPT_OR_UPGRADE);
            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                DatabaseControl.execute(admin, """
                        CREATE ROLE per008_unexpected_runtime
                        NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
                        NOREPLICATION NOBYPASSRLS
                        """);
                DatabaseControl.execute(admin, """
                        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
                            hris_performance.prf_cycles,
                            hris_performance.prf_cycle_versions,
                            hris_performance.prf_command_receipts,
                            hris_performance.prf_outbox_events
                        TO per008_unexpected_runtime
                        """);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> PeoplePerformanceV20BoundaryControl.stagePendingV20(
                            v20Environment, v20Stream));
            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                assertEquals(
                        false,
                        DatabaseControl.versionApplied(admin, v20Stream, "20"));
                DatabaseControl.execute(admin, "DROP OWNED BY per008_unexpected_runtime");
                DatabaseControl.execute(admin, "DROP ROLE per008_unexpected_runtime");
            }

            assertEquals(
                    true,
                    PeoplePerformanceV20BoundaryControl.stagePendingV20(
                            v20Environment, v20Stream));
            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                DatabaseControl.execute(admin, """
                        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
                            hris_performance.prf_feedback_requests,
                            hris_performance.prf_feedback_assignments,
                            hris_performance.prf_feedback_responses,
                            hris_performance.prf_feedback_release_snapshots
                        TO dwp_people_runtime
                        """);
                DatabaseControl.execute(admin, """
                        GRANT USAGE, SELECT, UPDATE ON SEQUENCE
                            hris_performance.prf_feedback_requests_request_internal_id_seq,
                            hris_performance.prf_feedback_assignments_assignment_internal_id_seq,
                            hris_performance.prf_feedback_responses_response_internal_id_seq,
                            hris_performance.prf_feedback_release_snapshots_snapshot_internal_id_seq
                        TO dwp_people_runtime
                        """);
                admin.setAutoCommit(false);
                try {
                    PeoplePerformanceV20BoundaryControl.restoreAndVerifyFinalBoundary(
                            admin, v20Environment);
                    admin.commit();
                } catch (Exception failure) {
                    admin.rollback();
                    throw failure;
                }
                assertPer008FinalBoundary(admin);
            }
        }
    }

    private static StreamPlan performanceStream(Path location) {
        return new StreamPlan(
                "people-performance",
                "hris_performance",
                "flyway_performance_schema_history",
                "filesystem:" + location.toAbsolutePath(),
                false,
                false);
    }

    private static ControlEnvironment performanceEnvironment(
            PostgreSQLContainer<?> postgres,
            StreamPlan stream,
            ControlEnvironment.Mode mode) {
        ControlPlan plan = new ControlPlan(
                "people",
                List.of(stream),
                null,
                List.of(),
                List.of(),
                List.of());
        return new ControlEnvironment(
                mode,
                plan,
                postgres.getJdbcUrl(),
                DATABASE,
                ADMIN,
                ADMIN_PASSWORD,
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                CONTROL_REFERENCE,
                Map.of(),
                "",
                Map.of(),
                "");
    }

    private static void assertPer008FinalBoundary(Connection connection)
            throws SQLException {
        for (String table : List.of(
                "prf_feedback_requests", "prf_feedback_assignments")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
                assertEquals(1L, hasTablePrivilege(
                        connection, RUNTIME, table, privilege));
            }
            for (String privilege : List.of(
                    "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                assertEquals(0L, hasTablePrivilege(
                        connection, RUNTIME, table, privilege));
            }
        }
        for (String table : List.of(
                "prf_feedback_responses", "prf_feedback_release_snapshots")) {
            for (String privilege : List.of("SELECT", "INSERT")) {
                assertEquals(1L, hasTablePrivilege(
                        connection, RUNTIME, table, privilege));
            }
            for (String privilege : List.of(
                    "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
                assertEquals(0L, hasTablePrivilege(
                        connection, RUNTIME, table, privilege));
            }
        }
        for (String sequence : List.of(
                "prf_feedback_requests_request_internal_id_seq",
                "prf_feedback_assignments_assignment_internal_id_seq",
                "prf_feedback_responses_response_internal_id_seq",
                "prf_feedback_release_snapshots_snapshot_internal_id_seq")) {
            for (String privilege : List.of("USAGE", "SELECT")) {
                assertEquals(1L, hasSequencePrivilege(
                        connection, RUNTIME, sequence, privilege));
            }
            assertEquals(0L, DatabaseControl.scalarLong(connection, "SELECT CASE WHEN "
                    + "has_sequence_privilege('" + RUNTIME + "', 'hris_performance."
                    + sequence + "', 'UPDATE') THEN 1 ELSE 0 END"));
        }
        assertEquals(0L, DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM information_schema.table_privileges
                 WHERE table_schema = 'hris_performance'
                   AND table_name IN (
                       'prf_feedback_requests', 'prf_feedback_assignments',
                       'prf_feedback_responses', 'prf_feedback_release_snapshots')
                   AND grantee = 'PUBLIC'
                """));
        assertEquals(8L, DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'hris_performance'
                   AND relation.relname IN (
                       'prf_cycles', 'prf_cycle_versions',
                       'prf_command_receipts', 'prf_outbox_events',
                       'prf_feedback_requests', 'prf_feedback_assignments',
                       'prf_feedback_responses', 'prf_feedback_release_snapshots')
                   AND relation.relrowsecurity
                   AND relation.relforcerowsecurity
                """));
    }

    private Path stage(String name, String sourceDirectory, boolean coreLedger)
            throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(workspace.resolve("dwp-core"))) {
            workspace = workspace.getParent();
        }
        Path target = Files.createDirectory(stagingRoot.resolve(name));
        try (var sources = Files.list(workspace.resolve(sourceDirectory))) {
            for (Path source : sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                Files.copy(
                        source,
                        target.resolve(source.getFileName()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        if (coreLedger) {
            Files.copy(
                    workspace.resolve(
                            "dwp-core/src/main/resources/db/migration/"
                                    + "R__create_domain_event_delivery_ledger.sql"),
                    target.resolve("R__create_domain_event_delivery_ledger.sql"));
        }
        return target;
    }

    private static void provision(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.createStatement()) {
            for (String[] role : List.of(
                    new String[] {MIGRATION, MIGRATION_PASSWORD},
                    new String[] {RUNTIME, RUNTIME_PASSWORD},
                    new String[] {METADATA, METADATA_PASSWORD})) {
                statement.execute("CREATE ROLE " + role[0] + " LOGIN PASSWORD '"
                        + role[1] + "' NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
            }
            try (var catalogs = statement.executeQuery(
                    "SELECT datname FROM pg_catalog.pg_database")) {
                while (catalogs.next()) {
                    statement.addBatch("REVOKE ALL ON DATABASE \""
                            + catalogs.getString(1) + "\" FROM PUBLIC");
                }
            }
            statement.executeBatch();
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO "
                    + MIGRATION + ", " + RUNTIME + ", " + METADATA);
            statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            statement.execute("CREATE SCHEMA hris_performance AUTHORIZATION " + MIGRATION);
            statement.execute("REVOKE ALL ON SCHEMA hris_performance FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA hris_performance TO "
                    + MIGRATION);
            statement.execute("GRANT USAGE ON SCHEMA hris_performance TO " + RUNTIME);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                    + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
            for (String schema : List.of("public", "hris_performance")) {
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                        + " IN SCHEMA " + schema + " REVOKE ALL ON TYPES FROM PUBLIC");
            }
            statement.execute("ALTER ROLE " + MIGRATION + " IN DATABASE " + DATABASE
                    + " SET search_path TO pg_catalog, public");
            statement.execute("ALTER ROLE " + RUNTIME + " IN DATABASE " + DATABASE
                    + " SET search_path TO pg_catalog, public");
            statement.execute("ALTER ROLE " + METADATA + " IN DATABASE " + DATABASE
                    + " SET search_path TO pg_catalog");
            statement.execute(
                    "CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'");
            statement.execute(
                    "CREATE EXTENSION pgcrypto WITH SCHEMA public VERSION '1.3'");
        }
    }

    private static long hasTablePrivilege(
            Connection connection, String role, String table, String privilege)
            throws SQLException {
        return DatabaseControl.scalarLong(connection, "SELECT CASE WHEN "
                + "has_table_privilege('" + role + "', 'hris_performance."
                + table + "', '" + privilege + "') THEN 1 ELSE 0 END");
    }

    private static long hasSequencePrivilege(
            Connection connection, String role, String sequence, String privilege)
            throws SQLException {
        return DatabaseControl.scalarLong(connection, "SELECT CASE WHEN "
                + "has_sequence_privilege('" + role + "', 'hris_performance."
                + sequence + "', '" + privilege + "') THEN 1 ELSE 0 END");
    }

    private static DataSource dataSource(
            String jdbcUrl, String principal, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(principal);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static Connection connection(
            String jdbcUrl, String principal, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, principal, password);
    }
}
