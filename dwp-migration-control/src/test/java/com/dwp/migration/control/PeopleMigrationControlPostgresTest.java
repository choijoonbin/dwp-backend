package com.dwp.migration.control;

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

            ReadOnlyMetadataDatabaseGuard.Source metadata =
                    new ReadOnlyMetadataDatabaseGuard.Source(
                            "Provider", "people", DATABASE, postgres.getJdbcUrl(),
                            METADATA, METADATA_PASSWORD);
            ReadOnlyMetadataDatabaseGuard.verify(metadata);

            try (Connection admin = connection(
                    postgres.getJdbcUrl(), ADMIN, ADMIN_PASSWORD)) {
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
