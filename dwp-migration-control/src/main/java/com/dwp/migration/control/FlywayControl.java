package com.dwp.migration.control;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.ValidateResult;

final class FlywayControl {
    private static final String REQUIRED_VERSION = "12.11.0";

    private FlywayControl() {
    }

    static void requireVersion() throws Exception {
        String flywayVersion;
        try (var version = Flyway.class.getClassLoader().getResourceAsStream(
                "org/flywaydb/core/internal/version.txt")) {
            if (version == null) {
                throw new IllegalStateException("Flyway version resource is missing");
            }
            flywayVersion = new String(version.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!REQUIRED_VERSION.equals(flywayVersion)) {
            throw new IllegalStateException(
                    "Migration Control requires Flyway " + REQUIRED_VERSION);
        }
    }

    static Flyway load(
            ControlEnvironment environment,
            StreamPlan stream,
            MigrationVersion target) {
        return configured(
                environment,
                stream,
                environment.migrationPrincipal(),
                environment.migrationPassword(),
                target,
                false);
    }

    static Flyway loadPrivilegedNotification(
            ControlEnvironment environment,
            StreamPlan stream,
            NotificationPrivilegedMigration migration) {
        migration.requireAttestedSource(environment);
        return configured(
                environment,
                stream,
                environment.migrationPrincipal(),
                environment.migrationPassword(),
                MigrationVersion.fromVersion(migration.version()),
                true);
    }

    static Flyway loadDatabaseCreate(
            ControlEnvironment environment,
            StreamPlan stream,
            DatabaseCreateMigration migration) {
        DatabaseCreateMigrationSourceControl.requireExact(environment, migration);
        return configured(
                environment,
                stream,
                environment.migrationPrincipal(),
                environment.migrationPassword(),
                MigrationVersion.fromVersion(migration.version()),
                true);
    }

    private static Flyway configured(
            ControlEnvironment environment,
            StreamPlan stream,
            String username,
            String password,
            MigrationVersion target,
            boolean privilegedNotification) {
        Map<String, String> placeholderValues = new LinkedHashMap<>();
        if (environment.plan().runtimePlaceholder() != null) {
            placeholderValues.put(
                    environment.plan().runtimePlaceholder(),
                    environment.runtimePrincipal());
        }
        if (!environment.plan().projectionPublisherPlaceholder().isEmpty()) {
            placeholderValues.put(
                    environment.plan().projectionPublisherPlaceholder(),
                    environment.projectionPublisherPrincipal());
        }
        Map<String, String> placeholders = Map.copyOf(placeholderValues);
        FluentConfiguration configuration = Flyway.configure(
                        FlywayControl.class.getClassLoader())
                .dataSource(environment.jdbcUrl(), username, password)
                .locations(stream.location())
                .defaultSchema(stream.schema())
                .schemas(stream.schema())
                .table(stream.historyTable())
                // Control never turns a non-empty unmanaged schema into an
                // implicit V0 baseline. Fresh flows preflight exact emptiness;
                // adoption requires an existing Flyway history table.
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .baselineDescription("<< Flyway Baseline >>")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .failOnMissingLocations(true)
                .encoding(StandardCharsets.UTF_8)
                .connectRetries(0)
                .connectRetriesInterval(120)
                .lockRetryCount(50)
                .cleanDisabled(true)
                .createSchemas(stream.createSchemas())
                .validateMigrationNaming(true)
                .sqlMigrationPrefix("V")
                .sqlMigrationSuffixes(".sql")
                .sqlMigrationSeparator("__")
                .repeatableSqlMigrationPrefix("R")
                .placeholders(placeholders)
                .placeholderReplacement(true)
                .placeholderPrefix("${")
                .placeholderSuffix("}")
                .placeholderSeparator(":")
                .scriptPlaceholderPrefix("FP__")
                .scriptPlaceholderSuffix("__")
                .executeInTransaction(true)
                .skipExecutingMigrations(false)
                .ignoreMigrationPatterns(new String[0])
                .loggers("slf4j")
                .jdbcProperties(Map.of())
                .batch(false)
                .stream(false)
                .outputQueryResults(false)
                .detectEncoding(false)
                .communityDBSupportEnabled(false)
                .group(false)
                .mixed(false)
                .callbacks(new org.flywaydb.core.api.callback.Callback[0])
                .skipDefaultCallbacks(privilegedNotification)
                .skipDefaultResolvers(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    static void migrateAndValidate(Flyway flyway) {
        migrateAndValidate(flyway, true);
    }

    static void migrateAndValidate(Flyway flyway, boolean requireNoPending) {
        PlatformInventoryBridgeControl.applyIfRequired(flyway);
        flyway.migrate();
        ValidateResult validation = flyway.validateWithResult();
        if (!validation.validationSuccessful) {
            throw new IllegalStateException(
                    "Flyway validation failed: " + validation.errorDetails);
        }
        if (requireNoPending && flyway.info().pending().length != 0) {
            throw new IllegalStateException("Migration Control left pending migrations");
        }
    }

    static MigrationVersion predecessorVersion(
            ControlEnvironment environment,
            StreamPlan stream,
            String targetVersion) {
        MigrationVersion target = MigrationVersion.fromVersion(targetVersion);
        Flyway flyway = load(
                environment,
                stream,
                null);
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null && version.compareTo(target) < 0)
                .max(MigrationVersion::compareTo)
                .orElse(MigrationVersion.fromVersion("0"));
    }
}
