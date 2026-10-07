package com.dwp.services.people.hris.migrationstream;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;

/**
 * Closes every Spring Boot Flyway property before either HRIS migration stream can run.
 *
 * <p>The explicit property inventory is intentional. A Spring Boot upgrade that adds a
 * Flyway property must fail the accompanying drift test until its migration impact has
 * been classified and pinned here.</p>
 */
public final class PeopleFlywayPropertiesGuard {

    private static final String UNSET = "<unset>";
    private static final Object REQUIRED_JDBC_URL = new Object();
    private static final Object REQUIRED_CREDENTIAL = new Object();
    private static final Set<String> SECRET_FIELDS = Set.of("password");
    private static final Map<String, Object> EXPECTED = expectedValues();

    private PeopleFlywayPropertiesGuard() {
    }

    static PeopleFlywayPropertiesGuard verify(FlywayProperties properties) {
        return verify(properties, false);
    }

    static PeopleFlywayPropertiesGuard verify(
            FlywayProperties properties,
            boolean localSeedEnabled) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, Object> actual = propertyValues(properties);
        requireExactInventory(actual.keySet(), bootPropertyFields());
        requireExactNestedInventory(classifiedNestedPropertyFields(), bootNestedPropertyFields());
        EXPECTED.forEach((name, expected) -> requireProperty(
                name,
                actual.get(name),
                "locations".equals(name)
                        ? expectedLocations(localSeedEnabled)
                        : expected));
        return new PeopleFlywayPropertiesGuard();
    }

    private static List<String> expectedLocations(boolean localSeedEnabled) {
        return localSeedEnabled
                ? PeopleMigrationStreamProperties.LOCAL_SEED_LOCATIONS
                : PeopleMigrationStreamProperties.LOCATIONS;
    }

    void verifyEffectiveConfiguration(Flyway flyway) {
        Configuration actual = Objects.requireNonNull(
                flyway, "flyway must not be null").getConfiguration();
        // Spring Boot materializes configured credentials into a dedicated DataSource;
        // Flyway's effective scalar fields must therefore remain unset. Live endpoint
        // and principal identity are verified against that DataSource before migration.
        requireEffective("url", actual.getUrl(), null);
        requireEffective("user", actual.getUser(), null);
        requireEffective("password", actual.getPassword(), null);
        requireEffective("driver", actual.getDriver(), null);
        requireEffective("connectRetries", actual.getConnectRetries(), 0);
        requireEffective("connectRetriesInterval", actual.getConnectRetriesInterval(), 120);
        requireEffective("lockRetryCount", actual.getLockRetryCount(), 50);
        requireEffective("tablespace", actual.getTablespace(), null);
        requireEffective("baselineDescription", actual.getBaselineDescription(),
                "<< Flyway Baseline >>");
        requireEffective("installedBy", actual.getInstalledBy(), null);
        requireEffective("initSql", actual.getInitSql(), null);
        requireEffective("placeholders", actual.getPlaceholders(), Map.of());
        requireEffective("scriptPlaceholderPrefix", actual.getScriptPlaceholderPrefix(), "FP__");
        requireEffective("scriptPlaceholderSuffix", actual.getScriptPlaceholderSuffix(), "__");
        requireEffective("loggers", Arrays.asList(actual.getLoggers()), List.of("slf4j"));
        requireEffective("batch", actual.isBatch(), false);
        requireEffective("dryRunOutput", actual.getDryRunOutput(), null);
        requireEffective("errorOverrides", Arrays.asList(actual.getErrorOverrides()), List.of());
        requireEffective("stream", actual.isStream(), false);
        requireEffective("jdbcProperties", actual.getJdbcProperties(), Map.of());
        requireEffective("kerberosConfigFile", actual.getKerberosConfigFile(), "");
        requireEffective("outputQueryResults", actual.isOutputQueryResults(), false);
        requireEffective("detectEncoding", actual.isDetectEncoding(), false);
        requireEffective("communityDbSupportEnabled",
                actual.isCommunityDBSupportEnabled(), false);
        PostgreSQLConfigurationExtension postgresql = actual.getConfigurationExtension(
                PostgreSQLConfigurationExtension.class);
        requireEffective("postgresql.transactionalLock",
                postgresql.isTransactionalLock(), true);
        requireEffective("postgresql.transactional", postgresql.getTransactional(), null);
    }

    static Set<String> bootPropertyFields() {
        return Arrays.stream(FlywayProperties.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toUnmodifiableSet());
    }

    static Set<String> classifiedPropertyFields() {
        return Collections.unmodifiableSet(EXPECTED.keySet());
    }

    static Map<String, Set<String>> bootNestedPropertyFields() {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        fields.put("oracle", instanceFields(FlywayProperties.Oracle.class));
        fields.put("postgresql", instanceFields(FlywayProperties.Postgresql.class));
        fields.put("sqlserver", instanceFields(FlywayProperties.Sqlserver.class));
        return Collections.unmodifiableMap(fields);
    }

    static Map<String, Set<String>> classifiedNestedPropertyFields() {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        fields.put("oracle", Set.of(
                "sqlplus", "sqlplusWarn", "kerberosCacheFile", "walletLocation"));
        fields.put("postgresql", Set.of("transactionalLock"));
        fields.put("sqlserver", Set.of("kerberosLoginFile"));
        return Collections.unmodifiableMap(fields);
    }

    @SuppressWarnings("removal")
    private static Map<String, Object> propertyValues(FlywayProperties properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", properties.isEnabled());
        values.put("failOnMissingLocations", properties.isFailOnMissingLocations());
        values.put("locations", immutable(properties.getLocations()));
        values.put("encoding", properties.getEncoding());
        values.put("connectRetries", properties.getConnectRetries());
        values.put("connectRetriesInterval", properties.getConnectRetriesInterval());
        values.put("lockRetryCount", properties.getLockRetryCount());
        values.put("defaultSchema", properties.getDefaultSchema());
        values.put("schemas", immutable(properties.getSchemas()));
        values.put("createSchemas", properties.isCreateSchemas());
        values.put("table", properties.getTable());
        values.put("tablespace", properties.getTablespace());
        values.put("baselineDescription", properties.getBaselineDescription());
        values.put("baselineVersion", properties.getBaselineVersion());
        values.put("installedBy", properties.getInstalledBy());
        values.put("placeholders", immutable(properties.getPlaceholders()));
        values.put("placeholderPrefix", properties.getPlaceholderPrefix());
        values.put("placeholderSuffix", properties.getPlaceholderSuffix());
        values.put("placeholderSeparator", properties.getPlaceholderSeparator());
        values.put("placeholderReplacement", properties.isPlaceholderReplacement());
        values.put("sqlMigrationPrefix", properties.getSqlMigrationPrefix());
        values.put("sqlMigrationSuffixes", immutable(properties.getSqlMigrationSuffixes()));
        values.put("sqlMigrationSeparator", properties.getSqlMigrationSeparator());
        values.put("repeatableSqlMigrationPrefix", properties.getRepeatableSqlMigrationPrefix());
        values.put("target", properties.getTarget());
        values.put("user", properties.getUser());
        values.put("password", properties.getPassword());
        values.put("driverClassName", properties.getDriverClassName());
        values.put("url", properties.getUrl());
        values.put("initSqls", immutable(properties.getInitSqls()));
        values.put("baselineOnMigrate", properties.isBaselineOnMigrate());
        values.put("cleanDisabled", properties.isCleanDisabled());
        values.put("cleanOnValidationError", properties.isCleanOnValidationError());
        values.put("group", properties.isGroup());
        values.put("mixed", properties.isMixed());
        values.put("outOfOrder", properties.isOutOfOrder());
        values.put("skipDefaultCallbacks", properties.isSkipDefaultCallbacks());
        values.put("skipDefaultResolvers", properties.isSkipDefaultResolvers());
        values.put("validateMigrationNaming", properties.isValidateMigrationNaming());
        values.put("validateOnMigrate", properties.isValidateOnMigrate());
        values.put("scriptPlaceholderPrefix", properties.getScriptPlaceholderPrefix());
        values.put("scriptPlaceholderSuffix", properties.getScriptPlaceholderSuffix());
        values.put("executeInTransaction", properties.isExecuteInTransaction());
        values.put("loggers", immutable(properties.getLoggers()));
        values.put("batch", properties.getBatch());
        values.put("dryRunOutput", properties.getDryRunOutput());
        values.put("errorOverrides", nullableList(properties.getErrorOverrides()));
        values.put("stream", properties.getStream());
        values.put("jdbcProperties", immutable(properties.getJdbcProperties()));
        values.put("kerberosConfigFile", properties.getKerberosConfigFile());
        values.put("outputQueryResults", properties.getOutputQueryResults());
        values.put("skipExecutingMigrations", properties.getSkipExecutingMigrations());
        values.put("ignoreMigrationPatterns", immutable(properties.getIgnoreMigrationPatterns()));
        values.put("detectEncoding", properties.getDetectEncoding());
        values.put("communityDbSupportEnabled", properties.getCommunityDbSupportEnabled());
        values.put("oracle", new OracleValues(
                properties.getOracle().getSqlplus(),
                properties.getOracle().getSqlplusWarn(),
                properties.getOracle().getKerberosCacheFile(),
                properties.getOracle().getWalletLocation()));
        values.put("postgresql", new PostgresqlValues(
                properties.getPostgresql().getTransactionalLock()));
        values.put("sqlserver", new SqlserverValues(
                properties.getSqlserver().getKerberosLoginFile()));
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> expectedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", true);
        values.put("failOnMissingLocations", true);
        values.put("locations", PeopleMigrationStreamProperties.LOCATIONS);
        values.put("encoding", StandardCharsets.UTF_8);
        values.put("connectRetries", 0);
        values.put("connectRetriesInterval", Duration.ofSeconds(120));
        values.put("lockRetryCount", 50);
        values.put("defaultSchema", PeopleMigrationStreamProperties.SCHEMA);
        values.put("schemas", List.of(PeopleMigrationStreamProperties.SCHEMA));
        values.put("createSchemas", false);
        values.put("table", PeopleMigrationStreamProperties.HISTORY_TABLE);
        values.put("tablespace", null);
        values.put("baselineDescription", "<< Flyway Baseline >>");
        values.put("baselineVersion", PeopleMigrationStreamProperties.BASELINE_VERSION);
        values.put("installedBy", null);
        values.put("placeholders", Map.of());
        values.put("placeholderPrefix", "${");
        values.put("placeholderSuffix", "}");
        values.put("placeholderSeparator", ":");
        values.put("placeholderReplacement", true);
        values.put("sqlMigrationPrefix", "V");
        values.put("sqlMigrationSuffixes", List.of(".sql"));
        values.put("sqlMigrationSeparator", "__");
        values.put("repeatableSqlMigrationPrefix", "R");
        values.put("target", PeopleMigrationStreamProperties.TARGET);
        values.put("user", REQUIRED_CREDENTIAL);
        values.put("password", REQUIRED_CREDENTIAL);
        values.put("driverClassName", null);
        values.put("url", REQUIRED_JDBC_URL);
        values.put("initSqls", List.of());
        values.put("baselineOnMigrate", true);
        values.put("cleanDisabled", true);
        values.put("cleanOnValidationError", false);
        values.put("group", false);
        values.put("mixed", false);
        values.put("outOfOrder", false);
        values.put("skipDefaultCallbacks", false);
        values.put("skipDefaultResolvers", false);
        values.put("validateMigrationNaming", true);
        values.put("validateOnMigrate", true);
        values.put("scriptPlaceholderPrefix", "FP__");
        values.put("scriptPlaceholderSuffix", "__");
        values.put("executeInTransaction", true);
        values.put("loggers", List.of("slf4j"));
        values.put("batch", null);
        values.put("dryRunOutput", null);
        values.put("errorOverrides", null);
        values.put("stream", null);
        values.put("jdbcProperties", Map.of());
        values.put("kerberosConfigFile", null);
        values.put("outputQueryResults", Boolean.FALSE);
        values.put("skipExecutingMigrations", Boolean.FALSE);
        values.put("ignoreMigrationPatterns", List.of());
        values.put("detectEncoding", null);
        values.put("communityDbSupportEnabled", Boolean.FALSE);
        values.put("oracle", new OracleValues(null, null, null, null));
        values.put("postgresql", new PostgresqlValues(null));
        values.put("sqlserver", new SqlserverValues(null));
        return Collections.unmodifiableMap(values);
    }

    private static List<String> immutable(String[] values) {
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    private static List<String> nullableList(String[] values) {
        return values == null ? null : List.copyOf(Arrays.asList(values));
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static void requireExactInventory(Set<String> actual, Set<String> expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Spring Boot Flyway property classification drift: expected "
                            + expected + ", got " + actual);
        }
    }

    private static void requireExactNestedInventory(
            Map<String, Set<String>> actual,
            Map<String, Set<String>> expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Spring Boot Flyway nested-property classification drift: expected "
                            + expected + ", got " + actual);
        }
    }

    private static Set<String> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireProperty(String name, Object actual, Object expected) {
        if (expected == REQUIRED_JDBC_URL) {
            if (!(actual instanceof String value)
                    || value.isBlank()
                    || !value.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "spring.flyway." + name
                                + " must be a nonblank PostgreSQL JDBC URL");
            }
            return;
        }
        if (expected == REQUIRED_CREDENTIAL) {
            if (!(actual instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException(
                        "spring.flyway." + name + " must be explicitly configured");
            }
            return;
        }
        if (!Objects.equals(actual, expected)) {
            String expectedDescription = expected == null ? UNSET : String.valueOf(expected);
            String actualDescription = actual == null ? UNSET : String.valueOf(actual);
            if (SECRET_FIELDS.contains(name) && actual != null) {
                actualDescription = "<set>";
            }
            throw new IllegalArgumentException(
                    "spring.flyway." + name + " must be " + expectedDescription
                            + ", got " + actualDescription);
        }
    }

    private static void requireEffective(String name, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(
                    "Effective primary Flyway " + name + " drift: expected "
                            + expected + ", got " + actual);
        }
    }

    private record OracleValues(
            Boolean sqlplus,
            Boolean sqlplusWarn,
            String kerberosCacheFile,
            String walletLocation) {
    }

    private record PostgresqlValues(Boolean transactionalLock) {
    }

    private record SqlserverValues(String kerberosLoginFile) {
    }
}
