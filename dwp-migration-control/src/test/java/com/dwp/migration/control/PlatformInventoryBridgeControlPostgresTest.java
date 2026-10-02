package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PlatformInventoryBridgeControlPostgresTest {
    private static final String BRIDGE =
            "V254_1__bridge_platform_trigger_inventory_before_v255.sql";
    private static final Set<String> LEGACY_EXCLUSIONS = Set.of(BRIDGE,
            "V261__close_platform_trigger_and_facility_retention_boundaries.sql");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(postgresImage());

    @AfterAll
    static void writeSealedObservationIfRequested() throws IOException {
        PlatformMigrationObservationSidecar.writeIfRequested(postgresImage());
    }

    @Test
    @DisplayName("fresh-ordered")
    void emptyDatabaseAppliesTheBridgeInVersionOrderThenV261() throws Exception {
        withDatabase("fresh", database -> {
            FlywayControl.migrateAndValidate(current(database, null));

            assertTrue(integer(database,
                    "SELECT installed_rank FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success")
                    < integer(database,
                    "SELECT installed_rank FROM flyway_schema_history "
                            + "WHERE version='255' AND success"));
            assertEquals(1, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='261' AND success"));
            assertFinalBoundary(database);
            PlatformMigrationObservationSidecar.recordScenario("fresh-ordered");
        });
    }

    @ParameterizedTest(name = "upgrade-{0}")
    @ValueSource(strings = {"191", "254", "255", "260"})
    void upgradesEveryReviewedPlatformPredecessorWithoutEditingHistory(
            String predecessor) throws Exception {
        withDatabase("upgrade_" + predecessor, database -> {
            if (Integer.parseInt(predecessor) < 255) {
                try (TemporaryMigrationDirectory versioned = versionedDirectory()) {
                    configured(database, versioned.location(), predecessor, false).migrate();
                }
            } else {
                try (TemporaryMigrationDirectory legacy = legacyDirectory()) {
                    configured(database, legacy.location(), "254", false).migrate();
                    // Models an already deployed immutable V255 predecessor. Its
                    // assertion has no schema effect; every V261 hardening effect
                    // is independently recreated and verified below.
                    configured(database, legacy.location(), "255", true).migrate();
                    if ("260".equals(predecessor)) {
                        configured(database, legacy.location(), "260", false).migrate();
                    }
                }
            }

            FlywayControl.migrateAndValidate(current(database, null));

            int bridgeRank = integer(database,
                    "SELECT installed_rank FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success");
            int v255Rank = integer(database,
                    "SELECT installed_rank FROM flyway_schema_history "
                            + "WHERE version='255' AND success");
            if (Integer.parseInt(predecessor) < 255) {
                assertTrue(bridgeRank < v255Rank);
            } else {
                assertTrue(bridgeRank > v255Rank);
            }
            assertEquals(1, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='261' AND success"));
            assertFinalBoundary(database);
            PlatformMigrationObservationSidecar.recordScenario(
                    "upgrade-" + predecessor);
        });
    }

    @ParameterizedTest(name = "control-{0}")
    @ValueSource(strings = {"fresh", "254", "260"})
    void controlPathUsesANoDatabaseCreateMigrationPrincipal(String predecessor)
            throws Exception {
        withControlledDatabase("control_" + predecessor, controlled -> {
            ControlEnvironment environment = controlled.environment();
            StreamPlan stream = environment.plan().streams().getFirst();
            if ("254".equals(predecessor) || "260".equals(predecessor)) {
                try (Connection bootstrap = controlled.bootstrapConnection()) {
                    PlatformBridgeSchemaControl.provisionRequiredExtensions(
                            bootstrap, environment);
                }
                try (TemporaryMigrationDirectory versioned = versionedDirectory()) {
                    configuredAs(controlled.database(), versioned.location(), "1", false,
                            controlled.migration(), controlled.migrationPassword()).migrate();
                }
                MigrationControlMain.applyTemporaryMigrations(environment, stream);
                try (TemporaryMigrationDirectory legacy = legacyDirectory()) {
                    configuredAs(controlled.database(), legacy.location(), "254", false,
                            controlled.migration(), controlled.migrationPassword()).migrate();
                    if ("260".equals(predecessor)) {
                        configuredAs(controlled.database(), legacy.location(), "255", true,
                                controlled.migration(), controlled.migrationPassword()).migrate();
                        configuredAs(controlled.database(), legacy.location(), "260", false,
                                controlled.migration(), controlled.migrationPassword()).migrate();
                    }
                }
            }
            boolean createAtEntry = databaseCreate(controlled);
            assertFalse(createAtEntry);
            PlatformMigrationObservationSidecar.recordDatabaseCreateAtEntry(
                    createAtEntry);
            assertFalse(databaseTemporary(controlled));
            MigrationControlMain.preparePrimaryMigrations(environment, stream);
            MigrationControlMain.preparePrimaryMigrations(environment, stream);
            assertFalse(databaseCreate(controlled));
            assertFalse(databaseTemporary(controlled));
            if (!"260".equals(predecessor)) {
                assertEquals(1, integer(controlled.database(),
                        "SELECT COUNT(*) FROM pg_namespace "
                                + "WHERE nspname='dwp_platform_v255_inventory_bridge'"));
            } else {
                assertEquals(0, integer(controlled.database(),
                        "SELECT COUNT(*) FROM pg_namespace "
                                + "WHERE nspname='dwp_platform_v255_inventory_bridge'"));
            }

            FlywayControl.migrateAndValidate(
                    FlywayControl.load(environment, stream, null));
            MigrationControlMain.preparePrimaryMigrations(environment, stream);
            FlywayControl.migrateAndValidate(
                    FlywayControl.load(environment, stream, null));
            boolean createAtExit = databaseCreate(controlled);
            assertFalse(createAtExit);
            PlatformMigrationObservationSidecar.recordDatabaseCreateAtExit(
                    createAtExit);
            assertFalse(databaseTemporary(controlled));
            assertEquals(1, integer(controlled.database(),
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success"));
            assertEquals(1, integer(controlled.database(),
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='261' AND success"));
            assertFinalBoundary(controlled.database());
            PlatformMigrationObservationSidecar.recordScenario(
                    "control-" + predecessor);
        });
    }

    @Test
    @DisplayName("bridge-failure-retry")
    void v254BridgeFailureIsCleanedAndCanBeRetriedExactlyOnce() throws Exception {
        withControlledDatabase("bridge_failure", controlled -> {
            ControlEnvironment environment = controlled.environment();
            StreamPlan stream = environment.plan().streams().getFirst();
            PlatformBridgeSchemaControl.prepare(environment, stream);
            MigrationControlMain.applyTemporaryMigrations(environment, stream);
            FlywayControl.migrateAndValidate(FlywayControl.load(
                    environment, stream, MigrationVersion.fromVersion("254")), false);
            execute(controlled.database(),
                    "ALTER TABLE public.wp_experience_facility_closures DISABLE TRIGGER "
                            + "trg_wp_facility_closure_resource_lock");

            assertThrows(RuntimeException.class,
                    () -> PlatformBridgeSchemaControl.applyPending(environment, stream));
            assertEquals(0, integer(controlled.database(),
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success"));
            assertEquals(0, integer(controlled.database(),
                    "SELECT COUNT(*) FROM pg_namespace WHERE nspname="
                            + "'dwp_platform_v255_inventory_bridge'"));

            execute(controlled.database(),
                    "ALTER TABLE public.wp_experience_facility_closures ENABLE TRIGGER "
                            + "trg_wp_facility_closure_resource_lock");
            PlatformBridgeSchemaControl.applyPending(environment, stream);
            FlywayControl.migrateAndValidate(FlywayControl.load(environment, stream, null));
            assertEquals(1, integer(controlled.database(),
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success"));
            assertFinalBoundary(controlled.database());
            PlatformMigrationObservationSidecar.recordScenario(
                    "bridge-failure-retry");
        });
    }

    @Test
    @DisplayName("bridge-partial-state-retry")
    void controlResumesAnExactEmptyBridgeSchemaAfterInterruptedProvisioning()
            throws Exception {
        withControlledDatabase("bridge_partial", controlled -> {
            ControlEnvironment environment = controlled.environment();
            StreamPlan stream = environment.plan().streams().getFirst();
            PlatformBridgeSchemaControl.prepare(environment, stream);
            MigrationControlMain.applyTemporaryMigrations(environment, stream);
            FlywayControl.migrateAndValidate(FlywayControl.load(
                    environment, stream, MigrationVersion.fromVersion("254")), false);
            execute(controlled.database(), "CREATE SCHEMA "
                    + quoteIdentifier("dwp_platform_v255_inventory_bridge")
                    + " AUTHORIZATION " + quoteIdentifier(controlled.migration()));
            execute(controlled.database(), "REVOKE ALL ON SCHEMA "
                    + quoteIdentifier("dwp_platform_v255_inventory_bridge")
                    + " FROM PUBLIC");

            MigrationControlMain.preparePrimaryMigrations(environment, stream);
            MigrationControlMain.preparePrimaryMigrations(environment, stream);
            assertEquals(1, integer(controlled.database(),
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='254.1' AND success"));
            assertEquals(7, integer(controlled.database(),
                    "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n "
                            + "ON n.oid=p.pronamespace WHERE n.nspname="
                            + "'dwp_platform_v255_inventory_bridge'"));

            FlywayControl.migrateAndValidate(FlywayControl.load(environment, stream, null));
            assertFinalBoundary(controlled.database());
            PlatformMigrationObservationSidecar.recordScenario(
                    "bridge-partial-state-retry");
        });
    }

    @Test
    @DisplayName("v261-failure-retry")
    void v261RejectsTriggerMappingDriftWithoutAdvancingHistory() throws Exception {
        withDatabase("mapping_drift", database -> {
            try (TemporaryMigrationDirectory versioned = versionedDirectory()) {
                configured(database, versioned.location(), "260", false).migrate();
            }
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE public.wp_experience_facility_closures "
                        + "DISABLE TRIGGER "
                        + "trg_wp_facility_closure_resource_lock");
            }

            assertThrows(RuntimeException.class,
                    () -> configured(database, platformLocation(), null, false).migrate());

            assertEquals(0, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='261' AND success"));
            assertEquals(1, integer(database,
                    "SELECT COUNT(*) FROM pg_namespace "
                            + "WHERE nspname='dwp_platform_v255_inventory_bridge'"));
            assertEquals(1, integer(database,
                    "SELECT COUNT(*) FROM pg_trigger "
                            + "WHERE tgname='trg_wp_facility_closure_resource_lock' "
                            + "AND tgenabled='D'"));
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE public.wp_experience_facility_closures "
                        + "ENABLE TRIGGER trg_wp_facility_closure_resource_lock");
            }
            configured(database, platformLocation(), null, false).migrate();
            assertEquals(1, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version='261' AND success"));
            assertFinalBoundary(database);
            PlatformMigrationObservationSidecar.recordScenario(
                    "v261-failure-retry");
        });
    }

    @ParameterizedTest(name = "historyless-nonempty-{0}")
    @MethodSource("historylessHostileCases")
    void controlRejectsAHistorylessNonemptyDatabaseBeforeAnyMutation(
            String scenario, String hostileSql)
            throws Exception {
        withControlledDatabase("hostile_" + scenario.replace('-', '_'), controlled -> {
            try (Connection connection = controlled.bootstrapConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(hostileSql);
            }

            StreamPlan stream = controlled.environment().plan().streams().getFirst();
            assertThrows(IllegalStateException.class,
                    () -> MigrationControlMain.preparePrimaryMigrations(
                            controlled.environment(), stream));
            assertFalse(databaseCreate(controlled));
            assertFalse(databaseTemporary(controlled));
            assertEquals(0, integer(controlled.database(),
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema='public' "
                            + "AND table_name='flyway_schema_history'"));
            assertEquals(0, integer(controlled.database(),
                    "SELECT COUNT(*) FROM pg_extension "
                            + "WHERE extname IN ('btree_gist','pgcrypto')"));
            PlatformMigrationObservationSidecar.recordScenario(
                    "historyless-nonempty-" + scenario);
        });
    }

    private static Stream<Arguments> historylessHostileCases() {
        return Stream.of(
                Arguments.of("relation",
                        "CREATE TABLE public.hostile_relation(id integer)"),
                Arguments.of("routine",
                        "CREATE FUNCTION public.hostile_routine() RETURNS trigger "
                                + "LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'"),
                Arguments.of("type", "CREATE TYPE public.hostile_type AS ENUM ('HOSTILE')"),
                Arguments.of("sequence", "CREATE SEQUENCE public.hostile_sequence"),
                Arguments.of("schema", "CREATE SCHEMA hostile_schema"),
                Arguments.of("bridge-schema",
                        "CREATE SCHEMA dwp_platform_v255_inventory_bridge"),
                Arguments.of("trigger",
                        "CREATE TABLE public.hostile_trigger_table(id integer); "
                                + "CREATE FUNCTION public.hostile_trigger_function() "
                                + "RETURNS trigger LANGUAGE plpgsql "
                                + "AS 'BEGIN RETURN NEW; END'; "
                                + "CREATE TRIGGER hostile_trigger BEFORE INSERT ON "
                                + "public.hostile_trigger_table FOR EACH ROW "
                                + "EXECUTE FUNCTION public.hostile_trigger_function()"));
    }

    @Test
    @DisplayName("unexpected-ignored")
    void controlRejectsAnUnexpectedIgnoredMigrationBesideTheExactBridge()
            throws Exception {
        withDatabase("unexpected_ignored", database -> {
            try (TemporaryMigrationDirectory legacy = legacyDirectory()) {
                configured(database, legacy.location(), "254", false).migrate();
                configured(database, legacy.location(), "255", true).migrate();
                configured(database, legacy.location(), "260", false).migrate();
            }
            try (TemporaryMigrationDirectory hostile = copyMigrations(Set.of())) {
                Files.writeString(
                        hostile.path().resolve("V254_2__unexpected_ignored.sql"),
                        "SELECT 1;\n");
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> FlywayControl.migrateAndValidate(configured(
                                database, hostile.location(), null, false)));
                assertTrue(failure.getMessage().contains(
                        "refuses unexpected out-of-order migrations"));
            }
            assertEquals(0, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version IN ('254.1','254.2','261') AND success"));
            PlatformMigrationObservationSidecar.recordScenario("unexpected-ignored");
        });
    }

    @Test
    @DisplayName("source-digest-drift")
    void controlRejectsInventoryBridgeSourceDigestDriftBeforeExecution()
            throws Exception {
        withDatabase("bridge_digest", database -> {
            try (TemporaryMigrationDirectory legacy = legacyDirectory()) {
                configured(database, legacy.location(), "254", false).migrate();
                configured(database, legacy.location(), "255", true).migrate();
                configured(database, legacy.location(), "260", false).migrate();
            }
            try (TemporaryMigrationDirectory hostile = copyMigrations(Set.of())) {
                Files.writeString(
                        hostile.path().resolve(BRIDGE),
                        Files.readString(hostile.path().resolve(BRIDGE))
                                + "-- hostile source drift\n");
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> FlywayControl.migrateAndValidate(configured(
                                database, hostile.location(), null, false)));
                assertTrue(failure.getMessage().contains(
                        "Platform migration source digest differs"));
            }
            assertEquals(0, integer(database,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version IN ('254.1','261') AND success"));
            PlatformMigrationObservationSidecar.recordScenario("source-digest-drift");
        });
    }

    @Test
    @DisplayName("immutable-predecessors")
    void immutablePredecessorSourcesRetainTheirReviewedBytes() throws Exception {
        assertSource("V255__harden_platform_trigger_execution_boundary.sql",
                4068,
                "183f48e714b932ad50339d8d9c16070c6cc299237eebaa490d076d99e3c68bc4");
        assertSource("V256__govern_audit_retention_execution.sql",
                8007,
                "bdbb4f8b10ef3d732c539f955dbf8a4778c85fb003ae606cea4290b5a4f0732d");
        assertSource("V259__enforce_append_only_platform_audit_evidence.sql",
                1339,
                "4036b0449ad73138ef1895afb6e6324991d4656fcb5c8fb8d377c7ef039bb6e3");
        PlatformMigrationObservationSidecar.recordScenario("immutable-predecessors");
    }

    private static void assertFinalBoundary(String database) throws SQLException {
        int triggerFunctionCount = integer(database,
                "SELECT COUNT(DISTINCT p.oid) FROM pg_trigger t "
                        + "JOIN pg_proc p ON p.oid=t.tgfoid "
                        + "JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE NOT t.tgisinternal AND n.nspname='public'");
        assertEquals(50, triggerFunctionCount);
        assertEquals(0, integer(database,
                "SELECT COUNT(*) FROM pg_trigger t "
                        + "JOIN pg_proc p ON p.oid=t.tgfoid "
                        + "JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE NOT t.tgisinternal AND n.nspname <> 'public'"));
        assertEquals(0, integer(database,
                "SELECT COUNT(*) FROM pg_trigger t "
                        + "JOIN pg_proc p ON p.oid=t.tgfoid "
                        + "JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE NOT t.tgisinternal AND n.nspname='public' AND ("
                        + "NOT p.prosecdef OR p.proconfig IS DISTINCT FROM "
                        + "ARRAY['search_path=pg_catalog, public, pg_temp']::text[] "
                        + "OR EXISTS (SELECT 1 FROM aclexplode(COALESCE(p.proacl, "
                        + "acldefault('f',p.proowner))) a WHERE a.grantee=0 "
                        + "AND a.privilege_type='EXECUTE'))"));
        int finalBridgeSchemaCount = integer(database,
                "SELECT COUNT(*) FROM pg_namespace "
                        + "WHERE nspname='dwp_platform_v255_inventory_bridge'");
        assertEquals(0, finalBridgeSchemaCount);
        int triggerMappingCount = integer(database,
                "SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid "
                        + "JOIN pg_namespace cn ON cn.oid=c.relnamespace "
                        + "JOIN pg_proc p ON p.oid=t.tgfoid "
                        + "JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                        + "WHERE NOT t.tgisinternal AND cn.nspname='public' "
                        + "AND pn.nspname='public'");
        assertEquals(68, triggerMappingCount);
        String triggerMappingDigest = query(database, triggerDigestSql("")).getFirst();
        assertEquals("cade7c2964b47a298bedc9c9d26f5185e5418ec0736db3b0d6ba5a5e7bc2bd8d",
                triggerMappingDigest);
        String bridgePredicate =
                "AND p.proname IN ('wp_assert_delegated_floor_scope',"
                        + "'wp_guard_calendar_facility_booking',"
                        + "'wp_guard_calendar_facility_event',"
                        + "'wp_guard_facility_closed_booking',"
                        + "'wp_lock_facility_closure_resource',"
                        + "'wp_preserve_delegated_floor_identity',"
                        + "'wp_preserve_restricted_delegation_scope')";
        int bridgedMappingCount = integer(database,
                "SELECT COUNT(*) FROM pg_trigger t "
                        + "JOIN pg_class c ON c.oid=t.tgrelid "
                        + "JOIN pg_namespace cn ON cn.oid=c.relnamespace "
                        + "JOIN pg_proc p ON p.oid=t.tgfoid "
                        + "JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                        + "WHERE NOT t.tgisinternal AND cn.nspname='public' "
                        + "AND pn.nspname='public' " + bridgePredicate);
        assertEquals(8, bridgedMappingCount);
        String bridgedMappingDigest = query(
                database, triggerDigestSql(bridgePredicate)).getFirst();
        assertEquals("342b41d9d6d39450b750bbdf63415fa98bb28e133d0138fa435bd82221c0d2af",
                bridgedMappingDigest);
        PlatformMigrationObservationSidecar.recordBoundary(
                new PlatformMigrationObservationSidecar.Boundary(
                        triggerFunctionCount,
                        triggerMappingCount,
                        triggerMappingDigest,
                        bridgedMappingCount,
                        bridgedMappingDigest,
                        finalBridgeSchemaCount));
    }

    private static void assertSource(
            String file, long byteLength, String sha256) throws Exception {
        byte[] source = Files.readAllBytes(platformDirectory().resolve(file));
        assertEquals(byteLength, source.length);
        assertEquals(sha256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source)));
    }

    private static String triggerDigestSql(String additionalPredicate) {
        return "SELECT encode(digest(COALESCE(string_agg(concat_ws(chr(31),"
                + "cn.nspname,c.relname,t.tgname,pn.nspname,p.proname,"
                + "pg_get_function_identity_arguments(p.oid),t.tgenabled::text,"
                + "t.tgtype::text,(t.tgconstraint<>0)::text,t.tgdeferrable::text,"
                + "t.tginitdeferred::text,pg_get_triggerdef(t.oid,true)),chr(30) "
                + "ORDER BY cn.nspname,c.relname,t.tgname,pn.nspname,p.proname,"
                + "pg_get_function_identity_arguments(p.oid),t.tgenabled::text,"
                + "t.tgtype::text,(t.tgconstraint<>0)::text,t.tgdeferrable::text,"
                + "t.tginitdeferred::text,pg_get_triggerdef(t.oid,true)),''),"
                + "'sha256'),'hex') FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_namespace cn ON cn.oid=c.relnamespace "
                + "JOIN pg_proc p ON p.oid=t.tgfoid "
                + "JOIN pg_namespace pn ON pn.oid=p.pronamespace "
                + "WHERE NOT t.tgisinternal AND cn.nspname='public' "
                + "AND pn.nspname='public' " + additionalPredicate;
    }

    private static Flyway current(String database, String target) {
        return configured(database, platformLocation(), target, false);
    }

    private static Flyway configured(
            String database, String location, String target, boolean skip) {
        return configuredAs(database, location, target, skip,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway configuredAs(
            String database,
            String location,
            String target,
            boolean skip,
            String username,
            String password) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(database), username, password)
                .locations(location)
                .defaultSchema("public")
                .schemas("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .cleanDisabled(true)
                .createSchemas(false)
                .validateMigrationNaming(true)
                .sqlMigrationPrefix("V")
                .repeatableSqlMigrationPrefix("R")
                .skipExecutingMigrations(skip)
                .ignoreMigrationPatterns(new String[0]);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private static TemporaryMigrationDirectory legacyDirectory() throws IOException {
        return copyMigrations(LEGACY_EXCLUSIONS);
    }

    private static TemporaryMigrationDirectory versionedDirectory() throws IOException {
        return copyMigrations(Set.of(
                "V261__close_platform_trigger_and_facility_retention_boundaries.sql"));
    }

    private static TemporaryMigrationDirectory copyMigrations(Set<String> excluded)
            throws IOException {
        Path directory = Files.createTempDirectory("platform-migration-proof-");
        try (var paths = Files.list(platformDirectory())) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                if (source.getFileName().toString().endsWith(".sql")
                        && !excluded.contains(source.getFileName().toString())) {
                    Files.copy(source, directory.resolve(source.getFileName()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return new TemporaryMigrationDirectory(directory);
    }

    private static String platformLocation() {
        return "filesystem:" + platformDirectory();
    }

    private static Path platformDirectory() {
        Path path = Path.of(System.getProperty(
                "dwp.test.platform.migration-directory", ""));
        if (!path.isAbsolute() || Files.isSymbolicLink(path)
                || !Files.isDirectory(path)
                || !Files.isRegularFile(path.resolve(BRIDGE))
                || !Files.isRegularFile(path.resolve(
                        "V261__close_platform_trigger_and_facility_retention_boundaries.sql"))) {
            throw new IllegalStateException(
                    "Platform migration test directory is not exact");
        }
        return path;
    }

    private static void withDatabase(String prefix, DatabaseWork work) throws Exception {
        String database = databaseName(prefix);
        createDatabase(database);
        try {
            work.run(database);
        } finally {
            dropDatabase(database);
        }
    }

    private static void withControlledDatabase(
            String prefix, ControlledDatabaseWork work) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String database = databaseName(prefix);
        String migration = "platform_migration_" + suffix;
        String runtime = "platform_runtime_" + suffix;
        String migrationPassword = "migration_password_" + suffix;
        String runtimePassword = "runtime_password_" + suffix;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + quoteIdentifier(migration)
                    + " LOGIN PASSWORD '" + migrationPassword
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + quoteIdentifier(runtime)
                    + " LOGIN PASSWORD '" + runtimePassword
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
        }
        createDatabase(database);
        try {
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("REVOKE ALL ON DATABASE " + quoteIdentifier(database)
                        + " FROM PUBLIC");
                statement.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database)
                        + " TO " + quoteIdentifier(migration) + ','
                        + quoteIdentifier(runtime));
                statement.execute("ALTER SCHEMA public OWNER TO "
                        + quoteIdentifier(migration));
                statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
                statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO "
                        + quoteIdentifier(migration));
                statement.execute("GRANT USAGE ON SCHEMA public TO "
                        + quoteIdentifier(runtime));
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE "
                        + quoteIdentifier(migration)
                        + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
            }
            StreamPlan stream = new StreamPlan(
                    "platform-main", "public", "flyway_schema_history",
                    platformLocation(), true, false);
            ControlPlan platformPlan = ControlPlan.forService("platform");
            ControlPlan plan = new ControlPlan(
                    "platform", List.of(stream), null, List.of(), List.of(),
                    platformPlan.temporaryMigrationVersions());
            ControlEnvironment environment = new ControlEnvironment(
                    ControlEnvironment.Mode.STRICT_FRESH,
                    plan,
                    jdbcUrl(database),
                    database,
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword(),
                    migration,
                    migrationPassword,
                    runtime,
                    runtimePassword,
                    "dwp-migration-control-v2:" + "a".repeat(64),
                    Map.of(), "", Map.of(), "");
            work.run(new Controlled(
                    database, migration, migrationPassword, runtime, runtimePassword,
                    environment));
        } finally {
            dropDatabase(database);
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP ROLE IF EXISTS " + quoteIdentifier(runtime));
                statement.execute("DROP ROLE IF EXISTS " + quoteIdentifier(migration));
            }
        }
    }

    private static boolean databaseCreate(Controlled controlled) throws SQLException {
        try (Connection connection = controlled.bootstrapConnection();
                var statement = connection.prepareStatement(
                        "SELECT has_database_privilege(?,current_database(),'CREATE')")) {
            statement.setString(1, controlled.migration());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean databaseTemporary(Controlled controlled) throws SQLException {
        try (Connection connection = controlled.bootstrapConnection();
                var statement = connection.prepareStatement(
                        "SELECT has_database_privilege(?,current_database(),'TEMPORARY')")) {
            statement.setString(1, controlled.migration());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void createDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(database));
        }
    }

    private static void dropDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(database)
                    + " WITH (FORCE)");
        }
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ':'
                + POSTGRES.getFirstMappedPort() + '/' + database;
    }

    private static int integer(String database, String sql) throws SQLException {
        return Integer.parseInt(query(database, sql).getFirst());
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> query(String database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            ResultSetMetaData metadata = resultSet.getMetaData();
            while (resultSet.next()) {
                String[] values = new String[metadata.getColumnCount()];
                for (int index = 0; index < values.length; index++) {
                    values[index] = String.valueOf(resultSet.getObject(index + 1));
                }
                rows.add(String.join("|", values));
            }
            return List.copyOf(rows);
        }
    }

    private static String quoteIdentifier(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String databaseName(String prefix) {
        return "platform_" + prefix + '_'
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String postgresImage() {
        String image = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine");
        if (!Set.of("postgres:16-alpine", "postgres:18.4-alpine").contains(image)) {
            throw new IllegalStateException("Unapproved Platform migration proof image");
        }
        return image;
    }

    @FunctionalInterface
    private interface DatabaseWork {
        void run(String database) throws Exception;
    }

    @FunctionalInterface
    private interface ControlledDatabaseWork {
        void run(Controlled controlled) throws Exception;
    }

    private record Controlled(
            String database,
            String migration,
            String migrationPassword,
            String runtime,
            String runtimePassword,
            ControlEnvironment environment) {
        Connection bootstrapConnection() throws SQLException {
            return DriverManager.getConnection(
                    jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }

    private record TemporaryMigrationDirectory(Path path) implements AutoCloseable {
        String location() {
            return "filesystem:" + path;
        }

        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(path)) {
                for (Path candidate : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(candidate);
                }
            }
        }
    }
}
