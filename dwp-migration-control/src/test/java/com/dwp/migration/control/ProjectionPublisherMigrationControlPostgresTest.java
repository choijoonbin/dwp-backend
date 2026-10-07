package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProjectionPublisherMigrationControlPostgresTest {
    private static final String ADMIN = "control_admin";
    private static final String ADMIN_PASSWORD = "a".repeat(43);
    private static final String MIGRATION_PASSWORD = "m".repeat(43);
    private static final String RUNTIME_PASSWORD = "r".repeat(43);
    private static final String PUBLISHER_PASSWORD = "p".repeat(43);
    private static final String CONTROL_REFERENCE =
            "dwp-migration-control-v2:" + "c".repeat(64);

    @TempDir
    Path stagingRoot;

    @ParameterizedTest(name = "payroll-{0}")
    @ValueSource(strings = {"postgres:16-alpine", "postgres:18.4-alpine"})
    void payrollFreshRunSealsPublisherAndKeepsRuntimeReadOnly(String postgresImage)
            throws Exception {
        exercise(
                postgresImage,
                "payroll",
                "dwp_payroll_w1",
                "dwp_payroll_migration",
                "dwp_payroll_runtime",
                "dwp_payroll_projection_publisher",
                "dwp-payroll-server/src/main/resources/db/migration",
                "pay_legal_entity_scope_projections",
                "dwp.payroll_tenant_id",
                """
                INSERT INTO public.pay_legal_entity_scope_projections (
                    tenant_id, projection_id, actor_id, context_scope_key,
                    policy_revision, authorization_revision, projection_revision,
                    status, valid_from, valid_until, recorded_at)
                VALUES (1, '10000000-0000-0000-0000-000000000001', 11,
                    'hcm-scope-0000000000000000000000000000000000000001',
                    'rollout-%s', 'psr-%s', 'feed-1', 'BUILDING',
                    CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)
                """.formatted("1".repeat(64), "2".repeat(64)));
    }

    @ParameterizedTest(name = "time-{0}")
    @ValueSource(strings = {"postgres:16-alpine", "postgres:18.4-alpine"})
    void timeFreshRunSealsPublisherAndKeepsRuntimeReadOnly(String postgresImage)
            throws Exception {
        exercise(
                postgresImage,
                "time",
                "dwp_time_w1",
                "dwp_time_migration",
                "dwp_time_runtime",
                "dwp_time_projection_publisher",
                "dwp-time-server/src/main/resources/db/migration",
                "tim_target_population_projections",
                "dwp.tenant_id",
                """
                INSERT INTO public.tim_target_population_projections (
                    tenant_id, population_public_id, scope_public_ref,
                    projection_revision, lifecycle_state, effective_from,
                    effective_to, source_digest, updated_by)
                VALUES (1, '20000000-0000-0000-0000-000000000001',
                    'population:20000000-0000-0000-0000-000000000001',
                    1, 'ACTIVE', CURRENT_DATE, NULL, '%s', 11)
                """.formatted("3".repeat(64)));
    }

    private void exercise(
            String postgresImage,
            String service,
            String database,
            String migration,
            String runtime,
            String publisher,
            String serviceMigrationDirectory,
            String projectionTable,
            String tenantSetting,
            String publisherInsert) throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(postgresImage)
                .withDatabaseName(database)
                .withUsername(ADMIN)
                .withPassword(ADMIN_PASSWORD)) {
            postgres.start();
            provision(postgres, service, database, migration, runtime, publisher);
            Path migrations = stage(service, serviceMigrationDirectory);
            ControlPlan standard = ControlPlan.forService(service);
            ControlPlan plan = new ControlPlan(
                    service,
                    List.of(new StreamPlan(
                            service + "-main",
                            "public",
                            "flyway_schema_history",
                            "filesystem:" + migrations.toAbsolutePath(),
                            false,
                            false)),
                    standard.runtimePlaceholder(),
                    standard.runtimeRoutineAllowlist(),
                    standard.runtimeTableDenials(),
                    standard.temporaryMigrationVersions());
            ControlEnvironment configured = new ControlEnvironment(
                    ControlEnvironment.Mode.STRICT_FRESH,
                    plan,
                    postgres.getJdbcUrl(),
                    database,
                    ADMIN,
                    ADMIN_PASSWORD,
                    migration,
                    MIGRATION_PASSWORD,
                    runtime,
                    RUNTIME_PASSWORD,
                    publisher,
                    PUBLISHER_PASSWORD,
                    CONTROL_REFERENCE,
                    Map.of(),
                    "",
                    Map.of(),
                    "");

            if ("payroll".equals(service)) {
                assertPreflightRejectsUnexpectedExtensionAndPublisherDrift(
                        postgres, postgresImage, database, configured, publisher);
            }

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
                    receipt = MigrationControlMain.runStrictFresh(active);
                    ServiceConnectionControl.restoreServiceConnections(
                            fence, configured, temporary);
                } catch (Exception failure) {
                    ServiceConnectionControl.failClosedServiceConnections(
                            fence, configured);
                    throw failure;
                }
            }

            MigrationAdoptionGuard.Contract contract =
                    new MigrationAdoptionGuard.Contract(
                            service + "-main",
                            "public",
                            "flyway_schema_history",
                            List.of("public"));
            DataSource migrationDataSource = dataSource(
                    postgres.getJdbcUrl(), migration, MIGRATION_PASSWORD);
            MigrationControlRunReceiptGuard.verify(
                    service,
                    List.of(contract),
                    migrationDataSource,
                    migration,
                    CONTROL_REFERENCE,
                    receipt.toJson(),
                    receipt.receiptSha256());

            assertPublisherPosture(
                    postgres, database, publisher, projectionTable);
            assertPublisherDatabaseScope(
                    postgres, database, publisher);
            assertRuntimeCannotMutate(
                    postgres, runtime, projectionTable, tenantSetting,
                    publisherInsert);
            assertPublisherCanInsert(
                    postgres, publisher, tenantSetting, publisherInsert);
            if ("time".equals(service)) {
                assertTimeRuntimeLockBoundary(postgres, runtime, publisher);
                assertTimeRuntimePrincipalDriftRejectedBeforeGrant(
                        postgres, configured, runtime);
            }

            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
                DatabaseControl.execute(admin, "GRANT DELETE ON TABLE public."
                        + projectionTable + " TO " + publisher);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> MigrationControlRunReceiptGuard.verify(
                            service,
                            List.of(contract),
                            migrationDataSource,
                            migration,
                            CONTROL_REFERENCE,
                            receipt.toJson(),
                            receipt.receiptSha256()));
        }
    }

    private static void assertPreflightRejectsUnexpectedExtensionAndPublisherDrift(
            PostgreSQLContainer<?> postgres,
            String postgresImage,
            String database,
            ControlEnvironment environment,
            String publisher) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.createStatement()) {
            statement.execute("CREATE EXTENSION hstore WITH SCHEMA public");
            assertThrows(
                    IllegalStateException.class,
                    () -> RequiredDatabaseExtensionControl.requireExact(
                            admin, environment));
            statement.execute("DROP EXTENSION hstore");
            RequiredDatabaseExtensionControl.requireExact(admin, environment);

            if (postgresImage.startsWith("postgres:18")) {
                statement.execute("DROP EXTENSION btree_gist");
                statement.execute("CREATE EXTENSION btree_gist WITH SCHEMA public");
                assertThrows(
                        IllegalStateException.class,
                        () -> RequiredDatabaseExtensionControl.requireExact(
                                admin, environment));
                statement.execute("DROP EXTENSION btree_gist");
                statement.execute(
                        "CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'");
                RequiredDatabaseExtensionControl.requireExact(admin, environment);
            }

            assertTransactionalDriftRejected(
                    admin,
                    "ALTER ROLE " + publisher + " CONNECTION LIMIT 2",
                    () -> DatabaseControl.requireStrictServiceLoginRoles(
                            admin, environment));
            assertTransactionalDriftRejected(
                    admin,
                    "ALTER ROLE " + publisher
                            + " VALID UNTIL '2099-01-01 00:00:00+00'",
                    () -> DatabaseControl.requireStrictServiceLoginRoles(
                            admin, environment));
            assertTransactionalDriftRejected(
                    admin,
                    "ALTER ROLE " + publisher
                            + " SET statement_timeout TO '1s'",
                    () -> DatabaseControl.requireStrictServiceLoginRoles(
                            admin, environment));
            assertTransactionalDriftRejected(
                    admin,
                    "ALTER ROLE " + publisher + " IN DATABASE " + database
                            + " SET search_path TO public",
                    () -> RoleSearchPathFence.requireExact(admin, environment));
            assertTransactionalDriftRejected(
                    admin,
                    "GRANT CONNECT ON DATABASE postgres TO " + publisher,
                    () -> DatabaseConnectionFence.requireDatabaseAcl(
                            admin, environment,
                            DatabaseConnectionFence.State.BASELINE));
            DatabaseControl.requireStrictServiceLoginRoles(admin, environment);
            RoleSearchPathFence.requireExact(admin, environment);
            DatabaseConnectionFence.requireDatabaseAcl(
                    admin, environment, DatabaseConnectionFence.State.BASELINE);
        }
    }

    private static void assertTransactionalDriftRejected(
            Connection admin, String drift, SqlVerifier verifier) throws Exception {
        boolean autoCommit = admin.getAutoCommit();
        admin.setAutoCommit(false);
        try (var statement = admin.createStatement()) {
            statement.execute(drift);
            assertThrows(IllegalStateException.class, verifier::verify);
        } finally {
            admin.rollback();
            admin.setAutoCommit(autoCommit);
        }
    }

    private Path stage(String service, String serviceMigrationDirectory)
            throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(workspace.resolve("dwp-core"))) {
            workspace = workspace.getParent();
        }
        Path target = Files.createDirectory(stagingRoot.resolve(service));
        try (var sources = Files.list(workspace.resolve(serviceMigrationDirectory))) {
            for (Path source : sources.filter(Files::isRegularFile).toList()) {
                Files.copy(
                        source,
                        target.resolve(source.getFileName()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Files.copy(
                workspace.resolve(
                        "dwp-core/src/main/resources/db/migration/"
                                + "R__create_domain_event_delivery_ledger.sql"),
                target.resolve("R__create_domain_event_delivery_ledger.sql"));
        return target;
    }

    private static void provision(
            PostgreSQLContainer<?> postgres,
            String service,
            String database,
            String migration,
            String runtime,
            String publisher) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.createStatement()) {
            for (String[] role : List.of(
                    new String[] {migration, MIGRATION_PASSWORD},
                    new String[] {runtime, RUNTIME_PASSWORD},
                    new String[] {publisher, PUBLISHER_PASSWORD})) {
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
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO "
                    + migration + ", " + runtime + ", " + publisher);
            statement.execute("ALTER SCHEMA public OWNER TO " + migration);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + migration);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + runtime);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                    + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                    + " IN SCHEMA public REVOKE ALL ON TYPES FROM PUBLIC");
            statement.execute("ALTER ROLE " + migration + " IN DATABASE " + database
                    + " SET search_path TO pg_catalog, public");
            statement.execute("ALTER ROLE " + runtime + " IN DATABASE " + database
                    + " SET search_path TO pg_catalog, public");
            statement.execute("ALTER ROLE " + publisher + " IN DATABASE " + database
                    + " SET search_path TO pg_catalog, public");
            if ("payroll".equals(service) || "time".equals(service)) {
                statement.execute(
                        "CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'");
            }
        }
    }

    private static void assertPublisherPosture(
            PostgreSQLContainer<?> postgres,
            String database,
            String publisher,
            String projectionTable) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.prepareStatement("""
                        SELECT role.rolcanlogin
                                   AND NOT (role.rolsuper OR role.rolcreatedb
                                       OR role.rolcreaterole OR role.rolinherit
                                       OR role.rolreplication OR role.rolbypassrls)
                                   AND role.rolconnlimit=-1
                                   AND role.rolvaliduntil IS NULL
                                   AND role.rolconfig IS NULL,
                               has_database_privilege(?, ?, 'CONNECT'),
                               NOT has_database_privilege(?, ?, 'CREATE')
                                   AND NOT has_database_privilege(?, ?, 'TEMPORARY'),
                               has_schema_privilege(?, 'public', 'USAGE'),
                               NOT has_schema_privilege(?, 'public', 'CREATE'),
                               has_table_privilege(?, 'public.' || ?, 'SELECT,INSERT'),
                               NOT has_table_privilege(?, 'public.' || ?, 'DELETE')
                                   AND NOT has_table_privilege(
                                       ?, 'public.' || ?, 'TRUNCATE')
                                   AND NOT has_table_privilege(
                                       ?, 'public.' || ?, 'REFERENCES')
                                   AND NOT has_table_privilege(
                                       ?, 'public.' || ?, 'TRIGGER')
                          FROM pg_catalog.pg_roles role
                         WHERE role.rolname=?
                        """)) {
            int index = 1;
            statement.setString(index++, publisher);
            statement.setString(index++, database);
            statement.setString(index++, publisher);
            statement.setString(index++, database);
            statement.setString(index++, publisher);
            statement.setString(index++, database);
            statement.setString(index++, publisher);
            statement.setString(index++, publisher);
            statement.setString(index++, publisher);
            statement.setString(index++, projectionTable);
            statement.setString(index++, publisher);
            statement.setString(index++, projectionTable);
            statement.setString(index++, publisher);
            statement.setString(index++, projectionTable);
            statement.setString(index++, publisher);
            statement.setString(index++, projectionTable);
            statement.setString(index++, publisher);
            statement.setString(index++, projectionTable);
            statement.setString(index, publisher);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                for (int column = 1; column <= 7; column++) {
                    assertTrue(result.getBoolean(column), "publisher posture column " + column);
                }
                assertFalse(result.next());
            }
        }
    }

    private static void assertPublisherDatabaseScope(
            PostgreSQLContainer<?> postgres,
            String database,
            String publisher) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var connectivity = admin.prepareStatement("""
                        SELECT candidate.datname
                          FROM pg_catalog.pg_database candidate
                         WHERE candidate.datallowconn
                           AND pg_catalog.has_database_privilege(
                               ?, candidate.oid, 'CONNECT')
                         ORDER BY candidate.datname
                        """);
                var settings = admin.prepareStatement("""
                        SELECT COALESCE(candidate.datname, '*') || ':' || setting.value
                          FROM pg_catalog.pg_db_role_setting configured
                          JOIN pg_catalog.pg_roles role
                            ON role.oid=configured.setrole
                          LEFT JOIN pg_catalog.pg_database candidate
                            ON candidate.oid=configured.setdatabase
                         CROSS JOIN LATERAL unnest(configured.setconfig) setting(value)
                         WHERE role.rolname=?
                         ORDER BY 1
                        """)) {
            connectivity.setString(1, publisher);
            assertEquals(
                    List.of(database), stringRows(connectivity.executeQuery()));
            settings.setString(1, publisher);
            assertEquals(
                    List.of(database + ":search_path=pg_catalog, public"),
                    stringRows(settings.executeQuery()));
        }
        try (Connection publisherConnection = connection(
                postgres.getJdbcUrl(), publisher, PUBLISHER_PASSWORD);
                var statement = publisherConnection.createStatement();
                var result = statement.executeQuery("SHOW search_path")) {
            assertTrue(result.next());
            assertEquals("pg_catalog, public", result.getString(1));
            assertFalse(result.next());
        }
    }

    private static List<String> stringRows(java.sql.ResultSet result)
            throws SQLException {
        try (result) {
            List<String> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(result.getString(1));
            }
            return List.copyOf(rows);
        }
    }

    private static void assertRuntimeCannotMutate(
            PostgreSQLContainer<?> postgres,
            String runtime,
            String projectionTable,
            String tenantSetting,
            String otherwiseValidInsert) throws Exception {
        try (Connection connection = connection(
                postgres.getJdbcUrl(), runtime, RUNTIME_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("SET " + tenantSetting + " = '1'");
            assertDmlDenied(statement, otherwiseValidInsert);
            assertDmlDenied(
                    statement,
                    "UPDATE public." + projectionTable + " SET tenant_id=tenant_id");
            assertDmlDenied(
                    statement,
                    "DELETE FROM public." + projectionTable);
        }
    }

    private static void assertDmlDenied(java.sql.Statement statement, String sql) {
        assertSqlState(statement, sql, "42501");
    }

    private static void assertSqlState(
            java.sql.Statement statement, String sql, String expectedSqlState) {
        SQLException denied = assertThrows(
                SQLException.class,
                () -> statement.execute(sql));
        assertEquals(expectedSqlState, denied.getSQLState());
    }

    private static void assertTimeRuntimeLockBoundary(
            PostgreSQLContainer<?> postgres,
            String runtime,
            String publisher) throws Exception {
        List<String> projectionTables = List.of(
                "tim_target_population_actor_grants",
                "tim_target_population_members",
                "tim_target_population_projections");
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var tablePrivilege = admin.prepareStatement("""
                        SELECT table_name
                          FROM unnest(?::text[]) table_name
                         WHERE has_table_privilege(
                                   ?, 'public.' || table_name, 'UPDATE')
                         ORDER BY table_name
                        """);
                var columnPrivilege = admin.prepareStatement("""
                        SELECT columns.table_name || '|' || columns.column_name
                          FROM information_schema.columns columns
                         WHERE columns.table_schema='public'
                           AND columns.table_name=ANY (?::text[])
                           AND has_column_privilege(
                                   ?, format('%I.%I', columns.table_schema,
                                             columns.table_name),
                                   columns.column_name, 'UPDATE')
                         ORDER BY columns.table_name, columns.ordinal_position
                        """)) {
            java.sql.Array tables = admin.createArrayOf(
                    "text", projectionTables.toArray(String[]::new));
            tablePrivilege.setArray(1, tables);
            tablePrivilege.setString(2, runtime);
            assertEquals(List.of(), stringRows(tablePrivilege.executeQuery()));
            columnPrivilege.setArray(1, tables);
            columnPrivilege.setString(2, runtime);
            assertEquals(
                    List.of(
                            "tim_target_population_actor_grants|updated_at",
                            "tim_target_population_members|updated_at",
                            "tim_target_population_projections|updated_at"),
                    stringRows(columnPrivilege.executeQuery()));
        }

        try (Connection publisherConnection = connection(
                postgres.getJdbcUrl(), publisher, PUBLISHER_PASSWORD);
                var statement = publisherConnection.createStatement()) {
            statement.execute("SET dwp.tenant_id = '1'");
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO public.tim_target_population_actor_grants (
                        tenant_id, actor_id, gateway_scope_key,
                        population_public_id, population_revision, grant_revision,
                        lifecycle_state, valid_from, valid_to, source_digest,
                        updated_by)
                    VALUES (
                        1, 11,
                        'hcm-scope-4444444444444444444444444444444444444444',
                        '20000000-0000-0000-0000-000000000001', 1, 1,
                        'ACTIVE', CURRENT_TIMESTAMP, NULL,
                        '5555555555555555555555555555555555555555555555555555555555555555',
                        11)
                    """));
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO public.tim_target_population_members (
                        tenant_id, population_public_id, population_revision,
                        worker_public_id, people_assignment_public_id,
                        people_assignment_revision, membership_revision,
                        lifecycle_state, effective_from, effective_to,
                        source_digest, updated_by)
                    VALUES (
                        1, '20000000-0000-0000-0000-000000000001', 1,
                        '30000000-0000-0000-0000-000000000001',
                        '40000000-0000-0000-0000-000000000001', 1, 1,
                        'ACTIVE', CURRENT_DATE, NULL,
                        '6666666666666666666666666666666666666666666666666666666666666666',
                        11)
                    """));
        }

        try (Connection runtimeConnection = connection(
                postgres.getJdbcUrl(), runtime, RUNTIME_PASSWORD);
                var statement = runtimeConnection.createStatement()) {
            statement.execute("SET dwp.tenant_id = '1'");
            try (var result = statement.executeQuery("""
                    SELECT 1
                      FROM public.tim_target_population_projections p
                      JOIN public.tim_target_population_actor_grants g
                        ON g.tenant_id=p.tenant_id
                       AND g.population_public_id=p.population_public_id
                       AND g.population_revision=p.projection_revision
                      JOIN public.tim_target_population_members m
                        ON m.tenant_id=p.tenant_id
                       AND m.population_public_id=p.population_public_id
                       AND m.population_revision=p.projection_revision
                     WHERE p.tenant_id=1
                       AND p.population_public_id=
                           '20000000-0000-0000-0000-000000000001'
                       AND g.actor_id=11
                       AND g.gateway_scope_key=
                           'hcm-scope-4444444444444444444444444444444444444444'
                       AND m.people_assignment_public_id=
                           '40000000-0000-0000-0000-000000000001'
                     FOR SHARE OF p, g, m
                    """)) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertFalse(result.next());
            }
            assertDmlDenied(
                    statement,
                    "UPDATE public.tim_target_population_projections "
                            + "SET projection_revision=projection_revision");
            assertDmlDenied(
                    statement,
                    "UPDATE public.tim_target_population_actor_grants "
                            + "SET grant_revision=grant_revision");
            assertDmlDenied(
                    statement,
                    "UPDATE public.tim_target_population_members "
                            + "SET membership_revision=membership_revision");
            for (String table : projectionTables) {
                assertSqlState(
                        statement,
                        "UPDATE public." + table
                                + " SET updated_at=updated_at WHERE tenant_id=1",
                        "P0001");
            }
        }
    }

    private static void assertTimeRuntimePrincipalDriftRejectedBeforeGrant(
            PostgreSQLContainer<?> postgres,
            ControlEnvironment environment,
            String runtime) throws Exception {
        List<String> projectionTables = List.of(
                "tim_target_population_actor_grants",
                "tim_target_population_members",
                "tim_target_population_projections");
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.createStatement()) {
            admin.setAutoCommit(false);
            try {
                List<RuntimeColumnUpdateGrant> unguardedGrants = new ArrayList<>(
                        environment.plan().runtimeColumnUpdateGrants());
                unguardedGrants.add(new RuntimeColumnUpdateGrant(
                        "public",
                        "tim_target_population_projections",
                        List.of("source_digest")));
                IllegalStateException unguardedGrantFailure = assertThrows(
                        IllegalStateException.class,
                        () -> TimeRuntimeProjectionLockControl.verify(
                                admin, environment, unguardedGrants));
                assertTrue(unguardedGrantFailure.getMessage().contains(
                        "runtime column UPDATE grants differ"));
                statement.execute("""
                        CREATE OR REPLACE FUNCTION
                            public.tim_reject_runtime_projection_update()
                        RETURNS TRIGGER
                        LANGUAGE plpgsql
                        SECURITY DEFINER
                        SET search_path = pg_catalog, public, pg_temp
                        AS $function$
                        BEGIN
                            IF session_user = 'dwp_time_runtime_stale'
                               OR current_setting('role', true) = 'dwp_time_runtime_stale' THEN
                                RAISE EXCEPTION 'TIM runtime cannot mutate target-population authority projections';
                            END IF;
                            RETURN NEW;
                        END;
                        $function$
                        """);
                for (String table : projectionTables) {
                    statement.execute("REVOKE UPDATE (updated_at) ON TABLE public."
                            + table + " FROM " + runtime);
                }
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> DomainPrivilegeControl.normalize(admin, environment));
                assertTrue(failure.getMessage().contains(
                        "TIME runtime projection lock boundary is invalid"));
                assertEquals(
                        List.of(),
                        runtimeUpdateColumns(
                                admin, runtime, projectionTables));
            } finally {
                admin.rollback();
            }
            try {
                statement.execute("""
                        ALTER TABLE public.tim_target_population_projections
                        DISABLE TRIGGER trg_tim_reject_runtime_population_update
                        """);
                for (String table : projectionTables) {
                    statement.execute("REVOKE UPDATE (updated_at) ON TABLE public."
                            + table + " FROM " + runtime);
                }
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> DomainPrivilegeControl.normalize(admin, environment));
                assertTrue(failure.getMessage().contains(
                        "TIME runtime projection lock boundary is invalid"));
                assertEquals(
                        List.of(),
                        runtimeUpdateColumns(
                                admin, runtime, projectionTables));
            } finally {
                admin.rollback();
            }
        }
    }

    private static List<String> runtimeUpdateColumns(
            Connection connection,
            String runtime,
            List<String> tables) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT columns.table_name || '|' || columns.column_name
                  FROM information_schema.columns columns
                 WHERE columns.table_schema='public'
                   AND columns.table_name=ANY (?::text[])
                   AND has_column_privilege(
                           ?, format('%I.%I', columns.table_schema,
                                     columns.table_name),
                           columns.column_name, 'UPDATE')
                 ORDER BY columns.table_name, columns.ordinal_position
                """)) {
            statement.setArray(
                    1,
                    connection.createArrayOf(
                            "text", tables.toArray(String[]::new)));
            statement.setString(2, runtime);
            return stringRows(statement.executeQuery());
        }
    }

    private static void assertPublisherCanInsert(
            PostgreSQLContainer<?> postgres,
            String publisher,
            String tenantSetting,
            String insert) throws Exception {
        try (Connection connection = connection(
                postgres.getJdbcUrl(), publisher, PUBLISHER_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("SET " + tenantSetting + " = '1'");
            assertEquals(1, statement.executeUpdate(insert));
            assertDmlDenied(statement, "UPDATE public."
                    + tableName(insert) + " SET tenant_id=tenant_id");
            assertDmlDenied(statement, "DELETE FROM public." + tableName(insert));
        }
    }

    private static String tableName(String insert) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)^\\s*INSERT\\s+INTO\\s+public[.]([a-z][a-z0-9_]{0,62})")
                .matcher(insert);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Publisher insert table is not canonical");
        }
        return matcher.group(1);
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

    @FunctionalInterface
    private interface SqlVerifier {
        void verify() throws Exception;
    }
}
