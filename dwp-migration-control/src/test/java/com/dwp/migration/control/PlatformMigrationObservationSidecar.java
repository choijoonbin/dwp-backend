package com.dwp.migration.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;

/** Writes the independently captured PostgreSQL migration proof observation. */
final class PlatformMigrationObservationSidecar {
    static final String ENVIRONMENT_VARIABLE =
            "DWP_PRE_G3_PLATFORM_OBSERVATION_FILE";
    static final Set<String> EXPECTED_SCENARIOS = Set.of(
            "bridge-failure-retry",
            "bridge-partial-state-retry",
            "control-254",
            "control-260",
            "control-fresh",
            "fresh-ordered",
            "historyless-nonempty-bridge-schema",
            "historyless-nonempty-relation",
            "historyless-nonempty-routine",
            "historyless-nonempty-schema",
            "historyless-nonempty-sequence",
            "historyless-nonempty-trigger",
            "historyless-nonempty-type",
            "immutable-predecessors",
            "source-digest-drift",
            "unexpected-ignored",
            "upgrade-191",
            "upgrade-254",
            "upgrade-255",
            "upgrade-260",
            "v261-failure-retry");

    private static final ConcurrentSkipListSet<String> VERIFIED_SCENARIOS =
            new ConcurrentSkipListSet<>();
    private static final AtomicReference<Boundary> BOUNDARY = new AtomicReference<>();
    private static final AtomicReference<Boolean> DATABASE_CREATE_AT_ENTRY =
            new AtomicReference<>();
    private static final AtomicReference<Boolean> DATABASE_CREATE_AT_EXIT =
            new AtomicReference<>();

    private PlatformMigrationObservationSidecar() {
    }

    static void recordScenario(String scenario) {
        if (!EXPECTED_SCENARIOS.contains(scenario)) {
            throw new IllegalStateException(
                    "Unexpected Platform migration evidence scenario: " + scenario);
        }
        if (!VERIFIED_SCENARIOS.add(scenario)) {
            throw new IllegalStateException(
                    "Platform migration evidence scenario was recorded twice: " + scenario);
        }
    }

    static void recordBoundary(Boundary actual) {
        Boundary previous = BOUNDARY.get();
        if (previous == null) {
            if (!BOUNDARY.compareAndSet(null, actual)) {
                previous = BOUNDARY.get();
            } else {
                return;
            }
        }
        if (!actual.equals(previous)) {
            throw new IllegalStateException(
                    "Platform migration boundary observations diverged");
        }
    }

    static void recordDatabaseCreateAtEntry(boolean actual) {
        recordBoolean(DATABASE_CREATE_AT_ENTRY, actual, "entry");
    }

    static void recordDatabaseCreateAtExit(boolean actual) {
        recordBoolean(DATABASE_CREATE_AT_EXIT, actual, "exit");
    }

    private static void recordBoolean(
            AtomicReference<Boolean> observation, boolean actual, String boundary) {
        if (actual) {
            throw new IllegalStateException(
                    "Migration principal retained database CREATE at " + boundary);
        }
        Boolean previous = observation.get();
        if (previous == null) {
            observation.compareAndSet(null, false);
        } else if (previous) {
            throw new IllegalStateException(
                    "Database CREATE observation diverged at " + boundary);
        }
    }

    static void writeIfRequested(String postgresImage) throws IOException {
        String configured = System.getenv(ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return;
        }
        if (!VERIFIED_SCENARIOS.equals(new ConcurrentSkipListSet<>(EXPECTED_SCENARIOS))) {
            throw new IllegalStateException(
                    "Platform migration evidence scenarios are incomplete: "
                            + VERIFIED_SCENARIOS);
        }
        Boundary boundary = BOUNDARY.get();
        if (boundary == null
                || !Boolean.FALSE.equals(DATABASE_CREATE_AT_ENTRY.get())
                || !Boolean.FALSE.equals(DATABASE_CREATE_AT_EXIT.get())) {
            throw new IllegalStateException(
                    "Platform migration evidence observations are incomplete");
        }

        Path output = Path.of(configured);
        Path parent = output.getParent();
        if (!output.isAbsolute() || parent == null
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Platform migration observation path is not a new regular file");
        }
        try (var entries = Files.list(parent)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalStateException(
                        "Platform migration observation directory is not empty");
            }
        }

        String scenarios = VERIFIED_SCENARIOS.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        String json = "{"
                + "\"schema\":\"dwp.pre-g3.platform-observation/v1\","
                + "\"postgresImage\":\"" + postgresImage + "\","
                + "\"triggerFunctionCount\":" + boundary.triggerFunctionCount() + ","
                + "\"triggerMappingCount\":" + boundary.triggerMappingCount() + ","
                + "\"triggerMappingSha256\":\""
                + boundary.triggerMappingSha256() + "\","
                + "\"bridgedMappingCount\":" + boundary.bridgedMappingCount() + ","
                + "\"bridgedMappingSha256\":\""
                + boundary.bridgedMappingSha256() + "\","
                + "\"finalBridgeSchemaCount\":" + boundary.finalBridgeSchemaCount() + ","
                + "\"migrationPrincipalDatabaseCreateAtEntry\":false,"
                + "\"migrationPrincipalDatabaseCreateAtExit\":false,"
                + "\"verifiedScenarioIds\":[" + scenarios + "]}\n";
        Files.writeString(
                output,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    record Boundary(
            int triggerFunctionCount,
            int triggerMappingCount,
            String triggerMappingSha256,
            int bridgedMappingCount,
            String bridgedMappingSha256,
            int finalBridgeSchemaCount) {
    }
}
