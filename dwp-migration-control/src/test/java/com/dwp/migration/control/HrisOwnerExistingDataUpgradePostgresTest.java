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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Focused native-Control upgrades over an already populated PAY or TIME owner DB. */
@Testcontainers(disabledWithoutDocker = true)
class HrisOwnerExistingDataUpgradePostgresTest {
    private static final String ADMIN = "hris_upgrade_admin";
    private static final String ADMIN_PASSWORD = "a".repeat(43);
    private static final String MIGRATION_PASSWORD = "m".repeat(43);
    private static final String RUNTIME_PASSWORD = "r".repeat(43);
    private static final String PUBLISHER_PASSWORD = "p".repeat(43);
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V([1-9][0-9]*)__.+[.]sql$");

    @TempDir
    Path stagingRoot;

    @ParameterizedTest(name = "{0}-existing-data-upgrade")
    @EnumSource(OwnerService.class)
    void upgradesAReceiptSealedPopulatedDatabaseAndPreservesOwnerRuntime(
            OwnerService service) throws Exception {
        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>("postgres:18.4-alpine")
                        .withDatabaseName(service.database)
                        .withUsername(ADMIN)
                        .withPassword(ADMIN_PASSWORD)) {
            postgres.start();
            provision(postgres, service);

            Path predecessorRoot = stage(
                    service.service + "-predecessor",
                    service.migrationDirectory,
                    service.predecessorVersion);
            ControlEnvironment predecessorEnvironment = environment(
                    postgres,
                    service,
                    plan(service, predecessorRoot),
                    "dwp-migration-control-v2:" + "1".repeat(64),
                    Map.of(),
                    "");
            ControlRunReceipt predecessorReceipt = runControl(
                    postgres, predecessorEnvironment);

            assertEquals(
                    service.predecessorVersion,
                    installedVersion(postgres, service));
            seedExistingData(postgres, service);
            assertExistingDataVisibleAfterReconnect(postgres, service);

            Path currentRoot = stage(
                    service.service + "-current",
                    service.migrationDirectory,
                    Integer.MAX_VALUE);
            StreamSeal predecessorSeal = predecessorReceipt.streams().getFirst();
            ControlEnvironment currentEnvironment = environment(
                    postgres,
                    service,
                    plan(service, currentRoot),
                    "dwp-migration-control-v2:" + "2".repeat(64),
                    Map.of(predecessorSeal.streamKey(), predecessorSeal),
                    predecessorReceipt.receiptSha256());
            ControlRunReceipt currentReceipt = runControl(postgres, currentEnvironment);

            assertEquals(predecessorReceipt.receiptSha256(),
                    currentReceipt.previousRunReceiptSha256());
            assertEquals(service.currentVersion, installedVersion(postgres, service));
            assertEquals(1L, historyRowCount(postgres, service.currentVersion));
            verifyRunReceipt(postgres, service, currentReceipt);
            assertExistingDataVisibleAfterReconnect(postgres, service);
            assertOwnerTriggerBoundary(postgres, service);
            assertPostUpgradeOwnerBehavior(postgres, service);
        }
    }

    private ControlPlan plan(OwnerService service, Path migrationRoot) {
        ControlPlan standard = ControlPlan.forService(service.service);
        return new ControlPlan(
                service.service,
                List.of(new StreamPlan(
                        service.service + "-main",
                        "public",
                        "flyway_schema_history",
                        "filesystem:" + migrationRoot.toAbsolutePath(),
                        false,
                        false)),
                standard.runtimePlaceholder(),
                standard.runtimeRoutineAllowlist(),
                standard.runtimeTableDenials(),
                standard.temporaryMigrationVersions());
    }

    private ControlEnvironment environment(
            PostgreSQLContainer<?> postgres,
            OwnerService service,
            ControlPlan plan,
            String controlReference,
            Map<String, StreamSeal> previousSeals,
            String previousRunReceiptSha256) {
        return new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                plan,
                postgres.getJdbcUrl(),
                service.database,
                ADMIN,
                ADMIN_PASSWORD,
                service.migration,
                MIGRATION_PASSWORD,
                service.runtime,
                RUNTIME_PASSWORD,
                service.publisher,
                PUBLISHER_PASSWORD,
                controlReference,
                Map.of(),
                "",
                previousSeals,
                previousRunReceiptSha256);
    }

    private static ControlRunReceipt runControl(
            PostgreSQLContainer<?> postgres,
            ControlEnvironment configured) throws Exception {
        try (Connection fence = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
            DatabaseControl.acquireExclusiveControlLock(fence, configured);
            ControlPreflight.verify(fence, configured);
            ControlCredentials temporary =
                    ServiceConnectionControl.activateExclusiveControlFence(
                            fence, configured);
            ControlEnvironment active = configured.withControlCredentials(temporary);
            try {
                ControlRunReceipt receipt = MigrationControlMain.runStrictFresh(active);
                ServiceConnectionControl.restoreServiceConnections(
                        fence, configured, temporary);
                DatabaseControl.requireTemporaryDenied(fence, configured);
                return receipt;
            } catch (Exception failure) {
                ServiceConnectionControl.failClosedServiceConnections(
                        fence, configured);
                throw failure;
            }
        }
    }

    private Path stage(String name, String sourceDirectory, int maximumVersion)
            throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(workspace.resolve("dwp-core"))) {
            workspace = workspace.getParent();
        }
        Path target = Files.createDirectory(stagingRoot.resolve(name));
        try (var sources = Files.list(workspace.resolve(sourceDirectory))) {
            for (Path source : sources.filter(Files::isRegularFile).sorted().toList()) {
                Matcher matcher = VERSIONED_MIGRATION.matcher(
                        source.getFileName().toString());
                if (matcher.matches()
                        && Integer.parseInt(matcher.group(1)) <= maximumVersion) {
                    Files.copy(
                            source,
                            target.resolve(source.getFileName()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
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
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
                var statement = admin.createStatement()) {
            for (String[] role : List.of(
                    new String[] {service.migration, MIGRATION_PASSWORD},
                    new String[] {service.runtime, RUNTIME_PASSWORD},
                    new String[] {service.publisher, PUBLISHER_PASSWORD})) {
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
            statement.execute("GRANT CONNECT ON DATABASE " + service.database + " TO "
                    + service.migration + ", " + service.runtime + ", "
                    + service.publisher);
            statement.execute("ALTER SCHEMA public OWNER TO " + service.migration);
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO "
                    + service.migration);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + service.runtime);
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + service.migration
                    + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + service.migration
                    + " IN SCHEMA public REVOKE ALL ON TYPES FROM PUBLIC");
            for (String role : List.of(
                    service.migration, service.runtime, service.publisher)) {
                statement.execute("ALTER ROLE " + role + " IN DATABASE "
                        + service.database
                        + " SET search_path TO pg_catalog, public");
            }
            statement.execute(
                    "CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.7'");
        }
    }

    private static void seedExistingData(
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        try (Connection publisher = connection(
                postgres.getJdbcUrl(), service.publisher, PUBLISHER_PASSWORD);
                var statement = publisher.createStatement()) {
            if (service == OwnerService.PAYROLL) {
                statement.execute("SET dwp.payroll_tenant_id = '7'");
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.pay_legal_entity_scope_projections (
                            tenant_id, projection_id, actor_id, context_scope_key,
                            policy_revision, authorization_revision, projection_revision,
                            status, valid_from, valid_until, recorded_at)
                        VALUES (7, '70000000-0000-0000-0000-000000000001', 71,
                            'hcm-scope-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                            'rollout-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                            'psr-cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                            'pay-existing-r1', 'BUILDING', CURRENT_TIMESTAMP, NULL,
                            CURRENT_TIMESTAMP)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.pay_legal_entity_scope_members (
                            tenant_id, projection_id, legal_entity_id)
                        VALUES (7, '70000000-0000-0000-0000-000000000001',
                            '71000000-0000-0000-0000-000000000001')
                        """));
                assertEquals(1, statement.executeUpdate("""
                        UPDATE public.pay_legal_entity_scope_projections
                           SET status='ACTIVE'
                         WHERE tenant_id=7
                           AND projection_id='70000000-0000-0000-0000-000000000001'
                        """));
            } else {
                statement.execute("SET dwp.tenant_id = '7'");
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.tim_target_population_projections (
                            tenant_id, population_public_id, scope_public_ref,
                            projection_revision, lifecycle_state, effective_from,
                            effective_to, source_digest, updated_by)
                        VALUES (7, '72000000-0000-0000-0000-000000000001',
                            'population:72000000-0000-0000-0000-000000000001',
                            1, 'ACTIVE', CURRENT_DATE, NULL,
                            'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                            71)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.tim_target_population_actor_grants (
                            tenant_id, actor_id, gateway_scope_key,
                            population_public_id, population_revision, grant_revision,
                            lifecycle_state, valid_from, valid_to, source_digest,
                            updated_by)
                        VALUES (7, 71,
                            'hcm-scope-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                            '72000000-0000-0000-0000-000000000001', 1, 1,
                            'ACTIVE', CURRENT_TIMESTAMP, NULL,
                            'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                            71)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.tim_target_population_members (
                            tenant_id, population_public_id, population_revision,
                            worker_public_id, people_assignment_public_id,
                            people_assignment_revision, membership_revision,
                            lifecycle_state, effective_from, effective_to,
                            source_digest, updated_by)
                        VALUES (7, '72000000-0000-0000-0000-000000000001', 1,
                            '73000000-0000-0000-0000-000000000001',
                            '74000000-0000-0000-0000-000000000001', 1, 1,
                            'ACTIVE', CURRENT_DATE, NULL,
                            'abababababababababababababababababababababababababababababababab',
                            71)
                        """));
            }
        }
    }

    private static void assertExistingDataVisibleAfterReconnect(
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        for (int restart = 0; restart < 2; restart++) {
            try (Connection runtime = connection(
                    postgres.getJdbcUrl(), service.runtime, RUNTIME_PASSWORD);
                    var statement = runtime.createStatement()) {
                statement.execute("SET " + service.tenantSetting + " = '7'");
                assertEquals(1L, scalarLong(statement, service.persistenceQuery));
            }
        }
    }

    private static void assertOwnerTriggerBoundary(
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
            for (String function : service.ownerFunctions) {
                assertEquals(1L, DatabaseControl.scalarLong(admin, """
                        SELECT COUNT(*)
                          FROM pg_catalog.pg_proc routine
                          JOIN pg_catalog.pg_namespace routine_schema
                            ON routine_schema.oid=routine.pronamespace
                          JOIN pg_catalog.pg_roles owner_role
                            ON owner_role.oid=routine.proowner
                         WHERE routine_schema.nspname='public'
                           AND routine.proname='%s'
                           AND routine.prosecdef
                           AND routine.proconfig=ARRAY[
                               'search_path=pg_catalog, public, pg_temp']::text[]
                           AND owner_role.rolname='%s'
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM pg_catalog.aclexplode(COALESCE(
                                     routine.proacl,
                                     pg_catalog.acldefault('f', routine.proowner))) acl
                                WHERE acl.grantee=0
                                  AND acl.privilege_type='EXECUTE')
                        """.formatted(function, service.migration)));
                for (String grantee : List.of(service.runtime, service.publisher)) {
                    assertEquals(0L, DatabaseControl.scalarLong(admin, """
                            SELECT CASE WHEN pg_catalog.has_function_privilege(
                                '%s', 'public.%s()', 'EXECUTE') THEN 1 ELSE 0 END
                            """.formatted(grantee, function)));
                }
            }
        }
    }

    private static void assertPostUpgradeOwnerBehavior(
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        if (service == OwnerService.PAYROLL) {
            try (Connection publisher = connection(
                    postgres.getJdbcUrl(), service.publisher, PUBLISHER_PASSWORD);
                    var statement = publisher.createStatement()) {
                statement.execute("SET dwp.payroll_tenant_id = '7'");
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.pay_legal_entity_scope_projections (
                            tenant_id, projection_id, actor_id, context_scope_key,
                            policy_revision, authorization_revision, projection_revision,
                            status, valid_from, valid_until, recorded_at)
                        VALUES (7, '70000000-0000-0000-0000-000000000002', 71,
                            'hcm-scope-1212121212121212121212121212121212121212',
                            'rollout-3434343434343434343434343434343434343434343434343434343434343434',
                            'psr-5656565656565656565656565656565656565656565656565656565656565656',
                            'pay-post-upgrade-r2', 'BUILDING', CURRENT_TIMESTAMP, NULL,
                            CURRENT_TIMESTAMP)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO public.pay_legal_entity_scope_members (
                            tenant_id, projection_id, legal_entity_id)
                        VALUES (7, '70000000-0000-0000-0000-000000000002',
                            '71000000-0000-0000-0000-000000000002')
                        """));
                assertEquals(1, statement.executeUpdate("""
                        UPDATE public.pay_legal_entity_scope_projections SET status='ACTIVE'
                         WHERE tenant_id=7
                           AND projection_id='70000000-0000-0000-0000-000000000002'
                        """));
            }
            return;
        }
        try (Connection runtime = connection(
                postgres.getJdbcUrl(), service.runtime, RUNTIME_PASSWORD);
                var statement = runtime.createStatement()) {
            statement.execute("SET dwp.tenant_id = '7'");
            SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE public.tim_target_population_projections
                       SET updated_at=updated_at
                     WHERE tenant_id=7
                       AND population_public_id='72000000-0000-0000-0000-000000000001'
                    """));
            assertEquals("P0001", rejected.getSQLState());
        }
        try (Connection publisher = connection(
                postgres.getJdbcUrl(), service.publisher, PUBLISHER_PASSWORD);
                var statement = publisher.createStatement()) {
            statement.execute("SET dwp.tenant_id = '7'");
            assertEquals(1, statement.executeUpdate("""
                    UPDATE public.tim_target_population_projections
                       SET source_digest=
                           'cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd'
                     WHERE tenant_id=7
                       AND population_public_id='72000000-0000-0000-0000-000000000001'
                    """));
        }
    }

    private static void verifyRunReceipt(
            PostgreSQLContainer<?> postgres,
            OwnerService service,
            ControlRunReceipt receipt) {
        MigrationAdoptionGuard.Contract contract =
                new MigrationAdoptionGuard.Contract(
                        service.service + "-main",
                        "public",
                        "flyway_schema_history",
                        List.of("public"));
        MigrationControlRunReceiptGuard.verify(
                service.service,
                List.of(contract),
                dataSource(postgres.getJdbcUrl(), service.migration, MIGRATION_PASSWORD),
                service.migration,
                receipt.controlReference(),
                receipt.toJson(),
                receipt.receiptSha256());
    }

    private static int installedVersion(
            PostgreSQLContainer<?> postgres, OwnerService service) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
            return Math.toIntExact(DatabaseControl.scalarLong(admin, """
                    SELECT MAX(version::integer)
                      FROM public.flyway_schema_history
                     WHERE success AND version IS NOT NULL
                    """));
        }
    }

    private static long historyRowCount(
            PostgreSQLContainer<?> postgres, int version) throws Exception {
        try (Connection admin = connection(
                postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
            return DatabaseControl.scalarLong(admin, """
                    SELECT COUNT(*) FROM public.flyway_schema_history
                     WHERE success AND version='%d'
                    """.formatted(version));
        }
    }

    private static long scalarLong(java.sql.Statement statement, String sql)
            throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            long value = result.getLong(1);
            assertFalse(result.next());
            return value;
        }
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

    private enum OwnerService {
        PAYROLL(
                "payroll",
                "dwp_payroll_g5_upgrade",
                "dwp_payroll_migration",
                "dwp_payroll_runtime",
                "dwp_payroll_projection_publisher",
                "dwp-payroll-server/src/main/resources/db/migration",
                4,
                5,
                "dwp.payroll_tenant_id",
                """
                SELECT COUNT(*)
                  FROM public.pay_legal_entity_scope_projections projection
                  JOIN public.pay_legal_entity_scope_members member
                    ON member.tenant_id=projection.tenant_id
                   AND member.projection_id=projection.projection_id
                 WHERE projection.tenant_id=7
                   AND projection.projection_id=
                       '70000000-0000-0000-0000-000000000001'
                   AND projection.status='ACTIVE'
                   AND projection.context_scope_key=
                       'hcm-scope-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                """,
                List.of(
                        "pay_foundation_guard_receipt_transition",
                        "pay_guard_legal_entity_scope_projection_transition",
                        "pay_guard_legal_entity_scope_member_lifecycle")),
        TIME(
                "time",
                "dwp_time_g5_upgrade",
                "dwp_time_migration",
                "dwp_time_runtime",
                "dwp_time_projection_publisher",
                "dwp-time-server/src/main/resources/db/migration",
                6,
                7,
                "dwp.tenant_id",
                """
                SELECT COUNT(*)
                  FROM public.tim_target_population_projections projection
                  JOIN public.tim_target_population_actor_grants actor_grant
                    ON actor_grant.tenant_id=projection.tenant_id
                   AND actor_grant.population_public_id=projection.population_public_id
                  JOIN public.tim_target_population_members member
                    ON member.tenant_id=projection.tenant_id
                   AND member.population_public_id=projection.population_public_id
                 WHERE projection.tenant_id=7
                   AND projection.population_public_id=
                       '72000000-0000-0000-0000-000000000001'
                   AND actor_grant.gateway_scope_key=
                       'hcm-scope-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
                """,
                List.of(
                        "tim_reject_target_evidence_mutation",
                        "tim_reject_command_authority_evidence_mutation",
                        "tim_guard_work_regime_sod_evidence",
                        "tim_reject_runtime_projection_update"));

        private final String service;
        private final String database;
        private final String migration;
        private final String runtime;
        private final String publisher;
        private final String migrationDirectory;
        private final int predecessorVersion;
        private final int currentVersion;
        private final String tenantSetting;
        private final String persistenceQuery;
        private final List<String> ownerFunctions;

        OwnerService(
                String service,
                String database,
                String migration,
                String runtime,
                String publisher,
                String migrationDirectory,
                int predecessorVersion,
                int currentVersion,
                String tenantSetting,
                String persistenceQuery,
                List<String> ownerFunctions) {
            this.service = service;
            this.database = database;
            this.migration = migration;
            this.runtime = runtime;
            this.publisher = publisher;
            this.migrationDirectory = migrationDirectory;
            this.predecessorVersion = predecessorVersion;
            this.currentVersion = currentVersion;
            this.tenantSetting = tenantSetting;
            this.persistenceQuery = persistenceQuery;
            this.ownerFunctions = ownerFunctions;
        }
    }
}
