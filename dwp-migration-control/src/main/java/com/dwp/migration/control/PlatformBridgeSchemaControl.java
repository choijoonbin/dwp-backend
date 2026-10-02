package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;

/**
 * Pre-provisions the exact transitional schema needed by Platform V254.1.
 *
 * <p>The bootstrap owner, not the NOCREATEDB migration login, creates the empty
 * schema. The migration owns only that schema and never receives database
 * CREATE. V254.1 validates the exact pre-state and V261 removes the schema.</p>
 */
final class PlatformBridgeSchemaControl {
    private static final String SCHEMA = "dwp_platform_v255_inventory_bridge";
    private static final String BRIDGE_VERSION = "254.1";
    private static final String BRIDGE_FILE =
            "V254_1__bridge_platform_trigger_inventory_before_v255.sql";
    private static final List<String> BRIDGED_FUNCTIONS = List.of(
            "wp_assert_delegated_floor_scope",
            "wp_guard_calendar_facility_booking",
            "wp_guard_calendar_facility_event",
            "wp_guard_facility_closed_booking",
            "wp_lock_facility_closure_resource",
            "wp_preserve_delegated_floor_identity",
            "wp_preserve_restricted_delegation_scope");
    private static final List<Extension> REQUIRED_EXTENSIONS = List.of(
            new Extension("btree_gist", "1.7"),
            new Extension("pgcrypto", "1.3"));

    private PlatformBridgeSchemaControl() {
    }

    static void prepare(
            ControlEnvironment environment, StreamPlan stream) throws Exception {
        if (!"platform".equals(environment.plan().service())) {
            return;
        }
        Flyway latest = FlywayControl.load(environment, stream, null);
        PlatformInventoryBridgeControl.requireExactSources(latest);
        try (Connection bootstrap = bootstrapConnection(environment)) {
            DatabaseControl.verifyIdentity(bootstrap, environment);
            DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
            boolean historyExists = DatabaseControl.historyExists(bootstrap, stream);
            if (!historyExists) {
                requireFreshDatabaseClean(bootstrap, environment, stream);
            }
            provisionRequiredExtensions(bootstrap, environment);
            State state = state(bootstrap, stream);
            requireStateSchema(bootstrap, environment, state);
            if (state.historyExists()) {
                return;
            }
        }
    }

    private static void requireFreshDatabaseClean(
            Connection connection,
            ControlEnvironment environment,
            StreamPlan stream) throws SQLException {
        long unexpectedExtensions = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_extension
                 WHERE extname NOT IN ('plpgsql', 'btree_gist', 'pgcrypto')
                """);
        if (unexpectedExtensions != 0L) {
            throw new IllegalStateException(
                    "Platform fresh migration refuses an unknown extension");
        }
        DatabaseControl.requireFreshSchemaClean(
                connection, stream, environment.migrationPrincipal());
        long unexpectedSchemas = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_namespace
                 WHERE nspname <> 'public'
                   AND nspname <> 'information_schema'
                   AND nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                """);
        if (unexpectedSchemas != 0L) {
            throw new IllegalStateException(
                    "Platform fresh migration refuses an unknown database schema");
        }
    }

    static void applyPending(
            ControlEnvironment environment, StreamPlan stream) throws Exception {
        if (!"platform".equals(environment.plan().service())) {
            return;
        }
        Flyway latest = FlywayControl.load(environment, stream, null);
        PlatformInventoryBridgeControl.requireExactSources(latest);

        State state;
        try (Connection bootstrap = bootstrapConnection(environment)) {
            DatabaseControl.verifyIdentity(bootstrap, environment);
            DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
            provisionRequiredExtensions(bootstrap, environment);
            state = state(bootstrap, stream);
            requireStateSchema(bootstrap, environment, state);
        }
        if (state.bridgeApplied() || state.v255Applied()) {
            return;
        }
        if (!state.historyExists()) {
            throw new IllegalStateException(
                    "Platform V254.1 bridge requires the reviewed sequential predecessor");
        }

        FlywayControl.migrateAndValidate(
                FlywayControl.load(
                        environment, stream, MigrationVersion.fromVersion("254")),
                false);
        try (Connection bootstrap = bootstrapConnection(environment)) {
            State predecessor = state(bootstrap, stream);
            if (!predecessor.historyExists()
                    || predecessor.bridgeApplied()
                    || predecessor.v255Applied()
                    || !DatabaseControl.versionApplied(bootstrap, stream, "254")) {
                throw new IllegalStateException(
                        "Platform V254.1 predecessor state is not exact");
            }
            provisionEmptySchema(bootstrap, environment);
        }
        Flyway bridge = FlywayControl.load(
                environment, stream, MigrationVersion.fromVersion(BRIDGE_VERSION));
        requireOnlyPending(bridge, BRIDGE_VERSION, BRIDGE_FILE);
        try {
            FlywayControl.migrateAndValidate(bridge, false);
        } catch (Exception failure) {
            cleanupEmptySchema(environment, failure);
            throw failure;
        }
        try (Connection bootstrap = bootstrapConnection(environment)) {
            requirePopulatedSchema(bootstrap, environment);
            DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
            requireExactSuccessfulHistory(
                    bootstrap, stream, BRIDGE_VERSION, BRIDGE_FILE, "SQL");
        }
    }

    private static void requireOnlyPending(
            Flyway flyway,
            String version,
            String file) {
        List<MigrationInfo> pending = Arrays.asList(flyway.info().pending());
        if (pending.size() != 1
                || !MigrationVersion.fromVersion(version).equals(
                        pending.getFirst().getVersion())
                || !file.equals(pending.getFirst().getScript())
                || pending.getFirst().getType().isBaseline()) {
            throw new IllegalStateException(
                    "Platform transitional migration selection is not exact: "
                            + pending.stream().map(MigrationInfo::getScript).toList());
        }
    }

    private static State state(Connection connection, StreamPlan stream)
            throws SQLException {
        boolean history = DatabaseControl.historyExists(connection, stream);
        boolean bridge = history && DatabaseControl.versionApplied(
                connection, stream, BRIDGE_VERSION);
        boolean v255 = history && DatabaseControl.versionApplied(connection, stream, "255");
        return new State(
                history,
                bridge,
                v255,
                bridge && v255
                        && installedRank(connection, stream, BRIDGE_VERSION)
                        < installedRank(connection, stream, "255"),
                history && DatabaseControl.versionApplied(connection, stream, "261"),
                schemaExists(connection));
    }

    private static void requireStateSchema(
            Connection connection,
            ControlEnvironment environment,
            State state) throws SQLException {
        if (state.v261Applied()) {
            requireSchemaAbsent(state, "Platform V261 history retains the transitional schema");
            return;
        }
        if (state.bridgeApplied()
                && (!state.v255Applied() || state.bridgeBeforeV255())) {
            requirePopulatedSchema(connection, environment);
            return;
        }
        if (state.v255Applied()) {
            requireSchemaAbsent(
                    state,
                    "Platform V255+ no-op bridge path has a transitional schema");
            return;
        }
        if (state.schemaExists()) {
            requireEmptySchema(connection, environment);
        }
    }

    private static void requireSchemaAbsent(State state, String message) {
        if (state.schemaExists()) {
            throw new IllegalStateException(message);
        }
    }

    private static void provisionEmptySchema(
            Connection connection, ControlEnvironment environment) throws SQLException {
        DatabaseControl.requireDatabaseCreateDenied(connection, environment);
        if (!schemaExists(connection)) {
            DatabaseControl.execute(connection, "CREATE SCHEMA " + quoteIdentifier(SCHEMA)
                    + " AUTHORIZATION "
                    + quoteIdentifier(environment.migrationPrincipal()));
            DatabaseControl.execute(connection, "REVOKE ALL ON SCHEMA "
                    + quoteIdentifier(SCHEMA) + " FROM PUBLIC");
        }
        requireEmptySchema(connection, environment);
        DatabaseControl.requireDatabaseCreateDenied(connection, environment);
    }

    static void provisionRequiredExtensions(
            Connection connection, ControlEnvironment environment) throws SQLException {
        requireAutoCommit(connection);
        List<Extension> missing = new ArrayList<>();
        for (Extension extension : REQUIRED_EXTENSIONS) {
            if (extensionExists(connection, extension.name())) {
                requireExactExtension(connection, environment, extension);
            } else {
                missing.add(extension);
            }
        }
        try {
            if (!missing.isEmpty()) {
                connection.setAutoCommit(false);
                DatabaseControl.execute(connection, "GRANT CREATE ON DATABASE "
                        + quoteIdentifier(environment.database()) + " TO "
                        + quoteIdentifier(environment.migrationPrincipal()));
                DatabaseControl.execute(connection, "SET LOCAL ROLE "
                        + quoteIdentifier(environment.migrationPrincipal()));
                for (Extension extension : missing) {
                    DatabaseControl.execute(connection, "CREATE EXTENSION "
                            + quoteIdentifier(extension.name())
                            + " WITH SCHEMA public VERSION "
                            + quoteLiteral(extension.version()));
                }
                DatabaseControl.execute(connection, "RESET ROLE");
                DatabaseControl.execute(connection, "REVOKE CREATE ON DATABASE "
                        + quoteIdentifier(environment.database()) + " FROM "
                        + quoteIdentifier(environment.migrationPrincipal()));
                connection.commit();
            }
        } catch (SQLException failure) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            throw failure;
        } finally {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
        }
        DatabaseControl.requireDatabaseCreateDenied(connection, environment);
        for (Extension extension : REQUIRED_EXTENSIONS) {
            requireExactExtension(connection, environment, extension);
        }
    }

    private static void requireExactExtension(
            Connection connection,
            ControlEnvironment environment,
            Extension extension) throws SQLException {
        long exact = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_extension e
                  JOIN pg_catalog.pg_namespace n ON n.oid=e.extnamespace
                  JOIN pg_catalog.pg_roles owner_role ON owner_role.oid=e.extowner
                 WHERE e.extname=%1$s
                   AND e.extversion=%2$s
                   AND n.nspname='public'
                   AND owner_role.rolname=%3$s
                """.formatted(
                        quoteLiteral(extension.name()),
                        quoteLiteral(extension.version()),
                        quoteLiteral(environment.migrationPrincipal())));
        if (exact != 1L) {
            throw new IllegalStateException(
                    "Platform required extension state differs: " + extension.name());
        }
    }

    private static boolean extensionExists(Connection connection, String name)
            throws SQLException {
        return DatabaseControl.scalarLong(connection,
                "SELECT COUNT(*) FROM pg_catalog.pg_extension WHERE extname="
                        + quoteLiteral(name)) == 1L;
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "Platform extension provisioning requires auto-commit entry state");
        }
    }

    private static void requireEmptySchema(
            Connection connection, ControlEnvironment environment) throws SQLException {
        requireSchemaOwnerAcl(connection, environment);
        long objects = DatabaseControl.scalarLong(connection, """
                SELECT (SELECT COUNT(*) FROM pg_catalog.pg_class c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_proc p
                         JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_type t
                         JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_operator o
                         JOIN pg_catalog.pg_namespace n ON n.oid=o.oprnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_collation c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.collnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_conversion c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.connamespace
                        WHERE n.nspname=%1$s)
                """.formatted(quoteLiteral(SCHEMA)));
        if (objects != 0L) {
            throw new IllegalStateException(
                    "Platform transitional bridge schema is not empty");
        }
    }

    private static void requirePopulatedSchema(
            Connection connection, ControlEnvironment environment) throws SQLException {
        requireSchemaOwnerAcl(connection, environment);
        String names = DatabaseControl.scalar(connection, """
                SELECT COALESCE(string_agg(p.proname::text, ',' ORDER BY p.proname), '')
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                 WHERE n.nspname=%s AND p.pronargs=0
                """.formatted(quoteLiteral(SCHEMA)));
        if (!String.join(",", BRIDGED_FUNCTIONS).equals(names)) {
            throw new IllegalStateException(
                    "Platform transitional bridge function inventory differs");
        }
        long otherObjects = DatabaseControl.scalarLong(connection, """
                SELECT (SELECT COUNT(*) FROM pg_catalog.pg_class c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_type t
                         JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_operator o
                         JOIN pg_catalog.pg_namespace n ON n.oid=o.oprnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_collation c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.collnamespace
                        WHERE n.nspname=%1$s)
                     + (SELECT COUNT(*) FROM pg_catalog.pg_conversion c
                         JOIN pg_catalog.pg_namespace n ON n.oid=c.connamespace
                        WHERE n.nspname=%1$s)
                """.formatted(quoteLiteral(SCHEMA)));
        if (otherObjects != 0L) {
            throw new IllegalStateException(
                    "Platform transitional bridge schema contains another object class");
        }
    }

    private static void requireSchemaOwnerAcl(
            Connection connection, ControlEnvironment environment) throws SQLException {
        long exact = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_namespace n
                  JOIN pg_catalog.pg_roles owner_role ON owner_role.oid=n.nspowner
                 WHERE n.nspname=%1$s
                   AND owner_role.rolname=%2$s
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.aclexplode(COALESCE(
                           n.nspacl,pg_catalog.acldefault('n',n.nspowner))) acl
                        WHERE acl.grantee<>n.nspowner)
                """.formatted(
                        quoteLiteral(SCHEMA),
                        quoteLiteral(environment.migrationPrincipal())));
        if (exact != 1L) {
            throw new IllegalStateException(
                    "Platform transitional bridge schema owner/ACL differs");
        }
    }

    private static void requireExactSuccessfulHistory(
            Connection connection,
            StreamPlan stream,
            String version,
            String file,
            String type) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM %s.%s
                 WHERE version=? AND script=? AND type=? AND success
                """.formatted(
                        quoteIdentifier(stream.schema()),
                        quoteIdentifier(stream.historyTable())))) {
            statement.setString(1, version);
            statement.setString(2, file);
            statement.setString(3, type);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 1L) {
                    throw new IllegalStateException(
                            "Platform transitional migration history is not exact");
                }
            }
        }
    }

    private static int installedRank(
            Connection connection, StreamPlan stream, String version) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT installed_rank
                  FROM %s.%s
                 WHERE version=? AND success
                """.formatted(
                        quoteIdentifier(stream.schema()),
                        quoteIdentifier(stream.historyTable())))) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Platform transitional history rank is missing");
                }
                int rank = result.getInt(1);
                if (result.next()) {
                    throw new IllegalStateException(
                            "Platform transitional history rank is not unique");
                }
                return rank;
            }
        }
    }

    private static void cleanupEmptySchema(
            ControlEnvironment environment, Exception migrationFailure) {
        try (Connection bootstrap = bootstrapConnection(environment)) {
            cleanupEmptySchema(bootstrap, environment);
        } catch (Exception cleanupFailure) {
            migrationFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void cleanupEmptySchema(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        if (schemaExists(bootstrap)) {
            requireEmptySchema(bootstrap, environment);
            DatabaseControl.execute(bootstrap,
                    "DROP SCHEMA " + quoteIdentifier(SCHEMA) + " RESTRICT");
        }
        DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
    }

    private static boolean schemaExists(Connection connection) throws SQLException {
        return DatabaseControl.scalarLong(connection, "SELECT COUNT(*) "
                + "FROM pg_catalog.pg_namespace WHERE nspname="
                + quoteLiteral(SCHEMA)) == 1L;
    }

    private static Connection bootstrapConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.bootstrapPrincipal(),
                environment.bootstrapPassword());
    }

    private record State(
            boolean historyExists,
            boolean bridgeApplied,
            boolean v255Applied,
            boolean bridgeBeforeV255,
            boolean v261Applied,
            boolean schemaExists) {
    }

    private record Extension(String name, String version) {
    }
}
