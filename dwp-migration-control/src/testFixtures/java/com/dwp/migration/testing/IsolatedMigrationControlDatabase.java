package com.dwp.migration.testing;

import com.dwp.core.database.MigrationAdoptionGuard.Contract;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real offline Control plus a disposable PG container. This fixture cannot
 * target an arbitrary JDBC endpoint or a caller's pre-existing container.
 * Test properties contain in-memory credentials and must never be logged.
 * Additional service plans require explicit fixture configuration review.
 */
public final class IsolatedMigrationControlDatabase implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOOTSTRAP = "fixture_bootstrap";
    private static final String MIGRATION = "fixture_migration";
    private static final String RUNTIME = "fixture_runtime";
    private static final String RETENTION_OWNER = "dwp_approval_retention_owner";
    private static final String RETENTION_SCHEMA = "apr_retention_internal";
    private static final String SIGNATURE_SCHEMA = "apr_signature_native";
    private static final String RECEIPT_PREFIX = "DWP_MIGRATION_CONTROL_RECEIPT=";
    private final String bootstrapPassword = password();
    private final String migrationPassword = password();
    private final String runtimePassword = password();
    private final String database = "fixture_approval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private PostgreSQLContainer<?> postgres;
    private String reference;
    private String receiptJson;
    private MigrationControlRunReceiptGuard.RunReceipt receipt;
    private boolean closed;

    private IsolatedMigrationControlDatabase() {
    }

    public static IsolatedMigrationControlDatabase approval() {
        return new IsolatedMigrationControlDatabase();
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Isolated Control fixture is already closed");
        }
        if (receipt != null) {
            return;
        }
        Path root = configuredRoot();
        String classpath = requiredProperty("dwp.test.migration-control.classpath.approval");
        String image = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:18.4-alpine");
        if (!Set.of("postgres:16-alpine", "postgres:18.4-alpine").contains(image)) {
            throw new IllegalArgumentException("Isolated Control fixture PG image is not approved");
        }
        postgres = new PostgreSQLContainer<>(image)
                .withDatabaseName(database).withUsername(BOOTSTRAP)
                .withPassword(bootstrapPassword).withReuse(false)
                .withStartupAttempts(1).withStartupTimeout(Duration.ofSeconds(90));
        try {
            postgres.start();
            ControlFixtureProvisioning.freshApproval(postgres.getJdbcUrl(), database,
                    BOOTSTRAP, bootstrapPassword, MIGRATION, migrationPassword,
                    RUNTIME, runtimePassword);
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            reference = ControlFixtureProcess.run(List.of("python3", "-I", "-c",
                    "import sys,runpy;sys.dont_write_bytecode=True;print(runpy.run_path('scripts/devctl.py')['migration_control_reference']())"),
                    root, environment, Duration.ofSeconds(30), secrets()).trim();
            if (!reference.matches("dwp-migration-control-v2:[0-9a-f]{64}")) {
                throw new IllegalStateException("Current source Control reference is not canonical");
            }
            environment.put("DWP_MIGRATION_CONTROL_MODE", "STRICT_FRESH");
            environment.put("DWP_MIGRATION_CONTROL_SERVICE", "approval");
            environment.put("DWP_MIGRATION_CONTROL_JDBC_URL", postgres.getJdbcUrl());
            environment.put("DWP_MIGRATION_CONTROL_DATABASE", database);
            environment.put("DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL", BOOTSTRAP);
            environment.put("DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD", bootstrapPassword);
            environment.put("DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL", MIGRATION);
            environment.put("DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD", migrationPassword);
            environment.put("DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL", RUNTIME);
            environment.put("DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD", runtimePassword);
            environment.put("DWP_MIGRATION_CONTROL_REFERENCE", reference);
            String output = ControlFixtureProcess.run(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-cp", classpath,
                    "com.dwp.migration.control.MigrationControlMain"),
                    root, environment, Duration.ofSeconds(180), secrets());
            List<String> receiptLines = output.lines()
                    .filter(line -> line.startsWith(RECEIPT_PREFIX)).toList();
            if (receiptLines.size() != 1) {
                throw new IllegalStateException("Real Control child must emit exactly one run receipt");
            }
            receiptJson = receiptLines.getFirst().substring(RECEIPT_PREFIX.length());
            var tree = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(receiptJson);
            receipt = MigrationControlRunReceiptGuard.verify("approval",
                    List.of(approvalContract()),
                    ControlFixtureProvisioning.dataSource(postgres.getJdbcUrl(), MIGRATION, migrationPassword),
                    MIGRATION, reference, receiptJson, tree.path("receiptSha256").asText(),
                    Set.of(RETENTION_OWNER));
        } catch (Exception exception) {
            boolean cleanupFailed = false;
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                cleanupFailed = true;
            }
            throw new IllegalStateException("Isolated Control fixture failed: "
                    + ControlFixtureProcess.safeDiagnostic(exception.getMessage() == null
                            ? exception.getClass().getSimpleName() : exception.getMessage(), secrets())
                    + (cleanupFailed ? "; private-container removal is unconfirmed after cleanup failure" : ""));
        }
    }

    public synchronized Map<String, Object> springProperties() {
        start();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spring.datasource.url", postgres.getJdbcUrl());
        values.put("spring.datasource.username", RUNTIME);
        values.put("spring.datasource.password", runtimePassword);
        values.put("spring.datasource.hikari.connection-init-sql", "SET search_path TO pg_catalog, public");
        values.put("spring.datasource.hikari.maximum-pool-size", 3);
        values.put("spring.datasource.hikari.connection-timeout", 10000);
        values.put("spring.flyway.enabled", true);
        values.put("spring.flyway.url", postgres.getJdbcUrl());
        values.put("spring.flyway.user", MIGRATION);
        values.put("spring.flyway.password", migrationPassword);
        values.put("spring.flyway.locations", List.of("classpath:db/migration"));
        values.put("spring.flyway.schemas", List.of("public"));
        values.put("spring.flyway.default-schema", "public");
        values.put("spring.flyway.table", "flyway_schema_history");
        values.put("spring.flyway.baseline-on-migrate", true);
        values.put("spring.flyway.baseline-version", "0");
        values.put("spring.flyway.validate-on-migrate", true);
        values.put("spring.flyway.out-of-order", false);
        values.put("spring.flyway.fail-on-missing-locations", true);
        values.put("spring.flyway.clean-disabled", true);
        values.put("spring.flyway.create-schemas", false);
        values.put("spring.flyway.validate-migration-naming", true);
        values.put("spring.flyway.output-query-results", false);
        values.put("spring.flyway.community-db-support-enabled", false);
        values.put("spring.flyway.skip-executing-migrations", false);
        values.put("spring.flyway.ignore-migration-patterns", List.of());
        values.put("spring.flyway.connect-retries", 0);
        values.put("spring.flyway.lock-retry-count", 50);
        values.put("dwp.approval.database.migration-principal-policy", "STRICT");
        values.put("dwp.approval.database.runtime-environment", "test");
        values.put("dwp.approval.database.service-instance", database);
        values.put("dwp.approval.database.control-run-receipt-json", receiptJson);
        values.put("dwp.approval.database.control-run-receipt-sha256", receipt.receiptSha256());
        values.put("dwp.approval.database.migration-control-reference", reference);
        values.put("dwp.approval.database.migration-control-bootstrap-principal", BOOTSTRAP);
        values.put("dwp.approval.database.adoption-receipt-sha256", "");
        values.put("dwp.approval.database.adoption-control-reference", "");
        values.put("dwp.observability.api-history.enabled", false);
        values.put("dwp.events.transport-enabled", false);
        values.put("dwp.audit.collector-url", "");
        values.put("dwp.audit.ingest-token", "");
        return Map.copyOf(values);
    }

    public synchronized String databaseName() {
        start();
        return database;
    }

    public synchronized String jdbcUrl() {
        start();
        return postgres.getJdbcUrl();
    }

    public synchronized void verifyNativeSeal() {
        start();
        MigrationControlRunReceiptGuard.verifyNativeStateUnchanged(receipt,
                List.of(approvalContract()),
                ControlFixtureProvisioning.dataSource(postgres.getJdbcUrl(), MIGRATION, migrationPassword),
                MIGRATION,
                Set.of(RETENTION_OWNER));
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            if (postgres != null) {
                postgres.stop();
                postgres = null;
            }
        } finally {
            // A failed stop retains the exclusively owned handle for a cleanup retry.
            receiptJson = null;
            receipt = null;
        }
    }

    private List<String> secrets() {
        return List.of(bootstrapPassword, migrationPassword, runtimePassword);
    }

    private static Contract approvalContract() {
        return new Contract(
                "approval-main",
                "public",
                "flyway_schema_history",
                List.of("public", RETENTION_SCHEMA, SIGNATURE_SCHEMA));
    }

    private static String password() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Verified fixture configuration is missing: " + name);
        }
        return value;
    }

    private static Path configuredRoot() {
        Path root = Path.of(requiredProperty("dwp.test.migration-control.backend-root"));
        if (!root.isAbsolute() || Files.isSymbolicLink(root)
                || !Files.isRegularFile(root.resolve("scripts/devctl.py"))) {
            throw new IllegalStateException("Verified fixture backend root is not canonical");
        }
        return root;
    }
}
