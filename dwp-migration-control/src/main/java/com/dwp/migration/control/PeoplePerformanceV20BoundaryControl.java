package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/** Exact runtime-role bridge and final ACL fence for People Performance V20. */
final class PeoplePerformanceV20BoundaryControl {
    static final String VERSION = "20";

    private static final String SERVICE = "people";
    private static final String STREAM = "people-performance";
    private static final String SCHEMA = "hris_performance";

    private static final List<String> V1_SEAM_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    private static final List<String> V20_MUTABLE_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments");
    private static final List<String> V20_APPEND_ONLY_TABLES = List.of(
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");
    private static final List<String> V20_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");
    private static final List<String> V20_SEQUENCES = List.of(
            "prf_feedback_requests_request_internal_id_seq",
            "prf_feedback_assignments_assignment_internal_id_seq",
            "prf_feedback_responses_response_internal_id_seq",
            "prf_feedback_release_snapshots_snapshot_internal_id_seq");
    private static final List<String> RLS_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events",
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");

    private PeoplePerformanceV20BoundaryControl() {
    }

    /**
     * Stages a pending Performance V20 behind an exact configured-runtime grant.
     * Returns true only when this call applied V20. Missing or already-applied V20 is a no-op.
     */
    static boolean stagePendingV20(
            ControlEnvironment environment, StreamPlan stream) throws Exception {
        if (!appliesTo(environment, stream)) {
            return false;
        }
        Flyway discovered = FlywayControl.load(environment, stream, null);
        if (!containsV20(discovered)) {
            return false;
        }
        try (Connection bootstrap = bootstrapConnection(environment)) {
            if (DatabaseControl.versionApplied(bootstrap, stream, VERSION)) {
                return false;
            }
        }

        FlywayControl.migrateAndValidate(
                FlywayControl.load(
                        environment,
                        stream,
                        FlywayControl.predecessorVersion(environment, stream, VERSION)),
                false);

        try (Connection bootstrap = bootstrapConnection(environment)) {
            transactional(bootstrap, () -> establishAndAttestV1RuntimeGrant(
                    bootstrap, environment));
        }

        FlywayControl.migrateAndValidate(
                FlywayControl.load(
                        environment, stream, MigrationVersion.fromVersion(VERSION)),
                false);
        try (Connection bootstrap = bootstrapConnection(environment)) {
            if (!DatabaseControl.versionApplied(bootstrap, stream, VERSION)) {
                throw new IllegalStateException(
                        "People Performance V20 did not reach the applied state");
            }
        }
        return true;
    }

    /** Restores V20's exact ACL after generic domain normalization and verifies it. */
    static void restoreAndVerifyFinalBoundary(
            Connection connection, ControlEnvironment environment) throws SQLException {
        StreamPlan stream = performanceStream(environment);
        if (stream == null || !DatabaseControl.versionApplied(connection, stream, VERSION)) {
            return;
        }

        DatabaseControl.requireBootstrapIdentity(connection, environment);
        DatabaseControl.requireStrictServiceLoginRoles(connection, environment);
        RoleMembershipFence.requireExact(connection, environment);

        String runtime = quoteIdentifier(environment.runtimePrincipal());
        DatabaseControl.execute(connection, "REVOKE ALL PRIVILEGES ON TABLE "
                + qualified(V20_TABLES) + " FROM PUBLIC, " + runtime);
        DatabaseControl.execute(connection, "GRANT SELECT, INSERT, UPDATE ON TABLE "
                + qualified(V20_MUTABLE_TABLES) + " TO " + runtime);
        DatabaseControl.execute(connection, "GRANT SELECT, INSERT ON TABLE "
                + qualified(V20_APPEND_ONLY_TABLES) + " TO " + runtime);

        DatabaseControl.execute(connection, "REVOKE ALL PRIVILEGES ON SEQUENCE "
                + qualified(V20_SEQUENCES) + " FROM PUBLIC, " + runtime);
        DatabaseControl.execute(connection, "GRANT USAGE, SELECT ON SEQUENCE "
                + qualified(V20_SEQUENCES) + " TO " + runtime);

        requireExactRuntimeAcl(connection, environment.runtimePrincipal());
        requirePublicDenied(connection);
        requireAllEightTablesForceRls(connection);
    }

    static boolean appliesTo(ControlEnvironment environment, StreamPlan stream) {
        return SERVICE.equals(environment.plan().service())
                && STREAM.equals(stream.streamKey())
                && SCHEMA.equals(stream.schema());
    }

    private static boolean containsV20(Flyway flyway) {
        MigrationVersion target = MigrationVersion.fromVersion(VERSION);
        return Arrays.stream(flyway.info().all())
                .anyMatch(migration -> target.equals(migration.getVersion()));
    }

    private static StreamPlan performanceStream(ControlEnvironment environment) {
        if (!SERVICE.equals(environment.plan().service())) {
            return null;
        }
        return environment.plan().streams().stream()
                .filter(stream -> STREAM.equals(stream.streamKey())
                        && SCHEMA.equals(stream.schema()))
                .findFirst()
                .orElse(null);
    }

    private static void establishAndAttestV1RuntimeGrant(
            Connection connection, ControlEnvironment environment) throws SQLException {
        DatabaseControl.requireBootstrapIdentity(connection, environment);
        DatabaseControl.requireStrictServiceLoginRoles(connection, environment);
        RoleMembershipFence.requireExact(connection, environment);
        DatabaseControl.execute(connection,
                "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "
                        + qualified(V1_SEAM_TABLES) + " TO "
                        + quoteIdentifier(environment.runtimePrincipal()));

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH direct_dml AS (
                    SELECT acl.grantee, relation.relname, acl.privilege_type
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(
                          COALESCE(relation.relacl,
                                   pg_catalog.acldefault('r', relation.relowner))) acl
                     WHERE namespace.nspname = 'hris_performance'
                       AND relation.relname IN (
                           'prf_cycles', 'prf_cycle_versions',
                           'prf_command_receipts', 'prf_outbox_events')
                       AND relation.relkind IN ('r', 'p')
                       AND acl.grantee <> 0
                       AND acl.grantee <> relation.relowner
                ), common_dml AS (
                    SELECT grantee
                      FROM direct_dml
                     GROUP BY grantee
                    HAVING COUNT(DISTINCT relname)
                               FILTER (WHERE privilege_type = 'SELECT') = 4
                       AND COUNT(DISTINCT relname)
                               FILTER (WHERE privilege_type = 'INSERT') = 4
                       AND COUNT(DISTINCT relname)
                               FILTER (WHERE privilege_type = 'UPDATE') = 4
                       AND COUNT(DISTINCT relname)
                               FILTER (WHERE privilege_type = 'DELETE') = 4
                ), safe_candidates AS (
                    SELECT role.rolname
                      FROM common_dml candidate
                      JOIN pg_catalog.pg_roles role ON role.oid = candidate.grantee
                     WHERE NOT role.rolsuper
                       AND NOT role.rolcreaterole
                       AND NOT role.rolcreatedb
                       AND NOT role.rolreplication
                       AND NOT role.rolbypassrls
                )
                SELECT COUNT(*), MIN(rolname)
                  FROM safe_candidates
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getInt(1) != 1
                        || !environment.runtimePrincipal().equals(result.getString(2))) {
                    throw new IllegalStateException(
                            "People Performance V20 runtime grant attestation failed");
                }
            }
        }
    }

    private static void requireExactRuntimeAcl(
            Connection connection, String runtimePrincipal) throws SQLException {
        Set<String> expectedTables = new TreeSet<>();
        for (String table : V20_MUTABLE_TABLES) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
                expectedTables.add(table + ":" + privilege);
            }
        }
        for (String table : V20_APPEND_ONLY_TABLES) {
            for (String privilege : List.of("SELECT", "INSERT")) {
                expectedTables.add(table + ":" + privilege);
            }
        }
        Set<String> actualTables = directPrivileges(
                connection, runtimePrincipal, V20_TABLES, "'r', 'p'");
        if (!expectedTables.equals(actualTables)) {
            throw new IllegalStateException(
                    "People Performance V20 runtime table ACL is not exact");
        }

        Set<String> expectedSequences = new TreeSet<>();
        for (String sequence : V20_SEQUENCES) {
            expectedSequences.add(sequence + ":SELECT");
            expectedSequences.add(sequence + ":USAGE");
        }
        Set<String> actualSequences = directPrivileges(
                connection, runtimePrincipal, V20_SEQUENCES, "'S'");
        if (!expectedSequences.equals(actualSequences)) {
            throw new IllegalStateException(
                    "People Performance V20 runtime sequence ACL is not exact");
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                  JOIN pg_catalog.pg_roles role ON role.oid = acl.grantee
                 WHERE namespace.nspname = 'hris_performance'
                   AND relation.relname IN (
                       'prf_feedback_requests', 'prf_feedback_assignments',
                       'prf_feedback_responses', 'prf_feedback_release_snapshots')
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                   AND role.rolname = ?
                """)) {
            statement.setString(1, runtimePrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0L) {
                    throw new IllegalStateException(
                            "People Performance V20 runtime column ACL is not empty");
                }
            }
        }
    }

    private static Set<String> directPrivileges(
            Connection connection,
            String principal,
            List<String> objects,
            String relationKinds) throws SQLException {
        Set<String> privileges = new TreeSet<>();
        String sql = """
                SELECT relation.relname, acl.privilege_type, acl.is_grantable
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(relation.relacl, '{}'::pg_catalog.aclitem[])) acl
                  JOIN pg_catalog.pg_roles role ON role.oid = acl.grantee
                 WHERE namespace.nspname = 'hris_performance'
                   AND relation.relname IN (%s)
                   AND relation.relkind IN (%s)
                   AND role.rolname = ?
                """.formatted(literals(objects), relationKinds);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String privilege = result.getString(1) + ":" + result.getString(2);
                    if (result.getBoolean(3)) {
                        privilege += ":GRANTABLE";
                    }
                    privileges.add(privilege);
                }
            }
        }
        return privileges;
    }

    private static void requirePublicDenied(Connection connection) throws SQLException {
        long publicPrivileges = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(relation.relacl, '{}'::pg_catalog.aclitem[])) acl
                 WHERE namespace.nspname = 'hris_performance'
                   AND relation.relname IN (%s)
                   AND relation.relkind IN ('r', 'p', 'S')
                   AND acl.grantee = 0
                """.formatted(literals(concat(V20_TABLES, V20_SEQUENCES))));
        if (publicPrivileges != 0L) {
            throw new IllegalStateException(
                    "People Performance V20 exposes privileges to PUBLIC");
        }
    }

    private static void requireAllEightTablesForceRls(Connection connection)
            throws SQLException {
        long invalid = DatabaseControl.scalarLong(connection, """
                SELECT COUNT(*)
                  FROM (VALUES %s) expected(table_name)
                  LEFT JOIN pg_catalog.pg_namespace namespace
                    ON namespace.nspname = 'hris_performance'
                  LEFT JOIN pg_catalog.pg_class relation
                    ON relation.relnamespace = namespace.oid
                   AND relation.relname = expected.table_name
                   AND relation.relkind IN ('r', 'p')
                 WHERE relation.oid IS NULL
                    OR NOT relation.relrowsecurity
                    OR NOT relation.relforcerowsecurity
                """.formatted(values(RLS_TABLES)));
        if (invalid != 0L) {
            throw new IllegalStateException(
                    "People Performance V20 FORCE RLS boundary is not exact");
        }
    }

    private static String qualified(List<String> objects) {
        return objects.stream()
                .map(object -> quoteIdentifier(SCHEMA) + "." + quoteIdentifier(object))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String literals(List<String> values) {
        return values.stream()
                .map(ControlValues::quoteLiteral)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String values(List<String> values) {
        return values.stream()
                .map(value -> "(" + quoteLiteral(value) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        java.util.ArrayList<String> combined = new java.util.ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static Connection bootstrapConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.bootstrapPrincipal(),
                environment.bootstrapPassword());
    }

    private static void transactional(Connection connection, SqlWork work)
            throws Exception {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }
}
