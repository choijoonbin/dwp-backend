package com.dwp.services.people.hris.migrationstream;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed configuration for the existing People-owned migration stream. */
@ConfigurationProperties("spring.flyway")
public record PeopleMigrationStreamProperties(
        boolean enabled,
        List<String> locations,
        String defaultSchema,
        List<String> schemas,
        String table,
        boolean baselineOnMigrate,
        String baselineVersion,
        boolean validateOnMigrate,
        boolean outOfOrder,
        boolean failOnMissingLocations,
        boolean cleanDisabled,
        boolean createSchemas,
        Charset encoding,
        String target,
        String url,
        String user,
        String password,
        String driverClassName,
        List<String> initSqls,
        Map<String, String> placeholders,
        boolean placeholderReplacement,
        String placeholderPrefix,
        String placeholderSuffix,
        String placeholderSeparator,
        String sqlMigrationPrefix,
        List<String> sqlMigrationSuffixes,
        String sqlMigrationSeparator,
        String repeatableSqlMigrationPrefix,
        boolean cleanOnValidationError,
        boolean group,
        boolean mixed,
        boolean skipDefaultCallbacks,
        boolean skipDefaultResolvers,
        boolean validateMigrationNaming,
        boolean executeInTransaction,
        Boolean skipExecutingMigrations,
        List<String> ignoreMigrationPatterns) {

    public static final List<String> LOCATIONS = List.of("classpath:db/migration");
    public static final List<String> LOCAL_SEED_LOCATIONS = List.of(
            "classpath:db/migration", "classpath:db/local-seed");
    public static final List<String> LOCAL_SEED_EFFECTIVE_LOCATIONS = List.of(
            "classpath:db/local-seed", "classpath:db/migration");
    public static final String SCHEMA = "public";
    public static final String HISTORY_TABLE = "flyway_schema_history";
    public static final String BASELINE_VERSION = "0";
    public static final String TARGET = "latest";

    public PeopleMigrationStreamProperties {
        locations = locations == null ? null : List.copyOf(locations);
        schemas = schemas == null ? null : List.copyOf(schemas);
        encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
        target = target == null ? TARGET : target;
        initSqls = initSqls == null ? List.of() : List.copyOf(initSqls);
        placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
        placeholderPrefix = placeholderPrefix == null ? "${" : placeholderPrefix;
        placeholderSuffix = placeholderSuffix == null ? "}" : placeholderSuffix;
        placeholderSeparator = placeholderSeparator == null ? ":" : placeholderSeparator;
        sqlMigrationPrefix = sqlMigrationPrefix == null ? "V" : sqlMigrationPrefix;
        sqlMigrationSuffixes = sqlMigrationSuffixes == null
                ? List.of(".sql") : List.copyOf(sqlMigrationSuffixes);
        sqlMigrationSeparator = sqlMigrationSeparator == null ? "__" : sqlMigrationSeparator;
        repeatableSqlMigrationPrefix = repeatableSqlMigrationPrefix == null
                ? "R" : repeatableSqlMigrationPrefix;
        skipExecutingMigrations = skipExecutingMigrations == null
                ? Boolean.FALSE : skipExecutingMigrations;
        ignoreMigrationPatterns = ignoreMigrationPatterns == null
                ? List.of() : List.copyOf(ignoreMigrationPatterns);
        if (!enabled) {
            throw new IllegalArgumentException("spring.flyway.enabled must remain true");
        }
        if (!LOCATIONS.equals(locations) && !LOCAL_SEED_LOCATIONS.equals(locations)) {
            throw new IllegalArgumentException(
                    "spring.flyway.locations must be an approved ordered People profile");
        }
        requireExact("defaultSchema", defaultSchema, SCHEMA);
        requireExact("schemas", schemas, List.of(SCHEMA));
        requireExact("table", table, HISTORY_TABLE);
        if (!baselineOnMigrate) {
            throw new IllegalArgumentException("spring.flyway.baselineOnMigrate must remain enabled");
        }
        requireExact("baselineVersion", baselineVersion, BASELINE_VERSION);
        if (!validateOnMigrate) {
            throw new IllegalArgumentException("spring.flyway.validateOnMigrate must remain enabled");
        }
        if (outOfOrder) {
            throw new IllegalArgumentException("spring.flyway.outOfOrder must remain disabled");
        }
        if (!failOnMissingLocations) {
            throw new IllegalArgumentException(
                    "spring.flyway.failOnMissingLocations must remain enabled");
        }
        if (!cleanDisabled) {
            throw new IllegalArgumentException("spring.flyway.cleanDisabled must remain enabled");
        }
        if (createSchemas) {
            throw new IllegalArgumentException("spring.flyway.createSchemas must remain disabled");
        }
        requireExact("encoding", encoding, StandardCharsets.UTF_8);
        requireExact("target", target, TARGET);
        requireJdbcUrl(url);
        requireCredential("user", user);
        requireCredential("password", password);
        requireUnset("driverClassName", driverClassName);
        requireExact("initSqls", initSqls, List.of());
        requireExact("placeholders", placeholders, Map.of());
        if (!placeholderReplacement) {
            throw new IllegalArgumentException(
                    "spring.flyway.placeholderReplacement must remain enabled");
        }
        requireExact("placeholderPrefix", placeholderPrefix, "${");
        requireExact("placeholderSuffix", placeholderSuffix, "}");
        requireExact("placeholderSeparator", placeholderSeparator, ":");
        requireExact("sqlMigrationPrefix", sqlMigrationPrefix, "V");
        requireExact("sqlMigrationSuffixes", sqlMigrationSuffixes, List.of(".sql"));
        requireExact("sqlMigrationSeparator", sqlMigrationSeparator, "__");
        requireExact("repeatableSqlMigrationPrefix", repeatableSqlMigrationPrefix, "R");
        if (cleanOnValidationError) {
            throw new IllegalArgumentException(
                    "spring.flyway.cleanOnValidationError must remain disabled");
        }
        if (group || mixed || skipDefaultCallbacks || skipDefaultResolvers) {
            throw new IllegalArgumentException(
                    "spring.flyway group/mixed/skip-default settings must remain disabled");
        }
        if (!validateMigrationNaming) {
            throw new IllegalArgumentException(
                    "spring.flyway.validateMigrationNaming must remain enabled");
        }
        if (!executeInTransaction) {
            throw new IllegalArgumentException(
                    "spring.flyway.executeInTransaction must remain enabled");
        }
        requireExact("skipExecutingMigrations", skipExecutingMigrations, Boolean.FALSE);
        requireExact("ignoreMigrationPatterns", ignoreMigrationPatterns, List.of());
    }

    private static void requireExact(String name, Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("spring.flyway." + name + " must be " + expected);
        }
    }

    private static void requireUnset(String name, String actual) {
        if (actual != null) {
            throw new IllegalArgumentException(
                    "spring.flyway." + name + " must remain unset");
        }
    }

    private static void requireJdbcUrl(String actual) {
        if (actual == null || actual.isBlank() || !actual.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException(
                    "spring.flyway.url must be a nonblank PostgreSQL JDBC URL");
        }
    }

    private static void requireCredential(String name, String actual) {
        if (actual == null || actual.isBlank()) {
            throw new IllegalArgumentException(
                    "spring.flyway." + name + " must be explicitly configured");
        }
    }
}
