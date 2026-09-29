package com.dwp.services.people.hris.migrationstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed configuration for the isolated Performance-owned migration stream. */
@ConfigurationProperties("dwp.people.hris.performance-migration")
public record PerformanceMigrationStreamProperties(
        String location,
        String schema,
        String historyTable,
        String baselineVersion,
        boolean baselineOnMigrate,
        boolean validateOnMigrate,
        boolean outOfOrder) {

    public static final String LOCATION = "classpath:db/performance-migration";
    public static final String SCHEMA = "hris_performance";
    public static final String HISTORY_TABLE = "flyway_performance_schema_history";
    public static final String BASELINE_VERSION = "0";

    public PerformanceMigrationStreamProperties {
        requireExact("location", location, LOCATION);
        requireExact("schema", schema, SCHEMA);
        requireExact("historyTable", historyTable, HISTORY_TABLE);
        requireExact("baselineVersion", baselineVersion, BASELINE_VERSION);
        if (baselineOnMigrate) {
            throw new IllegalArgumentException("baselineOnMigrate must remain disabled");
        }
        if (!validateOnMigrate) {
            throw new IllegalArgumentException("validateOnMigrate must remain enabled");
        }
        if (outOfOrder) {
            throw new IllegalArgumentException("outOfOrder must remain disabled");
        }
    }

    private static void requireExact(String name, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + " must be " + expected);
        }
    }
}
