package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact database-session and database-ACL boundary for an offline Control run. */
final class DatabaseConnectionFence {

    private static final int SESSION_DRAIN_ATTEMPTS = 41;
    private static final long SESSION_DRAIN_INTERVAL_MILLIS = 25L;

    enum State {
        BASELINE,
        ACTIVE,
        INSPECTION,
        RESTORED,
        FAILED
    }

    private DatabaseConnectionFence() {
    }

    static void requireNoForeignSessions(
            Connection connection, ControlEnvironment environment) throws SQLException {
        for (int attempt = 0; attempt < SESSION_DRAIN_ATTEMPTS; attempt++) {
            if (foreignSessions(connection).isEmpty()) {
                return;
            }
            if (attempt + 1 < SESSION_DRAIN_ATTEMPTS) {
                try {
                    Thread.sleep(SESSION_DRAIN_INTERVAL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Migration Control session drain was interrupted");
                }
            }
        }
        throw new IllegalStateException(
                "Migration Control requires every non-Control database session offline");
    }

    private static List<String> foreignSessions(Connection connection) throws SQLException {
        List<String> sessions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_stat_clear_snapshot()")) {
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(usename, '<unknown>') || ':' || pid::text
                  FROM pg_catalog.pg_stat_activity
                 WHERE datname=current_database()
                   AND pid<>pg_backend_pid()
                 ORDER BY usename, pid
                """);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                sessions.add(result.getString(1));
            }
        }
        return List.copyOf(sessions);
    }

    static void requireDatabaseAcl(
            Connection connection,
            ControlEnvironment environment,
            State state) throws SQLException {
        requireDatabaseAcl(connection, environment, state, null);
    }

    static void requireInspectionDatabaseAcl(
            Connection connection,
            ControlEnvironment environment,
            String probePrincipal) throws SQLException {
        if (!environment.runtimePrincipal().equals(probePrincipal)
                && !environment.plan().fencedReadOnlyPrincipals().contains(probePrincipal)) {
            throw new IllegalStateException("Inspection principal is not registered");
        }
        if (environment.migrationPrincipal().equals(probePrincipal)
                || environment.bootstrapPrincipal().equals(probePrincipal)) {
            throw new IllegalStateException("Inspection cannot use an owner principal");
        }
        requireDatabaseAcl(connection, environment, State.INSPECTION, probePrincipal);
    }

    private static void requireDatabaseAcl(
            Connection connection,
            ControlEnvironment environment,
            State state,
            String probePrincipal) throws SQLException {
        Set<String> actual = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(grantee.rolname, 'PUBLIC'),
                       acl.privilege_type,
                       acl.is_grantable,
                       grantor.rolname
                  FROM pg_catalog.pg_database database
                 CROSS JOIN LATERAL pg_catalog.aclexplode(
                     COALESCE(database.datacl,
                              pg_catalog.acldefault('d', database.datdba))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                  JOIN pg_catalog.pg_roles grantor ON grantor.oid=acl.grantor
                 WHERE database.datname=current_database()
                 ORDER BY 1, 2, 3, 4
                """);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                actual.add(row(
                        result.getString(1),
                        result.getString(2),
                        result.getBoolean(3),
                        result.getString(4)));
            }
        }
        Set<String> expected = expectedAcl(environment, state, probePrincipal);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control database ACL differs from the exact "
                            + state.name().toLowerCase(java.util.Locale.ROOT)
                            + " fence; expected=" + expected + "; actual=" + actual);
        }
        requireExactPublisherConnectivity(connection, environment, state);
    }

    private static void requireExactPublisherConnectivity(
            Connection connection,
            ControlEnvironment environment,
            State state) throws SQLException {
        if (!environment.hasProjectionPublisher()) {
            return;
        }
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT database.datname
                  FROM pg_catalog.pg_database database
                 WHERE database.datallowconn
                   AND pg_catalog.has_database_privilege(
                       ?, database.oid, 'CONNECT')
                 ORDER BY database.datname
                """)) {
            statement.setString(1, environment.projectionPublisherPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        List<String> expected = switch (state) {
            case BASELINE, RESTORED -> List.of(environment.database());
            case ACTIVE, INSPECTION, FAILED -> List.of();
        };
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control projection publisher CONNECT surface is not exact; "
                            + "expected=" + expected + "; actual=" + actual);
        }
    }

    private static Set<String> expectedAcl(
            ControlEnvironment environment, State state, String probePrincipal) {
        if (state == State.INSPECTION && probePrincipal == null) {
            throw new IllegalStateException("Inspection requires an exact registered principal");
        }
        Set<String> expected = new LinkedHashSet<>();
        String owner = environment.bootstrapPrincipal();
        for (String privilege : List.of("CONNECT", "CREATE", "TEMPORARY")) {
            expected.add(row(owner, privilege, false, owner));
        }
        for (String principal : servicePrincipals(environment)) {
            boolean connected = switch (state) {
                case BASELINE, RESTORED -> true;
                case ACTIVE -> principal.equals(environment.migrationPrincipal());
                case INSPECTION -> principal.equals(probePrincipal);
                case FAILED -> false;
            };
            if (connected) {
                expected.add(row(principal, "CONNECT", false, owner));
            }
        }
        return Set.copyOf(expected);
    }

    private static List<String> servicePrincipals(ControlEnvironment environment) {
        List<String> principals = new ArrayList<>();
        principals.add(environment.runtimePrincipal());
        principals.add(environment.migrationPrincipal());
        principals.addAll(environment.plan().fencedReadOnlyPrincipals());
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }
        return List.copyOf(principals);
    }

    private static String row(
            String grantee, String privilege, boolean grantable, String grantor) {
        return grantee + ":" + privilege + ":" + grantable + ":" + grantor;
    }
}
