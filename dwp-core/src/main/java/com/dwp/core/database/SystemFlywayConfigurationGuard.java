package com.dwp.core.database;

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
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;

/** Exact, fail-closed Flyway profile shared by SYS-owned Auth and Platform stores. */
public final class SystemFlywayConfigurationGuard {

    public static final String LOCATION = "classpath:db/migration";
    public static final String SCHEMA = "public";
    public static final String HISTORY_TABLE = "flyway_schema_history";
    public static final String BASELINE_VERSION = "0";

    private static final String UNSET = "<unset>";
    private static final Object REQUIRED_JDBC_URL = new Object();
    private static final Object REQUIRED_CREDENTIAL = new Object();
    private static final Set<String> SECRET_FIELDS = Set.of("password");
    private static final Profile SYSTEM_PROFILE = new Profile(
            true, 0, 50, Map.of(), List.of(LOCATION), List.of(LOCATION));
    private static final Set<String> CLASSIFIED_FIELDS = Collections.unmodifiableSet(
            expectedValues(SYSTEM_PROFILE).keySet());

    private final String migrationUser;
    private final Profile profile;

    private SystemFlywayConfigurationGuard(String migrationUser, Profile profile) {
        this.migrationUser = migrationUser;
        this.profile = profile;
    }

    public static SystemFlywayConfigurationGuard verify(FlywayProperties properties) {
        return verify(properties, SYSTEM_PROFILE);
    }

    public static SystemFlywayConfigurationGuard verify(
            FlywayProperties properties,
            Profile profile) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Map<String, Object> actual = propertyValues(properties);
        requireExactInventory(actual.keySet(), bootPropertyFields());
        requireExactNestedInventory(classifiedNestedPropertyFields(), bootNestedPropertyFields());
        expectedValues(profile).forEach(
                (name, expected) -> requireProperty(name, actual.get(name), expected));
        return new SystemFlywayConfigurationGuard(properties.getUser(), profile);
    }

    public static Profile freshModuleProfile(String placeholderName, String runtimeRole) {
        requireSafeIdentifier("placeholderName", placeholderName);
        requireSafeIdentifier("runtimeRole", runtimeRole);
        return freshModuleProfile(Map.of(placeholderName, runtimeRole));
    }

    /** Exact fresh-module profile with more than one pre-provisioned database principal. */
    public static Profile freshModuleProfile(Map<String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders must not be null").forEach(
                (name, value) -> {
                    requireSafeIdentifier("placeholderName", name);
                    requireSafeIdentifier("placeholderValue", value);
                });
        return new Profile(
                false,
                3,
                10,
                placeholders,
                List.of(LOCATION),
                List.of(LOCATION));
    }

    /** Exact existing-module profile, including the approved local fixture stream. */
    public static Profile existingModuleProfile(
            boolean localSeedEnabled,
            Map<String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders must not be null").forEach(
                (name, value) -> {
                    requireSafeIdentifier("placeholderName", name);
                    requireSafeIdentifier("placeholderValue", value);
                });
        List<String> configuredLocations = localSeedEnabled
                ? List.of(LOCATION, "classpath:db/local-seed")
                : List.of(LOCATION);
        // Flyway returns filesystem/classpath locations in its canonical comparator order.
        List<String> effectiveLocations = localSeedEnabled
                ? List.of("classpath:db/local-seed", LOCATION)
                : List.of(LOCATION);
        return new Profile(
                true,
                0,
                50,
                placeholders,
                configuredLocations,
                effectiveLocations);
    }

    public String migrationUser() {
        return migrationUser;
    }

    public void verifyEffectiveConfiguration(Flyway flyway) {
        Configuration actual = Objects.requireNonNull(
                flyway, "flyway must not be null").getConfiguration();
        requireEffective("url", actual.getUrl(), null);
        requireEffective("user", actual.getUser(), null);
        requireEffective("password", actual.getPassword(), null);
        requireEffective("driver", actual.getDriver(), null);
        requireEffective("locations",
                Arrays.stream(actual.getLocations()).map(Object::toString).toList(),
                profile.effectiveLocations());
        requireEffective("defaultSchema", actual.getDefaultSchema(), SCHEMA);
        requireEffective("schemas", Arrays.asList(actual.getSchemas()), List.of(SCHEMA));
        requireEffective("createSchemas", actual.isCreateSchemas(), false);
        requireEffective("table", actual.getTable(), HISTORY_TABLE);
        requireEffective("baselineOnMigrate",
                actual.isBaselineOnMigrate(), profile.baselineOnMigrate());
        requireEffective("baselineVersion", actual.getBaselineVersion(),
                MigrationVersion.fromVersion(BASELINE_VERSION));
        requireEffective("validateOnMigrate", actual.isValidateOnMigrate(), true);
        requireEffective("outOfOrder", actual.isOutOfOrder(), false);
        requireEffective("failOnMissingLocations", actual.isFailOnMissingLocations(), true);
        requireEffective("cleanDisabled", actual.isCleanDisabled(), true);
        requireEffective("encoding", actual.getEncoding(), StandardCharsets.UTF_8);
        requireEffective("target", actual.getTarget(), MigrationVersion.LATEST);
        requireEffective("connectRetries", actual.getConnectRetries(), profile.connectRetries());
        requireEffective("connectRetriesInterval", actual.getConnectRetriesInterval(), 120);
        requireEffective("lockRetryCount", actual.getLockRetryCount(), profile.lockRetryCount());
        requireEffective("tablespace", actual.getTablespace(), null);
        requireEffective("baselineDescription", actual.getBaselineDescription(),
                "<< Flyway Baseline >>");
        requireEffective("installedBy", actual.getInstalledBy(), null);
        requireEffective("initSql", actual.getInitSql(), null);
        requireEffective("placeholderReplacement", actual.isPlaceholderReplacement(), true);
        requireEffective("placeholders", actual.getPlaceholders(), profile.placeholders());
        requireEffective("placeholderPrefix", actual.getPlaceholderPrefix(), "${");
        requireEffective("placeholderSuffix", actual.getPlaceholderSuffix(), "}");
        requireEffective("placeholderSeparator", actual.getPlaceholderSeparator(), ":");
        requireEffective("sqlMigrationPrefix", actual.getSqlMigrationPrefix(), "V");
        requireEffective("sqlMigrationSuffixes",
                Arrays.asList(actual.getSqlMigrationSuffixes()), List.of(".sql"));
        requireEffective("sqlMigrationSeparator", actual.getSqlMigrationSeparator(), "__");
        requireEffective("repeatableSqlMigrationPrefix",
                actual.getRepeatableSqlMigrationPrefix(), "R");
        requireEffective("scriptPlaceholderPrefix",
                actual.getScriptPlaceholderPrefix(), "FP__");
        requireEffective("scriptPlaceholderSuffix",
                actual.getScriptPlaceholderSuffix(), "__");
        requireEffective("executeInTransaction", actual.isExecuteInTransaction(), true);
        requireEffective("skipExecutingMigrations", actual.isSkipExecutingMigrations(), false);
        requireEffective("ignoreMigrationPatterns",
                Arrays.stream(actual.getIgnoreMigrationPatterns()).map(Object::toString).toList(),
                List.of());
        requireEffective("validateMigrationNaming", actual.isValidateMigrationNaming(), true);
        requireEffective("group", actual.isGroup(), false);
        requireEffective("mixed", actual.isMixed(), false);
        requireEffective("skipDefaultCallbacks", actual.isSkipDefaultCallbacks(), false);
        requireEffective("skipDefaultResolvers", actual.isSkipDefaultResolvers(), false);
        requireEffective("callbacks", actual.getCallbacks().length, 0);
        requireEffective("resolvers", actual.getResolvers().length, 0);
        requireEffective("javaMigrations", actual.getJavaMigrations().length, 0);
        requireEffective("loggers", Arrays.asList(actual.getLoggers()), List.of("slf4j"));
        requireEffective("errorOverrides", Arrays.asList(actual.getErrorOverrides()), List.of());
        requireEffective("jdbcProperties", actual.getJdbcProperties(), Map.of());
        requireEffective("batch", actual.isBatch(), false);
        requireEffective("stream", actual.isStream(), false);
        requireEffective("outputQueryResults", actual.isOutputQueryResults(), false);
        requireEffective("detectEncoding", actual.isDetectEncoding(), false);
        requireEffective("communityDbSupportEnabled",
                actual.isCommunityDBSupportEnabled(), false);
        requireEffective("dryRunOutput", actual.getDryRunOutput(), null);
        requireEffective("kerberosConfigFile", actual.getKerberosConfigFile(), "");
        PostgreSQLConfigurationExtension postgresql = actual.getConfigurationExtension(
                PostgreSQLConfigurationExtension.class);
        requireEffective("postgresql.transactionalLock",
                postgresql.isTransactionalLock(), true);
        requireEffective("postgresql.transactional", postgresql.getTransactional(), null);
    }

    public static Set<String> bootPropertyFields() {
        return Arrays.stream(FlywayProperties.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> classifiedPropertyFields() {
        return CLASSIFIED_FIELDS;
    }

    public static Map<String, Set<String>> bootNestedPropertyFields() {
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        fields.put("oracle", instanceFields(FlywayProperties.Oracle.class));
        fields.put("postgresql", instanceFields(FlywayProperties.Postgresql.class));
        fields.put("sqlserver", instanceFields(FlywayProperties.Sqlserver.class));
        return Collections.unmodifiableMap(fields);
    }

    public static Map<String, Set<String>> classifiedNestedPropertyFields() {
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

    private static Map<String, Object> expectedValues(Profile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", true);
        values.put("failOnMissingLocations", true);
        values.put("locations", profile.configuredLocations());
        values.put("encoding", StandardCharsets.UTF_8);
        values.put("connectRetries", profile.connectRetries());
        values.put("connectRetriesInterval", Duration.ofSeconds(120));
        values.put("lockRetryCount", profile.lockRetryCount());
        values.put("defaultSchema", SCHEMA);
        values.put("schemas", List.of(SCHEMA));
        values.put("createSchemas", false);
        values.put("table", HISTORY_TABLE);
        values.put("tablespace", null);
        values.put("baselineDescription", "<< Flyway Baseline >>");
        values.put("baselineVersion", BASELINE_VERSION);
        values.put("installedBy", null);
        values.put("placeholders", profile.placeholders());
        values.put("placeholderPrefix", "${");
        values.put("placeholderSuffix", "}");
        values.put("placeholderSeparator", ":");
        values.put("placeholderReplacement", true);
        values.put("sqlMigrationPrefix", "V");
        values.put("sqlMigrationSuffixes", List.of(".sql"));
        values.put("sqlMigrationSeparator", "__");
        values.put("repeatableSqlMigrationPrefix", "R");
        values.put("target", "latest");
        values.put("user", REQUIRED_CREDENTIAL);
        values.put("password", REQUIRED_CREDENTIAL);
        values.put("driverClassName", null);
        values.put("url", REQUIRED_JDBC_URL);
        values.put("initSqls", List.of());
        values.put("baselineOnMigrate", profile.baselineOnMigrate());
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

    private static Set<String> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toUnmodifiableSet());
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

    private static void requireProperty(String name, Object actual, Object expected) {
        if (expected == REQUIRED_JDBC_URL) {
            if (!(actual instanceof String value)
                    || value.isBlank()
                    || !value.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "spring.flyway." + name + " must be a nonblank PostgreSQL JDBC URL");
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
                    "Effective SYS Flyway " + name + " drift: expected "
                            + expected + ", got " + actual);
        }
    }

    private static void requireSafeIdentifier(String name, String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a safe SQL identifier");
        }
    }

    public record Profile(
            boolean baselineOnMigrate,
            int connectRetries,
            int lockRetryCount,
            Map<String, String> placeholders,
            List<String> configuredLocations,
            List<String> effectiveLocations) {

        public Profile {
            if (connectRetries < 0 || lockRetryCount < 0) {
                throw new IllegalArgumentException("Flyway retry counts must not be negative");
            }
            placeholders = Map.copyOf(Objects.requireNonNull(
                    placeholders, "placeholders must not be null"));
            configuredLocations = List.copyOf(Objects.requireNonNull(
                    configuredLocations, "configuredLocations must not be null"));
            effectiveLocations = List.copyOf(Objects.requireNonNull(
                    effectiveLocations, "effectiveLocations must not be null"));
            if (configuredLocations.isEmpty() || effectiveLocations.isEmpty()) {
                throw new IllegalArgumentException("Flyway locations must not be empty");
            }
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
